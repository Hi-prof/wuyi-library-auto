package com.wuyi.libraryauto.sync

import android.util.Log
import java.time.Instant

/**
 * `account-pool-tri-sync` 任务 12.9：客户端拉黑事件上报的独立入口。
 *
 * 设计依据：
 * - design.md「Components and Interfaces · Android_Client」要求把拉黑事件上报从
 *   [AutomationTaskUploadWorker] / [AutomationTaskUploader] 中拆出，独立承担
 *   `POST /api/v1/active-accounts/{id}/blacklist-events` 的调用入口。
 * - Requirement 15.1 / 15.2 / 15.3：
 *   - 仅在 `Server_Sync_Config` 已配置 **且** `upload_enabled=true` 双开关全开启时才发起上报；
 *   - 任一开关关闭时进入 Local_Only_Mode，不发任何拉黑事件上报请求，仅在本地 Room 标记
 *     账号状态（标记动作由调用方 ViewModel / Repository 在调用本组件之前完成）；
 *   - 上报失败仅 UI / 日志提示，不阻塞本地执行，不删除 Room 中已经写入的拉黑标记。
 *
 * 与 [AutomationTaskUploader.enqueueBlacklistEvent] 的关系：
 * - 本组件是「ViewModel / Repository → 拉黑事件上报」的唯一对外入口；调用方不应再直接调用
 *   [AutomationTaskUploader.enqueueBlacklistEvent]，避免绕开双开关守卫。
 * - 实际的网络投递与 404 / 5xx / IO 重试语义保持原来的「写入 PendingTaskUpload 队列 + 由
 *   [AutomationTaskUploadWorker] 在后台 FIFO 拉取」流程；本组件只负责守卫与入队委托。
 * - 入队动作以函数引用 [enqueue] 注入，便于在单元测试中替换为可观察 / 抛错的 fake，
 *   生产代码使用 `BlacklistReporter.fromUploader(...)` 工厂直接绑定到真实 uploader。
 *
 * 失败语义（重要）：
 * - 双开关任一关闭 → 返回 [Outcome.SkippedLocalOnly]；调用方继续按本地 Room 标记执行业务，
 *   后续无论用户是否再打开开关都不会补发本次事件（与设计文档「不引入持久化重试队列在
 *   Local_Only_Mode 期间堆积」一致）。
 * - 入队过程抛异常（DB 不可用 / 磁盘满 / kotlinx 序列化失败等）→ 返回 [Outcome.EnqueueFailed]；
 *   日志记录后由调用方继续走本地流程；**绝不**删除调用方在 Room 中已经写入的拉黑标记。
 * - 入队成功但服务端最终 404 / 4xx / 5xx → 由 [AutomationTaskUploadWorker] 负责状态码映射与
 *   重试 / 丢弃；本组件不参与该阶段的状态变更。
 *
 * _Requirements: 15.1, 15.2, 15.3_
 */
