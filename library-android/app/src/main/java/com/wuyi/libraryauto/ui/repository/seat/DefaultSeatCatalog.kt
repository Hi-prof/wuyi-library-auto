package com.wuyi.libraryauto.ui.repository.seat

object DefaultSeatCatalog {

    fun buildLookupData(notice: String): SeatLookupData =
        SeatLookupData(
            beginTimeEpochSeconds = null,
            durationHours = null,
            peopleCount = null,
            rooms =
                listOf(
                    buildRoom(
                        roomName = "综合阅览室",
                        storey = "一楼",
                        seatRange = 1..360,
                    ),
                    buildRoom(
                        roomName = "自习室圆形二楼",
                        storey = "二楼",
                        seatRange = 1..188,
                    ),
                    buildRoom(
                        roomName = "自习室圆形一楼",
                        storey = "一楼",
                        seatRange = 1..120,
                    ),
                ),
            catalogOnly = true,
            notice = notice,
        )

    private fun buildRoom(
        roomName: String,
        storey: String,
        seatRange: IntRange,
    ): SeatRoomSnapshot =
        SeatRoomSnapshot(
            roomId = roomName,
            roomName = roomName,
            storey = storey,
            availableCount = seatRange.count(),
            seatNumbers = seatRange.map(Int::toString),
            recommendedSeatNumber = null,
        )
}
