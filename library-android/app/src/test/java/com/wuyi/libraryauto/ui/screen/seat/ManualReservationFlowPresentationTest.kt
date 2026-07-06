package com.wuyi.libraryauto.ui.screen.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.viewmodel.ManualReservationUiState
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import org.junit.Test

class ManualReservationFlowPresentationTest {
    @Test
    fun `manual reservation presentation prompts for account before query`() {
        val presentation =
            buildManualReservationFlowPresentation(
                uiState = ManualReservationUiState(),
            )

        assertThat(presentation.title).isEqualTo("手动预约")
        assertThat(presentation.subtitle).isEqualTo("选择账号、时间和座位后立即提交预约")
        assertThat(presentation.statusBadgeLabel).isEqualTo("待选择账号")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.queryAction.label).isEqualTo("查询可选座位")
        assertThat(presentation.queryAction.enabled).isFalse()
        assertThat(presentation.accountListAction.label).isEqualTo("账号列表")
        assertThat(presentation.addAccountAction.label).isEqualTo("先去添加账号")
        assertThat(presentation.selectedSeatText).isEqualTo("还没选中座位，请在下方自习室列表点选")
        assertThat(presentation.reserveAction.label).isEqualTo("立即预约")
        assertThat(presentation.reserveAction.enabled).isFalse()
    }

    @Test
    fun `manual reservation presentation marks query loading state`() {
        val presentation =
            buildManualReservationFlowPresentation(
                uiState =
                    ManualReservationUiState(
                        accounts = listOf(SavedAccountEntry(studentId = "20230001", password = "pw")),
                        selectedStudentId = "20230001",
                        isLoadingSeats = true,
                    ),
            )

        assertThat(presentation.statusBadgeLabel).isEqualTo("查询中")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Info)
        assertThat(presentation.queryAction.label).isEqualTo("查询中...")
        assertThat(presentation.queryAction.enabled).isFalse()
        assertThat(presentation.reserveAction.enabled).isFalse()
    }

    @Test
    fun `manual reservation presentation describes selected seat and reserve action`() {
        val presentation =
            buildManualReservationFlowPresentation(
                uiState =
                    ManualReservationUiState(
                        accounts = listOf(SavedAccountEntry(studentId = "20230001", password = "pw")),
                        selectedStudentId = "20230001",
                        selectedRoomName = "三楼阅览室",
                        selectedSeatNumber = "18",
                    ),
            )

        assertThat(presentation.statusBadgeLabel).isEqualTo("已选座位")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Positive)
        assertThat(presentation.queryAction.enabled).isTrue()
        assertThat(presentation.selectedSeatText).isEqualTo("已选座位：三楼阅览室 · 18")
        assertThat(presentation.reserveAction.label).isEqualTo("立即预约")
        assertThat(presentation.reserveAction.enabled).isTrue()
    }

    @Test
    fun `manual reservation presentation disables reserve while submitting`() {
        val presentation =
            buildManualReservationFlowPresentation(
                uiState =
                    ManualReservationUiState(
                        accounts = listOf(SavedAccountEntry(studentId = "20230001", password = "pw")),
                        selectedStudentId = "20230001",
                        selectedRoomName = "三楼阅览室",
                        selectedSeatNumber = "18",
                        isSubmitting = true,
                    ),
            )

        assertThat(presentation.statusBadgeLabel).isEqualTo("预约中")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Info)
        assertThat(presentation.reserveAction.label).isEqualTo("预约中...")
        assertThat(presentation.reserveAction.enabled).isFalse()
        assertThat(presentation.queryAction.enabled).isFalse()
    }
}
