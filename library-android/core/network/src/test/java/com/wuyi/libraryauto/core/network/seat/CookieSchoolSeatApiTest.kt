package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.http.OkHttpSchoolHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CookieSchoolSeatApiTest {

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
    fun `cancelBooking posts form request with session headers`() {
        server.enqueue(MockResponse().setBody("""{"CODE":"ok","MESSAGE":"已取消预约"}"""))
        val api =
            CookieSchoolSeatApi(
                session = fakeSession(),
                httpClient = OkHttpSchoolHttpClient(),
            )
        val requestUrl = server.url("/Seat/Index/cancelBooking?bookingId=booking-1&LAB_JSON=1").toString()

        val body = api.cancelBooking(requestUrl)

        assertThat(body).isEqualTo("""{"CODE":"ok","MESSAGE":"已取消预约"}""")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/Seat/Index/cancelBooking?bookingId=booking-1&LAB_JSON=1")
        assertThat(request.getHeader("Cookie")).isEqualTo("auth=token-20230001")
        assertThat(request.getHeader("Origin")).isEqualTo(server.url("/").toString().removeSuffix("/"))
        assertThat(request.getHeader("Referer")).isEqualTo(server.url("/").toString())
    }

    private fun fakeSession(): AuthenticatedSession =
        AuthenticatedSession(
            session = SessionBundle(cookieHeader = "auth=token-20230001", userId = "20230001"),
            cookies = emptyList(),
            currentUserJson = """{"id":"20230001"}""",
            origin = server.url("/").toString().removeSuffix("/"),
            installationId = "install-20230001",
        )
}
