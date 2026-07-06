package com.wuyi.libraryauto.ui.screen.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone

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
    val presentation = buildAccountBulkActionBarPresentation(selectedCount)
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = presentation.exitAction.contentDescription,
                )
            }
        },
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = presentation.title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = presentation.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(
                    text = presentation.selectionBadgeLabel,
                    tone = presentation.selectionBadgeTone,
                )
            }
        },
        actions = {
            IconButton(
                onClick = onSelectAll,
                enabled = presentation.selectAllAction.enabled,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                    contentDescription = presentation.selectAllAction.contentDescription,
                )
            }
            IconButton(
                onClick = onExportSelected,
                enabled = presentation.exportAction.enabled,
            ) {
                Icon(
                    imageVector = Icons.Outlined.IosShare,
                    contentDescription = presentation.exportAction.contentDescription,
                )
            }
            IconButton(
                onClick = onDeleteSelected,
                enabled = presentation.deleteAction.enabled,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = presentation.deleteAction.contentDescription,
                    tint = bulkActionIconColor(presentation.deleteAction.tone),
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

@Composable
private fun bulkActionIconColor(tone: StatusTone) =
    when (tone) {
        StatusTone.Negative -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
