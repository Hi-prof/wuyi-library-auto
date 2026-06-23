@file:Suppress("DEPRECATION")

package com.wuyi.libraryauto.core.runtime.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class ActiveWifiReconnector internal constructor(
    private val sdkInt: Int,
    private val cooldownStore: WifiSpecifierCooldownStore,
    private val permissionChecker: WifiReconnectPermissionChecker,
    private val specifierConnector: WifiConnectionConnector,
    private val legacyConnector: WifiConnectionConnector,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ActiveWifiReconnectRunner {
    constructor(
        context: Context,
        wifiManager: WifiManager,
        connectivityManager: ConnectivityManager,
        sdkInt: Int = Build.VERSION.SDK_INT,
        cooldownStore: WifiSpecifierCooldownStore = SharedPreferencesWifiSpecifierCooldownStore(context),
    ) : this(
        sdkInt = sdkInt,
        cooldownStore = cooldownStore,
        permissionChecker = AndroidWifiReconnectPermissionChecker(context),
        specifierConnector = AndroidWifiNetworkSpecifierConnector(connectivityManager),
        legacyConnector = AndroidLegacyWifiConnector(wifiManager),
    )

    override suspend fun tryReconnect(settings: WifiReconnectSettings): WifiReconnectResult {
        if (!settings.enabled) {
            return WifiReconnectResult.NoConfiguration
        }

        val networks = orderedNetworks(settings)
        if (networks.isEmpty()) {
            return WifiReconnectResult.NoConfiguration
        }

        val missingPermissions = permissionChecker.missingPermissions(sdkInt).sorted()
        if (missingPermissions.isNotEmpty()) {
            val checkedAt = nowMillis()
            return WifiReconnectResult.Failed(
                reason = WifiReconnectResult.FailureReason.PERMISSION_MISSING,
                attempts =
                    listOf(
                        AttemptRecord(
                            ssid = networks.first().ssid,
                            startedAtMillis = checkedAt,
                            endedAtMillis = checkedAt,
                            outcome = WifiReconnectAttemptOutcome.PERMISSION_MISSING,
                            missingPermissions = missingPermissions,
                        ),
                    ),
            )
        }

        val startedAt = nowMillis()
        val attempts = mutableListOf<AttemptRecord>()
        val result =
            withTimeoutOrNull(OVERALL_TIMEOUT_MILLIS) {
                runAttempts(
                    networks = networks,
                    settings = settings,
                    attempts = attempts,
                    startedAt = startedAt,
                )
            }

        return result
            ?: WifiReconnectResult.Failed(
                reason = WifiReconnectResult.FailureReason.OVERALL_TIMEOUT,
                attempts = attempts.toList(),
            )
    }

    private suspend fun runAttempts(
        networks: List<WifiReconnectNetwork>,
        settings: WifiReconnectSettings,
        attempts: MutableList<AttemptRecord>,
        startedAt: Long,
    ): WifiReconnectResult {
        var attemptedNetwork = false
        var skippedByCooldown = false
        val connector = if (sdkInt >= Build.VERSION_CODES.R) specifierConnector else legacyConnector
        val attemptTimeoutMillis = attemptTimeoutMillis(settings)

        for (network in networks) {
            if (sdkInt >= Build.VERSION_CODES.R && cooldownStore.isCoolingDown(network.ssid, nowMillis())) {
                skippedByCooldown = true
                val skippedAt = nowMillis()
                attempts +=
                    AttemptRecord(
                        ssid = network.ssid,
                        startedAtMillis = skippedAt,
                        endedAtMillis = skippedAt,
                        outcome = WifiReconnectAttemptOutcome.COOLDOWN,
                    )
                continue
            }

            attemptedNetwork = true
            val attemptStartedAt = nowMillis()
            val outcome =
                withTimeoutOrNull(attemptTimeoutMillis) {
                    connector.connect(network)
                } ?: WifiConnectionOutcome.TIMED_OUT
            val attemptEndedAt = nowMillis()
            attempts +=
                AttemptRecord(
                    ssid = network.ssid,
                    startedAtMillis = attemptStartedAt,
                    endedAtMillis = attemptEndedAt,
                    outcome = outcome.toAttemptOutcome(),
                )

            when (outcome) {
                WifiConnectionOutcome.CONNECTED ->
                    return WifiReconnectResult.Connected(
                        ssid = network.ssid,
                        durationMillis = (attemptEndedAt - startedAt).coerceAtLeast(0L),
                        attempts = attempts.toList(),
                    )

                WifiConnectionOutcome.PERMISSION_MISSING ->
                    return WifiReconnectResult.Failed(
                        reason = WifiReconnectResult.FailureReason.PERMISSION_MISSING,
                        attempts = attempts.toList(),
                    )

                WifiConnectionOutcome.USER_REJECTED -> {
                    cooldownStore.markRejected(network.ssid, attemptEndedAt)
                    return WifiReconnectResult.Failed(
                        reason = WifiReconnectResult.FailureReason.USER_REJECTED,
                        attempts = attempts.toList(),
                    )
                }

                WifiConnectionOutcome.FAILED,
                WifiConnectionOutcome.TIMED_OUT,
                -> Unit
            }
        }

        if (!attemptedNetwork && skippedByCooldown) {
            return WifiReconnectResult.Cooldown
        }
        return WifiReconnectResult.Failed(
            reason = WifiReconnectResult.FailureReason.ALL_NETWORKS_FAILED,
            attempts = attempts.toList(),
        )
    }

    private fun orderedNetworks(settings: WifiReconnectSettings): List<WifiReconnectNetwork> =
        buildList {
            settings.primaryNetwork?.let(::add)
            addAll(settings.candidateNetworks)
        }.mapNotNull(::sanitize)
            .distinctBy { network -> network.ssid }
            .take(MAX_NETWORK_ATTEMPTS)

    private fun sanitize(network: WifiReconnectNetwork): WifiReconnectNetwork? {
        val ssid = network.ssid.trim()
        if (ssid.isBlank() || network.password.isBlank()) {
            return null
        }
        return network.copy(ssid = ssid)
    }

    private fun attemptTimeoutMillis(settings: WifiReconnectSettings): Long =
        settings.attemptTimeoutSeconds
            .coerceIn(1, MAX_PER_NETWORK_TIMEOUT_SECONDS)
            .toLong() * 1_000L

    private fun WifiConnectionOutcome.toAttemptOutcome(): WifiReconnectAttemptOutcome =
        when (this) {
            WifiConnectionOutcome.CONNECTED -> WifiReconnectAttemptOutcome.CONNECTED
            WifiConnectionOutcome.FAILED -> WifiReconnectAttemptOutcome.FAILED
            WifiConnectionOutcome.TIMED_OUT -> WifiReconnectAttemptOutcome.TIMED_OUT
            WifiConnectionOutcome.PERMISSION_MISSING -> WifiReconnectAttemptOutcome.PERMISSION_MISSING
            WifiConnectionOutcome.USER_REJECTED -> WifiReconnectAttemptOutcome.USER_REJECTED
        }

    companion object {
        internal const val MAX_NETWORK_ATTEMPTS = 3
        internal const val MAX_PER_NETWORK_TIMEOUT_SECONDS = 15
        internal const val OVERALL_TIMEOUT_MILLIS = 60_000L
    }
}

sealed class WifiReconnectResult {
    data class Connected(
        val ssid: String,
        val durationMillis: Long,
        val attempts: List<AttemptRecord>,
    ) : WifiReconnectResult()

    data class Failed(
        val reason: FailureReason,
        val attempts: List<AttemptRecord>,
    ) : WifiReconnectResult()

    data object NoConfiguration : WifiReconnectResult()

    data object Cooldown : WifiReconnectResult()

    enum class FailureReason {
        OVERALL_TIMEOUT,
        ALL_NETWORKS_FAILED,
        PERMISSION_MISSING,
        USER_REJECTED,
    }
}

data class AttemptRecord(
    val ssid: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val outcome: WifiReconnectAttemptOutcome,
    val missingPermissions: List<String> = emptyList(),
)

enum class WifiReconnectAttemptOutcome {
    CONNECTED,
    FAILED,
    TIMED_OUT,
    COOLDOWN,
    PERMISSION_MISSING,
    USER_REJECTED,
}

interface WifiSpecifierCooldownStore {
    fun isCoolingDown(ssid: String, nowMillis: Long): Boolean

    fun markRejected(ssid: String, nowMillis: Long)
}

class SharedPreferencesWifiSpecifierCooldownStore(
    private val preferences: SharedPreferences,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
) : WifiSpecifierCooldownStore {
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
        cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    ) : this(
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
        cooldownMillis = cooldownMillis,
    )

    override fun isCoolingDown(ssid: String, nowMillis: Long): Boolean {
        val rejectedAt = preferences.getLong(keyFor(ssid), NO_REJECTION)
        if (rejectedAt == NO_REJECTION) {
            return false
        }
        val coolingDown = nowMillis - rejectedAt < cooldownMillis
        if (!coolingDown) {
            preferences.edit().remove(keyFor(ssid)).apply()
        }
        return coolingDown
    }

    override fun markRejected(ssid: String, nowMillis: Long) {
        preferences.edit().putLong(keyFor(ssid), nowMillis).apply()
    }

    private fun keyFor(ssid: String): String = "$KEY_PREFIX$ssid"

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 30L * 60L * 1_000L
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_wifi_specifier_cooldown"
        private const val KEY_PREFIX = "rejected_at:"
        private const val NO_REJECTION = Long.MIN_VALUE
    }
}

