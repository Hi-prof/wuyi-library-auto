package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class RuntimeGuidePresentationTest {
    @Test
    fun `runtime guide steps preserve order and metadata badges`() {
        val steps = buildRuntimeGuideStepPresentations()

        assertThat(steps.map { it.number })
            .containsExactly("1", "2", "3", "4")
            .inOrder()
        assertThat(steps.first().title).isEqualTo("账号登录态")
        assertThat(steps.first().detail).isEqualTo("账号页先完成认证，保证每个账号都有可复用登录态。")
        assertThat(steps.first().badgeLabel).isEqualTo("运行前")
        assertThat(steps.first().badgeTone).isEqualTo(StatusTone.Info)
    }

    @Test
    fun `runtime guide steps avoid embedded ordinal prefixes`() {
        val steps = buildRuntimeGuideStepPresentations()

        assertThat(steps.map { it.title })
            .containsExactly("账号登录态", "首轮调度", "系统权限", "厂商白名单")
            .inOrder()
        assertThat(steps.none { it.detail.trimStart().firstOrNull()?.isDigit() == true }).isTrue()
    }
}
