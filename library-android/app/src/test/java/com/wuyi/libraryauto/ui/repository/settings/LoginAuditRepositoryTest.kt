package com.wuyi.libraryauto.ui.repository.settings

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoginAuditRepositoryTest {

    @Test
    fun `recordAttempt persists pending login diagnosis`() {
        val repository = LoginAuditRepository(FakeSharedPreferences())

        repository.recordAttempt(
            studentId = "20231121130",
            loginUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            recordedAtEpochSeconds = 1_775_000_000L,
        )

        val actual = repository.loadLatest()
        checkNotNull(actual)
        assertThat(actual.studentId).isEqualTo("20231121130")
        assertThat(actual.outcomeLabel).isEqualTo("开始认证")
        assertThat(actual.message).contains("等待学校接口返回")
    }

    @Test
    fun `recordFailure persists latest login diagnosis`() {
        val repository = LoginAuditRepository(FakeSharedPreferences())

        repository.recordFailure(
            studentId = "20231121130",
            loginUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            message = "当前学校登录接口已失效（/api/1/login 返回 HTTP 404），请更新登录入口后重试。",
            recordedAtEpochSeconds = 1_776_000_000L,
        )

        val actual = repository.loadLatest()
        checkNotNull(actual)
        assertThat(actual.studentId).isEqualTo("20231121130")
        assertThat(actual.outcomeLabel).isEqualTo("登录失败")
        assertThat(actual.message).contains("HTTP 404")
        assertThat(actual.loginUrl).contains("wuyiu.huitu.zhishulib.com")
    }

    @Test
    fun `recordSuccess replaces previous failure`() {
        val repository = LoginAuditRepository(FakeSharedPreferences())
        repository.recordFailure(
            studentId = "20231121130",
            loginUrl = "https://example.com",
            message = "旧错误",
            recordedAtEpochSeconds = 1L,
        )

        repository.recordSuccess(
            studentId = "20231121130",
            loginUrl = "https://example.com",
            recordedAtEpochSeconds = 2L,
        )

        val actual = repository.loadLatest()
        checkNotNull(actual)
        assertThat(actual.outcomeLabel).isEqualTo("登录成功")
        assertThat(actual.message).isEqualTo("登录成功，会话已保存。")
    }

    @Test
    fun `clear removes saved diagnosis`() {
        val repository = LoginAuditRepository(FakeSharedPreferences())
        repository.recordFailure(
            studentId = "20231121130",
            loginUrl = "https://example.com",
            message = "旧错误",
            recordedAtEpochSeconds = 1L,
        )

        repository.clear()

        assertThat(repository.loadLatest()).isNull()
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
