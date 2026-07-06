package com.wuyi.libraryauto.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimePickerSheetPresentationTest {
    @Test
    fun `time picker sheet presentation keeps provided title and standard actions`() {
        val presentation = buildTimePickerSheetPresentation(title = "选择开始时间")

        assertThat(presentation.title).isEqualTo("选择开始时间")
        assertThat(presentation.dismissAction.label).isEqualTo("取消")
        assertThat(presentation.confirmAction.label).isEqualTo("确定")
    }

    @Test
    fun `time picker sheet presentation falls back when title is blank`() {
        val presentation = buildTimePickerSheetPresentation(title = " ")

        assertThat(presentation.title).isEqualTo("选择时间")
    }
}
