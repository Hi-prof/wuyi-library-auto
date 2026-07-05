package com.wuyi.libraryauto.ui.screen.account

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.viewmodel.AccountBookingEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import org.junit.Test

class AccountCardPresentationTest {
    @Test
    fun `account card presentation compresses active booking and hides preferred seat badge`() {
        val presentation =
            buildAccountCardPresentation(
                SavedAccountEntry(
                    studentId = "20230001",
                    password = "pw",
                    preferredSeatLabel = "综合阅览室 / 166",
                    isAuthenticated = true,
                    statusSummary = "当前账号已认证",
                    activeBookings =
                        listOf(
                            AccountBookingEntry(
                                bookingId = "booking-1",
                                roomName = "自习室圆形二楼",
                                seatNumber = "166",
                                beginLabel = "08:00 - 10:00",
                                statusLabel = "签到成功，已在馆",
                            ),
                            AccountBookingEntry(
                                bookingId = "booking-2",
                                roomName = "综合阅览室",
                                seatNumber = "201",
                                beginLabel = "10:00 - 12:00",
                                statusLabel = "待签到",
                            ),
                        ),
                ),
            )

        assertThat(presentation.authLabel).isEqualTo("已认证")
        assertThat(presentation.authTone).isEqualTo(StatusTone.Info)
        assertThat(presentation.preferredSeatBadge).isNull()
        assertThat(presentation.bookingPrimaryText).isEqualTo("自习室圆形二楼（166），签到成功")
        assertThat(presentation.bookingSecondaryText).isEqualTo("08:00 - 10:00 · 等 2 条")
        assertThat(presentation.statusSummary).isNull()
        assertThat(presentation.initials).isEqualTo("01")
    }

    @Test
    fun `account card presentation shows preferred seat and status when there is no booking`() {
        val presentation =
            buildAccountCardPresentation(
                SavedAccountEntry(
                    studentId = " 20230009 ",
                    password = "pw",
                    preferredSeatLabel = " 综合阅览室 / 201 ",
                    isAuthenticated = false,
                    statusSummary = " 当前未认证，请先刷新认证。 ",
                ),
            )

        assertThat(presentation.authLabel).isEqualTo("未认证")
        assertThat(presentation.authTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.preferredSeatBadge).isEqualTo("综合阅览室 / 201")
        assertThat(presentation.bookingPrimaryText).isNull()
        assertThat(presentation.bookingSecondaryText).isNull()
        assertThat(presentation.statusSummary).isEqualTo("当前未认证，请先刷新认证。")
        assertThat(presentation.initials).isEqualTo("09")
    }

    @Test
    fun `account initials fall back for blank ids`() {
        assertThat(accountInitials(" ")).isEqualTo("--")
    }
}
