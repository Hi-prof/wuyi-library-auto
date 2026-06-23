package com.wuyi.libraryauto.ui.adapter.seat

import com.wuyi.libraryauto.ui.repository.seat.SeatLookupData
import com.wuyi.libraryauto.ui.repository.seat.SeatRoomSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SeatLookupSummaryUiModel(
    val dateText: String,
    val timeText: String,
    val peopleCountText: String,
)

data class SeatRoomUiModel(
    val title: String,
    val availableText: String,
    val seatNumbersText: String,
    val hintText: String,
)

data class SeatLookupPresentation(
    val summary: SeatLookupSummaryUiModel?,
    val rooms: List<SeatRoomUiModel>,
    val catalogOnly: Boolean,
)

class SeatLookupUiMapper(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun map(data: SeatLookupData): SeatLookupPresentation {
        val summary =
            if (
                data.catalogOnly ||
                data.beginTimeEpochSeconds == null ||
                data.durationHours == null ||
                data.peopleCount == null
            ) {
                null
            } else {
                val start = Instant.ofEpochSecond(data.beginTimeEpochSeconds.toLong()).atZone(zoneId)
                val end = start.plusHours(data.durationHours.toLong())
                SeatLookupSummaryUiModel(
                    dateText = start.format(DATE_FORMATTER),
                    timeText = "${start.format(TIME_FORMATTER)} - ${end.format(TIME_FORMATTER)}",
                    peopleCountText = "${data.peopleCount} 人",
                )
            }
        return SeatLookupPresentation(
            summary = summary,
            rooms = data.rooms.map { room -> mapRoom(room, data.catalogOnly) },
            catalogOnly = data.catalogOnly,
        )
    }

    private fun mapRoom(
        room: SeatRoomSnapshot,
        catalogOnly: Boolean,
    ): SeatRoomUiModel {
        val title = room.roomName.ifBlank { "未命名房间" }
        val seatPreview =
            when {
                room.seatNumbers.isEmpty() -> "当前未解析到可用座位号"
                room.seatNumbers.size <= MAX_PREVIEW_SEATS -> room.seatNumbers.joinToString("、")
                else -> room.seatNumbers.take(MAX_PREVIEW_SEATS).joinToString("、") + " 等 ${room.seatNumbers.size} 个"
            }
        val hintParts = mutableListOf<String>()
        room.storey.takeIf(String::isNotBlank)?.let { storey ->
            hintParts += "楼层：$storey"
        }
        room.recommendedSeatNumber
            ?.takeIf(String::isNotBlank)
            ?.let { seatNumber ->
                hintParts += "推荐座位：$seatNumber"
            }
        if (catalogOnly) {
            hintParts += "默认目录"
        }
        val hintText = hintParts.ifEmpty { listOf("未标记推荐座位") }.joinToString("，")

        return SeatRoomUiModel(
            title = title,
            availableText =
                if (catalogOnly) {
                    "目录座位 ${room.seatNumbers.size} 个"
                } else {
                    "可用座位 ${room.availableCount} 个"
                },
            seatNumbersText = seatPreview,
            hintText = hintText,
        )
    }

    private companion object {
        private const val MAX_PREVIEW_SEATS = 20
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    }
}
