package com.wuyi.libraryauto.ui.repository.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInRunGate
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.ui.repository.session.InMemorySessionRepository
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInRowStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RunPeriodicCheckInBatchRunnerTest {

    @Test
    fun runBatch_skipsRemoteCheckInWhenSnapshotWindowIsNotOpen() = runTest {
        val executor = CountingAccountSeatActionExecutor()
        val runner =
            RunPeriodicCheckInBatchRunner(
                accountSource = FakeStoredAccountSource(),
                sessionRepository = loggedInSessionRepository(),
                reservationTaskDao =
                    FakeReservationTaskDao(
                        listOf(
                            ReservationTaskEntity(
                                id = "task-1",
                                studentId = STUDENT_ID,
                                roomName = "自习室圆形二楼",
                                seatNumber = "64",
                                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                                bookingId = "booking-1",
                                startTimeEpochSeconds = 1_000L,
                                limitSignAgoSeconds = 1_800L,
                                limitSignBackSeconds = 1_800L,
                                expectedMinorsCsv = "",
                                lastError = null,
                            ),
                        ),
                    ),
                accountSeatActionExecutor = executor,
                nowEpochSeconds = { 1_000L },
                runGate = PeriodicCheckInRunGate(),
            )

        val report = runner.runBatch {}

        assertThat(report.rows.single().status).isEqualTo(BatchCheckInRowStatus.Skipped)
        assertThat(report.rows.single().message).isEqualTo("未到签到时间")
        assertThat(executor.loadSnapshotCallCount).isEqualTo(1)
        assertThat(executor.performActionCallCount).isEqualTo(0)
    }

    @Test
    fun retry_skipsRemoteCheckInWhenSnapshotWindowIsNotOpen() = runTest {
        val executor = CountingAccountSeatActionExecutor()
        val runner =
            RunPeriodicCheckInBatchRunner(
                accountSource = FakeStoredAccountSource(),
                sessionRepository = loggedInSessionRepository(),
                reservationTaskDao = FakeReservationTaskDao(emptyList()),
                accountSeatActionExecutor = executor,
                runGate = PeriodicCheckInRunGate(),
            )

        val row = runner.retry(STUDENT_ID)

        assertThat(row.status).isEqualTo(BatchCheckInRowStatus.Skipped)
        assertThat(row.message).isEqualTo("未到签到时间")
        assertThat(executor.loadSnapshotCallCount).isEqualTo(1)
        assertThat(executor.performActionCallCount).isEqualTo(0)
    }

    private class FakeStoredAccountSource : StoredAccountSource {
        override fun readStoredAccounts(): List<StoredAccountSnapshot> =
            listOf(StoredAccountSnapshot(studentId = STUDENT_ID, password = "pw"))
    }

    private class CountingAccountSeatActionExecutor : AccountSeatActionExecutor {
        var loadSnapshotCallCount: Int = 0
            private set
        var performActionCallCount: Int = 0
            private set

        override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView {
            loadSnapshotCallCount += 1
            return SeatBookingSnapshotView(
                liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                bookingId = "booking-1",
                roomName = "自习室圆形二楼",
                seatNumber = "64",
                beginLabel = "2026-07-10 08:00",
                statusLabel = "待签到",
                checkinWindowOpen = false,
            )
        }

        override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> =
            listOf(loadSnapshot(studentId))

        override suspend fun performAction(
            studentId: String,
            action: AccountSeatAction,
            bookingId: String?,
        ): AccountSeatActionExecutionResult {
            performActionCallCount += 1
            return AccountSeatActionExecutionResult(message = "已签到")
        }
    }

    private class FakeReservationTaskDao(
        private val tasks: List<ReservationTaskEntity>,
    ) : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit

        override suspend fun findById(id: String): ReservationTaskEntity? =
            tasks.firstOrNull { it.id == id }

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? =
            tasks.filter { it.studentId == studentId }.maxByOrNull { it.startTimeEpochSeconds }

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> =
            tasks.filter { it.studentId == studentId }

        override fun observeAll(): Flow<List<ReservationTaskEntity>> = MutableStateFlow(tasks)

        override suspend fun listAll(): List<ReservationTaskEntity> = tasks
    }

    private fun loggedInSessionRepository(): InMemorySessionRepository =
        InMemorySessionRepository().apply {
            save(
                studentId = STUDENT_ID,
                session =
                    AuthenticatedSession(
                        session = SessionBundle(cookieHeader = "auth=token", userId = STUDENT_ID),
                        cookies = emptyList(),
                        currentUserJson = """{"id":"$STUDENT_ID"}""",
                        origin = "https://example.com",
                        installationId = "install-$STUDENT_ID",
                    ),
            )
        }

    private companion object {
        private const val STUDENT_ID = "20230001"
    }
}
