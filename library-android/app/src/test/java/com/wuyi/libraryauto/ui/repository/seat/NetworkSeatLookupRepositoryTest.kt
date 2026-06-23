package com.wuyi.libraryauto.ui.repository.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.http.HttpResponse
import com.wuyi.libraryauto.core.network.http.SchoolHttpClient
import com.wuyi.libraryauto.core.network.seat.SeatLookupService
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSeatLookupRepositoryTest {

    @Test
    fun `loadDefaultSeats retries next entry url when first query page is invalid`() = runTest {
        val httpClient =
            QueueHttpClient(
                responses =
                    ArrayDeque(
                        listOf(
                            response(
                                """
                                {
                                  "content": {
                                    "defaultItems": [
                                      {
                                        "link": {
                                          "url": "/Seat/Index/searchSeats?content_id=999"
                                        }
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent(),
                            ),
                            response(
                                """
                                {
                                  "CODE": "所属空间不存在",
                                  "ui_type": "com.Message"
                                }
                                """.trimIndent(),
                            ),
                            response(
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
                                """.trimIndent(),
                            ),
                            response(
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
                                """.trimIndent(),
                            ),
                        ),
                    ),
            )
        val repository =
            NetworkSeatLookupRepository(
                seatLookupService = SeatLookupService(httpClient = httpClient),
                sessionRepository = FakeSessionRepository(loggedInSession()),
                entryUrls =
                    listOf(
                        "https://example.com/#!/Space/Category/list",
                        "https://example.com/Seat/Index/searchSeats?content_id=301",
                    ),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result = repository.loadDefaultSeats()

        assertThat(result).isInstanceOf(SeatLookupLoadResult.Success::class.java)
        val success = result as SeatLookupLoadResult.Success
        assertThat(success.data.catalogOnly).isFalse()
        assertThat(success.data.rooms.map { room -> room.roomName }).containsExactly("三楼西区")
        assertThat(success.data.rooms.single().seatNumbers).containsExactly("301")
    }

    @Test
    fun `loadSeats retries next candidate search url from same entry`() = runTest {
        val httpClient =
            QueueHttpClient(
                responses =
                    ArrayDeque(
                        listOf(
                            response(
                                """
                                {
                                  "content": {
                                    "defaultItems": [
                                      {
                                        "link": {
                                          "url": "/Seat/Index/searchSeats?content_id=999"
                                        }
                                      },
                                      {
                                        "link": {
                                          "url": "/Seat/Index/searchSeats?content_id=301"
                                        }
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent(),
                            ),
                            response(
                                """
                                {
                                  "CODE": "所属空间不存在",
                                  "ui_type": "com.Message"
                                }
                                """.trimIndent(),
                            ),
                            response(
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
                                """.trimIndent(),
                            ),
                            response(
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
                                """.trimIndent(),
                            ),
                        ),
                    ),
            )
        val repository =
            NetworkSeatLookupRepository(
                seatLookupService = SeatLookupService(httpClient = httpClient),
                sessionRepository = FakeSessionRepository(loggedInSession()),
                entryUrls = listOf("https://example.com/#!/Space/Category/list"),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result =
            repository.loadSeats(
                SeatLookupQuery(
                    studentId = loggedInSession().session.userId,
                    entryUrl = "https://example.com/#!/Space/Category/list",
                    beginTimeEpochSeconds = 1_711_111_111,
                    durationSeconds = 14_400,
                    peopleCount = 1,
                ),
            )

        assertThat(result).isInstanceOf(SeatLookupLoadResult.Success::class.java)
        val success = result as SeatLookupLoadResult.Success
        assertThat(success.data.rooms.single().roomName).isEqualTo("三楼西区")
    }

    @Test
    fun `loadSeats prefers cached resolved search url for matching student and entry`() = runTest {
        val httpClient =
            QueueHttpClient(
                responses =
                    ArrayDeque(
                        listOf(
                            response(
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
                                """.trimIndent(),
                            ),
                            response(
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
                                """.trimIndent(),
                            ),
                        ),
                    ),
            )
        val entryUrl = "https://example.com/#!/Space/Category/list"
        val resolvedSearchApiUrl =
            "https://example.com/Seat/Index/searchSeats?" +
                "space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28"
        val resolvedSeatUrlRepository =
            InMemoryResolvedSeatUrlRepository().apply {
                save(studentId = loggedInSession().session.userId, entryUrl = entryUrl, searchApiUrl = resolvedSearchApiUrl)
            }
        val repository =
            NetworkSeatLookupRepository(
                seatLookupService = SeatLookupService(httpClient = httpClient),
                sessionRepository = FakeSessionRepository(loggedInSession()),
                entryUrls = listOf(entryUrl),
                resolvedSeatUrlRepository = resolvedSeatUrlRepository,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result =
            repository.loadSeats(
                SeatLookupQuery(
                    studentId = loggedInSession().session.userId,
                    entryUrl = entryUrl,
                    beginTimeEpochSeconds = 1_711_111_111,
                    durationSeconds = 14_400,
                    peopleCount = 1,
                ),
            )

        assertThat(result).isInstanceOf(SeatLookupLoadResult.Success::class.java)
        val success = result as SeatLookupLoadResult.Success
        assertThat(success.data.rooms.single().roomName).isEqualTo("三楼西区")
        assertThat(httpClient.recordedUrls.first()).isEqualTo("$resolvedSearchApiUrl&LAB_JSON=1")
    }

    @Test
    fun `loadDefaultSeats falls back to default catalog when query page shape changes`() = runTest {
        val httpClient =
            QueueHttpClient(
                responses =
                    ArrayDeque(
                        listOf(
                            response(
                                """
                                {
                                  "content": {
                                    "defaultItems": [
                                      {
                                        "link": {
                                          "url": "/Seat/Index/searchSeats?space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28"
                                        }
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent(),
                            ),
                            response(
                                """
                                {
                                  "CODE": "NotFound",
                                  "ui_type": "com.Message"
                                }
                                """.trimIndent(),
                            ),
                        ),
                    ),
            )
        val repository =
            NetworkSeatLookupRepository(
                seatLookupService = SeatLookupService(httpClient = httpClient),
                sessionRepository = FakeSessionRepository(loggedInSession()),
                entryUrls = listOf("https://example.com/#!/Space/Category/list"),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result = repository.loadDefaultSeats()

        assertThat(result).isInstanceOf(SeatLookupLoadResult.Success::class.java)
        val success = result as SeatLookupLoadResult.Success
        assertThat(success.data.catalogOnly).isTrue()
        assertThat(success.data.notice).contains("CODE=NotFound")
        assertThat(success.data.rooms.map { room -> room.roomName }).containsExactly(
            "综合阅览室",
            "自习室圆形二楼",
            "自习室圆形一楼",
        ).inOrder()
        assertThat(success.data.rooms.map { room -> room.seatNumbers.size }).containsExactly(360, 188, 120).inOrder()
    }

    @Test
    fun `loadDefaultSeats returns not logged in when session is missing`() = runTest {
        val repository =
            NetworkSeatLookupRepository(
                seatLookupService = SeatLookupService(httpClient = QueueHttpClient()),
                sessionRepository = FakeSessionRepository(null),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result = repository.loadDefaultSeats()

        assertThat(result).isEqualTo(SeatLookupLoadResult.NotLoggedIn)
    }

    @Test
    fun `loadSeats waits for web session bootstrap before querying entry page`() = runTest {
        val httpClient =
            QueueHttpClient(
                responses =
                    ArrayDeque(
                        listOf(
                            response(
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
                                """.trimIndent(),
                            ),
                            response(
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
                                """.trimIndent(),
                            ),
                        ),
                    ),
            )
        val sessionRepository = FakeSessionRepository(apiOnlySession())
        launch {
            delay(1_500)
            sessionRepository.save(
                studentId = apiOnlySession().session.userId,
                session = webSession(),
                activate = true,
            )
        }
        val repository =
            NetworkSeatLookupRepository(
                seatLookupService = SeatLookupService(httpClient = httpClient),
                sessionRepository = sessionRepository,
                entryUrls = listOf("https://example.com/Seat/Index/searchSeats?content_id=301"),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result =
            repository.loadSeats(
                SeatLookupQuery(
                    studentId = apiOnlySession().session.userId,
                    entryUrl = "https://example.com/Seat/Index/searchSeats?content_id=301",
                    beginTimeEpochSeconds = 1_711_111_111,
                    durationSeconds = 14_400,
                    peopleCount = 1,
                ),
            )

        assertThat(result).isInstanceOf(SeatLookupLoadResult.Success::class.java)
        assertThat(httpClient.recordedCookies.first()).contains("org_id=137")
    }

    private fun loggedInSession(): AuthenticatedSession =
        AuthenticatedSession(
            session = SessionBundle(cookieHeader = "auth=token", userId = "405963"),
            cookies = emptyList(),
            currentUserJson = """{"id":"405963"}""",
            origin = "https://wuyiu.huitu.zhishulib.com",
            installationId = "install-1",
        )

    private fun apiOnlySession(): AuthenticatedSession =
        loggedInSession().copy(
            session = SessionBundle(cookieHeader = "web_language=zh-CN; api_access_token=token", userId = "405963"),
        )

    private fun webSession(): AuthenticatedSession =
        loggedInSession().copy(
            session =
                SessionBundle(
                    cookieHeader = "org_id=137; login_time=1775887200; uid=405963; auth=token; is_remember=1; web_language=zh-CN",
                    userId = "405963",
                ),
        )

    private fun response(body: String): HttpResponse =
        HttpResponse(
            requestUrl = "https://wuyiu.huitu.zhishulib.com/mock",
            statusCode = 200,
            body = body,
            cookies = emptyList(),
        )

    private class QueueHttpClient(
        private val responses: ArrayDeque<HttpResponse> = ArrayDeque(),
    ) : SchoolHttpClient {
        val recordedCookies = mutableListOf<String>()
        val recordedUrls = mutableListOf<String>()

        override fun get(
            url: String,
            headers: Map<String, String>,
        ): HttpResponse {
            recordedCookies += headers["Cookie"].orEmpty()
            recordedUrls += url
            return responses.removeFirst()
        }

        override fun postJson(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpResponse = error("postJson should not be called in this test")

        override fun postForm(
            url: String,
            formFields: List<Pair<String, String>>,
            headers: Map<String, String>,
        ): HttpResponse {
            recordedCookies += headers["Cookie"].orEmpty()
            recordedUrls += url
            return responses.removeFirst()
        }
    }

    private class InMemoryResolvedSeatUrlRepository : ResolvedSeatUrlRepository {
        private val values = linkedMapOf<Pair<String, String>, String>()

        override fun load(
            studentId: String,
            entryUrl: String,
        ): String? = values[studentId.trim() to entryUrl.trim()]

        override fun save(
            studentId: String,
            entryUrl: String,
            searchApiUrl: String,
        ) {
            values[studentId.trim() to entryUrl.trim()] = searchApiUrl.trim()
        }

        override fun remove(
            studentId: String,
            entryUrl: String,
        ) {
            values.remove(studentId.trim() to entryUrl.trim())
        }
    }

    private class FakeSessionRepository(
        initialSession: AuthenticatedSession?,
    ) : SessionRepository {
        private val state = MutableStateFlow(initialSession)
        private var activeStudentId: String? = initialSession?.session?.userId

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? =
            state.value?.takeIf { activeStudentId == studentId }

        override fun activeStudentId(): String? = activeStudentId

        override fun activate(studentId: String): Boolean =
            state.value?.let {
                activeStudentId = studentId
                true
            } ?: false

        override fun save(session: AuthenticatedSession) {
            state.value = session
        }

        override fun save(
            studentId: String,
            session: AuthenticatedSession,
            activate: Boolean,
        ) {
            state.value = session
            if (activate) {
                activeStudentId = studentId
            }
        }

        override fun remove(studentId: String) {
            if (activeStudentId == studentId) {
                activeStudentId = null
                state.value = null
            }
        }

        override fun clear() {
            activeStudentId = null
            state.value = null
        }
    }
}
