package com.wuyi.libraryauto.ui.screen.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.viewmodel.CreateFromBookingsDialogState
import com.wuyi.libraryauto.ui.viewmodel.CreateFromBookingsRowUiState
import org.junit.Test

class CreateFromBookingsPresentationTest {
    @Test
    fun `presentation summarizes selectable rows`() {
        val presentation =
            buildCreateFromBookingsPresentation(
                CreateFromBookingsDialogState(
                    rows =
                        listOf(
                            CreateFromBookingsRowUiState(
                                studentId = "20230002",
                                roomName = "综合阅览室",
                                seatNumber = "021",
                                beginLabel = "2026-04-11 10:00",
                                statusLabel = "待签到",
                                selected = true,
                                canCreate = true,
                            ),
                            CreateFromBookingsRowUiState(
                                studentId = "20230001",
                                roomName = "二楼自习室",
                                seatNumber = "166",
                                hasExistingPlan = true,
                                canCreate = true,
                            ),
                            CreateFromBookingsRowUiState(
                                studentId = "20230003",
                                message = "当前没有可用预约",
                            ),
                        ),
                ),
            )

        assertThat(presentation.title).isEqualTo("根据当前预约创建自动任务")
        assertThat(presentation.subtitle).isEqualTo("可创建 2 个，已选 1 个")
        assertThat(presentation.confirmAction.label).isEqualTo("创建 1 个")
        assertThat(presentation.confirmAction.enabled).isTrue()
        assertThat(presentation.rows.map { it.bookingLabel })
            .containsExactly("综合阅览室 · 021", "二楼自习室 · 166", "当前没有可用预约")
            .inOrder()
        assertThat(presentation.rows[1].planBadgeLabel).isEqualTo("已有任务")
        assertThat(presentation.rows[2].enabled).isFalse()
    }
}
