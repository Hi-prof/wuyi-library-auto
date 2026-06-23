package com.wuyi.libraryauto.core.network.auth

import com.wuyi.libraryauto.core.network.http.HttpRequestException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI

/**
 * 登录链路统一错误格式化。后台 Worker 与前台登录入口共用一份文案，
 * 避免出现 `请求失败: HTTP 405 (https://...)` 这种裸 message 直接落进
 * `ExecutionLog` 或 `LoginAuditLog`。
 *
 * 仅依赖 core/network 自带的 `HttpRequestException`，不引入 app 层。
 */
fun Throwable.toLoginErrorMessage(): String =
    when (this) {
        is SocketTimeoutException -> "登录请求超时，请检查网络或学校接口状态后重试。"
        is IOException -> "网络异常，请检查连接后重试。"
        is HttpRequestException ->
            buildLoginRequestErrorMessage(
                url = url,
                statusCode = statusCode,
                responseBody = responseBody,
                defaultMessage = message?.takeIf(String::isNotBlank) ?: "登录失败，请稍后再试。",
            )
        is IllegalArgumentException -> message?.takeIf(String::isNotBlank) ?: "登录失败，请检查学号或密码。"
        else -> message?.takeIf(String::isNotBlank) ?: "登录失败，请稍后再试。"
    }

internal fun buildLoginRequestErrorMessage(
    url: String,
    statusCode: Int,
    responseBody: String,
    defaultMessage: String,
): String {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty().ifBlank { "/" }
    if (isObsoleteHuituEndpoint(url = url, statusCode = statusCode, responseBody = responseBody)) {
        return "当前学校登录接口已失效（$path 返回 HTTP $statusCode），请更新登录入口后重试。"
    }

    for (key in listOf("message", "msg", "error", "MESSAGE", "code")) {
        extractJsonStringField(responseBody = responseBody, key = key)?.let { value ->
            if (value.isNotBlank()) {
                return "登录接口 $path 返回 HTTP $statusCode：$value"
            }
        }
    }

    val rawText = responseBody.trim()
    val detail = rawText.ifBlank { defaultMessage }
    return "登录接口 $path 返回 HTTP $statusCode：$detail"
}

private fun isObsoleteHuituEndpoint(
    url: String,
    statusCode: Int,
    responseBody: String,
): Boolean {
    if (statusCode != 404) {
        return false
    }
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    if (!parsed.host.orEmpty().endsWith(".huitu.zhishulib.com")) {
        return false
    }
    if (
        !parsed.path.orEmpty().startsWith("/User/Index/") &&
        !parsed.path.orEmpty().startsWith("/api/1/") &&
        !parsed.path.orEmpty().startsWith("/Seat/Index/") &&
        !parsed.path.orEmpty().startsWith("/Space/") &&
        !parsed.path.orEmpty().startsWith("/space/")
    ) {
        return false
    }
    return extractJsonStringField(responseBody = responseBody, key = "CODE")
        ?.trim()
        ?.equals("NotFound", ignoreCase = true) == true
}

private fun extractJsonStringField(
    responseBody: String,
    key: String,
): String? =
    Regex(
        pattern = """"${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"])*)"""",
    ).find(responseBody)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace("""\"""", "\"")
        ?.replace("""\\n""", "\n")
        ?.replace("""\\r""", "\r")
        ?.replace("""\\t""", "\t")
        ?.trim()
        ?.takeIf(String::isNotBlank)
