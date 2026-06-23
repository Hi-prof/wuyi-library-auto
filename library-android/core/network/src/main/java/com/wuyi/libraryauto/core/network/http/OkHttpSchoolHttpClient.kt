package com.wuyi.libraryauto.core.network.http

import com.wuyi.libraryauto.core.network.auth.SchoolAuthRepository
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets

class OkHttpSchoolHttpClient(
    private val cookieJar: MemoryCookieJar = MemoryCookieJar(),
    // 派生自 [SharedHttpClientCore]，复用 dispatcher / 连接池 / 线程池。每个 OkHttpSchoolHttpClient
    // 实例只是换一个 CookieJar，账号间 cookie 仍然隔离，避免 20 账号下重复创建后台资源。
    // 显式 cookieJar/超时已经在 [SharedHttpClientCore] 里设置好，这里只需注入 cookieJar。
    private val client: OkHttpClient =
        SharedHttpClientCore.client.newBuilder()
            .cookieJar(cookieJar)
            .build(),
) : SchoolHttpClient {

    override fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse = execute(Request.Builder().url(url).headers(headers.toHeaders()).get().build())

    override fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpResponse =
        execute(
            Request.Builder()
                .url(url)
                .headers(headers.toHeaders())
                .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .build()
        )

    override fun postForm(
        url: String,
        formFields: List<Pair<String, String>>,
        headers: Map<String, String>,
    ): HttpResponse {
        val formBody =
            FormBody.Builder(StandardCharsets.UTF_8)
                .apply { formFields.forEach { (name, value) -> add(name, value) } }
                .build()
        return execute(
            Request.Builder()
                .url(url)
                .headers(headers.toHeaders())
                .post(formBody)
                .build()
        )
    }

    private fun execute(request: Request): HttpResponse =
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpRequestException(
                    url = request.url.toString(),
                    statusCode = response.code,
                    responseBody = responseBody,
                )
            }
            HttpResponse(
                requestUrl = request.url.toString(),
                statusCode = response.code,
                body = responseBody,
                cookies = cookieJar.snapshot(request.url),
            )
        }

    private fun Map<String, String>.toHeaders(): okhttp3.Headers =
        okhttp3.Headers.Builder().apply {
            forEach { (name, value) -> add(name, value) }
        }.build()
}

class MemoryCookieJar : CookieJar {
    private val cookiesByKey = linkedMapOf<String, Cookie>()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        synchronized(cookiesByKey) {
            cookies.forEach { cookie ->
                cookiesByKey[cookie.key()] = cookie
            }
            removeExpiredCookies()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(cookiesByKey) {
            removeExpiredCookies()
            cookiesByKey.values.filter { cookie -> cookie.matches(url) }
        }

    fun snapshot(url: HttpUrl): List<SchoolAuthRepository.CookieRecord> =
        loadForRequest(url).map { cookie ->
            SchoolAuthRepository.CookieRecord(
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain,
                path = cookie.path,
            )
        }

    private fun removeExpiredCookies() {
        val now = System.currentTimeMillis()
        cookiesByKey.entries.removeAll { (_, cookie) -> cookie.expiresAt <= now }
    }

    private fun Cookie.key(): String = "${name}|${domain}|${path}"
}
