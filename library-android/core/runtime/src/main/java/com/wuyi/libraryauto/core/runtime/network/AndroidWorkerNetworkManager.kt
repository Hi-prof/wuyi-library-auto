package com.wuyi.libraryauto.core.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay

class AndroidWorkerNetworkManager(
    context: Context,
) : WorkerNetworkManager {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    /**
     * 「真正可用」必须满足三个条件：
     * - 链路具备 [NetworkCapabilities.NET_CAPABILITY_INTERNET]
     * - 通过系统 captive portal 校验（[NetworkCapabilities.NET_CAPABILITY_VALIDATED]）
     * - 没有被系统标记为 captive portal 模式
     *
     * 之前只看 INTERNET 会让"已连上但未通过校园网认证"被误判为可用，
     * 后台 Worker 会硬走业务请求并被 portal 截断。
     */
    override suspend fun isNetworkAvailable(): Boolean {
        val capabilities = currentCapabilities() ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }

    /**
     * 链路连上但还卡在 captive portal：INTERNET 存在，VALIDATED 缺失或显式标记 CAPTIVE_PORTAL。
     */
    override suspend fun isCaptivePortalActive(): Boolean {
        val capabilities = currentCapabilities() ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return true
        }
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override suspend fun waitForNetworkRecovery(timeoutSeconds: Int): Boolean {
        repeat(timeoutSeconds.coerceAtLeast(1)) {
            if (isNetworkAvailable()) {
                return true
            }
            delay(1_000)
        }
        return isNetworkAvailable()
    }

    private fun currentCapabilities(): NetworkCapabilities? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getNetworkCapabilities(activeNetwork)
    }
}
