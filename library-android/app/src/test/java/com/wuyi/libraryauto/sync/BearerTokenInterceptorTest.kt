package com.wuyi.libraryauto.sync

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class BearerTokenInterceptorTest {
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
    fun `injects Authorization header when token is non-empty`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client =
            OkHttpClient.Builder()
                .addInterceptor(BearerTokenInterceptor { "tok-123" })
                .build()

        client.newCall(Request.Builder().url(server.url("/ping")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer tok-123")
    }

    @Test
    fun `omits Authorization header when token is null`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client =
            OkHttpClient.Builder()
                .addInterceptor(BearerTokenInterceptor { null })
                .build()

        client.newCall(Request.Builder().url(server.url("/ping")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    fun `omits Authorization header when token is blank`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client =
            OkHttpClient.Builder()
                .addInterceptor(BearerTokenInterceptor { "   " })
                .build()

        client.newCall(Request.Builder().url(server.url("/ping")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    fun `re-reads token on each request to support runtime rotation`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        var current = "tok-old"
        val client =
            OkHttpClient.Builder()
                .addInterceptor(BearerTokenInterceptor { current })
                .build()

        client.newCall(Request.Builder().url(server.url("/a")).build()).execute().close()
        current = "tok-new"
        client.newCall(Request.Builder().url(server.url("/b")).build()).execute().close()

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer tok-old")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer tok-new")
    }
}
