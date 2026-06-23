package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import androidx.work.ListenableWorker.Result
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import kotlinx.coroutines.flow.first

/**
 * 跨模块入口：给 [com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInWorker] 与
 * 周期 runner 用来"统一巡检所有 enabled plan"，替代各 plan 自己 30 分钟续排带来的
 * 风控压力。
 *
 * 改造目的：
 * - 之前每个 plan 都有独立的 [AutomationPlanScheduler.schedule] 续排，N 个 plan = N 路
 *   30 分钟轮询，全打学校接口；
 * - 现在统一由周期签到入口每 30 分钟扫一遍，加 5 分钟防抖窗口；
 * - 用户编辑 / 创建 plan 仍由 [com.wuyi.libraryauto.ui.repository.task.StorageAutomationPlanRepository]
 *   立刻 schedule 一次首跑（让新建任务不必等到下个 30 分钟周期）。
 */
object AutomationPlanRunner {
    /** 5 分钟防抖：[AutomationPlanEntity.lastRunAtEpochSeconds] 距当前不足该秒数 → 跳过本轮。 */
    const val DEFAULT_MIN_INTERVAL_SECONDS: Long = 5L * 60L

    /**
     * 巡检所有 enabled plan，按防抖窗口决定是否执行。
     *
     * @param context 任意 Context，仅用 applicationContext 获取数据库与依赖。
     * @param nowEpochSeconds 当前 UTC 秒级时间戳。由调用方注入便于测试。
     * @param minIntervalSeconds 同一 plan 两次实际执行的最小间隔，默认 [DEFAULT_MIN_INTERVAL_SECONDS]。
     * @return 实际触发执行的 plan 数量；被防抖跳过 / disabled 不计入。
     */
    suspend fun runForEnabledPlans(
        context: Context,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
        minIntervalSeconds: Long = DEFAULT_MIN_INTERVAL_SECONDS,
    ): Int {
        val appContext = context.applicationContext
        val plans =
            StorageDatabaseProvider.get(appContext)
                .automationPlanDao()
                .observeEnabledPlans()
                .first()
        if (plans.isEmpty()) {
            return 0
        }
        val dependencies = AutomationPlanWorkerProvider.get(appContext)
        var executed = 0
        plans.forEach { plan ->
            if (!plan.enabled) {
                return@forEach
            }
            val lastRunAt = plan.lastRunAtEpochSeconds
            if (lastRunAt != null && nowEpochSeconds - lastRunAt < minIntervalSeconds) {
                // 防抖：5 分钟内已经跑过一次，跳过避免对同一 plan 短时间内重复打学校接口。
                return@forEach
            }
            // executeOnce 内部已有 try-catch 与日志写入；这里再加一层防御，避免单 plan 异常影响其他 plan。
            runCatching {
                AutomationPlanWorker.executeOnce(
                    planId = plan.planId,
                    nowEpochSeconds = nowEpochSeconds,
                    dependencies = dependencies,
                )
            }
            executed += 1
        }
        return executed
    }
}
