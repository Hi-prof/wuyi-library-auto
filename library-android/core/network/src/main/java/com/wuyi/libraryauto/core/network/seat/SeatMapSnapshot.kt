package com.wuyi.libraryauto.core.network.seat

data class SeatMapSnapshot(
    val roomId: String,
    val roomName: String,
    val storey: String,
    val planUrl: String,
    val width: Int,
    val height: Int,
    val availableCount: Int,
    val lockedCount: Int,
    val selectedSeatId: String?,
    val selectedSeatNumber: String?,
    val systemRecommendedSeatId: String?,
    val systemRecommendedSeatNumber: String?,
    val seats: List<SeatPoi>,
)
