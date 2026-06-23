package com.wuyi.libraryauto.core.runtime.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogger
import com.wuyi.libraryauto.core.runtime.notification.NotificationFactory
import com.wuyi.libraryauto.core.runtime.worker.ReservationGuardWorker
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 常驻前台守护服务（档位 1）。
 *
 * 设计目标：
 * - 在 WorkManager / AlarmManager 之外建立第三条独立调度链路，让进程一直活着，
 *   把"签到延迟"从依赖系统调度链改为亚秒级触发，覆盖 Doze、长时间灭屏、
 *   厂商进程冻结等极端场景下的漏签。
 * - 不直接执行签到逻辑：到点的协程仍然 enqueue [ReservationGuardWorker]，
 *   走既有 BLE 扫描 / HTTP 签到 / 错误处理 / mutex 互斥链路，避免业务逻辑分叉。
 *
 * 互斥保证：每条预约同一时刻最多签一次，由 GuardWorker 内部
 * `BookingCheckInMutexRegistry.shared.withLock(bookingId)` 保证。WorkManager 主链、
 * AlarmManager 兜底、本服务三路到点同时落地不会重复发请求。
 *
 * 生命周期：
 * - 由 [WuyiLibraryApp.onCreate] / `BootRestoreReceiver` / `WatchdogWorker` 三路兜底拉起；
 * - 服务被系统杀掉后 [Service.onStartCommand] 返回 [Service.START_STICKY]，让系统在资源恢复后
 *   自动重启；同时 [WatchdogWorker] 每 6 小时巡检一次，发现没活立刻 startForegroundService。
 */
