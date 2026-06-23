package com.wuyi.libraryauto.core.ble

interface BleScannerClient {
    suspend fun scan(
        expectedMinors: Set<Int>,
        timeoutMillis: Long,
        correlationId: String,
    ): BleScanOutcome
}
