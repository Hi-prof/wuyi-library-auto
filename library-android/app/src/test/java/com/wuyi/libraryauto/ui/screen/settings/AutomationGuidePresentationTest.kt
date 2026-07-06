package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class AutomationGuidePresentationTest {
    @Test
    fun `automation guide rules preserve order and metadata badges`() {
        val rules = buildAutomationGuideRulePresentations()

        assertThat(rules.map { it.title })
            .containsExactly("目标座位", "座位查询", "单次模式")
            .inOrder()
        assertThat(rules.first().detail).isEqualTo("默认来自账号里最近一次成功的手动预约或自动任务配置。")
        assertThat(rules.first().badgeLabel).isEqualTo("预约规则")
        assertThat(rules.first().badgeTone).isEqualTo(StatusTone.Info)
    }

    @Test
    fun `automation guide rules keep details free of title prefixes`() {
        val rules = buildAutomationGuideRulePresentations()

        assertThat(rules.map { it.detail })
            .containsExactly(
                "默认来自账号里最近一次成功的手动预约或自动任务配置。",
                "自动任务弹窗里保留“刷新/查询座位”按钮；只有你主动点了才会查询。",
                "需要自己填写日期、开始时间和结束时间。",
            )
            .inOrder()
    }
}
