package com.wuyi.libraryauto.core.domain.port

interface BeaconScanner {
    suspend fun scan(
        expectedMinors: Set<Int>,
        timeoutMillis: Long,
        correlationId: String,
    ): BeaconScanResult
}

sealed class BeaconScanResult {
    abstract val correlationId: String
    abstract val seenMinors: List<Int>
    abstract val scanDurationMillis: Long

    data class Matched(
        val matchedMinor: Int,
        override val seenMinors: List<Int>,
        override val scanDurationMillis: Long,
        override val correlationId: String,
    ) : BeaconScanResult()

    data class Timeout(
        override val seenMinors: List<Int>,
        override val scanDurationMillis: Long,
        override val correlationId: String,
    ) : BeaconScanResult()

    data class Aborted(
        val reason: String,
        override val seenMinors: List<Int> = emptyList(),
        override val scanDurationMillis: Long = 0L,
        override val correlationId: String,
    ) : BeaconScanResult()

    data class EmptyExpectedMinors(
        override val correlationId: String,
    ) : BeaconScanResult() {
        override val seenMinors: List<Int> = emptyList()
        override val scanDurationMillis: Long = 0L
    }

    data class PermissionDenied(
        override val correlationId: String,
    ) : BeaconScanResult() {
        override val seenMinors: List<Int> = emptyList()
        override val scanDurationMillis: Long = 0L
    }

    data class BluetoothDisabled(
        override val correlationId: String,
    ) : BeaconScanResult() {
        override val seenMinors: List<Int> = emptyList()
        override val scanDurationMillis: Long = 0L
    }

    data class ForegroundServiceUnavailable(
        override val correlationId: String,
    ) : BeaconScanResult() {
        override val seenMinors: List<Int> = emptyList()
        override val scanDurationMillis: Long = 0L
    }
}
