package com.wuyi.libraryauto.ui.permission

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BatteryOptimizationPromptStoreTest {

    @Test
    fun `shouldPromptStrong returns true when never prompted before`() {
        val store = BatteryOptimizationPromptStore(FakeSharedPreferences())

        assertThat(store.shouldPromptStrong(nowEpochSeconds = 1_776_100_300L)).isTrue()
    }

    @Test
    fun `shouldPromptStrong returns false within 24 hour throttle window`() {
        val store = BatteryOptimizationPromptStore(FakeSharedPreferences())
        val firstPromptEpochSeconds = 1_776_100_300L
        store.markPrompted(firstPromptEpochSeconds)

        // 距上次提示仅 23 小时 59 分 59 秒，未达 24 小时阈值。
        val almostOneDayLater = firstPromptEpochSeconds + ONE_DAY_SECONDS - 1L

        assertThat(store.shouldPromptStrong(nowEpochSeconds = almostOneDayLater)).isFalse()
    }

    @Test
    fun `shouldPromptStrong returns true exactly at 24 hour boundary`() {
        val store = BatteryOptimizationPromptStore(FakeSharedPreferences())
        val firstPromptEpochSeconds = 1_776_100_300L
        store.markPrompted(firstPromptEpochSeconds)

        val exactlyOneDayLater = firstPromptEpochSeconds + ONE_DAY_SECONDS

        assertThat(store.shouldPromptStrong(nowEpochSeconds = exactlyOneDayLater)).isTrue()
    }

    @Test
    fun `shouldPromptStrong returns true beyond 24 hour throttle window`() {
        val store = BatteryOptimizationPromptStore(FakeSharedPreferences())
        val firstPromptEpochSeconds = 1_776_100_300L
        store.markPrompted(firstPromptEpochSeconds)

        val wellPastOneDay = firstPromptEpochSeconds + ONE_DAY_SECONDS + 60L

        assertThat(store.shouldPromptStrong(nowEpochSeconds = wellPastOneDay)).isTrue()
    }

    @Test
    fun `markPrompted ignores non positive timestamps to keep throttle state intact`() {
        val store = BatteryOptimizationPromptStore(FakeSharedPreferences())
        store.markPrompted(nowEpochSeconds = 1_776_100_300L)

        store.markPrompted(nowEpochSeconds = 0L)
        store.markPrompted(nowEpochSeconds = -10L)

        // 仍应处于 24h 节流内。
        assertThat(store.shouldPromptStrong(nowEpochSeconds = 1_776_100_400L)).isFalse()
    }

    @Test
    fun `clear resets the throttle and unblocks the next strong prompt`() {
        val store = BatteryOptimizationPromptStore(FakeSharedPreferences())
        store.markPrompted(nowEpochSeconds = 1_776_100_300L)

        store.clear()

        assertThat(store.shouldPromptStrong(nowEpochSeconds = 1_776_100_400L)).isTrue()
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key != null) {
                        values[key] = value
                    }
                    return this
                }

                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key != null) {
                        values.remove(key)
                    }
                    return this
                }

                override fun clear(): SharedPreferences.Editor {
                    values.clear()
                    return this
                }

                override fun commit(): Boolean = true

                override fun apply() = Unit

                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

                override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

                override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
                    .also {
                        if (key != null) {
                            values[key] = value
                        }
                    }

                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private companion object {
        private const val ONE_DAY_SECONDS = 24L * 60L * 60L
    }
}
