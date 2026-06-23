package com.wuyi.libraryauto.core.runtime.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogger
import com.wuyi.libraryauto.core.runtime.service.GuardSchedulerService
import com.wuyi.libraryauto.core.runtime.worker.GuardRestoreCoordinator
import com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInWorker
import com.wuyi.libraryauto.core.runtime.worker.ReservationGuardWorker
import com.wuyi.libraryauto.core.runtime.worker.StorageBackedTaskRepository
import com.wuyi.libraryauto.core.runtime.worker.WatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in restoreActions) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        LocalDiagnosticLogger.info(
            source = "BootRestoreReceiver",
            title = "收到恢复广播",
            detailLines = listOf("action=${intent.action.orEmpty()}"),
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = StorageBackedTaskRepository(appContext)
                val coordinator =
                    GuardRestoreCoordinator { task ->
                        ReservationGuardWorker.enqueue(
                            context = appContext,
                            taskId = task.taskId,
                            startTimeEpochSeconds = task.startTimeEpochSeconds,
                            limitSignAgoSeconds = task.limitSignAgoSeconds,
                        )
                    }

                val guardTasks = repository.listGuardTasks()
                val restoredCount = coordinator.restore(
                    tasks = guardTasks,
                    nowEpochSeconds = System.currentTimeMillis() / 1000,
                )
                // BUG-RATE-LIMIT 修复：开机不再立即把所有 enabled plan schedule 一遍，避免 N 个 plan
                // 在开机后短时间内集中打学校接口。改由 PeriodicCheckInWorker 30 分钟巡检统一驱动，
                // 巡检入口会做 5 分钟防抖。
                PeriodicCheckInWorker.ensureScheduled(appContext)
                WatchdogWorker.ensureScheduled(appContext)
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    // Android 15+ 禁止 BOOT_COMPLETED 直接启动 dataSync FGS；开机路径依赖 WorkManager
                    // 与 exact alarm 恢复，升级路径仍可主动拉起守护服务。
                    GuardSchedulerService.start(appContext)
                }
                LocalDiagnosticLogger.info(
                    source = "BootRestoreReceiver",
                    title = "恢复调度完成",
                    detailLines = listOf("action=${intent.action.orEmpty()}", "restoredGuardTaskCount=$restoredCount"),
                )
                Log.i(
                    TAG,
                    "Restored $restoredCount guard task(s) after ${intent.action}.",
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to restore guard tasks after ${intent.action}.", error)
                LocalDiagnosticLogger.error(
                    source = "BootRestoreReceiver",
                    title = "恢复调度失败",
                    detailLines =
                        listOf(
                            "action=${intent.action.orEmpty()}",
                            "type=${error::class.java.name}",
                            "message=${error.message.orEmpty()}",
                        ),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootRestoreReceiver"

        private val restoreActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
