// 撤回 spec account-pool-tri-sync 12.4 中的 ConnectivityGate 阻塞改造；本地执行不再依赖服务端可达性
package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.core.storage.db.TaskUploadConflictEntity
import com.wuyi.libraryauto.sync.AutomationPlanUploadService
import com.wuyi.libraryauto.sync.AutomationPlanUploadViewModel
import com.wuyi.libraryauto.sync.AutomationPlanUploadViewModelFactory
import com.wuyi.libraryauto.sync.ManualSyncCoverageViewModel
import com.wuyi.libraryauto.sync.ManualSyncCoverageViewModelFactory
import com.wuyi.libraryauto.sync.SyncButtonState
import com.wuyi.libraryauto.sync.SyncCandidate
import com.wuyi.libraryauto.sync.SyncKind
import com.wuyi.libraryauto.sync.SyncSelection
import com.wuyi.libraryauto.sync.SyncStatusIndicator
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.navigation.AppDependencies
import kotlinx.coroutines.launch

/**
 * 任务 12.14：「服务端同步」设置页。
 *
 * 页面结构：
 * 1. 顶部说明卡片：解释 Manual_Sync_Action 的工作机制。
 * 2. 服务端连接配置：保存 base_url / bearer_token / upload_enabled。
 * 3. 状态指示卡片：展示当前 [SyncStatusIndicator.syncButtonState] 三态文案。
 * 4. 「从服务端同步活跃池」按钮：按三态置灰；可点击时触发 [ManualSyncCoverageViewModel.trigger]。
 * 5. Sync_Coverage_Confirmation 对话框：当 ViewModel 状态进入 ConfirmationOpen 时弹出。
 *
 * UI 不直接调用网络层；所有交互通过 [ManualSyncCoverageViewModel] 驱动。
 *
 * _Requirements: 13.1, 13.3, 13.4, 13.5, 13.7, 13.8, 13.9, 13.10, 13.11, 13.12, 13.13, 13.15, 13.16_
 */
