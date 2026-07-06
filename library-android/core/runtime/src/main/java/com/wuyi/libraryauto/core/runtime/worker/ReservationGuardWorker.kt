package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wuyi.libraryauto.core.ble.AndroidBleScannerClient
import com.wuyi.libraryauto.core.ble.BleScanOutcome
import com.wuyi.libraryauto.core.ble.BleScanThrottler
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.model.SignInError
import com.wuyi.libraryauto.core.domain.usecase.BookingCheckInMutexRegistry
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SchoolAuthService
import com.wuyi.libraryauto.core.network.auth.toLoginErrorMessage
import com.wuyi.libraryauto.core.network.http.HttpRequestException
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.network.seat.CookieSchoolSeatApi
import com.wuyi.libraryauto.core.network.seat.SchoolSeatApi
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionResult
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionService
import com.wuyi.libraryauto.core.network.seat.SeatBookingSnapshot
import com.wuyi.libraryauto.core.network.seat.SeatBookingStatusService
import com.wuyi.libraryauto.core.runtime.beacon.BeaconScanCoordinator
import com.wuyi.libraryauto.core.runtime.beacon.StorageBeaconScanAuditWriter
import com.wuyi.libraryauto.core.runtime.service.BeaconForegroundServiceController
import com.wuyi.libraryauto.core.storage.audit.BeaconScanAuditRepository
import com.wuyi.libraryauto.core.storage.audit.SignInAuditRepository
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.ExecutionLogDao
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import androidx.room.withTransaction
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReservationGuardWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val dependencies: ReservationGuardWorkerDependencies =
        ReservationGuardWorkerProvider.get(appContext)

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val nowEpochSeconds = System.currentTimeMillis() / 1000
        return try {
            executeOnce(
                taskId = taskId,
                nowEpochSeconds = nowEpochSeconds,
                dependencies = dependencies,
                scheduleRetry = { task, retryAtEpochSeconds ->
                    enqueueAt(
                        context = applicationContext,
                        taskId = task.id,
                        runAtEpochSeconds = retryAtEpochSeconds,
                        nowEpochSeconds = nowEpochSeconds,
                    )
                },
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 协程被取消（例如系统约束变化）必须向上抛出，让 WorkManager 处理。
            throw cancellation
        } catch (error: Throwable) {
            // BUG 9 修复：顶层兜底，把任意 DAO/IO 异常落盘成一条 ExecutionLog，避免静默 backoff。
            runCatching {
                dependencies.insertExecutionLog(
                    ExecutionLogEntity(
                        taskId = taskId,
                        state = ReservationTaskState.FAILED_RETRYABLE,
                        recordedAtEpochSeconds = nowEpochSeconds,
                        message = "GuardWorker 顶层异常：${error::class.simpleName}: ${error.message.orEmpty()}".take(SIGNIN_AUDIT_MESSAGE_MAX_LENGTH),
                    ),
                )
            }
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME_PREFIX = "reservation-guard"
        internal const val KEY_TASK_ID = "taskId"
        private const val RETRY_INTERVAL_SECONDS = 60L
        // Back off failed guard attempts so transient school API failures do not retry every 60 seconds.
        private const val MAX_RETRY_INTERVAL_SECONDS = 300L
        private const val SIGNIN_AUDIT_MESSAGE_MAX_LENGTH = 1_024
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private val HTTP_SUCCESS_RANGE = 200..299
        private val loginMutex = Mutex()

        internal suspend fun executeOnce(
            taskId: String,
            nowEpochSeconds: Long,
            dependencies: ReservationGuardWorkerDependencies,
            scheduleRetry: (suspend (ReservationTaskEntity, Long) -> Unit)? = null,
        ): Result {
            val task = dependencies.findTask(taskId) ?: return Result.failure()
            if (!GuardRestoreCoordinator.isGuardable(task.state)) {
                return Result.success()
            }
            val bookingId = task.bookingId.orEmpty().trim()
            if (bookingId.isBlank()) {
                return failTask(
                    task = task,
                    nowEpochSeconds = nowEpochSeconds,
                    message = "预约记录缺少 bookingId，无法自动签到。",
                    dependencies = dependencies,
                )
            }
            val deadlineEpochSeconds = task.checkInDeadlineEpochSeconds()
            if (nowEpochSeconds > deadlineEpochSeconds) {
                return failTask(
                    task = task,
                    nowEpochSeconds = nowEpochSeconds,
                    message = "已超过自动签到时间窗，请手动处理。",
                    dependencies = dependencies,
                )
            }
            val account = dependencies.loadSavedAccount(task.studentId)
                ?: return failTask(
                    task = task,
                    nowEpochSeconds = nowEpochSeconds,
                    message = "未找到 ${task.studentId} 的本地账号，无法自动签到。",
                    dependencies = dependencies,
                )
            val session =
                runCatching {
                    loginMutex.withLock {
                        dependencies.login(account.studentId, account.password)
                    }
                }.getOrElse { error ->
                    return retryOrFail(
                        task = task,
                        nowEpochSeconds = nowEpochSeconds,
                        deadlineEpochSeconds = deadlineEpochSeconds,
                        message = error.toLoginErrorMessage(),
                        dependencies = dependencies,
                        scheduleRetry = scheduleRetry,
                    )
                }
            val bookingDetail =
                runCatching {
                    dependencies.loadBookingDetail(task, session)
                }.getOrElse { error ->
                    return retryOrFail(
                        task = task,
                        nowEpochSeconds = nowEpochSeconds,
                        deadlineEpochSeconds = deadlineEpochSeconds,
                        message = error.message?.takeIf(String::isNotBlank) ?: "读取预约状态失败",
                        dependencies = dependencies,
                        scheduleRetry = scheduleRetry,
                    )
                }
            if (bookingDetail == null) {
                return retryOrFail(
                    task = task,
                    nowEpochSeconds = nowEpochSeconds,
                    deadlineEpochSeconds = deadlineEpochSeconds,
                    message = "当前未读取到目标预约记录，稍后重试。",
                    dependencies = dependencies,
                    scheduleRetry = scheduleRetry,
                )
            }
            if (bookingDetail.isAlreadySignedIn) {
                return completeTask(
                    task = task,
                    nowEpochSeconds = nowEpochSeconds,
                    message = bookingDetail.statusLabel.ifBlank { "已自动签到" },
                    dependencies = dependencies,
                )
            }
            if (!bookingDetail.window.isOpen(nowEpochSeconds)) {
                return retryOrFail(
                    task = task,
                    nowEpochSeconds = nowEpochSeconds,
                    deadlineEpochSeconds = deadlineEpochSeconds,
                    message = "当前不在签到时间窗，稍后自动重试。",
                    dependencies = dependencies,
                    scheduleRetry = scheduleRetry,
                )
            }
            val expectedMinors = bookingDetail.expectedMinorSet(task)
            return BookingCheckInMutexRegistry.shared.withLock(bookingId) {
                executeLockedScanAndSignIn(
                    task = task,
                    bookingId = bookingId,
                    expectedMinors = expectedMinors,
                    session = session,
                    account = account,
                    nowEpochSeconds = nowEpochSeconds,
                    deadlineEpochSeconds = deadlineEpochSeconds,
                    dependencies = dependencies,
                    scheduleRetry = scheduleRetry,
                )
            }
        }

        private suspend fun executeLockedScanAndSignIn(
            task: ReservationTaskEntity,
            bookingId: String,
            expectedMinors: Set<Int>,
            session: AuthenticatedSession,
            account: SavedAccountStore.SavedAccount,
            nowEpochSeconds: Long,
            deadlineEpochSeconds: Long,
            dependencies: ReservationGuardWorkerDependencies,
            scheduleRetry: (suspend (ReservationTaskEntity, Long) -> Unit)?,
        ): Result {
            val scanOutcome = dependencies.scanAndMatch(
                bookingId = bookingId,
                expectedMinors = expectedMinors,
            )
            val scanSignInError = scanOutcome.toSignInError()
            if (scanSignInError != null) {
                val scanMessage = scanOutcome.toExecutionMessage()
                writeSignInAudit(
                    task = task,
                    bookingId = bookingId,
                    scanOutcome = scanOutcome,
                    httpStatus = null,
                    rawMessage = scanMessage,
                    signInError = scanSignInError,
                    nowEpochSeconds = nowEpochSeconds,
                    dependencies = dependencies,
                )
                return retryOrFail(
                    task = task,
                    nowEpochSeconds = nowEpochSeconds,
                    deadlineEpochSeconds = deadlineEpochSeconds,
                    message = scanMessage,
                    dependencies = dependencies,
                    scheduleRetry = scheduleRetry,
                )
            }
            // BUG 2 修复：BLE 命中后即将向服务端发起 checkIn，此刻就标记 lastGuardAttemptEpochSeconds，
            // 这样即使 checkIn 抛异常或进程被系统杀死，60 秒防抖时间戳也已落库。
            val attemptedTask = task.copy(lastGuardAttemptEpochSeconds = nowEpochSeconds)
            dependencies.updateTask(attemptedTask)
            val actionResult =
                runCatching {
                    dependencies.checkIn(session, bookingId)
                }.getOrElse { error ->
                    val httpStatus = (error as? HttpRequestException)?.statusCode
                    val isAuthError = httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN
                    val message = error.message?.takeIf(String::isNotBlank) ?: "自动签到失败"
                    val signInError = if (isAuthError) SignInError.ServerRejected else SignInError.NetworkError
                    writeSignInAudit(
                        task = attemptedTask,
                        bookingId = bookingId,
                        scanOutcome = scanOutcome,
                        httpStatus = httpStatus,
                        rawMessage = message,
                        signInError = signInError,
                        nowEpochSeconds = nowEpochSeconds,
                        dependencies = dependencies,
                    )
                    // BUG 4 修复：OkHttp 在 4xx 阶段抛 HttpRequestException 时同样触发 refreshLogin。
                    if (isAuthError) {
                        val refreshSucceeded =
                            runCatching {
                                loginMutex.withLock {
                                    dependencies.refreshLogin(account)
                                }
                            }.getOrDefault(false)
                        val refreshMessage =
                            if (refreshSucceeded) {
                                "登录态已刷新，稍后重新签到。"
                            } else {
                                "登录态失效，重新登录失败。"
                            }
                        return retryOrFail(
                            task = attemptedTask,
                            nowEpochSeconds = nowEpochSeconds,
                            deadlineEpochSeconds = deadlineEpochSeconds,
                            message = refreshMessage,
                            dependencies = dependencies,
                            scheduleRetry = scheduleRetry,
                        )
                    }
                    return retryOrFail(
                        task = attemptedTask,
                        nowEpochSeconds = nowEpochSeconds,
                        deadlineEpochSeconds = deadlineEpochSeconds,
                        message = message,
                        dependencies = dependencies,
                        scheduleRetry = scheduleRetry,
                    )
                }
            val signInError = actionResult.resolvedSignInError()
            writeSignInAudit(
                task = attemptedTask,
                bookingId = bookingId,
                scanOutcome = scanOutcome,
                httpStatus = actionResult.httpStatus,
                rawMessage = actionResult.rawMessage,
                signInError = signInError,
                nowEpochSeconds = nowEpochSeconds,
                dependencies = dependencies,
            )
            if (signInError == SignInError.AlreadySignedIn) {
                return completeTask(
                    task = attemptedTask,
                    nowEpochSeconds = nowEpochSeconds,
                    message = actionResult.rawMessage.ifBlank { "已自动签到" },
                    dependencies = dependencies,
                )
            }
            if (actionResult.requiresLoginRefresh()) {
                val refreshSucceeded =
                    runCatching {
                        loginMutex.withLock {
                            dependencies.refreshLogin(account)
                        }
                    }.getOrDefault(false)
                val message =
                    if (refreshSucceeded) {
                        actionResult.rawMessage.ifBlank { "登录态已刷新，稍后重新签到。" }
                    } else {
                        actionResult.rawMessage.ifBlank { "登录态失效，重新登录失败。" }
                    }
                return retryOrFail(
                    task = attemptedTask,
                    nowEpochSeconds = nowEpochSeconds,
                    deadlineEpochSeconds = deadlineEpochSeconds,
                    message = message,
                    dependencies = dependencies,
                    scheduleRetry = scheduleRetry,
                )
            }
            if (signInError != null) {
                return retryOrFail(
                    task = attemptedTask,
                    nowEpochSeconds = nowEpochSeconds,
                    deadlineEpochSeconds = deadlineEpochSeconds,
                    message = actionResult.rawMessage.ifBlank { signInError.name },
                    dependencies = dependencies,
                    scheduleRetry = scheduleRetry,
                )
            }
            return completeTask(
                task = attemptedTask,
                nowEpochSeconds = nowEpochSeconds,
                message = actionResult.rawMessage,
                dependencies = dependencies,
            )
        }

        private suspend fun completeTask(
            task: ReservationTaskEntity,
            nowEpochSeconds: Long,
            message: String,
            dependencies: ReservationGuardWorkerDependencies,
        ): Result {
            val updatedTask = task.copy(
                state = ReservationTaskState.SIGNIN_SUCCESS,
                lastError = null,
                consecutiveRetryCount = 0,
            )
            // BUG 8 修复：状态机推进 + 执行日志写入需在同一事务，避免「状态变了但日志没写」。
            dependencies.runInTransaction {
                dependencies.updateTask(updatedTask)
                dependencies.insertExecutionLog(
                    ExecutionLogEntity(
                        taskId = updatedTask.id,
                        state = updatedTask.state,
                        recordedAtEpochSeconds = nowEpochSeconds,
                        message = message,
                    ),
                )
            }
            return Result.success()
        }

        private suspend fun retryOrFail(
            task: ReservationTaskEntity,
            nowEpochSeconds: Long,
            deadlineEpochSeconds: Long,
            message: String,
            dependencies: ReservationGuardWorkerDependencies,
            scheduleRetry: (suspend (ReservationTaskEntity, Long) -> Unit)?,
        ): Result {
            val nextRetryDelay = computeRetryDelaySeconds(task)
            if (nowEpochSeconds + nextRetryDelay <= deadlineEpochSeconds && scheduleRetry != null) {
                val updatedTask = task.copy(
                    state = ReservationTaskState.GUARD_SCHEDULED,
                    lastError = message,
                    consecutiveRetryCount = (task.consecutiveRetryCount + 1).coerceAtMost(10),
                )
                dependencies.runInTransaction {
                    dependencies.updateTask(updatedTask)
                    dependencies.insertExecutionLog(
                        ExecutionLogEntity(
                            taskId = updatedTask.id,
                            state = updatedTask.state,
                            recordedAtEpochSeconds = nowEpochSeconds,
                            message = message,
                        ),
                    )
                }
                scheduleRetry(updatedTask, nowEpochSeconds + nextRetryDelay)
                return Result.success()
            }
            return failTask(
                task = task,
                nowEpochSeconds = nowEpochSeconds,
                message = message,
                dependencies = dependencies,
            )
        }

        /**
         * 指数退避：60s, 120s, 240s, ... 上限 [MAX_RETRY_INTERVAL_SECONDS]。
         * BLE 命中并且学校接口刚成功一次时，[ReservationTaskEntity.consecutiveRetryCount] 应被上层重置。
         */
        private fun computeRetryDelaySeconds(task: ReservationTaskEntity): Long {
            val attempts = task.consecutiveRetryCount.coerceIn(0, 10)
            val multiplier = 1L shl attempts.coerceAtMost(5)
            return (RETRY_INTERVAL_SECONDS * multiplier).coerceAtMost(MAX_RETRY_INTERVAL_SECONDS)
        }

        private suspend fun failTask(
            task: ReservationTaskEntity,
            nowEpochSeconds: Long,
            message: String,
            dependencies: ReservationGuardWorkerDependencies,
        ): Result {
            val updatedTask = task.copy(
                state = ReservationTaskState.FAILED_MANUAL_ACTION,
                lastError = message,
            )
            dependencies.runInTransaction {
                dependencies.updateTask(updatedTask)
                dependencies.insertExecutionLog(
                    ExecutionLogEntity(
                        taskId = updatedTask.id,
                        state = updatedTask.state,
                        recordedAtEpochSeconds = nowEpochSeconds,
                        message = message,
                    ),
                )
            }
            return Result.success()
        }

        private suspend fun writeSignInAudit(
            task: ReservationTaskEntity,
            bookingId: String,
            scanOutcome: BleScanOutcome,
            httpStatus: Int?,
            rawMessage: String,
            signInError: SignInError?,
            nowEpochSeconds: Long,
            dependencies: ReservationGuardWorkerDependencies,
        ) {
            dependencies.writeSignInAudit(
                SignInAuditRecord(
                    bookingId = bookingId,
                    studentId = task.studentId,
                    correlationId = null,
                    matchedMinor = scanOutcome.matchedMinorOrNull(),
                    seenMinors = scanOutcome.seenMinors,
                    scanDurationMillis = scanOutcome.durationMillisOrZero(),
                    httpStatus = httpStatus,
                    rawMessage = rawMessage.take(SIGNIN_AUDIT_MESSAGE_MAX_LENGTH),
                    signInError = signInError,
                    triggerSource = TriggerSource.GuardWorker,
                    createdAtEpochSeconds = nowEpochSeconds,
                ),
            )
        }

        private fun ReservationTaskEntity.checkInDeadlineEpochSeconds(): Long =
            startTimeEpochSeconds + CheckInWindow.capSignBackSeconds(limitSignBackSeconds)

        private fun BookingDetail.expectedMinorSet(task: ReservationTaskEntity): Set<Int> =
            expectedMinors.ifEmpty { task.expectedMinorsFromStorage() }.toSet()

        private fun ReservationTaskEntity.expectedMinorsFromStorage(): List<Int> =
            expectedMinorsCsv
                .split(',')
                .mapNotNull { value -> value.trim().toIntOrNull() }
                .filter { minor -> minor in 0..65_535 }
                .distinct()

        private fun SeatBookingActionResult.resolvedSignInError(): SignInError? =
            when {
                httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN -> SignInError.ServerRejected
                signInError != null -> signInError
                httpStatus !in HTTP_SUCCESS_RANGE -> SignInError.ServerRejected
                else -> null
            }

        private fun SeatBookingActionResult.requiresLoginRefresh(): Boolean =
            httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN

        private fun BleScanOutcome.toSignInError(): SignInError? =
            when (this) {
                is BleScanOutcome.Matched -> null
                BleScanOutcome.BluetoothDisabled -> SignInError.BluetoothDisabled
                BleScanOutcome.PermissionDenied -> SignInError.PermissionDenied
                is BleScanOutcome.Aborted,
                BleScanOutcome.EmptyExpectedMinors,
                BleScanOutcome.ForegroundServiceUnavailable,
                is BleScanOutcome.Timeout,
                -> SignInError.BeaconNotMatched
            }

        private fun BleScanOutcome.toExecutionMessage(): String =
            when (this) {
                is BleScanOutcome.Aborted -> "蓝牙扫描未命中：$reason"
                BleScanOutcome.BluetoothDisabled -> "蓝牙未开启，稍后自动重试。"
                BleScanOutcome.EmptyExpectedMinors -> "预约缺少 iBeacon minor，稍后自动重试。"
                BleScanOutcome.ForegroundServiceUnavailable -> "前台服务不可用，稍后自动重试。"
                is BleScanOutcome.Matched -> "蓝牙扫描已命中。"
                BleScanOutcome.PermissionDenied -> "缺少蓝牙扫描权限，稍后自动重试。"
                is BleScanOutcome.Timeout -> "蓝牙扫描未命中目标信标，请稍后再试。"
            }

        private fun BleScanOutcome.matchedMinorOrNull(): Int? =
            when (this) {
                is BleScanOutcome.Matched -> matchedMinor
                is BleScanOutcome.Aborted,
                BleScanOutcome.BluetoothDisabled,
                BleScanOutcome.EmptyExpectedMinors,
                BleScanOutcome.ForegroundServiceUnavailable,
                BleScanOutcome.PermissionDenied,
                is BleScanOutcome.Timeout,
                -> null
            }

        private fun BleScanOutcome.durationMillisOrZero(): Long =
            when (this) {
                is BleScanOutcome.Aborted -> durationMillis
                is BleScanOutcome.Matched -> durationMillis
                is BleScanOutcome.Timeout -> durationMillis
                BleScanOutcome.BluetoothDisabled,
                BleScanOutcome.EmptyExpectedMinors,
                BleScanOutcome.ForegroundServiceUnavailable,
                BleScanOutcome.PermissionDenied,
                -> 0L
            }

        fun buildRequest(
            taskId: String,
            runAtEpochSeconds: Long,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        ): OneTimeWorkRequest {
            val initialDelaySeconds = max(runAtEpochSeconds - nowEpochSeconds, 0L)
            return OneTimeWorkRequestBuilder<ReservationGuardWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TASK_ID to taskId,
                    ),
                )
                .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                .build()
        }

        fun enqueue(
            context: Context,
            taskId: String,
            startTimeEpochSeconds: Long,
            limitSignAgoSeconds: Long,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        ) {
            val runAtEpochSeconds = startTimeEpochSeconds - limitSignAgoSeconds
            enqueueAt(
                context = context,
                taskId = taskId,
                runAtEpochSeconds = runAtEpochSeconds,
                nowEpochSeconds = nowEpochSeconds,
            )
            // 双轨兜底：在 WorkManager 之外再排一个精确闹钟，Doze / 长时间灭屏下也能准时触发。
            // 没有授权或厂商 ROM 拒绝时静默降级为 noop，主链不受影响。
            com.wuyi.libraryauto.core.runtime.alarm.GuardExactAlarmScheduler(context)
                .schedule(
                    taskId = taskId,
                    startTimeEpochSeconds = startTimeEpochSeconds,
                    limitSignAgoSeconds = limitSignAgoSeconds,
                    nowEpochMillis = nowEpochSeconds * 1_000L,
                )
        }

        fun enqueueAt(
            context: Context,
            taskId: String,
            runAtEpochSeconds: Long,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(taskId),
                ExistingWorkPolicy.REPLACE,
                buildRequest(
                    taskId = taskId,
                    runAtEpochSeconds = runAtEpochSeconds,
                    nowEpochSeconds = nowEpochSeconds,
                ),
            )
        }

        internal fun uniqueWorkName(taskId: String): String = "$UNIQUE_WORK_NAME_PREFIX:$taskId"
    }
}

