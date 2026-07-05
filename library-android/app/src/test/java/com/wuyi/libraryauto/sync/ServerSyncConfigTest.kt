package com.wuyi.libraryauto.sync

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 任务 12.13：ServerSyncConfig 单元测试。
 *
 * 验证：
 * - 默认值语义（verify_tls=true, upload_enabled=false, base_url=null, bearer_token=null）
 * - isConfigured() / isUploadEnabled() / isLocalOnly() 三个工具方法的组合行为
 * - 缺失字段时不抛异常，进入 Local_Only_Mode
 * - 字段读写往返一致
 *
 * 与 [AccountPoolSyncRepositoryTest] 同样使用 `@Config(application = Application::class)`
 * 跑朴素 Application，绕过 `@HiltAndroidApp` 默认对 WorkManager 等
 * 生产依赖的初始化要求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class ServerSyncConfigTest {

    private lateinit var config: ServerSyncConfig

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs: SharedPreferences = context.getSharedPreferences(
            "test_server_sync_config",
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().commit()
        config = ServerSyncConfig(prefs)
    }

    // ─────────────────────────────────────────────────
    // 默认值
    // ─────────────────────────────────────────────────

    @Test
    fun `default baseUrl is null`() {
        assertThat(config.baseUrl).isNull()
    }

    @Test
    fun `default bearerToken is null`() {
        assertThat(config.bearerToken).isNull()
    }

    @Test
    fun `default verifyTls is true`() {
        assertThat(config.verifyTls).isTrue()
    }

    @Test
    fun `default uploadEnabled is false`() {
        assertThat(config.uploadEnabled).isFalse()
    }

    // ─────────────────────────────────────────────────
    // isConfigured()
    // ─────────────────────────────────────────────────

    @Test
    fun `isConfigured returns false when both fields are missing`() {
        assertThat(config.isConfigured()).isFalse()
    }

    @Test
    fun `isConfigured returns false when only baseUrl is set`() {
        config.saveBaseUrl("https://example.com")
        assertThat(config.isConfigured()).isFalse()
    }

    @Test
    fun `isConfigured returns false when only bearerToken is set`() {
        config.saveBearerToken("tok-abc")
        assertThat(config.isConfigured()).isFalse()
    }

    @Test
    fun `isConfigured returns true when both fields are set`() {
        config.saveBaseUrl("https://example.com")
        config.saveBearerToken("tok-abc")
        assertThat(config.isConfigured()).isTrue()
    }

    @Test
    fun `isConfigured returns false when baseUrl is blank`() {
        config.saveBaseUrl("   ")
        config.saveBearerToken("tok-abc")
        assertThat(config.isConfigured()).isFalse()
    }

    // ─────────────────────────────────────────────────
    // isUploadEnabled()
    // ─────────────────────────────────────────────────

    @Test
    fun `isUploadEnabled returns false when not configured`() {
        assertThat(config.isUploadEnabled()).isFalse()
    }

    @Test
    fun `isUploadEnabled returns false when configured but upload_enabled is false`() {
        config.saveBaseUrl("https://example.com")
        config.saveBearerToken("tok-abc")
        // upload_enabled defaults to false
        assertThat(config.isUploadEnabled()).isFalse()
    }

    @Test
    fun `isUploadEnabled returns true when configured and upload_enabled is true`() {
        config.saveBaseUrl("https://example.com")
        config.saveBearerToken("tok-abc")
        config.saveUploadEnabled(true)
        assertThat(config.isUploadEnabled()).isTrue()
    }

    @Test
    fun `isUploadEnabled returns false when upload_enabled is true but not configured`() {
        config.saveUploadEnabled(true)
        assertThat(config.isUploadEnabled()).isFalse()
    }

    // ─────────────────────────────────────────────────
    // isLocalOnly()
    // ─────────────────────────────────────────────────

    @Test
    fun `isLocalOnly returns true when not configured`() {
        assertThat(config.isLocalOnly()).isTrue()
    }

    @Test
    fun `isLocalOnly returns true when configured but upload_enabled is false`() {
        config.saveBaseUrl("https://example.com")
        config.saveBearerToken("tok-abc")
        assertThat(config.isLocalOnly()).isTrue()
    }

    @Test
    fun `isLocalOnly returns false when configured and upload_enabled is true`() {
        config.saveBaseUrl("https://example.com")
        config.saveBearerToken("tok-abc")
        config.saveUploadEnabled(true)
        assertThat(config.isLocalOnly()).isFalse()
    }

    // ─────────────────────────────────────────────────
    // 读写往返
    // ─────────────────────────────────────────────────

    @Test
    fun `saveAll persists all fields and reads back correctly`() {
        config.saveAll(
            baseUrl = "https://my-server.test/api",
            bearerToken = "secret-token-xyz",
            verifyTls = false,
            uploadEnabled = true,
        )

        assertThat(config.baseUrl).isEqualTo("https://my-server.test/api")
        assertThat(config.bearerToken).isEqualTo("secret-token-xyz")
        assertThat(config.verifyTls).isFalse()
        assertThat(config.uploadEnabled).isTrue()
        assertThat(config.isConfigured()).isTrue()
        assertThat(config.isUploadEnabled()).isTrue()
        assertThat(config.isLocalOnly()).isFalse()
    }

    @Test
    fun `saveBaseUrl trims whitespace`() {
        config.saveBaseUrl("  https://example.com  ")
        assertThat(config.baseUrl).isEqualTo("https://example.com")
    }

    @Test
    fun `saveBearerToken trims whitespace`() {
        config.saveBearerToken("  tok-abc  ")
        assertThat(config.bearerToken).isEqualTo("tok-abc")
    }

    @Test
    fun `saveBaseUrl with null clears the field`() {
        config.saveBaseUrl("https://example.com")
        config.saveBaseUrl(null)
        assertThat(config.baseUrl).isNull()
    }

    @Test
    fun `saveBearerToken with null clears the field`() {
        config.saveBearerToken("tok-abc")
        config.saveBearerToken(null)
        assertThat(config.bearerToken).isNull()
    }

    // ─────────────────────────────────────────────────
    // clear()
    // ─────────────────────────────────────────────────

    @Test
    fun `clear removes all fields and returns to Local_Only_Mode`() {
        config.saveAll(
            baseUrl = "https://example.com",
            bearerToken = "tok-abc",
            verifyTls = false,
            uploadEnabled = true,
        )

        config.clear()

        assertThat(config.baseUrl).isNull()
        assertThat(config.bearerToken).isNull()
        assertThat(config.verifyTls).isTrue()
        assertThat(config.uploadEnabled).isFalse()
        assertThat(config.isConfigured()).isFalse()
        assertThat(config.isLocalOnly()).isTrue()
    }

    // ─────────────────────────────────────────────────
    // 容错：EncryptedSharedPreferences 不可用时不崩溃
    // ─────────────────────────────────────────────────

    @Test
    fun `null preferences fallback does not throw and enters Local_Only_Mode`() {
        val failingConfig = ServerSyncConfig(lazyOf(null))

        assertThat(failingConfig.baseUrl).isNull()
        assertThat(failingConfig.bearerToken).isNull()
        assertThat(failingConfig.verifyTls).isTrue()
        assertThat(failingConfig.uploadEnabled).isFalse()
        assertThat(failingConfig.isConfigured()).isFalse()
        assertThat(failingConfig.isUploadEnabled()).isFalse()
        assertThat(failingConfig.isLocalOnly()).isTrue()

        // write operations should not throw
        failingConfig.saveBaseUrl("https://example.com")
        failingConfig.saveBearerToken("tok")
        failingConfig.saveVerifyTls(false)
        failingConfig.saveUploadEnabled(true)
        failingConfig.saveAll("url", "tok", false, true)
        failingConfig.clear()
    }
}
