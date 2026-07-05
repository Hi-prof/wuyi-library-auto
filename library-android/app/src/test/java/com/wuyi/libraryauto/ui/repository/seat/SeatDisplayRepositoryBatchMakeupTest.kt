package com.wuyi.libraryauto.ui.repository.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotEntity
import com.wuyi.libraryauto.ui.repository.session.InMemorySessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.HistorySource
import com.wuyi.libraryauto.ui.repository.task.ReservationHistoryHit
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SeatDisplayRepositoryBatchMakeupTest {

    @Test
    fun batchMakeupReservation_resolvesRoomIdAndEntryUrlBeforeReserve() = runTest {
        val gateway = FakeManualReservationGateway()
        val lookupRepository = FakeSeatLookupRepository()
        val repository =
            repository(
                lookupRepository = lookupRepository,
                gateway = gateway,
            )

        val result = repository.batchMakeupReservation()

        assertThat(result.total).isEqualTo(3)
        assertThat(result.success).isEqualTo(3)
        assertThat(result.failed).isEqualTo(0)
        assertThat(lookupRepository.queries).hasSize(3)
        assertThat(gateway.selections).hasSize(3)
        assertThat(gateway.selections.map(ManualReservationSelection::entryUrl))
            .containsExactly(SEAT_ENTRY_URL, SEAT_ENTRY_URL, SEAT_ENTRY_URL)
        assertThat(gateway.selections.map(ManualReservationSelection::roomId))
            .containsExactly("room-a", "room-a", "room-a")
    }

    @Test
    fun batchMakeupReservation_skipsTargetDateWithExistingActiveBooking() = runTest {
        val today = LocalDate.of(2026, 7, 4)
        val taskDao =
            FakeReservationTaskDao(
                tasks =
                    listOf(
                        task(
                            id = "existing",
                            startDate = today,
                            state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                        ),
                    ),
            )
        val gateway = FakeManualReservationGateway()
        val repository =
            repository(
                taskDao = taskDao,
                gateway = gateway,
            )

        val result = repository.batchMakeupReservation()

        assertThat(result.total).isEqualTo(3)
        assertThat(result.success).isEqualTo(2)
        assertThat(result.failed).isEqualTo(0)
        assertThat(result.details.count { it.skipped }).isEqualTo(1)
        assertThat(result.details.single { it.skipped }.targetDate).isEqualTo(today)
        assertThat(gateway.selections.map { it.beginTimeEpochSeconds.toTargetDate() })
            .containsExactly(today.plusDays(1), today.plusDays(2))
            .inOrder()
    }

    private fun repository(
        taskDao: FakeReservationTaskDao = FakeReservationTaskDao(),
        lookupRepository: FakeSeatLookupRepository = FakeSeatLookupRepository(),
        gateway: FakeManualReservationGateway = FakeManualReservationGateway(),
    ): SeatDisplayRepository =
        SeatDisplayRepository(
            accountRepository = FakeSavedAccountRepository(listOf(SavedAccountEntry(studentId = STUDENT_ID, password = ""))),
            sessionRepository = InMemorySessionRepository(),
            reservationTaskDao = taskDao,
            seatDisplaySnapshotDao = FakeSeatDisplaySnapshotDao(),
            accountSeatActionExecutor = null,
            smartSeatRecommender = SmartSeatRecommender(FakeHistoryReader()),
            manualReservationGateway = gateway,
            seatLookupRepository = lookupRepository,
            seatEntryUrls = listOf(SEAT_ENTRY_URL),
            clockMillis = { FIXED_NOW_MILLIS },
        )

    private class FakeSavedAccountRepository(
        private val entries: List<SavedAccountEntry>,
    ) : SavedAccountRepository {
        override fun readAll(): List<SavedAccountEntry> = entries

        override fun remove(studentId: String) = Unit
    }

    private class FakeHistoryReader : AccountReservationHistoryReader {
        override suspend fun loadHistory(studentId: String): List<ReservationHistoryHit> =
            listOf(
                ReservationHistoryHit(
                    roomName = "A区",
                    seatNumber = "001",
                    source = HistorySource.RESERVATION_TASK,
                    timestampEpochSeconds = 100,
                ),
            )
    }

    private class FakeSeatLookupRepository : SeatLookupRepository {
        val queries = mutableListOf<SeatLookupQuery>()

        override suspend fun loadDefaultSeats(): SeatLookupLoadResult =
            error("Batch makeup should use loadSeats(query)")

        override suspend fun loadSeats(query: SeatLookupQuery): SeatLookupLoadResult {
            queries.add(query)
            return SeatLookupLoadResult.Success(
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
    }

    private class FakeManualReservationGateway : ManualReservationGateway {
        val selections = mutableListOf<ManualReservationSelection>()

        override suspend fun reserve(selection: ManualReservationSelection): ManualReservationResult {
            selections.add(selection)
            return ManualReservationResult.Success(
                taskId = "task-${selections.size}",
                bookingId = "booking-${selections.size}",
                message = "已提交预约",
            )
        }
    }

    private class FakeReservationTaskDao(
        private val tasks: List<ReservationTaskEntity> = emptyList(),
    ) : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit

        override suspend fun findById(id: String): ReservationTaskEntity? = tasks.firstOrNull { it.id == id }

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? =
            tasks.filter { it.studentId == studentId }.maxByOrNull { it.startTimeEpochSeconds }

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> =
            tasks.filter { it.studentId == studentId }.sortedByDescending { it.startTimeEpochSeconds }

        override fun observeAll(): Flow<List<ReservationTaskEntity>> = MutableStateFlow(tasks)

        override suspend fun listAll(): List<ReservationTaskEntity> = tasks
    }

    private class FakeSeatDisplaySnapshotDao : SeatDisplaySnapshotDao {
        override suspend fun upsert(snapshot: SeatDisplaySnapshotEntity) = Unit

        override suspend fun findByStudentId(studentId: String): SeatDisplaySnapshotEntity? = null

        override suspend fun listAll(): List<SeatDisplaySnapshotEntity> = emptyList()
    }

    private companion object {
        const val STUDENT_ID = "20230001"
        const val SEAT_ENTRY_URL = "https://seat.example.com/"
        val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val FIXED_NOW_MILLIS: Long =
            LocalDate.of(2026, 7, 4).atStartOfDay(SHANGHAI_ZONE).toInstant().toEpochMilli()

        fun task(
            id: String,
            startDate: LocalDate,
            state: ReservationTaskState,
        ): ReservationTaskEntity =
            ReservationTaskEntity(
                id = id,
                studentId = STUDENT_ID,
                roomName = "A区",
                seatNumber = "001",
                state = state,
                bookingId = id,
                startTimeEpochSeconds = startDate.atTime(8, 0).atZone(SHANGHAI_ZONE).toEpochSecond(),
                limitSignAgoSeconds = 900,
                expectedMinorsCsv = "",
                lastError = null,
            )

        fun Int.toTargetDate(): LocalDate =
            java.time.Instant
                .ofEpochSecond(toLong())
                .atZone(SHANGHAI_ZONE)
                .toLocalDate()
    }
}
