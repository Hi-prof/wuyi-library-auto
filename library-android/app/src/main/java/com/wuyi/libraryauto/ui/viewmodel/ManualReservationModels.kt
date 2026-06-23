package com.wuyi.libraryauto.ui.viewmodel

const val DEFAULT_MANUAL_DURATION_HOURS = "4"

data class ManualReservationRoomUiModel(
    val roomId: String,
    val roomName: String,
    val storey: String,
    val availableCount: Int,
    val seatNumbers: List<String>,
    val recommendedSeatNumber: String?,
)

data class ManualReservationUiState(
    val accounts: List<SavedAccountEntry> = emptyList(),
    val selectedStudentId: String = "",
    val selectedDate: String = "",
    val selectedStartTime: String = "",
    val durationHours: String = DEFAULT_MANUAL_DURATION_HOURS,
    val rooms: List<ManualReservationRoomUiModel> = emptyList(),
    val selectedRoomId: String = "",
    val selectedRoomName: String = "",
    val selectedSeatNumber: String = "",
    val querySummary: String = "",
    val isLoadingSeats: Boolean = false,
    val isSubmitting: Boolean = false,
    val statusMessage: String? = null,
)
