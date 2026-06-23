package com.wuyi.libraryauto.sync

import android.content.Context
import android.util.Log
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadDao
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadEntity
import kotlinx.serialization.json.Json

/**
 * `account-pool-tri-sync` 任务 12.3：自动任务编辑 / 拉黑事件上报的入口类。
 *
 * 在 ViewModel / UI 层不直接调用 [AccountPoolApi]：所有上行动作都先经此处写入 [PendingTaskUploadDao]
 * 队列，再触发 [AutomationTaskUploadWorker]。这样可以保证：
 * - **本地立刻可见**：用户编辑自动任务后即便处于离线状态，UI 也能从「本地待发送」队列中读到最新意图；
 * - **崩溃可恢复**：进程被杀重启后队列还在，Worker 重新跑就会发出去；
 * - **服务端确认后用 `revision` 覆盖本地**：Worker 在 200/201 后取响应里的 `revision`，由调用方
 *   在自己业务表里更新；本组件不直接耦合业务表。
 *
 * 注意：本类只负责「写队列 + 排 Worker」。当应用上下文未注册 [AutomationTaskUploadWorker.Provider]
 * 时（比如纯单元测试），调用 [enqueueWorker = false] 可以单独验证写队列行为。
 *
 * 任务 12.10：在自动任务编辑入口（[enqueueUpsert] / [enqueueDelete]）内置一道
 * [ServerSyncConfig.isUploadEnabled] 双开关守卫。`Server_Sync_Config` 未配置或同步上行开关关闭时
 * （Local_Only_Mode），调用方对自动任务的本地编辑已经在业务 Room 表中落库（Requirement 14.1），
 * 本类 **不** 写入 [PendingTaskUploadDao] 队列、**不** 排 [AutomationTaskUploadWorker]、**不**
 * 触发任何网络请求；返回 [SKIPPED_PENDING_ID] 让 ViewModel / UI 可做相应反馈（Requirement 14.2）。
 *
 * 拉黑事件上报（[enqueueBlacklistEvent]）的双开关守卫由 [BlacklistReporter] 统一承担（任务 12.9），
 * 本类不重复检查，避免双重日志与返回值含义混淆。
 */
