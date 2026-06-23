package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogger
import com.wuyi.libraryauto.core.runtime.service.GuardSchedulerService
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogHeartbeatStore
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogHeartbeatWriter
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogState
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogStateRepository
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogStateStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        return runOnce(
            inspector = WorkManagerWatchdogWorkInspector(
                workManager = WorkManager.getInstance(context),
                repository = StorageBackedTaskRepository(context),
            ),
            recoveryScheduler = WorkManagerWatchdogRecoveryScheduler(context),
            stateRepository = WatchdogStateStore(context),
            heartbeatWriter = WatchdogHeartbeatStore(context),
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        )
    }

    companion object {
        const val UNIQUE_NAME = "watchdog"
        internal const val ENSURE_TIMEOUT_MILLIS = 5_000L
        internal const val GUARD_LOOKAHEAD_SECONDS = 24L * 3600L

        fun ensureScheduled(context: Context) {
            enqueuePeriodicWork(
                enqueuer = WorkManagerWatchdogEnqueuer(context),
                request = buildPeriodicRequest(),
            )
            LocalDiagnosticLogger.info("WatchdogWorker", "已确认看门狗周期调度")
        }

        internal fun buildPeriodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<WatchdogWorker>(
                6,
                TimeUnit.HOURS,
                30,
                TimeUnit.MINUTES,
            )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()

        internal fun enqueuePeriodicWork(
            enqueuer: WatchdogEnqueuer,
            request: PeriodicWorkRequest,
        ) {
            enqueuer.enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_NAME,
                policy = ExistingPeriodicWorkPolicy.KEEP,
                request = request,
            )
        }

        internal suspend fun runOnce(
            inspector: WatchdogWorkInspector,
            recoveryScheduler: WatchdogRecoveryScheduler,
            stateRepository: WatchdogStateRepository,
            heartbeatWriter: WatchdogHeartbeatWriter,
            nowEpochSeconds: () -> Long,
            ensureTimeoutMillis: Long = ENSURE_TIMEOUT_MILLIS,
        ): Result {
            val now = nowEpochSeconds()
            val state = stateRepository.read()
            if (state is WatchdogState.Backoff && now < state.backoffStartEpochSeconds + state.backoffDurationSeconds) {
                heartbeatWriter.markWatchdogHeartbeat(now)
                return Result.success()
            }

            val inspection = inspector.inspect(now)
            if (inspection.allReady) {
                stateRepository.reset()
                heartbeatWriter.markWatchdogHeartbeat(now)
                LocalDiagnosticLogger.info("WatchdogWorker", "巡检正常")
                return Result.success()
            }

            LocalDiagnosticLogger.warn(
                source = "WatchdogWorker",
                title = "巡检发现调度缺口",
                detailLines =
                    listOf(
                        "periodicCheckInReady=${inspection.periodicCheckInReady}",
                        "watchdogReady=${inspection.watchdogReady}",
                        "recentGuardWorkersReady=${inspection.recentGuardWorkersReady}",
                        "guardServiceReady=${inspection.guardServiceReady}",
                    ),
            )
            withTimeoutOrNull(ensureTimeoutMillis) {
                recoveryScheduler.ensureScheduled(inspection, now)
            }
            stateRepository.update(nextMissingState(state, now))
            heartbeatWriter.markWatchdogHeartbeat(now)
            return Result.success()
        }

        private fun nextMissingState(
            previous: WatchdogState,
            nowEpochSeconds: Long,
        ): WatchdogState {
            val nextMissCount =
                when (previous) {
                    WatchdogState.Healthy -> 1
                    is WatchdogState.Degraded -> previous.consecutiveMissCount + 1
                    is WatchdogState.Backoff -> 1
                }
            return if (nextMissCount >= 3) {
                WatchdogState.Backoff(nowEpochSeconds)
            } else {
                WatchdogState.Degraded(nextMissCount)
            }
        }
    }
}

internal data class WatchdogInspection(
    val periodicCheckInReady: Boolean,
    val watchdogReady: Boolean,
    val recentGuardWorkersReady: Boolean,
    val guardServiceReady: Boolean,
) {
    val allReady: Boolean
        get() = periodicCheckInReady && watchdogReady && recentGuardWorkersReady && guardServiceReady
}

