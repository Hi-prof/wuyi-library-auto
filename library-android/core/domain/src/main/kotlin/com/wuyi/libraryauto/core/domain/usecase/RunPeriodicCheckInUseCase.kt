package com.wuyi.libraryauto.core.domain.usecase

import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.model.SignInAttemptResult
import com.wuyi.libraryauto.core.domain.model.SignInError
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class RunPeriodicCheckInUseCase(
    private val accountSource: PeriodicCheckInAccountSource,
    private val reservationSource: PeriodicCheckInReservationSource,
    private val signInExecutor: PeriodicCheckInSignInExecutor,
    private val eventLogger: PeriodicCheckInEventLogger = PeriodicCheckInEventLogger.Noop,
    private val mutexRegistry: BookingCheckInMutexRegistry = BookingCheckInMutexRegistry.shared,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val singleAccountTimeoutMillis: Long = DEFAULT_SINGLE_ACCOUNT_TIMEOUT_MILLIS,
    private val accountConcurrency: Int = DEFAULT_ACCOUNT_CONCURRENCY,
) {
    suspend operator fun invoke(
        triggerSource: TriggerSource = TriggerSource.PeriodicMonitor,
    ): PeriodicCheckInSummary {
        val accounts = accountSource.listAccounts()
        if (accounts.isEmpty()) {
            return PeriodicCheckInSummary(emptyList())
        }
        // BUG-B 修复：账号之间并发处理，用 Semaphore 控制最多 N 个并发，避免接口限流，
        // 同时防止 Worker 5 分钟整体超时把已完成的账号结果一起丢掉。
        val permitCount = accountConcurrency.coerceAtLeast(1).coerceAtMost(accounts.size)
        val semaphore = Semaphore(permitCount)
        val results =
            coroutineScope {
                accounts
                    .map { account ->
                        async {
                            semaphore.withPermit {
                                withTimeoutOrNull(singleAccountTimeoutMillis) {
                                    processAccount(account, triggerSource)
                                } ?: run {
                                    eventLogger.log(account.studentId, "周期签到超时，已跳过")
                                    PeriodicCheckInAccountResult(
                                        studentId = account.studentId,
                                        status = PeriodicCheckInAccountStatus.TimedOut,
                                        attemptedReservations = 0,
                                        skippedReservations = 0,
                                    )
                                }
                            }
                        }
                    }
                    .awaitAll()
            }
        return PeriodicCheckInSummary(results)
    }

    private suspend fun processAccount(
        account: PeriodicCheckInAccount,
        triggerSource: TriggerSource,
    ): PeriodicCheckInAccountResult {
        if (!account.isSessionValid) {
            eventLogger.log(account.studentId, "账号登录态过期，已跳过")
            return PeriodicCheckInAccountResult(
                studentId = account.studentId,
                status = PeriodicCheckInAccountStatus.SkippedExpiredSession,
                attemptedReservations = 0,
                skippedReservations = 0,
            )
        }

        val now = nowEpochSeconds()
        val reservations = reservationSource.listReservations(account, now)
        var attempted = 0
        var skipped = 0
        var failed = 0
        reservations.forEach { reservation ->
            if (!reservation.isEligible(now)) {
                skipped += 1
                return@forEach
            }
            val attempt =
                PeriodicCheckInAttempt(
                    account = account,
                    reservation = reservation,
                    triggerSource = triggerSource,
                    nowEpochSeconds = now,
                )
            val result =
                mutexRegistry.withLock(reservation.bookingId) {
                    signInExecutor.attempt(attempt)
                }
            // BUG-G 修复：AlreadySignedIn 视为成功，不计入 failed，避免 Worker 误判 retry。
            if (result.signInError != null && result.signInError != SignInError.AlreadySignedIn) {
                failed += 1
            }
            attempted += 1
        }

        return PeriodicCheckInAccountResult(
            studentId = account.studentId,
            status = PeriodicCheckInAccountStatus.Completed,
            attemptedReservations = attempted,
            skippedReservations = skipped,
            failedReservations = failed,
        )
    }

    private fun PeriodicCheckInReservation.isEligible(nowEpochSeconds: Long): Boolean {
        if (bookingId.isBlank()) {
            return false
        }
        if (isAlreadySignedIn || isEnded) {
            return false
        }
        if (state != ReservationTaskState.RESERVED_WAITING_SIGNIN &&
            state != ReservationTaskState.GUARD_SCHEDULED
        ) {
            return false
        }
        val openAt = startTimeEpochSeconds - limitSignAgoSeconds
        val closeAt = startTimeEpochSeconds + limitSignBackSeconds
        // BUG 5 修复：与 CheckInWindow.isOpen 统一为左闭右开 [openAt, closeAt)，
        // 之前用闭区间会让 now == closeAt 这一秒在两个路径上判定不一致。
        if (nowEpochSeconds < openAt || nowEpochSeconds >= closeAt) {
            return false
        }
        val hadRecentGuardAttempt =
            lastGuardAttemptEpochSeconds
                ?.let { lastAttempt -> nowEpochSeconds - lastAttempt < RECENT_GUARD_ATTEMPT_SECONDS }
                ?: false
        return !hadRecentGuardAttempt
    }

    private companion object {
        private const val DEFAULT_SINGLE_ACCOUNT_TIMEOUT_MILLIS = 60_000L
        private const val DEFAULT_ACCOUNT_CONCURRENCY = 4
        private const val RECENT_GUARD_ATTEMPT_SECONDS = 60L
    }
}

