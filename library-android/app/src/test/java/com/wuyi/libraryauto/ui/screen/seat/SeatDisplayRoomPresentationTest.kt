package com.wuyi.libraryauto.ui.screen.seat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeatDisplayRoomPresentationTest {

    @Test
    fun `buildSeatDisplayRooms groups accounts by room and seat`() {
        val rooms =
            buildSeatDisplayRooms(
                listOf(
                    SeatDisplayCardUiState(studentId = "20230002", roomName = "自习室A", seatNumber = "18"),
                    SeatDisplayCardUiState(studentId = "20230001", roomName = "自习室A", seatNumber = "18"),
                    SeatDisplayCardUiState(studentId = "20230003", roomName = "自习室B", seatNumber = "2"),
                ),
            )

        assertThat(rooms.map(SeatDisplayRoomUiState::roomName)).containsExactly("自习室A", "自习室B").inOrder()
        assertThat(rooms.first().seats).hasSize(1)
        assertThat(rooms.first().seats.first().displaySeatNumber).isEqualTo("18")
        assertThat(rooms.first().seats.first().accounts.map(SeatDisplayCardUiState::studentId))
            .containsExactly("20230001", "20230002")
            .inOrder()
    }

    @Test
    fun `buildSeatDisplayRooms sorts numeric seats within room`() {
        val rooms =
            buildSeatDisplayRooms(
                listOf(
                    SeatDisplayCardUiState(studentId = "20230001", roomName = "自习室A", seatNumber = "101"),
                    SeatDisplayCardUiState(studentId = "20230002", roomName = "自习室A", seatNumber = "9"),
                    SeatDisplayCardUiState(studentId = "20230003", roomName = "自习室A", seatNumber = "18"),
                ),
            )

        assertThat(rooms.single().seats.map(SeatDisplaySeatUiState::displaySeatNumber))
            .containsExactly("9", "18", "101")
            .inOrder()
    }
}
