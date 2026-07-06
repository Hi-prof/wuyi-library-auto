package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.sync.LocalAccountSummary
import com.wuyi.libraryauto.sync.ServerAccountSnapshot
import com.wuyi.libraryauto.sync.SyncButtonState
import com.wuyi.libraryauto.sync.SyncCandidate
import com.wuyi.libraryauto.sync.SyncKind
import com.wuyi.libraryauto.ui.components.StatusTone
import org.junit.Test

class ServerSyncPresentationTest {
    @Test
    fun `connection presentation distinguishes configured upload enabled state`() {
        val presentation = serverConnectionPresentation(configured = true, uploadEnabled = true)

        assertThat(presentation.label).isEqualTo("已连接服务端")
        assertThat(presentation.detail).contains("允许上传本地自动任务")
        assertThat(presentation.badge).isEqualTo("双向同步")
    }

    @Test
    fun `connection presentation keeps local-only copy when upload is disabled`() {
        val presentation = serverConnectionPresentation(configured = true, uploadEnabled = false)

        assertThat(presentation.label).isEqualTo("已配置服务端")
        assertThat(presentation.detail).contains("手动拉取活跃池")
        assertThat(presentation.badge).isEqualTo("仅手动拉取")
    }

    @Test
    fun `connection presentation explains incomplete configuration`() {
        val presentation = serverConnectionPresentation(configured = false, uploadEnabled = true)

        assertThat(presentation.label).isEqualTo("未配置完整")
        assertThat(presentation.detail).contains("服务端 URL")
        assertThat(presentation.badge).isEqualTo("本地模式")
    }

    @Test
    fun `sync action presentation disables unavailable states`() {
        val unconfigured =
            syncActionPresentation(
                buttonState = SyncButtonState.DisabledUnconfigured,
                isLoading = false,
            )
        val unreachable =
            syncActionPresentation(
                buttonState = SyncButtonState.DisabledUnreachable(reason = "timeout"),
                isLoading = false,
            )

        assertThat(unconfigured.enabled).isFalse()
        assertThat(unconfigured.buttonLabel).isEqualTo("未配置服务端")
        assertThat(unconfigured.detail).contains("base_url")
        assertThat(unreachable.enabled).isFalse()
        assertThat(unreachable.buttonLabel).isEqualTo("服务端不可达")
        assertThat(unreachable.detail).contains("timeout")
    }

    @Test
    fun `sync action presentation exposes loading label only when enabled state is busy`() {
        val presentation =
            syncActionPresentation(
                buttonState = SyncButtonState.Enabled,
                isLoading = true,
            )

        assertThat(presentation.enabled).isFalse()
        assertThat(presentation.label).isEqualTo("服务端同步中")
        assertThat(presentation.buttonLabel).isEqualTo("同步中…")
    }

    @Test
    fun `sync coverage confirmation presentation summarizes candidates and selection`() {
        val candidates =
            listOf(
                addCandidate(studentId = "20230001", displayName = "新账号"),
                replaceCandidate(studentId = "20230002", serverDisplayName = "服务端账号"),
                removeCandidate(studentId = "20230003", localDisplayName = "本地账号"),
            )

        val presentation =
            syncCoverageConfirmationPresentation(
                candidates = candidates,
                selection =
                    mapOf(
                        "20230001" to true,
                        "20230002" to false,
                        "20230003" to true,
                    ),
            )

        assertThat(presentation.title).isEqualTo("确认同步")
        assertThat(presentation.summary).isEqualTo("本次同步将覆盖：新增 1、替换 1、移除 1。已勾选 2 / 3。")
        assertThat(presentation.badgeLabel).isEqualTo("已选 2/3")
        assertThat(presentation.badgeTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.selectAllAction.label).isEqualTo("全选")
        assertThat(presentation.clearAllAction.label).isEqualTo("全不选")
        assertThat(presentation.invertAllAction.label).isEqualTo("反选")
        assertThat(presentation.confirmAction.label).isEqualTo("确认覆盖")
        assertThat(presentation.dismissAction.label).isEqualTo("取消")
        assertThat(presentation.candidates.map { it.title })
            .containsExactly("新增 · 20230001", "替换 · 20230002", "移除 · 20230003")
            .inOrder()
        assertThat(presentation.candidates.map { it.displayName })
            .containsExactly("新账号", "服务端账号", "本地账号")
            .inOrder()
        assertThat(presentation.candidates.map { it.taskCountLabel })
            .containsExactly("关联自动任务：0 项", "关联自动任务：2 项", "关联自动任务：1 项")
            .inOrder()
        assertThat(presentation.candidates.map { it.checked })
            .containsExactly(true, false, true)
            .inOrder()
    }

    private fun addCandidate(
        studentId: String,
        displayName: String,
    ): SyncCandidate =
        SyncCandidate(
            kind = SyncKind.Add,
            studentId = studentId,
            serverPayload =
                ServerAccountSnapshot(
                    studentId = studentId,
                    password = "pwd-$studentId",
                    displayName = displayName,
                    automationTasks = emptyList(),
                ),
            localSummary = null,
            defaultChecked = true,
        )

    private fun replaceCandidate(
        studentId: String,
        serverDisplayName: String,
    ): SyncCandidate =
        SyncCandidate(
            kind = SyncKind.Replace,
            studentId = studentId,
            serverPayload =
                ServerAccountSnapshot(
                    studentId = studentId,
                    password = "pwd-$studentId",
                    displayName = serverDisplayName,
                    automationTasks = listOf(mockAutomationTask(), mockAutomationTask()),
                ),
            localSummary =
                LocalAccountSummary(
                    studentId = studentId,
                    displayName = "本地旧账号",
                    automationTaskCount = 1,
                    customWindowCount = 0,
                ),
            defaultChecked = true,
        )

    private fun removeCandidate(
        studentId: String,
        localDisplayName: String,
    ): SyncCandidate =
        SyncCandidate(
            kind = SyncKind.Remove,
            studentId = studentId,
            serverPayload = null,
            localSummary =
                LocalAccountSummary(
                    studentId = studentId,
                    displayName = localDisplayName,
                    automationTaskCount = 1,
                    customWindowCount = 0,
                ),
            defaultChecked = false,
        )

    private fun mockAutomationTask(): com.wuyi.libraryauto.sync.AutomationTaskDto =
        com.wuyi.libraryauto.sync.AutomationTaskDto(
            taskId = 1L,
            roomName = "自习室",
            seatNumber = "001",
            mode = "DAILY",
            customWindows = emptyList(),
            enabled = true,
            revision = 1L,
            updatedAt = "2026-07-05T00:00:00Z",
        )
}
