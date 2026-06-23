package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.flow.first

class AutomationPlanScheduler(
    private val context: Context,
) {
    fun schedule(
        planId: String,
        nextRunAtEpochSeconds: Long,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(planId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildRequest(planId, nextRunAtEpochSeconds, nowEpochSeconds),
        )
    }

    fun cancel(planId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(planId))
    }

    suspend fun restoreEnabledPlans(
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Int {
        val plans =
            StorageDatabaseProvider.get(context)
                .automationPlanDao()
                .observeEnabledPlans()
                .first()
        plans.forEach { plan ->
            schedule(
                planId = plan.planId,
                nextRunAtEpochSeconds = plan.nextRunAtEpochSeconds ?: nowEpochSeconds,
                nowEpochSeconds = nowEpochSeconds,
            )
        }
        return plans.size
    }

    private fun buildRequest(
        planId: String,
        nextRunAtEpochSeconds: Long,
        nowEpochSeconds: Long,
    ): OneTimeWorkRequest {
        val delaySeconds = max(nextRunAtEpochSeconds - nowEpochSeconds, 0L)
        return OneTimeWorkRequestBuilder<AutomationPlanWorker>()
            .setInputData(workDataOf(AutomationPlanWorker.KEY_PLAN_ID to planId))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
    }

    private fun uniqueWorkName(planId: String): String = "$UNIQUE_WORK_NAME_PREFIX:$planId"

    private companion object {
        private const val UNIQUE_WORK_NAME_PREFIX = "automation-plan"
    }
}
