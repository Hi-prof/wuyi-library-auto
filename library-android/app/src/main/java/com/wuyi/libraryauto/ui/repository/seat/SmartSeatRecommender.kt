package com.wuyi.libraryauto.ui.repository.seat

import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.ReservationHistoryHit

data class SeatRecommendation(
    val roomName: String,
    val seatNumber: String,
    val confidence: Double,
    val usageCount: Int,
    val latestUsedTimestamp: Long,
)

data class SeatFrequency(
    val count: Int,
    val latestTimestamp: Long,
)

class SmartSeatRecommender(
    private val historyReader: AccountReservationHistoryReader,
) {
    suspend fun recommendSeat(studentIds: List<String>): SeatRecommendation? {
        if (studentIds.isEmpty()) return null

        val allHistory = studentIds.flatMap { studentId ->
            try {
                historyReader.loadHistory(studentId)
            } catch (e: Exception) {
                emptyList()
            }
        }

        return analyzeHistory(allHistory)
    }

    suspend fun recommendSeatForSingleAccount(studentId: String): SeatRecommendation? {
        val history = try {
            historyReader.loadHistory(studentId)
        } catch (e: Exception) {
            return null
        }

        return analyzeHistory(history)
    }

    companion object {
        fun analyzeHistory(history: List<ReservationHistoryHit>): SeatRecommendation? {
            if (history.isEmpty()) return null

            val seatFrequency = history
                .groupBy { it.roomName to it.seatNumber }
                .mapValues { (_, hits) ->
                    SeatFrequency(
                        count = hits.size,
                        latestTimestamp = hits.maxOf { it.timestampEpochSeconds },
                    )
                }

            val (roomSeat, freq) = seatFrequency.maxWithOrNull(
                compareBy<Map.Entry<Pair<String, String>, SeatFrequency>> { it.value.count }
                    .thenBy { it.value.latestTimestamp }
            ) ?: return null

            return SeatRecommendation(
                roomName = roomSeat.first,
                seatNumber = roomSeat.second,
                confidence = freq.count.toDouble() / history.size,
                usageCount = freq.count,
                latestUsedTimestamp = freq.latestTimestamp,
            )
        }
    }
}
