package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "seat_display_snapshots",
    indices = [
        Index(
            value = ["roomName", "seatNumber"],
            name = "idx_seat_display_snapshots_room_seat",
        ),
    ],
)
data class SeatDisplaySnapshotEntity(
    @PrimaryKey val studentId: String,
    val roomName: String,
    val seatNumber: String,
    val beginLabel: String,
    val liveState: String,
    val statusLabel: String,
    val updatedAtEpochMillis: Long,
)
