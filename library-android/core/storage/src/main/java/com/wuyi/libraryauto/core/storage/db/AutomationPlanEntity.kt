package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_plans")
data class AutomationPlanEntity(
    @PrimaryKey val planId: String,
    val studentId: String,
    val roomName: String,
    val seatNumber: String,
    val mode: String,
    val singleDate: String?,
    val singleStartTime: String?,
    val singleEndTime: String?,
    val enabled: Boolean,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
    val nextRunAtEpochSeconds: Long?,
    val lastRunAtEpochSeconds: Long?,
    val lastResultMessage: String,
)
