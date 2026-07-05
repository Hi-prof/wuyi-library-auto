@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.wuyi.libraryauto.ui.screen.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode
import com.wuyi.libraryauto.ui.screen.AppTimePickerSheet
import com.wuyi.libraryauto.ui.screen.seat.buildInitialExpandedRoomIds
import com.wuyi.libraryauto.ui.screen.seat.sortSeatNumbersForDisplay
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskDialogState
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskSeatOptionUiModel
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun AutomationTaskDialog(
    dialogState: AutomationTaskDialogState,
    accounts: List<SavedAccountEntry>,
    onDismiss: () -> Unit,
    onStudentChange: (String) -> Unit,
    onModeChange: (AutomationTaskMode) -> Unit,
    onRoomNameChange: (String) -> Unit,
    onSeatNumberChange: (String) -> Unit,
    onRefreshSeats: () -> Unit,
    onSeatPicked: (String, String) -> Unit,
    onCustomDateChange: (String) -> Unit,
    onCustomStartTimeChange: (String) -> Unit,
    onCustomEndTimeChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val handleClose = {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
        Unit
    }
    var datePickerVisible by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf<TimePickerTarget?>(null) }
    val presentation =
        buildAutomationTaskDialogPresentation(
            dialogState = dialogState,
            accounts = accounts,
        )

    ModalBottomSheet(
        onDismissRequest = handleClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DialogHeader(presentation)
            DialogSection(
                title = "账号",
                icon = Icons.Outlined.PersonOutline,
            ) {
                TaskDialogDropdownField(
                    label = "任务账号",
                    selectedValue = dialogState.selectedStudentId,
                    options = buildTaskDialogAccountOptions(accounts),
                    supportingText = presentation.accountSupportingText,
                    selectionKey = "account-picker",
                    onSelected = onStudentChange,
                )
            }
            DialogSection(
                title = "目标座位",
                icon = Icons.Outlined.MeetingRoom,
            ) {
                val historyRooms = dialogState.historyHints.map { it.roomName }
                val defaultRooms = dialogState.seatOptions.map { it.roomName }
                val mergedRooms =
                    mergeAutomationTaskRoomOptions(historyRooms, defaultRooms, DefaultAutomationTaskRoomNames)
                TaskDialogDropdownField(
                    label = "目标自习室",
                    selectedValue = dialogState.roomName,
                    options = buildTaskDialogRoomOptions(mergedRooms),
                    supportingText = "可直接选择默认自习室，也可刷新带出实时座位",
                    selectionKey = "room-picker",
                    onSelected = onRoomNameChange,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onRefreshSeats,
                        enabled = presentation.refreshSeatAction.enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(presentation.refreshSeatAction.label)
                    }
                }
                OutlinedTextField(
                    value = dialogState.seatNumber,
                    onValueChange = onSeatNumberChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("当前座位") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Chair, contentDescription = null)
                    },
                    shape = RoundedCornerShape(14.dp),
                    supportingText = {
                        Text("可直接输入，也可在下方选择回填", style = MaterialTheme.typography.labelSmall)
                    },
                    singleLine = true,
                    colors = taskInputColors(),
                )
                if (dialogState.seatOptions.isNotEmpty()) {
                    val historySeatNumbers =
                        dialogState.historyHints
                            .filter { it.roomName == dialogState.roomName }
                            .map { it.seatNumber }
                            .toSet()
                    SeatOptionSection(
                        seatOptions = dialogState.seatOptions,
                        selectionKey = dialogState.selectedStudentId,
                        selectedRoomName = dialogState.roomName,
                        selectedSeatNumber = dialogState.seatNumber,
                        historySeatNumbers = historySeatNumbers,
                        onSeatPicked = onSeatPicked,
                    )
                }
            }
            DialogSection(
                title = "执行模式",
                icon = Icons.Outlined.EventRepeat,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = dialogState.mode == AutomationTaskMode.CONTINUOUS,
                        onClick = { onModeChange(AutomationTaskMode.CONTINUOUS) },
                        label = { Text("持续预约") },
                        shape = RoundedCornerShape(50),
                        colors = taskChipColors(),
                    )
                    FilterChip(
                        selected = dialogState.mode == AutomationTaskMode.SINGLE_CUSTOM,
                        onClick = { onModeChange(AutomationTaskMode.SINGLE_CUSTOM) },
                        label = { Text("单次自定义时间") },
                        shape = RoundedCornerShape(50),
                        colors = taskChipColors(),
                    )
                }
                if (dialogState.mode == AutomationTaskMode.CONTINUOUS) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = presentation.modeSummaryText,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = presentation.modeSummaryText,
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TaskPickerRow(
                            label = "日期",
                            value = dialogState.customDate.ifBlank { "选择日期" },
                            icon = Icons.Outlined.CalendarMonth,
                            onClick = { datePickerVisible = true },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TaskPickerRow(
                                label = "开始时间",
                                value = dialogState.customStartTime.ifBlank { "开始" },
                                icon = Icons.Outlined.AccessTime,
                                modifier = Modifier.weight(1f),
                                onClick = { timePicker = TimePickerTarget.Start },
                            )
                            TaskPickerRow(
                                label = "结束时间",
                                value = dialogState.customEndTime.ifBlank { "结束" },
                                icon = Icons.Outlined.AccessTime,
                                modifier = Modifier.weight(1f),
                                onClick = { timePicker = TimePickerTarget.End },
                            )
                        }
                    }
                }
            }
            dialogState.dialogMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = handleClose,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onSave,
                    enabled = presentation.saveAction.enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(presentation.saveAction.label)
                }
            }
        }
    }

    if (datePickerVisible) {
        TaskDatePickerSheet(
            initialDate = dialogState.customDate,
            onConfirm = { date ->
                onCustomDateChange(date)
                datePickerVisible = false
            },
            onDismiss = { datePickerVisible = false },
        )
    }
    timePicker?.let { target ->
        val initial =
            when (target) {
                TimePickerTarget.Start -> dialogState.customStartTime
                TimePickerTarget.End -> dialogState.customEndTime
            }
        AppTimePickerSheet(
            initialTime = initial,
            title = if (target == TimePickerTarget.Start) "选择开始时间" else "选择结束时间",
            onConfirm = { time ->
                when (target) {
                    TimePickerTarget.Start -> onCustomStartTimeChange(time)
                    TimePickerTarget.End -> onCustomEndTimeChange(time)
                }
                timePicker = null
            },
            onDismiss = { timePicker = null },
        )
    }
}

