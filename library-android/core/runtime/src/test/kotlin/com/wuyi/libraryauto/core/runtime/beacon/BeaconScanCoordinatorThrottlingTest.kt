package com.wuyi.libraryauto.core.runtime.beacon

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.ble.BleScanOutcome
import com.wuyi.libraryauto.core.ble.BleScanThrottler
import com.wuyi.libraryauto.core.ble.BleScannerClient
import com.wuyi.libraryauto.core.runtime.service.BeaconForegroundServiceController
import com.wuyi.libraryauto.core.runtime.service.BeaconForegroundServiceStartObserver
import com.wuyi.libraryauto.core.runtime.service.BeaconForegroundServiceStarter
import com.wuyi.libraryauto.core.runtime.service.ForegroundServicePermissionChecker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BeaconScanCoordinatorThrottlingTest {

    @Test
    fun `scanAndMatch writes matched audit and releases foreground service`() = runTest {
        val dependencies = CoordinatorDependencies(
            scannerOutcome =
                BleScanOutcome.Matched(
                    matchedMinor = 12,
                    seenMinors = listOf(10, 12),
                    durationMillis = 123L,
                ),
        )

        val outcome =
            dependencies.coordinator.scanAndMatch(
                bookingId = "booking-1",
                expectedMinors = setOf(12),
            )

        assertThat(outcome).isEqualTo(dependencies.scannerOutcome)
        assertThat(dependencies.scanner.callCount).isEqualTo(1)
        assertThat(dependencies.starter.stopCount).isEqualTo(1)
        assertThat(dependencies.auditWriter.records).hasSize(1)
        assertThat(dependencies.auditWriter.records.single())
            .isEqualTo(
                BeaconScanAuditRecord(
                    correlationId = "corr-1",
                    bookingId = "booking-1",
                    expectedMinors = setOf(12),
                    seenMinors = listOf(10, 12),
                    matchedMinor = 12,
                    scanDurationMillis = 123L,
                    terminationReason = "Matched",
                    createdAtEpochSeconds = 1_712_800_000L,
                ),
            )
    }

    @Test
    fun `scanAndMatch writes throttled audit without starting scanner`() = runTest {
        val dependencies =
            CoordinatorDependencies(
                throttler = BleScanThrottler(maxAttempts = 1, nowMillis = { 1_000L }),
            )

        dependencies.coordinator.scanAndMatch(
            bookingId = "booking-1",
            expectedMinors = setOf(12),
        )
        val throttled =
            dependencies.coordinator.scanAndMatch(
                bookingId = "booking-1",
                expectedMinors = setOf(12),
            )

        assertThat(throttled).isEqualTo(BleScanOutcome.Aborted(reason = "Throttled"))
        assertThat(dependencies.scanner.callCount).isEqualTo(1)
        assertThat(dependencies.auditWriter.records).hasSize(2)
        assertThat(dependencies.auditWriter.records.last().terminationReason)
            .isEqualTo("Aborted(reason=Throttled)")
    }

    @Test
    fun `scanAndMatch writes foreground unavailable audit when service acquire falls back`() = runTest {
        val dependencies =
            CoordinatorDependencies(
                permissionChecker = ForegroundServicePermissionChecker {
                    Manifest.permission.POST_NOTIFICATIONS
                },
            )

        val outcome =
            dependencies.coordinator.scanAndMatch(
                bookingId = "booking-1",
                expectedMinors = setOf(12),
            )

        assertThat(outcome).isEqualTo(dependencies.scannerOutcome)
        assertThat(dependencies.scanner.callCount).isEqualTo(1)
        assertThat(dependencies.starter.startCount).isEqualTo(0)
        assertThat(dependencies.starter.stopCount).isEqualTo(0)
        assertThat(dependencies.auditWriter.records.single().terminationReason)
            .isEqualTo("ForegroundServiceUnavailable")
    }

    @Test
    fun `scanAndMatch serializes concurrent scanner calls`() = runTest {
        val scanner = TrackingScanner()
        val dependencies =
            CoordinatorDependencies(
                scannerClient = scanner,
                scanMutex = Mutex(),
            )

        awaitAll(
            async {
                dependencies.coordinator.scanAndMatch(
                    bookingId = "booking-1",
                    expectedMinors = setOf(12),
                )
            },
            async {
                dependencies.coordinator.scanAndMatch(
                    bookingId = "booking-2",
                    expectedMinors = setOf(12),
                )
            },
        )

        assertThat(scanner.callCount).isEqualTo(2)
        assertThat(scanner.maxConcurrentCalls).isEqualTo(1)
        assertThat(dependencies.starter.startCount).isEqualTo(1)
        assertThat(dependencies.starter.stopCount).isEqualTo(1)
    }

    private class CoordinatorDependencies(
        val scannerOutcome: BleScanOutcome = BleScanOutcome.Timeout(
            seenMinors = listOf(1, 2),
            durationMillis = 456L,
        ),
        throttler: BleScanThrottler = BleScanThrottler(),
        permissionChecker: ForegroundServicePermissionChecker = ForegroundServicePermissionChecker { null },
        scannerClient: BleScannerClient? = null,
        scanMutex: Mutex = Mutex(),
    ) {
        private val observer = BeaconForegroundServiceStartObserver()
        val starter = FakeStarter(observer)
        val scanner = FakeScanner(scannerOutcome)
        val auditWriter = RecordingAuditWriter()
        val coordinator =
            BeaconScanCoordinator(
                controller =
                    BeaconForegroundServiceController(
                        context = ApplicationProvider.getApplicationContext(),
                        permissionChecker = permissionChecker,
                        serviceStarter = starter,
                        startObserver = observer,
                    ),
                scanner = scannerClient ?: scanner,
                throttler = throttler,
                auditWriter = auditWriter,
                correlationIdFactory = { "corr-1" },
                nowEpochSeconds = { 1_712_800_000L },
                scanMutex = scanMutex,
            )
    }

    private class FakeStarter(
        private val observer: BeaconForegroundServiceStartObserver,
    ) : BeaconForegroundServiceStarter {
        var startCount = 0
        var stopCount = 0

        override fun start(context: Context) {
            startCount += 1
            observer.notifyStarted()
        }

        override fun stop(context: Context) {
            stopCount += 1
        }
    }

    private class FakeScanner(
        private val outcome: BleScanOutcome,
    ) : BleScannerClient {
        var callCount = 0

        override suspend fun scan(
            expectedMinors: Set<Int>,
            timeoutMillis: Long,
            correlationId: String,
        ): BleScanOutcome {
            callCount += 1
            return outcome
        }
    }

    private class TrackingScanner : BleScannerClient {
        var callCount = 0
        var maxConcurrentCalls = 0
        private var activeCalls = 0

        override suspend fun scan(
            expectedMinors: Set<Int>,
            timeoutMillis: Long,
            correlationId: String,
        ): BleScanOutcome {
            callCount += 1
            activeCalls += 1
            maxConcurrentCalls = maxOf(maxConcurrentCalls, activeCalls)
            return try {
                delay(100L)
                BleScanOutcome.Timeout(
                    seenMinors = emptyList(),
                    durationMillis = 100L,
                )
            } finally {
                activeCalls -= 1
            }
        }
    }

    private class RecordingAuditWriter : BeaconScanAuditWriter {
        val records = mutableListOf<BeaconScanAuditRecord>()

        override suspend fun write(record: BeaconScanAuditRecord) {
            records += record
        }
    }
}