internal interface WifiReconnectPermissionChecker {
    fun missingPermissions(sdkInt: Int): Set<String>
}

private class AndroidWifiReconnectPermissionChecker(
    context: Context,
) : WifiReconnectPermissionChecker {
    private val appContext = context.applicationContext

    override fun missingPermissions(sdkInt: Int): Set<String> =
        buildSet {
            addIfMissing(Manifest.permission.CHANGE_WIFI_STATE)
            when {
                sdkInt >= Build.VERSION_CODES.TIRAMISU ->
                    addIfMissing(Manifest.permission.NEARBY_WIFI_DEVICES)

                sdkInt >= Build.VERSION_CODES.Q ->
                    addIfMissing(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

    private fun MutableSet<String>.addIfMissing(permission: String) {
        if (ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED) {
            add(permission)
        }
    }
}

internal interface WifiConnectionConnector {
    suspend fun connect(network: WifiReconnectNetwork): WifiConnectionOutcome
}

internal enum class WifiConnectionOutcome {
    CONNECTED,
    FAILED,
    TIMED_OUT,
    PERMISSION_MISSING,
    USER_REJECTED,
}

private class AndroidWifiNetworkSpecifierConnector(
    private val connectivityManager: ConnectivityManager,
) : WifiConnectionConnector {
    @Volatile
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect(network: WifiReconnectNetwork): WifiConnectionOutcome =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        connectivityManager.bindProcessToNetwork(network)
                        complete(WifiConnectionOutcome.CONNECTED)
                    }

                    override fun onUnavailable() {
                        if (activeCallback === this) {
                            activeCallback = null
                        }
                        unregister(this)
                        complete(WifiConnectionOutcome.USER_REJECTED)
                    }

                    override fun onLost(network: Network) {
                        if (activeCallback === this) {
                            activeCallback = null
                            connectivityManager.bindProcessToNetwork(null)
                        }
                    }

                    private fun complete(outcome: WifiConnectionOutcome) {
                        if (completed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(outcome)
                        }
                    }
                }

            continuation.invokeOnCancellation {
                if (activeCallback === callback) {
                    activeCallback = null
                }
                unregister(callback)
            }

            runCatching {
                releaseActiveRequest()
                activeCallback = callback
                connectivityManager.requestNetwork(buildRequest(network), callback)
            }.onFailure { error ->
                if (activeCallback === callback) {
                    activeCallback = null
                }
                val outcome =
                    if (error is SecurityException) {
                        WifiConnectionOutcome.PERMISSION_MISSING
                    } else {
                        WifiConnectionOutcome.FAILED
                    }
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(outcome)
                }
            }
        }

    private fun buildRequest(network: WifiReconnectNetwork): NetworkRequest {
        val specifier =
            WifiNetworkSpecifier.Builder()
                .setSsid(network.ssid)
                .setWpa2Passphrase(network.password)
                .build()
        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
    }

    private fun releaseActiveRequest() {
        activeCallback?.let(::unregister)
        activeCallback = null
        connectivityManager.bindProcessToNetwork(null)
    }

    private fun unregister(callback: ConnectivityManager.NetworkCallback) {
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}

