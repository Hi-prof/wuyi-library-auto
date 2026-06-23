package com.wuyi.libraryauto.core.network.seat

data class SeatPoi(
    val seatId: String,
    val seatNumber: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val state: String,
    val selectable: Boolean,
    val recommended: Boolean,
    val hasSocket: Boolean = false,
)
