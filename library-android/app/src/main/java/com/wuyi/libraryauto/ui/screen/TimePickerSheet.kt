package com.wuyi.libraryauto.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar

internal data class TimePickerSheetPresentation(
    val title: String,
    val dismissAction: TimePickerSheetActionPresentation,
    val confirmAction: TimePickerSheetActionPresentation,
)

internal data class TimePickerSheetActionPresentation(
    val label: String,
)

internal fun buildTimePickerSheetPresentation(title: String): TimePickerSheetPresentation =
    TimePickerSheetPresentation(
        title = title.trim().ifBlank { "选择时间" },
        dismissAction = TimePickerSheetActionPresentation(label = "取消"),
        confirmAction = TimePickerSheetActionPresentation(label = "确定"),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTimePickerSheet(
    initialTime: String,
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = remember(title) { buildTimePickerSheetPresentation(title) }
    val (hour, minute) = remember(initialTime) { parseTime(initialTime) }
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)

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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = presentation.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            TimePicker(state = state)
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
                Button(
                    onClick = { onConfirm(formatTime(state.hour, state.minute)) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(presentation.confirmAction.label)
                }
            }
        }
    }
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
