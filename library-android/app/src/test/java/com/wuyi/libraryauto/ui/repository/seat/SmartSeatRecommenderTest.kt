package com.wuyi.libraryauto.ui.repository.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.repository.task.HistorySource
import com.wuyi.libraryauto.ui.repository.task.ReservationHistoryHit
import org.junit.Test

class SmartSeatRecommenderTest {

    @Test
    fun analyzeHistory_prefersMostFrequentSeat() {
        val recommendation =
            SmartSeatRecommender.analyzeHistory(
                listOf(
                    hit(roomName = "A区", seatNumber = "001", timestamp = 100),
                    hit(roomName = "B区", seatNumber = "009", timestamp = 200),
                    hit(roomName = "B区", seatNumber = "009", timestamp = 300),
                ),
            )

        assertThat(recommendation?.roomName).isEqualTo("B区")
        assertThat(recommendation?.seatNumber).isEqualTo("009")
        assertThat(recommendation?.usageCount).isEqualTo(2)
        assertThat(recommendation?.latestUsedTimestamp).isEqualTo(300)
    }

    @Test
    fun analyzeHistory_tieBreaksByLatestUse() {
        val recommendation =
            SmartSeatRecommender.analyzeHistory(
                listOf(
                    hit(roomName = "旧区", seatNumber = "010", timestamp = 100),
                    hit(roomName = "新区", seatNumber = "020", timestamp = 900),
                ),
            )

        assertThat(recommendation?.roomName).isEqualTo("新区")
        assertThat(recommendation?.seatNumber).isEqualTo("020")
        assertThat(recommendation?.usageCount).isEqualTo(1)
        assertThat(recommendation?.latestUsedTimestamp).isEqualTo(900)
    }

    private fun hit(
        roomName: String,
        seatNumber: String,
        timestamp: Long,
    ): ReservationHistoryHit =
        ReservationHistoryHit(
            roomName = roomName,
            seatNumber = seatNumber,
            timestampEpochSeconds = timestamp,
            source = HistorySource.RESERVATION_TASK,
        )
}
