package com.wuyi.libraryauto.ui.repository.account

import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AccountExportPayload(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun build(accounts: List<SavedAccountEntry>): AccountExportRequest {
        rejectCredentialFields(EXPORTED_FIELDS)
        val jsonText = buildJson(accounts)
        val timestamp =
            FILE_NAME_FORMATTER.format(clock.instant().atZone(SHANGHAI_ZONE))
        return AccountExportRequest(
            fileName = "wuyi_accounts_$timestamp.json",
            mimeType = MIME_TYPE,
            jsonText = jsonText,
        )
    }

    private fun buildJson(accounts: List<SavedAccountEntry>): String =
        accounts.joinToString(prefix = "[", postfix = "]") { account ->
            buildString {
                append("{")
                appendJsonField(KEY_STUDENT_ID, account.studentId)
                append(",")
                appendJsonField(KEY_PREFERRED_ROOM_NAME, account.preferredRoomName)
                append(",")
                appendJsonField(KEY_PREFERRED_SEAT_NUMBER, account.preferredSeatNumber)
                append(",")
                appendJsonField(KEY_PREFERRED_SEAT_LABEL, account.preferredSeatLabel)
                append("}")
            }
        }

    private fun StringBuilder.appendJsonField(
        key: String,
        value: String,
    ) {
        append(jsonString(key))
        append(":")
        append(jsonString(value))
    }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }

    private fun rejectCredentialFields(fieldNames: Collection<String>) {
        val normalized = fieldNames.map(String::lowercase)
        if (BLACKLISTED_FIELDS.any(normalized::contains)) {
            throw IllegalStateException("导出数据包含敏感字段")
        }
    }

    private companion object {
        private const val MIME_TYPE = "application/json"
        private const val KEY_STUDENT_ID = "studentId"
        private const val KEY_PREFERRED_ROOM_NAME = "preferredRoomName"
        private const val KEY_PREFERRED_SEAT_NUMBER = "preferredSeatNumber"
        private const val KEY_PREFERRED_SEAT_LABEL = "preferredSeatLabel"
        private val EXPORTED_FIELDS =
            listOf(
                KEY_STUDENT_ID,
                KEY_PREFERRED_ROOM_NAME,
                KEY_PREFERRED_SEAT_NUMBER,
                KEY_PREFERRED_SEAT_LABEL,
            )
        private val BLACKLISTED_FIELDS = listOf("password", "cookie", "token")
        private val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private val FILE_NAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

data class AccountExportRequest(
    val fileName: String,
    val mimeType: String,
    val jsonText: String,
)
