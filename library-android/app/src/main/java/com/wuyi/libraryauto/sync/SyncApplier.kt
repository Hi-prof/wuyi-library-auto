package com.wuyi.libraryauto.sync

/**
 * 任务 12.12：Manual_Sync_Action 候选条目应用器（与 Window 端 `sync_applier.py` 等价）。
 *
 * 设计依据：
 * - design.md「数据流 4：Manual_Sync_Action 全流程」：用户在 Sync_Coverage_Confirmation
 *   勾选后，apply 阶段仅对勾选 true 的条目按 `kind` 写入本地受管字段。
 * - design.md「Sync_Coverage_Confirmation 数据结构与字段口径」：受管字段集合
 *   `{student_id, password, display_name, automation_tasks, custom_windows}`；
 *   非受管字段（本地备注 / UI 偏好 / 扩展字段 / 排序顺序 等）任何时候都不被本模块修改。
 * - Requirements 13.6 / 13.13 / 13.16 / 13.18：
 *   - 仅对 `selection[sid] == true` 生效；其它条目本地状态保持调用前一致。
 *   - `kind == Remove` 仅取消「受服务端管理」标记，不物理删除 Room 行。
 *   - `selection` 为空或全部 false → noop（既不写入也不删除）；调用方应在 UI
 *     提示「未选择任何账号」。
 *   - Sync_Selection 不持久化；本模块只承担「单次 apply」一次性写入。
 *
 * 行为契约：
 * - **不**做差异计算（差异由 [SyncPlanner] 完成）；本模块只做「按勾选执行写入」。
 * - **不**绕过受管字段口径；`add` / `replace` 一律调用 [LocalAccountStore.applyAddOrReplace]，
 *   `remove` 调用 [LocalAccountStore.unmarkServerManaged]，由存储端负责确保非受管字段
 *   不被覆盖。
 * - 所有写入必须落在同一 Room 事务内（由 [LocalAccountStore.runInTransaction] 提供），
 *   保证「半写不可见」：要么全部成功、要么整体回滚。
 * - 本模块**不**直接持有 Room / EncryptedSharedPreferences 引用，便于测试时注入内存
 *   实现；生产实现见调用方装配。
 */
object SyncApplier {
    /**
     * 应用同步候选条目到本地存储。
     *
     * 流程：
     * 1. 把 [candidates] 按 `studentId` 过滤为 `selection[sid] == true` 的子集；其它条目
     *    在本次调用中完全不被触碰。
     * 2. 子集为空 → 直接返回 [SyncApplyResult.Noop]，不进入事务、不调用 [store]。
     * 3. 子集非空 → 在 [LocalAccountStore.runInTransaction] 中按 `kind` 分发：
     *    - [SyncKind.Add] / [SyncKind.Replace]：调用 [LocalAccountStore.applyAddOrReplace]，
     *      由存储端写入受管字段并标记「受服务端管理」。
     *    - [SyncKind.Remove]：调用 [LocalAccountStore.unmarkServerManaged]，由存储端取消
     *      「受服务端管理」标记；存储端**禁止**物理删除 Room 行。
     * 4. 返回 [SyncApplyResult.Applied]，统计本次实际写入的三类计数。
     *
     * 输入校验：
     * - `candidates` 中重复 `studentId` 的条目不会被本模块拦截；上层 [SyncPlanner]
     *   已经按学号去重，本模块按 `selection[sid]` 过滤即可。
     * - `selection` 中包含 `candidates` 之外的 `studentId` 时被忽略，不影响 apply 结果。
     * - `Add` / `Replace` 候选缺少 [SyncCandidate.serverPayload] 时抛
     *   [IllegalArgumentException]：候选数据不完整属于上层 bug，应直接暴露。
     *
     * @param candidates 由 [SyncPlanner.computeDiff] 产出的候选条目列表。
     * @param selection 用户在 Sync_Coverage_Confirmation 弹窗中的勾选状态映射。
     *   key 为 `studentId`，value 为 `true` 表示用户勾选该条目接收覆盖；缺失或 `false`
     *   表示不接收。
     * @param store 本地存储端，封装 Room 单事务 + 受管字段写入语义。
     * @return [SyncApplyResult]：noop 表示未执行任何写入；Applied 携带三类计数。
     */
    suspend fun apply(
        candidates: List<SyncCandidate>,
        selection: Map<String, Boolean>,
        store: LocalAccountStore,
    ): SyncApplyResult {
        // 仅对 selection[sid] == true 的条目生效；其它候选条目在本次调用中完全不被触碰，
        // 满足 Requirement 13.13「未勾选条目所有字段保持调用前完全一致」。
        val selected = candidates.filter { candidate -> selection[candidate.studentId] == true }

        // selection 为空或全部 false → noop（Requirement 13.15 / 13.16）。
        // 不进入 store.runInTransaction，避免空事务在 Room 上仍然产生 WAL / 观察者副作用。
        if (selected.isEmpty()) {
            return SyncApplyResult.Noop
        }

        var added = 0
        var replaced = 0
        var removed = 0

        // 全过程在单事务内完成（任务 12.12「Room 单事务，避免半写」）；任意一步抛错都让
        // 存储端整体回滚，保证「要么全部成功、要么本地完全不变」。
        store.runInTransaction {
            for (candidate in selected) {
                when (candidate.kind) {
                    SyncKind.Add -> {
                        val server =
                            requireNotNull(candidate.serverPayload) {
                                "Add candidate must carry serverPayload (sid=${candidate.studentId})"
                            }
                        store.applyAddOrReplace(server)
                        added += 1
                    }
                    SyncKind.Replace -> {
                        val server =
                            requireNotNull(candidate.serverPayload) {
                                "Replace candidate must carry serverPayload (sid=${candidate.studentId})"
                            }
                        store.applyAddOrReplace(server)
                        replaced += 1
                    }
                    SyncKind.Remove -> {
                        // 仅取消「受服务端管理」标记，不物理删除（Requirement 13.18 / 13-Q2）。
                        store.unmarkServerManaged(candidate.studentId)
                        removed += 1
                    }
                }
            }
        }

        return SyncApplyResult.Applied(added = added, replaced = replaced, removed = removed)
    }
}

