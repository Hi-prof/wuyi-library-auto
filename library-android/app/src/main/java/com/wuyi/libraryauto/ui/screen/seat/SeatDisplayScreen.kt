@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wuyi.libraryauto.ui.screen.seat

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.R
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.ui.components.EmptyStatePanel
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.repository.seat.BatchCheckInResult
import com.wuyi.libraryauto.ui.repository.seat.BatchReservationResult
import com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepository
import com.wuyi.libraryauto.ui.viewmodel.SeatDisplayViewModel
import com.wuyi.libraryauto.ui.viewmodel.SeatDisplayViewModelFactory

@Composable
fun SeatDisplayScreen(
    repository: SeatDisplayRepository,
    onOpenAccounts: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val factory = remember(repository) { SeatDisplayViewModelFactory(repository) }
    val viewModel: SeatDisplayViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsState()
    var initialLoadDispatched by remember { mutableStateOf(false) }
    val rooms = remember(uiState.cards) { buildSeatDisplayRooms(uiState.cards) }
    val roomIds = remember(rooms) { rooms.map(SeatDisplayRoomUiState::roomId) }
    var expandedRoomIds by rememberSaveable(roomIds) { mutableStateOf(emptyList<String>()) }
    var selectedSeatKey by rememberSaveable(roomIds) { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && !initialLoadDispatched) {
                    initialLoadDispatched = true
                    viewModel.loadInitialSnapshot()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val headerPresentation =
                    buildSeatDisplayHeaderPresentation(
                        cardCount = uiState.cards.size,
                        waitingSignIn = uiState.cards.count { it.liveState == SeatBookingLiveState.RESERVED_WAITING_SIGNIN },
                        activeSignedIn = uiState.cards.count { it.liveState == SeatBookingLiveState.ACTIVE_SIGNED_IN },
                        isRefreshing = uiState.isRefreshingAll,
                        isBatchCheckingIn = uiState.isBatchCheckingIn,
                        isBatchReserving = uiState.isBatchReserving,
                    )
                SeatDisplayStatsHeader(
                    presentation = headerPresentation,
                    onRefreshAll = viewModel::refreshAll,
                    onBatchCheckIn = viewModel::batchCheckIn,
                    onBatchMakeupReservation = viewModel::batchMakeupReservation,
                )
            }
            if (uiState.batchProgressMessage.isNotBlank()) {
                item {
                    BatchStatusBanner(
                        message = uiState.batchProgressMessage,
                        isError = false,
                    )
                }
            }
            if (uiState.batchErrorMessage.isNotBlank()) {
                item {
                    BatchStatusBanner(
                        message = uiState.batchErrorMessage,
                        isError = true,
                    )
                }
            }
            if (rooms.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        EmptyStatePanel(
                            title = "暂无账号座位状态",
                            description = stringResource(R.string.seat_display_empty_hint),
                            actionLabel = "去账号列表",
                            actionIcon = Icons.Outlined.PersonAdd,
                            onAction = onOpenAccounts,
                        )
                    }
                }
            } else {
                items(
                    items = rooms,
                    key = { room -> room.roomId },
                ) { room ->
                    val expanded = expandedRoomIds.contains(room.roomId)
                    SeatDisplayRoomCard(
                        room = room,
                        expanded = expanded,
                        selectedSeatKey = selectedSeatKey,
                        singleRefreshing = uiState.singleRefreshing,
                        onToggleExpanded = {
                            expandedRoomIds =
                                if (expanded) expandedRoomIds - room.roomId
                                else expandedRoomIds + room.roomId
                        },
                        onSelectSeat = { seatKey ->
                            selectedSeatKey = if (selectedSeatKey == seatKey) null else seatKey
                        },
                        onRefreshSingle = viewModel::refreshSingle,
                    )
                }
            }
        }

        uiState.lastBatchCheckInResult?.let { result ->
            BatchCheckInResultDialog(
                result = result,
                onDismiss = viewModel::dismissBatchCheckInResult,
            )
        }

        uiState.lastBatchReservationResult?.let { result ->
            BatchReservationResultDialog(
                result = result,
                onDismiss = viewModel::dismissBatchReservationResult,
            )
        }
    }
}

@Composable
private fun BatchStatusBanner(
    message: String,
    isError: Boolean,
) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun BatchCheckInResultDialog(
    result: BatchCheckInResult,
    onDismiss: () -> Unit,
) {
    SeatDisplayBatchResultSheet(
        presentation = buildBatchCheckInResultPresentation(result),
        onDismiss = onDismiss,
    )
}

@Composable
private fun BatchReservationResultDialog(
    result: BatchReservationResult,
    onDismiss: () -> Unit,
) {
    SeatDisplayBatchResultSheet(
        presentation = buildBatchReservationResultPresentation(result),
        onDismiss = onDismiss,
    )
}

@Composable
private fun SeatDisplayBatchResultSheet(
    presentation: SeatDisplayBatchResultPresentation,
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
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    text = presentation.statusBadgeLabel,
                    tone = presentation.statusBadgeTone,
                )
            }
            if (presentation.detailTitle != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = presentation.detailTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        presentation.detailLines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(presentation.confirmAction.label)
            }
        }
    }
}

@Composable
private fun SeatDisplayStatsHeader(
    presentation: SeatDisplayHeaderPresentation,
    onRefreshAll: () -> Unit,
    onBatchCheckIn: () -> Unit,
    onBatchMakeupReservation: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = presentation.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presentation.badges.forEach { badge ->
                    StatusBadge(text = badge.label, tone = badge.tone)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = onRefreshAll,
                    enabled = presentation.refreshAction.enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(presentation.refreshAction.label)
                }
                presentation.checkInAction?.let { action ->
                    FilledTonalButton(
                        onClick = onBatchCheckIn,
                        enabled = action.enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Done,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(action.label)
                    }
                }
            }
            presentation.makeupReservationAction?.let { action ->
                FilledTonalButton(
                    onClick = onBatchMakeupReservation,
                    enabled = action.enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(action.label)
                }
            }
        }
    }
}
