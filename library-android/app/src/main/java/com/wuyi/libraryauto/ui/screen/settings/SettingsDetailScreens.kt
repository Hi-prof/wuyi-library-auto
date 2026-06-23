package com.wuyi.libraryauto.ui.screen.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wuyi.libraryauto.BuildConfig
import com.wuyi.libraryauto.accessibility.WuyiAccessibilityService
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
import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork
import com.wuyi.libraryauto.core.storage.network.WifiReconnectSnapshot
import com.wuyi.libraryauto.core.storage.network.WifiReconnectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BuildInfoScreen() {
    SettingsPageColumn {
        item {
            SettingsSectionCard(
                title = "构建信息",
                body = "用来确认手机当前运行的是不是最新安装包。",
            ) {
                InfoText("versionName: ${BuildConfig.VERSION_NAME}")
                InfoText("versionCode: ${BuildConfig.VERSION_CODE}")
                InfoText("build: ${BuildConfig.BUILD_MARKER}")
            }
        }
    }
}

@Composable
fun WifiReconnectSettingsScreen() {
    val context = LocalContext.current
    val store = remember(context) { WifiReconnectStore(context.applicationContext) }
    val suggestionRegistrar = remember(context) { WifiReconnectSuggestionRegistrar(context.applicationContext) }
    var refreshSignal by rememberSaveable { mutableIntStateOf(0) }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var primarySsid by rememberSaveable { mutableStateOf("") }
    var primaryPassphrase by rememberSaveable { mutableStateOf("") }
    var candidateLines by rememberSaveable { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store, refreshSignal) {
        val snapshot = store.loadSnapshot()
        enabled = snapshot.enabled
        primarySsid = snapshot.primaryNetwork?.ssid.orEmpty()
        primaryPassphrase = snapshot.primaryNetwork?.let(WifiReconnectNetwork::password).orEmpty()
        candidateLines =
            snapshot.candidateNetworks.joinToString("\n") { network ->
                "${network.ssid}|${network.password}"
            }
    }

    fun saveSnapshot() {
        val previousSnapshot = store.loadSnapshot()
        val snapshot =
            WifiReconnectSnapshot(
                enabled = enabled,
                primaryNetwork =
                    primarySsid.trim().takeIf(String::isNotBlank)?.let { ssid ->
                        WifiReconnectNetwork(ssid, primaryPassphrase)
                    },
                candidateNetworks =
                    candidateLines
                        .lineSequence()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .mapNotNull { line ->
                            val parts = line.split('|', limit = 2)
                            val ssid = parts.firstOrNull()?.trim().orEmpty()
                            val passphrase = parts.getOrNull(1)?.trim().orEmpty()
                            if (ssid.isBlank() || passphrase.isBlank()) {
                                null
                            } else {
                                WifiReconnectNetwork(ssid, passphrase)
                            }
                        }.toList(),
            )
        store.saveSnapshot(snapshot)
        refreshSignal += 1
        statusMessage = suggestionRegistrar.syncSuggestions(previousSnapshot, snapshot)
    }

    SettingsPageColumn {
        item {
            SettingsSectionCard(
                title = "Wi-Fi 重连",
                body = "后台自动预约和自动签到断网后，会等待系统按已登记的 Wi-Fi 建议自动恢复连接。密码只保存在本地加密存储里。",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("启用后台 Wi-Fi 重连", style = MaterialTheme.typography.titleMedium)
                        InfoText("保存后会把这些网络登记给系统，实际连回哪个由系统决定。")
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = primarySsid,
                    onValueChange = { primarySsid = it },
                    label = { Text("主 Wi-Fi SSID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = primaryPassphrase,
                    onValueChange = { primaryPassphrase = it },
                    label = { Text("主 Wi-Fi 密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = candidateLines,
                    onValueChange = { candidateLines = it },
                    label = { Text("候选 Wi-Fi 列表") },
                    supportingText = {
                        Text("每行一个，格式：SSID|密码。系统会在这些候选里自动选择可用网络。")
                    },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = ::saveSnapshot,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存配置")
                    }
                    OutlinedButton(
                        onClick = {
                            refreshSignal += 1
                            statusMessage = "已重新读取本地配置"
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重新读取")
                    }
                }
                InfoText("应用不会读取系统里已保存的 Wi-Fi，也不会把明文密码写进日志。")
                statusMessage?.let { message ->
                    InfoText(message)
                }
            }
        }
    }
}

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

    SettingsPageColumn {
        item {
            SettingsSectionCard(
                title = "权限",
                body =
                    if (incompleteCapabilityCount == 0) {
                        "运行时权限和系统授权都已就绪。后台运行时不要手动清掉常驻通知。"
                    } else {
                        "还有 $incompleteCapabilityCount 项未完成，系统可能拦住后台运行或前台服务。"
                    },
            ) {
                capabilityStatuses.forEach { status ->
                    InfoText("${status.title}：${status.detail}")
                }
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
                InfoText("厂商自启动、后台白名单和省电策略需要你自己到系统管家里确认。")
            }
        }
    }
}

@Composable
fun RuntimeGuideScreen() {
    SettingsPageColumn {
        item {
            SettingsSectionCard(
                title = "运行说明",
                body = "自动预约依赖后台存活、前台服务通知和系统不误杀。切到后台后，不要手动清掉常驻通知。",
            ) {
                InfoText("1. 账号页先完成认证，保证每个账号都有可复用登录态。")
                InfoText("2. 创建自动任务后，应用会立即调度首轮执行，不需要等到固定时间。")
                InfoText("3. 如果系统限制后台运行，先到权限页把通知、电池优化和无障碍检查完。")
                InfoText("4. 厂商自启动、后台白名单需要你自己到系统管家里确认。")
            }
        }
    }
}

@Composable
fun AutomationGuideScreen() {
    SettingsPageColumn {
        item {
            SettingsSectionCard(
                title = "自动预约说明",
                body = "默认是持续预约：10 点前会尝试补今天和后两天；10 点后会改为检查明天到大后天，缺哪天就补哪天。也可以改成自定义单次时间段。",
            ) {
                InfoText("目标座位默认来自账号里最近一次成功的手动预约或自动任务配置。")
                InfoText("自动任务弹窗里保留“刷新/查询座位”按钮；只有你主动点了才会查询。")
                InfoText("单次模式需要自己填写日期、开始时间和结束时间。")
            }
        }
    }
}

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

    SettingsPageColumn {
        item {
            SettingsSectionCard(
                title = "诊断日志",
                body =
                    if (diagnosticsSnapshot.entries.isEmpty()) {
                        "这里会统一展示登录诊断、账号动作、座位状态诊断和运行日志，方便一键复制给我排查。"
                    } else {
                        "日志按时间倒序汇总展示，支持一键复制全部诊断内容，也可以一键清空。"
                    },
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
                actionMessage?.let { message -> InfoText(message) }
                if (diagnosticsSnapshot.entries.isEmpty()) {
                    InfoText("暂无日志")
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
private fun SettingsPageColumn(
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SettingsSectionCard(
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                content()
            },
        )
    }
}

@Composable
private fun DiagnosticsLogCard(item: DiagnosticsLogEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
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

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
