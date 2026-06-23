package com.wuyi.libraryauto.ui.repository.task

import kotlinx.coroutines.flow.Flow

interface AutomationPlanRepository {
    fun observePlans(): Flow<List<AutomationPlanRecord>>

    suspend fun savePlan(draft: AutomationPlanDraft): AutomationPlanRecord

    suspend fun deletePlan(planId: String)
}
