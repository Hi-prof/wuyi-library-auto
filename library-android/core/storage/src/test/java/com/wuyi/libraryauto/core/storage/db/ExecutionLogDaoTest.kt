package com.wuyi.libraryauto.core.storage.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExecutionLogDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var executionLogDao: ExecutionLogDao
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
        executionLogDao = database.executionLogDao()
        reservationTaskDao = database.reservationTaskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `listAllNewestFirst returns logs in reverse time order`() = runTest {
        reservationTaskDao.upsert(task(id = "task-1"))
        reservationTaskDao.upsert(task(id = "task-2"))
        executionLogDao.insert(
            ExecutionLogEntity(
                taskId = "task-1",
                state = ReservationTaskState.PENDING_RESERVATION,
                recordedAtEpochSeconds = 100L,
                message = "older",
            ),
        )
        executionLogDao.insert(
            ExecutionLogEntity(
                taskId = "task-2",
                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                recordedAtEpochSeconds = 200L,
                message = "newer",
            ),
        )

        assertThat(executionLogDao.listAllNewestFirst().map { it.message })
            .containsExactly("newer", "older")
            .inOrder()
    }

    @Test
    fun `clearAll removes persisted logs`() = runTest {
        reservationTaskDao.upsert(task(id = "task-1"))
        executionLogDao.insert(
            ExecutionLogEntity(
                taskId = "task-1",
                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                recordedAtEpochSeconds = 100L,
                message = "exists",
            ),
        )

        executionLogDao.clearAll()

        assertThat(executionLogDao.listAllNewestFirst()).isEmpty()
    }

    private fun task(id: String): ReservationTaskEntity =
        ReservationTaskEntity(
            id = id,
            studentId = "20230001",
            roomName = "自习室圆形二楼",
            seatNumber = "166",
            state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
            bookingId = id,
            startTimeEpochSeconds = 1_712_800_000L,
            limitSignAgoSeconds = 900L,
            expectedMinorsCsv = "",
            lastError = null,
        )
}
