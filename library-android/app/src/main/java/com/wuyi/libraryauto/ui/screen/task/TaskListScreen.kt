@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wuyi.libraryauto.ui.screen.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRepository
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskPlanUiModel
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskReservationCheckStatus
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskViewModel
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskViewModelFactory
import com.wuyi.libraryauto.ui.viewmodel.CreateFromBookingsDialogState
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
    accountSeatActionExecutor: AccountSeatActionExecutor? = null,
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
            accountSeatActionExecutor,
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
                accountSeatActionExecutor = accountSeatActionExecutor,
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

    if (uiState.createFromBookingsDialog.visible) {
        CreateFromBookingsSheet(
            state = uiState.createFromBookingsDialog,
            onSelectAll = viewModel::selectAllCreateFromBookingsRows,
            onClearAll = viewModel::clearCreateFromBookingsRows,
            onToggleRow = viewModel::toggleCreateFromBookingsRow,
            onConfirm = viewModel::confirmCreateFromBookings,
            onDismiss = viewModel::closeCreateFromBookingsDialog,
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
                        isCheckingReservations = uiState.isCheckingReservations,
                        isCreateFromBookingsBusy =
                            uiState.createFromBookingsDialog.loading || uiState.createFromBookingsDialog.saving,
                        onClearFilter = onClearStudentFilter,
                        onCheckReservations = viewModel::checkReservationsForPlans,
                        onCreateFromBookings = viewModel::openCreateFromBookingsDialog,
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
        TaskDeleteConfirmationSheet(
            presentation = buildTaskDeleteConfirmationPresentation(),
            onConfirm = {
                viewModel.deletePlan(pendingPlanId)
                pendingDeletePlanId = null
            },
            onDismiss = { pendingDeletePlanId = null },
        )
    }
}

@Composable
private fun CreateFromBookingsSheet(
    state: CreateFromBookingsDialogState,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onToggleRow: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = buildCreateFromBookingsPresentation(state)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.AssignmentTurnedIn,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = presentation.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = presentation.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.message?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("正在读取当前预约")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onSelectAll,
                        enabled = !state.saving,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("全选")
                    }
                    OutlinedButton(
                        onClick = onClearAll,
                        enabled = !state.saving,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("清空")
                    }
                }

                presentation.rows.forEach { row ->
                    CreateFromBookingsRow(
                        row = row,
                        saving = state.saving,
                        onToggle = { onToggleRow(row.studentId) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("取消")
                }
                FilledTonalButton(
                    onClick = onConfirm,
                    enabled = presentation.confirmAction.enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(presentation.confirmAction.label)
                }
            }
        }
    }
}

@Composable
private fun CreateFromBookingsRow(
    row: CreateFromBookingsRowPresentation,
    saving: Boolean,
    onToggle: () -> Unit,
) {
    val enabled = row.enabled && !saving
    Surface(
        color =
            if (row.enabled) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        shape = RoundedCornerShape(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = row.selected,
                enabled = enabled,
                onCheckedChange = { onToggle() },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = row.studentId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.bookingLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(
                text = row.planBadgeLabel,
                tone = if (row.planBadgeLabel == "已有任务") StatusTone.Warning else StatusTone.Positive,
            )
        }
    }
}

@Composable
private fun TaskDeleteConfirmationSheet(
    presentation: TaskDeleteConfirmationPresentation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
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
                        text = presentation.message,
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(presentation.dismissAction.label)
                }
                FilledTonalButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                ) {
                    Text(presentation.confirmAction.label)
                }
            }
        }
    }
}

