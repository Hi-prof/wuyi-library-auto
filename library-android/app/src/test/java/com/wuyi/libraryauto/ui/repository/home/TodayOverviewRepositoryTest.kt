package com.wuyi.libraryauto.ui.repository.home

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotEntity
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSnapshot
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSource
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TodayOverviewRepositoryTest {
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock =
        Clock.fixed(
            LocalDateTime.of(2026, 7, 4, 10, 0).atZone(zoneId).toInstant(),
            zoneId,
        )

    @Test
    fun load_countsOnlyTodayForSavedAccounts() =
        runTest {
            val repository =
                TodayOverviewRepository(
                    accountSource = FakeAccountSource("1001", "1002", "1003"),
                    reservationTaskDao =
                        FakeReservationTaskDao(
                            listOf(
                                task("todaySigned", "1001", ReservationTaskState.SIGNIN_SUCCESS, "2026-07-04T08:00"),
                                task("yesterday", "1001", ReservationTaskState.RESERVED_WAITING_SIGNIN, "2026-07-03T08:00"),
                                task("todayWaiting", "1002", ReservationTaskState.RESERVED_WAITING_SIGNIN, "2026-07-04T09:00"),
                                task("unknownAccount", "9999", ReservationTaskState.SIGNIN_SUCCESS, "2026-07-04T08:30"),
                            ),
                        ),
                    seatDisplaySnapshotDao = FakeSeatDisplaySnapshotDao(),
                    clock = clock,
                )

            val snapshot = repository.load()

            assertThat(snapshot.dateLabel).isEqualTo("2026-07-04")
            assertThat(snapshot.totalAccountCount).isEqualTo(3)
            assertThat(snapshot.reservationAccountCount).isEqualTo(2)
            assertThat(snapshot.totalTaskCount).isEqualTo(2)
            assertThat(snapshot.reservedSeatCount).isEqualTo(2)
            assertThat(snapshot.signedInSeatCount).isEqualTo(1)
            assertThat(snapshot.waitingSignInSeatCount).isEqualTo(1)
            assertThat(snapshot.allSignedIn).isFalse()
            assertThat(snapshot.signInHeadline).isEqualTo("还有 1 个账号未完成")
        }

    @Test
    fun load_marksAllSignedInWhenTodaySeatsAreComplete() =
        runTest {
            val repository =
                TodayOverviewRepository(
                    accountSource = FakeAccountSource("1001", "1002"),
                    reservationTaskDao =
                        FakeReservationTaskDao(
                            listOf(
                                task("first", "1001", ReservationTaskState.SIGNIN_SUCCESS, "2026-07-04T08:00"),
                                task("second", "1002", ReservationTaskState.SIGNIN_SUCCESS, "2026-07-04T09:00"),
                            ),
                        ),
                    seatDisplaySnapshotDao = FakeSeatDisplaySnapshotDao(),
                    clock = clock,
                )

            val snapshot = repository.load()

            assertThat(snapshot.allSignedIn).isTrue()
            assertThat(snapshot.signInHeadline).isEqualTo("今天全部完成签到")
            assertThat(snapshot.signInDetail).isEqualTo("已签到 2 / 2 个座位。")
        }

    @Test
    fun load_countsTodaySeatSnapshotWhenTaskTableHasNoReservation() =
        runTest {
            val repository =
                TodayOverviewRepository(
                    accountSource = FakeAccountSource("1001"),
                    reservationTaskDao = FakeReservationTaskDao(emptyList()),
                    seatDisplaySnapshotDao =
                        FakeSeatDisplaySnapshotDao(
                            listOf(
                                seatSnapshot(
                                    studentId = "1001",
                                    liveState = "RESERVED_WAITING_SIGNIN",
                                    updatedAt = "2026-07-04T09:30",
                                ),
                            ),
                        ),
                    clock = clock,
                )

            val snapshot = repository.load()

            assertThat(snapshot.reservationAccountCount).isEqualTo(1)
            assertThat(snapshot.reservedSeatCount).isEqualTo(1)
            assertThat(snapshot.waitingSignInSeatCount).isEqualTo(1)
        }

    private fun task(
        id: String,
        studentId: String,
        state: ReservationTaskState,
        startTime: String,
    ): ReservationTaskEntity =
        ReservationTaskEntity(
            id = id,
            studentId = studentId,
            roomName = "自习室",
            seatNumber = id.takeLast(3),
            state = state,
            bookingId = "booking-$id",
            startTimeEpochSeconds = LocalDateTime.parse(startTime).atZone(zoneId).toEpochSecond(),
            limitSignAgoSeconds = 900,
            expectedMinorsCsv = "",
            lastError = null,
        )

    private fun seatSnapshot(
        studentId: String,
        liveState: String,
        updatedAt: String,
    ): SeatDisplaySnapshotEntity =
        SeatDisplaySnapshotEntity(
            studentId = studentId,
            roomName = "自习室",
            seatNumber = "101",
            beginLabel = "07-04 09:00",
            liveState = liveState,
            statusLabel = "",
            updatedAtEpochMillis = LocalDateTime.parse(updatedAt).atZone(zoneId).toInstant().toEpochMilli(),
        )

    private class FakeAccountSource(
        private vararg val studentIds: String,
    ) : StoredAccountSource {
        override fun readStoredAccounts(): List<StoredAccountSnapshot> =
            studentIds.map { studentId ->
                StoredAccountSnapshot(studentId = studentId)
            }
    }

    private class FakeReservationTaskDao(
        private val tasks: List<ReservationTaskEntity>,
    ) : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit

        override suspend fun findById(id: String): ReservationTaskEntity? =
            tasks.firstOrNull { it.id == id }

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? =
            tasks
                .filter { it.studentId == studentId }
                .maxByOrNull { it.startTimeEpochSeconds }

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> =
            tasks
                .filter { it.studentId == studentId }
                .sortedByDescending { it.startTimeEpochSeconds }

        override fun observeAll(): Flow<List<ReservationTaskEntity>> = flowOf(tasks)

        override suspend fun listAll(): List<ReservationTaskEntity> = tasks
    }

    private class FakeSeatDisplaySnapshotDao(
        private val snapshots: List<SeatDisplaySnapshotEntity> = emptyList(),
    ) : SeatDisplaySnapshotDao {
        override suspend fun upsert(snapshot: SeatDisplaySnapshotEntity) = Unit

        override suspend fun findByStudentId(studentId: String): SeatDisplaySnapshotEntity? =
            snapshots.firstOrNull { it.studentId == studentId }

        override suspend fun listAll(): List<SeatDisplaySnapshotEntity> = snapshots
    }
}
