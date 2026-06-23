package com.wuyi.libraryauto.sync

/**
 * 任务 12.11：Manual_Sync_Action 候选条目计算器（与 Window 端 `sync_planner.py` 等价）。
 *
 * 设计依据：
 * - design.md「数据流 4：Manual_Sync_Action 全流程」中按 `student_id` 计算 add / replace /
 *   remove 三类候选条目的 diff 算法。
 * - design.md「Sync_Coverage_Confirmation 数据结构与字段口径」中 [SyncCandidate] 的字段
 *   契约（kind / student_id / server_payload / local_summary / default_checked）。
 * - Requirements 13.5 / 13.10 / 13.14 / 13.17：服务端清单 → 本地差异 → 候选条目。
 *
 * 行为契约：
 * - **不**修改 Room、不写入本地任何持久化层；纯函数式计算。
 * - **不**依赖运行时网络状态；输入是已经拉到的服务端快照与本地存储集合。
 * - 受管字段集合（与 Window 端、Android 端一致）：
 *   `student_id` / `password` / `display_name` / 关联 `automation_tasks` / `custom_windows`。
 * - 三类候选条目的判定口径：
 *   - `add`：服务端 `student_id` 命中、本地无 → `default_checked = true`。
 *   - `replace`：两端 `student_id` 匹配，但任一受管字段不一致 → `default_checked = true`。
 *   - `remove`：本地 [LocalAccount.isServerManaged] 标记为 true、但服务端清单已无该
 *     `student_id` → `default_checked = false`。
 * - 输出顺序：先按 `add → replace → remove` 分组（与 design 伪代码一致），同组内按
 *   `student_id` 字典序稳定排序，便于 UI 渲染时不抖动、便于属性测试断言确定性。
 */
object SyncPlanner {
    /**
     * 计算服务端快照与本地存储之间的同步差异，输出候选条目列表。
     *
     * @param serverSnapshot 接口 A + 接口 B 拉取后合并得到的活跃账号详情快照集合。
     *   每条要包含 `student_id` / `password` / `display_name` / 关联 `automation_tasks` /
     *   `custom_windows`，服务端口径为权威。
     * @param localStore 本地 Room 表中的当前账号集合。每条要包含同样的受管字段，且要带
     *   [LocalAccount.isServerManaged] 标记用于判定 remove 候选。
     * @return 候选条目列表。`add` / `replace` / `remove` 顺序与 design.md 伪代码一致；
     *   同组内按 `student_id` 字典序排序。
     */
    fun computeDiff(
        serverSnapshot: List<ServerAccountSnapshot>,
        localStore: List<LocalAccount>,
    ): List<SyncCandidate> {
        // design.md 伪代码：把两端按 student_id 索引，分三轮扫描产出候选条目。
        // 服务端快照若出现重复 student_id，以后出现者覆盖前者；本地存储同理。这一行为与
        // Window 端 `dict[..]=` 语义一致；上层应通过乐观假设保证唯一性，本计算器不抛错、
        // 不输出诊断告警，避免污染 UI 流程。
        val serverBySid: Map<String, ServerAccountSnapshot> =
            serverSnapshot.associateBy(ServerAccountSnapshot::studentId)
        val localBySid: Map<String, LocalAccount> =
            localStore.associateBy(LocalAccount::studentId)

        val addCandidates = mutableListOf<SyncCandidate>()
        val replaceCandidates = mutableListOf<SyncCandidate>()
        val removeCandidates = mutableListOf<SyncCandidate>()

        // 1) 新增：服务端有、本地无。
        for ((sid, server) in serverBySid) {
            if (sid !in localBySid) {
                addCandidates.add(
                    SyncCandidate(
                        kind = SyncKind.Add,
                        studentId = sid,
                        serverPayload = server,
                        localSummary = null,
                        defaultChecked = true,
                    ),
                )
            }
        }

        // 2) 替换：两端匹配，但任一受管字段不一致。
        for ((sid, server) in serverBySid) {
            val local = localBySid[sid] ?: continue
            if (hasManagedFieldDiff(server = server, local = local)) {
                replaceCandidates.add(
                    SyncCandidate(
                        kind = SyncKind.Replace,
                        studentId = sid,
                        serverPayload = server,
                        localSummary = local.toSummary(),
                        defaultChecked = true,
                    ),
                )
            }
        }

        // 3) 移除：本地受服务端管理、但服务端清单已无对应 student_id。
        for ((sid, local) in localBySid) {
            if (local.isServerManaged && sid !in serverBySid) {
                removeCandidates.add(
                    SyncCandidate(
                        kind = SyncKind.Remove,
                        studentId = sid,
                        serverPayload = null,
                        localSummary = local.toSummary(),
                        defaultChecked = false,
                    ),
                )
            }
        }

        // 同组内按 student_id 字典序稳定排序，输出顺序：add → replace → remove。
        val byStudentId = compareBy<SyncCandidate> { it.studentId }
        return addCandidates.sortedWith(byStudentId) +
            replaceCandidates.sortedWith(byStudentId) +
            removeCandidates.sortedWith(byStudentId)
    }

