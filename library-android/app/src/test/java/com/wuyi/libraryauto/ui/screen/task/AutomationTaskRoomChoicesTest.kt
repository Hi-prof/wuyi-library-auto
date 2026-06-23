package com.wuyi.libraryauto.ui.screen.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import org.junit.Test

class AutomationTaskRoomChoicesTest {

    @Test
    fun `default automation task rooms stay fixed to three study rooms`() {
        assertThat(DefaultAutomationTaskRoomNames).containsExactly(
            "综合阅览室",
            "自习室圆形二楼",
            "自习室圆形一楼",
        ).inOrder()
    }

    @Test
    fun `task dialog room dropdown options keep the same fixed order`() {
        assertThat(buildTaskDialogRoomOptions(DefaultAutomationTaskRoomNames)).containsExactly(
            TaskDialogDropdownOption(value = "综合阅览室", label = "综合阅览室"),
            TaskDialogDropdownOption(value = "自习室圆形二楼", label = "自习室圆形二楼"),
            TaskDialogDropdownOption(value = "自习室圆形一楼", label = "自习室圆形一楼"),
        ).inOrder()
    }

    @Test
    fun `task dialog account dropdown options map student ids directly`() {
        assertThat(
            buildTaskDialogAccountOptions(
                listOf(
                    SavedAccountEntry(studentId = "20231121130", password = "alpha"),
                    SavedAccountEntry(studentId = "20231121131", password = "beta"),
                ),
            ),
        ).containsExactly(
            TaskDialogDropdownOption(value = "20231121130", label = "20231121130"),
            TaskDialogDropdownOption(value = "20231121131", label = "20231121131"),
        ).inOrder()
    }
}
