// 撤回 spec account-pool-tri-sync 12.4 中的 ConnectivityGate 阻塞改造；本地执行不再依赖服务端可达性
package com.wuyi.libraryauto.ui.screen.account

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.BuildConfig
import com.wuyi.libraryauto.R
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.account.AccountExportRequest
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.AccountStatusRepository
import com.wuyi.libraryauto.ui.viewmodel.AccountBookingEntry
import com.wuyi.libraryauto.ui.viewmodel.AccountManagementViewModel
import com.wuyi.libraryauto.ui.viewmodel.AccountManagementViewModelFactory
import com.wuyi.libraryauto.ui.viewmodel.AccountRefreshAllState
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInProgressWriter
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInRowState
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInRowStatus
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInRunner
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInState
import com.wuyi.libraryauto.ui.viewmodel.LoginGateway
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    accountRepository: SavedAccountRepository,
    loginGateway: LoginGateway,
    sessionRepository: SessionRepository,
    accountStatusRepository: AccountStatusRepository,
    accountSeatActionExecutor: AccountSeatActionExecutor? = null,
    batchCheckInRunner: BatchCheckInRunner? = null,
    batchProgressWriter: BatchCheckInProgressWriter = BatchCheckInProgressWriter.Noop,
    onOpenAddAccount: () -> Unit,
    onOpenTasksForAccount: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val factory =
        remember(
            accountRepository,
            loginGateway,
            sessionRepository,
            accountStatusRepository,
            accountSeatActionExecutor,
            batchCheckInRunner,
            batchProgressWriter,
        ) {
            AccountManagementViewModelFactory(
                accountRepository = accountRepository,
                loginGateway = loginGateway,
                sessionRepository = sessionRepository,
                accountStatusRepository = accountStatusRepository,
                accountSeatActionExecutor = accountSeatActionExecutor,
                batchCheckInRunner = batchCheckInRunner,
                batchProgressWriter = batchProgressWriter,
            )
        }
    val viewModel: AccountManagementViewModel = viewModel(factory = factory)
    val uiState = viewModel.uiState
    val context = LocalContext.current
    val selection = uiState.selection
    val selectedIds = selection.selectedIds()
    val isMultiSelectMode = selection.isMultiSelectMode()
    val visibleStudentIds = uiState.visibleStudentIds.toSet()
    val visibleAccounts = uiState.accounts.filter { account -> account.studentId in visibleStudentIds }
    val pendingDeleteAccount =
        uiState.pendingDeleteStudentId?.let { studentId ->
            uiState.accounts.firstOrNull { account -> account.studentId == studentId }
        }
    val pendingBulkDeleteTargets = (selection as? AccountSelectionState.ConfirmDelete)?.targets.orEmpty()
    val isAnyBatchActive =
        uiState.refreshAllState is AccountRefreshAllState.Running ||
            uiState.batchCheckInState is BatchCheckInState.Running

    BackHandler(enabled = isMultiSelectMode) {
        viewModel.exitMultiSelectMode()
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    // 仅重载本地账号列表（学号 / 密码 / 偏好等持久化字段），不触发任何网络请求。
                    // 预约状态刷新只走两个入口：用户手动点「一键刷新所有账号」，或每天定时调度
                    // （07:30 / 08:10），避免同 IP 频繁请求被学校接口检测。
                    viewModel.refreshAccounts()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (isMultiSelectMode) {
                AccountBulkActionBar(
                    selectedCount = selectedIds.size,
                    onSelectAll = viewModel::selectAllVisible,
                    onDeleteSelected = viewModel::requestBulkDelete,
                    onExportSelected = {
                        shareAccountExport(
                            context = context,
                            request = viewModel.buildExportIntentPayload(),
                        )
                    },
                    onExit = viewModel::exitMultiSelectMode,
                )
            } else {
                AccountTopBar(
                    accountCount = uiState.accounts.size,
                    refreshAllState = uiState.refreshAllState,
                    batchCheckInState = uiState.batchCheckInState,
                    isAnyBatchActive = isAnyBatchActive,
                    batchCheckInEnabled = batchCheckInRunner != null,
                    onRefreshAll = viewModel::refreshAllAccountsManually,
                    onStartBatchCheckIn = viewModel::startBatchCheckIn,
                    onOpenBulkImport = viewModel::openBulkImportDialog,
                    onEnterMultiSelect = viewModel::enterMultiSelectMode,
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !isMultiSelectMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = onOpenAddAccount,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("添加账号") },
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                AccountSearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    onClear = viewModel::clearSearchQuery,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                if (uiState.refreshAllState is AccountRefreshAllState.Running) {
                    BatchProgressStrip(
                        text = "正在刷新账号 ${uiState.refreshAllState.completed}/${uiState.refreshAllState.total}",
                    )
                } else if (uiState.batchCheckInState is BatchCheckInState.Running) {
                    BatchProgressStrip(
                        text = "批量签到 ${uiState.batchCheckInState.completed}/${uiState.batchCheckInState.total}",
                    )
                }
                LazyColumn(
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 96.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (uiState.refreshAllState is AccountRefreshAllState.Cooldown) {
                        item { CooldownHint(message = uiState.refreshAllState.message) }
                    }
                    if (batchCheckInRunner != null && uiState.batchCheckInState.rows.isNotEmpty()) {
                        item {
                            BatchCheckInCard(
                                state = uiState.batchCheckInState,
                                onRetry = viewModel::retryBatchCheckIn,
                            )
                        }
                    }
                    uiState.errorMessage?.let { message ->
                        item { AccountFlashCard(message = message, tone = StatusTone.Negative, onDismiss = viewModel::clearError) }
                    }
                    uiState.actionMessage?.let { message ->
                        item { AccountFlashCard(message = message, tone = StatusTone.Info, onDismiss = viewModel::clearActionMessage) }
                    }
                    if (uiState.accounts.isEmpty()) {
                        item { EmptyAccountCard(onAdd = onOpenAddAccount) }
                    } else if (visibleAccounts.isEmpty()) {
                        item {
                            AccountFlashCard(
                                message = stringResource(R.string.account_filter_empty_hint),
                                tone = StatusTone.Neutral,
                                onDismiss = viewModel::clearSearchQuery,
                            )
                        }
                    } else {
                        items(
                            items = visibleAccounts,
                            key = { account -> account.studentId },
                        ) { account ->
                            SavedAccountCard(
                                account = account,
                                selectionMode = isMultiSelectMode,
                                selected = account.studentId in selectedIds,
                                onClickCard = {
                                    if (isMultiSelectMode) {
                                        viewModel.toggleAccountSelection(account.studentId)
                                    } else {
                                        viewModel.openAccountDetail(account.studentId)
                                    }
                                },
                            )
                        }
                        item {
                            FooterMeta(
                                accountCount = uiState.accounts.size,
                                versionName = BuildConfig.VERSION_NAME,
                                buildMarker = BuildConfig.BUILD_MARKER,
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingDeleteAccount != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRemoveAccount,
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("确认删除账号") },
            text = { Text("确定删除账号 ${pendingDeleteAccount.studentId} 吗？删除后会同时移除该账号保存的会话，无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmRemoveAccount,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRemoveAccount) { Text("取消") }
            },
        )
    }

    if (pendingBulkDeleteTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::cancelBulkDelete,
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.account_bulk_action_confirm_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.account_bulk_action_confirm_delete_body,
                        pendingBulkDeleteTargets.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmBulkDelete,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelBulkDelete) { Text("取消") }
            },
        )
    }

    AccountBulkImportDialog(
        state = uiState.bulkImportDialog,
        onRawTextChange = viewModel::updateBulkImportRawText,
        onSubmit = viewModel::submitBulkImport,
        onDismiss = viewModel::closeBulkImportDialog,
    )

    uiState.detailDialog?.let { dialogState ->
        val account = uiState.accounts.firstOrNull { it.studentId == dialogState.studentId }
        val pendingForCurrentAccount = viewModel.isPendingActionForDetail(dialogState.studentId)
        AccountDetailDialog(
            state = dialogState,
            account = account,
            pendingAction =
                uiState.pendingAction?.takeIf { uiState.pendingStudentId == dialogState.studentId },
            pendingBookingId =
                uiState.pendingBookingId?.takeIf { uiState.pendingStudentId == dialogState.studentId },
            pendingForCurrentAccount = pendingForCurrentAccount,
            onClose = viewModel::closeAccountDetail,
            onRefreshStatus = { viewModel.refreshSingleAccountStatus(dialogState.studentId) },
            onPrimaryAction = { action -> viewModel.performAccountAction(dialogState.studentId, action) },
            onSecondaryAction = { action -> viewModel.performAccountAction(dialogState.studentId, action) },
            onBookingAction = { action, bookingId ->
                viewModel.performAccountAction(dialogState.studentId, action, bookingId)
            },
            onSetCurrent = { viewModel.setCurrentAccount(dialogState.studentId) },
            onRefreshAuth = { viewModel.refreshAuthForAccount(dialogState.studentId) },
            onOpenTasks = { viewModel.openTasksFromDetail(dialogState.studentId, onOpenTasksForAccount) },
            onRemove = { viewModel.requestRemoveAccountFromDetail(dialogState.studentId) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountTopBar(
    accountCount: Int,
    refreshAllState: AccountRefreshAllState,
    batchCheckInState: BatchCheckInState,
    isAnyBatchActive: Boolean,
    batchCheckInEnabled: Boolean,
    onRefreshAll: () -> Unit,
    onStartBatchCheckIn: () -> Unit,
    onOpenBulkImport: () -> Unit,
    onEnterMultiSelect: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "账号列表",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "共 $accountCount 个账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            IconButton(
                onClick = onRefreshAll,
                enabled = accountCount > 0 && !isAnyBatchActive,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription =
                        if (refreshAllState is AccountRefreshAllState.Running) {
                            stringResource(R.string.account_refresh_all_running)
                        } else {
                            stringResource(R.string.account_refresh_all_label)
                        },
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (batchCheckInEnabled) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (batchCheckInState is BatchCheckInState.Running) "签到中..." else "全部签到",
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                            enabled = !isAnyBatchActive && accountCount > 0,
                            onClick = {
                                menuExpanded = false
                                onStartBatchCheckIn()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_bulk_import_title)) },
                        leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                        enabled = !isAnyBatchActive,
                        onClick = {
                            menuExpanded = false
                            onOpenBulkImport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_bulk_action_enter)) },
                        leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                        enabled = !isAnyBatchActive && accountCount > 0,
                        onClick = {
                            menuExpanded = false
                            onEnterMultiSelect()
                        },
                    )
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

