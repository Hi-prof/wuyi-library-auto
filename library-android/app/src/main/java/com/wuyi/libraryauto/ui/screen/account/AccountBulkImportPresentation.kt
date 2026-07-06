package com.wuyi.libraryauto.ui.screen.account

import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.account.BulkImportDialogState
import com.wuyi.libraryauto.ui.repository.account.BulkImportResult
import com.wuyi.libraryauto.ui.repository.account.toSummaryText

internal data class AccountBulkImportPresentation(
    val title: String,
    val subtitle: String,
    val inputLabel: String,
    val inputPlaceholder: String,
    val inputSupportingText: String,
    val inputEnabled: Boolean,
    val statusBadgeLabel: String,
    val statusBadgeTone: StatusTone,
    val resultSummary: String?,
    val resultTone: StatusTone,
    val submitAction: AccountBulkImportActionPresentation,
    val dismissAction: AccountBulkImportActionPresentation,
)

internal data class AccountBulkImportActionPresentation(
    val label: String,
    val enabled: Boolean,
)

internal fun buildAccountBulkImportPresentation(
    state: BulkImportDialogState,
): AccountBulkImportPresentation {
    val result = state.result
    val rawLineCount = state.rawText.lineSequence().count { line -> line.isNotBlank() }
    val resultTone = result?.toStatusTone() ?: StatusTone.Neutral
    return AccountBulkImportPresentation(
        title = "批量导入账号",
        subtitle = "支持冒号、逗号、空格分隔；密码为空时默认使用学号",
        inputLabel = "账号文本",
        inputPlaceholder = "20230001:password\n20230002",
        inputSupportingText =
            if (rawLineCount > 0) {
                "已输入 $rawLineCount 行"
            } else {
                "每行一个账号，空行和 # 注释会忽略"
            },
        inputEnabled = !state.isSubmitting,
        statusBadgeLabel =
            when {
                state.isSubmitting -> "导入中"
                result != null -> result.toStatusLabel()
                state.rawText.isBlank() -> "等待输入"
                else -> "待导入"
            },
        statusBadgeTone =
            when {
                state.isSubmitting -> StatusTone.Info
                result != null -> resultTone
                state.rawText.isBlank() -> StatusTone.Neutral
                else -> StatusTone.Info
            },
        resultSummary = result?.toSummaryText(),
        resultTone = resultTone,
        submitAction =
            AccountBulkImportActionPresentation(
                label = if (state.isSubmitting) "导入中..." else "开始导入",
                enabled = !state.isSubmitting && state.rawText.isNotBlank(),
            ),
        dismissAction =
            AccountBulkImportActionPresentation(
                label = if (result == null) "取消" else "关闭",
                enabled = !state.isSubmitting,
            ),
    )
}

private fun BulkImportResult.toStatusLabel(): String =
    when {
        acceptedCount > 0 && failedCount == 0 -> "导入完成"
        acceptedCount > 0 -> "部分完成"
        failedCount > 0 -> "需要处理"
        else -> "无可导入"
    }

private fun BulkImportResult.toStatusTone(): StatusTone =
    when {
        acceptedCount > 0 && failedCount == 0 -> StatusTone.Positive
        acceptedCount > 0 -> StatusTone.Warning
        failedCount > 0 -> StatusTone.Negative
        else -> StatusTone.Neutral
    }
