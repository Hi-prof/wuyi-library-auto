package com.wuyi.libraryauto.core.storage.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class WifiReconnectNetwork(
    val ssid: String,
    val password: String,
)

data class WifiReconnectSnapshot(
    val enabled: Boolean = false,
    val primaryNetwork: WifiReconnectNetwork? = null,
    val candidateNetworks: List<WifiReconnectNetwork> = emptyList(),
    val recoveryTimeoutSeconds: Int = 90,
    val attemptTimeoutSeconds: Int = 12,
)

class WifiReconnectStore(
    private val preferences: Lazy<SharedPreferences>,
) {
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            createPreferences(context.applicationContext, preferencesName)
        },
    )

    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    fun loadSnapshot(): WifiReconnectSnapshot {
        val primaryNetwork = readNetwork(KEY_PRIMARY_SSID, KEY_PRIMARY_CREDENTIAL)
        val candidateNetworks = readCandidateNetworks()
        return WifiReconnectSnapshot(
            enabled = preferences.value.getBoolean(KEY_ENABLED, false),
            primaryNetwork = primaryNetwork,
            candidateNetworks = candidateNetworks,
            recoveryTimeoutSeconds = preferences.value.getInt(KEY_RECOVERY_TIMEOUT_SECONDS, 90),
            attemptTimeoutSeconds = preferences.value.getInt(KEY_ATTEMPT_TIMEOUT_SECONDS, 12),
        )
    }

    fun saveSnapshot(snapshot: WifiReconnectSnapshot) {
        val sanitizedPrimary = snapshot.primaryNetwork?.sanitize()
        val sanitizedCandidates = snapshot.candidateNetworks.mapNotNull { network ->
            network.sanitize()
        }
        preferences.value.edit()
            .putBoolean(KEY_ENABLED, snapshot.enabled)
            .putString(KEY_PRIMARY_SSID, sanitizedPrimary?.ssid)
            .putString(KEY_PRIMARY_CREDENTIAL, sanitizedPrimary?.password)
            .putString(KEY_CANDIDATE_NETWORKS, encodeNetworks(sanitizedCandidates))
            .putInt(KEY_RECOVERY_TIMEOUT_SECONDS, snapshot.recoveryTimeoutSeconds)
            .putInt(KEY_ATTEMPT_TIMEOUT_SECONDS, snapshot.attemptTimeoutSeconds)
            .apply()
    }

    private fun readNetwork(
        ssidKey: String,
        passwordKey: String,
    ): WifiReconnectNetwork? {
        val ssid = preferences.value.getString(ssidKey, null)?.trim().orEmpty()
        val passphrase = preferences.value.getString(passwordKey, null).orEmpty()
        return WifiReconnectNetwork(ssid, passphrase).sanitize()
    }

    private fun readCandidateNetworks(): List<WifiReconnectNetwork> {
        val encoded = preferences.value.getString(KEY_CANDIDATE_NETWORKS, null).orEmpty()
        if (encoded.isBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    WifiReconnectNetwork(
                        item.optString(JSON_KEY_SSID),
                        item.optString(JSON_KEY_CREDENTIAL),
                    ).sanitize()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeNetworks(networks: List<WifiReconnectNetwork>): String {
        val array = JSONArray()
        networks.forEach { network ->
            array.put(
                JSONObject()
                    .put(JSON_KEY_SSID, network.ssid)
                    .put(JSON_KEY_CREDENTIAL, network.password),
            )
        }
        return array.toString()
    }

    private fun WifiReconnectNetwork.sanitize(): WifiReconnectNetwork? {
        val sanitizedSsid = ssid.trim()
        if (sanitizedSsid.isBlank() || password.isBlank()) {
            return null
        }
        return copy(ssid = sanitizedSsid)
    }

    companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_wifi_reconnect"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRIMARY_SSID = "primary_ssid"
        private const val KEY_PRIMARY_CREDENTIAL = "primary_credential"
        private const val KEY_CANDIDATE_NETWORKS = "candidate_networks"
        private const val KEY_RECOVERY_TIMEOUT_SECONDS = "recovery_timeout_seconds"
        private const val KEY_ATTEMPT_TIMEOUT_SECONDS = "attempt_timeout_seconds"
        private const val JSON_KEY_SSID = "ssid"
        private const val JSON_KEY_CREDENTIAL = "credential"

        private fun createPreferences(
            context: Context,
            preferencesName: String,
        ): SharedPreferences {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                preferencesName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