@Composable
private fun BatchProgressStrip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun CooldownHint(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BatchCheckInCard(
    state: BatchCheckInState,
    onRetry: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DoneAll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("批量签到", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = buildBatchCheckInSummary(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state is BatchCheckInState.Cooldown) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.rows.forEach { row ->
                BatchCheckInRow(row = row, onRetry = { onRetry(row.studentId) })
            }
        }
    }
}

@Composable
private fun BatchCheckInRow(
    row: BatchCheckInRowState,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = row.studentId, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(text = row.status.toChineseLabel(), tone = batchRowTone(row.status))
                Text(
                    text = row.message,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (row.status == BatchCheckInRowStatus.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        if (row.canRetry) {
            FilledTonalButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("重试")
            }
        }
    }
}

private fun batchRowTone(status: BatchCheckInRowStatus): StatusTone =
    when (status) {
        BatchCheckInRowStatus.Pending -> StatusTone.Neutral
        BatchCheckInRowStatus.Running -> StatusTone.Info
        BatchCheckInRowStatus.Success -> StatusTone.Positive
        BatchCheckInRowStatus.Failed -> StatusTone.Negative
        BatchCheckInRowStatus.Skipped -> StatusTone.Neutral
    }

@Composable
private fun AccountFlashCard(
    message: String,
    tone: StatusTone,
    onDismiss: () -> Unit,
) {
    val (container, content, icon) = when (tone) {
        StatusTone.Negative ->
            Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Outlined.Error)
        StatusTone.Info ->
            Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Icons.Outlined.Done)
        else ->
            Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, Icons.Outlined.Done)
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = content,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyAccountCard(onAdd: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        com.wuyi.libraryauto.ui.components.EmptyStatePanel(
            title = "还没有保存账号",
            description = "添加账号后才能查询座位、设为当前账号或创建自动任务。\n密码可留空，默认使用学号。",
            actionLabel = "添加账号",
            actionIcon = Icons.Outlined.PersonAdd,
            onAction = onAdd,
        )
    }
}

