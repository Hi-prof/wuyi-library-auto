package com.wuyi.libraryauto.ui.screen.seat

import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.viewmodel.ManualReservationRoomUiModel

internal data class ManualRoomPresentation(
    val roomId: String,
    val roomName: String,
    val storeyLabel: String,
    val availabilityLabel: String,
    val availabilityTone: StatusTone,
    val expandActionLabel: String,
    val collapsedHint: String,
    val sortedSeatNumbers: List<String>,
)

internal fun buildInitialExpandedRoomIds(
    roomIds: List<String>,
    selectedRoomId: String,
): Set<String> {
    val safeSelectedRoomId = selectedRoomId.trim()
    return if (safeSelectedRoomId.isNotBlank() && roomIds.contains(safeSelectedRoomId)) {
        setOf(safeSelectedRoomId)
    } else {
        emptySet()
    }
}

internal fun sortSeatNumbersForDisplay(seatNumbers: List<String>): List<String> =
    seatNumbers.sortedWith(
        compareBy<String>(
            { it.toIntOrNull() == null },
            { it.toIntOrNull() ?: Int.MAX_VALUE },
            { it },
        ),
    )

internal fun buildManualRoomPresentation(
    room: ManualReservationRoomUiModel,
    expanded: Boolean,
    selectedRoomId: String,
    selectedSeatNumber: String,
): ManualRoomPresentation {
    val safeRoomName = room.roomName.trim().ifBlank { "未知自习室" }
    val safeRoomId = room.roomId.trim().ifBlank { safeRoomName }
    val safeSelectedSeatNumber = selectedSeatNumber.trim()
    val selectedInRoom = selectedRoomId.trim() == safeRoomId && safeSelectedSeatNumber.isNotBlank()
    val sortedSeats = sortSeatNumbersForDisplay(room.seatNumbers)
    return ManualRoomPresentation(
        roomId = safeRoomId,
        roomName = safeRoomName,
        storeyLabel = room.storey.trim().ifBlank { "楼层未知" },
        availabilityLabel =
            if (room.availableCount > 0) {
                "可用 ${room.availableCount}"
            } else {
                "暂无可用"
            },
        availabilityTone = if (room.availableCount > 0) StatusTone.Positive else StatusTone.Warning,
        expandActionLabel = if (expanded) "收起" else "展开",
        collapsedHint =
            when {
                sortedSeats.isEmpty() -> "暂无可选座位"
                selectedInRoom -> "已选 $safeSelectedSeatNumber，展开可更换座位"
                else -> "点按展开座位列表"
            },
        sortedSeatNumbers = sortedSeats,
    )
}

internal fun manualSeatChipLabel(
    seatNumber: String,
    recommendedSeatNumber: String?,
): String =
    if (seatNumber == recommendedSeatNumber?.trim()) {
        "$seatNumber · 推荐"
    } else {
        seatNumber
    }
