package com.wuyi.libraryauto.core.runtime.network

import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork

data class WifiReconnectSettings(
    val enabled: Boolean,
    val primaryNetwork: WifiReconnectNetwork?,
    val candidateNetworks: List<WifiReconnectNetwork>,
    val recoveryTimeoutSeconds: Int,
    val attemptTimeoutSeconds: Int,
)

data class NetworkRecoveryResult(
    val recovered: Boolean,
    val message: String,
)

interface WorkerNetworkManager {
    suspend fun isNetworkAvailable(): Boolean

    /**
     * 链路已连接但仍被系统判为"未通过校园网认证 / captive portal 待登录"的状态。
     * 默认 false 是为了保持与旧测试 fake 的二进制兼容。
     */
    suspend fun isCaptivePortalActive(): Boolean = false

    suspend fun waitForNetworkRecovery(timeoutSeconds: Int): Boolean
}
