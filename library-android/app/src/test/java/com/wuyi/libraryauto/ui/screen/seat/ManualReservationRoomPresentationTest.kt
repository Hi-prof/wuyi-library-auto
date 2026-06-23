package com.wuyi.libraryauto.ui.screen.seat

import com.google.common.truth.Truth.assertThat
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
}
