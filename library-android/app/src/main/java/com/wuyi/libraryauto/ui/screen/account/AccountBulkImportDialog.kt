package com.wuyi.libraryauto.ui.screen.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuyi.libraryauto.R
import com.wuyi.libraryauto.ui.repository.account.BulkImportDialogState
import com.wuyi.libraryauto.ui.repository.account.toSummaryText

@Composable
fun AccountBulkImportDialog(
    state: BulkImportDialogState,
    onRawTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.isVisible) {
        return
    }
    AlertDialog(
        onDismissRequest = {
            if (!state.isSubmitting) {
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.account_bulk_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.rawText,
                    onValueChange = onRawTextChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                    minLines = 6,
                    enabled = !state.isSubmitting,
                    placeholder = { Text("20230001:password") },
                )
                state.result?.let { result ->
                    Text(
                        text = result.toSummaryText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = !state.isSubmitting && state.rawText.isNotBlank(),
            ) {
                Text(
                    if (state.isSubmitting) {
                        "导入中…"
                    } else {
                        stringResource(R.string.account_bulk_import_confirm)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isSubmitting,
            ) {
                Text(stringResource(R.string.account_bulk_import_cancel))
            }
        },
    )
}
