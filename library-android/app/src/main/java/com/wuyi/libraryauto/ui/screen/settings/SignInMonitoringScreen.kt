package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SignInMonitoringScreen(
    source: SignInMonitoringDataSource,
) {
    val factory = remember(source) { SignInMonitoringViewModelFactory(source) }
    val viewModel: SignInMonitoringViewModel = viewModel(factory = factory)
    val state = viewModel.uiState

    SettingsLazyColumn {
        item {
            SettingsCard(title = "签到监控", body = "最近 50 条签到审计、蓝牙扫描审计和 24 小时失败聚合。") {
                Button(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text("刷新")
                }
                if (state.showPlaceholder) {
                    Text("加载中...")
                }
                if (state.emptyMessage.isNotBlank()) {
                    Text(state.emptyMessage)
                }
            }
        }
        item {
            SettingsCard(title = "签到审计", body = "最多 50 条。") {
                if (state.signInAudits.isEmpty()) {
                    Text("暂无记录")
                } else {
                    state.signInAudits.forEach { item ->
                        Text("${item.studentId} / ${item.bookingId} / ${item.result} / ${item.triggerSource}")
                    }
                }
            }
        }
        item {
            SettingsCard(title = "蓝牙扫描审计", body = "最多 50 条。") {
                if (state.beaconScanAudits.isEmpty()) {
                    Text("暂无记录")
                } else {
                    state.beaconScanAudits.forEach { item ->
                        Text("${item.bookingId} / 命中 ${item.matchedMinor} / ${item.durationMillis} ms")
                    }
                }
            }
        }
        item {
            SettingsCard(title = "24 小时失败聚合", body = "按 SignInError 归类。") {
                if (state.errorAggregates.isEmpty()) {
                    Text("暂无记录")
                } else {
                    state.errorAggregates.forEach { item -> Text("${item.error}: ${item.count}") }
                }
            }
        }
    }
}