@Composable
internal fun ServerSyncScreen(
    dependencies: AppDependencies,
) {
    val factory =
        remember(dependencies) {
            ManualSyncCoverageViewModelFactory(
                serverSyncConfig = dependencies.serverSyncConfig,
                repository = dependencies.accountPoolSyncRepository,
                localAccountStore = dependencies.roomLocalAccountStore,
                indicator = dependencies.syncStatusIndicator,
            )
        }
    val viewModel: ManualSyncCoverageViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val buttonState by dependencies.syncStatusIndicator.syncButtonState.collectAsStateWithLifecycle()
    val uploadFactory =
        remember(dependencies) {
            AutomationPlanUploadViewModelFactory(
                service = dependencies.automationPlanUploadService,
                pendingDao = dependencies.pendingTaskUploadDao,
                conflictDao = dependencies.taskUploadConflictDao,
            )
        }
    val uploadViewModel: AutomationPlanUploadViewModel =
        viewModel(factory = uploadFactory, key = "automation-plan-upload")
    val uploadState by uploadViewModel.state.collectAsStateWithLifecycle()
    val pendingQueue by uploadViewModel.pendingQueue.collectAsStateWithLifecycle()
    val conflicts by uploadViewModel.conflicts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val serverSyncConfig = dependencies.serverSyncConfig
    var baseUrlInput by rememberSaveable { mutableStateOf(serverSyncConfig.baseUrl.orEmpty()) }
    var bearerTokenInput by rememberSaveable { mutableStateOf(serverSyncConfig.bearerToken.orEmpty()) }
    var uploadEnabledInput by rememberSaveable { mutableStateOf(serverSyncConfig.uploadEnabled) }

    fun reloadConfigInputs() {
        baseUrlInput = serverSyncConfig.baseUrl.orEmpty()
        bearerTokenInput = serverSyncConfig.bearerToken.orEmpty()
        uploadEnabledInput = serverSyncConfig.uploadEnabled
    }

    fun showConfigSnackbar(message: String) {
        snackbarScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    fun saveServerConfig() {
        serverSyncConfig.saveAll(
            baseUrl = baseUrlInput,
            bearerToken = bearerTokenInput,
            verifyTls = serverSyncConfig.verifyTls,
            uploadEnabled = uploadEnabledInput,
        )
        dependencies.accountPoolSyncRepository.invalidateApiCache()
        dependencies.syncStatusIndicator.updateConfigState(serverSyncConfig.isConfigured())
        reloadConfigInputs()
        val message =
            if (serverSyncConfig.isConfigured()) {
                "已保存服务端配置"
            } else {
                "已保存；base_url 或 Bearer Token 为空，当前仍为本地模式"
            }
        showConfigSnackbar(message)
    }

    fun clearServerConfig() {
        serverSyncConfig.clear()
        dependencies.accountPoolSyncRepository.invalidateApiCache()
        dependencies.syncStatusIndicator.updateConfigState(configured = false)
        reloadConfigInputs()
        showConfigSnackbar("已清除服务端配置")
    }

    LaunchedEffect(event) {
        val current = event ?: return@LaunchedEffect
        when (current) {
            is ManualSyncCoverageViewModel.Event.Snackbar ->
                snackbarHostState.showSnackbar(message = current.message)
        }
        viewModel.consumeEvent()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        SettingsLazyColumn(modifier = Modifier.padding(innerPadding)) {
            item {
                val presentation =
                    serverConnectionPresentation(
                        configured = serverSyncConfig.isConfigured(),
                        uploadEnabled = serverSyncConfig.isUploadEnabled(),
                    )
                SettingsHeroCard(
                    title = "服务端同步",
                    body = "维护服务端连接、手动拉取活跃账号池，并按需上传本地自动任务。",
                    badge = presentation.badge,
                )
            }
            item {
                ServerConnectionConfigCard(
                    baseUrl = baseUrlInput,
                    bearerToken = bearerTokenInput,
                    uploadEnabled = uploadEnabledInput,
                    configured = serverSyncConfig.isConfigured(),
                    onBaseUrlChange = { baseUrlInput = it },
                    onBearerTokenChange = { bearerTokenInput = it },
                    onUploadEnabledChange = { uploadEnabledInput = it },
                    onSave = ::saveServerConfig,
                    onClear = ::clearServerConfig,
                )
            }
            item {
                SyncStatusCard(
                    buttonState = buttonState,
                    isLoading = state is ManualSyncCoverageViewModel.State.Loading ||
                        state is ManualSyncCoverageViewModel.State.Applying,
                )
            }
            item {
                ManualSyncButton(
                    buttonState = buttonState,
                    isLoading = state is ManualSyncCoverageViewModel.State.Loading ||
                        state is ManualSyncCoverageViewModel.State.Applying,
                    onClick = viewModel::trigger,
                )
            }
            item {
                AutomationPlanUploadCard(
                    state = uploadState,
                    pendingCount = pendingQueue.size,
                    conflictCount = conflicts.size,
                    uploadEnabled = serverSyncConfig.isUploadEnabled(),
                    onUpload = uploadViewModel::uploadAllPlans,
                    onDismiss = uploadViewModel::dismissResult,
                )
            }
            if (conflicts.isNotEmpty()) {
                item {
                    ConflictListCard(
                        conflicts = conflicts,
                        onDelete = uploadViewModel::deleteConflict,
                    )
                }
            }
        }
    }

    val confirmationState = state as? ManualSyncCoverageViewModel.State.ConfirmationOpen
    if (confirmationState != null) {
        SyncCoverageConfirmationSheet(
            candidates = confirmationState.candidates,
            selection = confirmationState.selection,
            onToggle = viewModel::toggleSelection,
            onSelectAll = viewModel::selectAll,
            onClearAll = viewModel::clearAll,
            onInvertAll = viewModel::invertAll,
            onConfirm = viewModel::confirm,
            onCancel = viewModel::cancel,
        )
    }
}

@Composable
private fun ServerConnectionConfigCard(
    baseUrl: String,
    bearerToken: String,
    uploadEnabled: Boolean,
    configured: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onUploadEnabledChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val presentation = serverConnectionPresentation(configured = configured, uploadEnabled = uploadEnabled)
    SettingsCard(
        title = "服务端连接",
        body = "保存 base_url、Bearer Token 和上行同步开关。",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = presentation.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
                SettingsInfoText(presentation.detail)
            }
            SettingsStatusPill(text = presentation.badge)
        }
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("服务端 URL（base_url）") },
            placeholder = { Text("https://server.example.com") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bearerToken,
            onValueChange = onBearerTokenChange,
            label = { Text("Bearer Token") },
            placeholder = { Text("服务端“客户端 Token”页面生成的 Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        ConfigSwitchRow(
            title = "允许上行同步",
            summary = "上传账号任务变更和拉黑事件；关闭时仍可手动拉取活跃池。",
            checked = uploadEnabled,
            onCheckedChange = onUploadEnabledChange,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f),
            ) {
                Text("清除配置")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
            ) {
                Text("保存配置")
            }
        }
    }
}

