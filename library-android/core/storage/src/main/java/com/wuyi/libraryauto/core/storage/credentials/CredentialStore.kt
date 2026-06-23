package com.wuyi.libraryauto.core.storage.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(
    private val preferences: Lazy<SharedPreferences>,
) {
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            createPreferences(context.applicationContext, preferencesName)
        }
    )

    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    fun save(studentId: String, password: String) {
        require(studentId.isNotBlank()) { "studentId must not be blank" }
        require(password.isNotBlank()) { "password must not be blank" }

        preferences.value.edit()
            .putString(KEY_STUDENT_ID, studentId)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun clear() {
        preferences.value.edit()
            .remove(KEY_STUDENT_ID)
            .remove(KEY_PASSWORD)
            .apply()
    }

    fun readStudentId(): String? =
        runCatching {
            preferences.value.getString(KEY_STUDENT_ID, null)
        }.getOrElse {
            clear()
            null
        }

    fun readPassword(): String? =
        runCatching {
            preferences.value.getString(KEY_PASSWORD, null)
        }.getOrElse {
            clear()
            null
        }

    fun read(): Credentials? {
        val studentId = readStudentId()
        val password = readPassword()
        return if (studentId.isNullOrBlank() || password.isNullOrBlank()) {
            null
        } else {
            Credentials(studentId = studentId, password = password)
        }
    }

    data class Credentials(
        val studentId: String,
        val password: String,
    )

    companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_credentials"
        private const val KEY_STUDENT_ID = "student_id"
        private const val KEY_PASSWORD = "password"

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
