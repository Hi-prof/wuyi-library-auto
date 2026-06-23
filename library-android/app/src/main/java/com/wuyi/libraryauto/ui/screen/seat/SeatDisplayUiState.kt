package com.wuyi.libraryauto.ui.screen.seat

import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState

data class SeatDisplayUiState(
    val cards: List<SeatDisplayCardUiState> = emptyList(),
    val isRefreshingAll: Boolean = false,
    val singleRefreshing: Set<String> = emptySet(),
    val emptyHint: String = "",
)

data class SeatDisplayCardUiState(
    val studentId: String,
    val isCurrentSession: Boolean = false,
    val roomName: String = "",
    val seatNumber: String = "",
    val beginLabel: String = "",
    val liveState: SeatBookingLiveState = SeatBookingLiveState.IDLE,
    val statusLabel: String = liveState.toChineseLabel(),
    val failureMessage: String? = null,
    val lastUpdatedEpochMillis: Long = 0L,
) {
    val bookingLabel: String =
        listOf(roomName, seatNumber, beginLabel)
            .filter(String::isNotBlank)
            .joinToString(" / ")
}

data class SeatDisplayRoomUiState(
    val roomId: String,
    val roomName: String,
    val seats: List<SeatDisplaySeatUiState>,
)

data class SeatDisplaySeatUiState(
    val key: String,
    val roomName: String,
    val seatNumber: String,
    val accounts: List<SeatDisplayCardUiState>,
) {
    val displaySeatNumber: String = seatNumber.ifBlank { UNASSIGNED_SEAT_NUMBER }
    val statusLabel: String =
        accounts
            .map(SeatDisplayCardUiState::statusLabel)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("、")
            .ifBlank { SeatBookingLiveState.IDLE.toChineseLabel() }
    val hasFailure: Boolean = accounts.any { account -> account.failureMessage != null }
}

internal fun buildSeatDisplayRooms(cards: List<SeatDisplayCardUiState>): List<SeatDisplayRoomUiState> =
    cards
        .groupBy { card -> card.roomName.ifBlank { UNASSIGNED_ROOM_NAME } }
        .map { (roomName, roomCards) ->
            val seatsByNumber = roomCards.groupBy { card -> card.seatNumber.ifBlank { UNASSIGNED_SEAT_NUMBER } }
            SeatDisplayRoomUiState(
                roomId = roomName,
                roomName = roomName,
                seats =
                    sortSeatNumbersForDisplay(seatsByNumber.keys.toList()).mapNotNull { seatNumber ->
                        seatsByNumber[seatNumber]?.let { accounts ->
                            SeatDisplaySeatUiState(
                                key = "$roomName:$seatNumber",
                                roomName = roomName,
                                seatNumber = seatNumber,
                                accounts = accounts.sortedBy(SeatDisplayCardUiState::studentId),
                            )
                        }
                    },
            )
        }
        .sortedBy(SeatDisplayRoomUiState::roomName)

fun SeatBookingLiveState.toChineseLabel(): String =
    when (this) {
        SeatBookingLiveState.NEED_LOGIN -> "需登录"
        SeatBookingLiveState.IDLE -> "暂无预约"
        SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> "待签到"
        SeatBookingLiveState.ACTIVE_SIGNED_IN -> "已签到"
        SeatBookingLiveState.FINISHED_OR_HISTORY -> "最近记录已结束"
    }

private const val UNASSIGNED_ROOM_NAME = "未分配自习室"
private const val UNASSIGNED_SEAT_NUMBER = "暂无座位"
