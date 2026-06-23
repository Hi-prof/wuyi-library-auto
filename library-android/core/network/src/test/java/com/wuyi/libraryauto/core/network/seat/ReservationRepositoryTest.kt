package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ReservationRepositoryTest {

    @Test
    fun `buildBookApiUrl converts searchSeats path into bookSeats lab json api`() {
        val actual =
            ReservationRepository.buildBookApiUrl(
                "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?content_id=1&day=2026-04-10"
            )

        assertThat(actual)
            .isEqualTo("https://wuyiu.huitu.zhishulib.com/Seat/Index/bookSeats?LAB_JSON=1")
    }

    @Test
    fun `buildBookApiUrl preserves origin and drops fragment`() {
        val actual =
            ReservationRepository.buildBookApiUrl(
                "https://example.com/Seat/Index/searchSeats?seat=1#!/ignored"
            )

        assertThat(actual).isEqualTo("https://example.com/Seat/Index/bookSeats?LAB_JSON=1")
    }

    @Test
    fun `buildBookApiUrl rejects non exact search path`() {
        val extraPathError =
            assertThrows(IllegalArgumentException::class.java) {
                ReservationRepository.buildBookApiUrl(
                    "https://example.com/Seat/Index/searchSeatsExtra?seat=1"
                )
            }
        val childPathError =
            assertThrows(IllegalArgumentException::class.java) {
                ReservationRepository.buildBookApiUrl(
                    "https://example.com/Seat/Index/searchSeats/child?seat=1"
                )
            }

        assertThat(extraPathError).hasMessageThat().contains(SchoolSeatApi.SEARCH_SEATS_PATH)
        assertThat(childPathError).hasMessageThat().contains(SchoolSeatApi.SEARCH_SEATS_PATH)
    }

    @Test
    fun `school seat api keeps endpoint constants as paths`() {
        assertThat(SchoolSeatApi.SEARCH_SEATS_PATH).isEqualTo("/Seat/Index/searchSeats")
        assertThat(SchoolSeatApi.BOOK_SEATS_PATH).isEqualTo("/Seat/Index/bookSeats")
        assertThat(SchoolSeatApi.MY_BOOKING_LIST_PATH).isEqualTo("/Seat/Index/myBookingList")
        assertThat(SchoolSeatApi.CHECKIN_PATH).isEqualTo("/Seat/Index/checkIn")
    }
}
