package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Active_Pool 同步缓存实体（任务 12.2）：
 * 仅持久化服务端 Active_Account_List_API（接口 A）返回的非敏感字段。
 *
 * 设计决定：
 * - **不**包含密码、cookie、session token 等敏感字段。设计文档明确要求接口 B 返回的密码
 *   仅在内存中保留供单次任务执行使用，不能落库；持久化层只承载列表清单。
 * - 主键直接使用服务端 `accountId`，便于按 ID 查询 / 与接口 B 串联，也避免本地额外维护
 *   行 ID 的复杂性。
 * - `poolStatus` 在接口 A 当前规约下永远是 `active`，但保留字段是为了与 design.md
 *   接口 A 响应字段 1:1 对齐；如果未来增量同步在响应中带上 `archived` / `paused` 等扩展
 *   状态，缓存层不需要再做 schema 变更。
 * - `updatedAt` 透传服务端 ISO8601 字符串（UTC），便于上层做「服务端权威时间」展示与
 *   排序；本地时区由 UI 层在渲染时统一转换。
 * - `syncedAtEpochSeconds` 记录本地最近一次成功同步时间（设备时钟），供同步按钮可达性
 *   判定使用。任务 12.8 撤回周期 Worker（ActivePoolListSyncWorker）后，该字段仅由
 *   Manual_Sync_Action 触发的 [AccountPoolSyncRepository.refreshActiveList] 写入。
 */
@Entity(tableName = "active_accounts")
data class ActiveAccountEntity(
    @PrimaryKey
    val accountId: Long,
    val studentId: String,
    val displayName: String,
    val poolStatus: String,
    val updatedAt: String,
    val syncedAtEpochSeconds: Long,
)
