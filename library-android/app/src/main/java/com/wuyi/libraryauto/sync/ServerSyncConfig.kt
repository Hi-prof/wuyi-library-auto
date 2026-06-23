package com.wuyi.libraryauto.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 任务 12.13：Server_Sync_Config 持久化。
 *
 * 使用 EncryptedSharedPreferences 安全存储服务端同步配置：
 * - `base_url`：服务端 API 的基础 URL（可空，缺失时进入 Local_Only_Mode）。
 * - `bearer_token`：客户端鉴权令牌（可空，缺失时进入 Local_Only_Mode）。
 * - `verify_tls`：是否校验 TLS 证书（默认 true）。
 * - `upload_enabled`：同步上行开关（默认 false）。
 *
 * 设计契约：
 * - 缺失任一关键字段（base_url 或 bearer_token）时进入 Local_Only_Mode，不抛异常、不阻塞启动。
 * - 升级期保留既有 Room 数据与既有偏好不动；新字段缺省时写默认值。
 * - EncryptedSharedPreferences 初始化失败时（极罕见的 KeyStore 损坏等）回退到内存空值，
 *   保证客户端正常启动进入 Local_Only_Mode。
 *
 * _Requirements: 11.6, 12.6, 14.2, 15.2_
 */
class ServerSyncConfig(
    private val preferences: Lazy<SharedPreferences?>,
) {
    /**
     * 生产环境构造：延迟初始化 EncryptedSharedPreferences，不阻塞 Application.onCreate。
     */
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            createPreferencesSafe(context.applicationContext, preferencesName)
        }
    )

    /**
     * 测试便利构造：直接注入 SharedPreferences 实例。
     */
    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    // ─────────────────────────────────────────────────
    // 读取字段
    // ─────────────────────────────────────────────────

    /** 服务端 API 基础 URL，缺失时返回 null。 */
    val baseUrl: String?
        get() = prefs?.getString(KEY_BASE_URL, null)?.takeIf(String::isNotBlank)

    /** Bearer Token，缺失时返回 null。 */
    val bearerToken: String?
        get() = prefs?.getString(KEY_BEARER_TOKEN, null)?.takeIf(String::isNotBlank)

    /** 是否校验 TLS 证书，默认 true。 */
    val verifyTls: Boolean
        get() = prefs?.getBoolean(KEY_VERIFY_TLS, DEFAULT_VERIFY_TLS) ?: DEFAULT_VERIFY_TLS

    /** 同步上行开关，默认 false。 */
    val uploadEnabled: Boolean
        get() = prefs?.getBoolean(KEY_UPLOAD_ENABLED, DEFAULT_UPLOAD_ENABLED) ?: DEFAULT_UPLOAD_ENABLED

    // ─────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────

    /**
     * 判断是否已完成最低配置（base_url 与 bearer_token 均非空）。
     * 缺失时返回 false，不抛异常。
     */
    fun isConfigured(): Boolean =
        baseUrl != null && bearerToken != null

    /**
     * 判断是否允许上行上传（isConfigured() 且 upload_enabled 为 true）。
     * 用于 AutomationTaskUploadWorker 与 BlacklistReporter 的双开关守卫。
     */
    fun isUploadEnabled(): Boolean =
        isConfigured() && uploadEnabled

    /**
     * 判断是否处于 Local_Only_Mode（未配置或上行开关关闭）。
     * Local_Only_Mode 下客户端不发起任何同步、上行、拉黑事件上报请求。
     */
    fun isLocalOnly(): Boolean =
        !isConfigured() || !uploadEnabled

    // ─────────────────────────────────────────────────
    // 写入字段
    // ─────────────────────────────────────────────────

    /** 保存服务端基础 URL。传入 null 或空串清除该字段。 */
    fun saveBaseUrl(url: String?) {
        prefs?.edit()
            ?.let { editor ->
                if (url.isNullOrBlank()) {
                    editor.remove(KEY_BASE_URL)
                } else {
                    editor.putString(KEY_BASE_URL, url.trim())
                }
                editor.apply()
            }
    }

    /** 保存 Bearer Token。传入 null 或空串清除该字段。 */
    fun saveBearerToken(token: String?) {
        prefs?.edit()
            ?.let { editor ->
                if (token.isNullOrBlank()) {
                    editor.remove(KEY_BEARER_TOKEN)
                } else {
                    editor.putString(KEY_BEARER_TOKEN, token.trim())
                }
                editor.apply()
            }
    }

    /** 保存 TLS 校验开关。 */
    fun saveVerifyTls(verify: Boolean) {
        prefs?.edit()
            ?.putBoolean(KEY_VERIFY_TLS, verify)
            ?.apply()
    }

    /** 保存上行上传开关。 */
    fun saveUploadEnabled(enabled: Boolean) {
        prefs?.edit()
            ?.putBoolean(KEY_UPLOAD_ENABLED, enabled)
            ?.apply()
    }

    /** 一次性保存全部字段。 */
    fun saveAll(
        baseUrl: String?,
        bearerToken: String?,
        verifyTls: Boolean = DEFAULT_VERIFY_TLS,
        uploadEnabled: Boolean = DEFAULT_UPLOAD_ENABLED,
    ) {
        prefs?.edit()?.apply {
            if (baseUrl.isNullOrBlank()) {
                remove(KEY_BASE_URL)
            } else {
                putString(KEY_BASE_URL, baseUrl.trim())
            }
            if (bearerToken.isNullOrBlank()) {
                remove(KEY_BEARER_TOKEN)
            } else {
                putString(KEY_BEARER_TOKEN, bearerToken.trim())
            }
            putBoolean(KEY_VERIFY_TLS, verifyTls)
            putBoolean(KEY_UPLOAD_ENABLED, uploadEnabled)
            apply()
        }
    }

    /** 清除全部服务端同步配置，回到 Local_Only_Mode。 */
    fun clear() {
        prefs?.edit()
            ?.remove(KEY_BASE_URL)
            ?.remove(KEY_BEARER_TOKEN)
            ?.remove(KEY_VERIFY_TLS)
            ?.remove(KEY_UPLOAD_ENABLED)
            ?.apply()
    }

    // ─────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────

    private val prefs: SharedPreferences?
        get() = try {
            preferences.value
        } catch (_: Exception) {
            // EncryptedSharedPreferences 在极端情况下（KeyStore 损坏）可能抛异常，
            // 此处吞掉以保证客户端进入 Local_Only_Mode 而非崩溃。
            null
        }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "server_sync_config"
        internal const val KEY_BASE_URL = "base_url"
        internal const val KEY_BEARER_TOKEN = "bearer_token"
        internal const val KEY_VERIFY_TLS = "verify_tls"
        internal const val KEY_UPLOAD_ENABLED = "upload_enabled"

        const val DEFAULT_VERIFY_TLS: Boolean = true
        const val DEFAULT_UPLOAD_ENABLED: Boolean = false

        /**
         * 安全创建 EncryptedSharedPreferences 实例。
         * 失败时返回 null 而非抛异常，保证调用方进入 Local_Only_Mode。
         */
        private fun createPreferencesSafe(
            context: Context,
            preferencesName: String,
        ): SharedPreferences? =
            try {
                val masterKey =
                    MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                EncryptedSharedPreferences.create(
                    context,
                    preferencesName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (_: Exception) {
                // KeyStore 损坏或安全硬件不可用时回退为 null，进入 Local_Only_Mode。
                null
            }
    }
}
