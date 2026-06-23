package com.wuyi.libraryauto.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `account-pool-tri-sync` Active_Account_Sync_API / Automation_Task_Sync_API 的客户端数据契约。
 *
 * 字段命名严格对齐 design.md「Active_Account_Sync_API」与「Automation_Task_Sync_API」章节的
 * JSON schema：服务端使用 snake_case，客户端通过 [SerialName] 映射到 Kotlin 的驼峰命名。
 *
 * 这些数据类只覆盖任务 12.1 的最小同步契约，[AccountPoolSyncRepository] 与
 * [AccountPoolSyncViewModel] 等后续任务会基于此扩展。任务 12.1 仅定义传输层，不提供持久化。
 */

/**
 * 接口 A 响应：Active_Pool 清单（不含密码、不含自动任务详情）。
 */
@Serializable
data class ActiveAccountListResponse(
    @SerialName("server_time")
    val serverTime: String,
    val accounts: List<ActiveAccountListItem>,
)

/**
 * 接口 A 中的单个活跃账号摘要项。
 */
@Serializable
data class ActiveAccountListItem(
    @SerialName("account_id")
    val accountId: Long,
    @SerialName("student_id")
    val studentId: String,
    @SerialName("display_name")
    val displayName: String = "",
    @SerialName("pool_status")
    val poolStatus: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * 接口 B 响应：Active_Pool 单账号详情，仅在账号属于 Active_Pool 时返回。
 *
 * 设计要求：[ActiveAccountDetail.password] 字段在响应解码后**不持久化**，只在内存中
 * 供单次任务执行使用，进程退出即销毁；持久化策略由 [AccountPoolSyncRepository] 负责。
 */
@Serializable
data class ActiveAccountDetailResponse(
    @SerialName("server_time")
    val serverTime: String,
    val account: ActiveAccountDetail,
    @SerialName("automation_tasks")
    val automationTasks: List<AutomationTaskDto> = emptyList(),
)

/**
 * 接口 B 中的账号详情，含明文密码与账号级 [revision]。
 */
@Serializable
data class ActiveAccountDetail(
    @SerialName("account_id")
    val accountId: Long,
    @SerialName("student_id")
    val studentId: String,
    @SerialName("display_name")
    val displayName: String = "",
    val password: String,
    val revision: Long,
)

/**
 * 自动任务下行获取响应，字段语义与接口 B 中的 `automation_tasks` 一致。
 *
 * 设计文档说明首版下行同步与接口 B 对齐，保留独立端点便于后续差量同步演进。
 */
@Serializable
data class AutomationTasksResponse(
    @SerialName("server_time")
    val serverTime: String,
    @SerialName("automation_tasks")
    val automationTasks: List<AutomationTaskDto> = emptyList(),
)

/**
 * 自动任务的传输 DTO。`revision` 为任务级乐观并发版本号；`mode` 取值见 [AutomationTaskMode]。
 */
@Serializable
data class AutomationTaskDto(
    @SerialName("task_id")
    val taskId: Long,
    @SerialName("room_name")
    val roomName: String,
    @SerialName("seat_number")
    val seatNumber: String,
    val mode: String,
    @SerialName("custom_windows")
    val customWindows: List<AutomationCustomWindowDto> = emptyList(),
    val enabled: Boolean,
    val revision: Long,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * 自定义时段。`endHour` 必须严格大于 `startHour`，0–23 整数。
 */
@Serializable
data class AutomationCustomWindowDto(
    val date: String,
    @SerialName("start_hour")
    val startHour: Int,
    @SerialName("end_hour")
    val endHour: Int,
)

/**
 * 自动任务上行 PUT 请求体。`revision` 为客户端持有的版本号，服务端落库前比对。
 */
@Serializable
data class AutomationTaskUpsertRequest(
    @SerialName("room_name")
    val roomName: String,
    @SerialName("seat_number")
    val seatNumber: String,
    val mode: String,
    @SerialName("custom_windows")
    val customWindows: List<AutomationCustomWindowDto> = emptyList(),
    val enabled: Boolean,
    val revision: Long,
)

/**
 * 自动任务上行 PUT 响应。`revision` 为服务端落库后写入的新版本号（旧 revision + 1）。
 */
@Serializable
data class AutomationTaskUpsertResponse(
    @SerialName("server_time")
    val serverTime: String,
    val revision: Long,
)

/**
 * 自动任务上行 DELETE 响应。`revision` 是软删除后写入的新版本号。
 */
@Serializable
data class AutomationTaskDeleteResponse(
    @SerialName("server_time")
    val serverTime: String,
    val revision: Long,
)

/**
 * 客户端拉黑事件上报请求体。
 *
 * - `evidence`：自由文本，例如「人机验证失败 5 次」。
 * - `clientKind`：`window` / `android`，用于审计区分。
 * - `clientObservedAt`：客户端本地观测到的时间戳（UTC ISO8601）。
 */
@Serializable
data class BlacklistEventRequest(
    val evidence: String,
    @SerialName("client_kind")
    val clientKind: String,
    @SerialName("client_observed_at")
    val clientObservedAt: String,
)

/**
 * 客户端拉黑事件上报响应。账号成功迁入 Suspended_Pool 后，服务端回写新的池状态。
 */
@Serializable
data class BlacklistEventResponse(
    @SerialName("server_time")
    val serverTime: String,
    @SerialName("account_id")
    val accountId: Long,
    @SerialName("pool_status")
    val poolStatus: String,
)

/**
 * 客户端类型枚举的字面量；写入 [BlacklistEventRequest.clientKind] 与日志审计字段。
 */
object ClientKindLiteral {
    const val ANDROID: String = "android"
    const val WINDOW: String = "window"
}

/**
 * 自动任务模式枚举的字面量，避免散落的字符串拼写错误。与服务端 `mode` 字段取值集合一致。
 */
object AutomationTaskMode {
    const val PREFERRED: String = "preferred"
    const val MANUAL: String = "manual"
    const val RANDOM: String = "random"
}
