package com.wuyi.libraryauto

import android.app.ActivityManager
import android.app.Application
import android.os.Process
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogger
import com.wuyi.libraryauto.core.runtime.diagnostics.ProcessDiagnosticMonitor
import com.wuyi.libraryauto.core.runtime.lifecycle.ProcessRestartObserver
import com.wuyi.libraryauto.core.runtime.service.GuardSchedulerService
import com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInWorker
import com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInWorkerProvider
import com.wuyi.libraryauto.core.runtime.worker.WatchdogWorker
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import com.wuyi.libraryauto.runtime.StorageBackedPeriodicCheckInRunner
import com.wuyi.libraryauto.sync.AccountPoolApiFactory
import com.wuyi.libraryauto.sync.AccountPoolSyncRepository
import com.wuyi.libraryauto.sync.AutomationTaskUploadWorker
import com.wuyi.libraryauto.sync.ServerSyncConfig
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WuyiLibraryApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // 手动初始化 WorkManager，避免 "WorkManager is not initialized properly" 错误
        WorkManager.initialize(this, workManagerConfiguration)

        val processName = ProcessDiagnosticMonitor.resolveProcessName(this)
        ProcessDiagnosticMonitor.install(this, processName)
        // 多进程模式下 Application.onCreate 会在每个进程各跑一次。:guard 进程只承担 GuardSchedulerService，
        // 不需要重新注入 worker runner，也不需要 ensureScheduled。
        if (isGuardProcess(processName)) {
            return
        }
        // BUG 1 修复：必须在 ensureScheduled / runOnceNow 之前注入生产 runner，
        // 否则 PeriodicCheckInWorker.doWork 会因 runnerFactory 默认 null 抛 IllegalStateException。
        PeriodicCheckInWorkerProvider.install { context ->
            StorageBackedPeriodicCheckInRunner(context)
        }
        // 自动任务上传 Worker：注入应用级依赖（API、DAO、Repository），
        // AutomationTaskUploader 入队后由 WorkManager 调度执行；不注入会让 doWork 抛 IllegalStateException。
        AutomationTaskUploadWorker.Provider.install { context ->
            val appContext = context.applicationContext
            val database = StorageDatabaseProvider.get(appContext)
            val serverSyncConfig = ServerSyncConfig(appContext)
            val api =
                AccountPoolApiFactory.create(
                    baseUrl =
                        serverSyncConfig.baseUrl?.let { url -> url.trimEnd('/') + "/" }
                            ?: "https://localhost/",
                    tokenProvider = { serverSyncConfig.bearerToken },
                )
            AutomationTaskUploadWorker.Dependencies(
                api = api,
                pendingDao = database.pendingTaskUploadDao(),
                conflictDao = database.taskUploadConflictDao(),
                activePoolRepository =
                    AccountPoolSyncRepository(
                        api = api,
                        activeAccountDao = database.activeAccountDao(),
                    ),
            )
        }
        PeriodicCheckInWorker.ensureScheduled(this)
        LocalDiagnosticLogger.info("Application", "已确认周期巡检调度")

        // 确保 WatchdogWorker 周期性运行，每 6 小时检查一次调度健康状态
        WatchdogWorker.ensureScheduled(this)
        LocalDiagnosticLogger.info("Application", "已确认看门狗调度")

        // 档位 2：常驻前台守护服务运行在 :guard 独立进程，UI 进程崩溃不影响守护链路。
        // startForegroundService 会触发 :guard 进程的 Application.onCreate，但因上面 isGuardProcess 早返回，
        // 不会重复初始化 worker / 网络监听。
        GuardSchedulerService.start(this)
        ProcessRestartObserver.create(this).observe()?.let { source ->
            LocalDiagnosticLogger.warn(
                source = "Application",
                title = "检测到进程重启触发巡检",
                detailLines = listOf("triggerSource=${source.name}"),
            )
            PeriodicCheckInWorker.runOnceNow(this, source)
        }
    }

    private fun isGuardProcess(processName: String): Boolean {
        if (processName.isNotBlank()) {
            return processName.endsWith(":guard")
        }
        val activityManager = getSystemService(ActivityManager::class.java) ?: return false
        val pid = Process.myPid()
        val fallbackProcessName =
            activityManager.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
                .orEmpty()
        return fallbackProcessName.endsWith(":guard")
    }
}
