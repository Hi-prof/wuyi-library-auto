package com.wuyi.libraryauto.sync

import androidx.room.withTransaction
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.ActiveAccountDao
import com.wuyi.libraryauto.core.storage.db.ActiveAccountEntity
import com.wuyi.libraryauto.core.storage.db.AppDatabase
import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity

/**
 * 任务 12.14：[LocalAccountStore] 的生产实现。
 *
 * 责任：
 * - 把 [ServerAccountSnapshot] 中的受管字段写入三块本地存储：
 *   - [SavedAccountStore]：账号 + 密码（明文）+ 登录所需字段；本地业务执行入口（自动任务、登录刷新、
 *     座位监控）实际读这块。
 *   - [ActiveAccountDao]：服务端 Active_Pool 缓存，作为「受服务端管理活跃账号」标记的载体；行存在
 *     即视为 [LocalAccount.isServerManaged] = true。
 *   - [AutomationPlanDao]：把服务端 [AutomationTaskDto] 映射为本地 [AutomationPlanEntity] 落库，
 *     使下一次 Manual_Sync_Action 比较时本地与服务端能保持一致，避免反复提示 replace。
 * - `unmarkServerManaged` 只删除 [ActiveAccountDao] 里的对应行（取消管理标记），
 *   **不**删除 [SavedAccountStore] 中的账号（Requirement 13.18 / 13-Q2）。
 * - 整个 apply 流程通过 [AppDatabase.withTransaction] 包裹：避免半写状态被 UI Flow 观察到。
 *
 * 自动任务持久化映射策略：
 * - 服务端 [AutomationTaskDto] 用稳定前缀 `server-task:{accountId}:{taskId}` 写入 [AutomationPlanEntity.planId]，
 *   便于在「该账号的服务端任务」与「用户本地手动建立的计划」之间区分边界。
 * - 服务端 `mode`（preferred / manual / random）与本地 [com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode]
 *   （CONTINUOUS / SINGLE_CUSTOM）不是 1:1 映射；这里按 `customWindows` 为空与否做保守降级：
 *   空 → CONTINUOUS（持续预约）；非空 → SINGLE_CUSTOM（取首个时段写入 single_* 字段）。
 *   这样不引入 schema 迁移，调度器依赖的输入契约保持不变。
 * - apply 时按 `accountId` 前缀清空旧的服务端受管计划再写入新的，避免服务端删任务后本地残留；
 *   同时不会动用户本地手动建的计划（因为它们的 planId 没有 `server-task:{accountId}:` 前缀）。
 */
