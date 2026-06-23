package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.http.OkHttpSchoolHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SeatLookupServiceTest {

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
    fun `service reuses direct search url without extra entry request`() {
        val service = SeatLookupService(httpClient = OkHttpSchoolHttpClient())
        val session = SessionBundle(cookieHeader = "PHPSESSID=session-1", userId = "9527")
        val directSearchUrl =
            "${server.url("/Seat/Index/searchSeats?space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28")}".removeSuffix(
                "/",
            )

        val resolved = service.resolveSearchApiUrl(directSearchUrl, session)

        assertThat(resolved).isEqualTo(directSearchUrl)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `service resolves search url fetches page and searches seats`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "content": {
                    "defaultItems": [
                      {
                        "link": {
                          "url": "/Seat/Index/searchSeats?LAB_JSON=1&content_id=301"
                        }
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "default": {
                      "date": 1711111111,
                      "duration": 4,
                      "num": 1
                    },
                    "space_category": {
                      "category_id": 11,
                      "content_id": 301
                    }
                  }
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "content": {
                    "defaultItems": [
                      {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "三楼西区",
                        "ifRecommend": true,
                        "seatMap": {
                          "info": {
                            "id": "room-3f-west",
                            "storey": "3F",
                            "plan": "/static/plan-3f.png",
                            "width": 1000,
                            "height": 600
                          },
                          "POIs": [
                            {
                              "id": "seat-301",
                              "title": "301",
                              "x": 10,
                              "y": 20,
                              "w": 30,
                              "h": 40,
                              "state": 0,
                              "recommend": 1,
                              "have_socket": 1
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )

        val service = SeatLookupService(httpClient = OkHttpSchoolHttpClient())
        val session = SessionBundle(cookieHeader = "PHPSESSID=session-1", userId = "9527")
        val entryUrl = "${server.url("/app-shell")}#!/Space/Index/detail?content=301"

        val searchApiUrl = service.resolveSearchApiUrl(entryUrl, session)
        val searchPage = service.fetchSearchPage(searchApiUrl, session)
        val result = service.searchSeats(searchApiUrl, session, SeatLookupRepository.buildSearchFormPayload(searchPage.rawPayload))

        assertThat(searchApiUrl)
            .isEqualTo("${server.url("/Seat/Index/searchSeats?content_id=301")}".removeSuffix("/"))
        assertThat(searchPage.categoryId).isEqualTo("11")
        assertThat(searchPage.contentId).isEqualTo("301")
        assertThat(result.selectedRoom.roomId).isEqualTo("room-3f-west")
        assertThat(result.selectedRoom.seats.single().hasSocket).isTrue()

        val entryRequest = server.takeRequest()
        assertThat(entryRequest.path).isEqualTo("/Space/Index/detail?content=301&LAB_JSON=1")
        assertThat(entryRequest.getHeader("Cookie")).isEqualTo("PHPSESSID=session-1")

        val searchPageRequest = server.takeRequest()
        assertThat(searchPageRequest.path).isEqualTo("/Seat/Index/searchSeats?content_id=301&LAB_JSON=1")

        val searchSeatsRequest = server.takeRequest()
        assertThat(searchSeatsRequest.method).isEqualTo("POST")
        assertThat(searchSeatsRequest.path).isEqualTo("/Seat/Index/searchSeats?content_id=301&LAB_JSON=1")
        assertThat(searchSeatsRequest.body.readUtf8())
            .isEqualTo(
                "beginTime=1711111111&duration=14400&num=1&space_category%5Bcategory_id%5D=11&space_category%5Bcontent_id%5D=301"
            )
    }
}
