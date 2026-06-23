package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Active_Pool 缓存 DAO（任务 12.2）：
 *
 * 提供「整体替换 + 单条查询 + 全量观察」三类操作，满足任务对 `insert/update/replace`、
 * `findAll`、`findById`、`deleteAll` 的最小要求；同时新增 `replaceAll` 事务用例支撑
 * Worker 的「整批同步」语义——该方法把「清空 + 写入」绑定到同一事务内，避免 UI Flow
 * 在同步过程中观察到中间空状态。
 */
@Dao
interface ActiveAccountDao {
    /**
     * Upsert 单条记录。`@Upsert` 会按主键 `accountId` 选择 INSERT 或 UPDATE，等价于任务
     * 描述里的「insert/update/replace」组合。
     */
    @Upsert
    suspend fun upsert(account: ActiveAccountEntity)

    @Upsert
    suspend fun upsertAll(accounts: List<ActiveAccountEntity>)

    @Query("SELECT * FROM active_accounts ORDER BY studentId ASC")
    suspend fun findAll(): List<ActiveAccountEntity>

    @Query("SELECT * FROM active_accounts ORDER BY studentId ASC")
    fun observeAll(): Flow<List<ActiveAccountEntity>>

    @Query("SELECT * FROM active_accounts WHERE accountId = :accountId LIMIT 1")
    suspend fun findById(accountId: Long): ActiveAccountEntity?

    @Query("SELECT * FROM active_accounts WHERE studentId = :studentId LIMIT 1")
    suspend fun findByStudentId(studentId: String): ActiveAccountEntity?

    @Query("DELETE FROM active_accounts WHERE studentId = :studentId")
    suspend fun deleteByStudentId(studentId: String)

    @Query("DELETE FROM active_accounts")
    suspend fun deleteAll()

    /**
     * 整批同步：在单个事务内清空旧缓存并写入新清单。
     *
     * 设计依据：服务端接口 A 返回的是当前 Active_Pool 的完整清单（首版不支持增量同步），
     * 任何账号一旦不在响应中就意味着已经迁出 Active_Pool；客户端必须把缓存替换为最新结果，
     * 否则会出现「旧缓存里仍然显示已迁出账号」的脏数据。把 deleteAll + upsertAll 放进
     * Room `@Transaction`，可以避免 Flow 观察者捕获到「已清空但还未写入」的瞬时空状态。
     */
    @Transaction
    suspend fun replaceAll(accounts: List<ActiveAccountEntity>) {
        deleteAll()
        if (accounts.isNotEmpty()) {
            upsertAll(accounts)
        }
    }
}
