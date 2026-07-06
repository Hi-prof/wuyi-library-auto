package com.wuyi.libraryauto.core.runtime.worker

import androidx.work.ListenableWorker.Result
import com.wuyi.libraryauto.core.ble.BleScanOutcome
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionResult
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.network.seat.SeatBookingSnapshot
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReservationGuardWorkerTest {

    @Test
    fun `executeOnce signs in booking when checkin window is open`() = runTest {
        val dependencies = FakeReservationGuardWorkerDependencies()

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "task-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertEquals(Result.success(), result)
        assertEquals(ReservationTaskState.SIGNIN_SUCCESS, dependencies.savedTask?.state)
        assertEquals("已签到", dependencies.executionLogs.last().message)
    }

    @Test
    fun `executeOnce requeues guard when booking is not yet in checkin window`() = runTest {
        val dependencies =
            FakeReservationGuardWorkerDependencies(
                bookingSnapshot =
                    SeatBookingSnapshot(
                        liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                        bookingId = "booking-1",
                        roomName = "自习室圆形二楼",
                        seatNumber = "166",
                        beginLabel = "2026-04-14 15:00",
                        statusLabel = "待签到",
                        checkinWindowOpen = false,
                    ),
            )
        var retryAtEpochSeconds: Long? = null

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "task-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
                scheduleRetry = { _, runAtEpochSeconds -> retryAtEpochSeconds = runAtEpochSeconds },
            )

        assertEquals(Result.success(), result)
        assertEquals(ReservationTaskState.GUARD_SCHEDULED, dependencies.savedTask?.state)
        assertEquals(1_712_800_060L, retryAtEpochSeconds)
    }

    @Test
    fun `executeOnce loads task by local id and signs in with remote booking id`() = runTest {
        val dependencies = FakeReservationGuardWorkerDependencies()

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "20230001:booking-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertEquals(Result.success(), result)
        assertEquals("20230001:booking-1", dependencies.lastFindTaskId)
        assertEquals("booking-1", dependencies.lastCheckInBookingId)
    }

    private class FakeReservationGuardWorkerDependencies(
        private val bookingSnapshot: SeatBookingSnapshot? =
            SeatBookingSnapshot(
                liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                bookingId = "booking-1",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                beginLabel = "2026-04-14 15:00",
                statusLabel = "待签到",
                checkinWindowOpen = true,
            ),
    ) : ReservationGuardWorkerDependencies {
        private val session =
            AuthenticatedSession(
                session = SessionBundle(cookieHeader = "auth=token", userId = "20230001"),
                cookies = emptyList(),
                currentUserJson = """{"id":"20230001"}""",
                origin = "https://example.com",
                installationId = "install-1",
            )

        var savedTask: ReservationTaskEntity? = null
        val executionLogs = mutableListOf<ExecutionLogEntity>()
        var lastFindTaskId: String? = null
        var lastCheckInBookingId: String? = null
        var loginCallCount = 0
        var refreshLoginCallCount = 0
        var scanOutcome: BleScanOutcome =
            BleScanOutcome.Matched(
                matchedMinor = 12,
                seenMinors = listOf(12),
                durationMillis = 100L,
            )
        val signInAuditRecords = mutableListOf<SignInAuditRecord>()

        override suspend fun findTask(taskId: String): ReservationTaskEntity? {
            lastFindTaskId = taskId
            return ReservationTaskEntity(
                id = taskId,
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                bookingId = "booking-1",
                startTimeEpochSeconds = 1_712_800_300L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "",
                lastError = null,
            )
        }

        override fun loadSavedAccount(studentId: String): SavedAccountStore.SavedAccount? =
            SavedAccountStore.SavedAccount(
                studentId = studentId,
                password = "secret",
                preferredRoomName = "自习室圆形二楼",
                preferredSeatNumber = "166",
            )

        override fun login(
            studentId: String,
            password: String,
        ): AuthenticatedSession {
            loginCallCount += 1
            return session
        }

        override fun loadBooking(
            task: ReservationTaskEntity,
            session: AuthenticatedSession,
        ): SeatBookingSnapshot? = bookingSnapshot

        override fun loadBookingDetail(
            task: ReservationTaskEntity,
            session: AuthenticatedSession,
        ): BookingDetail? {
            val snapshot = bookingSnapshot ?: return null
            return BookingDetail(
                bookingId = snapshot.bookingId ?: task.bookingId.orEmpty(),
                window =
                    CheckInWindow(
                        startEpochSeconds = task.startTimeEpochSeconds,
                        limitSignAgoSeconds = if (snapshot.checkinWindowOpen) task.limitSignAgoSeconds else 0L,
                        limitSignBackSeconds = task.limitSignBackSeconds,
                    ),
                expectedMinors = listOf(12),
                statusLabel = snapshot.statusLabel,
                isAlreadySignedIn = snapshot.liveState == SeatBookingLiveState.ACTIVE_SIGNED_IN,
            )
        }

        override suspend fun scanAndMatch(
            bookingId: String,
            expectedMinors: Set<Int>,
        ): BleScanOutcome = scanOutcome

        override fun checkIn(
            session: AuthenticatedSession,
            bookingId: String,
        ): SeatBookingActionResult {
            lastCheckInBookingId = bookingId
            return SeatBookingActionResult(
                bookingId = bookingId,
                httpStatus = 200,
                rawMessage = "已签到",
                signInError = null,
            )
        }

        override fun refreshLogin(account: SavedAccountStore.SavedAccount): Boolean {
            refreshLoginCallCount += 1
            return true
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
}
