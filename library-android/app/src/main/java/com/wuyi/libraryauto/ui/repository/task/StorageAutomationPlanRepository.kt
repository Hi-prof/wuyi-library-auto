package com.wuyi.libraryauto.ui.repository.task

import com.wuyi.libraryauto.core.domain.usecase.BuildContinuousReservationWindowsUseCase
import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface AutomationPlanScheduleWriter {
    fun schedule(
        planId: String,
        nextRunAtEpochSeconds: Long,
    )

    fun cancel(planId: String) = Unit
}

class StorageAutomationPlanRepository(
    private val automationPlanDao: AutomationPlanDao,
    private val accountPreferenceWriter: AccountPreferenceWriter,
    private val scheduleWriter: AutomationPlanScheduleWriter? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val buildContinuousReservationWindowsUseCase: BuildContinuousReservationWindowsUseCase =
        BuildContinuousReservationWindowsUseCase(),
) : AutomationPlanRepository {
    override fun observePlans(): Flow<List<AutomationPlanRecord>> =
        automationPlanDao.observeAll().map { plans -> plans.map(::toRecord) }

    override suspend fun savePlan(draft: AutomationPlanDraft): AutomationPlanRecord {
        val nowEpochSeconds = clock.instant().epochSecond
        val studentId = draft.studentId.trim()
        val existingPlan = automationPlanDao.findLatestEnabledByStudentId(studentId)
        val plan =
            AutomationPlanEntity(
                planId = draft.planId.ifBlank { existingPlan?.planId ?: UUID.randomUUID().toString() },
                studentId = studentId,
                roomName = draft.roomName.trim(),
                seatNumber = draft.seatNumber.trim(),
                mode = draft.mode.name,
                singleDate = draft.singleDate,
                singleStartTime = draft.singleStartTime,
                singleEndTime = draft.singleEndTime,
                enabled = true,
                createdAtEpochSeconds = existingPlan?.createdAtEpochSeconds ?: nowEpochSeconds,
                updatedAtEpochSeconds = nowEpochSeconds,
                nextRunAtEpochSeconds = nowEpochSeconds,
                lastRunAtEpochSeconds = existingPlan?.lastRunAtEpochSeconds,
                lastResultMessage = existingPlan?.lastResultMessage.orEmpty(),
            )
        automationPlanDao.upsert(plan)
        scheduleWriter?.schedule(plan.planId, nowEpochSeconds)
        accountPreferenceWriter.updatePreferredSeat(
            studentId = plan.studentId,
            preferredRoomName = plan.roomName,
            preferredSeatNumber = plan.seatNumber,
        )
        return toRecord(plan)
    }

    override suspend fun deletePlan(planId: String) {
        automationPlanDao.deleteById(planId)
        scheduleWriter?.cancel(planId)
    }

    private fun toRecord(plan: AutomationPlanEntity): AutomationPlanRecord =
        AutomationPlanRecord(
            planId = plan.planId,
            studentId = plan.studentId,
            roomName = plan.roomName,
            seatNumber = plan.seatNumber,
            mode = AutomationTaskMode.valueOf(plan.mode),
            singleDate = plan.singleDate,
            singleStartTime = plan.singleStartTime,
            singleEndTime = plan.singleEndTime,
            enabled = plan.enabled,
            previewText = buildPreviewText(plan),
            lastResultMessage = plan.lastResultMessage,
        )

    private fun buildPreviewText(plan: AutomationPlanEntity): String =
        when (AutomationTaskMode.valueOf(plan.mode)) {
            AutomationTaskMode.CONTINUOUS -> buildContinuousPreview()
            AutomationTaskMode.SINGLE_CUSTOM -> buildSinglePreview(plan)
        }

    private fun buildContinuousPreview(): String {
        val now = LocalDateTime.now(clock)
        val windows = buildContinuousReservationWindowsUseCase(now)
        return windows.joinToString("；") { window ->
            "${window.targetDate} ${window.startHour}:00-${window.endHour}:00"
        }
    }

    private fun buildSinglePreview(plan: AutomationPlanEntity): String {
        val date = plan.singleDate.orEmpty()
        val startTime = plan.singleStartTime.orEmpty()
        val endTime = plan.singleEndTime.orEmpty()
        return listOf(date, "$startTime-$endTime".trim('-'))
            .filter(String::isNotBlank)
            .joinToString(" ")
    }
}
