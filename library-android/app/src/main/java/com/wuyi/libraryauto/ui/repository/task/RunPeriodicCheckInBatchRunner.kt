package com.wuyi.libraryauto.ui.repository.task

import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.model.SignInAttemptResult
import com.wuyi.libraryauto.core.domain.model.SignInError
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAccount
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAccountResult
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAccountSource
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAccountStatus
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAttempt
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInEventLogger
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInReservation
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInReservationSource
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInSignInExecutor
import com.wuyi.libraryauto.core.domain.usecase.RunPeriodicCheckInUseCase
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInRunGate
import com.wuyi.libraryauto.core.storage.audit.SignInAuditRepository
import com.wuyi.libraryauto.core.storage.audit.SignInAuditWrite
import com.wuyi.libraryauto.core.storage.db.ExecutionLogDao
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInProgressWriter
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInReport
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInRowState
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInRowStatus

class RunPeriodicCheckInBatchRunner(
    private val accountSource: StoredAccountSource,
    private val sessionRepository: SessionRepository,
    private val reservationTaskDao: ReservationTaskDao,
    private val accountSeatActionExecutor: AccountSeatActionExecutor,
    private val signInAuditRepository: SignInAuditRepository? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val runGate: PeriodicCheckInRunGate = PeriodicCheckInRunGate.shared,
) : com.wuyi.libraryauto.ui.viewmodel.BatchCheckInRunner {
    override suspend fun runBatch(onProgress: suspend (BatchCheckInReport) -> Unit): BatchCheckInReport {
        // BUG 6 修复：标记批量签到正在运行，让 PeriodicCheckInWorker.runOnce 在此期间跳过执行，
        // 避免周期 worker 与 UI 批量签到对同一 booking 同时发起 HTTP。
        runGate.markBatchCheckInRunning(true)
        return try {
            runBatchInternal(onProgress)
        } finally {
            runGate.markBatchCheckInRunning(false)
        }
    }

    private suspend fun runBatchInternal(onProgress: suspend (BatchCheckInReport) -> Unit): BatchCheckInReport {
        val accounts = accountSource.readStoredAccounts()
        val rowOrder = accounts.map(StoredAccountSnapshot::studentId)
        val rows =
            rowOrder.associateWith { studentId ->
                BatchCheckInRowState(
                    studentId = studentId,
                    status = BatchCheckInRowStatus.Pending,
                    message = "等待签到",
                    canRetry = false,
                )
            }.toMutableMap()
        val reservations = reservationTaskDao.listAll()
        val useCase =
            RunPeriodicCheckInUseCase(
                accountSource =
                    PeriodicAccountSource(
                        accounts = accounts,
                        sessionRepository = sessionRepository,
                    ),
                reservationSource = StorageReservationSource(reservations),
                signInExecutor =
                    AccountSeatActionSignInExecutor(
                        accountSeatActionExecutor = accountSeatActionExecutor,
                        rows = rows,
                        rowOrder = rowOrder,
                        onProgress = onProgress,
                        auditRepository = signInAuditRepository,
                    ),
                eventLogger = RowEventLogger(rows),
                nowEpochSeconds = nowEpochSeconds,
            )

        val summary = useCase(TriggerSource.ManualBatch)
        summary.accountResults.forEach { result ->
            if (rows[result.studentId]?.status == BatchCheckInRowStatus.Pending) {
                rows[result.studentId] = result.toRow()
            }
        }
        return report(rowOrder, rows)
    }

    override suspend fun retry(studentId: String): BatchCheckInRowState =
        runCatching {
            val snapshot = accountSeatActionExecutor.loadSnapshot(studentId)
            if (snapshot.liveState == SeatBookingLiveState.RESERVED_WAITING_SIGNIN && !snapshot.checkinWindowOpen) {
                return@runCatching BatchCheckInRowState(
                    studentId = studentId,
                    status = BatchCheckInRowStatus.Skipped,
                    message = "未到签到时间",
                    canRetry = false,
                )
            }
            val result = accountSeatActionExecutor.performAction(studentId, AccountSeatAction.CheckIn)
            BatchCheckInRowState(
                studentId = studentId,
                status = BatchCheckInRowStatus.Success,
                message = result.message.ifBlank { "重试签到成功" },
                canRetry = false,
            )
        }.getOrElse { error ->
            BatchCheckInRowState(
                studentId = studentId,
                status = BatchCheckInRowStatus.Failed,
                message = error.message ?: "重试签到失败",
                canRetry = true,
            )
        }

    private fun PeriodicCheckInAccountResult.toRow(): BatchCheckInRowState =
        when (status) {
            PeriodicCheckInAccountStatus.Completed ->
                BatchCheckInRowState(
                    studentId = studentId,
                    status =
                        if (attemptedReservations > 0) {
                            BatchCheckInRowStatus.Success
                        } else {
                            BatchCheckInRowStatus.Skipped
                        },
                    message =
                        if (attemptedReservations > 0) {
                            "已处理 $attemptedReservations 个预约"
                        } else {
                            "没有处于签到窗口的预约"
                        },
                    canRetry = false,
                )
            PeriodicCheckInAccountStatus.SkippedExpiredSession ->
                BatchCheckInRowState(
                    studentId = studentId,
                    status = BatchCheckInRowStatus.Skipped,
                    message = "登录态过期，已跳过",
                    canRetry = false,
                )
            PeriodicCheckInAccountStatus.TimedOut ->
                BatchCheckInRowState(
                    studentId = studentId,
                    status = BatchCheckInRowStatus.Failed,
                    message = "账号签到超时",
                    canRetry = true,
                )
        }

    private class PeriodicAccountSource(
        private val accounts: List<StoredAccountSnapshot>,
        private val sessionRepository: SessionRepository,
    ) : PeriodicCheckInAccountSource {
        override suspend fun listAccounts(): List<PeriodicCheckInAccount> =
            accounts.map { account ->
                PeriodicCheckInAccount(
                    studentId = account.studentId,
                    isSessionValid = sessionRepository.currentSession(account.studentId) != null,
                )
            }
    }

    private class StorageReservationSource(
        private val reservations: List<ReservationTaskEntity>,
    ) : PeriodicCheckInReservationSource {
        override suspend fun listReservations(
            account: PeriodicCheckInAccount,
            nowEpochSeconds: Long,
        ): List<PeriodicCheckInReservation> =
            reservations
                .filter { task -> task.studentId == account.studentId }
                .map { task ->
                    PeriodicCheckInReservation(
                        taskId = task.id,
                        bookingId = task.bookingId.orEmpty(),
                        state = task.state,
                        startTimeEpochSeconds = task.startTimeEpochSeconds,
                        limitSignAgoSeconds = task.limitSignAgoSeconds,
                        limitSignBackSeconds = task.limitSignBackSeconds,
                        expectedMinors = task.expectedMinorsCsv.toMinorSet(),
                        lastGuardAttemptEpochSeconds = task.lastGuardAttemptEpochSeconds,
                        isAlreadySignedIn = task.state == ReservationTaskState.SIGNIN_SUCCESS,
                        isEnded = task.state == ReservationTaskState.CANCELLED,
                    )
                }

        private fun String.toMinorSet(): Set<Int> =
            split(',')
                .map(String::trim)
                .mapNotNull(String::toIntOrNull)
                .toSet()
    }

    private class AccountSeatActionSignInExecutor(
        private val accountSeatActionExecutor: AccountSeatActionExecutor,
        private val rows: MutableMap<String, BatchCheckInRowState>,
        private val rowOrder: List<String>,
        private val onProgress: suspend (BatchCheckInReport) -> Unit,
        private val auditRepository: SignInAuditRepository?,
    ) : PeriodicCheckInSignInExecutor {
        override suspend fun attempt(attempt: PeriodicCheckInAttempt): SignInAttemptResult {
            val studentId = attempt.account.studentId
            rows[studentId] =
                BatchCheckInRowState(
                    studentId = studentId,
                    status = BatchCheckInRowStatus.Running,
                    message = "签到中...",
                    canRetry = false,
                )
            onProgress(report(rowOrder, rows))
            val snapshot =
                runCatching { accountSeatActionExecutor.loadSnapshot(studentId) }.getOrNull()
            if (snapshot?.liveState == SeatBookingLiveState.RESERVED_WAITING_SIGNIN && !snapshot.checkinWindowOpen) {
                rows[studentId] =
                    BatchCheckInRowState(
                        studentId = studentId,
                        status = BatchCheckInRowStatus.Skipped,
                        message = "未到签到时间",
                        canRetry = false,
                    )
                onProgress(report(rowOrder, rows))
                return SignInAttemptResult(
                    correlationId = attempt.reservation.taskId,
                    matchedMinor = null,
                    seenMinors = emptyList(),
                    signInError = null,
                    scanDurationMillis = 0L,
                )
            }
            val result =
                runCatching {
                    accountSeatActionExecutor.performAction(
                        studentId,
                        AccountSeatAction.CheckIn,
                        attempt.reservation.bookingId,
                    )
                }
            // BUG-C / BUG-E 修复：从 typed 异常 / 结果对象读取已识别的 SignInError，
            // 同时让 UI 批量签到也写入 SignInAuditRepository，与 PeriodicWorker 路径一致。
            val signInError =
                result.getOrNull()?.signInError
                    ?: result.exceptionOrNull()?.let { error ->
                        var current: Throwable? = error
                        var resolved: SignInError? = null
                        while (current != null && resolved == null) {
                            resolved =
                                when (current) {
                                    is SeatActionFailedException -> current.signInError
                                    is SeatBookingNeedLoginException -> SignInError.ServerRejected
                                    else -> null
                                }
                            current = current.cause
                        }
                        resolved ?: SignInError.Unknown
                    }
            rows[studentId] =
                result.fold(
                    onSuccess = { actionResult ->
                        BatchCheckInRowState(
                            studentId = studentId,
                            status = BatchCheckInRowStatus.Success,
                            message = actionResult.message.ifBlank { "签到成功" },
                            canRetry = false,
                        )
                    },
                    onFailure = { error ->
                        BatchCheckInRowState(
                            studentId = studentId,
                            status = BatchCheckInRowStatus.Failed,
                            message = error.message ?: "签到失败",
                            canRetry = true,
                        )
                    },
                )
            onProgress(report(rowOrder, rows))
            val rawMessage =
                result.getOrNull()?.message?.ifBlank { "已签到" }
                    ?: result.exceptionOrNull()?.message?.ifBlank { signInError?.name.orEmpty() }
                    ?: signInError?.name
                    ?: "签到失败"
            auditRepository?.write(
                SignInAuditWrite(
                    correlationId = attempt.reservation.taskId,
                    bookingId = attempt.reservation.bookingId,
                    studentId = studentId,
                    matchedMinor = null,
                    httpStatusCode = null,
                    rawMessage = rawMessage.take(SIGNIN_AUDIT_MESSAGE_MAX_LENGTH),
                    signInError = signInError?.name,
                    triggerSource = attempt.triggerSource.name,
                    createdAtEpochSeconds = attempt.nowEpochSeconds,
                ),
            )
            return SignInAttemptResult(
                correlationId = attempt.reservation.taskId,
                matchedMinor = null,
                seenMinors = emptyList(),
                signInError = signInError,
                scanDurationMillis = 0L,
            )
        }

        private companion object {
            private const val SIGNIN_AUDIT_MESSAGE_MAX_LENGTH = 1_024
        }
    }

    private class RowEventLogger(
        private val rows: MutableMap<String, BatchCheckInRowState>,
    ) : PeriodicCheckInEventLogger {
        override suspend fun log(
            studentId: String,
            message: String,
        ) {
            rows[studentId] =
                BatchCheckInRowState(
                    studentId = studentId,
                    status = BatchCheckInRowStatus.Skipped,
                    message = message,
                    canRetry = false,
                )
        }
    }
}

class ExecutionLogBatchProgressWriter(
    private val executionLogDao: ExecutionLogDao,
    private val reservationTaskDao: ReservationTaskDao,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : BatchCheckInProgressWriter {
    override suspend fun record(message: String) {
        val taskId = reservationTaskDao.listAll().firstOrNull()?.id ?: return
        executionLogDao.insert(
            ExecutionLogEntity(
                taskId = taskId,
                state = ReservationTaskState.SCANNING,
                recordedAtEpochSeconds = nowEpochSeconds(),
                message = message,
            ),
        )
    }
}

private fun report(
    rowOrder: List<String>,
    rows: Map<String, BatchCheckInRowState>,
): BatchCheckInReport {
    val orderedRows = rowOrder.mapNotNull(rows::get)
    val success = orderedRows.count { row -> row.status == BatchCheckInRowStatus.Success }
    val failed = orderedRows.count { row -> row.status == BatchCheckInRowStatus.Failed }
    val skipped = orderedRows.count { row -> row.status == BatchCheckInRowStatus.Skipped }
    return BatchCheckInReport(
        rows = orderedRows,
        summaryMessage = "批量签到进度：成功 $success，失败 $failed，跳过 $skipped。",
    )
}
