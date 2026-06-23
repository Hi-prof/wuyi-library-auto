package com.wuyi.libraryauto.runtime

import android.content.Context
import com.wuyi.libraryauto.core.network.captive.CampusPortalAuthRequest
import com.wuyi.libraryauto.core.network.captive.CampusPortalAuthResult
import com.wuyi.libraryauto.core.network.captive.CampusPortalAuthenticator
import com.wuyi.libraryauto.core.network.http.MemoryCookieJar
import com.wuyi.libraryauto.core.runtime.network.CaptivePortalRecoveryResult
import com.wuyi.libraryauto.core.runtime.network.CaptivePortalRecoveryRunner
import com.wuyi.libraryauto.core.storage.network.CampusNetworkCredentialStore
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 生产环境 [CaptivePortalRecoveryRunner] 工厂。
 *
 * 流程：
 * 1. 从 [CampusNetworkCredentialStore] 读取本地保存的账号；缺失则跳过。
 * 2. 优先使用本地保存的登录页 URL，缺失时回退到 [SchoolPortalConfig.DefaultCampusPortalLoginPageUrl]。
 * 3. 用单例的 [CampusPortalAuthenticator] 跑认证；成功视为 Authenticated，失败传回 Failed，
 *    冷却时回传 Skipped 让上层 coordinator 据此输出原因。
 *
 * OkHttp 客户端使用 in-memory cookie jar 与 30 秒整体超时，避免后台 Worker 被慢响应卡住。
 * 不允许重定向跟随：portal 的 302 响应在 [CampusPortalAuthenticator.isLoginSuccess] 中识别。
 */
internal object CampusPortalRecoveryRunnerFactory {
    @Volatile
    private var sharedAuthenticator: CampusPortalAuthenticator? = null

    fun authenticator(): CampusPortalAuthenticator =
        sharedAuthenticator
            ?: synchronized(this) {
                sharedAuthenticator
                    ?: CampusPortalAuthenticator(client = buildHttpClient())
                        .also { sharedAuthenticator = it }
            }

    fun create(context: Context): CaptivePortalRecoveryRunner {
        val appContext = context.applicationContext
        val credentialStore = CampusNetworkCredentialStore(appContext)
        val authenticator = authenticator()
        return CaptivePortalRecoveryRunner {
            val credential = credentialStore.read()
                ?: return@CaptivePortalRecoveryRunner CaptivePortalRecoveryResult.Skipped(
                    message = "未配置校园网账号",
                )
            val loginPageUrl =
                SchoolPortalConfig.resolveCampusPortalLoginPageUrl(
                    credentialStore.readLoginPageUrl(),
                )
            val result =
                runCatching {
                    authenticator.authenticate(
                        CampusPortalAuthRequest(
                            loginPageUrl = loginPageUrl,
                            username = credential.username,
                            password = credential.password,
                        ),
                    )
                }.getOrElse { error ->
                    CampusPortalAuthResult.Failure(error.message ?: "校园网认证异常")
                }
            when (result) {
                is CampusPortalAuthResult.Success ->
                    CaptivePortalRecoveryResult.Authenticated(message = result.message)

                is CampusPortalAuthResult.Failure ->
                    CaptivePortalRecoveryResult.Failed(message = result.message)

                is CampusPortalAuthResult.Skipped ->
                    CaptivePortalRecoveryResult.Skipped(message = result.message)
            }
        }
    }

    private fun buildHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // 复用 core:network 的内存 CookieJar：portal 登录会下发 session cookie，
            // 提交表单时需要带回；不持久化，进程结束即清理。
            .cookieJar(MemoryCookieJar())
            .build()
}
