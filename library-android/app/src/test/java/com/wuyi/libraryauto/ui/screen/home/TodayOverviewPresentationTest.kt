package com.wuyi.libraryauto.ui.screen.home

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.home.TodayOverviewSnapshot
import org.junit.Test

class TodayOverviewPresentationTest {
    @Test
    fun `overview presentation exposes warning state and enabled actions`() {
        val presentation =
            buildTodayOverviewPresentation(
                snapshot = snapshot(totalAccountCount = 3, allSignedIn = false),
                isCheckingReservations = false,
                isSigningIn = false,
            )

        assertThat(presentation.title).isEqualTo("今日概览")
        assertThat(presentation.subtitle).isEqualTo("统计 2026-07-04 的本地预约与签到状态")
        assertThat(presentation.statusTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.checkReservationAction.label).isEqualTo("一键检查预约")
        assertThat(presentation.checkReservationAction.enabled).isTrue()
        assertThat(presentation.checkInAction.label).isEqualTo("一键签到")
        assertThat(presentation.checkInAction.enabled).isTrue()
        assertThat(presentation.metrics.map { it.title })
            .containsExactly("总账号", "今日预约账号", "今日任务", "已预约座位")
            .inOrder()
        assertThat(presentation.metrics.map { it.value })
            .containsExactly("3", "2", "4", "2")
            .inOrder()
    }

    @Test
    fun `overview presentation disables actions while busy`() {
        val presentation =
            buildTodayOverviewPresentation(
                snapshot = snapshot(totalAccountCount = 3, allSignedIn = false),
                isCheckingReservations = true,
                isSigningIn = false,
            )

        assertThat(presentation.checkReservationAction.label).isEqualTo("检查中...")
        assertThat(presentation.checkReservationAction.enabled).isFalse()
        assertThat(presentation.checkInAction.enabled).isFalse()
    }

    @Test
    fun `overview presentation handles empty and completed states`() {
        val empty =
            buildTodayOverviewPresentation(
                snapshot = snapshot(totalAccountCount = 0, reservationAccountCount = 0),
                isCheckingReservations = false,
                isSigningIn = false,
            )
        val completed =
            buildTodayOverviewPresentation(
                snapshot = snapshot(totalAccountCount = 2, allSignedIn = true),
                isCheckingReservations = false,
                isSigningIn = false,
            )

        assertThat(empty.statusTone).isEqualTo(StatusTone.Neutral)
        assertThat(empty.checkReservationAction.enabled).isFalse()
        assertThat(completed.statusTone).isEqualTo(StatusTone.Positive)
    }

    private fun snapshot(
        totalAccountCount: Int,
        reservationAccountCount: Int = 2,
        allSignedIn: Boolean = false,
    ): TodayOverviewSnapshot =
        TodayOverviewSnapshot(
            dateLabel = "2026-07-04",
            totalAccountCount = totalAccountCount,
            reservationAccountCount = reservationAccountCount,
            totalTaskCount = 4,
            reservedSeatCount = 2,
            signedInSeatCount = if (allSignedIn) 2 else 1,
            waitingSignInSeatCount = if (allSignedIn) 0 else 1,
            reservationQueueCount = 0,
            attentionCount = 0,
            allSignedIn = allSignedIn,
            signInHeadline = if (allSignedIn) "今天全部完成签到" else "还有账号未完成",
            signInDetail = "已签到 1 / 2",
            accountSummaries = emptyList(),
        )
}
