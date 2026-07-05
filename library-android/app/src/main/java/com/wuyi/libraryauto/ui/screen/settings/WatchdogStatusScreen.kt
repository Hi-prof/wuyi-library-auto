package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogHeartbeatStore
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogStateStore
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone

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
            SettingsHeroCard(
                title = "看门狗状态",
                body = "查看周期巡检心跳、失败计数和关键后台任务是否还在排队。",
                badge = state.stateLabel,
            )
        }
        item {
            SettingsCard(title = "巡检状态", body = "后台守护与周期签到的最近一次心跳。") {
                buildWatchdogStatusRows(state).forEach { row ->
                    WatchdogStatusRow(row)
                }
                Button(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text("刷新")
                }
            }
        }
        item {
            SettingsCard(title = "关键任务", body = "需要持续存在的 WorkManager unique work。") {
                if (state.uniqueWorks.isEmpty()) {
                    SettingsEmptyText("暂无关键任务")
                } else {
                    state.uniqueWorks.forEach { work ->
                        WatchdogStatusRow(buildUniqueWorkRowPresentation(work))
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchdogStatusRow(presentation: WatchdogStatusRowPresentation) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = presentation.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(
                text = presentation.badgeLabel,
                tone = presentation.badgeTone,
            )
        }
    }
}

internal data class WatchdogStatusRowPresentation(
    val title: String,
    val detail: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
)

internal fun buildWatchdogStatusRows(
    state: WatchdogStatusUiState,
): List<WatchdogStatusRowPresentation> =
    listOf(
        heartbeatRow(title = "周期签到心跳", heartbeat = state.periodicHeartbeat),
        heartbeatRow(title = "看门狗心跳", heartbeat = state.watchdogHeartbeat),
        failureCountRow(state.failureCount),
        stateLabelRow(state.stateLabel),
    )

internal fun buildUniqueWorkRowPresentation(
    work: UniqueWorkStatus,
): WatchdogStatusRowPresentation =
    WatchdogStatusRowPresentation(
        title = work.name.trim().ifBlank { "未命名任务" },
        detail = work.nextRunLabel.trim().ifBlank { "暂无排程说明" },
        badgeLabel = "已配置",
        badgeTone = StatusTone.Info,
    )

private fun heartbeatRow(
    title: String,
    heartbeat: String,
): WatchdogStatusRowPresentation {
    val detail = heartbeat.trim().ifBlank { "暂无" }
    val hasHeartbeat = detail != "暂无"
    return WatchdogStatusRowPresentation(
        title = title,
        detail = detail,
        badgeLabel = if (hasHeartbeat) "已记录" else "未记录",
        badgeTone = if (hasHeartbeat) StatusTone.Info else StatusTone.Neutral,
    )
}

private fun failureCountRow(failureCount: Int): WatchdogStatusRowPresentation {
    val safeCount = failureCount.coerceAtLeast(0)
    return WatchdogStatusRowPresentation(
        title = "失败计数",
        detail = "$safeCount 次",
        badgeLabel = if (safeCount == 0) "正常" else "$safeCount 次",
        badgeTone = if (safeCount == 0) StatusTone.Positive else StatusTone.Warning,
    )
}

private fun stateLabelRow(stateLabel: String): WatchdogStatusRowPresentation {
    val label = stateLabel.trim().ifBlank { "未知" }
    val healthy = label == "健康"
    return WatchdogStatusRowPresentation(
        title = "状态",
        detail = label,
        badgeLabel = if (healthy) "健康" else "需关注",
        badgeTone = if (healthy) StatusTone.Positive else StatusTone.Warning,
    )
}
