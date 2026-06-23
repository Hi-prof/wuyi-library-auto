package com.wuyi.libraryauto.core.ble

sealed class BleScanOutcome {
    abstract val seenMinors: List<Int>

    data class Matched(
        val matchedMinor: Int,
        override val seenMinors: List<Int>,
        val durationMillis: Long,
    ) : BleScanOutcome()

    data class Timeout(
        override val seenMinors: List<Int>,
        val durationMillis: Long,
    ) : BleScanOutcome()

    data class Aborted(
        val reason: String,
        override val seenMinors: List<Int> = emptyList(),
        val durationMillis: Long = 0L,
    ) : BleScanOutcome()

    data object EmptyExpectedMinors : BleScanOutcome() {
        override val seenMinors: List<Int> = emptyList()
    }

    data object PermissionDenied : BleScanOutcome() {
        override val seenMinors: List<Int> = emptyList()
    }

    data object BluetoothDisabled : BleScanOutcome() {
        override val seenMinors: List<Int> = emptyList()
    }

    data object ForegroundServiceUnavailable : BleScanOutcome() {
        override val seenMinors: List<Int> = emptyList()
    }
}
