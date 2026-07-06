package com.wuyi.libraryauto.ui.screen.home

import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.home.TodayOverviewSnapshot

internal data class TodayOverviewPresentation(
    val title: String,
    val subtitle: String,
    val statusTone: StatusTone,
    val checkReservationAction: TodayOverviewActionPresentation,
    val checkInAction: TodayOverviewActionPresentation,
    val metrics: List<TodayOverviewMetricPresentation>,
)

internal data class TodayOverviewActionPresentation(
    val label: String,
    val enabled: Boolean,
)

internal data class TodayOverviewMetricPresentation(
    val title: String,
    val value: String,
)

internal fun buildTodayOverviewPresentation(
    snapshot: TodayOverviewSnapshot,
    isCheckingReservations: Boolean,
    isSigningIn: Boolean,
): TodayOverviewPresentation {
    val operationRunning = isCheckingReservations || isSigningIn
    val hasAccounts = snapshot.totalAccountCount > 0
    return TodayOverviewPresentation(
        title = "今日概览",
        subtitle = "统计 ${snapshot.dateLabel} 的本地预约与签到状态",
        statusTone =
            when {
                snapshot.reservationAccountCount == 0 -> StatusTone.Neutral
                snapshot.allSignedIn -> StatusTone.Positive
                else -> StatusTone.Warning
            },
        checkReservationAction =
            TodayOverviewActionPresentation(
                label = if (isCheckingReservations) "检查中..." else "一键检查预约",
                enabled = hasAccounts && !operationRunning,
            ),
        checkInAction =
            TodayOverviewActionPresentation(
                label = if (isSigningIn) "签到中..." else "一键签到",
                enabled = hasAccounts && !operationRunning,
            ),
        metrics =
            listOf(
                TodayOverviewMetricPresentation("总账号", snapshot.totalAccountCount.toString()),
                TodayOverviewMetricPresentation("今日预约账号", snapshot.reservationAccountCount.toString()),
                TodayOverviewMetricPresentation("今日任务", snapshot.totalTaskCount.toString()),
                TodayOverviewMetricPresentation("已预约座位", snapshot.reservedSeatCount.toString()),
            ),
    )
}
