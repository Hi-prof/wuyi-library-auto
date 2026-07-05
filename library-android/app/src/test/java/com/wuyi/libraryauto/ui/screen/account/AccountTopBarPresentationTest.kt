package com.wuyi.libraryauto.ui.screen.account

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.viewmodel.AccountRefreshAllState
import com.wuyi.libraryauto.ui.viewmodel.BatchCheckInState
import org.junit.Test

class AccountTopBarPresentationTest {
    @Test
    fun `top bar presentation exposes account count and enabled actions`() {
        val presentation =
            buildAccountTopBarPresentation(
                accountCount = 3,
                refreshAllState = AccountRefreshAllState.Idle,
                batchCheckInState = BatchCheckInState.Idle,
                isAnyBatchActive = false,
                batchCheckInEnabled = true,
            )

        assertThat(presentation.title).isEqualTo("账号列表")
        assertThat(presentation.subtitle).isEqualTo("共 3 个账号")
        assertThat(presentation.refreshAction.label).isEqualTo("刷新全部")
        assertThat(presentation.refreshAction.contentDescription).isEqualTo("刷新全部账号状态")
        assertThat(presentation.refreshAction.enabled).isTrue()
        assertThat(presentation.batchCheckInAction?.label).isEqualTo("全部签到")
        assertThat(presentation.batchCheckInAction?.enabled).isTrue()
        assertThat(presentation.importAction.enabled).isTrue()
        assertThat(presentation.multiSelectAction.enabled).isTrue()
    }

    @Test
    fun `top bar presentation marks running states as busy`() {
        val presentation =
            buildAccountTopBarPresentation(
                accountCount = 3,
                refreshAllState = AccountRefreshAllState.Running(completed = 1, total = 3),
                batchCheckInState = BatchCheckInState.Idle,
                isAnyBatchActive = true,
                batchCheckInEnabled = true,
            )

        assertThat(presentation.refreshAction.label).isEqualTo("刷新中...")
        assertThat(presentation.refreshAction.contentDescription).isEqualTo("刷新中...")
        assertThat(presentation.refreshAction.enabled).isFalse()
        assertThat(presentation.batchCheckInAction?.enabled).isFalse()
        assertThat(presentation.importAction.enabled).isFalse()
        assertThat(presentation.multiSelectAction.enabled).isFalse()
    }

    @Test
    fun `top bar presentation hides batch check in when unavailable`() {
        val presentation =
            buildAccountTopBarPresentation(
                accountCount = 0,
                refreshAllState = AccountRefreshAllState.Idle,
                batchCheckInState = BatchCheckInState.Idle,
                isAnyBatchActive = false,
                batchCheckInEnabled = false,
            )

        assertThat(presentation.subtitle).isEqualTo("共 0 个账号")
        assertThat(presentation.refreshAction.enabled).isFalse()
        assertThat(presentation.batchCheckInAction).isNull()
        assertThat(presentation.importAction.enabled).isTrue()
        assertThat(presentation.multiSelectAction.enabled).isFalse()
    }
}
