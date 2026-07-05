package com.wuyi.libraryauto.ui.screen.account

import com.wuyi.libraryauto.ui.components.StatusTone

internal data class AccountDeleteConfirmationPresentation(
    val title: String,
    val message: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
    val confirmAction: AccountDeleteConfirmationActionPresentation,
    val dismissAction: AccountDeleteConfirmationActionPresentation,
)

internal data class AccountDeleteConfirmationActionPresentation(
    val label: String,
)

internal fun buildSingleAccountDeleteConfirmationPresentation(
    studentId: String,
): AccountDeleteConfirmationPresentation =
    AccountDeleteConfirmationPresentation(
        title = "删除账号",
        message = "确定删除账号 ${studentId.trim()} 吗？删除后会同时移除该账号保存的会话，无法撤销。",
        badgeLabel = "不可撤销",
        badgeTone = StatusTone.Negative,
        confirmAction = AccountDeleteConfirmationActionPresentation(label = "确认删除"),
        dismissAction = AccountDeleteConfirmationActionPresentation(label = "取消"),
    )

internal fun buildBulkAccountDeleteConfirmationPresentation(
    selectedCount: Int,
): AccountDeleteConfirmationPresentation {
    val safeSelectedCount = selectedCount.coerceAtLeast(0)
    return AccountDeleteConfirmationPresentation(
        title = "删除所选账号",
        message = "将删除 $safeSelectedCount 个账号，删除后会同时移除会话，无法撤销。",
        badgeLabel = "$safeSelectedCount 个账号",
        badgeTone = StatusTone.Negative,
        confirmAction = AccountDeleteConfirmationActionPresentation(label = "确认删除"),
        dismissAction = AccountDeleteConfirmationActionPresentation(label = "取消"),
    )
}
