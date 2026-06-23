@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.wuyi.libraryauto.ui.screen.seat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.ui.components.SectionHeader
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationGateway
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.viewmodel.ManualReservationRoomUiModel
import com.wuyi.libraryauto.ui.viewmodel.ManualReservationUiState
import com.wuyi.libraryauto.ui.viewmodel.ManualReservationViewModel
import com.wuyi.libraryauto.ui.viewmodel.ManualReservationViewModelFactory
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun SeatLookupScreen(
    accountRepository: SavedAccountRepository,
    seatLookupRepository: SeatLookupRepository,
    manualReservationRepository: ManualReservationGateway,
    sessionRepository: SessionRepository,
    onOpenAccounts: () -> Unit,
) {
    val factory =
        remember(accountRepository, seatLookupRepository, manualReservationRepository, sessionRepository) {
            ManualReservationViewModelFactory(
                accountRepository = accountRepository,
                seatLookupRepository = seatLookupRepository,
                manualReservationRepository = manualReservationRepository,
                sessionRepository = sessionRepository,
            )
        }
    val viewModel: ManualReservationViewModel = viewModel(factory = factory)
    val uiState = viewModel.uiState
    val lifecycleOwner = LocalLifecycleOwner.current
    val roomIds =
        remember(uiState.rooms) {
            uiState.rooms.map { room -> room.roomId.ifBlank { room.roomName } }
        }
    var expandedRoomIds by rememberSaveable(roomIds) {
        mutableStateOf(buildInitialExpandedRoomIds(roomIds, uiState.selectedRoomId).toList())
    }
    var datePickerVisible by remember { mutableStateOf(false) }
    var timePickerVisible by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAccounts()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StepCard(
                stepNumber = 1,
                title = "选择账号",
                icon = Icons.Outlined.PersonOutline,
            ) {
                if (uiState.accounts.isEmpty()) {
                    Button(
                        onClick = onOpenAccounts,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("先去添加账号")
                    }
                } else {
                    AccountDropdown(
                        accounts = uiState.accounts,
                        selectedStudentId = uiState.selectedStudentId,
                        onSelected = viewModel::updateSelectedStudentId,
                    )
                }
            }
        }
        item {
            StepCard(
                stepNumber = 2,
                title = "选择时间",
                icon = Icons.Outlined.CalendarMonth,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PickerRow(
                        label = "日期",
                        value = uiState.selectedDate.ifBlank { "选择日期" },
                        icon = Icons.Outlined.CalendarMonth,
                        onClick = { datePickerVisible = true },
                    )
                    PickerRow(
                        label = "开始时间",
                        value = uiState.selectedStartTime.ifBlank { "选择时间" },
                        icon = Icons.Outlined.AccessTime,
                        onClick = { timePickerVisible = true },
                    )
                    DurationSelector(
                        durationHours = uiState.durationHours,
                        onChange = viewModel::updateDurationHours,
                    )
                }
            }
        }
        item {
            StepCard(
                stepNumber = 3,
                title = "查询并选择座位",
                icon = Icons.Outlined.EventAvailable,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = viewModel::querySeats,
                        enabled = !uiState.isLoadingSeats && uiState.accounts.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (uiState.isLoadingSeats) "查询中..." else "查询可选座位")
                    }
                    OutlinedButton(
                        onClick = onOpenAccounts,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("账号列表")
                    }
                }
            }
        }
        uiState.statusMessage?.let { message ->
            item { ManualStatusBanner(message = message) }
        }
        if (uiState.querySummary.isNotBlank()) {
            item {
                ManualSummaryCard(
                    uiState = uiState,
                    onReserve = viewModel::reserveSelectedSeat,
                )
            }
        }
        items(
            items = uiState.rooms,
            key = { room -> room.roomId.ifBlank { room.roomName } },
        ) { room ->
            val roomId = room.roomId.ifBlank { room.roomName }
            ManualRoomCard(
                room = room,
                expanded = expandedRoomIds.contains(roomId),
                selectedRoomId = uiState.selectedRoomId,
                selectedSeatNumber = uiState.selectedSeatNumber,
                onToggleExpanded = {
                    expandedRoomIds =
                        if (expandedRoomIds.contains(roomId)) expandedRoomIds - roomId
                        else expandedRoomIds + roomId
                },
                onSelectSeat = viewModel::selectSeat,
            )
        }
    }

    if (datePickerVisible) {
        DatePickerSheet(
            initialDate = uiState.selectedDate,
            onConfirm = { date ->
                viewModel.updateSelectedDate(date)
                datePickerVisible = false
            },
            onDismiss = { datePickerVisible = false },
        )
    }
    if (timePickerVisible) {
        TimePickerSheet(
            initialTime = uiState.selectedStartTime,
            onConfirm = { time ->
                viewModel.updateSelectedStartTime(time)
                timePickerVisible = false
            },
            onDismiss = { timePickerVisible = false },
        )
    }
}

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepBadge(stepNumber)
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
private fun StepBadge(number: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun DurationSelector(
    durationHours: String,
    onChange: (String) -> Unit,
) {
    val options = listOf("1", "2", "3", "4", "6", "8")
    var expanded by rememberSaveable(durationHours) { mutableStateOf(false) }
    val displayText = if (durationHours.isBlank()) "选择时长" else "${durationHours}h"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { shouldExpand -> expanded = shouldExpand },
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            label = { Text("时长（小时）") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.HourglassEmpty,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true),
        ) {
            options.forEach { hours ->
                DropdownMenuItem(
                    text = { Text("${hours}h") },
                    onClick = {
                        expanded = false
                        onChange(hours)
                    },
                )
            }
        }
    }
}

