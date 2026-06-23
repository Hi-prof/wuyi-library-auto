package com.wuyi.libraryauto.core.network.captive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CampusPortalAuthenticatorTest {
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
    fun `crypto support builds encrypted campus payload`() {
        val payload =
            CampusPortalCryptoSupport().buildLoginPayload(
                loginPageHtml = loginPageHtml(),
                username = "user1",
                password = "password",
            )

        assertThat(payload.missingFields).isEmpty()
        assertThat(payload.parameters["execution"]).isEqualTo("flow-1")
        assertThat(payload.parameters["type"]).isEqualTo("UsernamePassword")
        assertThat(payload.parameters["croypto"]).isEqualTo(CRYPTO_KEY)
        assertThat(payload.parameters["password"]).isEqualTo("rmA8ZQEXu6eT2FIpXLr4yw==")
        assertThat(payload.parameters["captcha_payload"]).isEqualTo("b1s2l+ntfkq4Vh3dDBOcuw==")
    }

    @Test
    fun `authenticate posts encrypted payload and emits recovery on success`() = runBlocking {
        val emitter = RecordingRecoveryEmitter()
        server.enqueue(MockResponse().setBody(loginPageHtml()))
        server.enqueue(MockResponse().setBody("您已成功连接校园网!"))

        val result = authenticator(emitter = emitter).authenticate(authRequest())

        assertThat(result).isEqualTo(CampusPortalAuthResult.Success("校园网认证成功"))
        assertThat(emitter.emitCount).isEqualTo(1)
        assertThat(server.takeRequest().method).isEqualTo("GET")
        val postRequest = server.takeRequest()
        assertThat(postRequest.method).isEqualTo("POST")
        val form = postRequest.body.readUtf8()
        assertThat(form).contains("username=user1")
        assertThat(form).contains("execution=flow-1")
        assertThat(form).contains("password=rmA8ZQEXu6eT2FIpXLr4yw%3D%3D")
    }

    @Test
    fun `authenticate returns failure message from portal`() = runBlocking {
        server.enqueue(MockResponse().setBody(loginPageHtml()))
        server.enqueue(MockResponse().setBody("""<div role="alert">密码错误</div>"""))

        val result = authenticator().authenticate(authRequest())

        assertThat(result).isEqualTo(CampusPortalAuthResult.Failure("密码错误"))
    }

    @Test
    fun `authenticate enters cooldown after three failures and skips during cooldown`() = runBlocking {
        var now = 1_000L
        val authenticator = authenticator(clockMillis = { now })
        repeat(3) {
            server.enqueue(MockResponse().setBody(loginPageHtml()))
            server.enqueue(MockResponse().setBody("""<div class="ant-message">认证失败</div>"""))
            assertThat(authenticator.authenticate(authRequest()))
                .isEqualTo(CampusPortalAuthResult.Failure("认证失败"))
        }

        val skipped = authenticator.authenticate(authRequest())

        assertThat(skipped).isInstanceOf(CampusPortalAuthResult.Skipped::class.java)
        assertThat((skipped as CampusPortalAuthResult.Skipped).reason)
            .isEqualTo(CampusPortalSkipReason.COOLDOWN)
        assertThat(server.requestCount).isEqualTo(6)

        now += 30L * 60L * 1_000L + 1L
        server.enqueue(MockResponse().setBody(loginPageHtml()))
        server.enqueue(MockResponse().setBody("您已成功连接校园网!"))
        assertThat(authenticator.authenticate(authRequest()))
            .isEqualTo(CampusPortalAuthResult.Success("校园网认证成功"))
    }

    private fun authenticator(
        emitter: CampusAuthRecoveryEmitter = CampusAuthRecoveryEmitter.Noop,
        clockMillis: () -> Long = { 1_000L },
    ): CampusPortalAuthenticator =
        CampusPortalAuthenticator(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            recoveryEmitter = emitter,
            clockMillis = clockMillis,
        )

    private fun authRequest(): CampusPortalAuthRequest =
        CampusPortalAuthRequest(
            loginPageUrl = server.url("/authserver/login?service=seat").toString(),
            username = "user1",
            password = "password",
        )

    private fun loginPageHtml(): String =
        """
        <html>
          <input id="login-page-flowkey" value="flow-1" />
          <input id="login-croypto" value="$CRYPTO_KEY" />
          <input id="current-login-type" value="UsernamePassword" />
        </html>
        """.trimIndent()

    private class RecordingRecoveryEmitter : CampusAuthRecoveryEmitter {
        var emitCount = 0
            private set

        override suspend fun emitCampusAuthRecovery() {
            emitCount += 1
        }
    }

    private companion object {
        private const val CRYPTO_KEY = "MTIzNDU2Nzg5MGFiY2RlZg=="
    }
}
