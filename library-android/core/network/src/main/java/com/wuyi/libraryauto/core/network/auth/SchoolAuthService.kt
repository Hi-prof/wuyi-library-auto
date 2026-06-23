package com.wuyi.libraryauto.core.network.auth

import com.wuyi.libraryauto.core.network.http.OkHttpSchoolHttpClient
import com.wuyi.libraryauto.core.network.http.SchoolHttpClient
import java.util.UUID

class SchoolAuthService(
    private val httpClient: SchoolHttpClient = OkHttpSchoolHttpClient(),
    private val repository: SchoolAuthRepository = SchoolAuthRepository(),
    private val installationIdFactory: () -> String = { UUID.randomUUID().toString() },
) {

    fun login(
        loginUrl: String,
        studentId: String,
        password: String,
    ): AuthenticatedSession {
        val origin = repository.resolveOrigin(loginUrl)
        val installationId = installationIdFactory()

        val metadataResponse =
            httpClient.get(
                url = origin + SchoolAuthApi.LOGIN_METADATA_PATH,
                headers = buildHeaders(origin, SchoolAuthApi.LANGUAGE_COOKIE_HEADER),
            )
        val metadataCookies = mergeCookies(languageCookie(), *metadataResponse.cookies.toTypedArray())
        val metadata = repository.parseLoginMetadata(metadataResponse.body)

        val loginResponse =
            httpClient.postJson(
                url = origin + SchoolAuthApi.LOGIN_REQUEST_PATH,
                body =
                    repository.buildLoginRequestBody(
                        studentId = studentId,
                        password = password,
                        metadata = metadata,
                        installationId = installationId,
                    ),
                headers =
                    buildHeaders(
                        origin = origin,
                        cookieHeader = buildCookieHeader(metadataCookies),
                        extraHeaders =
                            mapOf(
                                "Content-Type" to "text/plain",
                                "Origin" to origin,
                            ),
                    ),
            )

        val cookies = mergeCookies(*metadataCookies.toTypedArray(), *loginResponse.cookies.toTypedArray())
        val currentUserJson = repository.normalizeCurrentUserJson(loginResponse.body)
        val session = repository.parseSavedSession(cookies, currentUserJson)
        return AuthenticatedSession(
            session = session,
            cookies = cookies,
            currentUserJson = currentUserJson,
            origin = origin,
            installationId = installationId,
        )
    }

    private fun buildHeaders(
        origin: String,
        cookieHeader: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> =
        linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Cookie" to cookieHeader,
            "Referer" to "$origin/",
        ).apply { putAll(extraHeaders) }

    private fun buildCookieHeader(cookies: Iterable<SchoolAuthRepository.CookieRecord>): String =
        cookies.mapNotNull(SchoolAuthRepository.CookieRecord::asHeaderSegment).joinToString("; ")

    private fun languageCookie(): SchoolAuthRepository.CookieRecord =
        SchoolAuthRepository.CookieRecord(
            name = "web_language",
            value = "zh-CN",
            path = "/",
        )

    private fun mergeCookies(
        vararg cookies: SchoolAuthRepository.CookieRecord,
    ): List<SchoolAuthRepository.CookieRecord> {
        val merged = LinkedHashMap<String, SchoolAuthRepository.CookieRecord>()
        cookies.forEach { cookie ->
            val key = cookie.name.orEmpty().trim()
            if (key.isBlank()) {
                return@forEach
            }
            merged[key] = cookie
        }
        return merged.values.toList()
    }

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    }
}
