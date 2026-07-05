package com.wuyi.libraryauto.ui.screen.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class TaskListPresentationTest {
    @Test
    fun `summary chips include account plan and optional filter state`() {
        val chips =
            buildTaskSummaryChips(
                accountCount = 3,
                planCount = 2,
                studentFilter = "20230001",
            )

        assertThat(chips.map { it.text })
            .containsExactly("账号 3", "任务 2", "过滤 20230001")
            .inOrder()
        assertThat(chips.map { it.tone })
            .containsExactly(StatusTone.Info, StatusTone.Positive, StatusTone.Warning)
            .inOrder()
    }

    @Test
    fun `summary chips omit filter state when filter is blank`() {
        val chips =
            buildTaskSummaryChips(
                accountCount = 1,
                planCount = 0,
                studentFilter = " ",
            )

        assertThat(chips.map { it.text }).containsExactly("账号 1", "任务 0").inOrder()
    }

    @Test
    fun `task card fallback text stays concise`() {
        assertThat(displayTaskValue(value = "", fallback = "未指定")).isEqualTo("未指定")
        assertThat(displayTaskValue(value = " 综合阅览室 ", fallback = "未指定")).isEqualTo("综合阅览室")
        assertThat(displayTaskPreview("")).isEqualTo("等待生成执行预览")
        assertThat(displayTaskLastResult("")).isEqualTo("最近还没有执行结果")
    }
}
