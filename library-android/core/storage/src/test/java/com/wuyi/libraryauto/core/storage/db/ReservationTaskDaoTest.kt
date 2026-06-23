package com.wuyi.libraryauto.core.storage.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReservationTaskDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var reservationTaskDao: ReservationTaskDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        reservationTaskDao = database.reservationTaskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `dao contract exposes suspend upsert and findById`() {
        val methods = ReservationTaskDao::class.java.declaredMethods.associateBy { it.name }

        assertThat(methods.getValue("upsert").parameterTypes.last().name)
            .isEqualTo("kotlin.coroutines.Continuation")
        assertThat(methods.getValue("findById").parameterTypes.last().name)
            .isEqualTo("kotlin.coroutines.Continuation")
    }

    @Test
    fun `upsert then findById returns reserved waiting signin task`() = runTest {
        val task =
            ReservationTaskEntity(
                id = "task-001",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "58",
                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                bookingId = "booking-001",
                startTimeEpochSeconds = 1_712_800_000L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "5,10",
                lastError = null,
            )

        reservationTaskDao.upsert(task)

        assertThat(reservationTaskDao.findById("task-001")).isEqualTo(task)
    }

    @Test
    fun `upsert overwrites existing task with same id`() = runTest {
        reservationTaskDao.upsert(
            ReservationTaskEntity(
                id = "task-003",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "11",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 1_712_700_000L,
                limitSignAgoSeconds = 1_800L,
                expectedMinorsCsv = "5",
                lastError = null,
            )
        )

        val updated =
            ReservationTaskEntity(
                id = "task-003",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "12",
                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                bookingId = "booking-003",
                startTimeEpochSeconds = 1_712_710_000L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "5,10",
                lastError = "retry once",
            )

        reservationTaskDao.upsert(updated)

        assertThat(reservationTaskDao.findById("task-003")).isEqualTo(updated)
    }

    @Test
    fun `observeAll emits inserted tasks`() = runTest {
        val task =
            ReservationTaskEntity(
                id = "task-002",
                studentId = "20230002",
                roomName = "自习室方形三楼",
                seatNumber = "88",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 1_712_900_000L,
                limitSignAgoSeconds = 1_200L,
                expectedMinorsCsv = "15",
                lastError = "network",
            )

        reservationTaskDao.upsert(task)

        assertThat(reservationTaskDao.observeAll().first()).containsExactly(task)
    }

    @Test
    fun `observeAll orders by start time then id`() = runTest {
        val lateTask =
            ReservationTaskEntity(
                id = "task-c",
                studentId = "20230003",
                roomName = "自习室圆形二楼",
                seatNumber = "33",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 300L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "",
                lastError = null,
            )
        val sameTimeHigherId =
            ReservationTaskEntity(
                id = "task-b",
                studentId = "20230002",
                roomName = "自习室方形三楼",
                seatNumber = "22",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 100L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "",
                lastError = null,
            )
        val sameTimeLowerId =
            ReservationTaskEntity(
                id = "task-a",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "11",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 100L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "",
                lastError = null,
            )

        reservationTaskDao.upsert(lateTask)
        reservationTaskDao.upsert(sameTimeHigherId)
        reservationTaskDao.upsert(sameTimeLowerId)

        assertThat(reservationTaskDao.observeAll().first())
            .containsExactly(sameTimeLowerId, sameTimeHigherId, lateTask)
            .inOrder()
    }

    @Test
    fun `listAll returns persisted tasks ordered by start time then id`() = runTest {
        val lateTask =
            ReservationTaskEntity(
                id = "task-c",
                studentId = "20230003",
                roomName = "自习室圆形二楼",
                seatNumber = "33",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 300L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "",
                lastError = null,
            )
        val sameTimeHigherId =
            ReservationTaskEntity(
                id = "task-b",
                studentId = "20230002",
                roomName = "自习室方形三楼",
                seatNumber = "22",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 100L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "",
                lastError = null,
            )
        val sameTimeLowerId =
            ReservationTaskEntity(
                id = "task-a",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "11",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 100L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "",
                lastError = null,
            )

        reservationTaskDao.upsert(lateTask)
        reservationTaskDao.upsert(sameTimeHigherId)
        reservationTaskDao.upsert(sameTimeLowerId)

        assertThat(reservationTaskDao.listAll())
            .containsExactly(sameTimeLowerId, sameTimeHigherId, lateTask)
            .inOrder()
    }

    @Test
    fun `reservation task persists student id and room name`() = runTest {
        val task =
            ReservationTaskEntity(
                id = "task-010",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                state = ReservationTaskState.PENDING_RESERVATION,
                bookingId = null,
                startTimeEpochSeconds = 1_712_800_000L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "",
                lastError = null,
            )

        reservationTaskDao.upsert(task)

        assertThat(reservationTaskDao.findById("task-010")).isEqualTo(task)
    }
}
