package com.wuyi.libraryauto.core.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationPlanDao {
    @Upsert
    suspend fun upsert(plan: AutomationPlanEntity)

    @Query("SELECT * FROM automation_plans ORDER BY updatedAtEpochSeconds DESC, planId ASC")
    fun observeAll(): Flow<List<AutomationPlanEntity>>

    @Query("SELECT * FROM automation_plans WHERE enabled = 1 ORDER BY updatedAtEpochSeconds DESC, planId ASC")
    fun observeEnabledPlans(): Flow<List<AutomationPlanEntity>>

    @Query("SELECT * FROM automation_plans WHERE planId = :planId LIMIT 1")
    suspend fun findById(planId: String): AutomationPlanEntity?

    @Query(
        """
        SELECT * FROM automation_plans
        WHERE studentId = :studentId AND enabled = 1
        ORDER BY updatedAtEpochSeconds DESC, planId ASC
        LIMIT 1
        """,
    )
    suspend fun findLatestEnabledByStudentId(studentId: String): AutomationPlanEntity?

    @Query("DELETE FROM automation_plans WHERE planId = :planId")
    suspend fun deleteById(planId: String)

    @Query("SELECT * FROM automation_plans ORDER BY planId ASC")
    suspend fun findAll(): List<AutomationPlanEntity>

    @Query("DELETE FROM automation_plans WHERE planId LIKE :planIdPrefix || '%'")
    suspend fun deleteByPlanIdPrefix(planIdPrefix: String)
}
