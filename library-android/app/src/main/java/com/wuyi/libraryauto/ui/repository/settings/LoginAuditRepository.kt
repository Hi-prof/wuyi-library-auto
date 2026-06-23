package com.wuyi.libraryauto.ui.repository.settings

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LoginAuditRecord(
    val timestampLabel: String,
    val studentId: String,
    val outcomeLabel: String,
    val message: String,
    val loginUrl: String,
)

class LoginAuditRepository(
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

    fun recordAttempt(
        studentId: String,
        loginUrl: String,
        recordedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        writeRecord(
            studentId = studentId,
            outcomeLabel = "开始认证",
            message = "已发起登录请求，等待学校接口返回。",
            loginUrl = loginUrl,
            recordedAtEpochSeconds = recordedAtEpochSeconds,
        )
    }

    fun recordSuccess(
        studentId: String,
        loginUrl: String,
        recordedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        writeRecord(
            studentId = studentId,
            outcomeLabel = "登录成功",
            message = "登录成功，会话已保存。",
            loginUrl = loginUrl,
            recordedAtEpochSeconds = recordedAtEpochSeconds,
        )
    }

    fun recordFailure(
        studentId: String,
        loginUrl: String,
        message: String,
        recordedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        writeRecord(
            studentId = studentId,
            outcomeLabel = "登录失败",
            message = message,
            loginUrl = loginUrl,
            recordedAtEpochSeconds = recordedAtEpochSeconds,
        )
    }

    fun loadLatest(): LoginAuditRecord? {
        val recordedAtEpochSeconds = preferences.value.getLong(KEY_RECORDED_AT_EPOCH_SECONDS, 0L)
        if (recordedAtEpochSeconds <= 0L) {
            return null
        }
        return LoginAuditRecord(
            timestampLabel =
                Instant.ofEpochSecond(recordedAtEpochSeconds)
                    .atZone(zoneId)
                    .format(timeFormatter),
            studentId = preferences.value.getString(KEY_STUDENT_ID, null).orEmpty().trim(),
            outcomeLabel = preferences.value.getString(KEY_OUTCOME_LABEL, null).orEmpty().trim(),
            message = preferences.value.getString(KEY_MESSAGE, null).orEmpty().trim(),
            loginUrl = preferences.value.getString(KEY_LOGIN_URL, null).orEmpty().trim(),
        )
    }

    fun clear() {
        preferences.value.edit()
            .remove(KEY_RECORDED_AT_EPOCH_SECONDS)
            .remove(KEY_STUDENT_ID)
            .remove(KEY_OUTCOME_LABEL)
            .remove(KEY_MESSAGE)
            .remove(KEY_LOGIN_URL)
            .apply()
    }

    private fun writeRecord(
        studentId: String,
        outcomeLabel: String,
        message: String,
        loginUrl: String,
        recordedAtEpochSeconds: Long,
    ) {
        preferences.value.edit()
            .putLong(KEY_RECORDED_AT_EPOCH_SECONDS, recordedAtEpochSeconds)
            .putString(KEY_STUDENT_ID, studentId.trim())
            .putString(KEY_OUTCOME_LABEL, outcomeLabel.trim())
            .putString(KEY_MESSAGE, message.trim())
            .putString(KEY_LOGIN_URL, loginUrl.trim())
            .apply()
    }

    private companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_login_audit"
        private const val KEY_RECORDED_AT_EPOCH_SECONDS = "recorded_at_epoch_seconds"
        private const val KEY_STUDENT_ID = "student_id"
        private const val KEY_OUTCOME_LABEL = "outcome_label"
        private const val KEY_MESSAGE = "message"
        private const val KEY_LOGIN_URL = "login_url"
        private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
