package com.wuyi.libraryauto.core.network.auth

data class SessionBundle(
    val cookieHeader: String,
    val userId: String,
)
