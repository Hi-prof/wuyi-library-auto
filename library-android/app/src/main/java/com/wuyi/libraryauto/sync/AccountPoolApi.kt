@file:OptIn(ExperimentalSerializationApi::class)

package com.wuyi.libraryauto.sync

import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `account-pool-tri-sync` 客户端调用服务端 Active_Account_Sync_API / Automation_Task_Sync_API
 * 的 Retrofit 接口。
 *
 * 鉴权与 HTTPS 由 [BearerTokenInterceptor] 与 [HttpsEnforcementInterceptor] 注入，
 * 接口本体不再单独声明 `@Header("Authorization")`：让所有调用点都强制走拦截器，
 * 避免「忘记带 token」与「手写 `Bearer ` 拼接」两类错误。
 *
 * 服务端契约见 design.md「Active_Account_Sync_API」「Automation_Task_Sync_API」章节，
 * 路径、方法、状态码与本接口一一对应。
 */
interface AccountPoolApi {
    /**
     * 接口 A：拉取 Active_Pool 清单。响应不含密码、不含自动任务详情。
     */
    @GET("api/v1/active-accounts")
    suspend fun listActiveAccounts(): ActiveAccountListResponse

    /**
     * 接口 B：拉取单个活跃账号详情，含明文密码与关联自动任务列表。
     *
     * 服务端会按 token + accountId 维度限频（默认 6 次/分钟），命中后返回 `429 rate_limited`。
     * 账号不在 Active_Pool 时返回 `404 account_not_found`，**不** 区分「不存在 / 暂停 / 未启用」。
     */
    @GET("api/v1/active-accounts/{accountId}/detail")
    suspend fun getActiveAccountDetail(
        @Path("accountId") accountId: Long,
    ): ActiveAccountDetailResponse

    /**
     * 客户端拉黑事件上报。账号已迁出 Active_Pool 时返回 `404`，不泄露真实状态。
     */
    @POST("api/v1/active-accounts/{accountId}/blacklist-events")
    suspend fun reportBlacklistEvent(
        @Path("accountId") accountId: Long,
        @Body request: BlacklistEventRequest,
    ): BlacklistEventResponse

    /**
     * 自动任务下行获取（独立端点；首版语义与接口 B 中的 `automation_tasks` 一致）。
     */
    @GET("api/v1/active-accounts/{accountId}/automation-tasks")
    suspend fun listAutomationTasks(
        @Path("accountId") accountId: Long,
    ): AutomationTasksResponse

    /**
     * 自动任务上行 PUT。`revision` 由调用方写入 [AutomationTaskUpsertRequest.revision]，
     * 落后版本会被服务端以 `409 revision_conflict` 拒绝。
     */
    @PUT("api/v1/active-accounts/{accountId}/automation-tasks/{taskId}")
    suspend fun upsertAutomationTask(
        @Path("accountId") accountId: Long,
        @Path("taskId") taskId: Long,
        @Body request: AutomationTaskUpsertRequest,
    ): AutomationTaskUpsertResponse

    /**
     * 自动任务上行删除：服务端按软删处理，仍会写一次 `revision` 自增。
     */
    @DELETE("api/v1/active-accounts/{accountId}/automation-tasks/{taskId}")
    suspend fun deleteAutomationTask(
        @Path("accountId") accountId: Long,
        @Path("taskId") taskId: Long,
        @Query("revision") revision: Long,
    ): AutomationTaskDeleteResponse
}

/**
 * 构造 [AccountPoolApi] 的统一工厂。
 *
 * 对调用方的承诺：
 * - 注入 [HttpsEnforcementInterceptor]：所有出向请求若非环回地址且非 HTTPS，会以
 *   [HttpsRequiredException] 失败，避免明文链路在客户端侧静默透传。
 * - 注入 [BearerTokenInterceptor]：每次请求都从 [tokenProvider] 重新读取 token，便于
 *   运行期换发 token 后立刻生效。
 * - kotlinx.serialization JSON 解码器对未知字段宽容（`ignoreUnknownKeys = true`），
 *   避免服务端先行加字段时客户端整体崩溃；编码侧不写 `null` 字段，与服务端一致。
 *
 * baseUrl 由调用方提供，必须以 `/` 结尾；若调用方传入未含尾部 `/` 的 URL，Retrofit 会抛
 * `IllegalArgumentException`，本工厂不再做兼容处理（与 Retrofit 的强约束一致）。
 *
 * @param baseUrl 服务端基础地址，例如 `https://example.com/`。
 * @param tokenProvider Bearer Token 提供器；返回 `null` 或空串时不写 `Authorization` 头。
 * @param httpClient 可选 [OkHttpClient]，便于测试注入 `MockWebServer` 用例；缺省使用内置默认。
 */
object AccountPoolApiFactory {
    /**
     * 内置默认超时，单位秒。与 [com.wuyi.libraryauto.core.network.http.SharedHttpClientCore]
     * 保持一致的 15s 节奏，避免出现「主链路 15s、同步链路 30s」的混乱节奏。
     */
    const val DEFAULT_TIMEOUT_SECONDS: Long = 15L

    /**
     * 默认 JSON 解码器。`encodeDefaults = false` 让 `customWindows = emptyList()` 这类默认
     * 值不写出，与服务端 Pydantic 的默认值省略行为一致；`ignoreUnknownKeys = true` 避免
     * 服务端先行加字段时客户端反序列化失败。
     */
    val DefaultJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun create(
        baseUrl: String,
        tokenProvider: () -> String?,
        httpClient: OkHttpClient? = null,
        json: Json = DefaultJson,
    ): AccountPoolApi {
        val client = httpClient ?: defaultClient(tokenProvider)
        val contentType = "application/json".toMediaType()
        val retrofit =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
        return retrofit.create(AccountPoolApi::class.java)
    }

    /**
     * 构造默认 [OkHttpClient]：先 HTTPS 强制，再 Bearer Token 注入；顺序与拦截器副作用绑定。
     *
     * - HTTPS 强制必须排在最前面：一旦命中明文非环回，拦截器直接抛 [HttpsRequiredException]，
     *   后续 Bearer Token 拦截器不会被触发，token 不会出现在被拒绝的请求里。
     * - 若调用方需要复用进程级 OkHttp（比如复用连接池或自定义 cookie jar），应自行调用
     *   `existing.newBuilder().addInterceptor(...).build()` 后通过 [create] 的 `httpClient`
     *   入参传入。
     */
    private fun defaultClient(tokenProvider: () -> String?): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(HttpsEnforcementInterceptor())
            .addInterceptor(BearerTokenInterceptor(tokenProvider))
            .build()
}
