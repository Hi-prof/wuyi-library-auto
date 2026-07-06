package com.wuyi.libraryauto.ui.screen.account

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class AccountDeleteConfirmationPresentationTest {
    @Test
    fun `single account delete confirmation includes target student id`() {
        val presentation = buildSingleAccountDeleteConfirmationPresentation(studentId = "20230001")

        assertThat(presentation.title).isEqualTo("删除账号")
        assertThat(presentation.message)
            .isEqualTo("确定删除账号 20230001 吗？删除后会同时移除该账号保存的会话，无法撤销。")
        assertThat(presentation.badgeLabel).isEqualTo("不可撤销")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Negative)
        assertThat(presentation.confirmAction.label).isEqualTo("确认删除")
        assertThat(presentation.dismissAction.label).isEqualTo("取消")
    }

    @Test
    fun `bulk account delete confirmation includes selected count`() {
        val presentation = buildBulkAccountDeleteConfirmationPresentation(selectedCount = 3)

        assertThat(presentation.title).isEqualTo("删除所选账号")
        assertThat(presentation.message).isEqualTo("将删除 3 个账号，删除后会同时移除会话，无法撤销。")
        assertThat(presentation.badgeLabel).isEqualTo("3 个账号")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Negative)
        assertThat(presentation.confirmAction.label).isEqualTo("确认删除")
        assertThat(presentation.dismissAction.label).isEqualTo("取消")
    }

    @Test
    fun `bulk account delete confirmation coerces negative count to zero`() {
        val presentation = buildBulkAccountDeleteConfirmationPresentation(selectedCount = -2)

        assertThat(presentation.message).isEqualTo("将删除 0 个账号，删除后会同时移除会话，无法撤销。")
        assertThat(presentation.badgeLabel).isEqualTo("0 个账号")
    }
}
