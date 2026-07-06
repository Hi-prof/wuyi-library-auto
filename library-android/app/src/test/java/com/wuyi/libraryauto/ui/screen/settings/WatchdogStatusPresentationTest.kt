package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class WatchdogStatusPresentationTest {
    @Test
    fun `watchdog status rows describe healthy state`() {
        val rows =
            buildWatchdogStatusRows(
                WatchdogStatusUiState(
                    periodicHeartbeat = "1720000000",
                    watchdogHeartbeat = "1720000100",
                    failureCount = 0,
                    stateLabel = "健康",
                ),
            )

        assertThat(rows.map { it.title })
            .containsExactly("周期签到心跳", "看门狗心跳", "失败计数", "状态")
            .inOrder()
        assertThat(rows[0].detail).isEqualTo("1720000000")
        assertThat(rows[0].badgeLabel).isEqualTo("已记录")
        assertThat(rows[0].badgeTone).isEqualTo(StatusTone.Info)
        assertThat(rows[2].detail).isEqualTo("0 次")
        assertThat(rows[2].badgeLabel).isEqualTo("正常")
        assertThat(rows[2].badgeTone).isEqualTo(StatusTone.Positive)
        assertThat(rows[3].badgeLabel).isEqualTo("健康")
        assertThat(rows[3].badgeTone).isEqualTo(StatusTone.Positive)
    }

    @Test
    fun `watchdog status rows highlight missing heartbeat and degraded state`() {
        val rows =
            buildWatchdogStatusRows(
                WatchdogStatusUiState(
                    periodicHeartbeat = "暂无",
                    watchdogHeartbeat = "暂无",
                    failureCount = 2,
                    stateLabel = "降级：连续缺失 2 次",
                ),
            )

        assertThat(rows[0].badgeLabel).isEqualTo("未记录")
        assertThat(rows[0].badgeTone).isEqualTo(StatusTone.Neutral)
        assertThat(rows[2].detail).isEqualTo("2 次")
        assertThat(rows[2].badgeLabel).isEqualTo("2 次")
        assertThat(rows[2].badgeTone).isEqualTo(StatusTone.Warning)
        assertThat(rows[3].badgeLabel).isEqualTo("需关注")
        assertThat(rows[3].badgeTone).isEqualTo(StatusTone.Warning)
    }

    @Test
    fun `unique work row presentation uses configured badge`() {
        val presentation =
            buildUniqueWorkRowPresentation(
                UniqueWorkStatus(
                    name = "watchdog",
                    nextRunLabel = "由 WorkManager 周期巡检",
                ),
            )

        assertThat(presentation.title).isEqualTo("watchdog")
        assertThat(presentation.detail).isEqualTo("由 WorkManager 周期巡检")
        assertThat(presentation.badgeLabel).isEqualTo("已配置")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Info)
    }
}
