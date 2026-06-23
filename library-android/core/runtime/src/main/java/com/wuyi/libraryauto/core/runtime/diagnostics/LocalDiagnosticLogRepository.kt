package com.wuyi.libraryauto.core.runtime.diagnostics

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LocalDiagnosticLogEntry(
    val recordedAtEpochMillis: Long,
    val timestampLabel: String,
    val level: String,
    val source: String,
    val title: String,
    val detailLines: List<String>,
)

class LocalDiagnosticLogRepository(
    context: Context,
) {
    private val logFile: File =
        File(context.applicationContext.filesDir, "diagnostics/local-diagnostics.log")

    fun append(
        level: String,
        source: String,
        title: String,
        detailLines: List<String> = emptyList(),
        recordedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            synchronized(lock) {
                logFile.parentFile?.mkdirs()
                val line =
                    listOf(
                        recordedAtEpochMillis.toString(),
                        cleanField(level),
                        cleanField(source),
                        cleanField(title),
                        cleanField(detailLines.joinToString(DETAIL_SEPARATOR)),
                    ).joinToString(FIELD_SEPARATOR)
                val existingLines =
                    if (logFile.exists()) {
                        logFile.readLines()
                    } else {
                        emptyList()
                    }
                val nextLines = (existingLines + line).takeLast(MAX_LINES)
                logFile.writeText(nextLines.joinToString(System.lineSeparator()) + System.lineSeparator())
            }
        }
    }

    fun loadEntries(): List<LocalDiagnosticLogEntry> =
        runCatching {
            synchronized(lock) {
                if (!logFile.exists()) {
                    return@synchronized emptyList()
                }
                logFile.readLines()
                    .mapNotNull(::parseLine)
                    .sortedByDescending(LocalDiagnosticLogEntry::recordedAtEpochMillis)
            }
        }.getOrDefault(emptyList())

    fun clear() {
        runCatching {
            synchronized(lock) {
                if (logFile.exists()) {
                    logFile.delete()
                }
            }
        }
    }

    private fun parseLine(line: String): LocalDiagnosticLogEntry? {
        val parts = line.split(FIELD_SEPARATOR, limit = 5)
        if (parts.size < 5) {
            return null
        }
        val recordedAtEpochMillis = parts[0].toLongOrNull() ?: return null
        val detailLines =
            parts[4]
                .split(DETAIL_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotBlank)
        return LocalDiagnosticLogEntry(
            recordedAtEpochMillis = recordedAtEpochMillis,
            timestampLabel =
                Instant.ofEpochMilli(recordedAtEpochMillis)
                    .atZone(zoneId)
                    .format(timeFormatter),
            level = parts[1],
            source = parts[2],
            title = parts[3],
            detailLines = detailLines,
        )
    }

    private fun cleanField(value: String): String {
        val flattened = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim()
        val withoutKeyedSecrets =
            keyedSensitivePattern.replace(flattened) { match ->
                "${match.groupValues[1]}=[REDACTED]"
            }
        return bearerSensitivePattern
            .replace(withoutKeyedSecrets, "Bearer [REDACTED]")
            .take(MAX_FIELD_CHARS)
    }

    companion object {
        private const val FIELD_SEPARATOR = "\t"
        private const val DETAIL_SEPARATOR = " | "
        private const val MAX_LINES = 400
        private const val MAX_FIELD_CHARS = 2_000
        private val lock = Any()
        private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val keyedSensitivePattern =
            Regex("""(?i)\b(password|passwd|pwd|token|cookie|authorization|session)\s*[:=]\s*(?:bearer\s+)?[^,\s;]+""")
        private val bearerSensitivePattern = Regex("""(?i)\bbearer\s+[^,\s;]+""")
    }
}
