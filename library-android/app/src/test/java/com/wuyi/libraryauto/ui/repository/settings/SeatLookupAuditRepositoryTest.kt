package com.wuyi.libraryauto.ui.repository.settings

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeatLookupAuditRepositoryTest {

    @Test
    fun `recordFailure persists latest manual lookup diagnosis`() {
        val repository = SeatLookupAuditRepository(FakeSharedPreferences())

        repository.recordFailure(
            studentId = "20231121153",
            entryUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            message = "查询页没有返回 data，当前接口返回：com.Message，CODE=所属空间不存在",
            recordedAtEpochSeconds = 1_776_100_300L,
        )

        val actual = repository.loadLatest()
        checkNotNull(actual)
        assertThat(actual.studentId).isEqualTo("20231121153")
        assertThat(actual.outcomeLabel).isEqualTo("查询失败")
        assertThat(actual.message).contains("所属空间不存在")
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
}
