package com.wuyi.libraryauto.ui.repository.seat

data class SeatLookupData(
    val beginTimeEpochSeconds: Int?,
    val durationHours: Int?,
    val peopleCount: Int?,
    val rooms: List<SeatRoomSnapshot>,
    val catalogOnly: Boolean = false,
    val notice: String? = null,
)

data class SeatRoomSnapshot(
    val roomId: String = "",
    val roomName: String,
    val storey: String,
    val availableCount: Int,
    val seatNumbers: List<String>,
    val recommendedSeatNumber: String?,
)

data class SeatLookupQuery(
    val studentId: String,
    val entryUrl: String,
    val beginTimeEpochSeconds: Int,
    val durationSeconds: Int,
    val peopleCount: Int = 1,
)

sealed interface SeatLookupLoadResult {
    data class Success(
        val data: SeatLookupData,
    ) : SeatLookupLoadResult

    data class Empty(
        val data: SeatLookupData,
    ) : SeatLookupLoadResult

    data class Failure(
        val message: String,
    ) : SeatLookupLoadResult

    data object NotLoggedIn : SeatLookupLoadResult
}
