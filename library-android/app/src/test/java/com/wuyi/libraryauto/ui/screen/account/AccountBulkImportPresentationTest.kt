package com.wuyi.libraryauto.ui.screen.account

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.account.BulkImportDialogState
import com.wuyi.libraryauto.ui.repository.account.BulkImportFailure
import com.wuyi.libraryauto.ui.repository.account.BulkImportFailureReason
import com.wuyi.libraryauto.ui.repository.account.BulkImportResult
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import org.junit.Test

class AccountBulkImportPresentationTest {
    @Test
    fun `bulk import presentation describes empty idle state`() {
        val presentation =
            buildAccountBulkImportPresentation(
                state = BulkImportDialogState(isVisible = true),
            )

        assertThat(presentation.title).isEqualTo("批量导入账号")
        assertThat(presentation.subtitle).isEqualTo("支持冒号、逗号、空格分隔；密码为空时默认使用学号")
        assertThat(presentation.inputLabel).isEqualTo("账号文本")
        assertThat(presentation.inputPlaceholder).isEqualTo("20230001:password\n20230002")
        assertThat(presentation.inputSupportingText).isEqualTo("每行一个账号，空行和 # 注释会忽略")
        assertThat(presentation.inputEnabled).isTrue()
        assertThat(presentation.statusBadgeLabel).isEqualTo("等待输入")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Neutral)
        assertThat(presentation.resultSummary).isNull()
        assertThat(presentation.resultTone).isEqualTo(StatusTone.Neutral)
        assertThat(presentation.submitAction.label).isEqualTo("开始导入")
        assertThat(presentation.submitAction.enabled).isFalse()
        assertThat(presentation.dismissAction.label).isEqualTo("取消")
        assertThat(presentation.dismissAction.enabled).isTrue()
    }

    @Test
    fun `bulk import presentation disables input and actions while submitting`() {
        val presentation =
            buildAccountBulkImportPresentation(
                state =
                    BulkImportDialogState(
                        isVisible = true,
                        rawText = "20230001:pw",
                        isSubmitting = true,
                    ),
            )

        assertThat(presentation.inputSupportingText).isEqualTo("已输入 1 行")
        assertThat(presentation.inputEnabled).isFalse()
        assertThat(presentation.statusBadgeLabel).isEqualTo("导入中")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Info)
        assertThat(presentation.submitAction.label).isEqualTo("导入中...")
        assertThat(presentation.submitAction.enabled).isFalse()
        assertThat(presentation.dismissAction.enabled).isFalse()
    }

    @Test
    fun `bulk import presentation surfaces mixed import result`() {
        val presentation =
            buildAccountBulkImportPresentation(
                state =
                    BulkImportDialogState(
                        isVisible = true,
                        rawText = "20230001:pw\nEXISTING:pw",
                        result =
                            BulkImportResult(
                                accepted = listOf(SavedAccountEntry(studentId = "20230001", password = "pw")),
                                duplicates =
                                    listOf(
                                        BulkImportFailure(
                                            lineNumber = 2,
                                            studentId = "EXISTING",
                                            reason = BulkImportFailureReason.DuplicateInExisting,
                                        ),
                                    ),
                            ),
                    ),
            )

        assertThat(presentation.inputSupportingText).isEqualTo("已输入 2 行")
        assertThat(presentation.statusBadgeLabel).isEqualTo("部分完成")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.resultTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.resultSummary).contains("成功 1 条")
        assertThat(presentation.resultSummary).contains("跳过 1 条")
        assertThat(presentation.dismissAction.label).isEqualTo("关闭")
    }
}
