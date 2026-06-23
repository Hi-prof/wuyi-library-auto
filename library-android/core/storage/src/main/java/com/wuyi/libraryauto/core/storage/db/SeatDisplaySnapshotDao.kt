package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SeatDisplaySnapshotDao {
    @Upsert
    suspend fun upsert(snapshot: SeatDisplaySnapshotEntity)

    @Query("SELECT * FROM seat_display_snapshots WHERE studentId = :studentId LIMIT 1")
    suspend fun findByStudentId(studentId: String): SeatDisplaySnapshotEntity?

    @Query("SELECT * FROM seat_display_snapshots ORDER BY roomName ASC, seatNumber ASC, studentId ASC")
    suspend fun listAll(): List<SeatDisplaySnapshotEntity>
}