private class AndroidLegacyWifiConnector(
    private val wifiManager: WifiManager,
) : WifiConnectionConnector {
    @Suppress("DEPRECATION")
    override suspend fun connect(network: WifiReconnectNetwork): WifiConnectionOutcome =
        runCatching {
            val networkId = wifiManager.addNetwork(network.toWifiConfiguration())
            if (networkId == -1) {
                return@runCatching WifiConnectionOutcome.FAILED
            }
            val enabled = wifiManager.enableNetwork(networkId, true)
            val reconnected = wifiManager.reconnect()
            if (enabled && reconnected) {
                WifiConnectionOutcome.CONNECTED
            } else {
                WifiConnectionOutcome.FAILED
            }
        }.getOrElse { error ->
            if (error is SecurityException) {
                WifiConnectionOutcome.PERMISSION_MISSING
            } else {
                WifiConnectionOutcome.FAILED
            }
        }

    @Suppress("DEPRECATION")
    private fun WifiReconnectNetwork.toWifiConfiguration(): WifiConfiguration =
        WifiConfiguration().apply {
            SSID = ssid.quoted()
            preSharedKey = password.quoted()
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            status = WifiConfiguration.Status.ENABLED
        }

    private fun String.quoted(): String = "\"${replace("\"", "\\\"")}\""
}
