package com.wuyi.libraryauto.ui.screen.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode
import com.wuyi.libraryauto.ui.viewmodel.AutomationTaskDialogState
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import org.junit.Test

class AutomationTaskDialogPresentationTest {
    @Test
    fun `dialog presentation exposes continuous mode copy and enabled actions`() {
        val presentation =
            buildAutomationTaskDialogPresentation(
                dialogState =
                    AutomationTaskDialogState(
                        selectedStudentId = "20230001",
                        mode = AutomationTaskMode.CONTINUOUS,
                        previewText = "",
                    ),
                accounts = listOf(SavedAccountEntry(studentId = "20230001", password = "pw")),
            )

        assertThat(presentation.title).isEqualTo("添加自动任务")
        assertThat(presentation.subtitle).isEqualTo("选择账号、目标座位与执行模式，保存后任务会自动执行")
        assertThat(presentation.modeBadgeLabel).isEqualTo("持续预约")
        assertThat(presentation.modeBadgeTone).isEqualTo(StatusTone.Info)
        assertThat(presentation.accountSupportingText).isEqualTo("切换账号后会带出该账号的默认座位与历史")
        assertThat(presentation.refreshSeatAction.label).isEqualTo("刷新座位")
        assertThat(presentation.refreshSeatAction.enabled).isTrue()
        assertThat(presentation.modeSummaryText).isEqualTo("持续预约：跟随系统排程自动续约")
        assertThat(presentation.saveAction.label).isEqualTo("创建自动任务")
        assertThat(presentation.saveAction.enabled).isTrue()
    }

    @Test
    fun `dialog presentation disables actions while refreshing seats`() {
        val presentation =
            buildAutomationTaskDialogPresentation(
                dialogState =
                    AutomationTaskDialogState(
                        selectedStudentId = "20230001",
                        isRefreshingSeats = true,
                    ),
                accounts = listOf(SavedAccountEntry(studentId = "20230001", password = "pw")),
            )

        assertThat(presentation.refreshSeatAction.label).isEqualTo("查询中...")
        assertThat(presentation.refreshSeatAction.enabled).isFalse()
        assertThat(presentation.saveAction.enabled).isFalse()
    }

    @Test
    fun `dialog presentation handles empty account and single custom mode`() {
        val presentation =
            buildAutomationTaskDialogPresentation(
                dialogState =
                    AutomationTaskDialogState(
                        mode = AutomationTaskMode.SINGLE_CUSTOM,
                    ),
                accounts = emptyList(),
            )

        assertThat(presentation.modeBadgeLabel).isEqualTo("单次任务")
        assertThat(presentation.modeBadgeTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.accountSupportingText).isEqualTo("当前没有可用账号")
        assertThat(presentation.modeSummaryText).isEqualTo("单次任务：按指定日期和时间段执行一次")
    }
}
