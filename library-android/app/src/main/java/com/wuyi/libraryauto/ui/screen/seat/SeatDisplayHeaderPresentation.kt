package com.wuyi.libraryauto.ui.screen.seat

import com.wuyi.libraryauto.ui.components.StatusTone

internal data class SeatDisplayHeaderPresentation(
    val title: String,
    val subtitle: String,
    val badges: List<SeatDisplayHeaderBadgePresentation>,
    val refreshAction: SeatDisplayHeaderActionPresentation,
    val checkInAction: SeatDisplayHeaderActionPresentation?,
    val makeupReservationAction: SeatDisplayHeaderActionPresentation?,
)

internal data class SeatDisplayHeaderBadgePresentation(
    val label: String,
    val tone: StatusTone,
)

internal data class SeatDisplayHeaderActionPresentation(
    val label: String,
    val enabled: Boolean,
)

internal fun buildSeatDisplayHeaderPresentation(
    cardCount: Int,
    waitingSignIn: Int,
    activeSignedIn: Int,
    isRefreshing: Boolean,
    isBatchCheckingIn: Boolean,
    isBatchReserving: Boolean,
): SeatDisplayHeaderPresentation {
    val busy = isRefreshing || isBatchCheckingIn || isBatchReserving
    return SeatDisplayHeaderPresentation(
        title = "座位状态",
        subtitle = "集中查看账号座位、签到和补约状态",
        badges =
            buildList {
                add(SeatDisplayHeaderBadgePresentation("$cardCount 个账号", StatusTone.Info))
                if (waitingSignIn > 0) {
                    add(SeatDisplayHeaderBadgePresentation("$waitingSignIn 待签到", StatusTone.Warning))
                }
                if (activeSignedIn > 0) {
                    add(SeatDisplayHeaderBadgePresentation("$activeSignedIn 已签到", StatusTone.Positive))
                }
            },
        refreshAction =
            SeatDisplayHeaderActionPresentation(
                label = if (isRefreshing) "刷新中..." else "刷新全部",
                enabled = cardCount > 0 && !busy,
            ),
        checkInAction =
            if (waitingSignIn > 0) {
                SeatDisplayHeaderActionPresentation(
                    label = if (isBatchCheckingIn) "签到中..." else "一键签到($waitingSignIn)",
                    enabled = !busy,
                )
            } else {
                null
            },
        makeupReservationAction =
            if (cardCount > 0) {
                SeatDisplayHeaderActionPresentation(
                    label = if (isBatchReserving) "补约中..." else "一键补约（今天+未来2天）",
                    enabled = !busy,
                )
            } else {
                null
            },
    )
}
