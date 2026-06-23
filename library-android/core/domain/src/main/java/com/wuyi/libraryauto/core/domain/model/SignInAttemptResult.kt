package com.wuyi.libraryauto.core.domain.model

data class SignInAttemptResult(
    val correlationId: String,
    val matchedMinor: Int?,
    val seenMinors: List<Int>,
    val signInError: SignInError?,
    val scanDurationMillis: Long,
)
