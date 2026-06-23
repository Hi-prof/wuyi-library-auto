package com.wuyi.libraryauto.ui.repository.settings

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SeatLookupAuditRecord(
    val timestampLabel: String,
    val studentId: String,
    val outcomeLabel: String,
    val entryUrl: String,
    val message: String,
)

class SeatLookupAuditRepository(
    private val preferences: Lazy<SharedPreferences>,
) {
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        },
    )

    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    fun recordSuccess(
        studentId: String,
        entryUrl: String,
        message: String,
        recordedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        writeRecord(
            studentId = studentId,
            outcomeLabel = "查询成功",
            entryUrl = entryUrl,
            message = message,
            recordedAtEpochSeconds = recordedAtEpochSeconds,
        )
    }

    fun recordFailure(
        studentId: String,
        entryUrl: String,
        message: String,
        recordedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        writeRecord(
            studentId = studentId,
            outcomeLabel = "查询失败",
            entryUrl = entryUrl,
            message = message,
            recordedAtEpochSeconds = recordedAtEpochSeconds,
        )
    }

    fun loadLatest(): SeatLookupAuditRecord? {
        val recordedAtEpochSeconds = preferences.value.getLong(KEY_RECORDED_AT_EPOCH_SECONDS, 0L)
        if (recordedAtEpochSeconds <= 0L) {
            return null
        }
        return SeatLookupAuditRecord(
            timestampLabel =
                Instant.ofEpochSecond(recordedAtEpochSeconds)
                    .atZone(zoneId)
                    .format(timeFormatter),
            studentId = preferences.value.getString(KEY_STUDENT_ID, null).orEmpty().trim(),
            outcomeLabel = preferences.value.getString(KEY_OUTCOME_LABEL, null).orEmpty().trim(),
            entryUrl = preferences.value.getString(KEY_ENTRY_URL, null).orEmpty().trim(),
            message = preferences.value.getString(KEY_MESSAGE, null).orEmpty().trim(),
        )
    }

    fun clear() {
        preferences.value.edit()
            .remove(KEY_RECORDED_AT_EPOCH_SECONDS)
            .remove(KEY_STUDENT_ID)
            .remove(KEY_OUTCOME_LABEL)
            .remove(KEY_ENTRY_URL)
            .remove(KEY_MESSAGE)
            .apply()
    }

    private fun writeRecord(
        studentId: String,
        outcomeLabel: String,
        entryUrl: String,
        message: String,
        recordedAtEpochSeconds: Long,
    ) {
        preferences.value.edit()
            .putLong(KEY_RECORDED_AT_EPOCH_SECONDS, recordedAtEpochSeconds)
            .putString(KEY_STUDENT_ID, studentId.trim())
            .putString(KEY_OUTCOME_LABEL, outcomeLabel.trim())
            .putString(KEY_ENTRY_URL, entryUrl.trim())
            .putString(KEY_MESSAGE, message.trim())
            .apply()
    }

    private companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_seat_lookup_audit"
        private const val KEY_RECORDED_AT_EPOCH_SECONDS = "recorded_at_epoch_seconds"
        private const val KEY_STUDENT_ID = "student_id"
        private const val KEY_OUTCOME_LABEL = "outcome_label"
        private const val KEY_ENTRY_URL = "entry_url"
        private const val KEY_MESSAGE = "message"
        private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
