package com.wuyi.libraryauto.ui.repository.settings

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeatStatusAuditRepositoryTest {

    @Test
    fun `recordFailure persists latest seat status diagnosis`() {
        val repository = SeatStatusAuditRepository(FakeSharedPreferences())

        repository.recordFailure(
            studentId = "20231121130",
            requestUrl = "https://wuyiu.huitu.zhishulib.com/Seat/Index/myBookingList?LAB_JSON=1",
            message = "预约状态接口返回了非 JSON 内容，可能登录态已失效，请点击“刷新认证”。",
            recordedAtEpochSeconds = 1_776_100_000L,
        )

        val actual = repository.loadLatest()
        checkNotNull(actual)
        assertThat(actual.studentId).isEqualTo("20231121130")
        assertThat(actual.outcomeLabel).isEqualTo("读取失败")
        assertThat(actual.requestUrl).contains("myBookingList")
        assertThat(actual.message).contains("非 JSON")
    }

    @Test
    fun `recordSuccess replaces previous failure`() {
        val repository = SeatStatusAuditRepository(FakeSharedPreferences())
        repository.recordFailure(
            studentId = "20231121130",
            requestUrl = "https://example.com/Seat/Index/myBookingList?LAB_JSON=1",
            message = "旧错误",
            recordedAtEpochSeconds = 1L,
        )

        repository.recordSuccess(
            studentId = "20231121130",
            requestUrl = "https://example.com/Seat/Index/myBookingList?LAB_JSON=1",
            message = "暂无预约",
            recordedAtEpochSeconds = 2L,
        )

        val actual = repository.loadLatest()
        checkNotNull(actual)
        assertThat(actual.outcomeLabel).isEqualTo("读取成功")
        assertThat(actual.message).isEqualTo("暂无预约")
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
