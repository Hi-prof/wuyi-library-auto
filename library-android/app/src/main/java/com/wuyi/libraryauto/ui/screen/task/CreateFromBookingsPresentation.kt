package com.wuyi.libraryauto.ui.screen.task

import com.wuyi.libraryauto.ui.viewmodel.CreateFromBookingsDialogState
import com.wuyi.libraryauto.ui.viewmodel.CreateFromBookingsRowUiState

internal data class CreateFromBookingsPresentation(
    val title: String,
    val subtitle: String,
    val confirmAction: CreateFromBookingsActionPresentation,
    val rows: List<CreateFromBookingsRowPresentation>,
)

internal data class CreateFromBookingsActionPresentation(
    val label: String,
    val enabled: Boolean,
)

internal data class CreateFromBookingsRowPresentation(
    val studentId: String,
    val bookingLabel: String,
    val detail: String,
    val planBadgeLabel: String,
    val selected: Boolean,
    val enabled: Boolean,
)

internal fun buildCreateFromBookingsPresentation(
    state: CreateFromBookingsDialogState,
): CreateFromBookingsPresentation {
    val creatableCount = state.rows.count(CreateFromBookingsRowUiState::canCreate)
    val selectedCount = state.rows.count { row -> row.selected && row.canCreate }
    return CreateFromBookingsPresentation(
        title = "根据当前预约创建自动任务",
        subtitle = "可创建 $creatableCount 个，已选 $selectedCount 个",
        confirmAction =
            CreateFromBookingsActionPresentation(
                label = if (state.saving) "创建中..." else "创建 $selectedCount 个",
                enabled = !state.loading && !state.saving && selectedCount > 0,
            ),
        rows = state.rows.map(::buildCreateFromBookingsRowPresentation),
    )
}

private fun buildCreateFromBookingsRowPresentation(
    row: CreateFromBookingsRowUiState,
): CreateFromBookingsRowPresentation {
    val bookingLabel =
        if (row.canCreate) {
            listOf(row.roomName, row.seatNumber)
                .filter(String::isNotBlank)
                .joinToString(" · ")
                .ifBlank { "当前预约" }
        } else {
            row.message.ifBlank { "当前没有可用预约" }
        }
    val detail =
        listOf(row.beginLabel, row.statusLabel)
            .filter(String::isNotBlank)
            .joinToString(" / ")
            .ifBlank { if (row.canCreate) "将创建连续自动任务" else row.studentId }
    return CreateFromBookingsRowPresentation(
        studentId = row.studentId,
        bookingLabel = bookingLabel,
        detail = detail,
        planBadgeLabel = if (row.hasExistingPlan) "已有任务" else "未创建",
        selected = row.selected,
        enabled = row.canCreate,
    )
}
