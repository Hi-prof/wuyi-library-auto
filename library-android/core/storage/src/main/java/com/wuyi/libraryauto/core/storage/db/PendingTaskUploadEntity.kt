package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `account-pool-tri-sync` 任务 12.3：待上传的自动任务变更与拉黑事件队列实体。
 *
 * 设计要点：
 * - 用户在 UI 上触发 PUT / DELETE 自动任务、上报拉黑事件时，先把动作以一条 [PendingTaskUploadEntity]
 *   形式持久化到本表，再排队 [com.wuyi.libraryauto.sync.AutomationTaskUploadWorker] 由后台 Worker
 *   异步发送到服务端。这样即便 App 被杀进程或网络抖动，重启后 Worker 也能重放队列。
 * - [kind] 字段区分三类负载：
 *   - [KIND_UPSERT]：服务端 `PUT /api/v1/active-accounts/{accountId}/automation-tasks/{taskId}`，
 *     [payloadJson] 序列化 `AutomationTaskUpsertRequest`，[revision] 来自 [payloadJson]。
 *   - [KIND_DELETE]：服务端 `DELETE /api/v1/active-accounts/{accountId}/automation-tasks/{taskId}?revision=...`，
 *     [payloadJson] 为空，[revision] 必填。
 *   - [KIND_BLACKLIST]：服务端 `POST /api/v1/active-accounts/{accountId}/blacklist-events`，
 *     [payloadJson] 序列化 `BlacklistEventRequest`，[taskId] 与 [revision] 均为空。
 * - [createdAtEpochSeconds] 固定打入入队时刻，便于 Worker 按 FIFO 取出（`ORDER BY createdAt ASC, id ASC`），
 *   保证「先编辑先发」的客户端语义。
 * - [retryCount] 由 Worker 在每次拿到可重试错误后递增，仅供诊断 / UI 展示用，不参与 WorkManager 的退避策略
 *   （后者由 [com.wuyi.libraryauto.sync.AutomationTaskUploadWorker] 的 BackoffPolicy 直接控制）。
 *
 * 注意：本表只持久化「上行」队列，**不**包含密码或 Cookie；服务端返回的 `password` 字段只在内存里短时存在，
 * 仍由 [com.wuyi.libraryauto.sync.AccountPoolSyncRepository] 严格保证。
 */
@Entity(tableName = "pending_task_uploads")
data class PendingTaskUploadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val kind: String,
    val accountId: Long,
    val taskId: Long?,
    val payloadJson: String?,
    val revision: Long?,
    val createdAtEpochSeconds: Long,
    val retryCount: Int = 0,
    val lastErrorReason: String? = null,
) {
    companion object {
        /** PUT 自动任务：[payloadJson] 必填，[taskId] 必填，[revision] 必填。 */
        const val KIND_UPSERT: String = "upsert"

        /** DELETE 自动任务：[taskId] 必填，[revision] 必填。 */
        const val KIND_DELETE: String = "delete"

        /** POST 拉黑事件：[payloadJson] 必填，[taskId] 与 [revision] 为空。 */
        const val KIND_BLACKLIST: String = "blacklist"
    }
}
