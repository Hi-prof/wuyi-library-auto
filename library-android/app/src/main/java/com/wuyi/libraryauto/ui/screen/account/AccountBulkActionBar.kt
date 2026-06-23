package com.wuyi.libraryauto.ui.screen.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wuyi.libraryauto.R

/**
 * 多选模式顶栏（Contextual Action Bar）。
 *
 * 触发多选后接管整个顶栏，明确告知用户"已进入多选模式"，并把全选 / 导出 / 删除等动作
 * 收为右上角图标按钮，避免和正常列表上的卡片按钮误触。
 *
 * 危险操作 `onDeleteSelected` 在父层会再二次弹窗确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountBulkActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onExportSelected: () -> Unit,
    onExit: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.account_bulk_action_exit),
                )
            }
        },
        title = {
            Text(
                text =
                    if (selectedCount > 0) {
                        "已选 $selectedCount 项"
                    } else {
                        stringResource(R.string.account_bulk_action_nothing_selected)
                    },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                    contentDescription = stringResource(R.string.account_bulk_action_select_all),
                )
            }
            IconButton(
                onClick = onExportSelected,
                enabled = selectedCount > 0,
            ) {
                Icon(
                    imageVector = Icons.Outlined.IosShare,
                    contentDescription = stringResource(R.string.account_bulk_action_export_selected),
                )
            }
            IconButton(
                onClick = onDeleteSelected,
                enabled = selectedCount > 0,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.account_bulk_action_delete_selected),
                    tint =
                        if (selectedCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    )
}