interface PeriodicCheckInAccountSource {
    suspend fun listAccounts(): List<PeriodicCheckInAccount>
}

interface PeriodicCheckInReservationSource {
    suspend fun listReservations(
        account: PeriodicCheckInAccount,
        nowEpochSeconds: Long,
    ): List<PeriodicCheckInReservation>
}

interface PeriodicCheckInSignInExecutor {
    suspend fun attempt(attempt: PeriodicCheckInAttempt): SignInAttemptResult
}

interface PeriodicCheckInEventLogger {
    suspend fun log(
        studentId: String,
        message: String,
    )

    object Noop : PeriodicCheckInEventLogger {
        override suspend fun log(
            studentId: String,
            message: String,
        ) = Unit
    }
}

class BookingCheckInMutexRegistry {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(
        bookingId: String,
        block: suspend () -> T,
    ): T {
        val key = bookingId.trim()
        require(key.isNotBlank()) { "bookingId must not be blank" }
        return mutexes.getOrPut(key) { Mutex() }.withLock {
            block()
        }
    }

    /**
     * 清理已不再使用的 bookingId mutex，避免按外部输入无限增长（每天会有新的 bookingId）。
     *
     * - [keep] 表示当前仍被任何账号占用的 bookingId 集合（来自 reservation_tasks 当前列表 +
     *   远端最近一次 sync 拉到的列表）。
     * - 仅当 mutex 当前未被持有时才移除，避免误删正在被 GuardWorker 或周期签到使用的锁。
     */
    fun evictIdle(keep: Set<String>) {
        val keepKeys = keep.asSequence().map(String::trim).filter(String::isNotBlank).toSet()
        val iterator = mutexes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in keepKeys) {
                continue
            }
            // tryLock 失败说明仍被其他协程持有，跳过等下次 sync 再清理。
            if (entry.value.tryLock()) {
                try {
                    iterator.remove()
                } finally {
                    entry.value.unlock()
                }
            }
        }
    }

    companion object {
        val shared = BookingCheckInMutexRegistry()
    }
}

data class PeriodicCheckInAccount(
    val studentId: String,
    val isSessionValid: Boolean,
)

data class PeriodicCheckInReservation(
    val taskId: String,
    val bookingId: String,
    val state: ReservationTaskState,
    val startTimeEpochSeconds: Long,
    val limitSignAgoSeconds: Long,
    val limitSignBackSeconds: Long,
    val expectedMinors: Set<Int>,
    val lastGuardAttemptEpochSeconds: Long? = null,
    val isAlreadySignedIn: Boolean = false,
    val isEnded: Boolean = false,
)

data class PeriodicCheckInAttempt(
    val account: PeriodicCheckInAccount,
    val reservation: PeriodicCheckInReservation,
    val triggerSource: TriggerSource,
    val nowEpochSeconds: Long,
)

data class PeriodicCheckInAccountResult(
    val studentId: String,
    val status: PeriodicCheckInAccountStatus,
    val attemptedReservations: Int,
    val skippedReservations: Int,
    val failedReservations: Int = 0,
)

enum class PeriodicCheckInAccountStatus {
    Completed,
    SkippedExpiredSession,
    TimedOut,
}

data class PeriodicCheckInSummary(
    val accountResults: List<PeriodicCheckInAccountResult>,
) {
    val attemptedReservations: Int = accountResults.sumOf { result -> result.attemptedReservations }
    val skippedReservations: Int = accountResults.sumOf { result -> result.skippedReservations }
    val failedReservations: Int = accountResults.sumOf { result -> result.failedReservations }
    val timedOutAccounts: Int = accountResults.count { result -> result.status == PeriodicCheckInAccountStatus.TimedOut }
}

enum class TriggerSource {
    GuardWorker,
    PeriodicMonitor,
    ManualBatch,
    ProcessRestart,
    LoginSuccess,
    NetworkRestored,
    CampusAuthRecovery,
}