internal interface WatchdogWorkInspector {
    suspend fun inspect(nowEpochSeconds: Long): WatchdogInspection
}

internal interface WatchdogRecoveryScheduler {
    suspend fun ensureScheduled(
        inspection: WatchdogInspection,
        nowEpochSeconds: Long,
    )
}

internal interface WatchdogEnqueuer {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )
}

private class WorkManagerWatchdogEnqueuer(
    context: Context,
) : WatchdogEnqueuer {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ) {
        workManager.enqueueUniquePeriodicWork(uniqueWorkName, policy, request)
    }
}

private class WorkManagerWatchdogWorkInspector(
    private val workManager: WorkManager,
    private val repository: StorageBackedTaskRepository,
) : WatchdogWorkInspector {
    override suspend fun inspect(nowEpochSeconds: Long): WatchdogInspection =
        withContext(Dispatchers.IO) {
            val guardTasksInWindow =
                repository.listGuardTasks()
                    .filter { task -> task.startTimeEpochSeconds <= nowEpochSeconds + WatchdogWorker.GUARD_LOOKAHEAD_SECONDS }
            WatchdogInspection(
                periodicCheckInReady = hasActiveUniqueWork(PeriodicCheckInWorker.UNIQUE_NAME),
                watchdogReady = hasActiveUniqueWork(WatchdogWorker.UNIQUE_NAME),
                recentGuardWorkersReady = guardTasksInWindow.all { task ->
                    hasActiveUniqueWork(ReservationGuardWorker.uniqueWorkName(task.taskId))
                },
                // 没法直接探测前台服务存活；保守地一直当成 false，让 ensureScheduled 阶段每次都试一下
                // startForegroundService。GuardSchedulerService 已在 manifest 配为 START_STICKY，
                // 重复 start 是幂等操作，系统不会因此重新创建实例。
                guardServiceReady = false,
            )
        }

    private fun hasActiveUniqueWork(uniqueWorkName: String): Boolean =
        workManager.getWorkInfosForUniqueWork(uniqueWorkName)
            .get()
            .any { info -> info.state in activeStates }

    companion object {
        private val activeStates = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
        )
    }
}

private class WorkManagerWatchdogRecoveryScheduler(
    context: Context,
) : WatchdogRecoveryScheduler {
    private val appContext = context.applicationContext
    private val repository = StorageBackedTaskRepository(appContext)

    override suspend fun ensureScheduled(
        inspection: WatchdogInspection,
        nowEpochSeconds: Long,
    ) {
        withContext(Dispatchers.IO) {
            if (!inspection.periodicCheckInReady) {
                PeriodicCheckInWorker.ensureScheduled(appContext)
                LocalDiagnosticLogger.info("WatchdogWorker", "已恢复周期巡检调度")
            }
            if (!inspection.watchdogReady) {
                WatchdogWorker.ensureScheduled(appContext)
                LocalDiagnosticLogger.info("WatchdogWorker", "已恢复看门狗调度")
            }
            if (!inspection.recentGuardWorkersReady) {
                restoreGuardWorkers(nowEpochSeconds)
            }
            if (!inspection.guardServiceReady) {
                // 幂等：service 已在跑时 startForegroundService 不会创建新实例，仅触发一次 onStartCommand。
                GuardSchedulerService.start(appContext)
                LocalDiagnosticLogger.info("WatchdogWorker", "已请求恢复守护服务")
            }
        }
    }

    private suspend fun restoreGuardWorkers(nowEpochSeconds: Long) {
        val coordinator =
            GuardRestoreCoordinator { task ->
                ReservationGuardWorker.enqueue(
                    context = appContext,
                    taskId = task.taskId,
                    startTimeEpochSeconds = task.startTimeEpochSeconds,
                    limitSignAgoSeconds = task.limitSignAgoSeconds,
                    nowEpochSeconds = nowEpochSeconds,
                )
            }
        coordinator.restore(
            tasks = repository.listGuardTasks(),
            nowEpochSeconds = nowEpochSeconds,
        ).also { restoredCount ->
            LocalDiagnosticLogger.info(
                source = "WatchdogWorker",
                title = "已恢复 GuardWorker",
                detailLines = listOf("restoredGuardTaskCount=$restoredCount"),
            )
        }
    }
}
