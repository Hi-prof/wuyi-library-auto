package com.wuyi.libraryauto.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadDao
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadEntity
import com.wuyi.libraryauto.core.storage.db.TaskUploadConflictDao
import com.wuyi.libraryauto.core.storage.db.TaskUploadConflictEntity
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import retrofit2.HttpException

/**
 * `account-pool-tri-sync` 任务 12.3：自动任务上行 + 拉黑事件上报的后台 Worker。
 *
 * 责任：
 * - 用户在 UI 编辑自动任务 / 触发拉黑上报时，先把动作写入 [PendingTaskUploadDao]（本地立刻可见），
 *   再由 [AutomationTaskUploader] 入队本 Worker；本 Worker 在后台按 FIFO 顺序逐条调用 [AccountPoolApi]
 *   把变更上传到服务端。
 * - 服务端确认后用响应中的 `revision` 覆盖本地：当前任务范围内只把队列条目移除，真正写入业务表的
 *   `revision` 由调用方（自动任务编辑入口 / ViewModel）通过 [UploadOutcome.Success]
 *   回调完成。这样本 Worker 不需要直接耦合自动任务的业务表 schema。
 * - 状态码契约（与 design.md「Error Handling」一致）：
 *   - `200/201` → [UploadOutcome.Success]，删除该队列条目；继续处理后续条目。
 *   - `409 revision_conflict` → 解析 body 中的 `server_revision` / `server_payload`，写入
 *     [TaskUploadConflictDao]；删除该队列条目（避免反复用旧 revision 砸服务端）；继续处理后续条目；
 *     最终返回 [Result.success]，把决定权交给冲突解决 UI。
 *   - `404 account_not_found` → 触发 [AccountPoolSyncRepository.refreshActiveList] 刷新本地清单；
 *     **保留**该队列条目（不知道是「账号被迁出」还是「客户端缓存陈旧」，由用户在刷新后的 UI 决定如何处理）；
 *     返回 [Result.failure] 让 WorkManager 不再重试本轮（避免 retryCount 飚升）。
 *   - `422 validation_error` → 字段校验失败，重试也不会通过；删除该队列条目（[lastErrorReason] 写入
 *     原因），返回 [Result.failure]，等用户修正后重新编辑。
 *   - `401 unauthorized` → token 失效，重试无意义；返回 [Result.failure]，等待新 token 触发 [enqueue]。
 *   - `426 https_required` / `429 rate_limited` / `5xx` / 网络 IO 异常 → [UploadOutcome.Retry]，
 *     原条目保留，更新 `retryCount` 与 `lastErrorReason`，返回 [Result.retry] 走 WorkManager 退避。
 * - 拉黑事件上报与自动任务上行共享同一队列（[PendingTaskUploadEntity.KIND_BLACKLIST]），按相同
 *   状态码语义处理；仅 404 在「拉黑」语境下也表示账号不在 Active_Pool，需要触发清单刷新。
 *
 * 与 [AutomationTaskUploadWorker] 处理多条 PendingTaskUpload 的事件触发一次性 Work（[OneTimeWorkRequest]）
 * 不同，本特性不再启用 Active_Pool 周期 Worker（任务 12.8 撤回 PeriodicWorkRequest），
 * Active_Pool 同步改为用户主动点击 Manual_Sync_Action 时由 [AccountPoolSyncViewModel] 触发。
 */
class AutomationTaskUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val provider =
            Provider.factory?.invoke(applicationContext)
                ?: error(
                    "AutomationTaskUploadWorker provider is not installed. " +
                        "Call AutomationTaskUploadWorker.Provider.install(...) before enqueue().",
                )
        // 同步结果仅向 SyncStatusIndicator 上报，不阻塞本地执行入口。
        return runOnce(
            dependencies = provider,
            indicator = SyncStatusIndicator.default(),
        )
    }

    /**
     * 注入到 Worker 的依赖工厂。生产环境一次性 install；测试可在每个用例里覆盖一次。
     */
    object Provider {
        @Volatile
        internal var factory: ((Context) -> Dependencies)? = null
            private set

        fun install(factory: (Context) -> Dependencies) {
            this.factory = factory
        }

        fun reset() {
            this.factory = null
        }
    }

    /**
     * Worker 运行所需的全部依赖。所有字段都是「应用级单例」，没有需要 per-call 创建的状态。
     */
    data class Dependencies(
        val api: AccountPoolApi,
        val pendingDao: PendingTaskUploadDao,
        val conflictDao: TaskUploadConflictDao,
        val activePoolRepository: AccountPoolSyncRepository,
        val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
        val json: Json = AccountPoolApiFactory.DefaultJson,
    )

    companion object {
        const val UNIQUE_NAME: String = "automation-task-upload"

        /** 退避起点 30 秒；线性策略，避免指数退避飚到几小时。 */
        private const val BACKOFF_DELAY_SECONDS: Long = 30L

        /** 最多扫描的队列条目数；保护 doWork 在异常巨长队列下仍能 timeboxed 退出。 */
        internal const val MAX_BATCH_SIZE: Int = 64

        private const val TAG: String = "AutomationTaskUploadWorker"

        /**
         * 入队一次后台上传。
         *
         * 任务 12.10：把入队条件从「服务端可达」改为「`Server_Sync_Config` 已配置 **且**
         * `upload_enabled=true`」双开关。任一关闭即静默跳过：
         * - 不构造 [OneTimeWorkRequest]、不调用 [WorkManager.enqueueUniqueWork]、不触发任何
         *   网络请求或 WorkManager 调度副作用；
         * - 不修改 [PendingTaskUploadDao] 队列里已经写入的本地编辑（Requirement 14.5），
         *   等待用户后续打开开关再由调用方重新入队（首版不引入持久化重试队列，14-Q1 默认）；
         * - 返回 `false` 让调用方的 UI 可以做相应反馈（例如「未启用上行同步」副提示）。
         *
         * `APPEND_OR_REPLACE` 让多次调用排队执行而非互相挤掉，保证「逐条上传」语义。
         *
         * @param context 任意 Context；内部使用 `applicationContext`。
         * @param serverSyncConfig 注入用于双开关守卫；缺省时使用 [ServerSyncConfig] 进程级实例。
         * @return `true` 表示已实际入队 WorkManager；`false` 表示守卫拦截、未入队。
         */
        fun enqueue(
            context: Context,
            serverSyncConfig: ServerSyncConfig =
                ServerSyncConfig(context.applicationContext),
        ): Boolean {
            // 双开关守卫：未配置 Server_Sync_Config 或 upload_enabled=false 时直接静默跳过，
            // 不调用 WorkManager.enqueueUniqueWork，避免触发任何调度副作用。
            if (!serverSyncConfig.isUploadEnabled()) {
                Log.i(
                    TAG,
                    "automation task upload skipped (local-only mode): " +
                        "isConfigured=${serverSyncConfig.isConfigured()}, " +
                        "uploadEnabled=${serverSyncConfig.uploadEnabled}",
                )
                return false
            }
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildOneTimeRequest(),
            )
            return true
        }

        internal fun buildOneTimeRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AutomationTaskUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()

        /**
         * Worker 主体。抽出来便于在不构造 [WorkerParameters] 的情况下做单元测试。
         *
         * 行为：按 FIFO 取出 [PendingTaskUploadDao.peekNext]，根据 [UploadOutcome] 决定继续 / 退出 /
         * 整体重试 / 整体失败。任意一条触发 [UploadOutcome.Retry] 就立刻退出本轮 doWork（避免在
         * 网络持续失败时空跑），由 WorkManager 的退避机制安排下一轮。
         */
        internal suspend fun runOnce(
            dependencies: Dependencies,
            indicator: SyncStatusIndicator? = null,
        ): Result {
            var processed = 0
            // 与可达性的关系：进入 doWork 之前 Worker 已经满足 NetworkType.CONNECTED 约束，
            // 但「网络通了」≠「服务端可达」。后续按 [UploadOutcome] 分类时显式更新 indicator：
            // - Success / ConflictResolved → reportSuccess（服务端 200/409 都说明能到）；
            // - 401 / 422 / 序列化错误 → 不动（与服务端是否可达无关）；
            // - 404 → 触发刷新；本次用 NOT_IN_ACTIVE_POOL 做诊断；
            // - Retry（IO/HTTPS/限频/5xx）→ reportFailure，让 UI 立即置灰同步按钮。
            while (processed < MAX_BATCH_SIZE) {
                val pending = dependencies.pendingDao.peekNext() ?: return Result.success()
                val outcome = handleSingle(dependencies, pending)
                when (outcome) {
                    is UploadOutcome.Success -> {
                        dependencies.pendingDao.deleteById(pending.id)
                        indicator?.reportSuccess()
                    }
                    is UploadOutcome.ConflictResolved -> {
                        // 冲突写入已在 handleSingle 内完成，这里只清队列。
                        dependencies.pendingDao.deleteById(pending.id)
                        indicator?.reportSuccess()
                    }
                    is UploadOutcome.Discard -> {
                        // 422 / 401 等不可重试错误：丢弃队列条目并整体返回 failure 让用户介入。
                        dependencies.pendingDao.deleteById(pending.id)
                        if (outcome.reason == "unauthorized") {
                            indicator?.reportFailure(reason = SyncStatusIndicator.REASON_UNAUTHORIZED)
                        }
                        return Result.failure()
                    }
                    is UploadOutcome.Retry -> {
                        dependencies.pendingDao.updateRetryState(
                            id = pending.id,
                            retryCount = pending.retryCount + 1,
                            reason = outcome.reason,
                        )
                        indicator?.reportFailure(reason = mapRetryReasonToIndicator(outcome.reason))
                        return Result.retry()
                    }
                    is UploadOutcome.RefreshAndFail -> {
                        // 服务端说账号不在 Active_Pool：先刷新本地清单，让 UI 立刻看到迁出，
                        // 再返回 failure。**不**删除队列条目：用户后续重试 / 取消由冲突解决 UI 决定。
                        dependencies.pendingDao.updateRetryState(
                            id = pending.id,
                            retryCount = pending.retryCount + 1,
                            reason = outcome.reason,
                        )
                        // 服务端响应了 404，说明实际可达；只把本账号标为「不在 Active_Pool」由 UI 处理。
                        runCatching { dependencies.activePoolRepository.refreshActiveList() }
                            .onSuccess { result ->
                                indicator?.reportSyncResult(result)
                            }
                        return Result.failure()
                    }
                }
                processed += 1
            }
            // 还有队列没扫完，但本轮已达批量上限；让 WorkManager 立刻再排一次（不走退避）。
            return Result.success()
        }

        /** 把 [UploadOutcome.Retry.reason] 翻译成 indicator 用的语义化原因常量。 */
        private fun mapRetryReasonToIndicator(reason: String): String =
            when {
                reason.startsWith("io_") -> SyncStatusIndicator.REASON_NETWORK
                reason == "https_required" -> SyncStatusIndicator.REASON_HTTPS_REQUIRED
                reason == "rate_limited" -> SyncStatusIndicator.REASON_RATE_LIMITED
                reason.startsWith("server_") -> SyncStatusIndicator.REASON_SERVER
                else -> SyncStatusIndicator.REASON_UNKNOWN
            }

        private suspend fun handleSingle(
            dependencies: Dependencies,
            pending: PendingTaskUploadEntity,
        ): UploadOutcome {
            return runCatching { dispatch(dependencies, pending) }
                .fold(
                    onSuccess = { it },
                    onFailure = { throwable -> classifyFailure(dependencies, pending, throwable) },
                )
        }

        private suspend fun dispatch(
            dependencies: Dependencies,
            pending: PendingTaskUploadEntity,
        ): UploadOutcome {
            return when (pending.kind) {
                PendingTaskUploadEntity.KIND_UPSERT -> {
                    val taskId = requireNotNull(pending.taskId) {
                        "PendingTaskUploadEntity.kind=upsert requires taskId"
                    }
                    val payloadJson = requireNotNull(pending.payloadJson) {
                        "PendingTaskUploadEntity.kind=upsert requires payloadJson"
                    }
                    val request: AutomationTaskUpsertRequest =
                        dependencies.json.decodeFromString(
                            AutomationTaskUpsertRequest.serializer(),
                            payloadJson,
                        )
                    val response = dependencies.api.upsertAutomationTask(
                        accountId = pending.accountId,
                        taskId = taskId,
                        request = request,
                    )
                    UploadOutcome.Success(serverRevision = response.revision)
                }
                PendingTaskUploadEntity.KIND_DELETE -> {
                    val taskId = requireNotNull(pending.taskId) {
                        "PendingTaskUploadEntity.kind=delete requires taskId"
                    }
                    val revision = requireNotNull(pending.revision) {
                        "PendingTaskUploadEntity.kind=delete requires revision"
                    }
                    val response = dependencies.api.deleteAutomationTask(
                        accountId = pending.accountId,
                        taskId = taskId,
                        revision = revision,
                    )
                    UploadOutcome.Success(serverRevision = response.revision)
                }
                PendingTaskUploadEntity.KIND_BLACKLIST -> {
                    val payloadJson = requireNotNull(pending.payloadJson) {
                        "PendingTaskUploadEntity.kind=blacklist requires payloadJson"
                    }
                    val request: BlacklistEventRequest =
                        dependencies.json.decodeFromString(
                            BlacklistEventRequest.serializer(),
                            payloadJson,
                        )
                    dependencies.api.reportBlacklistEvent(
                        accountId = pending.accountId,
                        request = request,
                    )
                    UploadOutcome.Success(serverRevision = null)
                }
                else -> {
                    // 未知 kind：丢弃即可，不走重试。Provider 与队列写入路径都受控，本分支应仅在
                    // 升级期出现旧版本 enum，最佳行为是「忽略并继续」。
                    UploadOutcome.Discard(reason = "unknown_kind:${pending.kind}")
                }
            }
        }

        private suspend fun classifyFailure(
            dependencies: Dependencies,
            pending: PendingTaskUploadEntity,
            throwable: Throwable,
        ): UploadOutcome {
            return when (throwable) {
                is HttpsRequiredException -> UploadOutcome.Retry(reason = "https_required")
                is HttpException -> when (val code = throwable.code()) {
                    400, 422 -> UploadOutcome.Discard(reason = parseErrorReason(throwable, dependencies.json) ?: "validation_error")
                    401 -> UploadOutcome.Discard(reason = "unauthorized")
                    404 -> UploadOutcome.RefreshAndFail(reason = "account_not_found")
                    409 -> handleConflict(dependencies, pending, throwable)
                    426 -> UploadOutcome.Retry(reason = "https_required")
                    429 -> UploadOutcome.Retry(reason = "rate_limited")
                    in 500..599 -> UploadOutcome.Retry(reason = "server_$code")
                    else -> UploadOutcome.Retry(reason = "http_$code")
                }
                is SerializationException -> UploadOutcome.Discard(reason = "serialization_error")
                is IOException -> UploadOutcome.Retry(reason = "io_error")
                else -> UploadOutcome.Retry(reason = "unexpected:${throwable::class.simpleName}")
            }
        }

        private suspend fun handleConflict(
            dependencies: Dependencies,
            pending: PendingTaskUploadEntity,
            throwable: HttpException,
        ): UploadOutcome {
            val taskId = pending.taskId ?: return UploadOutcome.Discard(reason = "conflict_without_task_id")
            val errorBody = throwable.response()?.errorBody()?.string().orEmpty()
            val parsed = parseConflictBody(dependencies.json, errorBody)
            val conflict = TaskUploadConflictEntity(
                conflictHash = TaskUploadConflictEntity.hashOf(
                    accountId = pending.accountId,
                    taskId = taskId,
                    kind = pending.kind,
                ),
                accountId = pending.accountId,
                taskId = taskId,
                kind = pending.kind,
                localPayloadJson = pending.payloadJson,
                localRevision = pending.revision ?: 0L,
                serverPayloadJson = parsed.serverPayloadJson,
                serverRevision = parsed.serverRevision,
                detectedAtEpochSeconds = dependencies.nowEpochSeconds(),
            )
            dependencies.conflictDao.upsert(conflict)
            return UploadOutcome.ConflictResolved(serverRevision = parsed.serverRevision)
        }

        private fun parseConflictBody(
            json: Json,
            body: String,
        ): ParsedConflict {
            if (body.isBlank()) {
                return ParsedConflict(serverRevision = -1L, serverPayloadJson = null)
            }
            return runCatching {
                val element = json.parseToJsonElement(body).jsonObject
                val serverRevision = element["server_revision"]?.jsonPrimitive?.long ?: -1L
                val serverPayload = element["server_payload"] as? JsonObject
                ParsedConflict(
                    serverRevision = serverRevision,
                    serverPayloadJson = serverPayload?.toString(),
                )
            }.getOrElse {
                ParsedConflict(serverRevision = -1L, serverPayloadJson = body.takeIf { it.length < 4_096 })
            }
        }

        private fun parseErrorReason(
            throwable: HttpException,
            json: Json,
        ): String? {
            val body = throwable.response()?.errorBody()?.string().orEmpty()
            if (body.isBlank()) return null
            return runCatching {
                json.parseToJsonElement(body).jsonObject["reason"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
    }

    private data class ParsedConflict(
        val serverRevision: Long,
        val serverPayloadJson: String?,
    )

    /**
     * 单条上传的处理结果。仅在 [Companion.runOnce] 内部传递，决定是否清队列、是否触发刷新、是否
     * 整体重试或失败。
     */
    internal sealed class UploadOutcome {
        data class Success(val serverRevision: Long?) : UploadOutcome()

        data class ConflictResolved(val serverRevision: Long) : UploadOutcome()

        /** 不可重试的客户端错误：丢弃队列条目，整体返回 failure。 */
        data class Discard(val reason: String) : UploadOutcome()

        /** 可重试的临时错误：保留队列条目，整体返回 retry。 */
        data class Retry(val reason: String) : UploadOutcome()

        /** 404：刷新本地 Active_Pool 清单后整体返回 failure，由用户介入。 */
        data class RefreshAndFail(val reason: String) : UploadOutcome()
    }
}
