package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.core.runtime.network.NetworkMonitorMetricsRepository
import com.wuyi.libraryauto.core.storage.network.WifiReconnectStore
import kotlinx.coroutines.launch

class NetworkMonitoringViewModel(
    private val metricsRepository: NetworkMonitorMetricsRepository,
    private val wifiReconnectStore: WifiReconnectStore,
) : ViewModel() {
    var uiState by mutableStateOf(NetworkMonitoringUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            metricsRepository.refresh()
            val metrics = metricsRepository.metrics.value
            val reconnect = wifiReconnectStore.loadSnapshot()
            uiState =
                NetworkMonitoringUiState(
                    primaryNetwork = reconnect.primaryNetwork?.ssid ?: "未配置",
                    candidateNetworks = reconnect.candidateNetworks.map { network -> network.ssid },
                    lastReconnect = metrics.lastReconnectResult?.toLabel() ?: "暂无记录",
                    targetReachability =
                        when (metrics.targetReachable) {
                            true -> "可直连，${metrics.targetProbeDurationMillis} ms"
                            false -> "不可直连：${metrics.targetProbeFailureReason ?: "未知"}"
                            null -> "尚未探测"
                        },
                    currentSsid = metrics.currentSsid,
                    meteredLabel = if (metrics.isMetered) "计费网络" else "非计费网络",
                    captivePortalLabel = if (metrics.requiresCaptivePortal) "需要认证" else "无需认证",
                )
        }
    }
}

data class NetworkMonitoringUiState(
    val primaryNetwork: String = "未配置",
    val candidateNetworks: List<String> = emptyList(),
    val lastReconnect: String = "暂无记录",
    val targetReachability: String = "尚未探测",
    val currentSsid: String = "未授权",
    val meteredLabel: String = "非计费网络",
    val captivePortalLabel: String = "无需认证",
)

class NetworkMonitoringViewModelFactory(
    private val metricsRepository: NetworkMonitorMetricsRepository,
    private val wifiReconnectStore: WifiReconnectStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NetworkMonitoringViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return NetworkMonitoringViewModel(
            metricsRepository = metricsRepository,
            wifiReconnectStore = wifiReconnectStore,
        ) as T
    }
}

private fun com.wuyi.libraryauto.core.runtime.network.ReconnectMetric.toLabel(): String =
    if (success) {
        "$ssid 重连成功"
    } else {
        "$ssid 重连失败：${failureReason ?: "未知原因"}"
    }
