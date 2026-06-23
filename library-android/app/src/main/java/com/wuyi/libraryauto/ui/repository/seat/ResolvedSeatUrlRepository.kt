package com.wuyi.libraryauto.ui.repository.seat

import android.content.Context
import android.content.SharedPreferences

interface ResolvedSeatUrlRepository {
    fun load(
        studentId: String,
        entryUrl: String,
    ): String?

    fun save(
        studentId: String,
        entryUrl: String,
        searchApiUrl: String,
    )

    fun remove(
        studentId: String,
        entryUrl: String,
    )
}

class PersistentResolvedSeatUrlRepository(
    private val preferences: Lazy<SharedPreferences>,
) : ResolvedSeatUrlRepository {
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        },
    )

    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    override fun load(
        studentId: String,
        entryUrl: String,
    ): String? = preferences.value.getString(buildKey(studentId, entryUrl), null)?.trim()?.takeIf(String::isNotBlank)

    override fun save(
        studentId: String,
        entryUrl: String,
        searchApiUrl: String,
    ) {
        val key = buildKey(studentId, entryUrl) ?: return
        val value = searchApiUrl.trim()
        if (value.isBlank()) {
            return
        }
        preferences.value.edit()
            .putString(key, value)
            .apply()
    }

    override fun remove(
        studentId: String,
        entryUrl: String,
    ) {
        val key = buildKey(studentId, entryUrl) ?: return
        preferences.value.edit()
            .remove(key)
            .apply()
    }

    private fun buildKey(
        studentId: String,
        entryUrl: String,
    ): String? {
        val safeStudentId = studentId.trim()
        val safeEntryUrl = entryUrl.trim()
        if (safeStudentId.isBlank() || safeEntryUrl.isBlank()) {
            return null
        }
        return "$safeStudentId|$safeEntryUrl"
    }

    private companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_resolved_seat_urls"
    }
}