private enum class TimePickerTarget { Start, End }

@Composable
private fun DialogHeader(presentation: AutomationTaskDialogPresentation) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
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
                text = presentation.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusBadge(
            text = presentation.modeBadgeLabel,
            tone = presentation.modeBadgeTone,
        )
    }
}

@Composable
private fun DialogSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        content()
    }
}

@Composable
private fun TaskPickerRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 56.dp).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun taskInputColors() =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )

@Composable
private fun taskChipColors() =
    FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
    )

data class TaskDialogDropdownOption(
    val value: String,
    val label: String,
)

fun buildTaskDialogAccountOptions(accounts: List<SavedAccountEntry>): List<TaskDialogDropdownOption> =
    accounts.map { account ->
        TaskDialogDropdownOption(value = account.studentId, label = account.studentId)
    }

fun buildTaskDialogRoomOptions(roomNames: List<String>): List<TaskDialogDropdownOption> =
    roomNames.map { roomName ->
        TaskDialogDropdownOption(value = roomName, label = roomName)
    }

fun mergeAutomationTaskRoomOptions(
    vararg roomLists: List<String>,
): List<String> =
    roomLists
        .asSequence()
        .flatMap { it.asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

@Composable
private fun TaskDialogDropdownField(
    label: String,
    selectedValue: String,
    options: List<TaskDialogDropdownOption>,
    supportingText: String,
    selectionKey: String,
    onSelected: (String) -> Unit,
) {
    var expanded by rememberSaveable(selectionKey, selectedValue, options.map(TaskDialogDropdownOption::value)) {
        mutableStateOf(false)
    }
    val enabled = options.isNotEmpty()
    val menuExpanded = expanded && enabled
    val selectedLabel =
        options.firstOrNull { option -> option.value == selectedValue }?.label ?: selectedValue

    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { shouldExpand -> if (enabled) expanded = shouldExpand },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            supportingText = { Text(supportingText, style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
            colors = taskInputColors(),
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    },
                )
            }
        }
    }
}

@Composable
private fun SeatOptionSection(
    seatOptions: List<AutomationTaskSeatOptionUiModel>,
    selectionKey: String,
    selectedRoomName: String,
    selectedSeatNumber: String,
    historySeatNumbers: Set<String>,
    onSeatPicked: (String, String) -> Unit,
) {
    val visibleSeatOptions =
        seatOptions
            .filter { room -> selectedRoomName.isBlank() || room.roomName == selectedRoomName }
            .ifEmpty { seatOptions }
    val roomNames =
        remember(visibleSeatOptions) { visibleSeatOptions.map(AutomationTaskSeatOptionUiModel::roomName) }
    var expandedRoomNames by rememberSaveable(selectionKey, roomNames, selectedRoomName) {
        mutableStateOf(buildInitialExpandedRoomIds(roomNames, selectedRoomName).toList())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "可选座位",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        visibleSeatOptions.forEach { room ->
            val expanded = expandedRoomNames.contains(room.roomName)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedRoomNames =
                                        if (expanded) expandedRoomNames - room.roomName
                                        else expandedRoomNames + room.roomName
                                },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = room.roomName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
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
                            sortSeatNumbersForDisplay(room.seatNumbers).forEach { seatNumber ->
                                val isSelected =
                                    selectedRoomName == room.roomName && selectedSeatNumber == seatNumber
                                val isRecommended = room.recommendedSeatNumber == seatNumber
                                val historyMark = seatNumber in historySeatNumbers
                                val tail =
                                    listOfNotNull(
                                        if (isRecommended) "推荐" else null,
                                        if (historyMark) "曾用" else null,
                                    ).joinToString(" · ").let { suffix -> if (suffix.isBlank()) "" else " · $suffix" }
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSeatPicked(room.roomName, seatNumber) },
                                    label = { Text("$seatNumber$tail") },
                                    shape = RoundedCornerShape(50),
                                    colors = taskChipColors(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDatePickerSheet(
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