/**
 * apply 结果。
 *
 * - [Noop]：本次调用未对本地存储做任何更改（selection 全空或全 false）。调用方应在 UI
 *   提示「未选择任何账号」（Requirement 13.15）。
 * - [Applied]：携带三类条目的实际写入计数。调用方按 `added` / `replaced` / `removed`
 *   组合 toast「同步成功：新增 X、替换 Y、移除 Z」。
 */
sealed class SyncApplyResult {
    /** selection 为空或全部 false 时的 noop 结果。 */
    data object Noop : SyncApplyResult()

    /** 至少一条候选被应用时的结果，含三类计数。 */
    data class Applied(
        val added: Int,
        val replaced: Int,
        val removed: Int,
    ) : SyncApplyResult() {
        /** 本次 apply 写入的总条目数。 */
        val total: Int
            get() = added + replaced + removed
    }
}

/**
 * 本地账号存储端口（hexagonal-architecture 中的 port）。
 *
 * SyncApplier 通过该端口与具体存储解耦：
 * - 生产实现：把受管字段写入 Room（[com.wuyi.libraryauto.core.storage.db.AppDatabase]）
 *   与加密 SharedPreferences（[com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore]）
 *   的组合，全过程包裹在 [androidx.room.withTransaction] 内。
 * - 测试实现：内存 Map + 顺序记录，便于断言「未勾选条目字段不变 / 非受管字段不被修改 /
 *   selection 全空时 store 未被触碰」等性质（任务 12.15 Property 18）。
 *
 * 端口契约：
 * - [applyAddOrReplace] 与 [unmarkServerManaged] 必须只修改受管字段集合
 *   `{student_id, password, display_name, automation_tasks, custom_windows}`，
 *   非受管字段（本地备注 / UI 偏好 / 扩展字段 / 排序顺序 等）保持调用前一致。
 * - [unmarkServerManaged] **禁止**物理删除 Room 行；它只清除「受服务端管理」标记。
 * - [runInTransaction] 必须保证 block 内所有写入要么全部成功提交、要么整体回滚；
 *   block 抛出的任何异常应在事务回滚后向上传播。
 */
interface LocalAccountStore {
    /**
     * 在单事务内执行 [block]。
     *
     * 生产实现应使用 `androidx.room.withTransaction { block() }`；测试实现可直接执行
     * [block]（in-memory 不存在事务概念，但仍应保证「block 抛错时回滚已写入数据」的
     * 行为以供测试断言）。
     */
    suspend fun runInTransaction(block: suspend () -> Unit)

    /**
     * 应用 add / replace 候选：把服务端受管字段写入本地，并标记「受服务端管理」。
     *
     * 行为约束：
     * - 仅修改受管字段：[ServerAccountSnapshot.studentId] / [ServerAccountSnapshot.password]
     *   / [ServerAccountSnapshot.displayName] / [ServerAccountSnapshot.automationTasks]
     *   及其嵌套 `customWindows`。
     * - 非受管字段（本地备注 / 本地标签 / UI 偏好折叠状态 / 排序顺序 / 用户自添加的扩展
     *   key / 本地 `note` 字段）保持调用前一致。
     * - `add` 与 `replace` 行为对称：实现层按 `studentId` upsert 即可，不需要区分两种语义。
     */
    suspend fun applyAddOrReplace(server: ServerAccountSnapshot)

    /**
     * 应用 remove 候选：取消该 `studentId` 的「受服务端管理」标记。
     *
     * 行为约束：
     * - **禁止**物理删除 Room 行 / SavedAccountStore 条目；只取消管理标记。
     * - 受管字段（学号 / 密码 / 备注 / 自动任务 / 自定义窗口）保持调用前一致；
     *   实现层不得借机覆盖或清理这些字段。
     * - 当 `studentId` 在本地不存在时，应安静返回（与 Window 端 sync_applier 一致），
     *   不抛错；这一情形理论上不会出现，但避免对 SyncPlanner 的输入做强校验。
     */
    suspend fun unmarkServerManaged(studentId: String)
}
