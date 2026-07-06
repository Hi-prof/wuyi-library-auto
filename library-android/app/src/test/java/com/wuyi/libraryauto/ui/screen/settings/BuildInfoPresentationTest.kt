package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class BuildInfoPresentationTest {
    @Test
    fun `build info row presents known value with neutral metadata badge`() {
        val presentation =
            buildBuildInfoRowPresentation(
                label = "versionName",
                value = "1.2.3",
            )

        assertThat(presentation.title).isEqualTo("versionName")
        assertThat(presentation.detail).isEqualTo("1.2.3")
        assertThat(presentation.badgeLabel).isEqualTo("元数据")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Neutral)
    }

    @Test
    fun `build info row falls back for blank values`() {
        val presentation =
            buildBuildInfoRowPresentation(
                label = " ",
                value = " ",
            )

        assertThat(presentation.title).isEqualTo("构建字段")
        assertThat(presentation.detail).isEqualTo("未知")
    }
}
