package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogHeartbeatStore
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogStateStore

@Composable
fun WatchdogStatusScreen(
    heartbeatStore: WatchdogHeartbeatStore,
    stateStore: WatchdogStateStore,
) {
    val factory =
        remember(heartbeatStore, stateStore) {
            WatchdogStatusViewModelFactory(
                heartbeatStore = heartbeatStore,
                stateStore = stateStore,
            )
        }
    val viewModel: WatchdogStatusViewModel = viewModel(factory = factory)
    val state = viewModel.uiState

    SettingsLazyColumn {
        item {
            SettingsCard(title = "看门狗状态", body = "周期巡检心跳、失败计数和关键 unique work 状态。") {
                InfoLine("周期签到心跳", state.periodicHeartbeat)
                InfoLine("看门狗心跳", state.watchdogHeartbeat)
                InfoLine("失败计数", state.failureCount.toString())
                InfoLine("状态", state.stateLabel)
                Button(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text("刷新")
                }
            }
        }
        item {
            SettingsCard(title = "关键任务", body = "展示需要持续存在的后台任务。") {
                state.uniqueWorks.forEach { work ->
                    Text("${work.name}：${work.nextRunLabel}")
                }
            }
        }
    }
}
