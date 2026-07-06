package com.wuyi.libraryauto.ui.screen.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class SeatDisplayHeaderPresentationTest {
    @Test
    fun `header presentation exposes account and sign in badges`() {
        val presentation =
            buildSeatDisplayHeaderPresentation(
                cardCount = 4,
                waitingSignIn = 2,
                activeSignedIn = 1,
                isRefreshing = false,
                isBatchCheckingIn = false,
                isBatchReserving = false,
            )

        assertThat(presentation.title).isEqualTo("座位状态")
        assertThat(presentation.subtitle).isEqualTo("集中查看账号座位、签到和补约状态")
        assertThat(presentation.badges.map { it.label })
            .containsExactly("4 个账号", "2 待签到", "1 已签到")
            .inOrder()
        assertThat(presentation.badges.map { it.tone })
            .containsExactly(StatusTone.Info, StatusTone.Warning, StatusTone.Positive)
            .inOrder()
        assertThat(presentation.refreshAction.label).isEqualTo("刷新全部")
        assertThat(presentation.refreshAction.enabled).isTrue()
        assertThat(presentation.checkInAction?.label).isEqualTo("一键签到(2)")
        assertThat(presentation.checkInAction?.enabled).isTrue()
        assertThat(presentation.makeupReservationAction?.label).isEqualTo("一键补约（今天+未来2天）")
        assertThat(presentation.makeupReservationAction?.enabled).isTrue()
    }

    @Test
    fun `header presentation disables actions while busy`() {
        val refreshing =
            buildSeatDisplayHeaderPresentation(
                cardCount = 3,
                waitingSignIn = 1,
                activeSignedIn = 0,
                isRefreshing = true,
                isBatchCheckingIn = false,
                isBatchReserving = false,
            )
        val reserving =
            buildSeatDisplayHeaderPresentation(
                cardCount = 3,
                waitingSignIn = 0,
                activeSignedIn = 0,
                isRefreshing = false,
                isBatchCheckingIn = false,
                isBatchReserving = true,
            )

        assertThat(refreshing.refreshAction.label).isEqualTo("刷新中...")
        assertThat(refreshing.refreshAction.enabled).isFalse()
        assertThat(refreshing.checkInAction?.enabled).isFalse()
        assertThat(reserving.makeupReservationAction?.label).isEqualTo("补约中...")
        assertThat(reserving.makeupReservationAction?.enabled).isFalse()
    }

    @Test
    fun `header presentation hides unavailable batch actions`() {
        val presentation =
            buildSeatDisplayHeaderPresentation(
                cardCount = 0,
                waitingSignIn = 0,
                activeSignedIn = 0,
                isRefreshing = false,
                isBatchCheckingIn = false,
                isBatchReserving = false,
            )

        assertThat(presentation.badges.map { it.label }).containsExactly("0 个账号")
        assertThat(presentation.refreshAction.enabled).isFalse()
        assertThat(presentation.checkInAction).isNull()
        assertThat(presentation.makeupReservationAction).isNull()
    }
}
