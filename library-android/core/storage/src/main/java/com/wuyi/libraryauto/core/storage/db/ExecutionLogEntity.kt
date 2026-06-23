package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState

@Entity(
    tableName = "execution_logs",
    foreignKeys = [
        ForeignKey(
            entity = ReservationTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val state: ReservationTaskState,
    val recordedAtEpochSeconds: Long,
    val message: String?,
)
