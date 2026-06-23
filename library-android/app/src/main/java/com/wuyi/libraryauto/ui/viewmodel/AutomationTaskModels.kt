package com.wuyi.libraryauto.ui.viewmodel

import com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode
import com.wuyi.libraryauto.ui.repository.task.ReservationHistoryHit

data class AutomationTaskPlanUiModel(
    val planId: String,
    val studentId: String,
    val title: String,
    val roomName: String,
    val seatNumber: String,
    val previewText: String,
    val lastResultMessage: String,
    val modeLabel: String,
    val enabled: Boolean,
)

data class AutomationTaskSeatOptionUiModel(
    val roomId: String,
    val roomName: String,
    val seatNumbers: List<String>,
    val recommendedSeatNumber: String?,
)

data class AutomationTaskDialogState(
    val visible: Boolean = false,
    val selectedStudentId: String = "",
    val mode: AutomationTaskMode = AutomationTaskMode.CONTINUOUS,
    val roomName: String = "",
    val seatNumber: String = "",
    val isRefreshingSeats: Boolean = false,
    val previewText: String = "",
    val customDate: String = "",
    val customStartTime: String = "",
    val customEndTime: String = "",
    val seatOptions: List<AutomationTaskSeatOptionUiModel> = emptyList(),
    val dialogMessage: String? = null,
    val lastAutofill: AutoFillSnapshot? = null,
    val historyHints: List<ReservationHistoryHit> = emptyList(),
)

data class AutoFillSnapshot(
    val roomName: String,
    val seatNumber: String,
)

data class AutomationTaskUiState(
    val accounts: List<SavedAccountEntry> = emptyList(),
    val studentFilter: String = "",
    val plans: List<AutomationTaskPlanUiModel> = emptyList(),
    val dialog: AutomationTaskDialogState = AutomationTaskDialogState(),
    val statusMessage: String? = null,
)
