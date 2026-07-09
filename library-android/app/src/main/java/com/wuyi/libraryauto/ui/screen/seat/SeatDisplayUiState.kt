package com.wuyi.libraryauto.ui.screen.seat

import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.ui.repository.seat.BatchCheckInResult
import com.wuyi.libraryauto.ui.repository.seat.BatchReservationResult
import com.wuyi.libraryauto.ui.components.StatusTone

data class SeatDisplayUiState(
    val cards: List<SeatDisplayCardUiState> = emptyList(),
    val isRefreshingAll: Boolean = false,
    val singleRefreshing: Set<String> = emptySet(),
    val emptyHint: String = "",
    val isBatchCheckingIn: Boolean = false,
    val lastBatchCheckInResult: BatchCheckInResult? = null,
    val isBatchReserving: Boolean = false,
    val lastBatchReservationResult: BatchReservationResult? = null,
    val batchProgressMessage: String = "",
    val batchErrorMessage: String = "",
)

data class SeatDisplayCardUiState(
    val studentId: String,
    val isCurrentSession: Boolean = false,
    val roomName: String = "",
    val seatNumber: String = "",
    val beginLabel: String = "",
    val liveState: SeatBookingLiveState = SeatBookingLiveState.IDLE,
    val checkinWindowOpen: Boolean = false,
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

internal data class SeatDisplayRoomPresentation(
    val roomName: String,
    val seatCountLabel: String,
    val accountCountLabel: String,
    val healthLabel: String,
    val healthTone: StatusTone,
    val expandActionLabel: String,
    val collapsedHint: String,
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

internal fun buildSeatDisplayRoomPresentation(
    room: SeatDisplayRoomUiState,
    expanded: Boolean,
    selectedSeatKey: String?,
): SeatDisplayRoomPresentation {
    val seatCount = room.seats.size
    val accountCount = room.seats.sumOf { seat -> seat.accounts.size }
    val failureSeatCount = room.seats.count(SeatDisplaySeatUiState::hasFailure)
    val selectedSeat = room.seats.firstOrNull { seat -> seat.key == selectedSeatKey }
    return SeatDisplayRoomPresentation(
        roomName = room.roomName.trim().ifBlank { UNASSIGNED_ROOM_NAME },
        seatCountLabel = "座位 $seatCount",
        accountCountLabel = "账号 $accountCount",
        healthLabel =
            when {
                seatCount == 0 -> "暂无座位"
                failureSeatCount > 0 -> "$failureSeatCount 个异常"
                else -> "状态正常"
            },
        healthTone =
            when {
                seatCount == 0 -> StatusTone.Neutral
                failureSeatCount > 0 -> StatusTone.Negative
                else -> StatusTone.Positive
            },
        expandActionLabel = if (expanded) "收起" else "展开",
        collapsedHint =
            when {
                seatCount == 0 -> "暂无座位详情"
                selectedSeat != null -> "已选 ${selectedSeat.displaySeatNumber}，展开查看账号"
                failureSeatCount > 0 -> "$failureSeatCount 个座位需要处理"
                else -> "点按展开座位详情"
            },
    )
}

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
