package com.wuyi.libraryauto.core.runtime.beacon

import com.wuyi.libraryauto.core.ble.BleScanOutcome
import com.wuyi.libraryauto.core.ble.BleScanThrottler
import com.wuyi.libraryauto.core.ble.BleScannerClient
import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogger
import com.wuyi.libraryauto.core.runtime.service.BeaconForegroundServiceController
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BeaconScanCoordinator(
    private val controller: BeaconForegroundServiceController,
    private val scanner: BleScannerClient,
    private val throttler: BleScanThrottler,
    private val auditWriter: BeaconScanAuditWriter,
    private val correlationIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val scanMutex: Mutex = sharedScanMutex,
) {
    suspend fun scanAndMatch(
        bookingId: String,
        expectedMinors: Set<Int>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): BleScanOutcome {
        require(bookingId.isNotBlank()) { "bookingId must not be blank" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }

        val correlationId = correlationIdFactory()
        if (!throttler.tryAcquire(bookingId)) {
            val throttled = BleScanOutcome.Aborted(reason = THROTTLED_REASON)
            LocalDiagnosticLogger.warn(
                source = "BleScan",
                title = "蓝牙扫描被节流",
                detailLines = listOf("correlationId=$correlationId", "expectedMinorCount=${expectedMinors.size}"),
            )
            auditWriter.write(
                throttled.toAuditRecord(
                    correlationId = correlationId,
                    bookingId = bookingId,
                    expectedMinors = expectedMinors,
                    terminationReason = throttled.terminationReason(),
                ),
            )
            return throttled
        }

        val acquisition = controller.acquire()
        val foregroundAcquired = acquisition !is BeaconForegroundServiceController.Acquisition.FailedFallback
        LocalDiagnosticLogger.info(
            source = "BleScan",
            title = "蓝牙扫描开始",
            detailLines =
                listOf(
                    "correlationId=$correlationId",
                    "expectedMinorCount=${expectedMinors.size}",
                    "foregroundAcquired=$foregroundAcquired",
                    "foregroundState=${acquisition.toDiagnosticLabel()}",
                ),
        )
        val outcome =
            try {
                scanMutex.withLock {
                    scanner.scan(
                        expectedMinors = expectedMinors,
                        timeoutMillis = timeoutMillis,
                        correlationId = correlationId,
                    )
                }
            } finally {
                if (foregroundAcquired) {
                    controller.release()
                }
            }
        LocalDiagnosticLogger.info(
            source = "BleScan",
            title = "蓝牙扫描结束",
            detailLines =
                listOf(
                    "correlationId=$correlationId",
                    "outcome=${outcome.terminationReason()}",
                    "seenMinorCount=${outcome.seenMinors.size}",
                    "durationMillis=${outcome.durationMillisOrZero()}",
                ),
        )

        auditWriter.write(
            outcome.toAuditRecord(
                correlationId = correlationId,
                bookingId = bookingId,
                expectedMinors = expectedMinors,
                terminationReason =
                    if (foregroundAcquired) {
                        outcome.terminationReason()
                    } else {
                        FOREGROUND_SERVICE_UNAVAILABLE_REASON
                    },
            ),
        )
        return outcome
    }

    private fun BleScanOutcome.toAuditRecord(
        correlationId: String,
        bookingId: String,
        expectedMinors: Set<Int>,
        terminationReason: String,
    ): BeaconScanAuditRecord =
        BeaconScanAuditRecord(
            correlationId = correlationId,
            bookingId = bookingId,
            expectedMinors = expectedMinors,
            seenMinors = seenMinors,
            matchedMinor = matchedMinorOrNull(),
            scanDurationMillis = durationMillisOrZero(),
            terminationReason = terminationReason,
            createdAtEpochSeconds = nowEpochSeconds(),
        )

    private fun BleScanOutcome.matchedMinorOrNull(): Int? =
        when (this) {
            is BleScanOutcome.Matched -> matchedMinor
            is BleScanOutcome.Aborted,
            BleScanOutcome.BluetoothDisabled,
            BleScanOutcome.EmptyExpectedMinors,
            BleScanOutcome.ForegroundServiceUnavailable,
            BleScanOutcome.PermissionDenied,
            is BleScanOutcome.Timeout,
            -> null
        }

    private fun BleScanOutcome.durationMillisOrZero(): Long =
        when (this) {
            is BleScanOutcome.Aborted -> durationMillis
            is BleScanOutcome.Matched -> durationMillis
            is BleScanOutcome.Timeout -> durationMillis
            BleScanOutcome.BluetoothDisabled,
            BleScanOutcome.EmptyExpectedMinors,
            BleScanOutcome.ForegroundServiceUnavailable,
            BleScanOutcome.PermissionDenied,
            -> 0L
        }

    private fun BleScanOutcome.terminationReason(): String =
        when (this) {
            is BleScanOutcome.Aborted -> "Aborted(reason=$reason)"
            BleScanOutcome.BluetoothDisabled -> "BluetoothDisabled"
            BleScanOutcome.EmptyExpectedMinors -> "EmptyExpectedMinors"
            BleScanOutcome.ForegroundServiceUnavailable -> FOREGROUND_SERVICE_UNAVAILABLE_REASON
            is BleScanOutcome.Matched -> "Matched"
            BleScanOutcome.PermissionDenied -> "PermissionDenied"
            is BleScanOutcome.Timeout -> "Timeout"
        }

    private fun BeaconForegroundServiceController.Acquisition.toDiagnosticLabel(): String =
        when (this) {
            BeaconForegroundServiceController.Acquisition.Started -> "Started"
            BeaconForegroundServiceController.Acquisition.Reused -> "Reused"
            is BeaconForegroundServiceController.Acquisition.FailedFallback -> "FailedFallback(reason=$reason)"
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L
        const val THROTTLED_REASON = "Throttled"
        const val FOREGROUND_SERVICE_UNAVAILABLE_REASON = "ForegroundServiceUnavailable"
        private val sharedScanMutex = Mutex()
    }
}

data class BeaconScanAuditRecord(
    val correlationId: String,
    val bookingId: String,
    val expectedMinors: Set<Int>,
    val seenMinors: List<Int>,
    val matchedMinor: Int?,
    val scanDurationMillis: Long,
    val terminationReason: String,
    val createdAtEpochSeconds: Long,
)

fun interface BeaconScanAuditWriter {
    suspend fun write(record: BeaconScanAuditRecord)
}
