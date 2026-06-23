package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeatLookupRepositoryTest {

    @Test
    fun `serializePoi maps selectable recommended seat`() {
        val poi =
            mapOf<String, Any?>(
                "id" to 88,
                "title" to " 58 ",
                "x" to 10,
                "y" to 20,
                "w" to 30,
                "h" to 40,
                "state" to 0,
                "recommend" to true,
            )

        val actual = SeatLookupRepository.serializePoi(poi)

        assertThat(actual)
            .isEqualTo(
                SeatPoi(
                    seatId = "88",
                    seatNumber = "58",
                    x = 10,
                    y = 20,
                    w = 30,
                    h = 40,
                    state = "0",
                    selectable = true,
                    recommended = true,
                )
            )
    }

    @Test
    fun `serializePoi maps locked non recommended seat`() {
        val poi =
            mapOf<String, Any?>(
                "id" to "99",
                "title" to "A01",
                "x" to 1,
                "y" to 2,
                "w" to 3,
                "h" to 4,
                "state" to "1",
                "recommend" to 0,
            )

        val actual = SeatLookupRepository.serializePoi(poi)

        assertThat(actual.selectable).isFalse()
        assertThat(actual.recommended).isFalse()
        assertThat(actual.state).isEqualTo("1")
        assertThat(actual.seatId).isEqualTo("99")
        assertThat(actual.seatNumber).isEqualTo("A01")
    }

    @Test
    fun `serializePoi normalizes double state into selectable string`() {
        val poi =
            mapOf<String, Any?>(
                "id" to "seat-2",
                "title" to "02",
                "x" to 10,
                "y" to 20,
                "w" to 30,
                "h" to 40,
                "state" to 2.0,
                "recommend" to "1",
            )

        val actual = SeatLookupRepository.serializePoi(poi)

        assertThat(actual.state).isEqualTo("2")
        assertThat(actual.selectable).isTrue()
        assertThat(actual.recommended).isTrue()
    }

    @Test
    fun `serializePoi treats string falsey recommend values as false`() {
        val zeroPoi =
            mapOf<String, Any?>(
                "id" to "seat-3",
                "title" to "03",
                "x" to 1,
                "y" to 2,
                "w" to 3,
                "h" to 4,
                "state" to 0.0,
                "recommend" to "0",
            )
        val falsePoi =
            mapOf<String, Any?>(
                "id" to "seat-4",
                "title" to "04",
                "x" to 1,
                "y" to 2,
                "w" to 3,
                "h" to 4,
                "state" to "0",
                "recommend" to "false",
            )
        val emptyPoi =
            mapOf<String, Any?>(
                "id" to "seat-5",
                "title" to "05",
                "x" to 1,
                "y" to 2,
                "w" to 3,
                "h" to 4,
                "state" to "0",
                "recommend" to "",
            )
        val nullPoi =
            mapOf<String, Any?>(
                "id" to "seat-6",
                "title" to "06",
                "x" to 1,
                "y" to 2,
                "w" to 3,
                "h" to 4,
                "state" to "0",
                "recommend" to null,
            )

        assertThat(SeatLookupRepository.serializePoi(zeroPoi).recommended).isFalse()
        assertThat(SeatLookupRepository.serializePoi(falsePoi).recommended).isFalse()
        assertThat(SeatLookupRepository.serializePoi(emptyPoi).recommended).isFalse()
        assertThat(SeatLookupRepository.serializePoi(nullPoi).recommended).isFalse()
    }

    @Test
    fun `buildSearchFormPayload mirrors python default fields`() {
        val actual =
            SeatLookupRepository.buildSearchFormPayload(
                """
                {
                  "data": {
                    "default": {
                      "date": 1711111111,
                      "duration": 4,
                      "num": 2
                    },
                    "space_category": {
                      "category_id": 11,
                      "content_id": 22
                    }
                  }
                }
                """.trimIndent()
            )

        assertThat(actual)
            .containsExactly(
                "beginTime" to "1711111111",
                "duration" to "14400",
                "num" to "2",
                "space_category[category_id]" to "11",
                "space_category[content_id]" to "22",
            )
            .inOrder()
    }

    @Test
    fun `buildCustomSearchFormPayload overrides time duration and people count`() {
        val searchPagePayload =
            """
            {
              "data": {
                "default": {
                  "date": 1711111111,
                  "duration": 4,
                  "num": 2
                },
                "space_category": {
                  "category_id": 11,
                  "content_id": 22
                }
              }
            }
            """.trimIndent()

        val actual =
            SeatLookupRepository.buildCustomSearchFormPayload(
                payloadJson = searchPagePayload,
                beginTime = 1711197600,
                durationSeconds = 7200,
                peopleCount = 3,
            )

        assertThat(actual)
            .containsExactly(
                "beginTime" to "1711197600",
                "duration" to "7200",
                "num" to "3",
                "space_category[category_id]" to "11",
                "space_category[content_id]" to "22",
            )
            .inOrder()
    }

    @Test
    fun `parseSearchPage reports payload type when data is missing`() {
        val payload =
            """
            {
              "CODE": "NotFound",
              "ui_type": "com.Message"
            }
            """.trimIndent()

        val failure =
            runCatching { SeatLookupRepository.parseSearchPage(payload) }
                .exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).contains("查询页没有返回 data")
        assertThat(failure?.message).contains("com.Message")
        assertThat(failure?.message).contains("CODE=NotFound")
    }

    @Test
    fun `serializeSeatMap picks recommended room and counts seats`() {
        val payload =
            """
            {
              "content": {
                "defaultItems": [
                  {
                    "ui_type": "ht.Seat.RecommendSeatItem",
                    "roomName": "二楼东区",
                    "ifRecommend": true,
                    "seatMap": {
                      "info": {
                        "id": "room-2f-east",
                        "storey": "2F",
                        "plan": "/static/plan.png",
                        "width": 1200,
                        "height": 900
                      },
                      "POIs": [
                        {
                          "id": "seat-001",
                          "title": "001",
                          "x": 10,
                          "y": 20,
                          "w": 30,
                          "h": 40,
                          "state": 2,
                          "recommend": 1,
                          "have_socket": 1
                        },
                        {
                          "id": "seat-002",
                          "title": "002",
                          "x": 50,
                          "y": 60,
                          "w": 30,
                          "h": 40,
                          "state": 1,
                          "recommend": 0,
                          "have_socket": 0
                        }
                      ]
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        val actual = SeatLookupRepository.serializeSeatMap(payload)

        assertThat(actual.roomId).isEqualTo("room-2f-east")
        assertThat(actual.roomName).isEqualTo("二楼东区")
        assertThat(actual.availableCount).isEqualTo(1)
        assertThat(actual.lockedCount).isEqualTo(1)
        assertThat(actual.selectedSeatId).isEqualTo("seat-001")
        assertThat(actual.systemRecommendedSeatId).isEqualTo("seat-001")
        assertThat(actual.seats.first().hasSocket).isTrue()
    }
}
