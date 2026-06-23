package com.wuyi.libraryauto.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidBleScannerClient internal constructor(
    private val bluetoothState: BluetoothState,
    private val scannerProvider: BluetoothLeScannerProvider,
    private val permissionChecker: BleScanPermissionChecker,
    private val timeoutScheduler: BleTimeoutScheduler = HandlerBleTimeoutScheduler(),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : BleScannerClient {
    constructor(context: Context) : this(
        bluetoothState = AndroidBluetoothState(context.applicationContext),
        scannerProvider = AndroidBluetoothScannerProvider(context.applicationContext),
        permissionChecker = AndroidBleScanPermissionChecker(context.applicationContext),
    )

    override suspend fun scan(
        expectedMinors: Set<Int>,
        timeoutMillis: Long,
        correlationId: String,
    ): BleScanOutcome {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(expectedMinors.all { it in MIN_MINOR..MAX_MINOR }) {
            "expectedMinors must be in range $MIN_MINOR..$MAX_MINOR"
        }

        if (expectedMinors.isEmpty()) {
            return BleScanOutcome.EmptyExpectedMinors
        }
        if (!permissionChecker.hasScanPermission()) {
            return BleScanOutcome.PermissionDenied
        }
        if (!bluetoothState.isEnabled()) {
            return BleScanOutcome.BluetoothDisabled
        }

        val scanner = scannerProvider.currentScanner() ?: return BleScanOutcome.BluetoothDisabled
        return suspendCancellableCoroutine { continuation ->
            val seenMinors = linkedSetOf<Int>()
            val startedAt = elapsedRealtime()
            val lock = Any()
            var finished = false
            var timeout: BleTimeoutHandle? = null

            lateinit var callback: BleLeScanCallback

            fun durationMillis(): Long = elapsedRealtime() - startedAt

            fun finish(outcome: BleScanOutcome) {
                val shouldResume =
                    synchronized(lock) {
                        if (finished) {
                            false
                        } else {
                            finished = true
                            true
                        }
                    }
                if (!shouldResume) {
                    return
                }
                timeout?.cancel()
                runCatching { scanner.stopScan(callback) }
                if (continuation.isActive) {
                    continuation.resume(outcome)
                }
            }

            callback = object : BleLeScanCallback {
                override fun onScanResult(manufacturerData: Map<Int, ByteArray>) {
                    val minor = IBeaconParser.extractMinor(manufacturerData) ?: return
                    seenMinors += minor
                    if (minor in expectedMinors) {
                        finish(
                            BleScanOutcome.Matched(
                                matchedMinor = minor,
                                seenMinors = seenMinors.toList(),
                                durationMillis = durationMillis(),
                            ),
                        )
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(
                        BleScanOutcome.Aborted(
                            reason = "scan_failed_$errorCode",
                            seenMinors = seenMinors.toList(),
                            durationMillis = durationMillis(),
                        ),
                    )
                }
            }

            timeout = timeoutScheduler.schedule(timeoutMillis) {
                finish(
                    BleScanOutcome.Timeout(
                        seenMinors = seenMinors.toList(),
                        durationMillis = durationMillis(),
                    ),
                )
            }

            continuation.invokeOnCancellation {
                val shouldStop =
                    synchronized(lock) {
                        if (finished) {
                            false
                        } else {
                            finished = true
                            true
                        }
                }
                if (shouldStop) {
                    timeout.cancel()
                    runCatching { scanner.stopScan(callback) }
                }
            }

            try {
                scanner.startScan(BleScanRequest.iBeacon(), callback)
            } catch (_: SecurityException) {
                finish(BleScanOutcome.PermissionDenied)
            } catch (error: IllegalStateException) {
                finish(
                    BleScanOutcome.Aborted(
                        reason = error.message ?: "scanner_unavailable",
                        seenMinors = seenMinors.toList(),
                        durationMillis = durationMillis(),
                    ),
                )
            }
        }
    }

    private companion object {
        private const val MIN_MINOR = 0
        private const val MAX_MINOR = 65_535
    }
}

internal fun interface BluetoothState {
    fun isEnabled(): Boolean
}

internal fun interface BluetoothLeScannerProvider {
    fun currentScanner(): BluetoothLeScannerFacade?
}

internal fun interface BleScanPermissionChecker {
    fun hasScanPermission(): Boolean
}

internal interface BluetoothLeScannerFacade {
    fun startScan(request: BleScanRequest, callback: BleLeScanCallback)
    fun stopScan(callback: BleLeScanCallback)
}

internal interface BleLeScanCallback {
    fun onScanResult(manufacturerData: Map<Int, ByteArray>)
    fun onScanFailed(errorCode: Int)
}

internal interface BleTimeoutScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): BleTimeoutHandle
}

