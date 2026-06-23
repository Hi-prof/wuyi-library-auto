package com.wuyi.libraryauto.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 任务 12.12 单元测试：SyncApplier。
 *
 * 覆盖任务描述的核心行为契约：
 * - selection 为空或全部 false → noop（既不写入也不删除）；
 * - selection[sid] == true → 按 kind 写入 Room（add / replace 调用受管字段写入；remove
 *   仅取消「受服务端管理」标记，不物理删除）；
 * - selection[sid] == false → 该候选条目对应的本地状态保持调用前完全一致；
 * - 非受管字段（本地备注 / UI 偏好 / 扩展字段）任何时候都不被 SyncApplier 修改；
 * - 全过程在 LocalAccountStore.runInTransaction 单事务内完成；任意一步抛错触发整体回滚。
 *
 * 用 in-memory [TestLocalAccountStore] 模拟生产 Room 实现：
 * - 维护 `accounts` 与 `localAnnotations` / `localUiPrefs` 两组字段，分别对应「受管字段」
 *   与「非受管字段」；
 * - 记录 [TestLocalAccountStore.calls] 顺序，断言「selection 全空时 store 未被触碰」与
 *   「runInTransaction 包裹所有写入」。
 */
class SyncApplierTest {
    @Test
    fun `noop when selection is empty`() = runBlocking {
        val store = TestLocalAccountStore()
        val candidates =
            listOf(
                addCandidate("s1"),
                replaceCandidate("s2"),
                removeCandidate("s3"),
            )

        val result = SyncApplier.apply(candidates = candidates, selection = emptyMap(), store = store)

        assertThat(result).isEqualTo(SyncApplyResult.Noop)
        // selection 全空时 store 不应被触碰：不能进事务、不能调写入方法。
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun `noop when selection is all false`() = runBlocking {
        val store = TestLocalAccountStore()
        val candidates =
            listOf(
                addCandidate("s1"),
                replaceCandidate("s2"),
                removeCandidate("s3"),
            )
        val selection = mapOf("s1" to false, "s2" to false, "s3" to false)

        val result = SyncApplier.apply(candidates, selection, store)

        assertThat(result).isEqualTo(SyncApplyResult.Noop)
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun `applies add and replace by writing managed fields and marking server-managed`() = runBlocking {
        val store = TestLocalAccountStore()
        // 预置一条本地账号供 replace 写入；保留非受管字段以验证不被覆盖。
        store.seed(
            studentId = "s2",
            password = "old-pwd",
            displayName = "old-name",
            isServerManaged = true,
            localAnnotation = "本地备注 - 不该被改",
            localUiPref = "ui-pref - 不该被改",
        )
        val candidates =
            listOf(
                addCandidate(
                    studentId = "s1",
                    password = "p1",
                    displayName = "n1",
                ),
                replaceCandidate(
                    studentId = "s2",
                    password = "new-pwd",
                    displayName = "new-name",
                ),
            )
        val selection = mapOf("s1" to true, "s2" to true)

        val result = SyncApplier.apply(candidates, selection, store)

        assertThat(result).isEqualTo(SyncApplyResult.Applied(added = 1, replaced = 1, removed = 0))
        // 受管字段被写入。
        val s1 = store.find("s1")!!
        assertThat(s1.password).isEqualTo("p1")
        assertThat(s1.displayName).isEqualTo("n1")
        assertThat(s1.isServerManaged).isTrue()
        val s2 = store.find("s2")!!
        assertThat(s2.password).isEqualTo("new-pwd")
        assertThat(s2.displayName).isEqualTo("new-name")
        assertThat(s2.isServerManaged).isTrue()
        // 非受管字段保留：replace 不应清空本地备注 / UI 偏好。
        assertThat(s2.localAnnotation).isEqualTo("本地备注 - 不该被改")
        assertThat(s2.localUiPref).isEqualTo("ui-pref - 不该被改")
        // 必须包裹在事务内：第一次调用就是 BEGIN。
        assertThat(store.calls.first()).isEqualTo("BEGIN")
        assertThat(store.calls.last()).isEqualTo("COMMIT")
    }

    @Test
    fun `remove cancels server-managed flag without physically deleting row`() = runBlocking {
        val store = TestLocalAccountStore()
        store.seed(
            studentId = "s9",
            password = "pwd",
            displayName = "name",
            isServerManaged = true,
            localAnnotation = "本地备注",
            localUiPref = "ui-pref",
        )
        val candidates = listOf(removeCandidate("s9"))
        val selection = mapOf("s9" to true)

        val result = SyncApplier.apply(candidates, selection, store)

        assertThat(result).isEqualTo(SyncApplyResult.Applied(added = 0, replaced = 0, removed = 1))
        // 行没被物理删除：仍能查到，且受管字段保持调用前一致。
        val s9 = store.find("s9")!!
        assertThat(s9.isServerManaged).isFalse()
        assertThat(s9.password).isEqualTo("pwd")
        assertThat(s9.displayName).isEqualTo("name")
        // 非受管字段保留。
        assertThat(s9.localAnnotation).isEqualTo("本地备注")
        assertThat(s9.localUiPref).isEqualTo("ui-pref")
    }

    @Test
    fun `unselected candidates leave local rows untouched`() = runBlocking {
        val store = TestLocalAccountStore()
        store.seed(
            studentId = "s2",
            password = "old-pwd",
            displayName = "old-name",
            isServerManaged = true,
            localAnnotation = "anno",
            localUiPref = "pref",
        )
        store.seed(
            studentId = "s3",
            password = "kept-pwd",
            displayName = "kept-name",
            isServerManaged = true,
            localAnnotation = "anno-3",
            localUiPref = "pref-3",
        )
        val candidates =
            listOf(
                addCandidate("s1"),
                replaceCandidate("s2", password = "new-pwd", displayName = "new-name"),
                removeCandidate("s3"),
            )
        // 仅勾选 s1（add）；s2 / s3 未勾选 → 不应被改动。
        val selection = mapOf("s1" to true, "s2" to false /* s3 缺失 */)

        val result = SyncApplier.apply(candidates, selection, store)

        assertThat(result).isEqualTo(SyncApplyResult.Applied(added = 1, replaced = 0, removed = 0))
        // 未勾选的 s2 完全不变。
        val s2 = store.find("s2")!!
        assertThat(s2.password).isEqualTo("old-pwd")
        assertThat(s2.displayName).isEqualTo("old-name")
        assertThat(s2.isServerManaged).isTrue()
        assertThat(s2.localAnnotation).isEqualTo("anno")
        assertThat(s2.localUiPref).isEqualTo("pref")
        // 未勾选的 s3 完全不变（remove 候选未生效）。
        val s3 = store.find("s3")!!
        assertThat(s3.password).isEqualTo("kept-pwd")
        assertThat(s3.displayName).isEqualTo("kept-name")
        assertThat(s3.isServerManaged).isTrue()
    }

    @Test
    fun `transaction rolls back when store throws mid-apply`() = runBlocking {
        val store = TestLocalAccountStore(failOnStudentId = "s2")
        val candidates =
            listOf(
                addCandidate("s1", password = "p1"),
                addCandidate("s2", password = "p2"),
            )
        val selection = mapOf("s1" to true, "s2" to true)

        val thrown =
            runCatching { SyncApplier.apply(candidates, selection, store) }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        // 整体回滚：s1 不应留存写入痕迹。
        assertThat(store.find("s1")).isNull()
        // 调用序列必须有 BEGIN 与 ROLLBACK，没有 COMMIT。
        assertThat(store.calls).contains("BEGIN")
        assertThat(store.calls).contains("ROLLBACK")
        assertThat(store.calls).doesNotContain("COMMIT")
    }

    @Test
    fun `applies multiple of each kind reporting accurate counts`() = runBlocking {
        val store = TestLocalAccountStore()
        store.seed("s2", "old", "old", isServerManaged = true)
        store.seed("s3", "old", "old", isServerManaged = true)
        store.seed("s4", "old", "old", isServerManaged = true)
        store.seed("s5", "old", "old", isServerManaged = true)

        val candidates =
            listOf(
                addCandidate("s1"),
                addCandidate("s6"),
                replaceCandidate("s2", "p2", "n2"),
                replaceCandidate("s3", "p3", "n3"),
                replaceCandidate("s4", "p4", "n4"),
                removeCandidate("s5"),
            )
        val selection =
            mapOf(
                "s1" to true,
                "s6" to true,
                "s2" to true,
                "s3" to true,
                "s4" to true,
                "s5" to true,
            )

        val result = SyncApplier.apply(candidates, selection, store)

        assertThat(result).isEqualTo(SyncApplyResult.Applied(added = 2, replaced = 3, removed = 1))
        val applied = result as SyncApplyResult.Applied
        assertThat(applied.total).isEqualTo(6)
    }

    // ─────────────────────────────────────────────────
    // 测试辅助
    // ─────────────────────────────────────────────────

    private fun addCandidate(
        studentId: String,
        password: String = "pwd-$studentId",
        displayName: String = "name-$studentId",
    ): SyncCandidate =
        SyncCandidate(
            kind = SyncKind.Add,
            studentId = studentId,
            serverPayload =
                ServerAccountSnapshot(
                    studentId = studentId,
                    password = password,
                    displayName = displayName,
                    automationTasks = emptyList(),
                ),
            localSummary = null,
            defaultChecked = true,
        )

    private fun replaceCandidate(
        studentId: String,
        password: String = "pwd-$studentId",
        displayName: String = "name-$studentId",
    ): SyncCandidate =
        SyncCandidate(
            kind = SyncKind.Replace,
            studentId = studentId,
            serverPayload =
                ServerAccountSnapshot(
                    studentId = studentId,
                    password = password,
                    displayName = displayName,
                    automationTasks = emptyList(),
                ),
            localSummary =
                LocalAccountSummary(
                    studentId = studentId,
                    displayName = "old",
                    automationTaskCount = 0,
                    customWindowCount = 0,
                ),
            defaultChecked = true,
        )

    private fun removeCandidate(studentId: String): SyncCandidate =
        SyncCandidate(
            kind = SyncKind.Remove,
            studentId = studentId,
            serverPayload = null,
            localSummary =
                LocalAccountSummary(
                    studentId = studentId,
                    displayName = "old",
                    automationTaskCount = 0,
                    customWindowCount = 0,
                ),
            defaultChecked = false,
        )

    /**
     * 内存版 [LocalAccountStore]，模拟生产 Room 实现：
     * - 在 [runInTransaction] 中记录 `BEGIN` / `COMMIT` / `ROLLBACK`，模拟事务边界；
     *   抛错时对 block 内的写入做整体回滚（撤销自 BEGIN 以来对 [accounts] 的所有变更）。
     * - 区分受管字段（password / displayName / isServerManaged / automationTasks）与
     *   非受管字段（localAnnotation / localUiPref），便于断言「非受管字段不被修改」。
     */
    private class TestLocalAccountStore(
        private val failOnStudentId: String? = null,
    ) : LocalAccountStore {
        private val store: MutableMap<String, AccountRow> = linkedMapOf()
        val calls: MutableList<String> = mutableListOf()
        private var snapshotBeforeTx: Map<String, AccountRow>? = null

        fun seed(
            studentId: String,
            password: String,
            displayName: String,
            isServerManaged: Boolean,
            localAnnotation: String = "",
            localUiPref: String = "",
        ) {
            store[studentId] =
                AccountRow(
                    studentId = studentId,
                    password = password,
                    displayName = displayName,
                    isServerManaged = isServerManaged,
                    localAnnotation = localAnnotation,
                    localUiPref = localUiPref,
                )
        }

        fun find(studentId: String): AccountRow? = store[studentId]

        override suspend fun runInTransaction(block: suspend () -> Unit) {
            calls += "BEGIN"
            // 拍快照，便于失败回滚。
            snapshotBeforeTx = store.toMap()
            try {
                block()
                calls += "COMMIT"
            } catch (t: Throwable) {
                // 回滚：把 store 恢复到 BEGIN 前的快照。
                store.clear()
                store.putAll(snapshotBeforeTx!!)
                calls += "ROLLBACK"
                throw t
            } finally {
                snapshotBeforeTx = null
            }
        }

        override suspend fun applyAddOrReplace(server: ServerAccountSnapshot) {
            calls += "applyAddOrReplace(${server.studentId})"
            if (server.studentId == failOnStudentId) {
                error("simulated failure on ${server.studentId}")
            }
            val previous = store[server.studentId]
            // 关键约束：只覆盖受管字段；非受管字段（localAnnotation / localUiPref）保持不变。
            store[server.studentId] =
                AccountRow(
                    studentId = server.studentId,
                    password = server.password,
                    displayName = server.displayName,
                    isServerManaged = true,
                    localAnnotation = previous?.localAnnotation.orEmpty(),
                    localUiPref = previous?.localUiPref.orEmpty(),
                )
        }

        override suspend fun unmarkServerManaged(studentId: String) {
            calls += "unmarkServerManaged($studentId)"
            val previous = store[studentId] ?: return
            // 仅取消「受服务端管理」标记；其它字段（含受管字段）保持不变。
            store[studentId] = previous.copy(isServerManaged = false)
        }
    }

    /** 内存账号行，覆盖受管 + 非受管字段。 */
    private data class AccountRow(
        val studentId: String,
        val password: String,
        val displayName: String,
        val isServerManaged: Boolean,
        val localAnnotation: String,
        val localUiPref: String,
    )
}