    /**
     * 受管字段差异判定。任一字段不一致即视为「需要替换」。
     *
     * 字段比较语义：
     * - `student_id`：两端按 student_id 匹配，理论上必相等；这里冗余比较保持算法对称。
     * - `password`：明文比较。空串视为「服务端密码未知」，但 design.md 接口 B 必返非空，
     *   不做特殊处理。
     * - `display_name`：直接字符串比较。空串与非空串语义不同。
     * - `automation_tasks`：只比较 Android 本地能持久化、能影响调度行为的字段子集，
     *   见 [hasAutomationTaskDiff]。
     */
    private fun hasManagedFieldDiff(
        server: ServerAccountSnapshot,
        local: LocalAccount,
    ): Boolean {
        if (server.studentId != local.studentId) return true
        if (server.password != local.password) return true
        if (server.displayName != local.displayName) return true
        if (hasAutomationTaskDiff(server.automationTasks, local.automationTasks)) return true
        return false
    }

    /**
     * 自动任务列表差异判定（按 [AutomationTaskDto.taskId] 主键比较）。
     *
     * 比较口径只覆盖 Android 本地 `automation_plans` schema 实际能落库、且能改变调度
     * 行为的字段：`taskId / roomName / seatNumber / customWindows / enabled`。`mode`
     * （服务端 preferred / manual / random）、`revision`（服务端乐观并发版本号）、
     * `updatedAt`（服务端写入时间戳）这些是服务端独有元数据，本地不持久化也不会基于
     * 它们调度，所以即便服务端响应中带了新 revision，也不应反复触发 replace 候选。
     *
     * 这样做的代价是：服务端把 mode 从 preferred 改成 manual、但其余字段都没动，本地
     * 不会主动提示用户去同步；可以通过 design.md 中其它显式触发点（比如用户在
     * Sync_Coverage_Confirmation 主动点「全选」）来覆盖。考虑到 Android 本地连
     * mode 都没法持久化，反复提示 replace 而又写不进去才是真正的死循环。
     */
    private fun hasAutomationTaskDiff(
        serverTasks: List<AutomationTaskDto>,
        localTasks: List<AutomationTaskDto>,
    ): Boolean {
        if (serverTasks.size != localTasks.size) return true
        val serverByTaskId = serverTasks.associateBy(AutomationTaskDto::taskId)
        val localByTaskId = localTasks.associateBy(AutomationTaskDto::taskId)
        if (serverByTaskId.keys != localByTaskId.keys) return true
        for ((taskId, serverTask) in serverByTaskId) {
            val localTask = localByTaskId.getValue(taskId)
            if (serverTask.roomName != localTask.roomName) return true
            if (serverTask.seatNumber != localTask.seatNumber) return true
            if (serverTask.enabled != localTask.enabled) return true
            if (serverTask.customWindows != localTask.customWindows) return true
        }
        return false
    }
}

/**
 * 候选条目类型（与 Window 端 `kind ∈ {'add','replace','remove'}` 等价）。
 *
 * 使用密封类而非 enum 是为了方便后续扩展携带类型相关字段（例如 remove 携带额外的
 * 「上次同步时间」），同时配合 Kotlin 编译期穷尽匹配给 SyncApplier 编写更强的契约。
 */
sealed class SyncKind {
    /** 服务端有、本地无：把受管字段写入本地。default_checked = true。 */
    data object Add : SyncKind()

    /** 两端匹配但受管字段不一致：用服务端值覆盖本地受管字段。default_checked = true。 */
    data object Replace : SyncKind()

