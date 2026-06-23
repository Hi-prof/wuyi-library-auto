package com.wuyi.libraryauto.core.ble

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidBleScannerClientTest {
    @Test
    fun `matched expected minor stops scan within timeout`() = runTest {
        var elapsedMillis = 1_000L
        val scanner = RecordingBluetoothLeScanner()
        val timeoutScheduler = RecordingTimeoutScheduler()
        val client = client(
            scanner = scanner,
            timeoutScheduler = timeoutScheduler,
            elapsedRealtime = { elapsedMillis },
        )

        val scan = async(start = CoroutineStart.UNDISPATCHED) {
            client.scan(
                expectedMinors = setOf(58, 77),
                timeoutMillis = 8_000L,
                correlationId = "corr-1",
            )
        }

        elapsedMillis = 1_900L
        scanner.emitMinor(12)
        scanner.emitMinor(58)

        assertThat(scan.await()).isEqualTo(
            BleScanOutcome.Matched(
                matchedMinor = 58,
                seenMinors = listOf(12, 58),
                durationMillis = 900L,
            ),
        )
        assertThat(scanner.stopCount).isEqualTo(1)
        assertThat(timeoutScheduler.cancelCount).isEqualTo(1)
        assertThat(scanner.request?.manufacturerId).isEqualTo(BleScanRequest.APPLE_COMPANY_ID)
        assertThat(scanner.request?.scanMode).isEqualTo(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    @Test
    fun `timeout returns seen minors and stops scan`() = runTest {
        var elapsedMillis = 10L
        val scanner = RecordingBluetoothLeScanner()
        val timeoutScheduler = RecordingTimeoutScheduler()
        val client = client(
            scanner = scanner,
            timeoutScheduler = timeoutScheduler,
            elapsedRealtime = { elapsedMillis },
        )

        val scan = async(start = CoroutineStart.UNDISPATCHED) {
            client.scan(
                expectedMinors = setOf(58),
                timeoutMillis = 8_000L,
                correlationId = "corr-2",
            )
        }

        scanner.emitMinor(12)
        elapsedMillis = 8_010L
        timeoutScheduler.fire()

        assertThat(scan.await()).isEqualTo(
            BleScanOutcome.Timeout(
                seenMinors = listOf(12),
                durationMillis = 8_000L,
            ),
        )
        assertThat(scanner.stopCount).isEqualTo(1)
        assertThat(timeoutScheduler.scheduledDelayMillis).isEqualTo(8_000L)
    }

    @Test
    fun `empty expected minors does not start scan`() = runTest {
        val scanner = RecordingBluetoothLeScanner()

        val outcome = client(scanner = scanner).scan(
            expectedMinors = emptySet(),
            timeoutMillis = 8_000L,
            correlationId = "corr-3",
        )

        assertThat(outcome).isEqualTo(BleScanOutcome.EmptyExpectedMinors)
        assertThat(scanner.startCount).isEqualTo(0)
    }

    @Test
    fun `permission denied returns without starting scan`() = runTest {
        val scanner = RecordingBluetoothLeScanner()

        val outcome = client(
            scanner = scanner,
            hasPermission = false,
        ).scan(
            expectedMinors = setOf(58),
            timeoutMillis = 8_000L,
            correlationId = "corr-4",
        )

        assertThat(outcome).isEqualTo(BleScanOutcome.PermissionDenied)
        assertThat(scanner.startCount).isEqualTo(0)
    }

    @Test
    fun `bluetooth disabled returns without starting scan`() = runTest {
        val scanner = RecordingBluetoothLeScanner()

        val outcome = client(
            scanner = scanner,
            bluetoothEnabled = false,
        ).scan(
            expectedMinors = setOf(58),
            timeoutMillis = 8_000L,
            correlationId = "corr-5",
        )

        assertThat(outcome).isEqualTo(BleScanOutcome.BluetoothDisabled)
        assertThat(scanner.startCount).isEqualTo(0)
    }

    @Test
    fun `cancelling scan stops scanner and timeout`() = runTest {
        val scanner = RecordingBluetoothLeScanner()
        val timeoutScheduler = RecordingTimeoutScheduler()
        val client = client(
            scanner = scanner,
            timeoutScheduler = timeoutScheduler,
        )

        val scan = async(start = CoroutineStart.UNDISPATCHED) {
            client.scan(
                expectedMinors = setOf(58),
                timeoutMillis = 8_000L,
                correlationId = "corr-cancel",
            )
        }

        scan.cancelAndJoin()

        assertThat(scanner.startCount).isEqualTo(1)
        assertThat(scanner.stopCount).isEqualTo(1)
        assertThat(timeoutScheduler.cancelCount).isEqualTo(1)
    }

    private fun client(
        scanner: RecordingBluetoothLeScanner,
        timeoutScheduler: RecordingTimeoutScheduler = RecordingTimeoutScheduler(),
        bluetoothEnabled: Boolean = true,
        hasPermission: Boolean = true,
        elapsedRealtime: () -> Long = { 0L },
    ): AndroidBleScannerClient = AndroidBleScannerClient(
        bluetoothState = BluetoothState { bluetoothEnabled },
        scannerProvider = BluetoothLeScannerProvider { scanner },
        permissionChecker = BleScanPermissionChecker { hasPermission },
        timeoutScheduler = timeoutScheduler,
        elapsedRealtime = elapsedRealtime,
    )

    private class RecordingBluetoothLeScanner : BluetoothLeScannerFacade {
        var request: BleScanRequest? = null
            private set
        var startCount = 0
            private set
        var stopCount = 0
            private set
        private var callback: BleLeScanCallback? = null

        override fun startScan(request: BleScanRequest, callback: BleLeScanCallback) {
            this.request = request
            this.callback = callback
            startCount += 1
        }

        override fun stopScan(callback: BleLeScanCallback) {
            stopCount += 1
        }

        fun emitMinor(minor: Int) {
            callback?.onScanResult(mapOf(BleScanRequest.APPLE_COMPANY_ID to ibeaconPayload(minor)))
        }

        private fun ibeaconPayload(minor: Int): ByteArray =
            ByteArray(22).apply {
                this[0] = 0x02
                this[1] = 0x15
                this[20] = ((minor shr 8) and 0xFF).toByte()
                this[21] = (minor and 0xFF).toByte()
            }
    }

    private class RecordingTimeoutScheduler : BleTimeoutScheduler {
        var scheduledDelayMillis: Long? = null
            private set
        var cancelCount = 0
            private set
        private var action: (() -> Unit)? = null

        override fun schedule(delayMillis: Long, action: () -> Unit): BleTimeoutHandle {
            scheduledDelayMillis = delayMillis
            this.action = action
            return BleTimeoutHandle { cancelCount += 1 }
        }

        fun fire() {
            action?.invoke()
        }
    }
}
