package com.wuyi.libraryauto.core.runtime.worker

import com.wuyi.libraryauto.core.ble.BleScanOutcome
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionResult
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.network.seat.SeatBookingSnapshot
import com.wuyi.libraryauto.core.runtime.network.NetworkRecoveryResult
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import java.util.concurrent.atomic.AtomicInteger

internal class FakeGuardWorkerDependencies(
    private val task: ReservationTaskEntity = testReservationTask(),
    private val bookingDetail: BookingDetail? = testBookingDetail(task),
    var scanOutcome: BleScanOutcome =
        BleScanOutcome.Matched(
            matchedMinor = 12,
            seenMinors = listOf(12),
            durationMillis = 100L,
        ),
    var checkInResult: SeatBookingActionResult = successfulCheckInResult(),
    /** 注入异常以模拟 OkHttp 在 4xx 阶段抛出 HttpRequestException 等场景。 */
    private val checkInOverride: (() -> SeatBookingActionResult)? = null,
    private val loginConcurrencyTracker: LoginConcurrencyTracker = LoginConcurrencyTracker(),
) : ReservationGuardWorkerDependencies {
    private val session =
        AuthenticatedSession(
            session = SessionBundle(cookieHeader = "auth=token", userId = task.studentId),
            cookies = emptyList(),
            currentUserJson = """{"id":"${task.studentId}"}""",
            origin = "https://example.com",
            installationId = "install-1",
        )

    var savedTask: ReservationTaskEntity? = null
    val executionLogs = mutableListOf<ExecutionLogEntity>()
    val signInAuditRecords = mutableListOf<SignInAuditRecord>()
    var lastFindTaskId: String? = null
    var lastCheckInBookingId: String? = null
    var lastScanBookingId: String? = null
    var lastExpectedMinors: Set<Int>? = null
    var networkRecoveryResult = NetworkRecoveryResult(recovered = true, message = "当前网络可用")
    var networkRecoveryCallCount = 0
    var loginCallCount = 0
    var checkInCallCount = 0
    var refreshLoginCallCount = 0
    var refreshLoginResult = true

    override suspend fun findTask(taskId: String): ReservationTaskEntity? {
        lastFindTaskId = taskId
        return task.copy(id = taskId)
    }

    override suspend fun ensureNetworkForBackgroundWork(): NetworkRecoveryResult {
        networkRecoveryCallCount += 1
        return networkRecoveryResult
    }

    override fun loadSavedAccount(studentId: String): SavedAccountStore.SavedAccount =
        SavedAccountStore.SavedAccount(
            studentId = studentId,
            password = "secret",
            preferredRoomName = task.roomName,
            preferredSeatNumber = task.seatNumber,
        )

    override fun login(
        studentId: String,
        password: String,
    ): AuthenticatedSession {
        loginCallCount += 1
        return loginConcurrencyTracker.track {
            session
        }
    }

    override fun loadBooking(
        task: ReservationTaskEntity,
        session: AuthenticatedSession,
    ): SeatBookingSnapshot? =
        bookingDetail?.let { detail ->
            SeatBookingSnapshot(
                liveState =
                    if (detail.isAlreadySignedIn) {
                        SeatBookingLiveState.ACTIVE_SIGNED_IN
                    } else {
                        SeatBookingLiveState.RESERVED_WAITING_SIGNIN
                    },
                bookingId = detail.bookingId,
                roomName = task.roomName,
                seatNumber = task.seatNumber,
                statusLabel = detail.statusLabel,
                checkinWindowOpen = detail.window.isOpen(detail.window.startEpochSeconds),
            )
        }

    override fun loadBookingDetail(
        task: ReservationTaskEntity,
        session: AuthenticatedSession,
    ): BookingDetail? = bookingDetail

    override suspend fun scanAndMatch(
        bookingId: String,
        expectedMinors: Set<Int>,
    ): BleScanOutcome {
        lastScanBookingId = bookingId
        lastExpectedMinors = expectedMinors
        return scanOutcome
    }

    override fun checkIn(
        session: AuthenticatedSession,
        bookingId: String,
    ): SeatBookingActionResult {
        checkInCallCount += 1
        lastCheckInBookingId = bookingId
        checkInOverride?.let { return it.invoke() }
        return checkInResult.copy(bookingId = bookingId)
    }

    override fun refreshLogin(account: SavedAccountStore.SavedAccount): Boolean {
        refreshLoginCallCount += 1
        return refreshLoginResult
    }

    override suspend fun writeSignInAudit(record: SignInAuditRecord) {
        signInAuditRecords += record
    }

    override suspend fun updateTask(task: ReservationTaskEntity) {
        savedTask = task
    }

    override suspend fun insertExecutionLog(log: ExecutionLogEntity) {
        executionLogs += log
    }
}

internal class LoginConcurrencyTracker(
    private val delayMillis: Long = 0L,
) {
    private val activeCalls = AtomicInteger(0)
    private val maxCalls = AtomicInteger(0)

    val maxConcurrentCalls: Int
        get() = maxCalls.get()

    fun <T> track(block: () -> T): T {
        val active = activeCalls.incrementAndGet()
        updateMax(active)
        return try {
            if (delayMillis > 0L) {
                Thread.sleep(delayMillis)
            }
            block()
        } finally {
            activeCalls.decrementAndGet()
        }
    }

    private fun updateMax(active: Int) {
        while (true) {
            val current = maxCalls.get()
            if (active <= current || maxCalls.compareAndSet(current, active)) {
                return
            }
        }
    }
}

internal fun testReservationTask(
    startTimeEpochSeconds: Long = 1_712_800_300L,
    limitSignAgoSeconds: Long = 900L,
    limitSignBackSeconds: Long = 1_800L,
    expectedMinorsCsv: String = "12",
): ReservationTaskEntity =
    ReservationTaskEntity(
        id = "task-1",
        studentId = "20230001",
        roomName = "自习室圆形二楼",
        seatNumber = "166",
        state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
        bookingId = "booking-1",
        startTimeEpochSeconds = startTimeEpochSeconds,
        limitSignAgoSeconds = limitSignAgoSeconds,
        limitSignBackSeconds = limitSignBackSeconds,
        expectedMinorsCsv = expectedMinorsCsv,
        lastError = null,
    )

internal fun testBookingDetail(
    task: ReservationTaskEntity = testReservationTask(),
    expectedMinors: List<Int> = listOf(12),
    isAlreadySignedIn: Boolean = false,
): BookingDetail =
    BookingDetail(
        bookingId = task.bookingId.orEmpty(),
        window =
            CheckInWindow(
                startEpochSeconds = task.startTimeEpochSeconds,
                limitSignAgoSeconds = task.limitSignAgoSeconds,
                limitSignBackSeconds = task.limitSignBackSeconds,
            ),
        expectedMinors = expectedMinors,
        statusLabel = if (isAlreadySignedIn) "已签到" else "待签到",
        isAlreadySignedIn = isAlreadySignedIn,
    )

internal fun successfulCheckInResult(): SeatBookingActionResult =
    SeatBookingActionResult(
        bookingId = "booking-1",
        httpStatus = 200,
        rawMessage = "已签到",
        signInError = null,
    )