internal interface ReservationGuardWorkerDependencies {
    suspend fun findTask(taskId: String): ReservationTaskEntity?

    fun loadSavedAccount(studentId: String): SavedAccountStore.SavedAccount?

    fun login(
        studentId: String,
        password: String,
    ): AuthenticatedSession

    fun loadBooking(
        task: ReservationTaskEntity,
        session: AuthenticatedSession,
    ): SeatBookingSnapshot?

    fun loadBookingDetail(
        task: ReservationTaskEntity,
        session: AuthenticatedSession,
    ): BookingDetail?

    suspend fun scanAndMatch(
        bookingId: String,
        expectedMinors: Set<Int>,
    ): BleScanOutcome

    fun checkIn(
        session: AuthenticatedSession,
        bookingId: String,
    ): SeatBookingActionResult

    fun refreshLogin(account: SavedAccountStore.SavedAccount): Boolean

    suspend fun writeSignInAudit(record: SignInAuditRecord)

    suspend fun updateTask(task: ReservationTaskEntity)

    suspend fun insertExecutionLog(log: ExecutionLogEntity)

    /**
     * BUG 8 修复：把跨表写入放在同一事务里，避免「audit 写入但 task 状态未更新」的不一致。
     * 默认实现把 block 直接执行，方便测试 fake 不依赖真实 Room。
     */
    suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
}

