package com.wuyi.libraryauto.core.network.seat

data class SeatLookupResult(
    val rawPayload: String,
    val roomMaps: List<SeatMapSnapshot>,
    val selectedRoom: SeatMapSnapshot,
)
