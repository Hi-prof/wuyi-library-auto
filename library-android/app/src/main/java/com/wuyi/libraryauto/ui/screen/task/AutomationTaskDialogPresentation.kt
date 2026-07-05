package com.wuyi.libraryauto.ui.screen.task

import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskDialogState
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry

internal data class AutomationTaskDialogPresentation(
    val title: String,
    val subtitle: String,
    val modeBadgeLabel: String,
    val modeBadgeTone: StatusTone,
    val accountSupportingText: String,
    val refreshSeatAction: AutomationTaskDialogActionPresentation,
    val modeSummaryText: String,
    val saveAction: AutomationTaskDialogActionPresentation,
)

internal data class AutomationTaskDialogActionPresentation(
    val label: String,
    val enabled: Boolean,
)

internal fun buildAutomationTaskDialogPresentation(
    dialogState: AutomationTaskDialogState,
    accounts: List<SavedAccountEntry>,
): AutomationTaskDialogPresentation {
    val refreshingSeats = dialogState.isRefreshingSeats
    return AutomationTaskDialogPresentation(
        title = "添加自动任务",
        subtitle = "选择账号、目标座位与执行模式，保存后任务会自动执行",
        modeBadgeLabel =
            when (dialogState.mode) {
                AutomationTaskMode.CONTINUOUS -> "持续预约"
                AutomationTaskMode.SINGLE_CUSTOM -> "单次任务"
            },
        modeBadgeTone =
            when (dialogState.mode) {
                AutomationTaskMode.CONTINUOUS -> StatusTone.Info
                AutomationTaskMode.SINGLE_CUSTOM -> StatusTone.Warning
            },
        accountSupportingText =
            if (accounts.isEmpty()) {
                "当前没有可用账号"
            } else {
                "切换账号后会带出该账号的默认座位与历史"
            },
        refreshSeatAction =
            AutomationTaskDialogActionPresentation(
                label = if (refreshingSeats) "查询中..." else "刷新座位",
                enabled = !refreshingSeats,
            ),
        modeSummaryText =
            when (dialogState.mode) {
                AutomationTaskMode.CONTINUOUS ->
                    dialogState.previewText.ifBlank { "持续预约：跟随系统排程自动续约" }
                AutomationTaskMode.SINGLE_CUSTOM -> "单次任务：按指定日期和时间段执行一次"
            },
        saveAction =
            AutomationTaskDialogActionPresentation(
                label = "创建自动任务",
                enabled = !refreshingSeats,
            ),
    )
}
