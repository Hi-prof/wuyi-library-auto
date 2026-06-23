package com.wuyi.libraryauto.core.storage.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 校园网认证凭据数据类。
 *
 * 与登录账号 (SavedAccountStore) 互不复用：仅承载用于 i-NET / 校园门户认证的
 * 用户名与密码。
 *
 * @property username 校园网认证用户名（已 trim、非空）。
 * @property password 校园网认证密码（已 trim、非空）。
 */
data class CampusCredential(
    val username: String,
    val password: String,
)

/**
 * 校园网认证凭据存储。
 *
 * 底层使用与 [com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore]
 * 相同的 [MasterKey] (AES256_GCM) 与 [EncryptedSharedPreferences]
 * (AES256_SIV / AES256_GCM) 加密方案；但落盘到独立的 prefs 文件
 * `campus_network_credentials.xml`，键名 `campus_username` / `campus_password`，
 * 因此与登录账号、Wi-Fi 重连凭据等互不冲突，也不会被其它模块意外覆盖。
 *
 * 线程安全由 [SharedPreferences] 自身保证；写入使用 `apply()` 异步落盘。
 */
class CampusNetworkCredentialStore(
    private val preferences: Lazy<SharedPreferences>,
) {
    /**
     * 主构造：根据 [Context] 创建/打开默认加密 prefs 文件。
     *
     * @param context 任意 [Context]，内部会取 `applicationContext` 以避免泄漏。
     * @param preferencesName prefs 文件名，默认 `campus_network_credentials`。
     */
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            createPreferences(context.applicationContext, preferencesName)
        },
    )

    /** 仅供测试注入已构造好的 [SharedPreferences]，避开 Android Keystore 依赖。 */
    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    /**
     * 读取已保存的校园网凭据。
     *
     * @return 凭据；若未配置或任一字段为空（含仅空白字符），返回 `null`。
     */
    fun read(): CampusCredential? {
        val username = preferences.value.getString(KEY_USERNAME, null).orEmpty().trim()
        val password = preferences.value.getString(KEY_PASSWORD, null).orEmpty()
        if (username.isEmpty() || password.isBlank()) {
            return null
        }
        return CampusCredential(username = username, password = password)
    }

    /**
     * 读取本地保存的 captive portal 登录页 URL，未保存时返回 null。
     * 实际生效的默认值由 UI 层提供（[SchoolPortalConfig.DefaultCampusPortalLoginPageUrl]）。
     */
    fun readLoginPageUrl(): String? =
        preferences.value.getString(KEY_LOGIN_PAGE_URL, null)?.trim()?.takeIf(String::isNotBlank)

    /** 保存 captive portal 登录页 URL；空白或 null 视为清除。 */
    fun saveLoginPageUrl(url: String?) {
        val safe = url?.trim().orEmpty()
        val editor = preferences.value.edit()
        if (safe.isBlank()) {
            editor.remove(KEY_LOGIN_PAGE_URL)
        } else {
            editor.putString(KEY_LOGIN_PAGE_URL, safe)
        }
        editor.apply()
    }

    /**
     * 保存校园网凭据。
     *
     * 入参会做 `trim`（密码仅校验非空白，不修剪内部内容）；任一字段为空将抛出
     * [IllegalArgumentException]，避免写入"看似已配置但其实为空"的状态。
     *
     * @param username 校园网用户名，必须非空白。
     * @param password 校园网密码，必须非空白。
     */
    fun save(username: String, password: String) {
        val safeUsername = username.trim()
        require(safeUsername.isNotEmpty()) { "campus username must not be blank" }
        require(password.isNotBlank()) { "campus password must not be blank" }
        preferences.value.edit()
            .putString(KEY_USERNAME, safeUsername)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    /**
     * 清空已保存的校园网凭据。
     *
     * 调用后 [read] 将返回 `null`；不会抛出异常，幂等。
     */
    fun clear() {
        preferences.value.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_LOGIN_PAGE_URL)
            .apply()
    }

    companion object {
        private const val DEFAULT_PREFERENCES_NAME = "campus_network_credentials"
        private const val KEY_USERNAME = "campus_username"
        private const val KEY_PASSWORD = "campus_password"
        private const val KEY_LOGIN_PAGE_URL = "campus_login_page_url"

        private fun createPreferences(
            context: Context,
            preferencesName: String,
        ): SharedPreferences {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                preferencesName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