@Composable
private fun ConfigSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SyncStatusCard(
    buttonState: SyncButtonState,
    isLoading: Boolean,
) {
    val presentation = syncActionPresentation(buttonState = buttonState, isLoading = isLoading)
    SettingsCard(
        title = "活跃池同步",
        body = "从服务端拉取候选账号，二次确认后再覆盖本地账号池。",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = presentation.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                SettingsInfoText(presentation.detail)
            }
            SettingsStatusPill(text = presentation.badge)
        }
    }
}

@Composable
private fun ManualSyncButton(
    buttonState: SyncButtonState,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    val presentation = syncActionPresentation(buttonState = buttonState, isLoading = isLoading)
    Button(
        onClick = onClick,
        enabled = presentation.enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.heightIn(min = 18.dp).width(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Box(modifier = Modifier.width(8.dp))
        }
        Text(text = presentation.buttonLabel)
    }
}

/**
 * Sync_Coverage_Confirmation 底部面板。
 *
 * 在弹窗外部维护 [SyncSelection]，UI 上对每条候选行级勾选；提供「全选 / 全不选 / 反选」
 * 三个快捷按钮；底部「确认覆盖 / 取消」按钮。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SyncCoverageConfirmationSheet(
    candidates: List<SyncCandidate>,
    selection: SyncSelection,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onInvertAll: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val presentation =
        syncCoverageConfirmationPresentation(
            candidates = candidates,
            selection = selection,
        )

    ModalBottomSheet(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = presentation.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = presentation.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(
                    text = presentation.badgeLabel,
                    tone = presentation.badgeTone,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = onSelectAll, modifier = Modifier.weight(1f)) {
                    Text(presentation.selectAllAction.label)
                }
                OutlinedButton(onClick = onClearAll, modifier = Modifier.weight(1f)) {
                    Text(presentation.clearAllAction.label)
                }
                OutlinedButton(onClick = onInvertAll, modifier = Modifier.weight(1f)) {
                    Text(presentation.invertAllAction.label)
                }
            }
            HorizontalDivider()
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(
                    items = presentation.candidates,
                    key = { candidate -> candidate.key },
                ) { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        onToggle = { onToggle(candidate.studentId) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(presentation.dismissAction.label)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(presentation.confirmAction.label)
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: SyncCoverageCandidatePresentation,
    onToggle: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(checked = candidate.checked, onCheckedChange = { onToggle() })
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (candidate.displayName.isNotBlank()) {
                    Text(
                        text = candidate.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = candidate.taskCountLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
private fun AutomationPlanUploadCard(
    state: AutomationPlanUploadViewModel.State,
    pendingCount: Int,
    conflictCount: Int,
    uploadEnabled: Boolean,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isUploading = state is AutomationPlanUploadViewModel.State.Uploading
    val buttonLabel = when {
        isUploading -> "上传中…"
        !uploadEnabled -> "未启用上行同步"
        else -> "上传本地自动任务"
    }
    SettingsCard(
        title = "上传本地自动任务",
        body = "把本地计划写入上传队列；遇到版本冲突时会在下方单独展示。",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsStatusPill(text = "待上传 $pendingCount")
            SettingsStatusPill(text = "冲突 $conflictCount")
        }
        Button(
            onClick = onUpload,
            enabled = uploadEnabled && !isUploading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isUploading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.heightIn(min = 18.dp).width(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Box(modifier = Modifier.width(8.dp))
            }
            Text(text = buttonLabel)
        }
        UploadResultBlock(state = state, onDismiss = onDismiss)
    }
}

@Composable
private fun UploadResultBlock(
    state: AutomationPlanUploadViewModel.State,
    onDismiss: () -> Unit,
) {
    when (state) {
        AutomationPlanUploadViewModel.State.Idle,
        AutomationPlanUploadViewModel.State.Uploading -> Unit

        AutomationPlanUploadViewModel.State.Empty ->
            UploadResultRow(
                title = "本地没有自动任务",
                detail = "请先在「自动任务」页面创建至少一条计划。",
                onDismiss = onDismiss,
            )

        is AutomationPlanUploadViewModel.State.Error ->
            UploadResultRow(
                title = "上传失败",
                detail = state.message,
                onDismiss = onDismiss,
                error = true,
            )

        is AutomationPlanUploadViewModel.State.Completed -> {
            val summary =
                "已入队 ${state.enqueued} 条，跳过 ${state.skipped} 条，拒绝 ${state.rejected} 条。" +
                    if (state.rejected > 0) "可在下方明细查看原因。" else ""
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UploadResultRow(
                    title = "已写入上传队列",
                    detail = summary,
                    onDismiss = onDismiss,
                )
                if (state.items.any { it.status != AutomationPlanUploadService.PlanItemResult.Status.ENQUEUED }) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = 220.dp),
                    ) {
                        state.items
                            .filter { it.status != AutomationPlanUploadService.PlanItemResult.Status.ENQUEUED }
                            .forEach { item ->
                                Text(
                                    text = "- ${item.studentId} ${item.roomName} ${item.seatNumber} · " +
                                        "${item.status.name.lowercase()}${item.reason?.let { "（$it）" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadResultRow(
    title: String,
    detail: String,
    onDismiss: () -> Unit,
    error: Boolean = false,
) {
    Surface(
        color =
            if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        if (error) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (error) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("收起") }
        }
    }
}

@Composable
private fun ConflictListCard(
    conflicts: List<TaskUploadConflictEntity>,
    onDelete: (String) -> Unit,
) {
    SettingsCard(
        title = "上传冲突（${conflicts.size}）",
        body = "服务端任务版本比本地新；可以先忽略该条，再回到自动任务页面修改后重新上传。",
    ) {
        conflicts.forEach { conflict ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "账号 #${conflict.accountId} · 任务 #${conflict.taskId}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "本地 revision=${conflict.localRevision}，服务端 revision=${conflict.serverRevision}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    TextButton(onClick = { onDelete(conflict.conflictHash) }) {
                        Text("忽略")
                    }
                }
            }
        }
    }
}

internal data class ServerConnectionPresentation(
    val label: String,
    val detail: String,
    val badge: String,
)

internal fun serverConnectionPresentation(
    configured: Boolean,
    uploadEnabled: Boolean,
): ServerConnectionPresentation =
    when {
        !configured ->
            ServerConnectionPresentation(
                label = "未配置完整",
                detail = "填写服务端 URL 和 Bearer Token 后才能连接服务端。",
                badge = "本地模式",
            )

        uploadEnabled ->
            ServerConnectionPresentation(
                label = "已连接服务端",
                detail = "可以手动拉取活跃池，并允许上传本地自动任务变更。",
                badge = "双向同步",
            )

        else ->
            ServerConnectionPresentation(
                label = "已配置服务端",
                detail = "可以手动拉取活跃池；上行同步关闭时不会上传本地任务变更。",
                badge = "仅手动拉取",
            )
    }

internal data class SyncActionPresentation(
    val label: String,
    val detail: String,
    val buttonLabel: String,
    val enabled: Boolean,
    val badge: String,
)

internal fun syncActionPresentation(
    buttonState: SyncButtonState,
    isLoading: Boolean,
): SyncActionPresentation =
    when (buttonState) {
        is SyncButtonState.Enabled ->
            if (isLoading) {
                SyncActionPresentation(
                    label = "服务端同步中",
                    detail = "正在拉取候选账号并准备二次确认。",
                    buttonLabel = "同步中…",
                    enabled = false,
                    badge = "处理中",
                )
            } else {
                SyncActionPresentation(
                    label = "服务端可达",
                    detail = "可以触发 Manual_Sync_Action 拉取活跃池清单。",
                    buttonLabel = "从服务端同步活跃池",
                    enabled = true,
                    badge = "可同步",
                )
            }

        is SyncButtonState.DisabledUnconfigured ->
            SyncActionPresentation(
                label = "未配置服务端",
                detail = "请先在本页的「服务端连接」中填入 base_url 与 bearer_token。",
                buttonLabel = "未配置服务端",
                enabled = false,
                badge = "本地模式",
            )

        is SyncButtonState.DisabledUnreachable ->
            SyncActionPresentation(
                label = "服务端不可达",
                detail = "最近一次同步失败：${buttonState.reason}。本地业务流程不受影响。",
                buttonLabel = "服务端不可达",
                enabled = false,
                badge = "不可达",
            )
    }

internal data class SyncCoverageConfirmationPresentation(
    val title: String,
    val summary: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
    val selectAllAction: SyncCoverageConfirmationActionPresentation,
    val clearAllAction: SyncCoverageConfirmationActionPresentation,
    val invertAllAction: SyncCoverageConfirmationActionPresentation,
    val confirmAction: SyncCoverageConfirmationActionPresentation,
    val dismissAction: SyncCoverageConfirmationActionPresentation,
    val candidates: List<SyncCoverageCandidatePresentation>,
)

internal data class SyncCoverageConfirmationActionPresentation(
    val label: String,
)

internal data class SyncCoverageCandidatePresentation(
    val key: String,
    val studentId: String,
    val title: String,
    val displayName: String,
    val taskCountLabel: String,
    val checked: Boolean,
)

internal fun syncCoverageConfirmationPresentation(
    candidates: List<SyncCandidate>,
    selection: SyncSelection,
): SyncCoverageConfirmationPresentation {
    val addCount = candidates.count { candidate -> candidate.kind == SyncKind.Add }
    val replaceCount = candidates.count { candidate -> candidate.kind == SyncKind.Replace }
    val removeCount = candidates.count { candidate -> candidate.kind == SyncKind.Remove }
    val checkedCount = candidates.count { candidate -> selection[candidate.studentId] == true }
    val totalCount = candidates.size
    return SyncCoverageConfirmationPresentation(
        title = "确认同步",
        summary = "本次同步将覆盖：新增 $addCount、替换 $replaceCount、移除 $removeCount。已勾选 $checkedCount / $totalCount。",
        badgeLabel = "已选 $checkedCount/$totalCount",
        badgeTone = StatusTone.Warning,
        selectAllAction = SyncCoverageConfirmationActionPresentation(label = "全选"),
        clearAllAction = SyncCoverageConfirmationActionPresentation(label = "全不选"),
        invertAllAction = SyncCoverageConfirmationActionPresentation(label = "反选"),
        confirmAction = SyncCoverageConfirmationActionPresentation(label = "确认覆盖"),
        dismissAction = SyncCoverageConfirmationActionPresentation(label = "取消"),
        candidates =
            candidates.map { candidate ->
                SyncCoverageCandidatePresentation(
                    key = candidate.studentId + candidate.kind.javaClass.simpleName,
                    studentId = candidate.studentId,
                    title = "${candidate.kind.toSyncCoverageLabel()} · ${candidate.studentId}",
                    displayName =
                        candidate.serverPayload?.displayName?.takeIf(String::isNotBlank)
                            ?: candidate.localSummary?.displayName?.takeIf(String::isNotBlank)
                            ?: "",
                    taskCountLabel = "关联自动任务：${candidate.taskCount()} 项",
                    checked = selection[candidate.studentId] == true,
                )
            },
    )
}

private fun SyncKind.toSyncCoverageLabel(): String =
    when (this) {
        SyncKind.Add -> "新增"
        SyncKind.Replace -> "替换"
        SyncKind.Remove -> "移除"
    }

private fun SyncCandidate.taskCount(): Int =
    serverPayload?.automationTasks?.size
        ?: localSummary?.automationTaskCount
        ?: 0