@Composable
private fun AccountDropdown(
    accounts: List<SavedAccountEntry>,
    selectedStudentId: String,
    onSelected: (String) -> Unit,
) {
    var expanded by rememberSaveable(selectedStudentId, accounts.map(SavedAccountEntry::studentId)) {
        mutableStateOf(false)
    }
    val selectedAccount = accounts.firstOrNull { it.studentId == selectedStudentId }
    val displayText =
        selectedAccount?.let { account ->
            val suffix = if (account.isAuthenticated) "" else " · 未认证"
            "${account.studentId}$suffix"
        } ?: "请选择账号"
    val supportingText =
        when {
            accounts.isEmpty() -> "当前没有可用账号"
            selectedAccount == null -> "共 ${accounts.size} 个账号，点击选择"
            !selectedAccount.isAuthenticated -> "该账号尚未认证，请先在账号页刷新"
            else -> "共 ${accounts.size} 个账号，可下拉切换"
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { shouldExpand -> expanded = shouldExpand },
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            label = { Text("账号") },
            supportingText = { Text(supportingText, style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true),
        ) {
            accounts.forEach { account ->
                val suffix = if (account.isAuthenticated) "" else " · 未认证"
                DropdownMenuItem(
                    text = { Text("${account.studentId}$suffix") },
                    onClick = {
                        expanded = false
                        onSelected(account.studentId)
                    },
                )
            }
        }
    }
}

@Composable
private fun manualChipColors() =
    FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
private fun ManualStatusBanner(message: String) {
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
private fun ManualSummaryCard(
    uiState: ManualReservationUiState,
    onReserve: () -> Unit,
) {
    val seatPicked = uiState.selectedSeatNumber.isNotBlank()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(
                title = "当前选择",
                subtitle = uiState.querySummary,
                padding = PaddingValues(0.dp),
            )
            Text(
                text =
                    if (seatPicked) {
                        "已选座位：${uiState.selectedRoomName} · ${uiState.selectedSeatNumber}"
                    } else {
                        "还没选中座位，请在下方自习室列表点选"
                    },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(
                onClick = onReserve,
                enabled = seatPicked && !uiState.isSubmitting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            ) {
                Text(if (uiState.isSubmitting) "预约中..." else "立即预约")
            }
        }
    }
}

@Composable
private fun ManualRoomCard(
    room: ManualReservationRoomUiModel,
    expanded: Boolean,
    selectedRoomId: String,
    selectedSeatNumber: String,
    onToggleExpanded: () -> Unit,
    onSelectSeat: (String, String, String) -> Unit,
) {
    val sortedSeatNumbers = remember(room.seatNumbers) { sortSeatNumbersForDisplay(room.seatNumbers) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = room.roomName, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusBadge(text = room.storey, tone = StatusTone.Neutral)
                        StatusBadge(
                            text = "可用 ${room.availableCount}",
                            tone = if (room.availableCount > 0) StatusTone.Positive else StatusTone.Warning,
                        )
                    }
                }
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sortedSeatNumbers.forEach { seatNumber ->
                        val isSelected =
                            selectedRoomId == room.roomId && selectedSeatNumber == seatNumber
                        val isRecommended = room.recommendedSeatNumber == seatNumber
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectSeat(room.roomId, room.roomName, seatNumber) },
                            label = {
                                Text(
                                    text = if (isRecommended) "$seatNumber · 推荐" else seatNumber,
                                )
                            },
                            shape = RoundedCornerShape(50),
                            colors = manualChipColors(),
                        )
                    }
                }
            } else {
                Text(
                    text = "点按展开座位列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DatePickerSheet(
    initialDate: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMillis = remember(initialDate) { parseDateToUtcMillis(initialDate) }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onConfirm(formatDate(it)) } ?: onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun TimePickerSheet(
    initialTime: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (hour, minute) = remember(initialTime) { parseTime(initialTime) }
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = "选择开始时间", style = MaterialTheme.typography.titleMedium)
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onConfirm(formatTime(state.hour, state.minute)) }) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

private fun parseDateToUtcMillis(date: String): Long? {
    if (date.isBlank()) return null
    return runCatching {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(date)?.time
    }.getOrNull()
}

private fun formatDate(utcMillis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date(utcMillis))
}

private fun parseTime(time: String): Pair<Int, Int> {
    val now = Calendar.getInstance()
    val parts = time.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: now.get(Calendar.HOUR_OF_DAY)
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
}

private fun formatTime(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour, minute)
