package com.wuyi.libraryauto.core.runtime.network

import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork

class BackgroundNetworkRecoveryCoordinator(
    private val networkManager: WorkerNetworkManager,
    private val activeWifiReconnector: ActiveWifiReconnectRunner? = null,
    private val captivePortalRunner: CaptivePortalRecoveryRunner? = null,
    private val eventEmitter: NetworkRecoveryEventEmitter = NetworkRecoveryEventEmitter { source ->
        NetworkRecoveryEventBus.emit(source)
    },
    private val bleScanActivityMonitor: BleScanActivityMonitor = BleScanActivityMonitor { false },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastUnavailableSinceMillis: Long? = null
    private var lastRecovered = true

    suspend fun recoverIfNeeded(settings: WifiReconnectSettings): NetworkRecoveryResult {
        if (networkManager.isNetworkAvailable()) {
            return recoveredResult("当前网络可用")
        }

        // captive portal 待登录：链路通了，但还没被系统校验。
        // 调用方注入了 [captivePortalRunner] 时尝试自动认证；成功视为已恢复。
        if (networkManager.isCaptivePortalActive()) {
            val portalResult = captivePortalRunner?.tryAuthenticate()
            if (portalResult is CaptivePortalRecoveryResult.Authenticated) {
                return recoveredResult("校园网认证成功：${portalResult.message}")
            }
            // 没注入 runner 或认证失败时，不立即放行：继续走主动重连或等待系统恢复，
            // 让上层观察到「网络仍未可用 + portal 状态」，避免硬走业务请求被截断。
            val portalSummary = portalResult?.failureSummary()
            markUnavailable()
            if (!settings.enabled) {
                return NetworkRecoveryResult(
                    recovered = false,
                    message = portalSummary?.let { "$it；后台 Wi-Fi 重连未开启" } ?: "校园网未认证，后台 Wi-Fi 重连未开启",
                )
            }
            // 校园网未认证场景下，主动重连同一 SSID 不会改变现状，等待系统二次校验更稳妥。
            if (networkManager.waitForNetworkRecovery(settings.recoveryTimeoutSeconds)) {
                return recoveredResult("系统已恢复网络连接")
            }
            return NetworkRecoveryResult(
                recovered = false,
                message = portalSummary?.let { "$it；等待系统认证超时" } ?: "校园网认证未完成，请手动登录后重试。",
            )
        }

        markUnavailable()

        if (!settings.enabled) {
            return NetworkRecoveryResult(recovered = false, message = "后台 Wi-Fi 重连未开启")
        }
        val networks = orderedNetworks(settings)
        if (networks.isEmpty()) {
            return NetworkRecoveryResult(recovered = false, message = "未配置可用的 Wi-Fi 重连项")
        }
        if (bleScanActivityMonitor.isBleScanActive()) {
            return NetworkRecoveryResult(recovered = false, message = "BLE 扫描进行中，延后网络恢复")
        }

        val activeReconnectResult = tryActiveReconnectIfEligible(settings)
        if (activeReconnectResult is WifiReconnectResult.Connected) {
            // Wi-Fi 已连回去，但可能立刻进入 captive portal 状态；
            // 这里再尝试一次认证，保证连上即可用。
            if (networkManager.isCaptivePortalActive()) {
                val portalResult = captivePortalRunner?.tryAuthenticate()
                if (portalResult is CaptivePortalRecoveryResult.Authenticated) {
                    return recoveredResult(
                        "主动 Wi-Fi 重连成功：${activeReconnectResult.ssid}；校园网认证成功：${portalResult.message}",
                    )
                }
            }
            return recoveredResult("主动 Wi-Fi 重连成功：${activeReconnectResult.ssid}")
        }

        if (networkManager.waitForNetworkRecovery(settings.recoveryTimeoutSeconds)) {
            return recoveredResult("系统已恢复网络连接")
        }
        val configuredSsids = networks.joinToString("、") { network -> network.ssid }
        val activeReconnectSummary = activeReconnectResult?.failureSummary()
        val messagePrefix =
            if (activeReconnectSummary == null) {
                ""
            } else {
                "$activeReconnectSummary；"
            }
        return NetworkRecoveryResult(
            recovered = false,
            message = "${messagePrefix}已等待系统恢复网络，当前仍未连上：$configuredSsids",
        )
    }

    private fun markUnavailable() {
        if (lastUnavailableSinceMillis == null) {
            lastUnavailableSinceMillis = nowMillis()
        }
        lastRecovered = false
    }

    private suspend fun recoveredResult(message: String): NetworkRecoveryResult {
        lastUnavailableSinceMillis = null
        if (!lastRecovered) {
            eventEmitter.emit(TriggerSource.NetworkRestored)
        }
        lastRecovered = true
        return NetworkRecoveryResult(recovered = true, message = message)
    }

    private suspend fun tryActiveReconnectIfEligible(settings: WifiReconnectSettings): WifiReconnectResult? {
        val reconnector = activeWifiReconnector ?: return null
        val unavailableSince = lastUnavailableSinceMillis ?: return null
        if (nowMillis() - unavailableSince < ACTIVE_RECONNECT_UNAVAILABLE_DELAY_MILLIS) {
            return null
        }
        return reconnector.tryReconnect(settings)
    }

    private fun orderedNetworks(settings: WifiReconnectSettings): List<WifiReconnectNetwork> =
        buildList {
            settings.primaryNetwork?.let(::add)
            addAll(settings.candidateNetworks)
        }.mapNotNull(::sanitize)
            .distinctBy(WifiReconnectNetwork::ssid)

    private fun sanitize(network: WifiReconnectNetwork): WifiReconnectNetwork? {
        val ssid = network.ssid.trim()
        if (ssid.isBlank() || network.password.isBlank()) {
            return null
        }
        return network.copy(ssid = ssid)
    }

    private fun WifiReconnectResult.failureSummary(): String? =
        when (this) {
            is WifiReconnectResult.Connected -> null
            is WifiReconnectResult.Failed -> "主动 Wi-Fi 重连失败：${reason.name}"
            WifiReconnectResult.Cooldown -> "主动 Wi-Fi 重连处于冷却"
            WifiReconnectResult.NoConfiguration -> "主动 Wi-Fi 重连未找到配置"
        }

    companion object {
        internal const val ACTIVE_RECONNECT_UNAVAILABLE_DELAY_MILLIS = 5_000L
    }
}

