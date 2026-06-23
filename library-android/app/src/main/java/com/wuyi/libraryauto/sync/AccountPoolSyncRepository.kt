package com.wuyi.libraryauto.sync

import com.wuyi.libraryauto.core.storage.db.ActiveAccountDao
import com.wuyi.libraryauto.core.storage.db.ActiveAccountEntity
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

/**
 * 任务 12.2：Active_Pool 同步仓库。
 *
 * 责任：
 * - 调用 [AccountPoolApi] 接口 A 拉取 Active_Pool 清单，并把结果按设计契约整批写入
 *   Room 表 `active_accounts`（实体见 [ActiveAccountEntity]、DAO 见 [ActiveAccountDao]）。
 * - 调用接口 B 取单账号详情（含明文密码与自动任务列表），仅短时返回给调用方供单次任务
 *   执行使用，**不**持久化密码字段。
 * - 通过 [observeCachedActiveAccounts] / [getCachedActiveAccount] 暴露本地缓存供 UI 与
 *   后台调度使用。
 *
 * 错误处理使用 [AccountPoolSyncResult] 密封类型：
 * - HTTP 网络/IO 错误归类到 [AccountPoolSyncResult.Error.Network]。
 * - 服务端返回 4xx/5xx 时，按照 design.md「Error Handling」表把状态码映射到具体错误类型，
 *   便于 ViewModel / Worker 判断是否应当回退到「服务端不可达」状态、是否应当向用户提示
 *   鉴权 / 限频问题。
 * - HTTPS 拦截器在客户端侧拒绝明文非环回请求时抛出 [HttpsRequiredException]，归类到
 *   [AccountPoolSyncResult.Error.HttpsRequired]，与服务端 426 响应保持一致语义。
 *
 * 仓库本身只承担「网络 → DTO → 实体 → DAO」的串接与错误归类，并不做后台调度。
 *
 * 任务 12.8 撤回了周期 Worker 拉取清单的改造：客户端不再启动期注册 PeriodicWorkRequest，
 * 也不在用户首次启动时静默拉取一次。本仓库的 [refreshActiveList] 仅在用户主动点击
 * Manual_Sync_Action 时由 ViewModel（[AccountPoolSyncViewModel]）调用；冲突解决（接口 B 的
 * password 内存缓存策略、自动任务上传）由 [AutomationTaskUploadWorker] 等模块在此仓库基础上扩展。
 */
class AccountPoolSyncRepository(
    private val apiProvider: () -> AccountPoolApi,
    private val activeAccountDao: ActiveAccountDao,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    @Volatile
    private var cachedApi: AccountPoolApi? = null

    constructor(
        api: AccountPoolApi,
        activeAccountDao: ActiveAccountDao,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    ) : this(
        apiProvider = { api },
        activeAccountDao = activeAccountDao,
        nowEpochSeconds = nowEpochSeconds,
    )

    @Synchronized
    fun invalidateApiCache() {
        cachedApi = null
    }

    /**
     * 拉取接口 A 并把结果整批替换本地缓存。
     *
     * 成功路径：
     * 1. 调用接口 A，得到 [ActiveAccountListResponse]。
     * 2. 把每条 [ActiveAccountListItem] 映射成 [ActiveAccountEntity]，统一打入当前
     *    [nowEpochSeconds] 作为 `syncedAtEpochSeconds`，便于诊断「最后一次成功同步时间」。
     * 3. 调用 [ActiveAccountDao.replaceAll]：在事务内清空旧缓存并写入新清单，避免观察者
     *    捕获到中间空状态。
     * 4. 返回 [AccountPoolSyncResult.Success]，携带最新实体列表与服务端 `serverTime`。
     */
    suspend fun refreshActiveList(): AccountPoolSyncResult<List<ActiveAccountEntity>> {
        val api = currentApi()
        return runCatching { api.listActiveAccounts() }
            .fold(
                onSuccess = { response ->
                    val syncedAt = nowEpochSeconds()
                    val entities = response.accounts.map { it.toEntity(syncedAt) }
                    activeAccountDao.replaceAll(entities)
                    AccountPoolSyncResult.Success(value = entities, serverTime = response.serverTime)
                },
                onFailure = { throwable ->
                    AccountPoolSyncResult.Error.fromThrowable(throwable)
                },
            )
    }

    /**
     * 取单账号详情（接口 B）。
     *
     * 关键约束：返回的 [ActiveAccountDetail.password] 仅以内存对象的形式回传给调用方，
     * **不**写入 [ActiveAccountDao] 或任何其他持久化层。调用方应在单次自动任务执行结束后
     * 立刻丢弃 [ActiveAccountSyncDetail] 对象，避免明文密码常驻内存。
     */
    suspend fun getActiveAccountDetail(accountId: Long): AccountPoolSyncResult<ActiveAccountSyncDetail> {
        val api = currentApi()
        return runCatching { api.getActiveAccountDetail(accountId) }
            .fold(
                onSuccess = { response ->
                    AccountPoolSyncResult.Success(
                        value =
                            ActiveAccountSyncDetail(
                                account = response.account,
                                automationTasks = response.automationTasks,
                            ),
                        serverTime = response.serverTime,
                    )
                },
                onFailure = { throwable ->
                    AccountPoolSyncResult.Error.fromThrowable(throwable)
                },
            )
    }

    @Synchronized
    private fun currentApi(): AccountPoolApi {
        val existing = cachedApi
        if (existing != null) {
            return existing
        }
        return apiProvider().also { cachedApi = it }
    }

    /** 查询本地缓存的 Active_Pool 清单（不触发网络）。 */
    suspend fun loadCachedActiveAccounts(): List<ActiveAccountEntity> = activeAccountDao.findAll()

    /** 观察本地缓存：用于 UI 在 Worker 同步成功后立刻刷新视图。 */
    fun observeCachedActiveAccounts(): Flow<List<ActiveAccountEntity>> = activeAccountDao.observeAll()

    /** 观察单账号缓存映射，便于详情页在 Worker 触发后实时更新非敏感字段。 */
    fun observeCachedActiveAccountIds(): Flow<List<Long>> =
        activeAccountDao.observeAll().map { list -> list.map(ActiveAccountEntity::accountId) }

    /** 按 ID 查询本地缓存的单账号摘要（不含密码）。 */
    suspend fun getCachedActiveAccount(accountId: Long): ActiveAccountEntity? =
        activeAccountDao.findById(accountId)
}