    /**
     * 本地标记「受服务端管理」但服务端已无该 student_id：仅取消「受服务端管理」标记，
     * **不**物理删除 Room 行（Requirement 13-Q2）。default_checked = false。
     */
    data object Remove : SyncKind()
}

/**
 * 同步候选条目。Sync_Coverage_Confirmation 弹窗以此渲染列表。
 *
 * 字段语义与 design.md「Sync_Coverage_Confirmation 数据结构与字段口径」一致：
 * - `add` / `replace`：[serverPayload] 必填，[SyncKind.Add] 时 [localSummary] 为 null，
 *   [SyncKind.Replace] 时 [localSummary] 必填便于 UI 展示「替换前后对比」。
 * - `remove`：[serverPayload] 为 null，[localSummary] 必填便于 UI 展示要被取消管理的本地
 *   账号摘要。
 * - [defaultChecked] 给 UI 在初始化 Sync_Selection 时使用：add/replace 默认 true，
 *   remove 默认 false。
 */
data class SyncCandidate(
    val kind: SyncKind,
    val studentId: String,
    val serverPayload: ServerAccountSnapshot?,
    val localSummary: LocalAccountSummary?,
    val defaultChecked: Boolean,
)

/**
 * 服务端快照中的单个活跃账号，受管字段全集。
 *
 * 由 [AccountPoolSyncRepository.refreshActiveList] + 多次
 * [AccountPoolSyncRepository.getActiveAccountDetail] 合并产生：接口 A 提供清单，接口 B 提供
 * 明文密码与 `automation_tasks`。
 *
 * **注意**：[password] 是明文密码，**不**得持久化。SyncPlanner 仅做内存计算，候选条目交给
 * SyncApplier 时由 ViewModel 在该单次 Manual_Sync_Action 流程内引用，流程结束立即释放。
 *
 * [accountId] 任务 12.14 新增：生产 [LocalAccountStore] 实现把账号写入 Room
 * `active_accounts` 表时需要主键 `accountId`，由接口 A / B 响应直接透传。默认值 0 仅用于
 * 单元测试构造便利（不会落库）。
 */
data class ServerAccountSnapshot(
    val studentId: String,
    val password: String,
    val displayName: String,
    val automationTasks: List<AutomationTaskDto>,
    val accountId: Long = 0L,
)

/**
 * 本地存储中的单个账号视图（受管字段 + 管理标记）。
 *
 * 由调用方从 Room 表（账号表 + 自动任务表）合并装配；SyncPlanner 仅做纯函数计算，**不**
 * 直接读取 Room、**不**触发任何 IO。
 *
 * - [isServerManaged]：标记该账号是否「受服务端管理活跃账号」。该标记决定 remove 候选条目
 *   是否产出（design.md 伪代码：`if local_row.is_server_managed and sid not in server_by_sid`）。
 *   未标记的本地账号即使服务端清单缺失，也不会被列入 remove 候选，避免误清理用户手动添加
 *   的账号。
 * - [password]：本地保留的密码字段（如果客户端从未通过接口 B 拉取过则可为空字符串）。
 *   与服务端不一致即触发 replace。
 */
data class LocalAccount(
    val studentId: String,
    val password: String,
    val displayName: String,
    val automationTasks: List<AutomationTaskDto>,
    val isServerManaged: Boolean,
)

/**
 * 本地账号摘要，供 Sync_Coverage_Confirmation 弹窗在 replace / remove 候选条目上展示
 * 「替换前 / 移除前」的本地状态对比。
 *
 * 摘要字段集合与受管字段一致，但**不**回传明文密码（弹窗在 UI 层只展示遮罩字符串，避免
 * 屏幕录像 / 截图泄露）。
 */
data class LocalAccountSummary(
    val studentId: String,
    val displayName: String,
    val automationTaskCount: Int,
    val customWindowCount: Int,
)

/**
 * [LocalAccount] → [LocalAccountSummary]：摘要不携带明文密码，自动任务与自定义窗口仅以
 * 计数形式展示，避免在弹窗 UI 层暴露过多内部细节。
 */
internal fun LocalAccount.toSummary(): LocalAccountSummary =
    LocalAccountSummary(
        studentId = studentId,
        displayName = displayName,
        automationTaskCount = automationTasks.size,
        customWindowCount = automationTasks.sumOf { task -> task.customWindows.size },
    )
