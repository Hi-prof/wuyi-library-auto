package com.wuyi.libraryauto.ui.repository.settings

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SeatStatusAuditRecord(
    val timestampLabel: String,
    val studentId: String,
    val outcomeLabel: String,
    val message: String,
    val requestUrl: String,
)

class SeatStatusAuditRepository(
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
        requestUrl: String,
        message: String,
        recordedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        writeRecord(
            studentId = studentId,
            outcomeLabel = "读取成功",
            message = message,
            requestUrl = requestUrl,
            recordedAtEpochSeconds = recordedAtEpochSeconds,
        )
    }

    fun recordFailure(
        studentId: String,
        requestUrl: String,
        message: String,
        recordedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        writeRecord(
            studentId = studentId,
            outcomeLabel = "读取失败",
            message = message,
            requestUrl = requestUrl,
            recordedAtEpochSeconds = recordedAtEpochSeconds,
        )
    }

    fun loadLatest(): SeatStatusAuditRecord? {
        val recordedAtEpochSeconds = preferences.value.getLong(KEY_RECORDED_AT_EPOCH_SECONDS, 0L)
        if (recordedAtEpochSeconds <= 0L) {
            return null
        }
        return SeatStatusAuditRecord(
            timestampLabel =
                Instant.ofEpochSecond(recordedAtEpochSeconds)
                    .atZone(zoneId)
                    .format(timeFormatter),
            studentId = preferences.value.getString(KEY_STUDENT_ID, null).orEmpty().trim(),
            outcomeLabel = preferences.value.getString(KEY_OUTCOME_LABEL, null).orEmpty().trim(),
            message = preferences.value.getString(KEY_MESSAGE, null).orEmpty().trim(),
            requestUrl = preferences.value.getString(KEY_REQUEST_URL, null).orEmpty().trim(),
        )
    }

    fun clear() {
        preferences.value.edit()
            .remove(KEY_RECORDED_AT_EPOCH_SECONDS)
            .remove(KEY_STUDENT_ID)
            .remove(KEY_OUTCOME_LABEL)
            .remove(KEY_MESSAGE)
            .remove(KEY_REQUEST_URL)
            .apply()
    }

    private fun writeRecord(
        studentId: String,
        outcomeLabel: String,
        message: String,
        requestUrl: String,
        recordedAtEpochSeconds: Long,
    ) {
        preferences.value.edit()
            .putLong(KEY_RECORDED_AT_EPOCH_SECONDS, recordedAtEpochSeconds)
            .putString(KEY_STUDENT_ID, studentId.trim())
            .putString(KEY_OUTCOME_LABEL, outcomeLabel.trim())
            .putString(KEY_MESSAGE, message.trim())
            .putString(KEY_REQUEST_URL, requestUrl.trim())
            .apply()
    }

    private companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_seat_status_audit"
        private const val KEY_RECORDED_AT_EPOCH_SECONDS = "recorded_at_epoch_seconds"
        private const val KEY_STUDENT_ID = "student_id"
        private const val KEY_OUTCOME_LABEL = "outcome_label"
        private const val KEY_MESSAGE = "message"
        private const val KEY_REQUEST_URL = "request_url"
        private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
