package com.wuyi.libraryauto.core.storage.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomationPlanDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var automationPlanDao: AutomationPlanDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        automationPlanDao = database.automationPlanDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `observeEnabledPlans returns only enabled plans ordered by updated time`() = runTest {
        automationPlanDao.upsert(
            AutomationPlanEntity(
                planId = "plan-1",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                mode = "CONTINUOUS",
                singleDate = null,
                singleStartTime = null,
                singleEndTime = null,
                enabled = true,
                createdAtEpochSeconds = 100L,
                updatedAtEpochSeconds = 110L,
                nextRunAtEpochSeconds = 120L,
                lastRunAtEpochSeconds = null,
                lastResultMessage = "",
            ),
        )
        automationPlanDao.upsert(
            AutomationPlanEntity(
                planId = "plan-2",
                studentId = "20230002",
                roomName = "自习室方形三楼",
                seatNumber = "088",
                mode = "SINGLE_CUSTOM",
                singleDate = "2026-04-12",
                singleStartTime = "08:00",
                singleEndTime = "10:00",
                enabled = false,
                createdAtEpochSeconds = 101L,
                updatedAtEpochSeconds = 111L,
                nextRunAtEpochSeconds = null,
                lastRunAtEpochSeconds = null,
                lastResultMessage = "disabled",
            ),
        )

        assertThat(automationPlanDao.observeEnabledPlans().first()).containsExactly(
            AutomationPlanEntity(
                planId = "plan-1",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                mode = "CONTINUOUS",
                singleDate = null,
                singleStartTime = null,
                singleEndTime = null,
                enabled = true,
                createdAtEpochSeconds = 100L,
                updatedAtEpochSeconds = 110L,
                nextRunAtEpochSeconds = 120L,
                lastRunAtEpochSeconds = null,
                lastResultMessage = "",
            ),
        )
    }
}
