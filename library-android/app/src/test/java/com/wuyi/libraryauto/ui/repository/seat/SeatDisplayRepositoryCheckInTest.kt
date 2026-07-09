package com.wuyi.libraryauto.ui.repository.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotEntity
import com.wuyi.libraryauto.ui.repository.session.InMemorySessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountSeatAction
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutionResult
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.SeatBookingSnapshotView
import com.wuyi.libraryauto.ui.screen.seat.SeatDisplayCardUiState
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SeatDisplayRepositoryCheckInTest {

    @Test
    fun signInWaitingCard_skipsRemoteCheckInWhenWindowIsNotOpen() = runTest {
        val executor = CountingAccountSeatActionExecutor()
        val repository = repository(accountSeatActionExecutor = executor)

        val card =
            repository.signInWaitingCard(
                SeatDisplayCardUiState(
                    studentId = STUDENT_ID,
                    liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                    checkinWindowOpen = false,
                    statusLabel = "待签到",
                ),
            )

        assertThat(card.statusLabel).isEqualTo("未到签到时间")
        assertThat(card.failureMessage).isNull()
        assertThat(executor.performActionCallCount).isEqualTo(0)
    }

    @Test
    fun batchCheckIn_refreshesSnapshotAndSkipsWaitingBookingWhenWindowIsNotOpen() = runTest {
        val executor =
            CountingAccountSeatActionExecutor(
                snapshot =
                    SeatBookingSnapshotView(
                        liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                        bookingId = "booking-1",
                        roomName = "自习室圆形二楼",
                        seatNumber = "64",
                        beginLabel = "2026-07-10 08:00",
                        statusLabel = "待签到",
                        checkinWindowOpen = false,
                    ),
            )
        val repository =
            repository(
                accountSeatActionExecutor = executor,
                seatDisplaySnapshotDao =
                    FakeSeatDisplaySnapshotDao(
                        snapshots =
                            listOf(
                                SeatDisplaySnapshotEntity(
                                    studentId = STUDENT_ID,
                                    roomName = "自习室圆形二楼",
                                    seatNumber = "64",
                                    beginLabel = "2026-07-10 08:00",
                                    liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN.name,
                                    statusLabel = "待签到",
                                    updatedAtEpochMillis = 1_000L,
                                ),
                            ),
                    ),
            )

        val result = repository.batchCheckIn()

        assertThat(result.total).isEqualTo(0)
        assertThat(executor.loadSnapshotCallCount).isEqualTo(1)
        assertThat(executor.performActionCallCount).isEqualTo(0)
    }

    private fun repository(
        accountSeatActionExecutor: AccountSeatActionExecutor,
        seatDisplaySnapshotDao: SeatDisplaySnapshotDao = FakeSeatDisplaySnapshotDao(),
    ): SeatDisplayRepository =
        SeatDisplayRepository(
            accountRepository = FakeSavedAccountRepository(listOf(SavedAccountEntry(studentId = STUDENT_ID, password = ""))),
            sessionRepository = InMemorySessionRepository(),
            reservationTaskDao = FakeReservationTaskDao(),
            seatDisplaySnapshotDao = seatDisplaySnapshotDao,
            accountSeatActionExecutor = accountSeatActionExecutor,
            smartSeatRecommender = null,
            manualReservationGateway = null,
            clockMillis = { 1_000L },
        )

    private class CountingAccountSeatActionExecutor(
        private val snapshot: SeatBookingSnapshotView =
            SeatBookingSnapshotView(
                liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                bookingId = "booking-1",
                statusLabel = "待签到",
                checkinWindowOpen = false,
            ),
    ) : AccountSeatActionExecutor {
        var loadSnapshotCallCount: Int = 0
            private set
        var performActionCallCount: Int = 0
            private set

        override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView {
            loadSnapshotCallCount += 1
            return snapshot
        }

        override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> =
            listOf(snapshot)

        override suspend fun performAction(
            studentId: String,
            action: AccountSeatAction,
            bookingId: String?,
        ): AccountSeatActionExecutionResult {
            performActionCallCount += 1
            return AccountSeatActionExecutionResult(
                message = "已签到",
                updatedSnapshot = snapshot.copy(liveState = SeatBookingLiveState.ACTIVE_SIGNED_IN),
            )
        }
    }

    private class FakeSavedAccountRepository(
        private val entries: List<SavedAccountEntry>,
    ) : SavedAccountRepository {
        override fun readAll(): List<SavedAccountEntry> = entries

        override fun remove(studentId: String) = Unit
    }

    private class FakeReservationTaskDao : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit

        override suspend fun findById(id: String): ReservationTaskEntity? = null

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? = null

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> = emptyList()

        override fun observeAll(): Flow<List<ReservationTaskEntity>> = MutableStateFlow(emptyList())

        override suspend fun listAll(): List<ReservationTaskEntity> = emptyList()
    }

    private class FakeSeatDisplaySnapshotDao(
        snapshots: List<SeatDisplaySnapshotEntity> = emptyList(),
    ) : SeatDisplaySnapshotDao {
        private val state = snapshots.toMutableList()

        override suspend fun upsert(snapshot: SeatDisplaySnapshotEntity) {
            state.removeAll { it.studentId == snapshot.studentId }
            state += snapshot
        }

        override suspend fun findByStudentId(studentId: String): SeatDisplaySnapshotEntity? =
            state.firstOrNull { it.studentId == studentId }

        override suspend fun listAll(): List<SeatDisplaySnapshotEntity> = state
    }

    private companion object {
        private const val STUDENT_ID = "20230001"
    }
}
