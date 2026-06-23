package com.wuyi.libraryauto.core.runtime.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.wuyi.libraryauto.core.network.captive.ProbeResult
import com.wuyi.libraryauto.core.network.captive.TargetReachabilityProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * R15 网络监控指标聚合仓库。
 *
 * 用途：
 * 1. 暴露当前主网络的 SSID（受 `ACCESS_FINE_LOCATION` 与位置服务约束）、是否计费、是否需要认证、目标域直连结果与最近一次主动 Wi-Fi 重连结果；
 * 2. 对外更新（StateFlow 推送）按 R15.8 进行 60 秒节流，并按 R15.2 限制主动探测频率为 30 分钟内最多 1 次；
 * 3. [recordReconnectResult] 由 `ActiveWifiReconnector` 调用，独立写入"最近一次 reconnect 结果"，不受 60 秒节流限制；
 * 4. 不向上层抛出任何异常（R15.3 / R15.7）。
 *
 * 关键入参：
 * - [context]：用于读取权限状态与位置服务开关。
 * - [wifiManager] / [connectivityManager]：系统服务。
 * - [probe]：[TargetReachabilityProbe] 实现，提供目标域 HTTP HEAD 探测；本仓库仅持有依赖。
 * - [clock]：时钟函数，单元测试可注入虚拟时间。
 */
