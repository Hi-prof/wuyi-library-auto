package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState

@Entity(tableName = "reservation_tasks")
data class ReservationTaskEntity(
    @PrimaryKey val id: String,
    val studentId: String = "",
    val roomName: String = "",
    val seatNumber: String,
    val state: ReservationTaskState,
    val bookingId: String?,
    val startTimeEpochSeconds: Long,
    val limitSignAgoSeconds: Long,
    @ColumnInfo(defaultValue = "1800")
    val limitSignBackSeconds: Long = 1_800L,
    val expectedMinorsCsv: String,
    val lastError: String?,
    // BUG 2 修复：记录 GuardWorker / 周期 runner 上一次实际尝试签到的 epoch seconds，
    // RunPeriodicCheckInUseCase 借此实现 60 秒重复签到防抖。
    @ColumnInfo(defaultValue = "NULL")
    val lastGuardAttemptEpochSeconds: Long? = null,
    // BUG-RETRY 修复：用于 ReservationGuardWorker 指数退避，签到成功时归零。
    @ColumnInfo(defaultValue = "0")
    val consecutiveRetryCount: Int = 0,
)

fun buildReservationTaskId(
    studentId: String,
    bookingId: String,
): String = "${studentId.trim()}:${bookingId.trim()}"
