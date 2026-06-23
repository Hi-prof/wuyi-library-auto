package com.wuyi.libraryauto.core.network.auth

interface SchoolAuthApi {

    fun fetchLoginMetadata(origin: String): String

    fun submitLoginRequest(origin: String, requestBody: String): String

    companion object {
        const val LOGIN_APPLICATION_ID = "lab4"
        const val LOGIN_METADATA_PATH = "/User/Index/login?LAB_JSON=1"
        const val LOGIN_REQUEST_PATH = "/api/1/login"
        const val LANGUAGE_COOKIE_HEADER = "web_language=zh-CN"
    }
}
