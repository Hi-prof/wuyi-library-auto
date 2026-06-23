package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Test
import kotlin.random.Random

class BookingDetailParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `loadBookingDetail parses window status and normalized ibeacon minors`() {
        val service =
            SeatBookingStatusService(
                api =
                    FakeSchoolSeatApi(
                        bookingListBody =
                            """
                            {"content":{"defaultItems":[{
                              "id":"booking-1",
                              "status":"0",
                              "time":1775869200,
                              "limitSignAgo":"900",
                              "limitSignBack":1800,
                              "ibeacons":[
                                {"minor":42},
                                {"minor":"7"},
                                {"minor":42},
                                {"minor":-1},
                                {"minor":65536},
                                {"minor":"bad"},
                                {"name":"missing minor"}
                              ]
                            }]}}
                            """.trimIndent(),
                    ),
            )

        val detail = service.loadBookingDetail("https://example.com/Seat/Index/myBookingList?LAB_JSON=1", "booking-1")

        assertThat(detail).isNotNull()
        assertThat(detail!!.bookingId).isEqualTo("booking-1")
        assertThat(detail.window.startEpochSeconds).isEqualTo(1775869200)
        assertThat(detail.window.limitSignAgoSeconds).isEqualTo(900)
        assertThat(detail.window.limitSignBackSeconds).isEqualTo(1800)
        assertThat(detail.expectedMinors).containsExactly(7, 42).inOrder()
        assertThat(detail.statusLabel).isEqualTo("待签到")
        assertThat(detail.isAlreadySignedIn).isFalse()
    }

    @Test
    fun `loadBookingDetail uses fallback window and empty minors when fields are missing`() {
        val service =
            SeatBookingStatusService(
                api =
                    FakeSchoolSeatApi(
                        bookingListBody =
                            """
                            {"CODE":"ok","DATA":{"list":[{
                              "id":"booking-2",
                              "status":"1",
                              "time":1775955600
                            }]}}
                            """.trimIndent(),
                    ),
            )

        val detail = service.loadBookingDetail("https://example.com/Seat/Index/myBookingList?LAB_JSON=1", "booking-2")

        assertThat(detail).isNotNull()
        assertThat(detail!!.window.limitSignAgoSeconds).isEqualTo(1800)
        assertThat(detail.window.limitSignBackSeconds).isEqualTo(1800)
        assertThat(detail.expectedMinors).isEmpty()
        assertThat(detail.statusLabel).isEqualTo("签到成功，使用中")
        assertThat(detail.isAlreadySignedIn).isTrue()
    }

    @Test
    fun `minor parser always returns in range sorted distinct list capped at 256`() {
        repeat(40) { seed ->
            val random = Random(seed)
            val rawItems =
                List(400) { index ->
                    when {
                        index % 17 == 0 -> """{"minor":"bad"}"""
                        index % 11 == 0 -> """{"minor":${65_536 + random.nextInt(5_000)}}"""
                        index % 7 == 0 -> """{"minor":${-random.nextInt(1, 5_000)}}"""
                        else -> """{"minor":${random.nextInt(0, 300)}}"""
                    }
                }
            val rawList = json.parseToJsonElement(rawItems.joinToString(prefix = "[", postfix = "]")).jsonArray

            val minors = IBeaconMinorListParser.parse(rawList)

            assertThat(minors).isEqualTo(minors.distinct().sorted())
            assertThat(minors.size).isAtMost(256)
            assertThat(minors.all { minor -> minor in 0..65_535 }).isTrue()
        }
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
