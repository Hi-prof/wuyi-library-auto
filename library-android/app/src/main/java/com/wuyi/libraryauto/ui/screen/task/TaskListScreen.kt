@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wuyi.libraryauto.ui.screen.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.R
import com.wuyi.libraryauto.ui.components.EmptyStatePanel
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogRepository
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRepository
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskPlanUiModel
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskViewModel
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskViewModelFactory
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository
import androidx.compose.ui.res.stringResource

@Composable
fun TaskListScreen(
    accountRepository: SavedAccountRepository,
    automationPlanRepository: AutomationPlanRepository,
    seatLookupRepository: SeatLookupRepository,
    sessionRepository: SessionRepository,
    historyReader: AccountReservationHistoryReader,
    diagnosticsLogRepository: DiagnosticsLogRepository,
    studentIdFilter: String,
    onOpenAccounts: () -> Unit,
    onClearStudentFilter: () -> Unit,
) {
    val factory =
        remember(
            accountRepository,
            automationPlanRepository,
            seatLookupRepository,
            sessionRepository,
            historyReader,
            diagnosticsLogRepository,
            studentIdFilter,
        ) {
            AutomationTaskViewModelFactory(
                accountRepository = accountRepository,
                automationPlanRepository = automationPlanRepository,
                seatLookupRepository = seatLookupRepository,
                sessionRepository = sessionRepository,
                initialStudentFilter = studentIdFilter,
                historyReader = historyReader,
                diagnosticsLogRepository = diagnosticsLogRepository,
            )
        }
    val viewModel: AutomationTaskViewModel = viewModel(factory = factory)
    val uiState = viewModel.uiState
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingDeletePlanId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAccounts()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (uiState.dialog.visible) {
        AutomationTaskDialog(
            dialogState = uiState.dialog,
            accounts = uiState.accounts,
            onDismiss = viewModel::closeDialog,
            onStudentChange = viewModel::updateDialogStudentId,
            onModeChange = viewModel::switchMode,
            onRoomNameChange = viewModel::updateDialogRoomName,
            onSeatNumberChange = viewModel::updateDialogSeatNumber,
            onRefreshSeats = viewModel::refreshSeatOptions,
            onSeatPicked = viewModel::chooseSeat,
            onCustomDateChange = viewModel::updateCustomDate,
            onCustomStartTimeChange = viewModel::updateCustomStartTime,
            onCustomEndTimeChange = viewModel::updateCustomEndTime,
            onSave = viewModel::savePlan,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            AnimatedVisibility(
                visible = uiState.accounts.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::openCreateDialog,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("添加任务") },
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SummaryStrip(
                        accountCount = uiState.accounts.size,
                        planCount = uiState.plans.size,
                        studentFilter = uiState.studentFilter,
                        onClearFilter = onClearStudentFilter,
                    )
                }
                uiState.statusMessage?.let { message ->
                    item { TaskStatusBanner(message = message) }
                }
                if (uiState.accounts.isEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            EmptyStatePanel(
                                title = "还没有可用账号",
                                description = "先去账号列表添加账号并完成认证，再回来创建自动任务。",
                                actionLabel = "去账号列表",
                                onAction = onOpenAccounts,
                            )
                        }
                    }
                } else if (uiState.plans.isEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            EmptyStatePanel(
                                title = "还没有自动任务",
                                description = stringResource(R.string.task_list_empty_short),
                                actionLabel = "添加自动任务",
                                actionIcon = Icons.Filled.Add,
                                onAction = viewModel::openCreateDialog,
                            )
                        }
                    }
                } else {
                    items(
                        items = uiState.plans,
                        key = { plan -> plan.planId },
                    ) { plan ->
                        AutomationPlanCard(
                            plan = plan,
                            onDelete = { pendingDeletePlanId = plan.planId },
                        )
                    }
                }
            }
        }
    }

    val pendingPlanId = pendingDeletePlanId
    if (pendingPlanId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeletePlanId = null },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("确认删除自动任务") },
            text = { Text("删除后该自动任务不再执行，可在添加任务里重新创建。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlan(pendingPlanId)
                        pendingDeletePlanId = null
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePlanId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SummaryStrip(
    accountCount: Int,
    planCount: Int,
    studentFilter: String,
    onClearFilter: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "自动任务",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(text = "账号 $accountCount", tone = StatusTone.Info)
                StatusBadge(text = "任务 $planCount", tone = StatusTone.Positive)
                if (studentFilter.isNotBlank()) {
                    StatusBadge(text = "过滤 $studentFilter", tone = StatusTone.Warning, icon = Icons.Outlined.FilterList)
                }
            }
            if (studentFilter.isNotBlank()) {
                TextButton(onClick = onClearFilter, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text("查看全部任务", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun TaskStatusBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun AutomationPlanCard(
    plan: AutomationTaskPlanUiModel,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)) {
            Box(
                modifier =
                    Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
            )
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = plan.studentId,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = plan.modeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "删除自动任务",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlanInfoBlock(
                        icon = Icons.Outlined.MeetingRoom,
                        label = "自习室",
                        value = plan.roomName.ifBlank { "未指定" },
                        modifier = Modifier.weight(1f),
                    )
                    PlanInfoBlock(
                        icon = Icons.Outlined.Chair,
                        label = "座位",
                        value = plan.seatNumber.ifBlank { "未指定" },
                        modifier = Modifier.weight(1f),
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = plan.previewText.ifBlank { "等待生成执行预览" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AssignmentTurnedIn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = plan.lastResultMessage.ifBlank { "最近还没有执行结果" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanInfoBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