class GuardSchedulerService : Service() {
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + supervisorJob)

    /**
     * 每条预约对应一个挂起到目标时间的协程 Job。Key 为 [ReservationTaskEntity.id]，
     * value 为 launch 出来的 Job。任务进入终态或被远端取消时立即 cancel + remove。
     */
    private val scheduledJobs = ConcurrentHashMap<String, Job>()

    private lateinit var reservationTaskDao: ReservationTaskDao

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LocalDiagnosticLogger.info("GuardSchedulerService", "服务创建")
        if (!startForegroundCompat(activeTaskCount = 0)) {
            LocalDiagnosticLogger.error("GuardSchedulerService", "前台化失败，服务停止")
            stopSelf()
            return
        }
        reservationTaskDao =
            runCatching {
                StorageDatabaseProvider.get(applicationContext).reservationTaskDao()
            }.getOrElse { error ->
                Log.e(TAG, "Failed to open reservation task database for guard scheduler.", error)
                LocalDiagnosticLogger.error(
                    source = "GuardSchedulerService",
                    title = "打开预约任务数据库失败",
                    detailLines = listOf("type=${error::class.java.name}", "message=${error.message.orEmpty()}"),
                )
                stopSelf()
                return
            }
        observeReservationTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 已经在 onCreate 启动了前台 + 监听，这里仅处理 BootReceiver / Watchdog 重新拉起的场景。
        if (!startForegroundCompat(activeTaskCount = scheduledJobs.size)) {
            LocalDiagnosticLogger.error(
                source = "GuardSchedulerService",
                title = "onStartCommand 前台化失败",
                detailLines = listOf("startId=$startId", "activeTaskCount=${scheduledJobs.size}"),
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "GuardSchedulerService foreground service timed out; stopping service.")
        LocalDiagnosticLogger.warn(
            source = "GuardSchedulerService",
            title = "前台服务超时，主动停止",
            detailLines = listOf("startId=$startId", "activeTaskCount=${scheduledJobs.size}"),
        )
        stopSelf(startId)
    }

    override fun onDestroy() {
        LocalDiagnosticLogger.info(
            source = "GuardSchedulerService",
            title = "服务销毁",
            detailLines = listOf("activeTaskCount=${scheduledJobs.size}"),
        )
        scheduledJobs.values.forEach(Job::cancel)
        scheduledJobs.clear()
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startForegroundCompat(activeTaskCount: Int): Boolean {
        val notification =
            NotificationFactory.createGuardSchedulerNotification(applicationContext, activeTaskCount)
        // foregroundServiceType 不传，使用 manifest 声明的默认值（dataSync）。
        return runCatching {
            startForeground(NotificationFactory.GUARD_SCHEDULER_NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.e(TAG, "Failed to promote GuardSchedulerService to foreground.", error)
            LocalDiagnosticLogger.error(
                source = "GuardSchedulerService",
                title = "调用 startForeground 失败",
                detailLines = listOf("type=${error::class.java.name}", "message=${error.message.orEmpty()}"),
            )
        }.isSuccess
    }

    private fun observeReservationTasks() {
        // 监听 reservation_tasks 全表 Flow：新增预约 → 调度新协程；状态推进到终态 → 取消既有协程。
        // distinctUntilChanged 减少重复处理：Room observeAll 在任何字段更新时都会发新值。
        serviceScope.launch {
            reservationTaskDao.observeAll()
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Reservation task observer failed; stopping guard scheduler.", error)
                    LocalDiagnosticLogger.error(
                        source = "GuardSchedulerService",
                        title = "预约任务监听失败，服务停止",
                        detailLines = listOf("type=${error::class.java.name}", "message=${error.message.orEmpty()}"),
                    )
                    stopSelf()
                }
                .collect { tasks ->
                    runCatching {
                        syncSchedulesWithTasks(tasks)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to sync guard schedules; stopping guard scheduler.", error)
                        LocalDiagnosticLogger.error(
                            source = "GuardSchedulerService",
                            title = "同步守护调度失败，服务停止",
                            detailLines = listOf("type=${error::class.java.name}", "message=${error.message.orEmpty()}"),
                        )
                        stopSelf()
                    }
                }
        }
    }

    private fun syncSchedulesWithTasks(tasks: List<ReservationTaskEntity>) {
        val activeTaskIds = mutableSetOf<String>()
        val nowEpochSeconds = System.currentTimeMillis() / 1_000L
        tasks.forEach { task ->
            if (!task.isSchedulable(nowEpochSeconds)) {
                return@forEach
            }
            activeTaskIds += task.id
            // 已经在排队的不重新排：避免每次 Room 通知都重新计算一遍 delay。
            if (scheduledJobs.containsKey(task.id)) {
                return@forEach
            }
            val job = launchSignInJob(task, nowEpochSeconds)
            scheduledJobs[task.id] = job
        }
        // 任务进终态或被删除：取消并清理对应协程，释放内存。
        scheduledJobs.entries.removeAll { (taskId, job) ->
            if (taskId !in activeTaskIds) {
                job.cancel()
                true
            } else {
                false
            }
        }
        LocalDiagnosticLogger.info(
            source = "GuardSchedulerService",
            title = "已同步守护任务",
            detailLines = listOf("taskCount=${tasks.size}", "activeTaskCount=${scheduledJobs.size}"),
        )
        updateNotification(scheduledJobs.size)
    }

    private fun launchSignInJob(
        task: ReservationTaskEntity,
        nowEpochSeconds: Long,
    ): Job {
        // 触发时刻：start - limitSignAgo（与 GuardWorker 的 enqueue 保持一致）。
        val targetEpochSeconds = task.startTimeEpochSeconds - task.limitSignAgoSeconds
        val delaySeconds = max(targetEpochSeconds - nowEpochSeconds, 0L)
        return serviceScope.launch {
            if (delaySeconds > 0L) {
                delay(delaySeconds * 1_000L)
            }
            // 复用 GuardWorker.enqueue：内部含 alarm 双轨 + WorkManager + mutex 互斥。
            // 这里把 GuardWorker 当成"签到执行器"调用，避免在前台服务里再实现一份 BLE 扫描。
            runCatching {
                ReservationGuardWorker.enqueue(
                    context = applicationContext,
                    taskId = task.id,
                    startTimeEpochSeconds = task.startTimeEpochSeconds,
                    limitSignAgoSeconds = task.limitSignAgoSeconds,
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to enqueue GuardWorker for ${task.id}: ${error.message}")
                LocalDiagnosticLogger.warn(
                    source = "GuardSchedulerService",
                    title = "入队 GuardWorker 失败",
                    detailLines = listOf("taskId=${task.id}", "type=${error::class.java.name}", "message=${error.message.orEmpty()}"),
                )
            }
        }
    }

    private fun updateNotification(activeTaskCount: Int) {
        val manager =
            ContextCompat.getSystemService(applicationContext, NotificationManager::class.java)
                ?: return
        manager.notify(
            NotificationFactory.GUARD_SCHEDULER_NOTIFICATION_ID,
            NotificationFactory.createGuardSchedulerNotification(applicationContext, activeTaskCount),
        )
    }

    private fun ReservationTaskEntity.isSchedulable(nowEpochSeconds: Long): Boolean {
        if (state != ReservationTaskState.RESERVED_WAITING_SIGNIN &&
            state != ReservationTaskState.GUARD_SCHEDULED
        ) {
            return false
        }
        // 签到截止时间已过，没必要再排了。
        return nowEpochSeconds < startTimeEpochSeconds + limitSignBackSeconds
    }

    companion object {
        private const val TAG = "GuardSchedulerService"

        /**
         * 由 [WuyiLibraryApp.onCreate] / `BootRestoreReceiver` / `WatchdogWorker` 调用。
         * Android 8.0+ 必须用 `startForegroundService`，否则 5 秒内不调 `startForeground` 会被系统 ANR。
         */
        fun start(context: Context) {
            val intent = Intent(context.applicationContext, GuardSchedulerService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
                LocalDiagnosticLogger.info("GuardSchedulerService", "已请求启动守护服务")
            }.onFailure { error ->
                Log.w(TAG, "Failed to start GuardSchedulerService: ${error.message}")
                LocalDiagnosticLogger.warn(
                    source = "GuardSchedulerService",
                    title = "请求启动守护服务失败",
                    detailLines = listOf("type=${error::class.java.name}", "message=${error.message.orEmpty()}"),
                )
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, GuardSchedulerService::class.java)
            context.applicationContext.stopService(intent)
            LocalDiagnosticLogger.info("GuardSchedulerService", "已请求停止守护服务")
        }
    }
}
