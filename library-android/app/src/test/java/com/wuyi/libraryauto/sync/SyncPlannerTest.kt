package com.wuyi.libraryauto.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 任务 12.11 单元测试：[SyncPlanner.computeDiff]。
 *
 * 这些用例不依赖 Robolectric / Compose / Room，纯函数式断言：
 * - add / replace / remove 三类候选条目按 design.md 数据流 4 伪代码产出。
 * - `defaultChecked` 满足 add/replace = true、remove = false。
 * - 受管字段（`student_id` / `password` / `display_name` / `automation_tasks`）任一变化都会触发
 *   replace；非受管字段（如 [LocalAccount.isServerManaged] 自身）不参与 diff 判定。
 * - 本地未标记 `isServerManaged` 的账号即便服务端清单缺失也不进入 remove 候选。
 * - 输出顺序稳定：先 add → 再 replace → 再 remove，组内按 student_id 字典序排序。
 */
class SyncPlannerTest {
    @Test
    fun `computeDiff produces add candidate when server has new student id`() {
        val server = listOf(snapshot(studentId = "S001", password = "p1", displayName = "张三"))
        val local = emptyList<LocalAccount>()

        val candidates = SyncPlanner.computeDiff(server, local)

        assertThat(candidates).hasSize(1)
        val candidate = candidates.single()
        assertThat(candidate.kind).isEqualTo(SyncKind.Add)
        assertThat(candidate.studentId).isEqualTo("S001")
        assertThat(candidate.serverPayload).isEqualTo(server.single())
        assertThat(candidate.localSummary).isNull()
        assertThat(candidate.defaultChecked).isTrue()
    }

    @Test
    fun `computeDiff produces replace candidate when password differs`() {
        val server = listOf(snapshot(studentId = "S002", password = "newPwd", displayName = "李四"))
        val local =
            listOf(
                local(
                    studentId = "S002",
                    password = "oldPwd",
                    displayName = "李四",
                    isServerManaged = true,
                ),
            )

        val candidates = SyncPlanner.computeDiff(server, local)

        val candidate = candidates.single()
        assertThat(candidate.kind).isEqualTo(SyncKind.Replace)
        assertThat(candidate.studentId).isEqualTo("S002")
        assertThat(candidate.serverPayload).isEqualTo(server.single())
        assertThat(candidate.localSummary).isEqualTo(local.single().toSummary())
        assertThat(candidate.defaultChecked).isTrue()
    }

