package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeatBookingStatusServiceTest {

    @Test
    fun `loadCurrentBooking maps waiting signin booking and checkin window`() {
        val service =
            SeatBookingStatusService(
                api =
                    FakeSchoolSeatApi(
                        bookingListBody =
                            """
                            {"CODE":"ok","DATA":{"list":[{"id":"booking-1","no":"166","room":"自习室圆形二楼","status":"Reserve","day":"2026-04-11","begin":"09:00","end":"12:00","sign_can":1}]}}
                            """.trimIndent(),
                    ),
            )

        val result = service.loadCurrentBooking("https://example.com/Seat/Index/myBookingList?LAB_JSON=1")

        assertThat(result.liveState).isEqualTo(SeatBookingLiveState.RESERVED_WAITING_SIGNIN)
        assertThat(result.bookingId).isEqualTo("booking-1")
        assertThat(result.roomName).isEqualTo("自习室圆形二楼")
        assertThat(result.seatNumber).isEqualTo("166")
        assertThat(result.beginLabel).isEqualTo("2026-04-11 09:00")
        assertThat(result.statusLabel).isEqualTo("待签到")
        assertThat(result.checkinWindowOpen).isTrue()
    }

    @Test
    fun `loadCurrentBooking maps checked in booking`() {
        val service =
            SeatBookingStatusService(
                api =
                    FakeSchoolSeatApi(
                        bookingListBody =
                            """
                            {"CODE":"ok","DATA":{"list":[{"id":"booking-2","no":"021","room":"综合阅览室","status":"CheckIn","day":"2026-04-11","begin":"13:00","sign_can":0}]}}
                            """.trimIndent(),
                    ),
            )

        val result = service.loadCurrentBooking("https://example.com/Seat/Index/myBookingList?LAB_JSON=1")

        assertThat(result.liveState).isEqualTo(SeatBookingLiveState.ACTIVE_SIGNED_IN)
        assertThat(result.statusLabel).isEqualTo("已签到")
        assertThat(result.checkinWindowOpen).isFalse()
    }

    @Test
    fun `loadCurrentBooking supports current booking payload from windows implementation`() {
        val service =
            SeatBookingStatusService(
                api =
                    FakeSchoolSeatApi(
                        bookingListBody =
                            """
                            {"content":{"defaultItems":[{"id":"booking-3","seatNum":"166","roomName":"自习室圆形二楼","status":"0","time":1775869200,"duration":10800,"nowTime":1775868900,"limitSignAgo":900,"limitSignBack":1800}]}}
                            """.trimIndent(),
                    ),
            )

        val result = service.loadCurrentBooking("https://example.com/Seat/Index/myBookingList?LAB_JSON=1")

        assertThat(result.liveState).isEqualTo(SeatBookingLiveState.RESERVED_WAITING_SIGNIN)
        assertThat(result.bookingId).isEqualTo("booking-3")
        assertThat(result.roomName).isEqualTo("自习室圆形二楼")
        assertThat(result.seatNumber).isEqualTo("166")
        assertThat(result.beginLabel).isEqualTo("2026-04-11 09:00")
        assertThat(result.statusLabel).isEqualTo("待签到")
        assertThat(result.checkinWindowOpen).isTrue()
    }

    @Test
    fun `loadActiveBookingDates extracts reservable and signed in dates from current payload`() {
        val service =
            SeatBookingStatusService(
                api =
                    FakeSchoolSeatApi(
                        bookingListBody =
                            """
                            {"content":{"defaultItems":[
                              {"id":"booking-3","seatNum":"166","roomName":"自习室圆形二楼","status":"0","time":1775869200,"duration":10800,"nowTime":1775868900,"limitSignAgo":900,"limitSignBack":1800},
                              {"id":"booking-4","seatNum":"021","roomName":"综合阅览室","status":"1","time":1775955600,"duration":10800,"nowTime":1775955900,"limitSignAgo":900,"limitSignBack":1800},
                              {"id":"booking-5","seatNum":"099","roomName":"综合阅览室","status":"4","time":1776042000,"duration":10800,"nowTime":1776042300,"limitSignAgo":900,"limitSignBack":1800}
                            ]}}
                            """.trimIndent(),
                    ),
            )

        val result = service.loadActiveBookingDates("https://example.com/Seat/Index/myBookingList?LAB_JSON=1")

        assertThat(result).containsExactly("2026-04-11", "2026-04-12")
    }

    @Test
    fun `loadCurrentBooking maps empty list to idle`() {
        val service =
            SeatBookingStatusService(
                api = FakeSchoolSeatApi(bookingListBody = """{"CODE":"ok","DATA":{"list":[]}}"""),
            )

        val result = service.loadCurrentBooking("https://example.com/Seat/Index/myBookingList?LAB_JSON=1")

        assertThat(result.liveState).isEqualTo(SeatBookingLiveState.IDLE)
        assertThat(result.statusLabel).isEqualTo("暂无预约")
    }

    @Test
    fun `loadCurrentBooking maps non ok response to need login`() {
        val service =
            SeatBookingStatusService(
                api = FakeSchoolSeatApi(bookingListBody = """{"CODE":"fail","MESSAGE":"请重新登录"}"""),
            )

        val result = service.loadCurrentBooking("https://example.com/Seat/Index/myBookingList?LAB_JSON=1")

        assertThat(result.liveState).isEqualTo(SeatBookingLiveState.NEED_LOGIN)
        assertThat(result.statusLabel).isEqualTo("请重新登录")
    }

    @Test
    fun `loadCurrentBooking treats html login page as need login`() {
        val service =
            SeatBookingStatusService(
                api =
                    FakeSchoolSeatApi(
                        bookingListBody =
                            """
                            <!DOCTYPE html>
                            <html>
                              <body>请先登录 /User/Index/login</body>
                            </html>
                            """.trimIndent(),
                    ),
            )

        val result = service.loadCurrentBooking("https://example.com/Seat/Index/myBookingList?LAB_JSON=1")

        assertThat(result.liveState).isEqualTo(SeatBookingLiveState.NEED_LOGIN)
        assertThat(result.statusLabel).contains("刷新认证")
    }

    private class FakeSchoolSeatApi(
        private val bookingListBody: String,
    ) : SchoolSeatApi {
        override fun fetchSearchPage(url: String): String = error("unused")

        override fun searchSeats(
            url: String,
            requestBody: String,
        ): String = error("unused")

        override fun reserveSeats(
            url: String,
            requestBody: String,
        ): String = error("unused")

        override fun fetchBookingList(url: String): String = bookingListBody

        override fun checkIn(url: String): String = error("unused")

        override fun checkout(url: String): String = error("unused")

        override fun cancelBooking(url: String): String = error("unused")
    }
}
