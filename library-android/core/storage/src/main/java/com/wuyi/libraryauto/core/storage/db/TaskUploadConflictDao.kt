package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * `account-pool-tri-sync` 任务 12.3：自动任务上传冲突记录 DAO。
 *
 * 详情页将通过 [observeAll] 监听冲突列表，向用户暴露冲突解决 UI；本 DAO 只负责
 * 把 Worker 写入 / 清理冲突记录的能力暴露出来，不做 UI。
 */
@Dao
interface TaskUploadConflictDao {
    @Upsert
    suspend fun upsert(conflict: TaskUploadConflictEntity)

    @Query("SELECT * FROM task_upload_conflicts ORDER BY detectedAtEpochSeconds DESC")
    suspend fun findAll(): List<TaskUploadConflictEntity>

    @Query("SELECT * FROM task_upload_conflicts ORDER BY detectedAtEpochSeconds DESC")
    fun observeAll(): Flow<List<TaskUploadConflictEntity>>

    @Query("SELECT * FROM task_upload_conflicts WHERE conflictHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): TaskUploadConflictEntity?

    @Query("DELETE FROM task_upload_conflicts WHERE conflictHash = :hash")
    suspend fun deleteByHash(hash: String)

    @Query("DELETE FROM task_upload_conflicts")
    suspend fun deleteAll()
}
