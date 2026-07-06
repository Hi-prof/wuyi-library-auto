package com.wuyi.libraryauto.ui.screen.account

import com.wuyi.libraryauto.ui.viewmodel.AccountRefreshAllState
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInState

internal data class AccountTopBarPresentation(
    val title: String,
    val subtitle: String,
    val refreshAction: AccountTopBarActionPresentation,
    val batchCheckInAction: AccountTopBarActionPresentation?,
    val importAction: AccountTopBarActionPresentation,
    val multiSelectAction: AccountTopBarActionPresentation,
)

internal data class AccountTopBarActionPresentation(
    val label: String,
    val contentDescription: String = label,
    val enabled: Boolean,
)

internal fun buildAccountTopBarPresentation(
    accountCount: Int,
    refreshAllState: AccountRefreshAllState,
    batchCheckInState: BatchCheckInState,
    isAnyBatchActive: Boolean,
    batchCheckInEnabled: Boolean,
): AccountTopBarPresentation =
    AccountTopBarPresentation(
        title = "账号列表",
        subtitle = "共 $accountCount 个账号",
        refreshAction =
            AccountTopBarActionPresentation(
                label = if (refreshAllState is AccountRefreshAllState.Running) "刷新中..." else "刷新全部",
                contentDescription =
                    if (refreshAllState is AccountRefreshAllState.Running) {
                        "刷新中..."
                    } else {
                        "刷新全部账号状态"
                    },
                enabled = accountCount > 0 && !isAnyBatchActive,
            ),
        batchCheckInAction =
            if (batchCheckInEnabled) {
                AccountTopBarActionPresentation(
                    label = if (batchCheckInState is BatchCheckInState.Running) "签到中..." else "全部签到",
                    enabled = accountCount > 0 && !isAnyBatchActive,
                )
            } else {
                null
            },
        importAction =
            AccountTopBarActionPresentation(
                label = "批量导入账号",
                enabled = !isAnyBatchActive,
            ),
        multiSelectAction =
            AccountTopBarActionPresentation(
                label = "多选管理",
                enabled = accountCount > 0 && !isAnyBatchActive,
            ),
    )
