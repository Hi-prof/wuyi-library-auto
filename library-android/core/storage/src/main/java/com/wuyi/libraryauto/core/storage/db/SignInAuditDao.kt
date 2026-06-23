package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SignInAuditDao {
    @Insert
    suspend fun insert(audit: SignInAuditEntity): Long

    @Query("SELECT * FROM signin_audit ORDER BY createdAtEpochSeconds DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SignInAuditEntity>

    @Query(
        """
        SELECT signInError, COUNT(*) AS count
        FROM signin_audit
        WHERE createdAtEpochSeconds >= :rangeStart
            AND createdAtEpochSeconds <= :rangeEnd
        GROUP BY signInError
        """,
    )
    suspend fun countByErrorWithin(
        rangeStart: Long,
        rangeEnd: Long,
    ): List<SignInErrorCount>

    @Query("DELETE FROM signin_audit WHERE createdAtEpochSeconds < :cutoffEpochSeconds")
    suspend fun deleteOlderThan(cutoffEpochSeconds: Long): Int

    @Query(
        """
        DELETE FROM signin_audit
        WHERE id NOT IN (
            SELECT id FROM signin_audit
            ORDER BY createdAtEpochSeconds DESC, id DESC
            LIMIT :maxRows
        )
        """,
    )
    suspend fun deleteRowsOutsideRecentLimit(maxRows: Int): Int

    @Query("DELETE FROM signin_audit")
    suspend fun clearAll(): Int
}

data class SignInErrorCount(
    val signInError: String?,
    val count: Long,
)
