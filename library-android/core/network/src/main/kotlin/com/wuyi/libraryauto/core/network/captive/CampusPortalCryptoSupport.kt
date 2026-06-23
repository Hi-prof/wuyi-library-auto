package com.wuyi.libraryauto.core.network.captive

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CampusPortalCryptoSupport {
    fun buildLoginPayload(
        loginPageHtml: String,
        username: String,
        password: String,
    ): CampusPortalLoginPayload {
        val flowKey = extractElementValue(loginPageHtml, FIELD_FLOW_KEY)
        val cryptoKey = extractElementValue(loginPageHtml, FIELD_CRYPTO_KEY)
        val missingFields =
            listOfNotNull(
                FIELD_FLOW_KEY.takeIf { flowKey.isBlank() },
                FIELD_CRYPTO_KEY.takeIf { cryptoKey.isBlank() },
            )
        if (missingFields.isNotEmpty()) {
            return CampusPortalLoginPayload(parameters = emptyMap(), missingFields = missingFields)
        }

        val loginType = extractElementValue(loginPageHtml, FIELD_LOGIN_TYPE).ifBlank { DEFAULT_LOGIN_TYPE }
        val iv = extractElementValue(loginPageHtml, FIELD_CRYPTO_IV).ifBlank { null }
        val parameters =
            linkedMapOf(
                "username" to username,
                "type" to loginType,
                "_eventId" to "submit",
                "geolocation" to "",
                "execution" to flowKey,
                "captcha_code" to "",
                "croypto" to cryptoKey,
                "password" to encryptValue(cryptoKey = cryptoKey, value = password, iv = iv),
                "captcha_payload" to encryptValue(
                    cryptoKey = cryptoKey,
                    value = DEFAULT_CAPTCHA_PAYLOAD,
                    iv = iv,
                ),
            )
        return CampusPortalLoginPayload(parameters = parameters, missingFields = emptyList())
    }

    fun encryptValue(
        cryptoKey: String,
        value: String,
        iv: String? = null,
    ): String {
        val keyBytes =
            runCatching { Base64.getDecoder().decode(cryptoKey) }
                .getOrElse { throw IllegalArgumentException("登录页加密参数无效", it) }
        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            "登录页加密参数长度无效"
        }
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher =
            if (iv.isNullOrBlank()) {
                Cipher.getInstance("AES/ECB/PKCS5Padding").also { cipher ->
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                }
            } else {
                val ivBytes =
                    runCatching { Base64.getDecoder().decode(iv) }
                        .getOrElse { throw IllegalArgumentException("登录页加密 IV 无效", it) }
                require(ivBytes.size == AES_BLOCK_BYTES) { "登录页加密 IV 长度无效" }
                Cipher.getInstance("AES/CBC/PKCS5Padding").also { cipher ->
                    cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(ivBytes))
                }
            }
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun extractElementValue(
        loginPageHtml: String,
        elementId: String,
    ): String {
        val escapedId = Regex.escape(elementId)
        val patterns =
            listOf(
                Regex(
                    "id=[\"']$escapedId[\"'][^>]*value=[\"']([^\"']+)[\"']",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ),
                Regex(
                    "id=[\"']$escapedId[\"'][^>]*>(.*?)<",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ),
            )
        return patterns
            .asSequence()
            .mapNotNull { pattern -> pattern.find(loginPageHtml)?.groupValues?.getOrNull(1) }
            .map(::decodeBasicHtmlEntities)
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    }

    private fun decodeBasicHtmlEntities(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private companion object {
        private const val FIELD_FLOW_KEY = "login-page-flowkey"
        private const val FIELD_CRYPTO_KEY = "login-croypto"
        private const val FIELD_CRYPTO_IV = "login-iv"
        private const val FIELD_LOGIN_TYPE = "current-login-type"
        private const val DEFAULT_LOGIN_TYPE = "UsernamePassword"
        private const val DEFAULT_CAPTCHA_PAYLOAD = "{}"
        private const val AES_BLOCK_BYTES = 16
    }
}

data class CampusPortalLoginPayload(
    val parameters: Map<String, String>,
    val missingFields: List<String>,
) {
    val isValid: Boolean = missingFields.isEmpty()
}