class NetworkMonitorMetricsRepository(
    private val context: Context,
    private val wifiManager: WifiManager,
    private val connectivityManager: ConnectivityManager,
    private val probe: TargetReachabilityProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val applicationContext = context.applicationContext
    private val mutex = Mutex()
    private val state = MutableStateFlow(NetworkMonitorMetrics.empty(clock()))

    /** 上次成功推送至 [metrics] 的时间戳（毫秒），用于 R15.8 60 秒节流。 */
    @Volatile
    private var lastEmitMillis: Long = 0L

    /** 上次主动调用 [TargetReachabilityProbe.probe] 的时间戳，用于 R15.2 30 分钟节流。 */
    @Volatile
    private var lastProbeMillis: Long = 0L

    /** 当前 R15 网络指标快照流，UI 可订阅以驱动渲染（R13.3）。 */
    val metrics: StateFlow<NetworkMonitorMetrics> = state.asStateFlow()

    /**
     * 主动触发一次指标采集与可能的对外更新。
     *
     * 行为：
     * - SSID / 是否计费 / 是否 captive portal 三项即时读取，受系统权限保护读取失败时回退为占位值；
     * - 仅当距上次主动探测 ≥ [PROBE_INTERVAL_MILLIS]（30 分钟）时才调用 [TargetReachabilityProbe.probe]，否则沿用上次探测结果；
     * - 仅当距上次推送 ≥ [EMIT_INTERVAL_MILLIS]（60 秒）且新旧值发生变化、或已到 30 分钟周期点时才更新 [metrics]。
     *
     * 不抛出异常至上层。
     */
    suspend fun refresh() {
        mutex.withLock {
            val now = clock()
            val ssid = readSsidOrUnauthorized()
            val (metered, captivePortal) = readCapabilities()

            val previous = state.value
            val shouldProbe = lastProbeMillis == 0L || now - lastProbeMillis >= PROBE_INTERVAL_MILLIS
            val (probeResult, probeUpdatedAtMillis) =
                if (shouldProbe) {
                    val result = runProbeSafely()
                    lastProbeMillis = now
                    result to now
                } else {
                    null to previous.targetProbedAtMillis
                }

            val candidate =
                previous.copy(
                    currentSsid = ssid,
                    isMetered = metered,
                    requiresCaptivePortal = captivePortal,
                    targetReachable =
                        probeResult?.reachable ?: previous.targetReachable,
                    targetProbeDurationMillis =
                        probeResult?.durationMillis ?: previous.targetProbeDurationMillis,
                    targetProbeFailureReason =
                        if (probeResult != null) {
                            probeResult.failureReason?.toChineseLabel()
                        } else {
                            previous.targetProbeFailureReason
                        },
                    targetProbedAtMillis = probeUpdatedAtMillis,
                )

            commitIfNeeded(previous = previous, candidate = candidate, now = now, periodicTick = shouldProbe)
        }
    }

    /**
     * 写入"最近一次 reconnect 结果"指标。
     *
     * 关键入参：[result] 来自 `ActiveWifiReconnector` 的成功 / 失败摘要。
     * 行为：即时更新 StateFlow，不受 R15.8 60 秒节流限制（按任务说明显式豁免）。
     */
    fun recordReconnectResult(result: ReconnectMetric) {
        val now = clock()
        val previous = state.value
        val updated =
            previous.copy(
                lastReconnectResult = result,
                updatedAtMillis = now,
            )
        state.value = updated
        lastEmitMillis = now
    }

    /**
     * 当 [candidate] 与 [previous] 出现差异（忽略 [NetworkMonitorMetrics.updatedAtMillis] 自身）或处于 30 分钟周期点时，
     * 在距上次推送 ≥ 60 秒后更新 [state]。
     */
    private fun commitIfNeeded(
        previous: NetworkMonitorMetrics,
        candidate: NetworkMonitorMetrics,
        now: Long,
        periodicTick: Boolean,
    ) {
        val changed = !candidate.isSameSnapshot(previous)
        val throttleElapsed = lastEmitMillis == 0L || now - lastEmitMillis >= EMIT_INTERVAL_MILLIS
        val shouldEmit = (changed && throttleElapsed) || periodicTick
        if (!shouldEmit) {
            return
        }
        state.value = candidate.copy(updatedAtMillis = now)
        lastEmitMillis = now
    }

    /**
     * 调用 [TargetReachabilityProbe.probe]；任何异常（含尚未实现的占位实现）都按"其他失败"分类返回，
     * 以确保不向上层抛异常。
     */
    private suspend fun runProbeSafely(): ProbeResult =
        runCatching { probe.probe() }
            .getOrElse {
                ProbeResult(
                    reachable = false,
                    durationMillis = 0L,
                    failureReason = ProbeResult.FailureReason.OTHER,
                )
            }

    /**
     * 读取当前主 Wi-Fi 的 SSID。
     *
     * 当缺少 [Manifest.permission.ACCESS_FINE_LOCATION]、位置服务关闭、或系统返回 `<unknown ssid>` 占位串时，
     * 一律返回 [SSID_UNAUTHORIZED]，对齐 R15.7。
     */
    @Suppress("DEPRECATION")
    private fun readSsidOrUnauthorized(): String {
        if (!hasFineLocationPermission()) {
            return SSID_UNAUTHORIZED
        }
        if (!isLocationServicesEnabled()) {
            return SSID_UNAUTHORIZED
        }
        val raw =
            runCatching { wifiManager.connectionInfo?.ssid }
                .getOrNull()
                .orEmpty()
        val trimmed = raw.trim().trim('"')
        return if (trimmed.isEmpty() || trimmed.equals(UNKNOWN_SSID_PLACEHOLDER, ignoreCase = true)) {
            SSID_UNAUTHORIZED
        } else {
            trimmed
        }
    }

    /** 通过 ConnectivityManager 取主网络能力，读取失败时回退为 (false, false)。 */
    private fun readCapabilities(): Pair<Boolean, Boolean> =
        runCatching {
            val active = connectivityManager.activeNetwork
            val caps = active?.let(connectivityManager::getNetworkCapabilities)
            if (caps == null) {
                false to false
            } else {
                val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                val captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                metered to captive
            }
        }.getOrDefault(false to false)

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun isLocationServicesEnabled(): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val manager =
                    applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                manager?.isLocationEnabled == true
            } else {
                @Suppress("DEPRECATION")
                val mode =
                    Settings.Secure.getInt(
                        applicationContext.contentResolver,
                        Settings.Secure.LOCATION_MODE,
                        Settings.Secure.LOCATION_MODE_OFF,
                    )
                @Suppress("DEPRECATION")
                mode != Settings.Secure.LOCATION_MODE_OFF
            }
        }.getOrDefault(false)

    private fun ProbeResult.FailureReason.toChineseLabel(): String =
        when (this) {
            ProbeResult.FailureReason.TIMEOUT -> "超时"
            ProbeResult.FailureReason.NETWORK_UNREACHABLE -> "网络不可达"
            ProbeResult.FailureReason.CERTIFICATE_ERROR -> "证书异常"
            ProbeResult.FailureReason.OTHER -> "其他"
        }

    companion object {
        /** SSID 不可读时的占位文案，对齐 R15.7。 */
        const val SSID_UNAUTHORIZED: String = "未授权"

        /** R15.8 节流：StateFlow 写入间隔下限。 */
        internal const val EMIT_INTERVAL_MILLIS: Long = 60_000L

        /** R15.2 节流：主动探测目标域的间隔下限（30 分钟）。 */
        internal const val PROBE_INTERVAL_MILLIS: Long = 30L * 60L * 1_000L

        /** Android 在缺少位置权限时返回的占位 SSID。 */
        private const val UNKNOWN_SSID_PLACEHOLDER: String = "<unknown ssid>"
    }
}

