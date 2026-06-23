package com.wuyi.libraryauto.core.network.captive

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class TargetReachabilityProbeTest {

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
    fun `probe returns reachable for 200 OK`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = OkHttpTargetReachabilityProbe(OkHttpClient())
            .probe(server.url("/seat").toString())

        assertThat(result.reachable).isTrue()
        assertThat(result.failureReason).isNull()
        assertThat(server.takeRequest().method).isEqualTo("HEAD")
    }

    @Test
    fun `probe maps timeout to timeout failure`() = runBlocking {
        val result = OkHttpTargetReachabilityProbe(clientThrowing(SocketTimeoutException("timeout")))
            .probe("https://example.test/")

        assertThat(result.reachable).isFalse()
        assertThat(result.failureReason).isEqualTo(ProbeResult.FailureReason.TIMEOUT)
    }

    @Test
    fun `probe maps certificate error to certificate failure`() = runBlocking {
        val result = OkHttpTargetReachabilityProbe(clientThrowing(SSLHandshakeException("bad certificate")))
            .probe("https://example.test/")

        assertThat(result.reachable).isFalse()
        assertThat(result.failureReason).isEqualTo(ProbeResult.FailureReason.CERTIFICATE_ERROR)
    }

    private fun clientThrowing(cause: IOException): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { throw cause }
            .build()
}
