@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wuyi.libraryauto.ui.screen.seat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuyi.libraryauto.R
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone

@Composable
fun SeatDisplayRoomCard(
    room: SeatDisplayRoomUiState,
    expanded: Boolean,
    selectedSeatKey: String?,
    singleRefreshing: Set<String>,
    onToggleExpanded: () -> Unit,
    onSelectSeat: (String) -> Unit,
    onRefreshSingle: (String) -> Unit,
) {
    val selectedSeat = room.seats.firstOrNull { seat -> seat.key == selectedSeatKey }
    val presentation =
        buildSeatDisplayRoomPresentation(
            room = room,
            expanded = expanded,
            selectedSeatKey = selectedSeatKey,
        )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = presentation.roomName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusBadge(text = presentation.seatCountLabel, tone = StatusTone.Neutral)
                        StatusBadge(text = presentation.accountCountLabel, tone = StatusTone.Info)
                        StatusBadge(text = presentation.healthLabel, tone = presentation.healthTone)
                    }
                }
                Text(
                    text = presentation.expandActionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    room.seats.forEach { seat ->
                        val isFailure = seat.hasFailure
                        FilterChip(
                            selected = selectedSeatKey == seat.key,
                            onClick = { onSelectSeat(seat.key) },
                            label = { Text(seat.displaySeatNumber) },
                            shape = RoundedCornerShape(50),
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor =
                                        if (isFailure) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.primary,
                                    selectedLabelColor =
                                        if (isFailure) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onPrimary,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                ),
                        )
                    }
                }
                if (selectedSeat != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = selectedSeat.displaySeatNumber,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                StatusBadge(
                                    text = selectedSeat.statusLabel,
                                    tone = if (selectedSeat.hasFailure) StatusTone.Negative else StatusTone.Info,
                                )
                            }
                            selectedSeat.accounts.forEach { account ->
                                SeatDisplayCard(
                                    state = account,
                                    isRefreshing = account.studentId in singleRefreshing,
                                    onRefreshSingle = { onRefreshSingle(account.studentId) },
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = presentation.collapsedHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SeatDisplayCard(
    state: SeatDisplayCardUiState,
    isRefreshing: Boolean,
    onRefreshSingle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = state.studentId, style = MaterialTheme.typography.titleSmall)
            Text(
                text = state.statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (state.failureMessage == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
            if (state.bookingLabel.isNotBlank()) {
                Text(
                    text = state.bookingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onRefreshSingle,
            enabled = !isRefreshing,
            modifier = Modifier.size(48.dp),
        ) {
            if (isRefreshing) {
                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.seat_display_refresh),
                )
            }
        }
    }
}
