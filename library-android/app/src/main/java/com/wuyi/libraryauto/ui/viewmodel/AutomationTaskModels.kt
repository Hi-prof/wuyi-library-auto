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
    val reservationCheck: AutomationTaskReservationCheckUiState = AutomationTaskReservationCheckUiState(),
)

enum class AutomationTaskReservationCheckStatus {
    UNCHECKED,
    CHECKING,
    MATCHED,
    OTHER_BOOKING,
    EMPTY,
    FAILED,
}

data class AutomationTaskReservationCheckUiState(
    val status: AutomationTaskReservationCheckStatus = AutomationTaskReservationCheckStatus.UNCHECKED,
    val label: String = "未检查",
    val detail: String = "",
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

data class CreateFromBookingsDialogState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val rows: List<CreateFromBookingsRowUiState> = emptyList(),
    val message: String? = null,
)

data class CreateFromBookingsRowUiState(
    val studentId: String,
    val roomName: String = "",
    val seatNumber: String = "",
    val beginLabel: String = "",
    val statusLabel: String = "",
    val hasExistingPlan: Boolean = false,
    val selected: Boolean = false,
    val canCreate: Boolean = false,
    val message: String = "",
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
    val createFromBookingsDialog: CreateFromBookingsDialogState = CreateFromBookingsDialogState(),
    val statusMessage: String? = null,
    val isCheckingReservations: Boolean = false,
)
