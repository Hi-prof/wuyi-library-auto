package com.wuyi.libraryauto.ui.repository.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.usecase.BuildContinuousReservationWindowsUseCase
import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StorageAutomationPlanRepositoryTest {

    @Test
    fun `savePlan writes plan and updates preferred seat`() = runTest {
        val accountPreferenceWriter = FakeAccountPreferenceWriter()
        val scheduler = FakeAutomationPlanScheduleWriter()
        val repository =
            StorageAutomationPlanRepository(
                automationPlanDao = FakeAutomationPlanDao(),
                accountPreferenceWriter = accountPreferenceWriter,
                scheduleWriter = scheduler,
                clock = Clock.fixed(Instant.parse("2026-04-11T01:30:00Z"), ZoneId.of("Asia/Shanghai")),
                buildContinuousReservationWindowsUseCase = BuildContinuousReservationWindowsUseCase(),
            )

        repository.savePlan(
            AutomationPlanDraft(
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                mode = AutomationTaskMode.CONTINUOUS,
            ),
        )

        val savedPlan = repository.observePlans().first().single()
        assertThat(savedPlan.studentId).isEqualTo("20230001")
        assertThat(savedPlan.seatNumber).isEqualTo("166")
        assertThat(savedPlan.previewText).contains("2026-04-11 10:00-22:00")
        assertThat(savedPlan.previewText).contains("2026-04-13 8:00-22:00")
        assertThat(accountPreferenceWriter.lastUpdate).isEqualTo(
            PreferredSeatUpdate(
                studentId = "20230001",
                preferredRoomName = "自习室圆形二楼",
                preferredSeatNumber = "166",
            ),
        )
        assertThat(scheduler.lastPlanId).isEqualTo(savedPlan.planId)
        assertThat(scheduler.lastRunAtEpochSeconds).isEqualTo(1_775_871_000L)
    }

    @Test
    fun `savePlan preview rounds partial current hour up`() = runTest {
        val repository =
            StorageAutomationPlanRepository(
                automationPlanDao = FakeAutomationPlanDao(),
                accountPreferenceWriter = FakeAccountPreferenceWriter(),
                scheduleWriter = FakeAutomationPlanScheduleWriter(),
                clock = Clock.fixed(Instant.parse("2026-04-11T02:30:00Z"), ZoneId.of("Asia/Shanghai")),
                buildContinuousReservationWindowsUseCase = BuildContinuousReservationWindowsUseCase(),
            )

        repository.savePlan(
            AutomationPlanDraft(
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                mode = AutomationTaskMode.CONTINUOUS,
            ),
        )

        val savedPlan = repository.observePlans().first().single()
        assertThat(savedPlan.previewText)
            .isEqualTo("2026-04-11 11:00-22:00；2026-04-12 8:00-22:00；2026-04-13 8:00-22:00")
    }

    @Test
    fun `savePlan reuses existing enabled plan for the same student`() = runTest {
        val repository =
            StorageAutomationPlanRepository(
                automationPlanDao =
                    FakeAutomationPlanDao(
                        plans =
                            listOf(
                                AutomationPlanEntity(
                                    planId = "plan-1",
                                    studentId = "20230001",
                                    roomName = "旧馆",
                                    seatNumber = "001",
                                    mode = "CONTINUOUS",
                                    singleDate = null,
                                    singleStartTime = null,
                                    singleEndTime = null,
                                    enabled = true,
                                    createdAtEpochSeconds = 100L,
                                    updatedAtEpochSeconds = 100L,
                                    nextRunAtEpochSeconds = 100L,
                                    lastRunAtEpochSeconds = null,
                                    lastResultMessage = "旧计划",
                                ),
                            ),
                    ),
                accountPreferenceWriter = FakeAccountPreferenceWriter(),
                scheduleWriter = FakeAutomationPlanScheduleWriter(),
                clock = Clock.fixed(Instant.parse("2026-04-11T01:30:00Z"), ZoneId.of("Asia/Shanghai")),
                buildContinuousReservationWindowsUseCase = BuildContinuousReservationWindowsUseCase(),
            )

        val saved =
            repository.savePlan(
                AutomationPlanDraft(
                    studentId = "20230001",
                    roomName = "自习室圆形二楼",
                    seatNumber = "166",
                    mode = AutomationTaskMode.CONTINUOUS,
                ),
            )

        val savedPlans = repository.observePlans().first()
        assertThat(saved.planId).isEqualTo("plan-1")
        assertThat(savedPlans.map { it.planId }).containsExactly("plan-1")
        assertThat(savedPlans.single().roomName).isEqualTo("自习室圆形二楼")
    }

    @Test
    fun `deletePlan removes plan and cancels scheduled work`() = runTest {
        val scheduler = FakeAutomationPlanScheduleWriter()
        val repository =
            StorageAutomationPlanRepository(
                automationPlanDao =
                    FakeAutomationPlanDao(
                        plans =
                            listOf(
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
                                    updatedAtEpochSeconds = 100L,
                                    nextRunAtEpochSeconds = 100L,
                                    lastRunAtEpochSeconds = null,
                                    lastResultMessage = "",
                                ),
                            ),
                    ),
                accountPreferenceWriter = FakeAccountPreferenceWriter(),
                scheduleWriter = scheduler,
            )

        repository.deletePlan("plan-1")

        assertThat(repository.observePlans().first()).isEmpty()
        assertThat(scheduler.lastCancelledPlanId).isEqualTo("plan-1")
    }

    private class FakeAutomationPlanDao(
        plans: List<AutomationPlanEntity> = emptyList(),
    ) : AutomationPlanDao {
        private val plans = MutableStateFlow(plans)

        override suspend fun upsert(plan: AutomationPlanEntity) {
            plans.value = listOf(plan) + plans.value.filterNot { it.planId == plan.planId }
        }

        override fun observeAll(): Flow<List<AutomationPlanEntity>> = plans

        override fun observeEnabledPlans(): Flow<List<AutomationPlanEntity>> =
            MutableStateFlow(plans.value.filter { it.enabled })

        override suspend fun findById(planId: String): AutomationPlanEntity? =
            plans.value.firstOrNull { it.planId == planId }

        override suspend fun findLatestEnabledByStudentId(studentId: String): AutomationPlanEntity? =
            plans.value
                .filter { it.studentId == studentId && it.enabled }
                .sortedWith(compareByDescending<AutomationPlanEntity> { it.updatedAtEpochSeconds }.thenBy { it.planId })
                .firstOrNull()

        override suspend fun deleteById(planId: String) {
            plans.value = plans.value.filterNot { it.planId == planId }
        }

        override suspend fun findAll(): List<AutomationPlanEntity> = plans.value

        override suspend fun deleteByPlanIdPrefix(planIdPrefix: String) {
            plans.value = plans.value.filterNot { it.planId.startsWith(planIdPrefix) }
        }
    }

    private class FakeAccountPreferenceWriter : AccountPreferenceWriter {
        var lastUpdate: PreferredSeatUpdate? = null

        override fun updatePreferredSeat(
            studentId: String,
            preferredRoomName: String,
            preferredSeatNumber: String,
        ) {
            lastUpdate =
                PreferredSeatUpdate(
                    studentId = studentId,
                    preferredRoomName = preferredRoomName,
                    preferredSeatNumber = preferredSeatNumber,
                )
        }
    }

    private class FakeAutomationPlanScheduleWriter : AutomationPlanScheduleWriter {
        var lastPlanId: String? = null
        var lastRunAtEpochSeconds: Long? = null
        var lastCancelledPlanId: String? = null

        override fun schedule(
            planId: String,
            nextRunAtEpochSeconds: Long,
        ) {
            lastPlanId = planId
            lastRunAtEpochSeconds = nextRunAtEpochSeconds
        }

        override fun cancel(planId: String) {
            lastCancelledPlanId = planId
        }
    }
}