internal object ReservationGuardWorkerProvider {
    @Volatile
    private var factory: (Context) -> ReservationGuardWorkerDependencies = { context ->
        StorageBackedReservationGuardWorkerDependencies(context.applicationContext)
    }

    fun install(factory: (Context) -> ReservationGuardWorkerDependencies) {
        this.factory = factory
    }

    fun get(context: Context): ReservationGuardWorkerDependencies = factory(context)
}

private class StorageBackedReservationGuardWorkerDependencies(
    context: Context,
) : ReservationGuardWorkerDependencies {
    private val appContext = context.applicationContext
    private val database = StorageDatabaseProvider.get(appContext)
    private val reservationTaskDao: ReservationTaskDao = database.reservationTaskDao()
    private val executionLogDao: ExecutionLogDao = database.executionLogDao()
    private val signInAuditWriter = StorageSignInAuditWriter(SignInAuditRepository(database.signInAuditDao()))
    private val savedAccountStore = SavedAccountStore(appContext)
    private val authService = SchoolAuthService()
    private val seatServiceOrigins = listOf(DEFAULT_SEAT_SERVICE_ORIGIN)
    private val beaconScanCoordinator =
        BeaconScanCoordinator(
            controller = BeaconForegroundServiceController(appContext),
            scanner = AndroidBleScannerClient(appContext),
            throttler = sharedBleScanThrottler,
            auditWriter = StorageBeaconScanAuditWriter(BeaconScanAuditRepository(database.beaconScanAuditDao())),
        )

    override suspend fun findTask(taskId: String): ReservationTaskEntity? =
        reservationTaskDao.findById(taskId)

    override fun loadSavedAccount(studentId: String): SavedAccountStore.SavedAccount? =
        savedAccountStore.readAll().firstOrNull { account -> account.studentId == studentId }

    override fun login(
        studentId: String,
        password: String,
    ): AuthenticatedSession = authService.login(DEFAULT_ENTRY_URL, studentId, password)

    override fun loadBooking(
        task: ReservationTaskEntity,
        session: AuthenticatedSession,
    ): SeatBookingSnapshot? {
        val bookingId = task.bookingId.orEmpty().trim()
        if (bookingId.isBlank()) {
            return null
        }
        val statusService = SeatBookingStatusService(CookieSchoolSeatApi(session))
        buildSeatServiceOrigins(session).forEach { origin ->
            runCatching {
                statusService.loadBooking(buildBookingListUrl(origin), bookingId)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    override fun loadBookingDetail(
        task: ReservationTaskEntity,
        session: AuthenticatedSession,
    ): BookingDetail? {
        val bookingId = task.bookingId.orEmpty().trim()
        if (bookingId.isBlank()) {
            return null
        }
        val statusService = SeatBookingStatusService(CookieSchoolSeatApi(session))
        buildSeatServiceOrigins(session).forEach { origin ->
            runCatching {
                statusService.loadBookingDetail(buildBookingListUrl(origin), bookingId)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    override suspend fun scanAndMatch(
        bookingId: String,
        expectedMinors: Set<Int>,
    ): BleScanOutcome =
        beaconScanCoordinator.scanAndMatch(
            bookingId = bookingId,
            expectedMinors = expectedMinors,
        )

    override fun checkIn(
        session: AuthenticatedSession,
        bookingId: String,
    ): SeatBookingActionResult {
        val actionService = SeatBookingActionService(CookieSchoolSeatApi(session))
        var lastError: Throwable? = null
        buildSeatServiceOrigins(session).forEach { origin ->
            val attempt =
                runCatching {
                    actionService.checkIn(buildCheckInUrl(origin, bookingId), bookingId)
                }
            val result = attempt.getOrNull()
            if (result != null) {
                return result
            }
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("自动签到失败")
    }

    override fun refreshLogin(account: SavedAccountStore.SavedAccount): Boolean =
        runCatching {
            authService.login(DEFAULT_ENTRY_URL, account.studentId, account.password)
        }.isSuccess

    override suspend fun writeSignInAudit(record: SignInAuditRecord) {
        signInAuditWriter.write(record)
    }

    override suspend fun updateTask(task: ReservationTaskEntity) {
        reservationTaskDao.upsert(task)
    }

    override suspend fun insertExecutionLog(log: ExecutionLogEntity) {
        executionLogDao.insert(log)
    }

    override suspend fun <T> runInTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }

    private fun buildSeatServiceOrigins(session: AuthenticatedSession): List<String> =
        buildList {
            add(session.origin)
            addAll(seatServiceOrigins)
        }.map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    private fun buildBookingListUrl(origin: String): String =
        SchoolSeatApi.appendLabJson("${origin.removeSuffix("/")}${SchoolSeatApi.MY_BOOKING_LIST_PATH}")

    private fun buildCheckInUrl(
        origin: String,
        bookingId: String,
    ): String = SchoolSeatApi.appendLabJson("${origin.removeSuffix("/")}${SchoolSeatApi.CHECKIN_PATH}?bookingId=$bookingId")

    private companion object {
        private const val DEFAULT_ENTRY_URL = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
        private const val DEFAULT_SEAT_SERVICE_ORIGIN = "https://wuyiu.huitu.zhishulib.com"
        private val sharedBleScanThrottler = BleScanThrottler()
    }
}