@Composable
private fun SummaryStrip(
    accountCount: Int,
    planCount: Int,
    studentFilter: String,
    isCheckingReservations: Boolean,
    isCreateFromBookingsBusy: Boolean,
    onClearFilter: () -> Unit,
    onCheckReservations: () -> Unit,
    onCreateFromBookings: () -> Unit,
) {
    val chips = buildTaskSummaryChips(accountCount, planCount, studentFilter)
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
                chips.forEach { chip ->
                    StatusBadge(
                        text = chip.text,
                        tone = chip.tone,
                        icon = if (chip.isFilter) Icons.Outlined.FilterList else null,
                    )
                }
            }
            if (studentFilter.isNotBlank()) {
                TextButton(onClick = onClearFilter, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text("查看全部任务", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (accountCount > 0) {
                OutlinedButton(
                    onClick = onCreateFromBookings,
                    enabled = !isCreateFromBookingsBusy,
                    modifier = Modifier.heightIn(min = 42.dp),
                ) {
                    Icon(Icons.Outlined.AssignmentTurnedIn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isCreateFromBookingsBusy) "读取预约中" else "根据当前预约创建")
                }
            }
            if (planCount > 0) {
                OutlinedButton(
                    onClick = onCheckReservations,
                    enabled = !isCheckingReservations,
                    modifier = Modifier.heightIn(min = 42.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isCheckingReservations) "检查中" else "检查预约")
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
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = plan.studentId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(text = plan.modeLabel, tone = StatusTone.Info)
                        StatusBadge(
                            text = if (plan.enabled) "已启用" else "已停用",
                            tone = if (plan.enabled) StatusTone.Positive else StatusTone.Neutral,
                        )
                    }
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
                    value = displayTaskValue(plan.roomName, "未指定"),
                    modifier = Modifier.weight(1f),
                )
                PlanInfoBlock(
                    icon = Icons.Outlined.Chair,
                    label = "座位",
                    value = displayTaskValue(plan.seatNumber, "未指定"),
                    modifier = Modifier.weight(1f),
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
                        text = displayTaskPreview(plan.previewText),
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
                    text = displayTaskLastResult(plan.lastResultMessage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plan.reservationCheck.status != AutomationTaskReservationCheckStatus.UNCHECKED) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(
                        text = plan.reservationCheck.label,
                        tone = reservationCheckTone(plan.reservationCheck.status),
                        icon = Icons.Outlined.AssignmentTurnedIn,
                    )
                    if (plan.reservationCheck.detail.isNotBlank()) {
                        Text(
                            text = plan.reservationCheck.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun reservationCheckTone(status: AutomationTaskReservationCheckStatus): StatusTone =
    when (status) {
        AutomationTaskReservationCheckStatus.MATCHED -> StatusTone.Positive
        AutomationTaskReservationCheckStatus.OTHER_BOOKING -> StatusTone.Warning
        AutomationTaskReservationCheckStatus.EMPTY -> StatusTone.Neutral
        AutomationTaskReservationCheckStatus.FAILED -> StatusTone.Negative
        AutomationTaskReservationCheckStatus.CHECKING -> StatusTone.Info
        AutomationTaskReservationCheckStatus.UNCHECKED -> StatusTone.Neutral
    }

internal data class TaskSummaryChip(
    val text: String,
    val tone: StatusTone,
    val isFilter: Boolean = false,
)

internal fun buildTaskSummaryChips(
    accountCount: Int,
    planCount: Int,
    studentFilter: String,
): List<TaskSummaryChip> =
    buildList {
        add(TaskSummaryChip("账号 $accountCount", StatusTone.Info))
        add(TaskSummaryChip("任务 $planCount", StatusTone.Positive))
        studentFilter.trim().takeIf(String::isNotBlank)?.let { filter ->
            add(TaskSummaryChip("过滤 $filter", StatusTone.Warning, isFilter = true))
        }
    }

internal fun displayTaskValue(value: String, fallback: String): String =
    value.trim().ifBlank { fallback }

internal fun displayTaskPreview(previewText: String): String =
    previewText.trim().ifBlank { "等待生成执行预览" }

internal fun displayTaskLastResult(lastResultMessage: String): String =
    lastResultMessage.trim().ifBlank { "最近还没有执行结果" }

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
