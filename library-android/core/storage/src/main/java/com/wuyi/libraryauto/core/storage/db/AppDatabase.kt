package com.wuyi.libraryauto.core.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState

@Database(
    entities = [
        ReservationTaskEntity::class,
        ExecutionLogEntity::class,
        AutomationPlanEntity::class,
        BeaconScanAuditEntity::class,
        SeatDisplaySnapshotEntity::class,
        SignInAuditEntity::class,
        ActiveAccountEntity::class,
        PendingTaskUploadEntity::class,
        TaskUploadConflictEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(StorageTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activeAccountDao(): ActiveAccountDao

    abstract fun automationPlanDao(): AutomationPlanDao

    abstract fun beaconScanAuditDao(): BeaconScanAuditDao

    abstract fun executionLogDao(): ExecutionLogDao

    abstract fun pendingTaskUploadDao(): PendingTaskUploadDao

    abstract fun reservationTaskDao(): ReservationTaskDao

    abstract fun seatDisplaySnapshotDao(): SeatDisplaySnapshotDao

    abstract fun signInAuditDao(): SignInAuditDao

    abstract fun taskUploadConflictDao(): TaskUploadConflictDao
}

class StorageTypeConverters {
    @TypeConverter
    fun toReservationTaskState(value: String): ReservationTaskState = ReservationTaskState.valueOf(value)

    @TypeConverter
    fun fromReservationTaskState(value: ReservationTaskState): String = value.name
}