fun interface ActiveWifiReconnectRunner {
    suspend fun tryReconnect(settings: WifiReconnectSettings): WifiReconnectResult
}

/**
 * 校园网 captive portal 自动认证的统一入口。
 *
 * 实现方负责读取本地保存的校园网账号、组装登录页 URL，并调用底层
 * `CampusPortalAuthenticator.authenticate(...)`；返回值告诉协调器是否完成认证。
 */
fun interface CaptivePortalRecoveryRunner {
    suspend fun tryAuthenticate(): CaptivePortalRecoveryResult
}

sealed class CaptivePortalRecoveryResult {
    data class Authenticated(val message: String) : CaptivePortalRecoveryResult()

    data class Failed(val message: String) : CaptivePortalRecoveryResult()

    /** 尚未配置校园网账号或处于冷却中。 */
    data class Skipped(val message: String) : CaptivePortalRecoveryResult()

    internal fun failureSummary(): String? =
        when (this) {
            is Authenticated -> null
            is Failed -> "校园网认证失败：$message"
            is Skipped -> "校园网认证未执行：$message"
        }
}

fun interface NetworkRecoveryEventEmitter {
    suspend fun emit(source: TriggerSource)
}

fun interface BleScanActivityMonitor {
    fun isBleScanActive(): Boolean
}
