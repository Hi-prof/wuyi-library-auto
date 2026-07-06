package com.wuyi.libraryauto.runtime

import android.content.Context
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.model.SignInAttemptResult
import com.wuyi.libraryauto.core.domain.model.SignInError
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAccount
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAccountSource
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAttempt
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInEventLogger
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInReservation
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInReservationSource
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInSignInExecutor
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInSummary
import com.wuyi.libraryauto.core.domain.usecase.RunPeriodicCheckInUseCase
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SchoolAuthService
import com.wuyi.libraryauto.core.network.http.HttpRequestException
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.network.seat.CookieSchoolSeatApi
import com.wuyi.libraryauto.core.network.seat.SchoolSeatApi
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionService
import com.wuyi.libraryauto.core.network.seat.SeatBookingStatusService
import com.wuyi.libraryauto.core.runtime.sync.ReservationSyncCoordinator
import com.wuyi.libraryauto.core.runtime.worker.AutomationPlanRunner
import com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInRunner
import com.wuyi.libraryauto.core.runtime.worker.ReservationGuardWorker
import com.wuyi.libraryauto.core.storage.audit.SignInAuditRepository
import com.wuyi.libraryauto.core.storage.audit.SignInAuditWrite
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import com.wuyi.libraryauto.ui.repository.account.StoredSavedAccountRepository
import com.wuyi.libraryauto.ui.repository.auth.SchoolLoginGateway
import com.wuyi.libraryauto.ui.repository.session.PersistentSessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountOperationCoordinator
import com.wuyi.libraryauto.ui.repository.task.AccountSeatAction
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionRepository
import com.wuyi.libraryauto.ui.repository.task.SeatActionFailedException
import com.wuyi.libraryauto.ui.repository.task.SeatBookingNeedLoginException
import kotlin.math.min

internal object PeriodicCheckInWindow {
    /** 兼容 alias：实际值在 [CheckInWindow.AUTO_SIGN_BACK_CAP_SECONDS] 中维护。 */
    val MAX_SIGN_BACK_SECONDS: Long = CheckInWindow.AUTO_SIGN_BACK_CAP_SECONDS
}

/**
 * 生产环境下 [PeriodicCheckInWorker] 使用的默认 [PeriodicCheckInRunner]。
 *
 * 在 [WuyiLibraryApp.onCreate] 中通过 `PeriodicCheckInWorkerProvider.install { context ->
 * StorageBackedPeriodicCheckInRunner(context) }` 注入，避免出现「runner 未注入」的运行时错误。
 *
 * 注意：使用最小依赖集（直接复用持久化层 + 网络层），避免与 Compose 层的 `AppDependencies`
 * 形成构造时序耦合。
 *
 * 自动签到数据源：直接调学校 `loadCurrentBookingDetail` 接口，与本地 `reservation_tasks`
 * 表脱钩。只要账号在签到时间窗内（且未签到），就触发自动签到；签到截止时间统一截断
 * 到「预约开始时间 + 30 分钟」，超过后停止尝试。
 */
