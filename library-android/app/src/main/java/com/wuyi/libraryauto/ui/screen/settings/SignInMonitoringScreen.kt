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
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone

@Composable
fun SignInMonitoringScreen(
    source: SignInMonitoringDataSource,
) {
    val factory = remember(source) { SignInMonitoringViewModelFactory(source) }
    val viewModel: SignInMonitoringViewModel = viewModel(factory = factory)
    val state = viewModel.uiState

    SettingsLazyColumn {
        item {
            SettingsHeroCard(
                title = "签到监控",
                body = "查看最近签到审计、蓝牙扫描审计和 24 小时失败聚合。",
                badge = if (state.isLoading) "加载中" else "${state.signInAudits.size} 条签到",
            )
        }
        item {
            SettingsCard(title = "数据刷新", body = "重新读取本机最近审计记录。") {
                Button(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text("刷新")
                }
                if (state.showPlaceholder) {
                    SettingsInfoText("加载中...")
                }
                if (state.emptyMessage.isNotBlank()) {
                    SettingsEmptyText(state.emptyMessage)
                }
            }
        }
        item {
            SettingsCard(title = "签到审计", body = "最多 50 条。") {
                if (state.signInAudits.isEmpty()) {
                    SettingsEmptyText()
                } else {
                    state.signInAudits.forEach { item ->
                        MonitoringAuditRow(buildSignInAuditRowPresentation(item))
                    }
                }
            }
        }
        item {
            SettingsCard(title = "蓝牙扫描审计", body = "最多 50 条。") {
                if (state.beaconScanAudits.isEmpty()) {
                    SettingsEmptyText()
                } else {
                    state.beaconScanAudits.forEach { item ->
                        MonitoringAuditRow(buildBeaconScanAuditRowPresentation(item))
                    }
                }
            }
        }
        item {
            SettingsCard(title = "24 小时失败聚合", body = "按 SignInError 归类。") {
                if (state.errorAggregates.isEmpty()) {
                    SettingsEmptyText()
                } else {
                    state.errorAggregates.forEach { item ->
                        MonitoringAuditRow(buildSignInErrorAggregateRowPresentation(item))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitoringAuditRow(presentation: MonitoringAuditRowPresentation) {
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

internal data class MonitoringAuditRowPresentation(
    val title: String,
    val detail: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
)

internal fun buildSignInAuditRowPresentation(
    item: SignInAuditDisplay,
): MonitoringAuditRowPresentation {
    val result = item.result.trim().ifBlank { "未分类" }
    return MonitoringAuditRowPresentation(
        title = item.studentId.trim().ifBlank { "未知账号" },
        detail = "预约 ${item.bookingId.trim().ifBlank { "-" }} · ${item.triggerSource.trim().ifBlank { "未知来源" }}",
        badgeLabel = result,
        badgeTone = if (result == "成功") StatusTone.Positive else StatusTone.Negative,
    )
}

internal fun buildBeaconScanAuditRowPresentation(
    item: BeaconScanAuditDisplay,
): MonitoringAuditRowPresentation {
    val matchedMinor = item.matchedMinor.trim()
    val hasMatch = matchedMinor.isNotBlank() && matchedMinor != "-"
    return MonitoringAuditRowPresentation(
        title = "预约 ${item.bookingId.trim().ifBlank { "-" }}",
        detail =
            "期望 ${item.expectedMinors.trim().ifBlank { "-" }} · " +
                "看到 ${item.seenMinors.trim().ifBlank { "-" }} · " +
                "耗时 ${item.durationMillis.coerceAtLeast(0L)} ms",
        badgeLabel = if (hasMatch) "命中 $matchedMinor" else "未命中",
        badgeTone = if (hasMatch) StatusTone.Positive else StatusTone.Warning,
    )
}

internal fun buildSignInErrorAggregateRowPresentation(
    item: SignInErrorAggregateDisplay,
): MonitoringAuditRowPresentation {
    val count = item.count.coerceAtLeast(0L)
    return MonitoringAuditRowPresentation(
        title = item.error.trim().ifBlank { "未分类错误" },
        detail = "最近 24 小时内出现 $count 次",
        badgeLabel = "$count 次",
        badgeTone = if (count > 0L) StatusTone.Negative else StatusTone.Neutral,
    )
}
