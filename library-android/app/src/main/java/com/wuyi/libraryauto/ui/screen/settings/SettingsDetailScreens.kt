package com.wuyi.libraryauto.ui.screen.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wuyi.libraryauto.BuildConfig
import com.wuyi.libraryauto.accessibility.WuyiAccessibilityService
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.permission.CapabilityStatus
import com.wuyi.libraryauto.ui.permission.applicationDetailsSettingsIntent
import com.wuyi.libraryauto.ui.permission.areNotificationsEnabled
import com.wuyi.libraryauto.ui.permission.buildCapabilityStatuses
import com.wuyi.libraryauto.ui.permission.canScheduleExactAlarms
import com.wuyi.libraryauto.ui.permission.ignoreBatteryOptimizationSettingsIntent
import com.wuyi.libraryauto.ui.permission.isAccessibilityServiceEnabled
import com.wuyi.libraryauto.ui.permission.isBatteryOptimizationIgnored
import com.wuyi.libraryauto.ui.permission.missingRuntimePermissions
import com.wuyi.libraryauto.ui.permission.notificationSettingsIntent
import com.wuyi.libraryauto.ui.permission.requestIgnoreBatteryOptimizationsIntent
import com.wuyi.libraryauto.ui.permission.requestScheduleExactAlarmIntent
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogEntry
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogRepository
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BuildInfoScreen() {
    SettingsLazyColumn {
        item {
            SettingsHeroCard(
                title = "构建信息",
                body = "确认手机当前运行的安装包版本、构建号和发布时间标记。",
                badge = BuildConfig.VERSION_NAME,
            )
        }
        item {
            SettingsCard(
                title = "安装包详情",
                body = "遇到兼容、同步或运行问题时先核对这些信息。",
            ) {
                listOf(
                    buildBuildInfoRowPresentation("versionName", BuildConfig.VERSION_NAME),
                    buildBuildInfoRowPresentation("versionCode", BuildConfig.VERSION_CODE.toString()),
                    buildBuildInfoRowPresentation("build", BuildConfig.BUILD_MARKER),
                ).forEach { row ->
                    BuildInfoRow(row)
                }
            }
        }
    }
}

