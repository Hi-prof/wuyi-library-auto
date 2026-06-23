// 撤回 spec account-pool-tri-sync 12.4 中的 ConnectivityGate 阻塞改造；本地执行不再依赖服务端可达性
package com.wuyi.libraryauto.ui.screen.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.task.AccountSeatAction
import com.wuyi.libraryauto.ui.viewmodel.AccountBookingEntry
import com.wuyi.libraryauto.ui.viewmodel.AccountDetailDialogState
import com.wuyi.libraryauto.ui.viewmodel.AccountPendingAction
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.pendingActionLabel
import kotlinx.coroutines.launch

/**
 * `AccountDetailDialog` 关闭过渡动画时长（毫秒）。
 *
 * 对外保留常量，给依赖该值的现有调用点保持兼容；ModalBottomSheet 自身的关闭动画由
 * [rememberModalBottomSheetState] 管理，无需单独驱动 alpha 渐隐。
 */
internal const val ACCOUNT_DETAIL_DIALOG_CLOSE_ANIMATION_MILLIS: Int = 600

/**
 * 账号详情：从 AlertDialog 改为 [ModalBottomSheet]，原因：
 * - 内容多按钮场景下底部弹层比中间对话框更省屏幕空间；
 * - 拖动 / 点击空白即可关闭，避免误触"关闭"按钮；
 * - 危险动作（删除账号）独立分组，并和正向操作有视觉间隔。
 *
 * 行为保持与原 AlertDialog 一致：
 * - `pendingForCurrentAccount = true` 时除关闭外按钮均禁用；命中 `pendingAction` 的按钮显示进行中文案；
 * - "刷新预约状态"按钮使用 [AccountDetailDialogState.refreshing] 互斥；
 * - [account] 为 null 时仅渲染占位文案与关闭按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailDialog(
    state: AccountDetailDialogState,
    account: SavedAccountEntry?,
    pendingAction: AccountPendingAction?,
    pendingBookingId: String?,
    pendingForCurrentAccount: Boolean,
    onClose: () -> Unit,
    onRefreshStatus: () -> Unit,
    onPrimaryAction: (AccountSeatAction) -> Unit,
    onSecondaryAction: (AccountSeatAction) -> Unit,
    onBookingAction: (AccountSeatAction, String) -> Unit,
    onSetCurrent: () -> Unit,
    onRefreshAuth: () -> Unit,
    onOpenTasks: () -> Unit,
    onRemove: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val handleClose: () -> Unit = {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) onClose()
        }
    }

    ModalBottomSheet(
        onDismissRequest = handleClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDragHandle() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            DetailHeader(
                studentId = account?.studentId ?: state.studentId,
                isAuthenticated = account?.isAuthenticated == true,
            )
            if (account == null) {
                Text(
                    text = "账号信息已不可用，可能已被移除。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IdentityInfoSection(account = account)
                BookingsSection(
                    bookings = account.activeBookings,
                    pendingAction = pendingAction,
                    pendingBookingId = pendingBookingId,
                    pendingForCurrentAccount = pendingForCurrentAccount,
                    onBookingAction = onBookingAction,
                )
                if (account.activeBookings.isEmpty() &&
                    (account.primaryAction != null || account.secondaryAction != null)
                ) {
                    AggregateSeatActionRow(
                        account = account,
                        pendingAction = pendingAction,
                        pendingForCurrentAccount = pendingForCurrentAccount,
                        onPrimaryAction = onPrimaryAction,
                        onSecondaryAction = onSecondaryAction,
                    )
                }
                AccountActionsSection(
                    account = account,
                    state = state,
                    pendingAction = pendingAction,
                    pendingForCurrentAccount = pendingForCurrentAccount,
                    onSetCurrent = onSetCurrent,
                    onRefreshAuth = onRefreshAuth,
                    onRefreshStatus = onRefreshStatus,
                    onOpenTasks = onOpenTasks,
                )
                FooterMessage(message = state.dialogMessage)
                DangerZone(
                    pendingForCurrentAccount = pendingForCurrentAccount,
                    onRemove = onRemove,
                )
            }
            CloseRow(handleClose = handleClose)
        }
    }
}
@Composable
private fun BottomSheetDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(40.dp).heightIn(min = 4.dp),
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        ) {
            Spacer(Modifier.heightIn(min = 4.dp))
        }
    }
}

@Composable
private fun DetailHeader(
    studentId: String,
    isAuthenticated: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(studentId = studentId)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = studentId.ifBlank { "未知账号" },
                style = MaterialTheme.typography.titleLarge,
            )
            StatusBadge(
                text = if (isAuthenticated) "已认证" else "未认证",
                tone = if (isAuthenticated) StatusTone.Info else StatusTone.Warning,
                icon = Icons.Outlined.VerifiedUser,
            )
        }
    }
}

@Composable
private fun IdentityInfoSection(account: SavedAccountEntry) {
    val rows =
        listOf(
            "学号" to account.studentId,
            "目标座位" to account.preferredSeatLabel.ifBlank { "暂无" },
            "当前预约" to account.currentBookingLabel.ifBlank { "暂无" },
            "状态摘要" to account.statusSummary.ifBlank { "暂无" },
        )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookingsSection(
    bookings: List<AccountBookingEntry>,
    pendingAction: AccountPendingAction?,
    pendingBookingId: String?,
    pendingForCurrentAccount: Boolean,
    onBookingAction: (AccountSeatAction, String) -> Unit,
) {
    if (bookings.isEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.EventBusy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "暂无活跃预约",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "当前预约 · ${bookings.size}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        bookings.forEach { booking ->
            BookingRow(
                booking = booking,
                pendingAction = pendingAction,
                pendingBookingId = pendingBookingId,
                pendingForCurrentAccount = pendingForCurrentAccount,
                onBookingAction = onBookingAction,
            )
        }
    }
}

@Composable
private fun BookingRow(
    booking: AccountBookingEntry,
    pendingAction: AccountPendingAction?,
    pendingBookingId: String?,
    pendingForCurrentAccount: Boolean,
    onBookingAction: (AccountSeatAction, String) -> Unit,
) {
    val isThisBookingPending = pendingBookingId != null && pendingBookingId == booking.bookingId
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text =
                    listOf(booking.roomName, booking.seatNumber, booking.beginLabel)
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                        .ifBlank { "预约 ${booking.bookingId}" },
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    text = booking.statusLabel,
                    tone = bookingStatusTone(booking),
                    icon = Icons.Outlined.Schedule,
                )
                if (booking.actionHint.isNotBlank()) {
                    Text(
                        text = booking.actionHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val primary = booking.primaryAction
            val secondary = booking.secondaryAction
            if (primary != null || secondary != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (primary != null) {
                        Button(
                            onClick = { onBookingAction(primary, booking.bookingId) },
                            enabled =
                                !pendingForCurrentAccount &&
                                    booking.bookingId.isNotBlank() &&
                                    booking.primaryActionEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        ) {
                            Icon(
                                imageVector = seatActionIcon(primary),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text =
                                    bookingActionLabel(
                                        action = primary,
                                        pendingAction = pendingAction,
                                        isThisBookingPending = isThisBookingPending,
                                        default = booking.primaryActionLabel,
                                    ),
                            )
                        }
                    }
                    if (secondary != null) {
                        OutlinedButton(
                            onClick = { onBookingAction(secondary, booking.bookingId) },
                            enabled =
                                !pendingForCurrentAccount && booking.bookingId.isNotBlank(),
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        ) {
                            Icon(
                                imageVector = seatActionIcon(secondary),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text =
                                    bookingActionLabel(
                                        action = secondary,
                                        pendingAction = pendingAction,
                                        isThisBookingPending = isThisBookingPending,
                                        default = booking.secondaryActionLabel,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun bookingStatusTone(booking: AccountBookingEntry): StatusTone =
    when {
        booking.statusLabel.contains("已签到") -> StatusTone.Positive
        booking.statusLabel.contains("待签到") -> StatusTone.Info
        booking.statusLabel.contains("已结束") || booking.statusLabel.contains("失败") -> StatusTone.Neutral
        else -> StatusTone.Info
    }

private fun seatActionIcon(action: AccountSeatAction): ImageVector =
    when (action) {
        AccountSeatAction.CheckIn -> Icons.AutoMirrored.Outlined.Login
        AccountSeatAction.Checkout -> Icons.AutoMirrored.Outlined.Logout
        AccountSeatAction.CancelBooking -> Icons.Outlined.Cancel
    }

private fun bookingActionLabel(
    action: AccountSeatAction,
    pendingAction: AccountPendingAction?,
    isThisBookingPending: Boolean,
    default: String,
): String {
    if (!isThisBookingPending) return default
    return when (action) {
        AccountSeatAction.CheckIn ->
            if (pendingAction == AccountPendingAction.CheckIn) "签到中..." else default
        AccountSeatAction.Checkout ->
            if (pendingAction == AccountPendingAction.Checkout) "签退中..." else default
        AccountSeatAction.CancelBooking ->
            if (pendingAction == AccountPendingAction.CancelBooking) "取消中..." else default
    }
}

@Composable
private fun AggregateSeatActionRow(
    account: SavedAccountEntry,
    pendingAction: AccountPendingAction?,
    pendingForCurrentAccount: Boolean,
    onPrimaryAction: (AccountSeatAction) -> Unit,
    onSecondaryAction: (AccountSeatAction) -> Unit,
) {
    val primary = account.primaryAction
    val secondary = account.secondaryAction
    if (primary == null && secondary == null) {
        if (account.actionHint.isNotBlank()) {
            Text(
                text = account.actionHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (primary != null) {
            Button(
                onClick = { onPrimaryAction(primary) },
                enabled = !pendingForCurrentAccount && account.primaryActionEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(
                    imageVector = seatActionIcon(primary),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(seatActionLabel(primary, pendingAction, account.primaryActionLabel))
            }
        }
        if (secondary != null) {
            OutlinedButton(
                onClick = { onSecondaryAction(secondary) },
                enabled = !pendingForCurrentAccount,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(
                    imageVector = seatActionIcon(secondary),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(seatActionLabel(secondary, pendingAction, account.secondaryActionLabel))
            }
        }
    }
}

private fun seatActionLabel(
    action: AccountSeatAction,
    pendingAction: AccountPendingAction?,
    default: String,
): String =
    when (action) {
        AccountSeatAction.CheckIn ->
            if (pendingAction == AccountPendingAction.CheckIn) "签到中..." else default
        AccountSeatAction.Checkout ->
            if (pendingAction == AccountPendingAction.Checkout) "签退中..." else default
        AccountSeatAction.CancelBooking ->
            if (pendingAction == AccountPendingAction.CancelBooking) "取消中..." else default
    }

@Composable
private fun AccountActionsSection(
    account: SavedAccountEntry,
    state: AccountDetailDialogState,
    pendingAction: AccountPendingAction?,
    pendingForCurrentAccount: Boolean,
    onSetCurrent: () -> Unit,
    onRefreshAuth: () -> Unit,
    onRefreshStatus: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    val setCurrentDefault =
        when {
            account.isActive -> "已激活"
            else -> "激活此账号"
        }
    val setCurrentLabel =
        if (pendingAction == AccountPendingAction.SetCurrent) {
            pendingActionLabel(pendingAction, setCurrentDefault) ?: setCurrentDefault
        } else {
            setCurrentDefault
        }

    val refreshAuthDefault = if (account.isAuthenticated) "刷新认证" else "认证账号"
    val refreshAuthLabel =
        if (pendingAction == AccountPendingAction.RefreshAuth) {
            pendingActionLabel(pendingAction, refreshAuthDefault) ?: refreshAuthDefault
        } else {
            refreshAuthDefault
        }

    val refreshStatusLabel = if (state.refreshing) "刷新中..." else "刷新预约状态"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "账号管理",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onSetCurrent,
                enabled = !pendingForCurrentAccount && !account.isActive,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.SwitchAccount,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(setCurrentLabel)
            }
            FilledTonalButton(
                onClick = onRefreshAuth,
                enabled = !pendingForCurrentAccount,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(refreshAuthLabel)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onRefreshStatus,
                enabled = !pendingForCurrentAccount && !state.refreshing,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(refreshStatusLabel)
            }
            OutlinedButton(
                onClick = onOpenTasks,
                enabled = !pendingForCurrentAccount,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.TaskAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("自动任务")
            }
        }
    }
}

@Composable
private fun FooterMessage(message: String?) {
    if (message.isNullOrBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AssignmentTurnedIn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun DangerZone(
    pendingForCurrentAccount: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "危险区域",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "删除账号将同时移除该账号的会话与本地凭据，无法撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(
                onClick = onRemove,
                enabled = !pendingForCurrentAccount,
                modifier = Modifier.heightIn(min = 56.dp),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("删除账号")
            }
        }
    }
}

@Composable
private fun CloseRow(handleClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        FilledTonalButton(
            onClick = handleClose,
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("关闭")
        }
    }
}