class AutomationTaskUploader(
    private val context: Context,
    private val pendingDao: PendingTaskUploadDao,
    private val serverSyncConfig: ServerSyncConfig = ServerSyncConfig(context.applicationContext),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val json: Json = AccountPoolApiFactory.DefaultJson,
) {
    /**
     * 提交一次自动任务 PUT。
     *
     * 任务 12.10：内置 [ServerSyncConfig.isUploadEnabled] 双开关守卫。`Server_Sync_Config` 未配置
     * 或同步上行开关关闭时直接静默跳过（不写 [PendingTaskUploadDao]、不排 Worker、不触发网络），
     * 返回 [SKIPPED_PENDING_ID]。调用方此前对业务 Room 表的本地编辑保持不变（Requirement 14.1 / 14.5）。
     *
     * @param accountId 服务端账号 id（必须属于 Active_Pool；否则 Worker 上传时会收到 404 触发刷新）。
     * @param taskId 服务端自动任务 id；新建任务时由调用方按业务规则提前分配（与 design.md 一致）。
     * @param request 服务端要求的字段集合，含本地持有的 `revision`。
     * @param enqueueWorker 默认 true；测试可以传 false 跳过 WorkManager。
     * @return 写入数据库后生成的队列条目 id；Local_Only_Mode 下返回 [SKIPPED_PENDING_ID]。
     */
    suspend fun enqueueUpsert(
        accountId: Long,
        taskId: Long,
        request: AutomationTaskUpsertRequest,
        enqueueWorker: Boolean = true,
    ): Long {
        if (!serverSyncConfig.isUploadEnabled()) {
            Log.i(
                TAG,
                "automation task upsert skipped (local-only mode): " +
                    "accountId=$accountId, taskId=$taskId, " +
                    "isConfigured=${serverSyncConfig.isConfigured()}, " +
                    "uploadEnabled=${serverSyncConfig.uploadEnabled}",
            )
            return SKIPPED_PENDING_ID
        }
        val payloadJson = json.encodeToString(AutomationTaskUpsertRequest.serializer(), request)
        val id = pendingDao.insert(
            PendingTaskUploadEntity(
                kind = PendingTaskUploadEntity.KIND_UPSERT,
                accountId = accountId,
                taskId = taskId,
                payloadJson = payloadJson,
                revision = request.revision,
                createdAtEpochSeconds = nowEpochSeconds(),
            ),
        )
        if (enqueueWorker) {
            AutomationTaskUploadWorker.enqueue(context, serverSyncConfig)
        }
        return id
    }

    /**
     * 提交一次自动任务 DELETE。`revision` 为本地最近一次成功上传后服务端给的版本号；
     * 服务端会用它做乐观并发校验，落后的删除请求会被 409 拒绝。
     *
     * 任务 12.10：与 [enqueueUpsert] 一致的双开关守卫；Local_Only_Mode 下返回 [SKIPPED_PENDING_ID]。
     */
    suspend fun enqueueDelete(
        accountId: Long,
        taskId: Long,
        revision: Long,
        enqueueWorker: Boolean = true,
    ): Long {
        if (!serverSyncConfig.isUploadEnabled()) {
            Log.i(
                TAG,
                "automation task delete skipped (local-only mode): " +
                    "accountId=$accountId, taskId=$taskId, " +
                    "isConfigured=${serverSyncConfig.isConfigured()}, " +
                    "uploadEnabled=${serverSyncConfig.uploadEnabled}",
            )
            return SKIPPED_PENDING_ID
        }
        val id = pendingDao.insert(
            PendingTaskUploadEntity(
                kind = PendingTaskUploadEntity.KIND_DELETE,
                accountId = accountId,
                taskId = taskId,
                payloadJson = null,
                revision = revision,
                createdAtEpochSeconds = nowEpochSeconds(),
            ),
        )
        if (enqueueWorker) {
            AutomationTaskUploadWorker.enqueue(context, serverSyncConfig)
        }
        return id
    }

    /**
     * 提交一次拉黑事件上报。Worker 把它发到
     * `POST /api/v1/active-accounts/{accountId}/blacklist-events`；账号已迁出 Active_Pool 时
     * 服务端返回 404，Worker 会触发本地清单刷新。
     *
     * 注意：本方法 **不** 在内部做 [ServerSyncConfig.isUploadEnabled] 守卫；双开关由 [BlacklistReporter]
     * 在调用本方法前统一检查（任务 12.9）。生产代码应通过 [BlacklistReporter] 上报，避免绕过守卫。
     */
    suspend fun enqueueBlacklistEvent(
        accountId: Long,
        request: BlacklistEventRequest,
        enqueueWorker: Boolean = true,
    ): Long {
        val payloadJson = json.encodeToString(BlacklistEventRequest.serializer(), request)
        val id = pendingDao.insert(
            PendingTaskUploadEntity(
                kind = PendingTaskUploadEntity.KIND_BLACKLIST,
                accountId = accountId,
                taskId = null,
                payloadJson = payloadJson,
                revision = null,
                createdAtEpochSeconds = nowEpochSeconds(),
            ),
        )
        if (enqueueWorker) {
            AutomationTaskUploadWorker.enqueue(context, serverSyncConfig)
        }
        return id
    }

    companion object {
        private const val TAG: String = "AutomationTaskUploader"

        /**
         * Local_Only_Mode 下 [enqueueUpsert] / [enqueueDelete] 的返回值：表示本次入队被双开关守卫
         * 拦截、未写入 [PendingTaskUploadDao]、未排 [AutomationTaskUploadWorker]。
         *
         * 取 -1L：与 Room 自增主键的合法范围（>=1）不重叠，调用方可用
         * `pendingId == AutomationTaskUploader.SKIPPED_PENDING_ID` 判断「未入队」。
         */
        const val SKIPPED_PENDING_ID: Long = -1L
    }
}