    @Test
    fun `computeDiff produces replace candidate when display_name differs`() {
        val server =
            listOf(snapshot(studentId = "S003", password = "p3", displayName = "王五·新"))
        val local =
            listOf(
                local(
                    studentId = "S003",
                    password = "p3",
                    displayName = "王五·旧",
                    isServerManaged = true,
                ),
            )

        val candidates = SyncPlanner.computeDiff(server, local)

        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().kind).isEqualTo(SyncKind.Replace)
    }

    @Test
    fun `computeDiff produces replace candidate when automation_tasks differs`() {
        val task1 =
            AutomationTaskDto(
                taskId = 1L,
                roomName = "三层东区",
                seatNumber = "A12",
                mode = AutomationTaskMode.PREFERRED,
                customWindows = emptyList(),
                enabled = true,
                revision = 1L,
                updatedAt = "2026-04-26T08:00:00Z",
            )
        val task2 = task1.copy(taskId = 2L, seatNumber = "A13")
        val server =
            listOf(
                snapshot(
                    studentId = "S004",
                    password = "p4",
                    displayName = "n",
                    automationTasks = listOf(task1, task2),
                ),
            )
        val local =
            listOf(
                local(
                    studentId = "S004",
                    password = "p4",
                    displayName = "n",
                    automationTasks = listOf(task1),
                    isServerManaged = true,
                ),
            )

        val candidates = SyncPlanner.computeDiff(server, local)

        assertThat(candidates.single().kind).isEqualTo(SyncKind.Replace)
    }

    @Test
    fun `computeDiff produces remove candidate when local is server managed but missing on server`() {
        val server = emptyList<ServerAccountSnapshot>()
        val local =
            listOf(
                local(
                    studentId = "S005",
                    password = "p5",
                    displayName = "赵六",
                    isServerManaged = true,
                ),
            )

        val candidates = SyncPlanner.computeDiff(server, local)

        val candidate = candidates.single()
        assertThat(candidate.kind).isEqualTo(SyncKind.Remove)
        assertThat(candidate.studentId).isEqualTo("S005")
        assertThat(candidate.serverPayload).isNull()
        assertThat(candidate.localSummary).isEqualTo(local.single().toSummary())
        assertThat(candidate.defaultChecked).isFalse()
    }

    @Test
    fun `computeDiff skips remove when local account is not server managed`() {
        val server = emptyList<ServerAccountSnapshot>()
        val local =
            listOf(
                local(
                    studentId = "S006",
                    password = "p6",
                    displayName = "用户自添加",
                    isServerManaged = false,
                ),
            )

        val candidates = SyncPlanner.computeDiff(server, local)

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `computeDiff skips replace when all managed fields equal`() {
        val task =
            AutomationTaskDto(
                taskId = 7L,
                roomName = "二层北区",
                seatNumber = "B01",
                mode = AutomationTaskMode.MANUAL,
                customWindows =
                    listOf(
                        AutomationCustomWindowDto(
                            date = "2026-04-26",
                            startHour = 8,
                            endHour = 12,
                        ),
                    ),
                enabled = true,
                revision = 3L,
                updatedAt = "2026-04-26T08:30:00Z",
            )
        val server =
            listOf(
                snapshot(
                    studentId = "S007",
                    password = "samePwd",
                    displayName = "同步过",
                    automationTasks = listOf(task),
                ),
            )
        val local =
            listOf(
                local(
                    studentId = "S007",
                    password = "samePwd",
                    displayName = "同步过",
                    automationTasks = listOf(task),
                    isServerManaged = true,
                ),
            )

        val candidates = SyncPlanner.computeDiff(server, local)

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `computeDiff skips replace when automation task only differs in unsupported metadata`() {
        val serverTask =
            AutomationTaskDto(
                taskId = 8L,
                roomName = "二层北区",
                seatNumber = "B02",
                mode = AutomationTaskMode.RANDOM,
                customWindows =
                    listOf(
                        AutomationCustomWindowDto(
                            date = "2026-04-27",
                            startHour = 9,
                            endHour = 12,
                        ),
                    ),
                enabled = true,
                revision = 99L,
                updatedAt = "2026-04-27T08:30:00Z",
            )
        val localTask =
            serverTask.copy(
                mode = AutomationTaskMode.PREFERRED,
                revision = 0L,
                updatedAt = "",
            )
        val server =
            listOf(
                snapshot(
                    studentId = "S007",
                    password = "samePwd",
                    displayName = "同步过",
                    automationTasks = listOf(serverTask),
                ),
            )
        val local =
            listOf(
                local(
                    studentId = "S007",
                    password = "samePwd",
                    displayName = "同步过",
                    automationTasks = listOf(localTask),
                    isServerManaged = true,
                ),
            )

        val candidates = SyncPlanner.computeDiff(server, local)

        assertThat(candidates).isEmpty()
    }

    @Test
    fun `computeDiff returns add replace remove ordering with stable student_id sort within group`() {
        val server =
            listOf(
                snapshot(studentId = "S100", password = "p", displayName = "服务端独有 A"),
                snapshot(studentId = "S010", password = "newPwd", displayName = "替换 A"),
                snapshot(studentId = "S020", password = "newPwd", displayName = "替换 B"),
                snapshot(studentId = "S050", password = "p", displayName = "服务端独有 B"),
            )
        val local =
            listOf(
                local(
                    studentId = "S010",
                    password = "oldPwd",
                    displayName = "替换 A",
                    isServerManaged = true,
                ),
                local(
                    studentId = "S020",
                    password = "oldPwd",
                    displayName = "替换 B",
                    isServerManaged = true,
                ),
                local(
                    studentId = "S200",
                    password = "p",
                    displayName = "本地受管但服务端缺失 A",
                    isServerManaged = true,
                ),
                local(
                    studentId = "S300",
                    password = "p",
                    displayName = "本地受管但服务端缺失 B",
                    isServerManaged = true,
                ),
            )

        val candidates = SyncPlanner.computeDiff(server, local)

        // 期望：add(S050, S100) → replace(S010, S020) → remove(S200, S300)，组内按字典序。
        val kinds = candidates.map { it.kind }
        val ids = candidates.map { it.studentId }
        assertThat(kinds)
            .containsExactly(
                SyncKind.Add,
                SyncKind.Add,
                SyncKind.Replace,
                SyncKind.Replace,
                SyncKind.Remove,
                SyncKind.Remove,
            )
            .inOrder()
        assertThat(ids).containsExactly("S050", "S100", "S010", "S020", "S200", "S300").inOrder()
    }

    @Test
    fun `computeDiff is empty when both inputs are empty`() {
        val candidates = SyncPlanner.computeDiff(emptyList(), emptyList())
        assertThat(candidates).isEmpty()
    }

    @Test
    fun `computeDiff does not mutate inputs`() {
        val task =
            AutomationTaskDto(
                taskId = 9L,
                roomName = "r",
                seatNumber = "s",
                mode = AutomationTaskMode.RANDOM,
                customWindows = emptyList(),
                enabled = false,
                revision = 1L,
                updatedAt = "2026-04-26T08:00:00Z",
            )
        val originalServer =
            listOf(
                snapshot(
                    studentId = "S008",
                    password = "p",
                    displayName = "n",
                    automationTasks = listOf(task),
                ),
            )
        val originalLocal =
            listOf(
                local(
                    studentId = "S008",
                    password = "p",
                    displayName = "n",
                    automationTasks = listOf(task),
                    isServerManaged = true,
                ),
            )
        val serverSnapshotCopy = originalServer.toList()
        val localStoreCopy = originalLocal.toList()

        SyncPlanner.computeDiff(originalServer, originalLocal)

        assertThat(originalServer).containsExactlyElementsIn(serverSnapshotCopy).inOrder()
        assertThat(originalLocal).containsExactlyElementsIn(localStoreCopy).inOrder()
    }

    private fun snapshot(
        studentId: String,
        password: String,
        displayName: String,
        automationTasks: List<AutomationTaskDto> = emptyList(),
    ): ServerAccountSnapshot =
        ServerAccountSnapshot(
            studentId = studentId,
            password = password,
            displayName = displayName,
            automationTasks = automationTasks,
        )

    private fun local(
        studentId: String,
        password: String,
        displayName: String,
        automationTasks: List<AutomationTaskDto> = emptyList(),
        isServerManaged: Boolean,
    ): LocalAccount =
        LocalAccount(
            studentId = studentId,
            password = password,
            displayName = displayName,
            automationTasks = automationTasks,
            isServerManaged = isServerManaged,
        )
}
