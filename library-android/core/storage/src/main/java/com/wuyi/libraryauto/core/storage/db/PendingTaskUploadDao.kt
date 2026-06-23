package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * `account-pool-tri-sync` 任务 12.3：待上传队列的 DAO。
 *
 * 设计语义：
 * - 严格 FIFO：[peekNext] / [findOldest] 都按 `createdAtEpochSeconds ASC, id ASC` 排序，
 *   保证「先入队先发」与服务端审计顺序对齐。
 * - 单条删除使用 [deleteById]：成功上传后 Worker 删该条；遇到不可重试错误（如 422）也用此方法清队。
 * - [updateRetryState] 用于把可重试失败的错误原因记下来，方便排查；`retryCount` 自增由调用方完成。
 */
@Dao
interface PendingTaskUploadDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PendingTaskUploadEntity): Long

    @Query(
        """
        SELECT * FROM pending_task_uploads
        ORDER BY createdAtEpochSeconds ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun peekNext(): PendingTaskUploadEntity?

    @Query(
        """
        SELECT * FROM pending_task_uploads
        ORDER BY createdAtEpochSeconds ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun findOldest(): PendingTaskUploadEntity?

    @Query("SELECT * FROM pending_task_uploads ORDER BY createdAtEpochSeconds ASC, id ASC")
    suspend fun findAll(): List<PendingTaskUploadEntity>

    @Query("SELECT * FROM pending_task_uploads ORDER BY createdAtEpochSeconds ASC, id ASC")
    fun observeAll(): Flow<List<PendingTaskUploadEntity>>

    @Query("SELECT COUNT(*) FROM pending_task_uploads")
    suspend fun count(): Int

    @Query("DELETE FROM pending_task_uploads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        UPDATE pending_task_uploads
        SET retryCount = :retryCount, lastErrorReason = :reason
        WHERE id = :id
        """,
    )
    suspend fun updateRetryState(
        id: Long,
        retryCount: Int,
        reason: String?,
    )

    @Query("DELETE FROM pending_task_uploads")
    suspend fun deleteAll()
}
