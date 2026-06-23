package com.wuyi.libraryauto.core.storage.audit

internal object AuditSanitizer {
    private const val REDACTED = "[REDACTED]"

    private val jsonSensitiveValue =
        Regex(
            pattern = "(?i)(\"(?:cookie|token|authorization|password)\"\\s*:\\s*\")[^\"]*(\")",
        )
    private val authorizationValue =
        Regex(
            pattern = "(?i)\\b(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+",
        )
    private val assignedSensitiveValue =
        Regex(
            pattern = "(?i)\\b(cookie|token|password)\\b(\\s*[:=]\\s*)[^\\s,;]+",
        )
    private val configJsonValue =
        Regex(
            pattern = "(?is)(config\\.json\\s*[:=]\\s*)\\{.*?\\}",
        )

    fun redact(value: String): String =
        value
            .replace(jsonSensitiveValue) { match ->
                "${match.groupValues[1]}$REDACTED${match.groupValues[2]}"
            }
            .replace(authorizationValue) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
            }
            .replace(assignedSensitiveValue) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
            }
            .replace(configJsonValue) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
}
