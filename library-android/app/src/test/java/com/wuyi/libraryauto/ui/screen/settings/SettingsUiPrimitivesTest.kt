package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsUiPrimitivesTest {
    @Test
    fun `settings info line uses Chinese label separator`() {
        assertThat(formatSettingsInfoLine(label = "状态", value = "正常"))
            .isEqualTo("状态：正常")
    }

    @Test
    fun `settings info line trims accidental whitespace`() {
        assertThat(formatSettingsInfoLine(label = " 版本 ", value = " 3.1.4 "))
            .isEqualTo("版本：3.1.4")
    }

    @Test
    fun `mask sensitive keeps short values readable without exposing the full token`() {
        assertThat("".maskSensitive()).isEmpty()
        assertThat("a".maskSensitive()).isEqualTo("a*")
        assertThat("ab".maskSensitive()).isEqualTo("a*")
        assertThat("abcdef".maskSensitive()).isEqualTo("a***f")
        assertThat("booking-abcdef".maskSensitive()).isEqualTo("boo***def")
    }
}
