package com.wuyi.libraryauto.ui.repository.session

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SchoolAuthRepository
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import org.junit.Test

class PersistentSessionRepositoryTest {

    @Test
    fun `save persists session across repository recreation`() {
        val preferences = FakeSharedPreferences()
        val firstRepository = PersistentSessionRepository(preferences)

        firstRepository.save(
            studentId = "20231121130",
            session = fakeSession("20231121130"),
        )

        val recreatedRepository = PersistentSessionRepository(preferences)

        assertThat(recreatedRepository.activeStudentId()).isEqualTo("20231121130")
        assertThat(recreatedRepository.currentSession("20231121130")).isEqualTo(fakeSession("20231121130"))
        assertThat(recreatedRepository.currentSession()).isEqualTo(fakeSession("20231121130"))
    }

    private fun fakeSession(studentId: String): AuthenticatedSession =
        AuthenticatedSession(
            session = SessionBundle(cookieHeader = "auth=token-$studentId", userId = studentId),
            cookies =
                listOf(
                    SchoolAuthRepository.CookieRecord(
                        name = "auth",
                        value = "token-$studentId",
                        path = "/",
                    ),
                ),
            currentUserJson = """{"id":"$studentId"}""",
            origin = "https://example.com",
            installationId = "install-$studentId",
        )

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
