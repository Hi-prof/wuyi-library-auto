package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.SignInError
import org.junit.Test

class SeatBookingActionServiceTest {

    @Test
    fun `cancelBooking returns school message`() {
        val service =
            SeatBookingActionService(
                api = FakeSchoolSeatApi(cancelBody = """{"CODE":"ok","MESSAGE":"已取消预约"}"""),
            )

        val result = service.cancelBooking("https://example.com/Seat/Index/cancelBook?id=booking-1", "booking-1")

        assertThat(result).isEqualTo(
            SeatBookingActionResult(
                bookingId = "booking-1",
                httpStatus = 200,
                rawMessage = "已取消预约",
                signInError = null,
            ),
        )
    }

    @Test
    fun `cancelBooking accepts literal success message payload`() {
        val service =
            SeatBookingActionService(
                api = FakeSchoolSeatApi(cancelBody = """"已取消预约""""),
            )

        val result = service.cancelBooking("https://example.com/Seat/Index/cancelBooking?bookingId=booking-1", "booking-1")

        assertThat(result).isEqualTo(
            SeatBookingActionResult(
                bookingId = "booking-1",
                httpStatus = 200,
                rawMessage = "已取消预约",
                signInError = null,
            ),
        )
    }

    @Test
    fun `cancelBooking uses DATA msg when school reports cancel success`() {
        val service =
            SeatBookingActionService(
                api =
                    FakeSchoolSeatApi(
                        cancelBody =
                            """
                            {
                              "CODE": "ok",
                              "MESSAGE": "请求成功",
                              "DATA": {
                                "result": "fail",
                                "msg": "取消预约成功"
                              }
                            }
                            """.trimIndent(),
                    ),
            )

        val result = service.cancelBooking("https://example.com/Seat/Index/cancelBooking?bookingId=booking-1", "booking-1")

        assertThat(result).isEqualTo(
            SeatBookingActionResult(
                bookingId = "booking-1",
                httpStatus = 200,
                rawMessage = "取消预约成功",
                signInError = null,
            ),
        )
    }

    @Test
    fun `cancelBooking returns DATA msg and signInError when school rejects action`() {
        val service =
            SeatBookingActionService(
                api =
                    FakeSchoolSeatApi(
                        cancelBody =
                            """
                            {
                              "CODE": "ok",
                              "MESSAGE": "请求成功",
                              "DATA": {
                                "result": "fail",
                                "msg": "当前状态无法操作，状态非待签到状态"
                              }
                            }
                            """.trimIndent(),
                    ),
            )

        val result = service.cancelBooking("https://example.com/Seat/Index/cancelBooking?bookingId=booking-1", "booking-1")

        assertThat(result.rawMessage).isEqualTo("当前状态无法操作，状态非待签到状态")
        assertThat(result.signInError).isEqualTo(SignInError.ServerRejected)
    }

    @Test
    fun `checkIn returns signInError on failure`() {
        val service =
            SeatBookingActionService(
                api = FakeSchoolSeatApi(checkInBody = """{"CODE":"fail","MESSAGE":"未到签到时间"}"""),
            )

        val result = service.checkIn("https://example.com/Seat/Index/checkIn?id=booking-1", "booking-1")

        assertThat(result.rawMessage).isEqualTo("未到签到时间")
        assertThat(result.signInError).isEqualTo(SignInError.NotInWindow)
    }

    @Test
    fun `checkIn returns unknown when response cannot be parsed`() {
        val service =
            SeatBookingActionService(
                api = FakeSchoolSeatApi(checkInBody = "<html>login</html>"),
            )

        val result = service.checkIn("https://example.com/Seat/Index/checkIn?id=booking-1", "booking-1")

        assertThat(result.rawMessage).isEqualTo("<html>login</html>")
        assertThat(result.signInError).isEqualTo(SignInError.Unknown)
    }

    private class FakeSchoolSeatApi(
        private val checkInBody: String = """{"CODE":"ok","MESSAGE":"已签到"}""",
        private val checkoutBody: String = """{"CODE":"ok","MESSAGE":"已签退"}""",
        private val cancelBody: String = """{"CODE":"ok","MESSAGE":"已取消预约"}""",
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

        override fun fetchBookingList(url: String): String = error("unused")

        override fun checkIn(url: String): String = checkInBody

        override fun checkout(url: String): String = checkoutBody

        override fun cancelBooking(url: String): String = cancelBody
    }
}
