package com.wuyi.libraryauto.core.storage.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class SavedAccountStore(
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

    fun save(studentId: String, password: String) {
        val safeStudentId = studentId.trim()
        val safePassword = password.trim()
        require(safeStudentId.isNotEmpty()) { "studentId must not be blank" }
        require(safePassword.isNotEmpty()) { "password must not be blank" }
        val existingAccount = readAll().firstOrNull { it.studentId == safeStudentId }

        val updatedAccounts =
            buildList {
                add(
                    SavedAccount(
                        studentId = safeStudentId,
                        password = safePassword,
                        preferredRoomName = existingAccount?.preferredRoomName.orEmpty(),
                        preferredSeatNumber = existingAccount?.preferredSeatNumber.orEmpty(),
                    ),
                )
                addAll(readAll().filterNot { it.studentId == safeStudentId })
            }
        writeAccounts(updatedAccounts)
    }

    fun remove(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isEmpty()) {
            return
        }
        writeAccounts(readAll().filterNot { it.studentId == safeStudentId })
    }

    fun updatePreferredSeat(
        studentId: String,
        preferredRoomName: String,
        preferredSeatNumber: String,
    ) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isEmpty()) {
            return
        }

        val updatedAccounts =
            readAll().map { account ->
                if (account.studentId != safeStudentId) {
                    account
                } else {
                    account.copy(
                        preferredRoomName = preferredRoomName.trim(),
                        preferredSeatNumber = preferredSeatNumber.trim(),
                    )
                }
            }
        writeAccounts(updatedAccounts)
    }

    fun readAll(): List<SavedAccount> {
        val rawJson =
            runCatching {
                preferences.value.getString(KEY_SAVED_ACCOUNTS, null).orEmpty()
            }.getOrElse {
                preferences.value.edit().remove(KEY_SAVED_ACCOUNTS).apply()
                return emptyList()
            }
        if (rawJson.isBlank()) {
            return emptyList()
        }

        return runCatching {
            val jsonArray = JSONArray(rawJson)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val studentId = item.optString(KEY_STUDENT_ID).trim()
                    val password = item.optString(KEY_PASSWORD).trim()
                    if (studentId.isNotEmpty() && password.isNotEmpty()) {
                        add(
                            SavedAccount(
                                studentId = studentId,
                                password = password,
                                preferredRoomName = item.optString(KEY_PREFERRED_ROOM_NAME).trim(),
                                preferredSeatNumber = item.optString(KEY_PREFERRED_SEAT_NUMBER).trim(),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAccounts(accounts: List<SavedAccount>) {
        val jsonArray =
            JSONArray().apply {
                accounts.forEach { account ->
                    put(
                        JSONObject().apply {
                            put(KEY_STUDENT_ID, account.studentId)
                            put(KEY_PASSWORD, account.password)
                            put(KEY_PREFERRED_ROOM_NAME, account.preferredRoomName)
                            put(KEY_PREFERRED_SEAT_NUMBER, account.preferredSeatNumber)
                        },
                    )
                }
            }

        preferences.value.edit()
            .putString(KEY_SAVED_ACCOUNTS, jsonArray.toString())
            .apply()
    }

    data class SavedAccount(
        val studentId: String,
        val password: String,
        val preferredRoomName: String = "",
        val preferredSeatNumber: String = "",
    )

    companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_saved_accounts"
        private const val KEY_SAVED_ACCOUNTS = "saved_accounts"
        private const val KEY_STUDENT_ID = "student_id"
        private const val KEY_PASSWORD = "password"
        private const val KEY_PREFERRED_ROOM_NAME = "preferred_room_name"
        private const val KEY_PREFERRED_SEAT_NUMBER = "preferred_seat_number"

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
