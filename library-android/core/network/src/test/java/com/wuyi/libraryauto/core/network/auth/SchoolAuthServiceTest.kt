package com.wuyi.libraryauto.core.network.auth

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.http.OkHttpSchoolHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SchoolAuthServiceTest {

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
    fun `login fetches metadata submits credentials and returns usable session`() {
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "PHPSESSID=meta-session; Path=/")
                .setBody(
                    """
                    {
                      "content": {
                        "data": {
                          "code": "encoded-code",
                          "str": "encoded-str"
                        },
                        "itemHeader": {
                          "defaultData": {
                            "custom_value": "org-9527"
                          }
                        }
                      }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "Hm_lvt=analytics; Path=/")
                .setBody("""{"id":9527,"name":"tester","accessToken":"token-1"}""")
        )

        val service =
            SchoolAuthService(
                httpClient = OkHttpSchoolHttpClient(),
                installationIdFactory = { "install-1" },
            )

        val actual = service.login(server.url("/portal").toString(), "20240001", "secret")

        assertThat(actual.session.userId).isEqualTo("9527")
        assertThat(actual.session.cookieHeader)
            .contains("PHPSESSID=meta-session")
        assertThat(actual.session.cookieHeader)
            .contains("Hm_lvt=analytics")
        assertThat(actual.currentUserJson)
            .isEqualTo(
                """{"id":9527,"name":"tester","accessToken":"token-1","access_token":"token-1","objectId":"9527","sessionToken":"fake","className":"_User"}"""
            )
        assertThat(actual.cookies.map { it.name to it.value })
            .containsExactly(
                "web_language" to "zh-CN",
                "PHPSESSID" to "meta-session",
                "Hm_lvt" to "analytics",
            )
            .inOrder()

        val metadataRequest = server.takeRequest()
        assertThat(metadataRequest.path).isEqualTo("/User/Index/login?LAB_JSON=1")
        assertThat(metadataRequest.method).isEqualTo("GET")
        assertThat(metadataRequest.getHeader("Cookie")).isEqualTo("web_language=zh-CN")

        val loginRequest = server.takeRequest()
        assertThat(loginRequest.path).isEqualTo("/api/1/login")
        assertThat(loginRequest.method).isEqualTo("POST")
        assertThat(loginRequest.getHeader("Cookie")).isNotNull()
        assertThat(loginRequest.body.readUtf8())
            .isEqualTo(
                """{"login_name":"20240001","password":"secret","ui_type":"com.Raw","code":"encoded-code","str":"encoded-str","org_id":"org-9527","_ApplicationId":"lab4","_JavaScriptKey":"lab4","_ClientVersion":"js_xxx","_InstallationId":"install-1","_SessionToken":"fake"}"""
            )
    }

    @Test
    fun `login keeps cookies that are set during redirect chain`() {
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "PHPSESSID=meta-session; Path=/")
                .setBody(
                    """
                    {
                      "content": {
                        "data": {
                          "code": "encoded-code",
                          "str": "encoded-str"
                        },
                        "itemHeader": {
                          "defaultData": {
                            "custom_value": "org-9527"
                          }
                        }
                      }
                    }
                    """.trimIndent()
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/api/1/login/final").toString())
                .setHeader("Set-Cookie", "auth=redirect-token; Path=/")
                .setBody(""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "uid=9527-cookie; Path=/")
                .setBody("""{"id":9527,"name":"tester","accessToken":"token-1"}"""),
        )

        val service =
            SchoolAuthService(
                httpClient = OkHttpSchoolHttpClient(),
                installationIdFactory = { "install-1" },
            )

        val actual = service.login(server.url("/portal").toString(), "20240001", "secret")

        assertThat(actual.session.cookieHeader)
            .contains("auth=redirect-token")
        assertThat(actual.session.cookieHeader)
            .contains("uid=9527-cookie")
    }
}
