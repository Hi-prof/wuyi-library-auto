package com.wuyi.libraryauto.ui.screen.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
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

    @Test
    fun `buildSeatDisplayRoomPresentation summarizes selected room with anomaly state`() {
        val room =
            SeatDisplayRoomUiState(
                roomId = "room-a",
                roomName = " 综合阅览室 ",
                seats =
                    listOf(
                        SeatDisplaySeatUiState(
                            key = "room-a:18",
                            roomName = "综合阅览室",
                            seatNumber = "18",
                            accounts =
                                listOf(
                                    SeatDisplayCardUiState(studentId = "20230001"),
                                    SeatDisplayCardUiState(studentId = "20230002"),
                                ),
                        ),
                        SeatDisplaySeatUiState(
                            key = "room-a:19",
                            roomName = "综合阅览室",
                            seatNumber = "19",
                            accounts =
                                listOf(
                                    SeatDisplayCardUiState(
                                        studentId = "20230003",
                                        failureMessage = "刷新失败",
                                    ),
                                ),
                        ),
                    ),
            )

        val presentation =
            buildSeatDisplayRoomPresentation(
                room = room,
                expanded = false,
                selectedSeatKey = "room-a:18",
            )

        assertThat(presentation.roomName).isEqualTo("综合阅览室")
        assertThat(presentation.seatCountLabel).isEqualTo("座位 2")
        assertThat(presentation.accountCountLabel).isEqualTo("账号 3")
        assertThat(presentation.healthLabel).isEqualTo("1 个异常")
        assertThat(presentation.healthTone).isEqualTo(StatusTone.Negative)
        assertThat(presentation.expandActionLabel).isEqualTo("展开")
        assertThat(presentation.collapsedHint).isEqualTo("已选 18，展开查看账号")
    }

    @Test
    fun `buildSeatDisplayRoomPresentation handles empty room fallback`() {
        val presentation =
            buildSeatDisplayRoomPresentation(
                room =
                    SeatDisplayRoomUiState(
                        roomId = "",
                        roomName = " ",
                        seats = emptyList(),
                    ),
                expanded = true,
                selectedSeatKey = null,
            )

        assertThat(presentation.roomName).isEqualTo("未分配自习室")
        assertThat(presentation.seatCountLabel).isEqualTo("座位 0")
        assertThat(presentation.accountCountLabel).isEqualTo("账号 0")
        assertThat(presentation.healthLabel).isEqualTo("暂无座位")
        assertThat(presentation.healthTone).isEqualTo(StatusTone.Neutral)
        assertThat(presentation.expandActionLabel).isEqualTo("收起")
        assertThat(presentation.collapsedHint).isEqualTo("暂无座位详情")
    }
}
