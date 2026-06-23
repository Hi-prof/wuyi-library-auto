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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
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
import com.wuyi.libraryauto.ui.components.StatusTone
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
                SeatDisplayStatsHeader(
                    cardCount = uiState.cards.size,
                    waitingSignIn = uiState.cards.count { it.liveState == SeatBookingLiveState.RESERVED_WAITING_SIGNIN },
                    activeSignedIn = uiState.cards.count { it.liveState == SeatBookingLiveState.ACTIVE_SIGNED_IN },
                    isRefreshing = uiState.isRefreshingAll,
                    onRefreshAll = viewModel::refreshAll,
                )
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
    }
}

@Composable
private fun SeatDisplayStatsHeader(
    cardCount: Int,
    waitingSignIn: Int,
    activeSignedIn: Int,
    isRefreshing: Boolean,
    onRefreshAll: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "座位概览",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(text = "账号 $cardCount", tone = StatusTone.Info)
                StatusBadge(text = "待签到 $waitingSignIn", tone = StatusTone.Warning, icon = Icons.Outlined.Schedule)
                StatusBadge(text = "已签到 $activeSignedIn", tone = StatusTone.Positive, icon = Icons.Outlined.Done)
            }
            FilledTonalButton(
                onClick = onRefreshAll,
                enabled = !isRefreshing && cardCount > 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isRefreshing) {
                        stringResource(R.string.seat_display_refreshing)
                    } else {
                        stringResource(R.string.seat_display_refresh_all)
                    },
                )
            }
        }
    }
}