class BlacklistReporter(
    private val serverSyncConfig: ServerSyncConfig,
    private val enqueue: BlacklistEventEnqueuer,
    private val clientKind: String = ClientKindLiteral.ANDROID,
    private val nowIsoUtc: () -> String = { Instant.now().toString() },
    private val logger: Logger = Logger.AndroidLog,
) {
    /**
     * 上报一次拉黑事件。本方法不修改任何本地 Room 表 —— 调用方应在调用本方法前 / 后按
     * 业务流程独立写入本地拉黑标记，本组件只承担「是否上报」「如何上报」两件事。
     *
     * @param accountId 服务端账号 id；客户端在执行任务时观察到该账号被图书馆系统拉黑。
     * @param evidence 自由文本证据（例如「人机验证失败 5 次」）；服务端写入 audit log 时会
     *                 经过脱敏过滤，但客户端侧仍 SHALL 不传入密码 / token / cookie 等敏感字段。
     * @param clientObservedAt 客户端本地观测到拉黑的 UTC ISO8601 时间戳；缺省取 [nowIsoUtc]。
     * @return 本次上报的归宿（[Outcome.SkippedLocalOnly] / [Outcome.Enqueued] / [Outcome.EnqueueFailed]）。
     */
    suspend fun report(
        accountId: Long,
        evidence: String,
        clientObservedAt: String = nowIsoUtc(),
    ): Outcome {
        // 双开关守卫：未配置 Server_Sync_Config 或 upload_enabled=false 时直接返回，
        // 不构造 BlacklistEventRequest、不写 PendingTaskUpload 队列、不触发 Worker。
        if (!serverSyncConfig.isUploadEnabled()) {
            logger.info(
                TAG,
                "blacklist event upload skipped (local-only mode): accountId=$accountId",
            )
            return Outcome.SkippedLocalOnly
        }

        val request = BlacklistEventRequest(
            evidence = evidence,
            clientKind = clientKind,
            clientObservedAt = clientObservedAt,
        )
        return try {
            val pendingId = enqueue.enqueueBlacklistEvent(accountId, request)
            logger.info(
                TAG,
                "blacklist event enqueued: accountId=$accountId, pendingId=$pendingId",
            )
            Outcome.Enqueued(pendingId = pendingId)
        } catch (throwable: Throwable) {
            // 入队层面失败（DB 异常等）：仅日志提示；调用方继续按本地标记执行业务，
            // 不阻塞、不回滚、不删除本地拉黑标记（Requirement 15.3）。
            val reason = throwable.message ?: throwable::class.simpleName.orEmpty()
            logger.warn(
                TAG,
                "blacklist event enqueue failed: accountId=$accountId, reason=$reason",
                throwable,
            )
            Outcome.EnqueueFailed(reason = reason.ifBlank { "unknown" })
        }
    }

    /**
     * 上报结果。三种情况对应三种 UI 反馈策略：
     * - [SkippedLocalOnly]：UI 可静默或仅显示「未启用上行同步」副提示；不应作为错误展示。
     * - [Enqueued]：UI 可显示「已入队，将在网络恢复时上报」类副提示；不阻塞本地执行。
     * - [EnqueueFailed]：UI 可显示错误 toast（含 [reason]）；不阻塞本地执行、不撤销本地标记。
     */
    sealed class Outcome {
        /** 双开关任一关闭，本次未发起任何网络请求。 */
        data object SkippedLocalOnly : Outcome()

        /** 已写入 PendingTaskUpload 队列；后续由 [AutomationTaskUploadWorker] 投递。 */
        data class Enqueued(val pendingId: Long) : Outcome()

        /** 入队过程中本地异常（DB / 序列化等），未触达网络。 */
        data class EnqueueFailed(val reason: String) : Outcome()
    }

    /**
     * 入队委托接口。生产代码用 [fromUploader] 绑定到 [AutomationTaskUploader.enqueueBlacklistEvent]；
     * 测试可注入抛错或可观察的 fake。
     */
    fun interface BlacklistEventEnqueuer {
        suspend fun enqueueBlacklistEvent(
            accountId: Long,
            request: BlacklistEventRequest,
        ): Long
    }

    /**
     * 极简日志接口，便于单元测试断言「双开关任一关闭时未触达任何 Android Log 副作用」。
     * 生产代码使用 [AndroidLog] 实现，写入 logcat。
     */
    fun interface Logger {
        fun log(level: Int, tag: String, message: String, throwable: Throwable?)

        fun info(tag: String, message: String) = log(Log.INFO, tag, message, null)

        fun warn(tag: String, message: String, throwable: Throwable? = null) =
            log(Log.WARN, tag, message, throwable)

        companion object {
            /** 默认实现：直接写入 Android Log。 */
            val AndroidLog: Logger = Logger { level, tag, message, throwable ->
                when (level) {
                    Log.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
                    Log.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
                    else -> Log.i(tag, message)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BlacklistReporter"

        /**
         * 生产代码工厂：把 [AutomationTaskUploader.enqueueBlacklistEvent] 绑定到
         * [BlacklistEventEnqueuer]。Worker 入队默认开启（与原 [AutomationTaskUploader] 调用约定一致）。
         */
        fun fromUploader(
            serverSyncConfig: ServerSyncConfig,
            uploader: AutomationTaskUploader,
            clientKind: String = ClientKindLiteral.ANDROID,
            nowIsoUtc: () -> String = { Instant.now().toString() },
            logger: Logger = Logger.AndroidLog,
        ): BlacklistReporter = BlacklistReporter(
            serverSyncConfig = serverSyncConfig,
            enqueue = BlacklistEventEnqueuer { accountId, request ->
                uploader.enqueueBlacklistEvent(accountId = accountId, request = request)
            },
            clientKind = clientKind,
            nowIsoUtc = nowIsoUtc,
            logger = logger,
        )
    }
}
