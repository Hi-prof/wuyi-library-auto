package com.wuyi.libraryauto.core.runtime.network

import android.content.Context

/**
 * 后台 captive portal 自动认证的全局注入点。
 *
 * 之前 [BackgroundNetworkRecoveryCoordinator] 没有 captive portal 处理路径，
 * 校园网未认证时会直接放行业务请求，导致后台 Worker 大概率失败。
 *
 * 现在通过该 provider 把生产环境的 [CaptivePortalRecoveryRunner] 注入到
 * runtime 模块；app 层在 `Application.onCreate` 中调用 [install] 完成注入，
 * 测试或 BootRestoreReceiver 等不需要 captive portal 时可以保持默认 noop。
 */
object CaptivePortalRecoveryProvider {
    @Volatile
    private var factory: ((Context) -> CaptivePortalRecoveryRunner)? = null

    fun install(factory: (Context) -> CaptivePortalRecoveryRunner) {
        this.factory = factory
    }

    fun reset() {
        factory = null
    }

    fun get(context: Context): CaptivePortalRecoveryRunner? = factory?.invoke(context.applicationContext)
}
