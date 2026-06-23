package com.wuyi.libraryauto.ui.repository.task

import com.wuyi.libraryauto.core.domain.model.SignInError
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.http.HttpRequestException
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionService
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.network.seat.SeatBookingSnapshot
import com.wuyi.libraryauto.core.network.seat.SeatBookingStatusService
import com.wuyi.libraryauto.core.network.seat.SchoolSeatApi
import com.wuyi.libraryauto.ui.repository.settings.SeatActionAuditRepository
import com.wuyi.libraryauto.ui.repository.settings.SeatStatusAuditRepository
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.viewmodel.LoginGateway
import com.wuyi.libraryauto.ui.viewmodel.LoginResult
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccountSeatActionRepository(
    private val accountSource: StoredAccountSource,
    private val sessionRepository: SessionRepository,
    private val loginGateway: LoginGateway,
    private val statusServiceFactory: (AuthenticatedSession) -> SeatBookingStatusService,
    private val actionServiceFactory: (AuthenticatedSession) -> SeatBookingActionService,
    private val coordinator: AccountOperationCoordinator,
    private val seatServiceOrigins: List<String> = emptyList(),
    private val seatStatusAuditRepository: SeatStatusAuditRepository? = null,
    private val seatActionAuditRepository: SeatActionAuditRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountSeatActionExecutor {
    override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView =
        withContext(ioDispatcher) {
            coordinator.run(studentId) {
                runWithSessionAutoRefresh(studentId) { session ->
                    loadResolvedRemoteSnapshot(studentId, session).snapshot.toView()
                }
            }
        }

    override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> =
        withContext(ioDispatcher) {
            coordinator.run(studentId) {
                runWithSessionAutoRefresh(studentId) { session ->
                    loadResolvedActiveBookings(studentId, session).map { it.toView() }
                }
            }
        }

    override suspend fun performAction(
        studentId: String,
        action: AccountSeatAction,
        bookingId: String?,
    ): AccountSeatActionExecutionResult =
        withContext(ioDispatcher) {
            coordinator.run(studentId) {
                val actionLabel = action.auditLabel()
                var requestUrl = ""
                try {
                    runWithSessionAutoRefresh(studentId) { session ->
                        val targetBookingId = bookingId?.trim()?.takeIf(String::isNotBlank)
                        val resolvedSnapshot = loadResolvedRemoteSnapshot(studentId, session, targetBookingId)
                        val snapshot = resolvedSnapshot.snapshot
                        val actionService = actionServiceFactory(session)
                        val effectiveBookingId = targetBookingId ?: snapshot.bookingId ?: error("当前没有可操作预约")
                        requestUrl =
                            when (action) {
                                AccountSeatAction.CheckIn ->
                                    buildActionUrl(resolvedSnapshot.seatServiceOrigin, SchoolSeatApi.CHECKIN_PATH, effectiveBookingId)
                                AccountSeatAction.CancelBooking ->
                                    buildActionUrl(resolvedSnapshot.seatServiceOrigin, SchoolSeatApi.CANCEL_BOOKING_PATH, effectiveBookingId)
                                AccountSeatAction.Checkout ->
                                    buildActionUrl(resolvedSnapshot.seatServiceOrigin, SchoolSeatApi.CHECKOUT_PATH, effectiveBookingId)
                            }
                        seatActionAuditRepository?.recordAttempt(
                            studentId = studentId,
                            actionLabel = actionLabel,
                            requestUrl = requestUrl,
                            message = "已发起${actionLabel}请求，等待学校接口返回。",
                        )
                        val actionResult =
                            when (action) {
                                AccountSeatAction.CheckIn -> actionService.checkIn(requestUrl, effectiveBookingId)
                                AccountSeatAction.CancelBooking -> actionService.cancelBooking(requestUrl, effectiveBookingId)
                                AccountSeatAction.Checkout -> actionService.checkout(requestUrl, effectiveBookingId)
                            }
                        val signInError = actionResult.signInError
                        if (signInError != null && signInError != SignInError.AlreadySignedIn) {
                            // BUG-C 修复：把已识别的 SignInError 包进 SeatActionFailedException，
                            // 让上层 PerformActionSignInExecutor 能直接读取错误码而不再依赖文本匹配。
                            // message 保留 `执行${actionLabel}失败：xxx` 前缀，与既有审计文案一致。
                            throw SeatActionFailedException(
                                signInError = signInError,
                                rawMessage = actionResult.rawMessage,
                                message =
                                    "执行${actionLabel}失败：" +
                                        actionResult.rawMessage.ifBlank { signInError.name },
                            )
                        }
                        seatActionAuditRepository?.recordSuccess(
                            studentId = studentId,
                            actionLabel = actionLabel,
                            requestUrl = requestUrl,
                            message = actionResult.rawMessage,
                        )
                        // BUG 7 修复：AlreadySignedIn 与 GuardWorker 路径保持一致，视为成功并附带说明文案，
                        // 避免 UI 批量签到把已签到状态显示为「失败」。
                        val resolvedMessage =
                            if (signInError == SignInError.AlreadySignedIn) {
                                actionResult.rawMessage.ifBlank { "已签到" }
                            } else {
                                actionResult.rawMessage
                            }
                        AccountSeatActionExecutionResult(
                            message = resolvedMessage,
                            updatedSnapshot = loadResolvedRemoteSnapshot(studentId, session).snapshot.toView(),
                            signInError = signInError,
                        )
                    }
                } catch (error: Throwable) {
                    val normalizedError = normalizeSeatActionError(actionLabel, error)
                    seatActionAuditRepository?.recordFailure(
                        studentId = studentId,
                        actionLabel = actionLabel,
                        requestUrl = requestUrl,
                        message = normalizedError.message ?: "执行${actionLabel}失败",
                    )
                    throw normalizedError
                }
            }
        }

    /**
     * BUG-A 修复：包装 [block] 自动处理「Cookie 过期」场景。
     *
     * 现象：`PersistentSessionRepository` 把 cookie 持久化到 SharedPreferences，第二天周期签到时
     * `currentSession(studentId)` 仍能命中旧 cookie，但学校接口已识别为未登录。
     *
     * 行为：先用现有会话执行一次；若识别到 `NEED_LOGIN` 或 401/403，清掉旧会话并强制重新登录后再执行一次。
     * 仅重试一次，避免账号密码失效时无限循环。
     */
    private suspend fun <T> runWithSessionAutoRefresh(
        studentId: String,
        block: suspend (AuthenticatedSession) -> T,
    ): T {
        val session = ensureSession(studentId, forceRefresh = false)
        val firstAttempt =
            runCatching { block(session) }
        firstAttempt.getOrNull()?.let { return it }
        val cause = firstAttempt.exceptionOrNull()!!
        if (!cause.isSessionExpired()) {
            throw cause
        }
        // 旧 cookie 已失效，清掉本地会话再用账号密码重新登录一次。
        sessionRepository.remove(studentId)
        val refreshedSession = ensureSession(studentId, forceRefresh = true)
        return block(refreshedSession)
    }

    private suspend fun ensureSession(
        studentId: String,
        forceRefresh: Boolean,
    ): AuthenticatedSession {
        if (!forceRefresh) {
            sessionRepository.currentSession(studentId)?.let { return it }
        }
        val account =
            accountSource.readStoredAccounts().firstOrNull { it.studentId == studentId.trim() }
                ?: error("未找到账号 $studentId")
        val result = loginGateway.login(studentId.trim(), account.password.ifBlank { studentId.trim() })
        when (result) {
            LoginResult.Success -> {
                return sessionRepository.currentSession(studentId.trim())
                    ?: error("登录后未生成可用会话")
            }

            is LoginResult.Failure -> error(result.message)
        }
    }

    /**
     * 判断异常是否对应「会话已失效」语义：
     * - HTTP 401/403 直接判定。
     * - `loadCurrentBooking` 返回 NEED_LOGIN 时上层会抛 "当前没有可操作预约"，需要先看 snapshot；
     *   这里通过 cause 链上的 [HttpRequestException] 或 [SeatBookingNeedLoginException] 识别。
     */
    private fun Throwable.isSessionExpired(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is HttpRequestException &&
                (current.statusCode == 401 || current.statusCode == 403)
            ) {
                return true
            }
            if (current is SeatBookingNeedLoginException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * 拉取「当前操作目标」预约 snapshot。
     *
     * - [targetBookingId] 为空：复用既有逻辑拿当前最相关的一条（含状态择优）。
     * - [targetBookingId] 非空：拉取列表后定位指定预约；找不到时回退到 NEED_LOGIN 异常之外的
     *   IllegalStateException，避免对错误的 bookingId 做操作。
     */
    private fun loadResolvedRemoteSnapshot(
        studentId: String,
        session: AuthenticatedSession,
        targetBookingId: String? = null,
    ): ResolvedSeatBookingSnapshot {
        var lastError: Throwable? = null
        var lastRequestUrl = ""
        // 兼容旧版本遗留的 origin；先尝试会话里保存的域名，失败后再回退到当前固定座位域名。
        for (origin in buildSeatServiceOrigins(session)) {
            val requestUrl = buildBookingListUrl(origin)
            try {
                val service = statusServiceFactory(session)
                val snapshot =
                    if (targetBookingId.isNullOrBlank()) {
                        service.loadCurrentBooking(requestUrl)
                    } else {
                        service.loadBooking(requestUrl, targetBookingId)
                            ?: throw IllegalStateException("未找到对应预约（id=$targetBookingId），请刷新预约状态后重试。")
                    }
                if (snapshot.liveState == SeatBookingLiveState.NEED_LOGIN) {
                    // BUG-A 修复：服务端识别为未登录时不要把 NEED_LOGIN snapshot 返回上层，
                    // 否则上层会以 "当前没有可操作预约" 报错并丢失会话过期的语义。
                    seatStatusAuditRepository?.recordFailure(
                        studentId = studentId,
                        requestUrl = requestUrl,
                        message = snapshot.statusLabel.ifBlank { "登录态已失效" },
                    )
                    throw SeatBookingNeedLoginException(
                        message = snapshot.statusLabel.ifBlank { "登录态已失效，请刷新认证后重试。" },
                    )
                }
                val resolvedSnapshot = ResolvedSeatBookingSnapshot(
                    seatServiceOrigin = origin,
                    snapshot = snapshot,
                )
                seatStatusAuditRepository?.recordSuccess(
                    studentId = studentId,
                    requestUrl = requestUrl,
                    message = resolvedSnapshot.snapshot.toAuditMessage(),
                )
                return resolvedSnapshot
            } catch (needLogin: SeatBookingNeedLoginException) {
                throw needLogin
            } catch (error: Throwable) {
                lastRequestUrl = requestUrl
                lastError = normalizeSeatStatusError(requestUrl, error)
            }
        }
        seatStatusAuditRepository?.recordFailure(
            studentId = studentId,
            requestUrl = lastRequestUrl,
            message = lastError?.message ?: "座位状态查询失败",
        )
        throw lastError ?: IllegalStateException("座位状态查询失败")
    }

    /**
     * 拉取该账号当前的全部活跃预约。逻辑与 [loadResolvedRemoteSnapshot] 类似，
     * 但调用 [SeatBookingStatusService.loadActiveBookings] 一次返回多条。
     */
    private fun loadResolvedActiveBookings(
        studentId: String,
        session: AuthenticatedSession,
    ): List<SeatBookingSnapshot> {
        var lastError: Throwable? = null
        var lastRequestUrl = ""
        for (origin in buildSeatServiceOrigins(session)) {
            val requestUrl = buildBookingListUrl(origin)
            try {
                val bookings = statusServiceFactory(session).loadActiveBookings(requestUrl)
                val needLogin =
                    bookings.firstOrNull { it.liveState == SeatBookingLiveState.NEED_LOGIN }
                if (needLogin != null) {
                    seatStatusAuditRepository?.recordFailure(
                        studentId = studentId,
                        requestUrl = requestUrl,
                        message = needLogin.statusLabel.ifBlank { "登录态已失效" },
                    )
                    throw SeatBookingNeedLoginException(
                        message = needLogin.statusLabel.ifBlank { "登录态已失效，请刷新认证后重试。" },
                    )
                }
                seatStatusAuditRepository?.recordSuccess(
                    studentId = studentId,
                    requestUrl = requestUrl,
                    message =
                        if (bookings.isEmpty()) {
                            "暂无活跃预约"
                        } else {
                            bookings.joinToString(" | ") { it.toAuditMessage() }
                        },
                )
                return bookings
            } catch (needLogin: SeatBookingNeedLoginException) {
                throw needLogin
            } catch (error: Throwable) {
                lastRequestUrl = requestUrl
                lastError = normalizeSeatStatusError(requestUrl, error)
            }
        }
        seatStatusAuditRepository?.recordFailure(
            studentId = studentId,
            requestUrl = lastRequestUrl,
            message = lastError?.message ?: "座位状态查询失败",
        )
        throw lastError ?: IllegalStateException("座位状态查询失败")
    }

    private fun buildSeatServiceOrigins(session: AuthenticatedSession): List<String> =
        buildList {
            add(session.origin)
            addAll(seatServiceOrigins)
        }.map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    private fun buildBookingListUrl(origin: String): String =
        SchoolSeatApi.appendLabJson("${origin.removeSuffix("/")}${SchoolSeatApi.MY_BOOKING_LIST_PATH}")

    private fun buildActionUrl(
        origin: String,
        path: String,
        bookingId: String,
    ): String = SchoolSeatApi.appendLabJson("${origin.removeSuffix("/")}$path?bookingId=$bookingId")

    private fun SeatBookingSnapshot.toView(): SeatBookingSnapshotView =
        SeatBookingSnapshotView(
            liveState = liveState,
            bookingId = bookingId,
            roomName = roomName,
            seatNumber = seatNumber,
            beginLabel = beginLabel,
            statusLabel = statusLabel,
            checkinWindowOpen = checkinWindowOpen,
        )

    private fun SeatBookingSnapshot.toAuditMessage(): String =
        listOf(
            statusLabel.ifBlank { liveState.name },
            roomName,
            seatNumber,
            beginLabel,
        ).filter(String::isNotBlank).joinToString(" / ")

    private fun normalizeSeatStatusError(
        requestUrl: String,
        error: Throwable,
    ): IllegalStateException =
        when (error) {
            is IllegalStateException ->
                error.message?.takeIf(String::isNotBlank)?.let(::IllegalStateException)
                    ?: IllegalStateException("读取座位状态失败：$requestUrl")
            is HttpRequestException ->
                IllegalStateException(
                    "座位状态接口返回 HTTP ${error.statusCode}：${error.url}",
                    error,
                )
            is SocketTimeoutException ->
                IllegalStateException("座位状态请求超时，请检查网络后重试。", error)
            is IOException ->
                IllegalStateException("座位状态请求失败：网络异常，请稍后重试。", error)
            else -> {
                val detail =
                    error.message?.takeIf(String::isNotBlank)
                        ?: error::class.simpleName
                        ?: "未知异常"
                IllegalStateException("读取座位状态失败：$detail", error)
            }
        }

    private fun normalizeSeatActionError(
        actionLabel: String,
        error: Throwable,
    ): IllegalStateException =
        when (error) {
            // BUG-A / BUG-C 修复：携带 typed 信息的异常需要原样上抛，不要被通用包装吞掉。
            is SeatActionFailedException -> error
            is SeatBookingNeedLoginException -> error
            is IllegalStateException, is IllegalArgumentException ->
                wrapSeatActionError(
                    actionLabel = actionLabel,
                    detail = error.message,
                    cause = error,
                )
            is HttpRequestException ->
                IllegalStateException(
                    "执行${actionLabel}失败：接口返回 HTTP ${error.statusCode}：${error.url}",
                    error,
                )
            is SocketTimeoutException ->
                IllegalStateException("执行${actionLabel}失败：请求超时，请检查网络后重试。", error)
            is IOException ->
                IllegalStateException("执行${actionLabel}失败：网络异常，请稍后重试。", error)
            else -> {
                val detail =
                    error.message?.takeIf(String::isNotBlank)
                        ?: error::class.simpleName
                        ?: "未知异常"
                wrapSeatActionError(actionLabel = actionLabel, detail = detail, cause = error)
            }
        }

    private fun wrapSeatActionError(
        actionLabel: String,
        detail: String?,
        cause: Throwable,
    ): IllegalStateException =
        detail?.takeIf(String::isNotBlank)?.let { message ->
            IllegalStateException("执行${actionLabel}失败：$message", cause)
        } ?: IllegalStateException("执行${actionLabel}失败。", cause)

    private fun AccountSeatAction.auditLabel(): String =
        when (this) {
            AccountSeatAction.CheckIn -> "签到"
            AccountSeatAction.CancelBooking -> "取消预约"
            AccountSeatAction.Checkout -> "签退"
        }

    private data class ResolvedSeatBookingSnapshot(
        val seatServiceOrigin: String,
        val snapshot: SeatBookingSnapshot,
    )
}

/**
 * BUG-A 修复：服务端识别为「需要重新登录」时抛出的 typed 异常，让上层
 * [AccountSeatActionRepository.runWithSessionAutoRefresh] 能够检测到并触发会话自愈重试。
 */
class SeatBookingNeedLoginException(
    message: String,
) : IllegalStateException(message)

/**
 * BUG-C 修复：座位动作（签到/签退/取消）业务失败时抛出的 typed 异常，携带已识别好的
 * [SignInError] 错误码，避免上层在错误文本上做模糊匹配。
 */
class SeatActionFailedException(
    val signInError: SignInError,
    val rawMessage: String,
    message: String,
) : IllegalStateException(message)
