package com.wuyi.libraryauto.core.storage.network

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WifiReconnectStoreTest {

    @Test
    fun `saveSnapshot persists primary and candidate wifi credentials in order`() {
        val preferences = FakeSharedPreferences()
        val store = WifiReconnectStore(preferences)

        store.saveSnapshot(
            WifiReconnectSnapshot(
                enabled = true,
                primaryNetwork = WifiReconnectNetwork(ssid = "Wuyi-5G", password = "pass-1"),
                candidateNetworks =
                    listOf(
                        WifiReconnectNetwork(ssid = "Wuyi-2F", password = "pass-2"),
                        WifiReconnectNetwork(ssid = "Wuyi-3F", password = "pass-3"),
                    ),
                recoveryTimeoutSeconds = 90,
                attemptTimeoutSeconds = 12,
            ),
        )

        assertThat(store.loadSnapshot()).isEqualTo(
            WifiReconnectSnapshot(
                enabled = true,
                primaryNetwork = WifiReconnectNetwork(ssid = "Wuyi-5G", password = "pass-1"),
                candidateNetworks =
                    listOf(
                        WifiReconnectNetwork(ssid = "Wuyi-2F", password = "pass-2"),
                        WifiReconnectNetwork(ssid = "Wuyi-3F", password = "pass-3"),
                    ),
                recoveryTimeoutSeconds = 90,
                attemptTimeoutSeconds = 12,
            ),
        )
    }

    @Test
    fun `saveSnapshot drops blank networks and trims ssid`() {
        val preferences = FakeSharedPreferences()
        val store = WifiReconnectStore(preferences)

        store.saveSnapshot(
            WifiReconnectSnapshot(
                enabled = true,
                primaryNetwork = WifiReconnectNetwork(ssid = "  Wuyi-5G  ", password = "pass-1"),
                candidateNetworks =
                    listOf(
                        WifiReconnectNetwork(ssid = " ", password = "pass-2"),
                        WifiReconnectNetwork(ssid = "Wuyi-3F", password = " "),
                        WifiReconnectNetwork(ssid = "  Wuyi-2F  ", password = "pass-2"),
                    ),
            ),
        )

        assertThat(store.loadSnapshot()).isEqualTo(
            WifiReconnectSnapshot(
                enabled = true,
                primaryNetwork = WifiReconnectNetwork(ssid = "Wuyi-5G", password = "pass-1"),
                candidateNetworks = listOf(WifiReconnectNetwork(ssid = "Wuyi-2F", password = "pass-2")),
                recoveryTimeoutSeconds = 90,
                attemptTimeoutSeconds = 12,
            ),
        )
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var shouldClear = false

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = values
        }

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = null
        }

        override fun clear(): SharedPreferences.Editor = apply {
            shouldClear = true
            pending.clear()
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (shouldClear) {
                values.clear()
            }
            pending.forEach { (key, value) ->
                if (value == null) {
                    values.remove(key)
                } else {
                    values[key] = value
                }
            }
        }
    }
}