/**
 * 接口 B 详情的内存载体。**禁止**持久化或日志输出整个对象（[ActiveAccountDetail.password]
 * 是明文密码）；调用方应只在单次任务执行作用域内引用，执行结束立刻释放。
 */
data class ActiveAccountSyncDetail(
    val account: ActiveAccountDetail,
    val automationTasks: List<AutomationTaskDto>,
)

/**
 * 同步操作的结果包装。`Success` 与 `Error` 两类均不抛异常，便于 Worker 在 `Result.retry()` /
 * `Result.success()` 之间做明确判断。
 */
sealed class AccountPoolSyncResult<out T> {
    data class Success<T>(val value: T, val serverTime: String) : AccountPoolSyncResult<T>()

    sealed class Error : AccountPoolSyncResult<Nothing>() {
        abstract val cause: Throwable

        /** 调用方应回退到「服务端不可达」状态：连不上、读不到、TLS 失败等。 */
        data class Network(override val cause: Throwable) : Error()

        /** 客户端侧 HTTPS 拦截器拒绝了明文非环回请求；服务端配置仍处于 HTTP 时也会落到这里。 */
        data class HttpsRequired(override val cause: Throwable) : Error()

        /** 服务端 401：Bearer Token 缺失、过期或被撤销。 */
        data class Unauthorized(override val cause: Throwable, val statusCode: Int = 401) : Error()

        /** 服务端 429：触发限频（design.md 中接口 B 默认 6 次/分钟）。 */
        data class RateLimited(override val cause: Throwable, val statusCode: Int = 429) : Error()

        /** 服务端 404：账号不在 Active_Pool（迁出 / 被暂停 / 被回收 / 不存在均归此一类）。 */
        data class AccountNotInActivePool(
            override val cause: Throwable,
            val statusCode: Int = 404,
        ) : Error()

        /** 其余 4xx/5xx；保留 statusCode 便于上层做诊断打点。 */
        data class Server(override val cause: Throwable, val statusCode: Int) : Error()

        /** 兜底：解析异常等无法归类的本地错误。 */
        data class Unexpected(override val cause: Throwable) : Error()

        companion object {
            internal fun fromThrowable(throwable: Throwable): Error =
                when (throwable) {
                    is HttpsRequiredException -> HttpsRequired(throwable)
                    is HttpException ->
                        when (val code = throwable.code()) {
                            401 -> Unauthorized(throwable, code)
                            404 -> AccountNotInActivePool(throwable, code)
                            429 -> RateLimited(throwable, code)
                            // 426 是服务端 HTTPS 强制响应；与本地 [HttpsRequiredException]
                            // 同语义合并，便于上层统一处理。
                            426 -> HttpsRequired(throwable)
                            else -> Server(throwable, code)
                        }
                    is IOException -> Network(throwable)
                    else -> Unexpected(throwable)
                }
        }
    }
}

internal fun ActiveAccountListItem.toEntity(syncedAtEpochSeconds: Long): ActiveAccountEntity =
    ActiveAccountEntity(
        accountId = accountId,
        studentId = studentId,
        displayName = displayName,
        poolStatus = poolStatus,
        updatedAt = updatedAt,
        syncedAtEpochSeconds = syncedAtEpochSeconds,
    )
