package com.wuyi.libraryauto.ui.screen.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.viewmodel.ManualReservationRoomUiModel
import org.junit.Test

class ManualReservationRoomPresentationTest {

    @Test
    fun `buildInitialExpandedRoomIds keeps rooms collapsed without selected seat`() {
        val actual =
            buildInitialExpandedRoomIds(
                roomIds = listOf("room-a", "room-b"),
                selectedRoomId = "",
            )

        assertThat(actual).isEmpty()
    }

    @Test
    fun `buildInitialExpandedRoomIds keeps selected room expanded`() {
        val actual =
            buildInitialExpandedRoomIds(
                roomIds = listOf("room-a", "room-b"),
                selectedRoomId = "room-b",
            )

        assertThat(actual).containsExactly("room-b")
    }

    @Test
    fun `sortSeatNumbersForDisplay sorts numeric seat numbers ascending`() {
        val actual = sortSeatNumbersForDisplay(listOf("360", "2", "18", "101", "9"))

        assertThat(actual).containsExactly("2", "9", "18", "101", "360").inOrder()
    }

    @Test
    fun `buildManualRoomPresentation normalizes room header and selected state`() {
        val presentation =
            buildManualRoomPresentation(
                room =
                    ManualReservationRoomUiModel(
                        roomId = " room-a ",
                        roomName = " 综合阅览室 ",
                        storey = " 三楼 ",
                        availableCount = 5,
                        seatNumbers = listOf("18", "2", "101"),
                        recommendedSeatNumber = "18",
                    ),
                expanded = false,
                selectedRoomId = "room-a",
                selectedSeatNumber = "18",
            )

        assertThat(presentation.roomId).isEqualTo("room-a")
        assertThat(presentation.roomName).isEqualTo("综合阅览室")
        assertThat(presentation.storeyLabel).isEqualTo("三楼")
        assertThat(presentation.availabilityLabel).isEqualTo("可用 5")
        assertThat(presentation.availabilityTone).isEqualTo(StatusTone.Positive)
        assertThat(presentation.expandActionLabel).isEqualTo("展开")
        assertThat(presentation.collapsedHint).isEqualTo("已选 18，展开可更换座位")
        assertThat(presentation.sortedSeatNumbers).containsExactly("2", "18", "101").inOrder()
    }

    @Test
    fun `buildManualRoomPresentation handles empty rooms and blank labels`() {
        val presentation =
            buildManualRoomPresentation(
                room =
                    ManualReservationRoomUiModel(
                        roomId = "",
                        roomName = " ",
                        storey = " ",
                        availableCount = 0,
                        seatNumbers = emptyList(),
                        recommendedSeatNumber = null,
                    ),
                expanded = true,
                selectedRoomId = "",
                selectedSeatNumber = "",
            )

        assertThat(presentation.roomId).isEqualTo("未知自习室")
        assertThat(presentation.roomName).isEqualTo("未知自习室")
        assertThat(presentation.storeyLabel).isEqualTo("楼层未知")
        assertThat(presentation.availabilityLabel).isEqualTo("暂无可用")
        assertThat(presentation.availabilityTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.expandActionLabel).isEqualTo("收起")
        assertThat(presentation.collapsedHint).isEqualTo("暂无可选座位")
    }

    @Test
    fun `manualSeatChipLabel marks only recommended seat`() {
        assertThat(manualSeatChipLabel(seatNumber = "18", recommendedSeatNumber = "18"))
            .isEqualTo("18 · 推荐")
        assertThat(manualSeatChipLabel(seatNumber = "19", recommendedSeatNumber = "18"))
            .isEqualTo("19")
    }
}
