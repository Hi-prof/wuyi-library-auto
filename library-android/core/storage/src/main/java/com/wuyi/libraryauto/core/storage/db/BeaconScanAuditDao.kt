package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BeaconScanAuditDao {
    @Insert
    suspend fun insert(audit: BeaconScanAuditEntity): Long

    @Query("SELECT * FROM beacon_scan_audit ORDER BY createdAtEpochSeconds DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BeaconScanAuditEntity>

    @Query("DELETE FROM beacon_scan_audit WHERE createdAtEpochSeconds < :cutoffEpochSeconds")
    suspend fun deleteOlderThan(cutoffEpochSeconds: Long): Int

    @Query(
        """
        DELETE FROM beacon_scan_audit
        WHERE id NOT IN (
            SELECT id FROM beacon_scan_audit
            ORDER BY createdAtEpochSeconds DESC, id DESC
            LIMIT :maxRows
        )
        """,
    )
    suspend fun deleteRowsOutsideRecentLimit(maxRows: Int): Int

    @Query("DELETE FROM beacon_scan_audit")
    suspend fun clearAll(): Int
}
