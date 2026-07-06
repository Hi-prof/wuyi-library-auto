package com.wuyi.libraryauto.ui.screen.seat

import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.viewmodel.ManualReservationUiState

internal data class ManualReservationFlowPresentation(
    val title: String,
    val subtitle: String,
    val statusBadgeLabel: String,
    val statusBadgeTone: StatusTone,
    val queryAction: ManualReservationActionPresentation,
    val accountListAction: ManualReservationActionPresentation,
    val addAccountAction: ManualReservationActionPresentation,
    val selectedSeatText: String,
    val reserveAction: ManualReservationActionPresentation,
)

internal data class ManualReservationActionPresentation(
    val label: String,
    val enabled: Boolean,
)

internal fun buildManualReservationFlowPresentation(
    uiState: ManualReservationUiState,
): ManualReservationFlowPresentation {
    val hasAccounts = uiState.accounts.isNotEmpty()
    val seatPicked = uiState.selectedSeatNumber.isNotBlank()
    return ManualReservationFlowPresentation(
        title = "手动预约",
        subtitle = "选择账号、时间和座位后立即提交预约",
        statusBadgeLabel =
            when {
                uiState.isSubmitting -> "预约中"
                uiState.isLoadingSeats -> "查询中"
                !hasAccounts -> "待选择账号"
                seatPicked -> "已选座位"
                uiState.rooms.isNotEmpty() -> "待选座位"
                else -> "待查询"
            },
        statusBadgeTone =
            when {
                uiState.isSubmitting || uiState.isLoadingSeats -> StatusTone.Info
                !hasAccounts -> StatusTone.Warning
                seatPicked -> StatusTone.Positive
                uiState.rooms.isNotEmpty() -> StatusTone.Info
                else -> StatusTone.Neutral
            },
        queryAction =
            ManualReservationActionPresentation(
                label = if (uiState.isLoadingSeats) "查询中..." else "查询可选座位",
                enabled = hasAccounts && !uiState.isLoadingSeats && !uiState.isSubmitting,
            ),
        accountListAction =
            ManualReservationActionPresentation(
                label = "账号列表",
                enabled = true,
            ),
        addAccountAction =
            ManualReservationActionPresentation(
                label = "先去添加账号",
                enabled = true,
            ),
        selectedSeatText =
            if (seatPicked) {
                "已选座位：${uiState.selectedRoomName} · ${uiState.selectedSeatNumber}"
            } else {
                "还没选中座位，请在下方自习室列表点选"
            },
        reserveAction =
            ManualReservationActionPresentation(
                label = if (uiState.isSubmitting) "预约中..." else "立即预约",
                enabled = seatPicked && !uiState.isSubmitting && !uiState.isLoadingSeats,
            ),
    )
}
