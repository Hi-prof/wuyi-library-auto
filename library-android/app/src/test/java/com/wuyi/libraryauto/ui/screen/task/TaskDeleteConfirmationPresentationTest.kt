package com.wuyi.libraryauto.ui.screen.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class TaskDeleteConfirmationPresentationTest {
    @Test
    fun `delete confirmation presents destructive action clearly`() {
        val presentation = buildTaskDeleteConfirmationPresentation()

        assertThat(presentation.title).isEqualTo("删除自动任务")
        assertThat(presentation.message).isEqualTo("删除后该自动任务不再执行，可在添加任务里重新创建。")
        assertThat(presentation.badgeLabel).isEqualTo("不可撤销")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Negative)
        assertThat(presentation.confirmAction.label).isEqualTo("确认删除")
        assertThat(presentation.dismissAction.label).isEqualTo("取消")
    }
}
