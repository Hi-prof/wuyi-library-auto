package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ExecutionLogDao {
    @Insert
    suspend fun insert(log: ExecutionLogEntity)

    @Query("SELECT * FROM execution_logs ORDER BY recordedAtEpochSeconds DESC, id DESC")
    suspend fun listAllNewestFirst(): List<ExecutionLogEntity>

    @Query("DELETE FROM execution_logs")
    suspend fun clearAll()
}
