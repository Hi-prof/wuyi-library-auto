package com.wuyi.libraryauto.core.runtime.worker

import com.wuyi.libraryauto.core.storage.audit.SignInAuditRepository
import com.wuyi.libraryauto.core.storage.audit.SignInAuditWrite

internal fun interface SignInAuditWriter {
    suspend fun write(record: SignInAuditRecord)
}

internal class StorageSignInAuditWriter(
    private val writeRecord: suspend (SignInAuditWrite) -> Unit,
) : SignInAuditWriter {
    constructor(repository: SignInAuditRepository) : this(
        writeRecord = { record -> repository.write(record) },
    )

    override suspend fun write(record: SignInAuditRecord) {
        writeRecord(
            SignInAuditWrite(
                correlationId = record.correlationId.orEmpty(),
                bookingId = record.bookingId,
                studentId = record.studentId,
                matchedMinor = record.matchedMinor,
                httpStatusCode = record.httpStatus,
                rawMessage = record.rawMessage,
                signInError = record.signInError?.name,
                triggerSource = record.triggerSource.name,
                createdAtEpochSeconds = record.createdAtEpochSeconds,
            ),
        )
    }
}
