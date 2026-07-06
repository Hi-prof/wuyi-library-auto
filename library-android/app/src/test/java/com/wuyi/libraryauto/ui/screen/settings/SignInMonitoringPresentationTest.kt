package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class SignInMonitoringPresentationTest {
    @Test
    fun `sign in audit row highlights successful result`() {
        val presentation =
            buildSignInAuditRowPresentation(
                SignInAuditDisplay(
                    studentId = "202***234",
                    bookingId = "boo***def",
                    result = "成功",
                    triggerSource = "ManualBatch",
                ),
            )

        assertThat(presentation.title).isEqualTo("202***234")
        assertThat(presentation.detail).isEqualTo("预约 boo***def · ManualBatch")
        assertThat(presentation.badgeLabel).isEqualTo("成功")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Positive)
    }

    @Test
    fun `sign in audit row marks failed result as negative`() {
        val presentation =
            buildSignInAuditRowPresentation(
                SignInAuditDisplay(
                    studentId = "202***234",
                    bookingId = "boo***def",
                    result = "蓝牙未命中",
                    triggerSource = "Watchdog",
                ),
            )

        assertThat(presentation.badgeLabel).isEqualTo("蓝牙未命中")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Negative)
    }

    @Test
    fun `beacon scan row distinguishes matched and unmatched scans`() {
        val matched =
            buildBeaconScanAuditRowPresentation(
                BeaconScanAuditDisplay(
                    bookingId = "booking-1",
                    expectedMinors = "101,102",
                    seenMinors = "102",
                    matchedMinor = "102",
                    durationMillis = 860L,
                ),
            )
        val unmatched =
            buildBeaconScanAuditRowPresentation(
                BeaconScanAuditDisplay(
                    bookingId = "booking-2",
                    expectedMinors = "101",
                    seenMinors = "-",
                    matchedMinor = "-",
                    durationMillis = 1200L,
                ),
            )

        assertThat(matched.title).isEqualTo("预约 booking-1")
        assertThat(matched.detail).isEqualTo("期望 101,102 · 看到 102 · 耗时 860 ms")
        assertThat(matched.badgeLabel).isEqualTo("命中 102")
        assertThat(matched.badgeTone).isEqualTo(StatusTone.Positive)
        assertThat(unmatched.badgeLabel).isEqualTo("未命中")
        assertThat(unmatched.badgeTone).isEqualTo(StatusTone.Warning)
    }

    @Test
    fun `error aggregate row exposes count as negative badge`() {
        val presentation =
            buildSignInErrorAggregateRowPresentation(
                SignInErrorAggregateDisplay(error = "蓝牙未命中", count = 3L),
            )

        assertThat(presentation.title).isEqualTo("蓝牙未命中")
        assertThat(presentation.detail).isEqualTo("最近 24 小时内出现 3 次")
        assertThat(presentation.badgeLabel).isEqualTo("3 次")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Negative)
    }
}
