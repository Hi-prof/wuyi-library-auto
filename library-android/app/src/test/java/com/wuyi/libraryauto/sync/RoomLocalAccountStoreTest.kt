package com.wuyi.libraryauto.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.AppDatabase
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class RoomLocalAccountStoreTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var store: RoomLocalAccountStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store =
            RoomLocalAccountStore(
                database = database,
                savedAccountStore =
                    SavedAccountStore(
                        lazyOf(
                            context.getSharedPreferences(
                                "test_room_local_account_store_${System.nanoTime()}",
                                Context.MODE_PRIVATE,
                            ),
                        ),
                    ),
                nowEpochSeconds = { 1_700_000_000L },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `applyAddOrReplace writes server automation tasks into local automation plans`() {
        runBlocking {
            val continuousTask =
                AutomationTaskDto(
                    taskId = 901L,
                    roomName = "三层东区",
                    seatNumber = "A12",
                    mode = AutomationTaskMode.PREFERRED,
                    customWindows = emptyList(),
                    enabled = true,
                    revision = 3L,
                    updatedAt = "2026-04-27T08:00:00Z",
                )
            val singleTask =
                AutomationTaskDto(
                    taskId = 902L,
                    roomName = "四层西区",
                    seatNumber = "B08",
                    mode = AutomationTaskMode.MANUAL,
                    customWindows =
                        listOf(
                            AutomationCustomWindowDto(
                                date = "2026-04-28",
                                startHour = 8,
                                endHour = 12,
                            ),
                        ),
                    enabled = true,
                    revision = 1L,
                    updatedAt = "2026-04-27T09:00:00Z",
                )

            store.applyAddOrReplace(
                ServerAccountSnapshot(
                    accountId = 17L,
                    studentId = "20230001",
                    password = "pw",
                    displayName = "张三",
                    automationTasks = listOf(continuousTask, singleTask),
                ),
            )

            val plans = database.automationPlanDao().observeAll().first()
            assertThat(plans.map(AutomationPlanEntity::planId))
                .containsExactly("server-task:17:901", "server-task:17:902")
            val continuousPlan = plans.single { it.planId == "server-task:17:901" }
            assertThat(continuousPlan.studentId).isEqualTo("20230001")
            assertThat(continuousPlan.roomName).isEqualTo("三层东区")
            assertThat(continuousPlan.seatNumber).isEqualTo("A12")
            assertThat(continuousPlan.mode).isEqualTo("CONTINUOUS")
            assertThat(continuousPlan.singleDate).isNull()
            assertThat(continuousPlan.enabled).isTrue()
            assertThat(continuousPlan.nextRunAtEpochSeconds).isEqualTo(1_700_000_000L)

            val singlePlan = plans.single { it.planId == "server-task:17:902" }
            assertThat(singlePlan.mode).isEqualTo("SINGLE_CUSTOM")
            assertThat(singlePlan.singleDate).isEqualTo("2026-04-28")
            assertThat(singlePlan.singleStartTime).isEqualTo("08:00")
            assertThat(singlePlan.singleEndTime).isEqualTo("12:00")

            val loaded = store.loadAll().single()
            assertThat(loaded.automationTasks.map(AutomationTaskDto::taskId)).containsExactly(901L, 902L)
        }
    }

    @Test
    fun `applyAddOrReplace removes stale server managed plans but keeps local user plans`() {
        runBlocking {
            val planDao = database.automationPlanDao()
            planDao.upsert(existingPlan("server-task:17:900", studentId = "20230001", roomName = "旧服务端任务"))
            planDao.upsert(existingPlan("local-user-plan", studentId = "20230001", roomName = "本地自建任务"))

            store.applyAddOrReplace(
                ServerAccountSnapshot(
                    accountId = 17L,
                    studentId = "20230001",
                    password = "pw",
                    displayName = "张三",
                    automationTasks =
                        listOf(
                            AutomationTaskDto(
                                taskId = 901L,
                                roomName = "新服务端任务",
                                seatNumber = "A12",
                                mode = AutomationTaskMode.PREFERRED,
                                customWindows = emptyList(),
                                enabled = true,
                                revision = 1L,
                                updatedAt = "2026-04-27T08:00:00Z",
                            ),
                        ),
                ),
            )

            val plans = planDao.observeAll().first()
            assertThat(plans.map(AutomationPlanEntity::planId))
                .containsExactly("server-task:17:901", "local-user-plan")
        }
    }

    private fun existingPlan(
        planId: String,
        studentId: String,
        roomName: String,
    ): AutomationPlanEntity =
        AutomationPlanEntity(
            planId = planId,
            studentId = studentId,
            roomName = roomName,
            seatNumber = "A01",
            mode = "CONTINUOUS",
            singleDate = null,
            singleStartTime = null,
            singleEndTime = null,
            enabled = true,
            createdAtEpochSeconds = 1_699_999_000L,
            updatedAtEpochSeconds = 1_699_999_000L,
            nextRunAtEpochSeconds = 1_699_999_000L,
            lastRunAtEpochSeconds = null,
            lastResultMessage = "",
        )
}