class RoomLocalAccountStore(
    private val database: AppDatabase,
    private val savedAccountStore: SavedAccountStore,
    private val activeAccountDao: ActiveAccountDao = database.activeAccountDao(),
    private val automationPlanDao: AutomationPlanDao = database.automationPlanDao(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : LocalAccountStore {
    override suspend fun runInTransaction(block: suspend () -> Unit) {
        // androidx.room.withTransaction 提供挂起函数级别的事务边界：block 内部任意挂起 / 抛错都会
        // 整体回滚 Room 写入。SavedAccountStore 是 EncryptedSharedPreferences，本身不在事务范围内，
        // 但其写入是「读 → 改 → 写」的同步 commit，单条调用具备原子性；apply 流程整体若中途抛错，
        // 调用方会重新拉起一次 Manual_Sync_Action 后再次落盘，不会留下「Room 已写但 SavedAccount 没写」
        // 的混合中间态（apply 内对每个候选只调用一次 store，且写入顺序在内部固定为「先 saved，再 dao」）。
        database.withTransaction { block() }
    }

    override suspend fun applyAddOrReplace(server: ServerAccountSnapshot) {
        // 1) 写 SavedAccountStore：受管字段「学号 + 密码」整体覆盖；preferredRoomName /
        //    preferredSeatNumber 由 SavedAccountStore.save 内部读取既有行后保留。
        savedAccountStore.save(studentId = server.studentId, password = server.password)
        // 2) 写 active_accounts 缓存：作为「受服务端管理」标记的载体。
        val nowEpoch = nowEpochSeconds()
        activeAccountDao.upsert(
            ActiveAccountEntity(
                accountId = server.accountId,
                studentId = server.studentId,
                displayName = server.displayName,
                poolStatus = "active",
                updatedAt = "",
                syncedAtEpochSeconds = nowEpoch,
            ),
        )
        // 3) 同步该账号的服务端受管自动任务：先按前缀清空旧记录，再批量 upsert 新记录。
        //    这样能正确处理「服务端删了某个 task」的场景，又不会动用户本地手动建的计划。
        automationPlanDao.deleteByPlanIdPrefix(serverTaskPlanIdPrefix(server.accountId))
        for (task in server.automationTasks) {
            automationPlanDao.upsert(toAutomationPlanEntity(server, task, nowEpoch))
        }
    }

    override suspend fun unmarkServerManaged(studentId: String) {
        // 仅取消「受服务端管理」标记：删除 active_accounts 缓存对应行；
        // SavedAccountStore 中的账号记录、preferredRoomName / preferredSeatNumber 保持不变。
        activeAccountDao.deleteByStudentId(studentId)
    }

    /**
     * 装配本地账号集合用于 [SyncPlanner.computeDiff]。
     *
     * 数据来源：
     * - [SavedAccountStore.readAll]：账号学号 + 密码 + 偏好（password 字段是受管字段；偏好是非受管字段）。
     * - [ActiveAccountDao.findAll]：服务端管理标记 + accountId + display_name。
     * - [AutomationPlanDao.findAll]：按 `server-task:{accountId}:` 前缀过滤出该账号的服务端受管任务。
     *
     * 合并规则：以学号为主键，左连接 active_accounts；命中即视为 `isServerManaged=true`，并按
     * 该账号的 `accountId` 反查 plans，重新组装出 [AutomationTaskDto] 列表。`mode` / `revision` /
     * `updatedAt` 这三类字段本地无法持久化，这里填占位值；[SyncPlanner.hasAutomationTaskDiff]
     * 会忽略它们，保证比较口径稳定。
     */
    suspend fun loadAll(): List<LocalAccount> {
        val saved = savedAccountStore.readAll()
        val managed = activeAccountDao.findAll().associateBy(ActiveAccountEntity::studentId)
        val plansByAccountId = automationPlanDao.findAll()
            .mapNotNull { entity ->
                val parsed = parseServerTaskPlanId(entity.planId) ?: return@mapNotNull null
                parsed to entity
            }
            .groupBy({ it.first.accountId }, { it.second })
        return saved.map { entry ->
            val managedRow = managed[entry.studentId]
            val tasks =
                if (managedRow == null) {
                    emptyList()
                } else {
                    plansByAccountId[managedRow.accountId]
                        ?.mapNotNull(::fromAutomationPlanEntity)
                        .orEmpty()
                }
            LocalAccount(
                studentId = entry.studentId,
                password = entry.password,
                displayName = managedRow?.displayName.orEmpty(),
                automationTasks = tasks,
                isServerManaged = managedRow != null,
            )
        }
    }

    private fun toAutomationPlanEntity(
        server: ServerAccountSnapshot,
        task: AutomationTaskDto,
        nowEpoch: Long,
    ): AutomationPlanEntity {
        // 服务端 customWindows 为空 → 持续预约 (CONTINUOUS)；
        // 取出第一个 customWindow → 单次自定义时段 (SINGLE_CUSTOM)。
        // 多个 customWindow 的场景在 Android 端没有 schema 表达能力，先保守按首个时段降级，
        // 让调度器至少能跑起来；后续若需要完整支持，应在 design 层先扩展 plan schema。
        val firstWindow = task.customWindows.firstOrNull()
        val mode = if (firstWindow == null) "CONTINUOUS" else "SINGLE_CUSTOM"
        return AutomationPlanEntity(
            planId = "${serverTaskPlanIdPrefix(server.accountId)}${task.taskId}",
            studentId = server.studentId,
            roomName = task.roomName,
            seatNumber = task.seatNumber,
            mode = mode,
            singleDate = firstWindow?.date,
            singleStartTime = firstWindow?.let { formatHour(it.startHour) },
            singleEndTime = firstWindow?.let { formatHour(it.endHour) },
            enabled = task.enabled,
            createdAtEpochSeconds = nowEpoch,
            updatedAtEpochSeconds = nowEpoch,
            nextRunAtEpochSeconds = nowEpoch,
            lastRunAtEpochSeconds = null,
            lastResultMessage = "",
        )
    }

    private fun fromAutomationPlanEntity(entity: AutomationPlanEntity): AutomationTaskDto? {
        val parsed = parseServerTaskPlanId(entity.planId) ?: return null
        val customWindow =
            if (entity.mode == "SINGLE_CUSTOM" &&
                entity.singleDate != null &&
                entity.singleStartTime != null &&
                entity.singleEndTime != null
            ) {
                AutomationCustomWindowDto(
                    date = entity.singleDate!!,
                    startHour = parseHour(entity.singleStartTime!!),
                    endHour = parseHour(entity.singleEndTime!!),
                )
            } else {
                null
            }
        return AutomationTaskDto(
            taskId = parsed.taskId,
            roomName = entity.roomName,
            seatNumber = entity.seatNumber,
            // 本地 schema 不持久化服务端 mode/revision/updatedAt；用稳定占位值，
            // SyncPlanner 比较时会忽略这些字段。
            mode = AutomationTaskMode.PREFERRED,
            customWindows = listOfNotNull(customWindow),
            enabled = entity.enabled,
            revision = 0L,
            updatedAt = "",
        )
    }

    private fun serverTaskPlanIdPrefix(accountId: Long): String = "server-task:$accountId:"

    private fun parseServerTaskPlanId(planId: String): ServerTaskPlanId? {
        val prefix = "server-task:"
        if (!planId.startsWith(prefix)) return null
        val rest = planId.removePrefix(prefix)
        val parts = rest.split(":")
        if (parts.size != 2) return null
        val accountId = parts[0].toLongOrNull() ?: return null
        val taskId = parts[1].toLongOrNull() ?: return null
        return ServerTaskPlanId(accountId = accountId, taskId = taskId)
    }

    private data class ServerTaskPlanId(val accountId: Long, val taskId: Long)

    private fun formatHour(hour: Int): String = "%02d:00".format(hour)

    private fun parseHour(time: String): Int = time.substringBefore(":").toIntOrNull() ?: 0
}
