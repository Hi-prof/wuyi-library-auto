package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReservationTaskDao {
    @Upsert
    suspend fun upsert(task: ReservationTaskEntity)

    @Query("SELECT * FROM reservation_tasks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ReservationTaskEntity?

    @Query(
        "SELECT * FROM reservation_tasks WHERE studentId = :studentId " +
            "ORDER BY startTimeEpochSeconds DESC, id DESC LIMIT 1",
    )
    suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity?

    @Query(
        "SELECT * FROM reservation_tasks WHERE studentId = :studentId " +
            "ORDER BY startTimeEpochSeconds DESC",
    )
    suspend fun listForStudent(studentId: String): List<ReservationTaskEntity>

    @Query("SELECT * FROM reservation_tasks ORDER BY startTimeEpochSeconds ASC, id ASC")
    fun observeAll(): Flow<List<ReservationTaskEntity>>

    @Query("SELECT * FROM reservation_tasks ORDER BY startTimeEpochSeconds ASC, id ASC")
    suspend fun listAll(): List<ReservationTaskEntity>
}
