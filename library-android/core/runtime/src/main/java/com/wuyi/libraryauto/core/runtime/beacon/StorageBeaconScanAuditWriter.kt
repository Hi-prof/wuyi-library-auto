package com.wuyi.libraryauto.core.runtime.beacon

import com.wuyi.libraryauto.core.storage.audit.BeaconScanAuditRepository
import com.wuyi.libraryauto.core.storage.audit.BeaconScanAuditWrite

class StorageBeaconScanAuditWriter(
    private val writeRecord: suspend (BeaconScanAuditWrite) -> Unit,
) : BeaconScanAuditWriter {
    constructor(repository: BeaconScanAuditRepository) : this(
        writeRecord = { record -> repository.write(record) },
    )

    override suspend fun write(record: BeaconScanAuditRecord) {
        writeRecord(
            BeaconScanAuditWrite(
                correlationId = record.correlationId,
                bookingId = record.bookingId,
                expectedMinors = record.expectedMinors.toList(),
                seenMinors = record.seenMinors,
                matchedMinor = record.matchedMinor,
                scanDurationMillis = record.scanDurationMillis,
                terminationReason = record.terminationReason,
                createdAtEpochSeconds = record.createdAtEpochSeconds,
            ),
        )
    }
}
