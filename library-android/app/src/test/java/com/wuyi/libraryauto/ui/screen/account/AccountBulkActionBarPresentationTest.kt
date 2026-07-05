package com.wuyi.libraryauto.ui.screen.account

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class AccountBulkActionBarPresentationTest {
    @Test
    fun `bulk action bar presentation describes empty selection`() {
        val presentation = buildAccountBulkActionBarPresentation(selectedCount = 0)

        assertThat(presentation.title).isEqualTo("选择账号")
        assertThat(presentation.subtitle).isEqualTo("选择要批量处理的账号")
        assertThat(presentation.selectionBadgeLabel).isEqualTo("未选择")
        assertThat(presentation.selectionBadgeTone).isEqualTo(StatusTone.Neutral)
        assertThat(presentation.exitAction.contentDescription).isEqualTo("退出多选")
        assertThat(presentation.selectAllAction.contentDescription).isEqualTo("全选")
        assertThat(presentation.exportAction.contentDescription).isEqualTo("先选择账号再导出")
        assertThat(presentation.exportAction.enabled).isFalse()
        assertThat(presentation.deleteAction.contentDescription).isEqualTo("先选择账号再删除")
        assertThat(presentation.deleteAction.enabled).isFalse()
        assertThat(presentation.deleteAction.tone).isEqualTo(StatusTone.Neutral)
    }

    @Test
    fun `bulk action bar presentation enables selected actions`() {
        val presentation = buildAccountBulkActionBarPresentation(selectedCount = 3)

        assertThat(presentation.subtitle).isEqualTo("已选 3 项")
        assertThat(presentation.selectionBadgeLabel).isEqualTo("3 项")
        assertThat(presentation.selectionBadgeTone).isEqualTo(StatusTone.Info)
        assertThat(presentation.exportAction.contentDescription).isEqualTo("导出 3 个账号")
        assertThat(presentation.exportAction.enabled).isTrue()
        assertThat(presentation.deleteAction.contentDescription).isEqualTo("删除 3 个账号")
        assertThat(presentation.deleteAction.enabled).isTrue()
        assertThat(presentation.deleteAction.tone).isEqualTo(StatusTone.Negative)
    }
}
