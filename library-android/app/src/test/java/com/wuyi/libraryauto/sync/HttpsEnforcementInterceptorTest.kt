package com.wuyi.libraryauto.sync

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows

class HttpsEnforcementInterceptorTest {
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
    fun `allows http to loopback host like 127_0_0_1`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client =
            OkHttpClient.Builder()
                .addInterceptor(HttpsEnforcementInterceptor())
                .build()

        // MockWebServer 默认监听 127.0.0.1，server.url 已经是 http://127.0.0.1:port/...
        val response =
            client
                .newCall(Request.Builder().url(server.url("/ok")).build())
                .execute()

        assertThat(response.code).isEqualTo(200)
        response.close()
    }

    @Test
    fun `allows http to localhost`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client =
            OkHttpClient.Builder()
                .addInterceptor(HttpsEnforcementInterceptor())
                .build()
        // MockWebServer 在测试机器上的 127.0.0.1 端口，把 host 段改写成 localhost 字面量。
        val originalUrl = server.url("/ok")
        val localhostUrl = originalUrl.newBuilder().host("localhost").build()

        val response =
            client
                .newCall(Request.Builder().url(localhostUrl).build())
                .execute()

        assertThat(response.code).isEqualTo(200)
        response.close()
    }

    @Test
    fun `rejects http to non-loopback host with HttpsRequiredException`() {
        val client =
            OkHttpClient.Builder()
                .addInterceptor(HttpsEnforcementInterceptor())
                .build()
        val request =
            Request.Builder()
                .url("http://example.com/api/v1/active-accounts".toHttpUrl())
                .build()

        val ex =
            assertThrows(IOException::class.java) {
                client.newCall(request).execute()
            }

        assertThat(ex).isInstanceOf(HttpsRequiredException::class.java)
        assertThat(ex.message).contains("example.com")
    }

    @Test
    fun `allows https requests through to the chain`() {
        // 即使没有真实 TLS endpoint，HTTPS scheme 应让 interceptor 放行进入下一拦截器；
        // 这里通过自定义内部拦截器作为终点验证 chain.proceed 已经被调用。
        var proceeded = false
        val terminator =
            okhttp3.Interceptor { chain ->
                proceeded = true
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("ok")
                    .body("".toResponseBody(null))
                    .build()
            }
        val client =
            OkHttpClient.Builder()
                .addInterceptor(HttpsEnforcementInterceptor())
                .addInterceptor(terminator)
                .build()
        val request = Request.Builder().url("https://example.com/api/v1/x".toHttpUrl()).build()

        client.newCall(request).execute().close()

        assertThat(proceeded).isTrue()
    }
}
