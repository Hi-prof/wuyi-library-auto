package com.wuyi.libraryauto.core.runtime.worker

import com.wuyi.libraryauto.core.domain.model.SignInError
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource

internal data class SignInAuditRecord(
    val bookingId: String,
    val studentId: String,
    val correlationId: String?,
    val matchedMinor: Int?,
    val seenMinors: List<Int>,
    val scanDurationMillis: Long,
    val httpStatus: Int?,
    val rawMessage: String,
    val signInError: SignInError?,
    val triggerSource: TriggerSource,
    val createdAtEpochSeconds: Long,
)
