package com.wuyi.libraryauto.core.network.captive

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class CampusPortalAuthenticator(
    private val client: OkHttpClient,
    private val cryptoSupport: CampusPortalCryptoSupport = CampusPortalCryptoSupport(),
    private val recoveryEmitter: CampusAuthRecoveryEmitter = CampusAuthRecoveryEmitter.Noop,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private var consecutiveFailures = 0
    private var cooldownUntilMillis = 0L

    suspend fun authenticate(request: CampusPortalAuthRequest): CampusPortalAuthResult =
        mutex.withLock {
            val now = clockMillis()
            if (now < cooldownUntilMillis) {
                return@withLock CampusPortalAuthResult.Skipped(
                    reason = CampusPortalSkipReason.COOLDOWN,
                    message = "校园网认证冷却中，请稍后再试。",
                    retryAtMillis = cooldownUntilMillis,
                )
            }

            val result =
                runCatching { submitLogin(request) }
                    .getOrElse { error ->
                        CampusPortalAuthResult.Failure(error.message ?: "校园网认证失败，请检查网络。")
                    }
            if (result is CampusPortalAuthResult.Success) {
                consecutiveFailures = 0
                cooldownUntilMillis = 0L
                recoveryEmitter.emitCampusAuthRecovery()
                return@withLock result
            }

            consecutiveFailures += 1
            if (consecutiveFailures >= FAILURE_COOLDOWN_THRESHOLD) {
                cooldownUntilMillis = now + COOLDOWN_MILLIS
            }
            result
        }

    private suspend fun submitLogin(request: CampusPortalAuthRequest): CampusPortalAuthResult =
        withContext(Dispatchers.IO) {
            val loginPageHtml = executeGet(request.loginPageUrl)
            val payload =
                cryptoSupport.buildLoginPayload(
                    loginPageHtml = loginPageHtml,
                    username = request.username.trim(),
                    password = request.password,
                )
            if (!payload.isValid) {
                return@withContext CampusPortalAuthResult.Failure(
                    "校园网登录页缺少必要字段：${payload.missingFields.joinToString()}",
                )
            }

            val formBuilder = FormBody.Builder()
            payload.parameters.forEach { (name, value) -> formBuilder.add(name, value) }
            val response =
                client.newCall(
                    Request.Builder()
                        .url(request.loginPageUrl)
                        .post(formBuilder.build())
                        .build(),
                ).execute()
            response.use { bodyResponse ->
                val responseHtml = bodyResponse.body?.string().orEmpty()
                val responseUrl = bodyResponse.request.url.toString()
                if (isLoginSuccess(responseUrl, responseHtml)) {
                    CampusPortalAuthResult.Success("校园网认证成功")
                } else {
                    CampusPortalAuthResult.Failure(extractLoginErrorMessage(responseHtml))
                }
            }
        }

    private fun executeGet(url: String): String =
        client.newCall(
            Request.Builder()
                .url(url)
                .get()
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("校园网登录页返回 HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }

    internal fun isLoginSuccess(
        responseUrl: String,
        responseHtml: String,
    ): Boolean =
        SUCCESS_PAGE_MARKER in responseUrl || SUCCESS_TEXT_MARKER in responseHtml.trim()

    internal fun extractLoginErrorMessage(responseHtml: String): String {
        val patterns =
            listOf(
                Regex(
                    "role=[\"']alert[\"'][^>]*>(.*?)<",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ),
                Regex(
                    "class=[\"'][^\"']*(?:ant-form-explain|ant-message|error)[^\"']*[\"'][^>]*>(.*?)<",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ),
            )
        return patterns
            .asSequence()
            .mapNotNull { pattern -> pattern.find(responseHtml)?.groupValues?.getOrNull(1) }
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?: "校园网认证失败，请检查账号密码或登录页状态"
    }

    companion object {
        private const val SUCCESS_PAGE_MARKER = "success.jsp"
        private const val SUCCESS_TEXT_MARKER = "您已成功连接校园网!"
        private const val FAILURE_COOLDOWN_THRESHOLD = 3
        private const val COOLDOWN_MILLIS = 30L * 60L * 1_000L
    }
}

data class CampusPortalAuthRequest(
    val loginPageUrl: String,
    val username: String,
    val password: String,
)

sealed class CampusPortalAuthResult {
    data class Success(val message: String) : CampusPortalAuthResult()

    data class Failure(val message: String) : CampusPortalAuthResult()

    data class Skipped(
        val reason: CampusPortalSkipReason,
        val message: String,
        val retryAtMillis: Long,
    ) : CampusPortalAuthResult()
}

enum class CampusPortalSkipReason {
    COOLDOWN,
}

fun interface CampusAuthRecoveryEmitter {
    suspend fun emitCampusAuthRecovery()

    object Noop : CampusAuthRecoveryEmitter {
        override suspend fun emitCampusAuthRecovery() = Unit
    }
}