internal fun interface BleTimeoutHandle {
    fun cancel()
}

internal class BleScanRequest(
    val manufacturerId: Int,
    val manufacturerData: ByteArray,
    val manufacturerDataMask: ByteArray,
    val scanMode: Int,
) {
    companion object {
        fun iBeacon(): BleScanRequest = BleScanRequest(
            manufacturerId = APPLE_COMPANY_ID,
            manufacturerData = byteArrayOf(IBEACON_PREFIX_FIRST, IBEACON_PREFIX_SECOND),
            manufacturerDataMask = byteArrayOf(BYTE_MASK, BYTE_MASK),
            scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
        )

        const val APPLE_COMPANY_ID = 0x004C
        private const val IBEACON_PREFIX_FIRST = 0x02.toByte()
        private const val IBEACON_PREFIX_SECOND = 0x15.toByte()
        private const val BYTE_MASK = 0xFF.toByte()
    }
}

private class HandlerBleTimeoutScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : BleTimeoutScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): BleTimeoutHandle {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return BleTimeoutHandle { handler.removeCallbacks(runnable) }
    }
}

private class AndroidBluetoothState(
    private val context: Context,
) : BluetoothState {
    override fun isEnabled(): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}

private class AndroidBluetoothScannerProvider(
    private val context: Context,
) : BluetoothLeScannerProvider {
    @SuppressLint("MissingPermission")
    override fun currentScanner(): BluetoothLeScannerFacade? =
        context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bluetoothLeScanner
            ?.let(::PlatformBluetoothLeScannerFacade)
}

private class AndroidBleScanPermissionChecker(
    private val context: Context,
) : BleScanPermissionChecker {
    override fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

private class PlatformBluetoothLeScannerFacade(
    private val scanner: BluetoothLeScanner,
) : BluetoothLeScannerFacade {
    private val callbacks = mutableMapOf<BleLeScanCallback, ScanCallback>()

    @SuppressLint("MissingPermission")
    override fun startScan(request: BleScanRequest, callback: BleLeScanCallback) {
        val platformCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.scanRecord?.toManufacturerData(request.manufacturerId)?.let(callback::onScanResult)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    result.scanRecord?.toManufacturerData(request.manufacturerId)?.let(callback::onScanResult)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                callback.onScanFailed(errorCode)
            }
        }
        callbacks[callback] = platformCallback
        scanner.startScan(
            listOf(request.toScanFilter()),
            request.toScanSettings(),
            platformCallback,
        )
    }

    @SuppressLint("MissingPermission")
    override fun stopScan(callback: BleLeScanCallback) {
        callbacks.remove(callback)?.let(scanner::stopScan)
    }

    private fun BleScanRequest.toScanFilter(): ScanFilter =
        ScanFilter.Builder()
            .setManufacturerData(manufacturerId, manufacturerData, manufacturerDataMask)
            .build()

    private fun BleScanRequest.toScanSettings(): ScanSettings =
        ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

    private fun ScanRecord.toManufacturerData(manufacturerId: Int): Map<Int, ByteArray>? {
        val payload = getManufacturerSpecificData(manufacturerId) ?: return null
        return mapOf(manufacturerId to payload)
    }
}
