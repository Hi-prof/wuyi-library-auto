package com.wuyi.libraryauto.core.storage.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeatDisplaySnapshotDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var seatDisplaySnapshotDao: SeatDisplaySnapshotDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        seatDisplaySnapshotDao = database.seatDisplaySnapshotDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert then findByStudentId returns snapshot`() = runTest {
        val snapshot =
            SeatDisplaySnapshotEntity(
                studentId = "20230001",
                roomName = "自习室圆形二",
                seatNumber = "58",
                beginLabel = "05-16 08:00",
                liveState = "RESERVED_WAITING_SIGNIN",
                statusLabel = "待签到",
                updatedAtEpochMillis = 1_777_777_777L,
            )

        seatDisplaySnapshotDao.upsert(snapshot)

        assertThat(seatDisplaySnapshotDao.findByStudentId("20230001")).isEqualTo(snapshot)
    }

    @Test
    fun `listAll orders snapshots by room seat then account`() = runTest {
        val secondSeat =
            SeatDisplaySnapshotEntity(
                studentId = "20230002",
                roomName = "room-b",
                seatNumber = "22",
                beginLabel = "",
                liveState = "IDLE",
                statusLabel = "暂无预约",
                updatedAtEpochMillis = 2L,
            )
        val firstSeat =
            SeatDisplaySnapshotEntity(
                studentId = "20230001",
                roomName = "room-a",
                seatNumber = "11",
                beginLabel = "",
                liveState = "IDLE",
                statusLabel = "暂无预约",
                updatedAtEpochMillis = 1L,
            )

        seatDisplaySnapshotDao.upsert(secondSeat)
        seatDisplaySnapshotDao.upsert(firstSeat)

        assertThat(seatDisplaySnapshotDao.listAll())
            .containsExactly(firstSeat, secondSeat)
            .inOrder()
    }
}
