package com.wuyi.libraryauto.ui.screen.seat

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
