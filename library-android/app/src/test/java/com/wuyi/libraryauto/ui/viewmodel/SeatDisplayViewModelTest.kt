package com.wuyi.libraryauto.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotEntity
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationGateway
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationResult
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationSelection
import com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupData
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupLoadResult
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupQuery
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatRoomSnapshot
import com.wuyi.libraryauto.ui.repository.seat.SmartSeatRecommender
import com.wuyi.libraryauto.ui.repository.session.InMemorySessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.HistorySource
import com.wuyi.libraryauto.ui.repository.task.ReservationHistoryHit
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeatDisplayViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun batchMakeupReservation_showsProgressAndResult() = runTest(dispatcher.scheduler) {
        val viewModel =
            SeatDisplayViewModel(
                repository = repository(historyDelayMillis = 500),
                ioDispatcher = dispatcher,
            )

        viewModel.batchMakeupReservation()
        runCurrent()

        assertThat(viewModel.uiState.value.isBatchReserving).isTrue()
        assertThat(viewModel.uiState.value.batchProgressMessage).isEqualTo("正在补约今天和未来 2 天")

        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isBatchReserving).isFalse()
        assertThat(viewModel.uiState.value.batchProgressMessage).isEmpty()
        assertThat(viewModel.uiState.value.lastBatchReservationResult?.success).isEqualTo(3)
    }

    @Test
    fun batchMakeupReservation_reportsFailureMessage() = runTest(dispatcher.scheduler) {
        val viewModel =
            SeatDisplayViewModel(
                repository =
                    repository(
                        gateway =
                            object : ManualReservationGateway {
                                override suspend fun reserve(selection: ManualReservationSelection): ManualReservationResult {
                                    throw IllegalStateException("预约接口不可用")
                                }
                            },
                    ),
                ioDispatcher = dispatcher,
            )

        viewModel.batchMakeupReservation()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isBatchReserving).isFalse()
        assertThat(viewModel.uiState.value.batchErrorMessage).isEqualTo("预约接口不可用")
    }

    private fun repository(
        historyDelayMillis: Long = 0,
        gateway: ManualReservationGateway = SuccessManualReservationGateway(),
    ): SeatDisplayRepository =
        SeatDisplayRepository(
            accountRepository = FakeSavedAccountRepository(listOf(SavedAccountEntry(studentId = "20230001", password = ""))),
            sessionRepository = InMemorySessionRepository(),
            reservationTaskDao = FakeReservationTaskDao(),
            seatDisplaySnapshotDao = FakeSeatDisplaySnapshotDao(),
            accountSeatActionExecutor = null,
            smartSeatRecommender = SmartSeatRecommender(FakeHistoryReader(historyDelayMillis)),
            manualReservationGateway = gateway,
            seatLookupRepository = FakeSeatLookupRepository(),
            seatEntryUrls = listOf("https://seat.example.com/"),
            clockMillis = { FIXED_NOW_MILLIS },
        )

    private class FakeSavedAccountRepository(
        private val entries: List<SavedAccountEntry>,
    ) : SavedAccountRepository {
        override fun readAll(): List<SavedAccountEntry> = entries

        override fun remove(studentId: String) = Unit
    }

    private class FakeHistoryReader(
        private val delayMillis: Long,
    ) : AccountReservationHistoryReader {
        override suspend fun loadHistory(studentId: String): List<ReservationHistoryHit> {
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            return listOf(
                ReservationHistoryHit(
                    roomName = "A区",
                    seatNumber = "001",
                    source = HistorySource.RESERVATION_TASK,
                    timestampEpochSeconds = 100,
                ),
            )
        }
    }

    private class FakeSeatLookupRepository : SeatLookupRepository {
        override suspend fun loadDefaultSeats(): SeatLookupLoadResult =
            error("SeatDisplayViewModel batch tests should use loadSeats(query)")

        override suspend fun loadSeats(query: SeatLookupQuery): SeatLookupLoadResult =
            SeatLookupLoadResult.Success(
                SeatLookupData(
                    beginTimeEpochSeconds = query.beginTimeEpochSeconds,
                    durationHours = query.durationSeconds / 3600,
                    peopleCount = query.peopleCount,
                    rooms =
                        listOf(
                            SeatRoomSnapshot(
                                roomId = "room-a",
                                roomName = "A区",
                                storey = "1F",
                                availableCount = 1,
                                seatNumbers = listOf("001"),
                                recommendedSeatNumber = "001",
                            ),
                        ),
                ),
            )
    }

    private class SuccessManualReservationGateway : ManualReservationGateway {
        private var count = 0

        override suspend fun reserve(selection: ManualReservationSelection): ManualReservationResult {
            count += 1
            return ManualReservationResult.Success(
                taskId = "task-$count",
                bookingId = "booking-$count",
                message = "已提交预约",
            )
        }
    }

    private class FakeReservationTaskDao : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit

        override suspend fun findById(id: String): ReservationTaskEntity? = null

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? = null

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> = emptyList()

        override fun observeAll(): Flow<List<ReservationTaskEntity>> = MutableStateFlow(emptyList())

        override suspend fun listAll(): List<ReservationTaskEntity> = emptyList()
    }

    private class FakeSeatDisplaySnapshotDao : SeatDisplaySnapshotDao {
        override suspend fun upsert(snapshot: SeatDisplaySnapshotEntity) = Unit

        override suspend fun findByStudentId(studentId: String): SeatDisplaySnapshotEntity? = null

        override suspend fun listAll(): List<SeatDisplaySnapshotEntity> = emptyList()
    }

    private companion object {
        val FIXED_NOW_MILLIS: Long =
            LocalDate
                .of(2026, 7, 4)
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli()
    }
}
