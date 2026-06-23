package com.wuyi.libraryauto.core.network.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI

class SchoolAuthRepository(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun parseSavedSession(
        cookies: Iterable<CookieRecord>,
        currentUserJson: String,
    ): SessionBundle {
        val cookieHeader =
            cookies
                .mapNotNull(CookieRecord::asHeaderSegment)
                .joinToString(separator = "; ")
                .takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("登录态中未找到可用 Cookie，请重新执行 save-login")

        return SessionBundle(
            cookieHeader = cookieHeader,
            userId = parseUserId(currentUserJson),
        )
    }

    fun extractCurrentUserJson(origins: Iterable<OriginRecord>): String =
        origins
            .asSequence()
            .flatMap { origin -> origin.localStorage.asSequence() }
            .firstOrNull { record -> record.name.orEmpty().endsWith("/currentUser") }
            ?.value
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("登录态中未找到当前用户信息，请重新执行 save-login")

    fun parseLoginMetadata(responseBody: String): LoginMetadata {
        val payload = parseObject(responseBody)
        val content = payload.requiredObject("content")
        val rawData = content.requiredObject("data")
        val orgId =
            content
                .requiredObject("itemHeader")
                .requiredObject("defaultData")
                .requiredPrimitive("custom_value")
                .content
                .trim()

        val code = rawData.requiredPrimitive("code").content.trim()
        val secret = rawData.requiredPrimitive("str").content.trim()
        if (code.isBlank() || secret.isBlank() || orgId.isBlank()) {
            throw IllegalArgumentException("登录页未返回完整认证参数，请稍后再试")
        }
        return LoginMetadata(code = code, secret = secret, orgId = orgId)
    }

    fun buildLoginRequestBody(
        studentId: String,
        password: String,
        metadata: LoginMetadata,
        installationId: String,
    ): String =
        buildJsonObject {
            put("login_name", studentId)
            put("password", password)
            put("ui_type", "com.Raw")
            put("code", metadata.code)
            put("str", metadata.secret)
            put("org_id", metadata.orgId)
            put("_ApplicationId", SchoolAuthApi.LOGIN_APPLICATION_ID)
            put("_JavaScriptKey", SchoolAuthApi.LOGIN_APPLICATION_ID)
            put("_ClientVersion", "js_xxx")
            put("_InstallationId", installationId)
            // 现网前端会显式携带一个假的 SessionToken，这里保持同样字段，避免后端按不同兼容分支处理登录请求。
            put("_SessionToken", "fake")
        }.toString()

    fun normalizeCurrentUserJson(responseBody: String): String {
        val payload = parseObject(responseBody)
        val id =
            when (val element = payload["id"]) {
                null, JsonNull -> ""
                is JsonPrimitive -> element.content
                else -> ""
            }.trim()

        if (id.isEmpty()) {
            throw IllegalArgumentException(extractErrorMessage(payload, "登录失败，请检查学号或密码"))
        }
        return normalizeCurrentUserPayload(payload).toString()
    }

    fun resolveOrigin(loginUrl: String): String {
        val parsed = URI(loginUrl)
        require(!parsed.scheme.isNullOrBlank() && !parsed.rawAuthority.isNullOrBlank()) {
            "无效的登录地址：$loginUrl"
        }
        return URI(parsed.scheme, parsed.rawAuthority, null, null, null).toString()
    }

    private fun parseUserId(currentUserJson: String): String {
        val payload =
            runCatching { parseObject(currentUserJson) }.getOrElse {
                throw IllegalArgumentException("登录态中未找到当前用户信息，请重新执行 save-login", it)
            }

        val id =
            when (val element = payload["id"]) {
                null, JsonNull -> ""
                is JsonPrimitive -> element.content
                else -> ""
            }.trim()

        if (id.isEmpty()) {
            throw IllegalArgumentException("登录态中未找到用户 ID，请重新执行 save-login")
        }
        return id
    }

    private fun normalizeCurrentUserPayload(payload: JsonObject): JsonObject =
        buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }

            if ("accessToken" in payload && "access_token" !in payload) {
                put("access_token", payload.getValue("accessToken"))
            }
            if ("objectId" !in payload) {
                payload["id"]?.let { put("objectId", idToString(it)) }
            }
            if ("sessionToken" !in payload) {
                put("sessionToken", "fake")
            }
            if ("className" !in payload) {
                put("className", "_User")
            }
        }

    private fun extractErrorMessage(
        payload: JsonObject,
        defaultMessage: String,
    ): String {
        val keys = listOf("message", "msg", "error", "code")
        return keys
            .firstNotNullOfOrNull { key ->
                payload[key]
                    ?.let { element -> (element as? JsonPrimitive)?.content }
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            } ?: defaultMessage
    }

    private fun idToString(element: JsonElement): String =
        (element as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun parseObject(rawJson: String): JsonObject = json.parseToJsonElement(rawJson).jsonObject

    private fun JsonObject.requiredObject(key: String): JsonObject =
        get(key)?.jsonObject ?: throw IllegalArgumentException("登录页未返回完整认证参数，请稍后再试")

    private fun JsonObject.requiredPrimitive(key: String): JsonPrimitive =
        get(key) as? JsonPrimitive ?: throw IllegalArgumentException("登录页未返回完整认证参数，请稍后再试")

    data class CookieRecord(
        val name: String?,
        val value: String?,
        val domain: String? = null,
        val path: String? = null,
    ) {
        fun asHeaderSegment(): String? {
            val safeName = name.orEmpty().trim()
            val safeValue = value.orEmpty().trim()
            if (safeName.isEmpty() || safeValue.isEmpty()) {
                return null
            }
            return "$safeName=$safeValue"
        }
    }

    data class OriginRecord(
        val localStorage: List<LocalStorageRecord>,
    )

    data class LocalStorageRecord(
        val name: String?,
        val value: String?,
    )

    data class LoginMetadata(
        val code: String,
        val secret: String,
        val orgId: String,
    )
}
