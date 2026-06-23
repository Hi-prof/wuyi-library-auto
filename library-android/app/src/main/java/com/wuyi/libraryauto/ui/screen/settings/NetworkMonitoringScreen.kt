package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.core.runtime.network.NetworkMonitorMetricsRepository
import com.wuyi.libraryauto.core.storage.network.WifiReconnectStore

@Composable
fun NetworkMonitoringScreen(
    metricsRepository: NetworkMonitorMetricsRepository,
    wifiReconnectStore: WifiReconnectStore,
) {
    val factory =
        remember(metricsRepository, wifiReconnectStore) {
            NetworkMonitoringViewModelFactory(
                metricsRepository = metricsRepository,
                wifiReconnectStore = wifiReconnectStore,
            )
        }
    val viewModel: NetworkMonitoringViewModel = viewModel(factory = factory)
    val state = viewModel.uiState

    SettingsLazyColumn {
        item {
            SettingsCard(title = "网络监控", body = "主网络、计费状态、认证状态和目标域直连状态。") {
                InfoLine("当前 SSID", state.currentSsid)
                InfoLine("主网络", state.primaryNetwork)
                InfoLine("计费状态", state.meteredLabel)
                InfoLine("认证状态", state.captivePortalLabel)
                InfoLine("目标域直连", state.targetReachability)
                InfoLine("最近重连", state.lastReconnect)
                Button(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text("刷新")
                }
            }
        }
        item {
            SettingsCard(title = "候选网络", body = "按 Wi-Fi 重连设置中的候选列表展示。") {
                if (state.candidateNetworks.isEmpty()) {
                    Text("暂无记录")
                } else {
                    state.candidateNetworks.forEach { ssid -> Text(ssid) }
                }
            }
        }
    }
}
