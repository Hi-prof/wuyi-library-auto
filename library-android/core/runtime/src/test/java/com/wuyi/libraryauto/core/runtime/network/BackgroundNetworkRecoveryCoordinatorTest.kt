package com.wuyi.libraryauto.core.runtime.network

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackgroundNetworkRecoveryCoordinatorTest {

    @Test
    fun `recoverIfNeeded returns success immediately when network is already available`() = runTest {
        val manager = FakeWorkerNetworkManager(connected = true)

        val result = BackgroundNetworkRecoveryCoordinator(manager).recoverIfNeeded(enabledSettings())

        assertThat(result.recovered).isTrue()
        assertThat(manager.waitCallCount).isEqualTo(0)
    }

    @Test
    fun `recoverIfNeeded waits for system recovery when wifi reconnect is enabled`() = runTest {
        val manager =
            FakeWorkerNetworkManager(
                connected = false,
                waitResult = true,
            )

        val result = BackgroundNetworkRecoveryCoordinator(manager).recoverIfNeeded(enabledSettings())

        assertThat(result.recovered).isTrue()
        assertThat(result.message).isEqualTo("系统已恢复网络连接")
        assertThat(manager.waitCallCount).isEqualTo(1)
        assertThat(manager.lastWaitTimeoutSeconds).isEqualTo(90)
    }

    @Test
    fun `recoverIfNeeded returns failure when feature is disabled`() = runTest {
        val manager = FakeWorkerNetworkManager(connected = false)

        val result =
            BackgroundNetworkRecoveryCoordinator(manager).recoverIfNeeded(
                enabledSettings().copy(enabled = false),
            )

        assertThat(result.recovered).isFalse()
        assertThat(result.message).isEqualTo("后台 Wi-Fi 重连未开启")
    }

    @Test
    fun `recoverIfNeeded returns failure when no networks are configured`() = runTest {
        val manager = FakeWorkerNetworkManager(connected = false)

        val result =
            BackgroundNetworkRecoveryCoordinator(manager).recoverIfNeeded(
                enabledSettings().copy(primaryNetwork = null, candidateNetworks = emptyList()),
            )

        assertThat(result.recovered).isFalse()
        assertThat(result.message).isEqualTo("未配置可用的 Wi-Fi 重连项")
    }

    @Test
    fun `recoverIfNeeded returns failure summary when waiting times out`() = runTest {
        val manager = FakeWorkerNetworkManager(connected = false, waitResult = false)

        val result = BackgroundNetworkRecoveryCoordinator(manager).recoverIfNeeded(enabledSettings())

        assertThat(result.recovered).isFalse()
        assertThat(result.message).contains("Wuyi-5G")
        assertThat(result.message).contains("Wuyi-2F")
    }

    @Test
    fun `recoverIfNeeded triggers portal authentication when captive portal active and runner succeeds`() = runTest {
        val manager =
            FakeWorkerNetworkManager(
                connected = false,
                captivePortal = true,
            )
        val runner = RecordingCaptivePortalRunner(
            CaptivePortalRecoveryResult.Authenticated(message = "校园网认证成功"),
        )

        val result =
            BackgroundNetworkRecoveryCoordinator(
                networkManager = manager,
                captivePortalRunner = runner,
            ).recoverIfNeeded(enabledSettings())

        assertThat(result.recovered).isTrue()
        assertThat(result.message).contains("校园网认证成功")
        assertThat(runner.invocationCount).isEqualTo(1)
    }

    @Test
    fun `recoverIfNeeded reports portal failure when runner fails and system does not recover`() = runTest {
        val manager =
            FakeWorkerNetworkManager(
                connected = false,
                captivePortal = true,
                waitResult = false,
            )
        val runner = RecordingCaptivePortalRunner(
            CaptivePortalRecoveryResult.Failed(message = "登录失败"),
        )

        val result =
            BackgroundNetworkRecoveryCoordinator(
                networkManager = manager,
                captivePortalRunner = runner,
            ).recoverIfNeeded(enabledSettings())

        assertThat(result.recovered).isFalse()
        assertThat(result.message).contains("校园网认证失败")
        assertThat(runner.invocationCount).isEqualTo(1)
    }

    private fun enabledSettings(): WifiReconnectSettings =
        WifiReconnectSettings(
            enabled = true,
            primaryNetwork = WifiReconnectNetwork(ssid = "Wuyi-5G", password = "pass-1"),
            candidateNetworks = listOf(WifiReconnectNetwork(ssid = "Wuyi-2F", password = "pass-2")),
            recoveryTimeoutSeconds = 90,
            attemptTimeoutSeconds = 12,
        )

    private class FakeWorkerNetworkManager(
        private val connected: Boolean,
        private val waitResult: Boolean = false,
        private val captivePortal: Boolean = false,
    ) : WorkerNetworkManager {
        var waitCallCount = 0
        var lastWaitTimeoutSeconds = 0

        override suspend fun isNetworkAvailable(): Boolean = connected

        override suspend fun isCaptivePortalActive(): Boolean = captivePortal

        override suspend fun waitForNetworkRecovery(timeoutSeconds: Int): Boolean {
            waitCallCount += 1
            lastWaitTimeoutSeconds = timeoutSeconds
            return waitResult
        }
    }

    private class RecordingCaptivePortalRunner(
        private val result: CaptivePortalRecoveryResult,
    ) : CaptivePortalRecoveryRunner {
        var invocationCount = 0
        override suspend fun tryAuthenticate(): CaptivePortalRecoveryResult {
            invocationCount += 1
            return result
        }
    }
}