/**
 * R15 网络监控指标快照。
 *
 * 关键字段：
 * - [currentSsid]：当前主 Wi-Fi 的 SSID，无法读取时为 [NetworkMonitorMetricsRepository.SSID_UNAUTHORIZED]。
 * - [isMetered]：是否计费网络（来自 [NetworkCapabilities.NET_CAPABILITY_NOT_METERED] 取反）。
 * - [requiresCaptivePortal]：是否需要认证（[NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL]）。
 * - [targetReachable]：最近一次目标域探测是否成功；首次未探测前为 null。
 * - [targetProbeDurationMillis]：最近一次探测耗时毫秒数。
 * - [targetProbeFailureReason]：失败原因中文摘要（"超时" / "网络不可达" / "证书异常" / "其他"）。
 * - [targetProbedAtMillis]：最近一次探测完成时间戳，用于 30 分钟节流判断。
 * - [lastReconnectResult]：最近一次主动 Wi-Fi 重连摘要。
 * - [updatedAtMillis]：最近一次对外推送的时间戳。
 */
data class NetworkMonitorMetrics(
    val currentSsid: String,
    val isMetered: Boolean,
    val requiresCaptivePortal: Boolean,
    val targetReachable: Boolean?,
    val targetProbeDurationMillis: Long,
    val targetProbeFailureReason: String?,
    val targetProbedAtMillis: Long,
    val lastReconnectResult: ReconnectMetric?,
    val updatedAtMillis: Long,
) {
    /** 比较两个快照在"实质性指标"上是否一致，用于 60 秒节流的"值变化"判定。 */
    internal fun isSameSnapshot(other: NetworkMonitorMetrics): Boolean =
        currentSsid == other.currentSsid &&
            isMetered == other.isMetered &&
            requiresCaptivePortal == other.requiresCaptivePortal &&
            targetReachable == other.targetReachable &&
            targetProbeDurationMillis == other.targetProbeDurationMillis &&
            targetProbeFailureReason == other.targetProbeFailureReason &&
            lastReconnectResult == other.lastReconnectResult

    companion object {
        internal fun empty(updatedAtMillis: Long): NetworkMonitorMetrics =
            NetworkMonitorMetrics(
                currentSsid = NetworkMonitorMetricsRepository.SSID_UNAUTHORIZED,
                isMetered = false,
                requiresCaptivePortal = false,
                targetReachable = null,
                targetProbeDurationMillis = 0L,
                targetProbeFailureReason = null,
                targetProbedAtMillis = 0L,
                lastReconnectResult = null,
                updatedAtMillis = updatedAtMillis,
            )
    }
}

/**
 * 最近一次主动 Wi-Fi 重连摘要。
 *
 * 关键字段：[ssid]、[startedAtMillis]、[endedAtMillis]、[success]、可选的中文 [failureReason]。
 */
data class ReconnectMetric(
    val ssid: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val success: Boolean,
    val failureReason: String? = null,
)
