package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `account-pool-tri-sync` 任务 12.3：自动任务上传遇到 `409 revision_conflict` 时的「待人工解决」记录。
 *
 * 设计要点：
 * - Worker 在上传 PUT/DELETE 时若收到 409，会：
 *   1. 把服务端最新 `server_revision` 与 `server_payload` 持久化到本表；
 *   2. 移除对应的 [PendingTaskUploadEntity]（避免无限重试用错误 revision 砸服务端）；
 *   3. 返回 `Result.success()` 让 WorkManager 不再调度该任务，把决定权交给 UI。
 * - UI 层（冲突解决 ViewModel）需要监听本表的变更，弹出冲突解决面板：
 *   - 「保留本地」→ 把 `localPayloadJson` 重新封装成 [PendingTaskUploadEntity] 并写入 `revision = serverRevision` 后入队；
 *   - 「接受服务端」→ 用 `serverPayloadJson` 覆盖本地 Room 中的自动任务并丢弃冲突记录。
 * - [conflictHash] 用于「同一 (accountId,taskId) 的同一冲突」幂等：再次发生 409 时按主键 upsert，避免堆积重复行。
 */
@Entity(tableName = "task_upload_conflicts")
data class TaskUploadConflictEntity(
    @PrimaryKey
    val conflictHash: String,
    val accountId: Long,
    val taskId: Long,
    val kind: String,
    val localPayloadJson: String?,
    val localRevision: Long,
    val serverPayloadJson: String?,
    val serverRevision: Long,
    val detectedAtEpochSeconds: Long,
) {
    companion object {
        /**
         * 计算冲突主键。同一 (accountId,taskId,kind) 始终落到同一行，最后一次冲突结果覆盖前一次。
         */
        fun hashOf(
            accountId: Long,
            taskId: Long,
            kind: String,
        ): String = "$accountId:$taskId:$kind"
    }
}
