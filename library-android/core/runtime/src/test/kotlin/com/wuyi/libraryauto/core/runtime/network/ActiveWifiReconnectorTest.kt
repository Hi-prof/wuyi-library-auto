package com.wuyi.libraryauto.core.runtime.network

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActiveWifiReconnectorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
    }

    @Test
    fun `api 30 and above uses wifi specifier path and returns connected`() = runTest {
        val specifierConnector = RecordingConnector(WifiConnectionOutcome.CONNECTED)
        val legacyConnector = RecordingConnector(WifiConnectionOutcome.CONNECTED)
        val reconnector =
            newReconnector(
                sdkInt = 30,
                specifierConnector = specifierConnector,
                legacyConnector = legacyConnector,
            )

        val result = reconnector.tryReconnect(settings(primarySsid = "LibraryWiFi"))

        assertThat(result).isInstanceOf(WifiReconnectResult.Connected::class.java)
        result as WifiReconnectResult.Connected
        assertThat(result.ssid).isEqualTo("LibraryWiFi")
        assertThat(result.attempts.map { attempt -> attempt.outcome })
            .containsExactly(WifiReconnectAttemptOutcome.CONNECTED)
        assertThat(specifierConnector.ssids).containsExactly("LibraryWiFi")
        assertThat(legacyConnector.ssids).isEmpty()
    }

    @Test
    fun `api 30 user rejection stores thirty minute cooldown for same ssid`() = runTest {
        val clock = MutableClock(nowMillis = 1_000L)
        val specifierConnector =
            RecordingConnector(
                WifiConnectionOutcome.USER_REJECTED,
                WifiConnectionOutcome.CONNECTED,
            )
        val reconnector =
            newReconnector(
                sdkInt = 30,
                clock = clock,
                specifierConnector = specifierConnector,
            )

        val rejected = reconnector.tryReconnect(settings(primarySsid = "DormWiFi"))

        assertThat(rejected).isInstanceOf(WifiReconnectResult.Failed::class.java)
        rejected as WifiReconnectResult.Failed
        assertThat(rejected.reason).isEqualTo(WifiReconnectResult.FailureReason.USER_REJECTED)
        assertThat(rejected.attempts.map { attempt -> attempt.outcome })
            .containsExactly(WifiReconnectAttemptOutcome.USER_REJECTED)

        clock.advance(SharedPreferencesWifiSpecifierCooldownStore.DEFAULT_COOLDOWN_MILLIS - 1L)
        val coolingDown = reconnector.tryReconnect(settings(primarySsid = "DormWiFi"))

        assertThat(coolingDown).isEqualTo(WifiReconnectResult.Cooldown)
        assertThat(specifierConnector.ssids).containsExactly("DormWiFi")

        clock.advance(2L)
        val connected = reconnector.tryReconnect(settings(primarySsid = "DormWiFi"))

        assertThat(connected).isInstanceOf(WifiReconnectResult.Connected::class.java)
        assertThat(specifierConnector.ssids).containsExactly("DormWiFi", "DormWiFi").inOrder()
    }

    @Test
    fun `api 29 and below uses legacy enable network path`() = runTest {
        val specifierConnector = RecordingConnector(WifiConnectionOutcome.CONNECTED)
        val legacyConnector = RecordingConnector(WifiConnectionOutcome.CONNECTED)
        val reconnector =
            newReconnector(
                sdkInt = 29,
                specifierConnector = specifierConnector,
                legacyConnector = legacyConnector,
            )

        val result = reconnector.tryReconnect(settings(primarySsid = "LegacyWiFi"))

        assertThat(result).isInstanceOf(WifiReconnectResult.Connected::class.java)
        assertThat(legacyConnector.ssids).containsExactly("LegacyWiFi")
        assertThat(specifierConnector.ssids).isEmpty()
    }

    @Test
    fun `permission missing returns permission missing without connecting`() = runTest {
        val specifierConnector = RecordingConnector(WifiConnectionOutcome.CONNECTED)
        val legacyConnector = RecordingConnector(WifiConnectionOutcome.CONNECTED)
        val reconnector =
            newReconnector(
                sdkInt = 30,
                missingPermissions = setOf(Manifest.permission.CHANGE_WIFI_STATE),
                specifierConnector = specifierConnector,
                legacyConnector = legacyConnector,
            )

        val result = reconnector.tryReconnect(settings(primarySsid = "LibraryWiFi"))

        assertThat(result).isInstanceOf(WifiReconnectResult.Failed::class.java)
        result as WifiReconnectResult.Failed
        assertThat(result.reason).isEqualTo(WifiReconnectResult.FailureReason.PERMISSION_MISSING)
        assertThat(result.attempts.single().outcome)
            .isEqualTo(WifiReconnectAttemptOutcome.PERMISSION_MISSING)
        assertThat(result.attempts.single().missingPermissions)
            .containsExactly(Manifest.permission.CHANGE_WIFI_STATE)
        assertThat(specifierConnector.ssids).isEmpty()
        assertThat(legacyConnector.ssids).isEmpty()
    }

    private fun newReconnector(
        sdkInt: Int,
        missingPermissions: Set<String> = emptySet(),
        clock: MutableClock = MutableClock(),
        specifierConnector: RecordingConnector = RecordingConnector(WifiConnectionOutcome.FAILED),
        legacyConnector: RecordingConnector = RecordingConnector(WifiConnectionOutcome.FAILED),
    ): ActiveWifiReconnector =
        ActiveWifiReconnector(
            sdkInt = sdkInt,
            cooldownStore =
                SharedPreferencesWifiSpecifierCooldownStore(
                    preferences = preferences(),
                    cooldownMillis = SharedPreferencesWifiSpecifierCooldownStore.DEFAULT_COOLDOWN_MILLIS,
                ),
            permissionChecker = FakePermissionChecker(missingPermissions),
            specifierConnector = specifierConnector,
            legacyConnector = legacyConnector,
            nowMillis = clock::now,
        )

    private fun settings(
        primarySsid: String,
        candidates: List<WifiReconnectNetwork> = emptyList(),
    ): WifiReconnectSettings =
        WifiReconnectSettings(
            enabled = true,
            primaryNetwork = WifiReconnectNetwork(primarySsid, "password123"),
            candidateNetworks = candidates,
            recoveryTimeoutSeconds = 90,
            attemptTimeoutSeconds = 15,
        )

    private fun preferences() =
        context.getSharedPreferences("active-wifi-reconnector-test", Context.MODE_PRIVATE)

    private class FakePermissionChecker(
        private val missingPermissions: Set<String>,
    ) : WifiReconnectPermissionChecker {
        override fun missingPermissions(sdkInt: Int): Set<String> = missingPermissions
    }

    private class RecordingConnector(
        vararg outcomes: WifiConnectionOutcome,
    ) : WifiConnectionConnector {
        val ssids = mutableListOf<String>()
        private val outcomes = ArrayDeque(outcomes.toList())

        override suspend fun connect(network: WifiReconnectNetwork): WifiConnectionOutcome {
            ssids += network.ssid
            return outcomes.removeFirstOrNull() ?: WifiConnectionOutcome.FAILED
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
