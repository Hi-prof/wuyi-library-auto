package com.wuyi.libraryauto.ui.viewmodel

import com.wuyi.libraryauto.ui.repository.seat.SeatRoomSnapshot
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRecord
import com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode

fun filterAutomationPlans(
    plans: List<AutomationPlanRecord>,
    studentFilter: String,
    reservationChecks: Map<String, AutomationTaskReservationCheckUiState> = emptyMap(),
): List<AutomationTaskPlanUiModel> {
    val safeFilter = studentFilter.trim()
    return plans
        .asSequence()
        .filter { plan -> safeFilter.isBlank() || plan.studentId == safeFilter }
        .map { plan -> plan.toAutomationTaskPlanUiModel(reservationChecks[plan.planId]) }
        .toList()
}

fun AutomationPlanRecord.toAutomationTaskPlanUiModel(
    reservationCheck: AutomationTaskReservationCheckUiState? = null,
): AutomationTaskPlanUiModel =
    AutomationTaskPlanUiModel(
        planId = planId,
        studentId = studentId,
        title = "$roomName $seatNumber 号座位",
        roomName = roomName,
        seatNumber = seatNumber,
        previewText = previewText,
        lastResultMessage = lastResultMessage,
        modeLabel =
            if (mode == AutomationTaskMode.CONTINUOUS) {
                "持续预约 + 自动签到"
            } else {
                "单次时间段"
        },
        enabled = enabled,
        reservationCheck = reservationCheck ?: AutomationTaskReservationCheckUiState(),
    )

fun SeatRoomSnapshot.toAutomationTaskSeatOptionUiModel(): AutomationTaskSeatOptionUiModel =
    AutomationTaskSeatOptionUiModel(
        roomId = roomId,
        roomName = roomName,
        seatNumbers = seatNumbers,
        recommendedSeatNumber = recommendedSeatNumber,
    )

fun AutomationTaskSeatOptionUiModel.defaultSeatSuggestion(): Pair<String, String>? {
    val seatNumber =
        recommendedSeatNumber?.takeIf(String::isNotBlank)
            ?: seatNumbers.firstOrNull()
    return seatNumber?.let { roomName to it }
}
