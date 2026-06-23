package com.wuyi.libraryauto.core.runtime.network

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackgroundNetworkRecoveryCoordinatorActiveReconnectTest {

    @Test
    fun `active reconnect success emits network restored event without fallback wait`() = runTest {
        val clock = MutableClock()
        val manager = FakeWorkerNetworkManager(connected = false, waitResults = listOf(false))
        val activeReconnect =
            FakeActiveWifiReconnectRunner(
                WifiReconnectResult.Connected(
                    ssid = "Wuyi-5G",
                    durationMillis = 120L,
                    attempts = emptyList(),
                ),
            )
        val emittedSources = mutableListOf<TriggerSource>()
        val coordinator =
            coordinator(
                manager = manager,
                activeReconnect = activeReconnect,
                eventSink = emittedSources,
                clock = clock,
            )

        val first = coordinator.recoverIfNeeded(enabledSettings())
        clock.advance(BackgroundNetworkRecoveryCoordinator.ACTIVE_RECONNECT_UNAVAILABLE_DELAY_MILLIS)
        val recovered = coordinator.recoverIfNeeded(enabledSettings())

        assertThat(first.recovered).isFalse()
        assertThat(recovered.recovered).isTrue()
        assertThat(recovered.message).isEqualTo("主动 Wi-Fi 重连成功：Wuyi-5G")
        assertThat(activeReconnect.callCount).isEqualTo(1)
        assertThat(manager.waitCallCount).isEqualTo(1)
        assertThat(emittedSources).containsExactly(TriggerSource.NetworkRestored)
    }

    @Test
    fun `active reconnect failure falls back to wait for network recovery`() = runTest {
        val clock = MutableClock()
        val manager = FakeWorkerNetworkManager(connected = false, waitResults = listOf(false, true))
        val activeReconnect =
            FakeActiveWifiReconnectRunner(
                WifiReconnectResult.Failed(
                    reason = WifiReconnectResult.FailureReason.ALL_NETWORKS_FAILED,
                    attempts = emptyList(),
                ),
            )
        val coordinator =
            coordinator(
                manager = manager,
                activeReconnect = activeReconnect,
                clock = clock,
            )

        coordinator.recoverIfNeeded(enabledSettings())
        clock.advance(BackgroundNetworkRecoveryCoordinator.ACTIVE_RECONNECT_UNAVAILABLE_DELAY_MILLIS)
        val recovered = coordinator.recoverIfNeeded(enabledSettings())

        assertThat(recovered.recovered).isTrue()
        assertThat(recovered.message).isEqualTo("系统已恢复网络连接")
        assertThat(activeReconnect.callCount).isEqualTo(1)
        assertThat(manager.waitCallCount).isEqualTo(2)
    }

    @Test
    fun `ble scan activity delays reconnect and system wait`() = runTest {
        val manager = FakeWorkerNetworkManager(connected = false, waitResults = listOf(true))
        val activeReconnect =
            FakeActiveWifiReconnectRunner(
                WifiReconnectResult.Connected(
                    ssid = "Wuyi-5G",
                    durationMillis = 120L,
                    attempts = emptyList(),
                ),
            )
        val coordinator =
            coordinator(
                manager = manager,
                activeReconnect = activeReconnect,
                bleScanActive = true,
            )

        val result = coordinator.recoverIfNeeded(enabledSettings())

        assertThat(result.recovered).isFalse()
        assertThat(result.message).isEqualTo("BLE 扫描进行中，延后网络恢复")
        assertThat(activeReconnect.callCount).isEqualTo(0)
        assertThat(manager.waitCallCount).isEqualTo(0)
    }

    private fun coordinator(
        manager: FakeWorkerNetworkManager,
        activeReconnect: FakeActiveWifiReconnectRunner,
        eventSink: MutableList<TriggerSource> = mutableListOf(),
        clock: MutableClock = MutableClock(),
        bleScanActive: Boolean = false,
    ): BackgroundNetworkRecoveryCoordinator =
        BackgroundNetworkRecoveryCoordinator(
            networkManager = manager,
            activeWifiReconnector = activeReconnect,
            eventEmitter = NetworkRecoveryEventEmitter { source -> eventSink += source },
            bleScanActivityMonitor = BleScanActivityMonitor { bleScanActive },
            nowMillis = clock::now,
        )

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
        waitResults: List<Boolean>,
    ) : WorkerNetworkManager {
        var waitCallCount = 0
        private val waitResults = ArrayDeque(waitResults)

        override suspend fun isNetworkAvailable(): Boolean = connected

        override suspend fun waitForNetworkRecovery(timeoutSeconds: Int): Boolean {
            waitCallCount += 1
            return waitResults.removeFirstOrNull() ?: false
        }
    }

    private class FakeActiveWifiReconnectRunner(
        private val result: WifiReconnectResult,
    ) : ActiveWifiReconnectRunner {
        var callCount = 0

        override suspend fun tryReconnect(settings: WifiReconnectSettings): WifiReconnectResult {
            callCount += 1
            return result
        }
    }

    private class MutableClock(
        private var nowMillis: Long = 1_000L,
    ) {
        fun now(): Long = nowMillis

        fun advance(millis: Long) {
            nowMillis += millis
        }
    }
}
