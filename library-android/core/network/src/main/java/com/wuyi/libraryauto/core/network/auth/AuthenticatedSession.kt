package com.wuyi.libraryauto.core.network.auth

data class AuthenticatedSession(
    val session: SessionBundle,
    val cookies: List<SchoolAuthRepository.CookieRecord>,
    val currentUserJson: String,
    val origin: String,
    val installationId: String,
)