@Composable
private fun SavedAccountCard(
    account: SavedAccountEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onClickCard: () -> Unit,
) {
    // 视觉一致：所有账号卡片默认都折叠成单行摘要，
    // 不在卡片上展示预约列表与签到/签退/取消按钮，
    // 操作统一进入详情 BottomSheet 处理，避免误触。
    val activeBookings = account.activeBookings
    val firstBooking = activeBookings.firstOrNull()
    val cardContainer =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    // 行 1（紧凑）"自习室圆形二楼（111），签到成功"
    // 行 2（小字）时间 + 多预约提示
    val bookingPrimaryText = firstBooking?.let { booking ->
        formatBookingPrimary(booking, account)
    }.orEmpty()
    val bookingSecondaryText = firstBooking?.let { booking ->
        val parts = mutableListOf<String>()
        if (booking.beginLabel.isNotBlank()) parts.add(booking.beginLabel)
        if (activeBookings.size > 1) parts.add("等 ${activeBookings.size} 条")
        parts.joinToString(" · ")
    }.orEmpty()

    Surface(
        color = cardContainer,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClickCard)
                    .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClickCard() },
                )
            } else {
                AccountAvatar(studentId = account.studentId)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = account.studentId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(
                        text = if (account.isAuthenticated) "已认证" else "未认证",
                        tone = if (account.isAuthenticated) StatusTone.Info else StatusTone.Warning,
                        icon = Icons.Outlined.VerifiedUser,
                    )
                    // 没有活跃预约时才显示目标座位徽章，避免和下方预约信息撞车
                    if (firstBooking == null && account.preferredSeatLabel.isNotBlank()) {
                        StatusBadge(text = account.preferredSeatLabel, tone = StatusTone.Neutral)
                    }
                }
                if (firstBooking != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = bookingPrimaryText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                    if (bookingSecondaryText.isNotBlank()) {
                        Text(
                            text = bookingSecondaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                } else if (account.statusSummary.isNotBlank()) {
                    Text(
                        text = account.statusSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (!selectionMode) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "查看详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 把单条预约压成一行紧凑展示："自习室圆形二楼（111），签到成功"。
 *
 * - 房间名+座位号用全角括号紧贴；
 * - 状态标签放最后，只取 [AccountBookingEntry.statusLabel] 第一段（去掉「，使用中」等修饰），
 *   避免和卡片上半部的状态摘要重复又被截断；
 * - 缺少房间名/座位号时优雅降级，不抛硬编码占位符。
 */
private fun formatBookingPrimary(
    booking: AccountBookingEntry,
    @Suppress("UNUSED_PARAMETER") account: SavedAccountEntry,
): String {
    val location =
        when {
            booking.roomName.isNotBlank() && booking.seatNumber.isNotBlank() ->
                "${booking.roomName}（${booking.seatNumber}）"
            booking.roomName.isNotBlank() -> booking.roomName
            booking.seatNumber.isNotBlank() -> "座位 ${booking.seatNumber}"
            else -> "预约 ${booking.bookingId.takeLast(6)}"
        }
    val status = booking.statusLabel.substringBefore('，').substringBefore(',').trim()
    return if (status.isBlank()) location else "$location，$status"
}

@Composable
private fun FooterMeta(
    accountCount: Int,
    versionName: String,
    buildMarker: String,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "已加载 $accountCount 个账号",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "构建 $versionName · $buildMarker",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
internal fun AccountAvatar(
    studentId: String,
) {
    val initials = studentId.takeLast(2).ifBlank { "--" }
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun shareAccountExport(
    context: android.content.Context,
    request: AccountExportRequest?,
) {
    if (request == null) {
        Toast.makeText(context, "请先选择账号", Toast.LENGTH_SHORT).show()
        return
    }
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = request.mimeType
            putExtra(Intent.EXTRA_SUBJECT, request.fileName)
            putExtra(Intent.EXTRA_TEXT, request.jsonText)
        }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "导出所选账号"))
    }.onFailure {
        Toast.makeText(context, "当前设备没有可用分享入口", Toast.LENGTH_SHORT).show()
    }.onSuccess {
        Toast.makeText(context, "已用文本方式导出，可发送到微信/邮件保存", Toast.LENGTH_SHORT).show()
    }
}

private fun buildBatchCheckInSummary(state: BatchCheckInState): String =
    when (state) {
        BatchCheckInState.Idle -> "暂无批量签到记录。"
        is BatchCheckInState.Running -> "进度 ${state.completed}/${state.total}"
        is BatchCheckInState.Cooldown -> "已完成本轮批量签到，可查看每个账号结果。"
    }

private fun BatchCheckInRowStatus.toChineseLabel(): String =
    when (this) {
        BatchCheckInRowStatus.Pending -> "等待"
        BatchCheckInRowStatus.Running -> "执行中"
        BatchCheckInRowStatus.Success -> "成功"
        BatchCheckInRowStatus.Failed -> "失败"
        BatchCheckInRowStatus.Skipped -> "跳过"
    }
