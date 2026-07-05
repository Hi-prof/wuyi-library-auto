package com.wuyi.libraryauto.ui.screen.home

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotEntity
import com.wuyi.libraryauto.ui.repository.home.TodayOverviewRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepository
import com.wuyi.libraryauto.ui.repository.session.InMemorySessionRepository
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSnapshot
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSource
import com.wuyi.libraryauto.ui.viewmodel.MainDispatcherRule
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayOverviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun initLoadsOverviewSnapshot() =
        runTest(dispatcher.scheduler) {
            val viewModel =
                TodayOverviewViewModel(
                    repository = repository(),
                    seatDisplayRepository = seatDisplayRepository(),
                    ioDispatcher = dispatcher,
                )

            advanceUntilIdle()

            assertThat(viewModel.uiState.isLoading).isFalse()
            assertThat(viewModel.uiState.snapshot?.totalAccountCount).isEqualTo(1)
        }

    private fun repository(): TodayOverviewRepository =
        TodayOverviewRepository(
            accountSource = FakeAccountSource("1001"),
            reservationTaskDao = FakeReservationTaskDao(),
            seatDisplaySnapshotDao = FakeSeatDisplaySnapshotDao(),
            clock =
                Clock.fixed(
                    LocalDateTime
                        .of(2026, 7, 4, 10, 0)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant(),
                    ZoneId.of("Asia/Shanghai"),
                ),
        )

    private fun seatDisplayRepository(): SeatDisplayRepository =
        SeatDisplayRepository(
            accountRepository = FakeSavedAccountRepository(),
            sessionRepository = InMemorySessionRepository(),
            reservationTaskDao = FakeReservationTaskDao(),
            seatDisplaySnapshotDao = FakeSeatDisplaySnapshotDao(),
            accountSeatActionExecutor = null,
            smartSeatRecommender = null,
            manualReservationGateway = null,
        )

    private class FakeAccountSource(
        private vararg val studentIds: String,
    ) : StoredAccountSource {
        override fun readStoredAccounts(): List<StoredAccountSnapshot> =
            studentIds.map { studentId -> StoredAccountSnapshot(studentId = studentId) }
    }

    private class FakeSavedAccountRepository : SavedAccountRepository {
        override fun readAll(): List<SavedAccountEntry> =
            listOf(SavedAccountEntry(studentId = "1001", password = ""))

        override fun remove(studentId: String) = Unit
    }

    private class FakeReservationTaskDao : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit

        override suspend fun findById(id: String): ReservationTaskEntity? = null

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? = null

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> = emptyList()

        override fun observeAll(): Flow<List<ReservationTaskEntity>> = flowOf(emptyList())

        override suspend fun listAll(): List<ReservationTaskEntity> = emptyList()
    }

    private class FakeSeatDisplaySnapshotDao : SeatDisplaySnapshotDao {
        override suspend fun upsert(snapshot: SeatDisplaySnapshotEntity) = Unit

        override suspend fun findByStudentId(studentId: String): SeatDisplaySnapshotEntity? = null

        override suspend fun listAll(): List<SeatDisplaySnapshotEntity> = emptyList()
    }
}
