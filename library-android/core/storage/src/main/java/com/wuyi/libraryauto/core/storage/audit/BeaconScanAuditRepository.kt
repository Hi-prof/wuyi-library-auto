package com.wuyi.libraryauto.core.storage.audit

import com.wuyi.libraryauto.core.storage.db.BeaconScanAuditDao
import com.wuyi.libraryauto.core.storage.db.BeaconScanAuditEntity

class BeaconScanAuditRepository(
    private val dao: BeaconScanAuditDao,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    suspend fun write(record: BeaconScanAuditWrite): Long {
        val id =
            dao.insert(
                BeaconScanAuditEntity(
                    correlationId = AuditSanitizer.redact(record.correlationId),
                    bookingId = record.bookingId?.let(AuditSanitizer::redact),
                    expectedMinorsCsv = record.expectedMinors.toMinorCsv(limit = 32, sort = true),
                    seenMinorsCsv = record.seenMinors.toMinorCsv(limit = 64, sort = false),
                    matchedMinor = record.matchedMinor.validMinorOrNull(),
                    scanDurationMillis = record.scanDurationMillis.coerceIn(0L, MAX_SCAN_DURATION_MILLIS),
                    terminationReason = AuditSanitizer.redact(record.terminationReason),
                    createdAtEpochSeconds = record.createdAtEpochSeconds ?: nowEpochSeconds(),
                ),
            )
        purgeStale()
        return id
    }

    suspend fun recent(limit: Int = 50): List<BeaconScanAuditEntity> =
        dao.recent(limit = limit.coerceAtLeast(0))

    suspend fun purgeStale(
        maxAgeDays: Int = 30,
        maxRows: Int = 500,
    ) {
        val cutoffEpochSeconds =
            nowEpochSeconds() - maxAgeDays.coerceAtLeast(0).toLong() * SECONDS_PER_DAY
        dao.deleteOlderThan(cutoffEpochSeconds)
        dao.deleteRowsOutsideRecentLimit(maxRows.coerceAtLeast(0))
    }

    private fun List<Int>.toMinorCsv(
        limit: Int,
        sort: Boolean,
    ): String {
        val minors =
            asSequence()
                .mapNotNull { minor -> minor.validMinorOrNull() }
                .distinct()
                .let { sequence -> if (sort) sequence.sorted() else sequence }
                .take(limit)
                .toList()
        return minors.joinToString(",")
    }

    private fun Int?.validMinorOrNull(): Int? = this?.takeIf { minor -> minor in 0..65_535 }

    private companion object {
        private const val SECONDS_PER_DAY = 86_400L
        private const val MAX_SCAN_DURATION_MILLIS = 600_000L
    }
}

data class BeaconScanAuditWrite(
    val correlationId: String,
    val bookingId: String?,
    val expectedMinors: List<Int>,
    val seenMinors: List<Int>,
    val matchedMinor: Int?,
    val scanDurationMillis: Long,
    val terminationReason: String,
    val createdAtEpochSeconds: Long? = null,
)
