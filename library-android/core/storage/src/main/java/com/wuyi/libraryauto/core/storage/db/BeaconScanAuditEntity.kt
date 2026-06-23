package com.wuyi.libraryauto.core.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "beacon_scan_audit",
    indices = [Index(value = ["createdAtEpochSeconds"], name = "idx_beacon_scan_audit_created_at")],
)
data class BeaconScanAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val correlationId: String,
    val bookingId: String?,
    val expectedMinorsCsv: String,
    val seenMinorsCsv: String,
    val matchedMinor: Int?,
    val scanDurationMillis: Long,
    val terminationReason: String,
    val createdAtEpochSeconds: Long,
)