internal class StorageBackedPeriodicCheckInRunner(
    context: Context,
) : PeriodicCheckInRunner {
    private val appContext = context.applicationContext
    private val database = StorageDatabaseProvider.get(appContext)
    private val signInAuditRepository = SignInAuditRepository(database.signInAuditDao())
    private val reservationTaskDao: ReservationTaskDao = database.reservationTaskDao()
    private val savedAccountStore = SavedAccountStore(appContext)
    private val storedAccountRepository = StoredSavedAccountRepository(savedAccountStore)
    private val sessionRepository = PersistentSessionRepository(appContext)
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L }
    private val authService = SchoolAuthService()
    private val loginGateway =
        SchoolLoginGateway(
            authService = authService,
            credentialStore = com.wuyi.libraryauto.core.storage.credentials.CredentialStore(appContext),
            savedAccountStore = savedAccountStore,
            sessionRepository = sessionRepository,
            loginAuditRepository = null,
        )
    private val statusServiceFactory: (AuthenticatedSession) -> SeatBookingStatusService = { session ->
        SeatBookingStatusService(CookieSchoolSeatApi(session))
    }
    private val accountSeatActionExecutor: AccountSeatActionExecutor =
        AccountSeatActionRepository(
            accountSource = storedAccountRepository,
            sessionRepository = sessionRepository,
            loginGateway = loginGateway,
            statusServiceFactory = statusServiceFactory,
            actionServiceFactory = { session ->
                SeatBookingActionService(CookieSchoolSeatApi(session))
            },
            coordinator = AccountOperationCoordinator(),
            seatServiceOrigins = listOf(SchoolPortalConfig.SeatServiceOrigin),
        )

    private val reservationSyncCoordinator =
        ReservationSyncCoordinator(
            reservationTaskDao = reservationTaskDao,
            guardScheduler = { taskId, startTimeEpochSeconds, limitSignAgoSeconds ->
                ReservationGuardWorker.enqueue(
                    context = appContext,
                    taskId = taskId,
                    startTimeEpochSeconds = startTimeEpochSeconds,
                    limitSignAgoSeconds = limitSignAgoSeconds,
                )
            },
            nowEpochSeconds = nowEpochSeconds,
        )

    override suspend fun run(source: TriggerSource): PeriodicCheckInSummary {
        // 在跑签到 use case 之前先同步一遍每个账号的远端预约：把外部预约（网页/别的端）补成本地
        // reservation_tasks 行并入队各自的 GuardWorker，保证「检测到账号有预约就有独立 GuardWorker」
        // 的语义；同时清理过期 bookingId 的 mutex 缓存，避免内存随天数线性增长。
        runCatching { syncRemoteReservations() }
        // 巡检自动预约 plan：每 30 分钟统一扫一次，加 5 分钟防抖。替代 AutomationPlanWorker 内部的
        // 自我续排（去掉后 N 个 plan 不再产生 N 路独立轮询，避免学校接口风控）。
        runCatching {
            AutomationPlanRunner.runForEnabledPlans(
                context = appContext,
                nowEpochSeconds = nowEpochSeconds(),
            )
        }
        val accounts = storedAccountRepository.readStoredAccounts()
        val localReservations = reservationTaskDao.listAll()
        val useCase =
            RunPeriodicCheckInUseCase(
                accountSource = StoredAccountPeriodicSource(accounts.map { it.studentId }, sessionRepository),
                reservationSource =
                    RemoteSeatStatusReservationSource(
                        accountSeatActionExecutor = accountSeatActionExecutor,
                        sessionRepository = sessionRepository,
                        statusServiceFactory = statusServiceFactory,
                        seatServiceOrigins = listOf(SchoolPortalConfig.SeatServiceOrigin),
                        localReservations = localReservations,
                    ),
                signInExecutor =
                    PerformActionSignInExecutor(accountSeatActionExecutor, signInAuditRepository),
                eventLogger =
                    StoragePeriodicEventLogger(
                        repository = signInAuditRepository,
                        triggerSource = source,
                        nowEpochSeconds = nowEpochSeconds,
                    ),
                nowEpochSeconds = nowEpochSeconds,
            )
        return useCase(source)
    }

    /**
     * 对每个账号尝试拉一次 `loadActiveBookingDetails` 并交给 [ReservationSyncCoordinator] 同步。
     *
     * 失败的账号（会话过期 / 网络异常等）静默跳过，下一次 run 还会重试；不在这里主动重新登录，
     * 避免把签到链路阻塞在 sync 上。真正的"会话自愈"在 [accountSeatActionExecutor] 内部处理。
     */
    private suspend fun syncRemoteReservations() {
        val accounts = storedAccountRepository.readStoredAccounts()
        accounts.forEach { account ->
            val studentId = account.studentId
            val session = sessionRepository.currentSession(studentId) ?: return@forEach
            val bookings =
                runCatching { loadActiveBookingDetailsFromAnyOrigin(session) }
                    .getOrNull()
                    ?: return@forEach
            runCatching { reservationSyncCoordinator.syncAccount(studentId, bookings) }
        }
    }

    private fun loadActiveBookingDetailsFromAnyOrigin(
        session: AuthenticatedSession,
    ): List<BookingDetail> {
        val origins =
            (listOf(session.origin) + listOf(SchoolPortalConfig.SeatServiceOrigin))
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        for (origin in origins) {
            val attempt =
                runCatching {
                    val url =
                        SchoolSeatApi.appendLabJson(
                            "${origin.removeSuffix("/")}${SchoolSeatApi.MY_BOOKING_LIST_PATH}",
                        )
                    statusServiceFactory(session).loadActiveBookingDetails(url)
                }
            attempt.getOrNull()?.let { return it }
        }
        return emptyList()
    }

    private class StoredAccountPeriodicSource(
        private val studentIds: List<String>,
        private val sessionRepository: PersistentSessionRepository,
    ) : PeriodicCheckInAccountSource {
        override suspend fun listAccounts(): List<PeriodicCheckInAccount> =
            studentIds.map { studentId ->
                PeriodicCheckInAccount(
                    studentId = studentId,
                    isSessionValid = sessionRepository.currentSession(studentId) != null,
                )
            }
    }

    /**
     * 直接调用学校 `loadCurrentBookingDetail` 接口检查账号当前预约，与本地
     * `reservation_tasks` 表脱钩；只要账号在签到时间窗内（且未签到），就生成
     * 一条可签到的 [PeriodicCheckInReservation]。
     *
     * 半小时上限：把 `limitSignBackSeconds` 截断到 [MAX_SIGN_BACK_SECONDS]（30 分钟），
     * 让上游 `RunPeriodicCheckInUseCase.isEligible` 的「now < closeAt」判断自然生效。
     *
     * 60 秒防抖：从本地 `reservation_tasks` 表查同 bookingId 的 lastGuardAttemptEpochSeconds，
     * 复用现有防抖逻辑，避免 GuardWorker 与周期签到对同一 booking 60 秒内重复尝试。
     */
    private class RemoteSeatStatusReservationSource(
        private val accountSeatActionExecutor: AccountSeatActionExecutor,
        private val sessionRepository: PersistentSessionRepository,
        private val statusServiceFactory: (AuthenticatedSession) -> SeatBookingStatusService,
        private val seatServiceOrigins: List<String>,
        private val localReservations: List<ReservationTaskEntity>,
    ) : PeriodicCheckInReservationSource {
        override suspend fun listReservations(
            account: PeriodicCheckInAccount,
            nowEpochSeconds: Long,
        ): List<PeriodicCheckInReservation> {
            // 先触发一次 snapshot 加载，借 AccountSeatActionRepository 内置的会话自愈
            // 把已过期的 cookie 刷新掉；失败说明账号确实无法登录，跳过该账号。
            val refreshed =
                runCatching { accountSeatActionExecutor.loadSnapshot(account.studentId) }
                    .getOrNull()
            if (refreshed == null) {
                return emptyList()
            }
            val session = sessionRepository.currentSession(account.studentId) ?: return emptyList()
            val detail = loadDetailFromAnyOrigin(session) ?: return emptyList()
            if (detail.bookingId.isBlank() || detail.isAlreadySignedIn) {
                return emptyList()
            }
            val matchedLocal =
                localReservations.firstOrNull { task ->
                    task.studentId == account.studentId &&
                        task.bookingId.orEmpty().trim() == detail.bookingId
                }
            val cappedSignBack = min(detail.window.limitSignBackSeconds, PeriodicCheckInWindow.MAX_SIGN_BACK_SECONDS)
            return listOf(
                PeriodicCheckInReservation(
                    taskId = matchedLocal?.id ?: buildRemoteTaskId(account.studentId, detail.bookingId),
                    bookingId = detail.bookingId,
                    state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                    startTimeEpochSeconds = detail.window.startEpochSeconds,
                    limitSignAgoSeconds = detail.window.limitSignAgoSeconds,
                    limitSignBackSeconds = cappedSignBack,
                    expectedMinors = detail.expectedMinors.toSet(),
                    lastGuardAttemptEpochSeconds = matchedLocal?.lastGuardAttemptEpochSeconds,
                    isAlreadySignedIn = false,
                    isEnded = false,
                ),
            )
        }

        private fun loadDetailFromAnyOrigin(session: AuthenticatedSession): BookingDetail? {
            val origins =
                (listOf(session.origin) + seatServiceOrigins)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
            for (origin in origins) {
                val detail =
                    runCatching {
                        val url =
                            SchoolSeatApi.appendLabJson(
                                "${origin.removeSuffix("/")}${SchoolSeatApi.MY_BOOKING_LIST_PATH}",
                            )
                        statusServiceFactory(session).loadCurrentBookingDetail(url)
                    }.getOrNull()
                if (detail != null) {
                    return detail
                }
            }
            return null
        }

        private fun buildRemoteTaskId(
            studentId: String,
            bookingId: String,
        ): String = "remote-$studentId-$bookingId"

        private companion object {
            // 兜底：自动签到都不超过预约开始时间 +30 分钟。值由顶层 [PeriodicCheckInWindow.MAX_SIGN_BACK_SECONDS] 提供。
        }
    }

    private class StoragePeriodicEventLogger(
        private val repository: SignInAuditRepository,
        private val triggerSource: TriggerSource,
        private val nowEpochSeconds: () -> Long,
    ) : PeriodicCheckInEventLogger {
        override suspend fun log(
            studentId: String,
            message: String,
        ) {
            repository.write(
                SignInAuditWrite(
                    correlationId = "",
                    bookingId = "",
                    studentId = studentId,
                    matchedMinor = null,
                    httpStatusCode = null,
                    rawMessage = message.take(SIGNIN_AUDIT_MESSAGE_MAX_LENGTH),
                    signInError = message.toAccountLevelSignInError().name,
                    triggerSource = triggerSource.name,
                    createdAtEpochSeconds = nowEpochSeconds(),
                ),
            )
        }

        private fun String.toAccountLevelSignInError(): SignInError =
            when {
                contains("登录态") -> SignInError.ServerRejected
                contains("超时") -> SignInError.NetworkError
                else -> SignInError.Unknown
            }
    }

    /**
     * 包装 [AccountSeatActionExecutor.performAction]，把 `AlreadySignedIn` 当成功，
     * 其他异常封装为 `SignInError.Unknown`，与 GuardWorker 路径保持一致语义。
     */
    private class PerformActionSignInExecutor(
        private val executor: AccountSeatActionExecutor,
        private val auditRepository: SignInAuditRepository,
    ) : PeriodicCheckInSignInExecutor {
        override suspend fun attempt(attempt: PeriodicCheckInAttempt): SignInAttemptResult {
            val studentId = attempt.account.studentId
            // 把数据源已经识别出的 bookingId 显式传进 performAction，避免
            // loadCurrentBookingDetail 与 performAction 之间间隙里学校接口选中另一条预约。
            val targetBookingId = attempt.reservation.bookingId.takeIf(String::isNotBlank)
            val outcome =
                runCatching {
                    executor.performAction(studentId, AccountSeatAction.CheckIn, targetBookingId)
                }
            val error = outcome.exceptionOrNull()
            // BUG-C 修复：优先从 AccountSeatActionExecutionResult.signInError 读，
            // 失败时从 typed 异常 SeatActionFailedException 取，最后再回退到极少数兜底分支。
            val signInError =
                outcome.getOrNull()?.signInError
                    ?: error?.toSignInError()
            val rawMessage =
                outcome.getOrNull()?.message?.ifBlank { "已自动签到" }
                    ?: error?.message?.ifBlank { signInError?.name.orEmpty() }
                    ?: signInError?.name
                    ?: "签到失败"
            auditRepository.write(
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

        private fun Throwable.toSignInError(): SignInError {
            // 走 cause 链拿 typed 异常；如果 AccountSeatActionRepository 的会话自愈仍失败，
            // 401/403 会被识别为 ServerRejected，其他业务错误已经在 SeatBookingErrorMapper 解析过。
            var current: Throwable? = this
            while (current != null) {
                if (current is SeatActionFailedException) {
                    return current.signInError
                }
                if (current is SeatBookingNeedLoginException) {
                    return SignInError.ServerRejected
                }
                if (current is HttpRequestException) {
                    return when (current.statusCode) {
                        401, 403 -> SignInError.ServerRejected
                        408 -> SignInError.NetworkError
                        in 500..599 -> SignInError.NetworkError
                        else -> SignInError.ServerRejected
                    }
                }
                if (current is java.net.SocketTimeoutException || current is java.io.IOException) {
                    return SignInError.NetworkError
                }
                current = current.cause
            }
            return SignInError.Unknown
        }
    }

    private companion object {
        private const val SIGNIN_AUDIT_MESSAGE_MAX_LENGTH = 1_024
    }
}
