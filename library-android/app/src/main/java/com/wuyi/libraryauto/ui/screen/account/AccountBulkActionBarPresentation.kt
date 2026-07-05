package com.wuyi.libraryauto.ui.screen.account

import com.wuyi.libraryauto.ui.components.StatusTone

internal data class AccountBulkActionBarPresentation(
    val title: String,
    val subtitle: String,
    val selectionBadgeLabel: String,
    val selectionBadgeTone: StatusTone,
    val exitAction: AccountBulkActionPresentation,
    val selectAllAction: AccountBulkActionPresentation,
    val exportAction: AccountBulkActionPresentation,
    val deleteAction: AccountBulkActionPresentation,
)

internal data class AccountBulkActionPresentation(
    val contentDescription: String,
    val enabled: Boolean,
    val tone: StatusTone = StatusTone.Neutral,
)

internal fun buildAccountBulkActionBarPresentation(
    selectedCount: Int,
): AccountBulkActionBarPresentation {
    val safeSelectedCount = selectedCount.coerceAtLeast(0)
    val hasSelection = safeSelectedCount > 0
    return AccountBulkActionBarPresentation(
        title = "选择账号",
        subtitle = if (hasSelection) "已选 $safeSelectedCount 项" else "选择要批量处理的账号",
        selectionBadgeLabel = if (hasSelection) "$safeSelectedCount 项" else "未选择",
        selectionBadgeTone = if (hasSelection) StatusTone.Info else StatusTone.Neutral,
        exitAction =
            AccountBulkActionPresentation(
                contentDescription = "退出多选",
                enabled = true,
            ),
        selectAllAction =
            AccountBulkActionPresentation(
                contentDescription = "全选",
                enabled = true,
            ),
        exportAction =
            AccountBulkActionPresentation(
                contentDescription =
                    if (hasSelection) "导出 $safeSelectedCount 个账号" else "先选择账号再导出",
                enabled = hasSelection,
                tone = if (hasSelection) StatusTone.Info else StatusTone.Neutral,
            ),
        deleteAction =
            AccountBulkActionPresentation(
                contentDescription =
                    if (hasSelection) "删除 $safeSelectedCount 个账号" else "先选择账号再删除",
                enabled = hasSelection,
                tone = if (hasSelection) StatusTone.Negative else StatusTone.Neutral,
            ),
    )
}
