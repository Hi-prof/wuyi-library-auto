package com.wuyi.libraryauto.core.storage.audit

import com.wuyi.libraryauto.core.storage.db.SignInAuditDao
import com.wuyi.libraryauto.core.storage.db.SignInAuditEntity
import com.wuyi.libraryauto.core.storage.db.SignInErrorCount

class SignInAuditRepository(
    private val dao: SignInAuditDao,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    suspend fun write(record: SignInAuditWrite): Long {
        val id =
            dao.insert(
                SignInAuditEntity(
                    correlationId = AuditSanitizer.redact(record.correlationId),
                    bookingId = AuditSanitizer.redact(record.bookingId),
                    studentId = AuditSanitizer.redact(record.studentId),
                    matchedMinor = record.matchedMinor.validMinorOrNull(),
                    httpStatusCode = record.httpStatusCode,
                    rawMessage = AuditSanitizer.redact(record.rawMessage).take(MAX_RAW_MESSAGE_LENGTH),
                    signInError = record.signInError?.let(AuditSanitizer::redact),
                    triggerSource = AuditSanitizer.redact(record.triggerSource),
                    createdAtEpochSeconds = record.createdAtEpochSeconds ?: nowEpochSeconds(),
                ),
            )
        purgeStale()
        return id
    }

    suspend fun recent(limit: Int = 50): List<SignInAuditEntity> =
        dao.recent(limit = limit.coerceAtLeast(0))

    suspend fun countByErrorWithin(
        rangeStart: Long,
        rangeEnd: Long,
    ): List<SignInErrorCount> =
        dao.countByErrorWithin(rangeStart = rangeStart, rangeEnd = rangeEnd)

    suspend fun purgeStale(
        maxAgeDays: Int = 30,
        maxRows: Int = 500,
    ) {
        val cutoffEpochSeconds =
            nowEpochSeconds() - maxAgeDays.coerceAtLeast(0).toLong() * SECONDS_PER_DAY
        dao.deleteOlderThan(cutoffEpochSeconds)
        dao.deleteRowsOutsideRecentLimit(maxRows.coerceAtLeast(0))
    }

    private fun Int?.validMinorOrNull(): Int? = this?.takeIf { minor -> minor in 0..65_535 }

    private companion object {
        private const val SECONDS_PER_DAY = 86_400L
        private const val MAX_RAW_MESSAGE_LENGTH = 1_024
    }
}

data class SignInAuditWrite(
    val correlationId: String,
    val bookingId: String,
    val studentId: String,
    val matchedMinor: Int?,
    val httpStatusCode: Int?,
    val rawMessage: String,
    val signInError: String?,
    val triggerSource: String,
    val createdAtEpochSeconds: Long? = null,
)
