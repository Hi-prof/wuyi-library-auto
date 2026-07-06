package com.wuyi.libraryauto.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.EventSeat
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.repository.home.TodayAccountOverview
import com.wuyi.libraryauto.ui.repository.home.TodayOverviewRepository
import com.wuyi.libraryauto.ui.repository.home.TodayOverviewSnapshot
import com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayOverviewScreen(
    repository: TodayOverviewRepository,
    seatDisplayRepository: SeatDisplayRepository,
    onOpenAccountManager: () -> Unit,
    onOpenAddAccount: () -> Unit,
    onOpenManualReservation: () -> Unit,
    onOpenSeatDisplay: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val factory =
        remember(repository, seatDisplayRepository) {
            TodayOverviewViewModelFactory(
                repository = repository,
                seatDisplayRepository = seatDisplayRepository,
            )
        }
    val viewModel: TodayOverviewViewModel = viewModel(factory = factory)
    val uiState = viewModel.uiState

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refresh()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("首页") },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !uiState.isLoading,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "刷新今日数据",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "打开设置",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            if (uiState.snapshot == null && uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                val snapshot = uiState.snapshot ?: emptySnapshot()
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        val presentation =
                            buildTodayOverviewPresentation(
                                snapshot = snapshot,
                                isCheckingReservations = uiState.isCheckingReservations,
                                isSigningIn = uiState.isSigningIn,
                            )
                        OverviewCard(
                            snapshot = snapshot,
                            presentation = presentation,
                            errorMessage = uiState.errorMessage,
                            actionMessage = uiState.actionMessage,
                            onCheckReservations = viewModel::checkReservations,
                            onCheckInAll = viewModel::checkInAll,
                            onOpenAccountManager = onOpenAccountManager,
                            onOpenAddAccount = onOpenAddAccount,
                            onOpenManualReservation = onOpenManualReservation,
                            onOpenSeatDisplay = onOpenSeatDisplay,
                            onOpenTasks = onOpenTasks,
                        )
                        MetricGrid(metrics = presentation.metrics)
                        AccountSummarySection(accountSummaries = snapshot.accountSummaries)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(
    snapshot: TodayOverviewSnapshot,
    presentation: TodayOverviewPresentation,
    errorMessage: String,
    actionMessage: String,
    onCheckReservations: () -> Unit,
    onCheckInAll: () -> Unit,
    onOpenAccountManager: () -> Unit,
    onOpenAddAccount: () -> Unit,
    onOpenManualReservation: () -> Unit,
    onOpenSeatDisplay: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = presentation.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            StatusBadge(
                text = snapshot.signInHeadline,
                tone = presentation.statusTone,
            )

            Text(
                text = snapshot.signInDetail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onCheckReservations,
                    enabled = presentation.checkReservationAction.enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EventSeat,
                        contentDescription = null,
                    )
                    Text(
                        presentation.checkReservationAction.label,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Button(
                    onClick = onCheckInAll,
                    enabled = presentation.checkInAction.enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DoneAll,
                        contentDescription = null,
                    )
                    Text(
                        presentation.checkInAction.label,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (actionMessage.isNotBlank()) {
                Text(
                    text = actionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (errorMessage.isNotBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = onOpenAccountManager,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ManageAccounts,
                            contentDescription = null,
                        )
                        Text("账号管理", modifier = Modifier.padding(start = 8.dp))
                    }
                    Button(
                        onClick = onOpenAddAccount,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PersonAdd,
                            contentDescription = null,
                        )
                        Text("添加账号", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = onOpenManualReservation,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EventSeat,
                            contentDescription = null,
                        )
                        Text("手动预约", modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(
                        onClick = onOpenSeatDisplay,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DoneAll,
                            contentDescription = null,
                        )
                        Text("座位状态", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                FilledTonalButton(
                    onClick = onOpenTasks,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                    )
                    Text("自动任务", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<TodayOverviewMetricPresentation>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowMetrics.forEach { metric ->
                    MetricCard(
                        title = metric.title,
                        value = metric.value,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowMetrics.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AccountSummarySection(accountSummaries: List<TodayAccountOverview>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "账号明细",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (accountSummaries.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "今天还没有账号产生预约记录。",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            accountSummaries.forEach { summary ->
                AccountSummaryRow(summary = summary)
            }
        }
    }
}

@Composable
private fun AccountSummaryRow(summary: TodayAccountOverview) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        text = summary.studentId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = summary.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${summary.reservedSeatCount} 个座位",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text =
                    buildList {
                        add("任务 ${summary.totalTaskCount}")
                        add("已签到 ${summary.signedInSeatCount}")
                        if (summary.waitingSignInCount > 0) {
                            add("待签到 ${summary.waitingSignInCount}")
                        }
                        if (summary.reservationQueueCount > 0) {
                            add("预约中 ${summary.reservationQueueCount}")
                        }
                        if (summary.attentionCount > 0) {
                            add("待处理 ${summary.attentionCount}")
                        }
                    }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun emptySnapshot(): TodayOverviewSnapshot =
    TodayOverviewSnapshot(
        dateLabel = "--",
        totalAccountCount = 0,
        reservationAccountCount = 0,
        totalTaskCount = 0,
        reservedSeatCount = 0,
        signedInSeatCount = 0,
        waitingSignInSeatCount = 0,
        reservationQueueCount = 0,
        attentionCount = 0,
        allSignedIn = false,
        signInHeadline = "今天暂无预约",
        signInDetail = "首页只统计今天的本地预约和签到记录。",
        accountSummaries = emptyList(),
    )
