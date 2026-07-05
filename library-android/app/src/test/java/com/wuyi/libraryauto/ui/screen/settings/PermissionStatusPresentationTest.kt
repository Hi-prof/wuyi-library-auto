package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.permission.CapabilityStatus
import org.junit.Test

class PermissionStatusPresentationTest {
    @Test
    fun `permission status row marks ready capability as positive`() {
        val presentation =
            buildPermissionStatusRowPresentation(
                CapabilityStatus(
                    title = "通知开关",
                    detail = "已开启",
                    ready = true,
                ),
            )

        assertThat(presentation.title).isEqualTo("通知开关")
        assertThat(presentation.detail).isEqualTo("已开启")
        assertThat(presentation.badgeLabel).isEqualTo("已就绪")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Positive)
    }

    @Test
    fun `permission status row marks missing capability as warning`() {
        val presentation =
            buildPermissionStatusRowPresentation(
                CapabilityStatus(
                    title = "无障碍服务",
                    detail = "未开启",
                    ready = false,
                ),
            )

        assertThat(presentation.badgeLabel).isEqualTo("待处理")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Warning)
    }

    @Test
    fun `permission status row falls back for blank values`() {
        val presentation =
            buildPermissionStatusRowPresentation(
                CapabilityStatus(
                    title = " ",
                    detail = " ",
                    ready = false,
                ),
            )

        assertThat(presentation.title).isEqualTo("未命名能力")
        assertThat(presentation.detail).isEqualTo("暂无说明")
    }

    @Test
    fun `manual permission hint uses warning tone`() {
        val presentation = buildManualPermissionHintPresentation()

        assertThat(presentation.title).isEqualTo("厂商后台策略")
        assertThat(presentation.detail).isEqualTo("厂商自启动、后台白名单和省电策略需要你自己到系统管家里确认。")
        assertThat(presentation.badgeLabel).isEqualTo("需手动确认")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Warning)
    }
}