@Composable
private fun BuildInfoRow(presentation: BuildInfoRowPresentation) {
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

internal data class BuildInfoRowPresentation(
    val title: String,
    val detail: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
)

internal fun buildBuildInfoRowPresentation(
    label: String,
    value: String,
): BuildInfoRowPresentation =
    BuildInfoRowPresentation(
        title = label.trim().ifBlank { "构建字段" },
        detail = value.trim().ifBlank { "未知" },
        badgeLabel = "元数据",
        badgeTone = StatusTone.Neutral,
    )

@Composable
fun PermissionsScreen() {
    val context = LocalContext.current
    var refreshSignal by rememberSaveable { mutableIntStateOf(0) }
    val missingPermissions = remember(context, refreshSignal) { missingRuntimePermissions(context) }
    val accessibilityEnabled =
        remember(context, refreshSignal) {
            isAccessibilityServiceEnabled(context, WuyiAccessibilityService::class.java)
        }
    val notificationsEnabled = remember(context, refreshSignal) { areNotificationsEnabled(context) }
    val batteryOptimizationIgnored =
        remember(context, refreshSignal) { isBatteryOptimizationIgnored(context) }
    val exactAlarmAllowed =
        remember(context, refreshSignal) { canScheduleExactAlarms(context) }
    val capabilityStatuses =
        remember(
            missingPermissions,
            accessibilityEnabled,
            notificationsEnabled,
            batteryOptimizationIgnored,
            exactAlarmAllowed,
        ) {
            buildCapabilityStatuses(
                missingRuntimePermissions = missingPermissions,
                accessibilityEnabled = accessibilityEnabled,
                notificationsEnabled = notificationsEnabled,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
                exactAlarmAllowed = exactAlarmAllowed,
            )
        }
    val incompleteCapabilityCount = capabilityStatuses.count { status -> !status.ready }
    val runtimePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            refreshSignal += 1
        }

    LifecycleResumeEffect(Unit) {
        refreshSignal += 1
        onPauseOrDispose {}
    }

    fun openSettings(vararg intents: Intent) {
        var lastError: Throwable? = null
        intents.forEach { intent ->
            val result = runCatching { context.startActivity(intent) }
            if (result.isSuccess) {
                return
            }
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("无法打开系统设置")
    }

    SettingsLazyColumn {
        item {
            SettingsHeroCard(
                title = "权限状态",
                body =
                    if (incompleteCapabilityCount == 0) {
                        "运行时权限和系统授权都已就绪。后台运行时不要手动清掉常驻通知。"
                    } else {
                        "还有 $incompleteCapabilityCount 项未完成，系统可能拦住后台运行或前台服务。"
                    },
                badge = if (incompleteCapabilityCount == 0) "已就绪" else "待处理 $incompleteCapabilityCount 项",
            )
        }
        item {
            SettingsCard(
                title = "授权检查",
                body = "逐项确认后台运行、提醒、通知和自动操作依赖的系统能力。",
            ) {
                capabilityStatuses.forEach { status ->
                    PermissionStatusRow(buildPermissionStatusRowPresentation(status))
                }
            }
        }
        item {
            SettingsCard(
                title = "系统入口",
                body = "跳转到 Android 系统设置补齐权限；返回本页后状态会自动刷新。",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            if (missingPermissions.isNotEmpty()) {
                                runtimePermissionLauncher.launch(missingPermissions.toTypedArray())
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("申请运行时权限")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("无障碍入口")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            openSettings(notificationSettingsIntent(context), applicationDetailsSettingsIntent(context))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("通知设置")
                    }
                    OutlinedButton(
                        onClick = {
                            if (batteryOptimizationIgnored) {
                                openSettings(
                                    ignoreBatteryOptimizationSettingsIntent(),
                                    applicationDetailsSettingsIntent(context),
                                )
                            } else {
                                openSettings(
                                    requestIgnoreBatteryOptimizationsIntent(context),
                                    ignoreBatteryOptimizationSettingsIntent(),
                                    applicationDetailsSettingsIntent(context),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("电池优化")
                    }
                }
                OutlinedButton(
                    onClick = {
                        openSettings(applicationDetailsSettingsIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("应用设置")
                }
                OutlinedButton(
                    onClick = {
                        val exactAlarmIntent = requestScheduleExactAlarmIntent(context)
                        if (exactAlarmIntent != null) {
                            openSettings(exactAlarmIntent, applicationDetailsSettingsIntent(context))
                        } else {
                            openSettings(applicationDetailsSettingsIntent(context))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("精确闹钟入口")
                }
                PermissionStatusRow(buildManualPermissionHintPresentation())
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(presentation: PermissionStatusRowPresentation) {
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

internal data class PermissionStatusRowPresentation(
    val title: String,
    val detail: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
)

internal fun buildPermissionStatusRowPresentation(
    status: CapabilityStatus,
): PermissionStatusRowPresentation =
    PermissionStatusRowPresentation(
        title = status.title.trim().ifBlank { "未命名能力" },
        detail = status.detail.trim().ifBlank { "暂无说明" },
        badgeLabel = if (status.ready) "已就绪" else "待处理",
        badgeTone = if (status.ready) StatusTone.Positive else StatusTone.Warning,
    )

internal fun buildManualPermissionHintPresentation(): PermissionStatusRowPresentation =
    PermissionStatusRowPresentation(
        title = "厂商后台策略",
        detail = "厂商自启动、后台白名单和省电策略需要你自己到系统管家里确认。",
        badgeLabel = "需手动确认",
        badgeTone = StatusTone.Warning,
    )

@Composable
fun RuntimeGuideScreen() {
    SettingsLazyColumn {
        item {
            SettingsHeroCard(
                title = "运行说明",
                body = "自动预约依赖后台存活、前台服务通知和系统不误杀。切到后台后，不要手动清掉常驻通知。",
                badge = "后台运行",
            )
        }
        item {
            SettingsCard(
                title = "运行前检查",
                body = "按顺序完成这些动作，可以减少任务漏跑和后台被系统清理的情况。",
            ) {
                buildRuntimeGuideStepPresentations().forEach { step ->
                    RuntimeGuideStepRow(step)
                }
            }
        }
    }
}

@Composable
private fun RuntimeGuideStepRow(presentation: RuntimeGuideStepPresentation) {
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
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = presentation.number,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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

internal data class RuntimeGuideStepPresentation(
    val number: String,
    val title: String,
    val detail: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
)

internal fun buildRuntimeGuideStepPresentations(): List<RuntimeGuideStepPresentation> =
    listOf(
        RuntimeGuideStepPresentation(
            number = "1",
            title = "账号登录态",
            detail = "账号页先完成认证，保证每个账号都有可复用登录态。",
            badgeLabel = "运行前",
            badgeTone = StatusTone.Info,
        ),
        RuntimeGuideStepPresentation(
            number = "2",
            title = "首轮调度",
            detail = "创建自动任务后，应用会立即调度首轮执行，不需要等到固定时间。",
            badgeLabel = "运行前",
            badgeTone = StatusTone.Info,
        ),
        RuntimeGuideStepPresentation(
            number = "3",
            title = "系统权限",
            detail = "如果系统限制后台运行，先到权限页把通知、电池优化和无障碍检查完。",
            badgeLabel = "运行前",
            badgeTone = StatusTone.Info,
        ),
        RuntimeGuideStepPresentation(
            number = "4",
            title = "厂商白名单",
            detail = "厂商自启动、后台白名单需要你自己到系统管家里确认。",
            badgeLabel = "运行前",
            badgeTone = StatusTone.Info,
        ),
    )

@Composable
fun AutomationGuideScreen() {
    SettingsLazyColumn {
        item {
            SettingsHeroCard(
                title = "自动预约说明",
                body = "默认是持续预约：10 点前会尝试补今天和后两天；10 点后会改为检查明天到大后天，缺哪天就补哪天。也可以改成自定义单次时间段。",
                badge = "持续预约",
            )
        }
        item {
            SettingsCard(
                title = "预约规则",
                body = "这些规则影响自动任务默认目标和查询行为。",
            ) {
                buildAutomationGuideRulePresentations().forEach { rule ->
                    AutomationGuideRuleRow(rule)
                }
            }
        }
    }
}

@Composable
private fun AutomationGuideRuleRow(presentation: AutomationGuideRulePresentation) {
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

internal data class AutomationGuideRulePresentation(
    val title: String,
    val detail: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
)

internal fun buildAutomationGuideRulePresentations(): List<AutomationGuideRulePresentation> =
    listOf(
        AutomationGuideRulePresentation(
            title = "目标座位",
            detail = "默认来自账号里最近一次成功的手动预约或自动任务配置。",
            badgeLabel = "预约规则",
            badgeTone = StatusTone.Info,
        ),
        AutomationGuideRulePresentation(
            title = "座位查询",
            detail = "自动任务弹窗里保留“刷新/查询座位”按钮；只有你主动点了才会查询。",
            badgeLabel = "预约规则",
            badgeTone = StatusTone.Info,
        ),
        AutomationGuideRulePresentation(
            title = "单次模式",
            detail = "需要自己填写日期、开始时间和结束时间。",
            badgeLabel = "预约规则",
            badgeTone = StatusTone.Info,
        ),
    )

@Composable
fun DiagnosticsScreen(
    diagnosticsLogRepository: DiagnosticsLogRepository,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var refreshSignal by rememberSaveable { mutableIntStateOf(0) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticsSnapshot by remember { mutableStateOf(DiagnosticsLogSnapshot(emptyList())) }

    LaunchedEffect(refreshSignal) {
        diagnosticsSnapshot = diagnosticsLogRepository.loadSnapshot()
        if (actionMessage == "清空中...") {
            actionMessage = "日志已清空"
        }
    }

    suspend fun clearLogs() {
        withContext(Dispatchers.IO) {
            diagnosticsLogRepository.clearAll()
        }
        refreshSignal += 1
    }

    SettingsLazyColumn {
        item {
            SettingsHeroCard(
                title = "诊断日志",
                body =
                    if (diagnosticsSnapshot.entries.isEmpty()) {
                        "这里会统一展示登录诊断、账号动作、座位状态诊断和运行日志，方便一键复制给我排查。"
                    } else {
                        "日志按时间倒序汇总展示，支持一键复制全部诊断内容，也可以一键清空。"
                    },
                badge = "${diagnosticsSnapshot.entries.size} 条",
            )
        }
        item {
            SettingsCard(
                title = "日志操作",
                body = "复制或分享给排查人员；清空只影响本机已缓存的诊断日志。",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            actionMessage =
                                diagnosticsLogRepository.buildCopyText(diagnosticsSnapshot)?.let { text ->
                                    clipboardManager.setText(AnnotatedString(text))
                                    "诊断日志已复制"
                                } ?: "当前没有可复制的日志"
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("一键复制日志")
                    }
                    OutlinedButton(
                        onClick = {
                            val copyText = diagnosticsLogRepository.buildCopyText(diagnosticsSnapshot)
                            shareDiagnosticsLog(context, copyText)
                            actionMessage =
                                if (copyText.isNullOrBlank()) {
                                    "当前没有可分享的日志"
                                } else {
                                    "已打开系统分享入口"
                                }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("分享日志")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { refreshSignal += 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("刷新日志")
                    }
                    Button(
                        onClick = {
                            actionMessage = "清空中..."
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("一键清空全部")
                    }
                }
                if (actionMessage == "清空中...") {
                    LaunchedEffect(actionMessage) {
                        clearLogs()
                    }
                }
                actionMessage?.let { message -> SettingsInfoText(message) }
            }
        }
        item {
            SettingsCard(
                title = "日志明细",
                body = "按时间倒序展示最近记录。",
            ) {
                if (diagnosticsSnapshot.entries.isEmpty()) {
                    SettingsEmptyText("暂无日志")
                } else {
                    diagnosticsSnapshot.entries.forEach { item -> DiagnosticsLogCard(item) }
                }
            }
        }
    }
}

private fun shareDiagnosticsLog(
    context: android.content.Context,
    text: String?,
) {
    if (text.isNullOrBlank()) {
        Toast.makeText(context, "当前没有可分享的日志", Toast.LENGTH_SHORT).show()
        return
    }
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "wuyi-library-auto 诊断日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享诊断日志"))
    }.onFailure {
        Toast.makeText(context, "当前设备没有可用分享入口", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DiagnosticsLogCard(item: DiagnosticsLogEntry) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = item.timestampLabel, style = MaterialTheme.typography.labelLarge)
            Text(
                text = "${item.sourceLabel} · ${item.title}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            item.detailLines.forEach { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
