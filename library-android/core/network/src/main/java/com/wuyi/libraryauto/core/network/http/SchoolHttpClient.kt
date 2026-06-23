package com.wuyi.libraryauto.core.network.http

import com.wuyi.libraryauto.core.network.auth.SchoolAuthRepository

data class HttpResponse(
    val requestUrl: String,
    val statusCode: Int,
    val body: String,
    val cookies: List<SchoolAuthRepository.CookieRecord>,
)

class HttpRequestException(
    val url: String,
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("请求失败: HTTP $statusCode ($url)")

interface SchoolHttpClient {

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    fun postForm(
        url: String,
        formFields: List<Pair<String, String>>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse
}
