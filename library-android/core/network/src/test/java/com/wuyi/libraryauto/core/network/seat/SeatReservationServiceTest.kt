package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.http.OkHttpSchoolHttpClient
import java.security.MessageDigest
import java.util.Base64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SeatReservationServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reserveSeat posts bookSeats request and returns booking id`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "CODE": "ok",
                  "DATA": {
                    "result": "success",
                    "bookingId": "booking-166"
                  }
                }
                """.trimIndent(),
            ),
        )
        val service =
            SeatReservationService(
                httpClient = OkHttpSchoolHttpClient(),
                apiTimeProvider = { 1_712_800_000 },
            )

        val receipt =
            service.reserveSeat(
                searchApiUrl = "${server.url("/Seat/Index/searchSeats?content_id=301")}".removeSuffix("/"),
                session = SessionBundle(cookieHeader = "PHPSESSID=session-1", userId = "9527"),
                seatId = "seat-166",
                roomId = "room-2f",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                beginTime = 1_712_800_000,
                durationSeconds = 14_400,
            )

        assertThat(receipt).isEqualTo(
            ReservationReceipt(
                bookingId = "booking-166",
                message = "已提交 自习室圆形二楼 166 号座位预约",
            ),
        )

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/Seat/Index/bookSeats?LAB_JSON=1")
        assertThat(request.getHeader("Cookie")).isEqualTo("PHPSESSID=session-1")
        assertThat(request.getHeader("Origin")).isEqualTo(server.url("/").toString().removeSuffix("/"))
        assertThat(request.getHeader("Referer")).isEqualTo(server.url("/").toString())
        assertThat(request.getHeader("Api-Token")).isEqualTo(
            expectedApiToken(
                beginTime = 1_712_800_000,
                durationSeconds = 14_400,
                seatId = "seat-166",
                userId = "9527",
                apiTime = 1_712_800_000,
            ),
        )
        assertThat(request.body.readUtf8()).isEqualTo(
            "beginTime=1712800000&duration=14400&seats%5B0%5D=seat-166&is_recommend=0&api_time=1712800000&seatBookers%5B0%5D=9527",
        )
    }

    private fun expectedApiToken(
        beginTime: Int,
        durationSeconds: Int,
        seatId: String,
        userId: String,
        apiTime: Int,
    ): String {
        val signSource =
            buildString {
                append("post&/Seat/Index/bookSeats?LAB_JSON=1")
                append("&api_time").append(apiTime)
                append("&beginTime").append(beginTime)
                append("&duration").append(durationSeconds)
                append("&is_recommend0")
                append("&seatBookers[0]").append(userId.toInt())
                append("&seats[0]").append(seatId)
            }
        val digest =
            MessageDigest.getInstance("MD5")
                .digest(signSource.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        return Base64.getEncoder().encodeToString(digest.toByteArray(Charsets.UTF_8))
    }
}
