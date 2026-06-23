package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInSummary
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogHeartbeatStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull

class PeriodicCheckInWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val source =
            inputData.getString(KEY_TRIGGER_SOURCE)
                ?.let { value -> runCatching { TriggerSource.valueOf(value) }.getOrNull() }
                ?: TriggerSource.PeriodicMonitor
        return runOnce(
            source = source,
            timeoutMillis = RUN_TIMEOUT_MILLIS,
            runner = PeriodicCheckInWorkerProvider.runner(applicationContext),
            heartbeatWriter = WatchdogHeartbeatStore(applicationContext),
            runGate = PeriodicCheckInRunGate.shared,
            nowEpochSeconds = { System.currentTimeMillis() / 1_000L },
        )
    }

    companion object {
        const val UNIQUE_NAME = "periodic-check-in"
        internal const val KEY_TRIGGER_SOURCE = "triggerSource"
        // BUG-B 修复：账号间并发后单次执行可能更长（4 并发 × 60s/账号），把整体超时
        // 从 5 分钟拉到 10 分钟，仍然小于 30 分钟周期，避免 Summary 超时丢失已完成结果。
        internal const val RUN_TIMEOUT_MILLIS = 10 * 60_000L

        fun ensureScheduled(context: Context) {
            enqueuePeriodicWork(
                enqueuer = WorkManagerPeriodicCheckInEnqueuer(context),
                request = buildPeriodicRequest(),
            )
        }

        fun runOnceNow(
            context: Context,
            source: TriggerSource,
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_NAME:run-now:${source.name}",
                ExistingWorkPolicy.REPLACE,
                buildRunOnceRequest(source),
            )
        }

        internal fun buildPeriodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PeriodicCheckInWorker>(
                30,
                TimeUnit.MINUTES,
                10,
                TimeUnit.MINUTES,
            )
                // BUG-CAPTIVE 修复：之前 setRequiredNetworkType(CONNECTED) 在校园网未通过认证
                // （未 VALIDATED）时会被 WorkManager 视为不满足约束直接不调度，导致后台连不上 portal。
                // 真实的网络判定改由 [BackgroundNetworkRecoveryCoordinator] 在 doWork 内做，
                // 这里只保留 LINEAR backoff 兜底。
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
                .build()

        internal fun buildRunOnceRequest(source: TriggerSource): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PeriodicCheckInWorker>()
                .setInputData(workDataOf(KEY_TRIGGER_SOURCE to source.name))
                // BUG-D 修复：与 PeriodicWorkRequest 对齐 LINEAR backoff，避免默认指数退避在
                // ProcessRestart / NetworkRestored / CampusAuthRecovery 触发时上飙到几小时。
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
                .build()

        internal fun enqueuePeriodicWork(
            enqueuer: PeriodicCheckInEnqueuer,
            request: PeriodicWorkRequest,
        ) {
            enqueuer.enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_NAME,
                policy = ExistingPeriodicWorkPolicy.KEEP,
                request = request,
            )
        }

        internal suspend fun runOnce(
            source: TriggerSource,
            timeoutMillis: Long,
            runner: PeriodicCheckInRunner,
            heartbeatWriter: PeriodicCheckInHeartbeatWriter,
            runGate: PeriodicCheckInRunGate,
            nowEpochSeconds: () -> Long,
        ): Result {
            if (runGate.isBatchCheckInRunning()) {
                heartbeatWriter.markPeriodicCheckInHeartbeat(nowEpochSeconds())
                return Result.success()
            }
            val summary =
                withTimeoutOrNull(timeoutMillis) {
                    runner.run(source)
                }
            heartbeatWriter.markPeriodicCheckInHeartbeat(nowEpochSeconds())
            return when {
                summary == null -> Result.retry()
                summary.timedOutAccounts > 0 -> Result.retry()
                summary.failedReservations > 0 -> Result.retry()
                else -> Result.success()
            }
        }
    }
}

interface PeriodicCheckInRunner {
    suspend fun run(source: TriggerSource): PeriodicCheckInSummary
}

interface PeriodicCheckInEnqueuer {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )
}

private class WorkManagerPeriodicCheckInEnqueuer(
    context: Context,
) : PeriodicCheckInEnqueuer {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ) {
        workManager.enqueueUniquePeriodicWork(uniqueWorkName, policy, request)
    }
}

interface PeriodicCheckInHeartbeatWriter {
    fun markPeriodicCheckInHeartbeat(epochSeconds: Long)
}

/**
 * 进程级互斥状态：UI 批量签到正在执行时阻断 [PeriodicCheckInWorker]，避免对同一 booking
 * 同时发起两次 HTTP 请求。
 *
 * **前提**：当前 app 单进程运行，因此 JVM 静态变量足够。如果未来 Worker 拆到独立进程，
 * 需要换成 SharedPreferences/文件锁 等跨进程信号。
 */
class PeriodicCheckInRunGate {
    @Volatile
    private var batchCheckInRunning: Boolean = false

    fun isBatchCheckInRunning(): Boolean = batchCheckInRunning

    fun markBatchCheckInRunning(running: Boolean) {
        batchCheckInRunning = running
    }

    companion object {
        val shared = PeriodicCheckInRunGate()
    }
}

object PeriodicCheckInWorkerProvider {
    @Volatile
    private var runnerFactory: ((Context) -> PeriodicCheckInRunner)? = null

    internal fun runner(context: Context): PeriodicCheckInRunner =
        runnerFactory?.invoke(context)
            ?: error("PeriodicCheckInWorker runner is not installed.")

    /**
     * 注入生产环境使用的 [PeriodicCheckInRunner] 工厂。
     * 必须在 [PeriodicCheckInWorker.ensureScheduled] / [PeriodicCheckInWorker.runOnceNow] 之前调用，
     * 否则 [PeriodicCheckInWorker.doWork] 会抛 [IllegalStateException]。
     */
    fun install(factory: (Context) -> PeriodicCheckInRunner) {
        runnerFactory = factory
    }

    /** 仅供测试还原。 */
    fun reset() {
        runnerFactory = null
    }
}
