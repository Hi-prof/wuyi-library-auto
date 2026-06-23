package com.wuyi.libraryauto.core.runtime.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * 给每条预约同时排一个 [AlarmManager.setExactAndAllowWhileIdle]，作为 [WorkManager] 的兜底。
 *
 * 与 [com.wuyi.libraryauto.core.runtime.worker.ReservationGuardWorker.enqueue] 是**双轨**：
 * - WorkManager 走 Doze maintenance window，平时已经够用；
 * - exact alarm 在 Doze + 长时间灭屏下穿透系统省电策略，到点立刻 broadcast。
 *
 * 双方在 [com.wuyi.libraryauto.core.domain.usecase.BookingCheckInMutexRegistry] 上互斥，到点
 * 同时落地的两路最多只发一次签到。
 *
 * Android 12+ 上若用户撤销 SCHEDULE_EXACT_ALARM 授权，[AlarmManager.canScheduleExactAlarms]
 * 返回 false，本类会自动降级为 noop，不影响 WorkManager 主链。
 */
class GuardExactAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager? =
        ContextCompat.getSystemService(appContext, AlarmManager::class.java)

    /**
     * 为指定 task 排一个精确闹钟，目标触发时间 = 预约开始时间 - limitSignAgo。
     *
     * @return true 表示已成功排上 exact alarm；false 表示当前不可用（无 AlarmManager / 未授权 /
     *   触发时间已过），调用方仍可依赖 WorkManager 主链。
     */
    fun schedule(
        taskId: String,
        startTimeEpochSeconds: Long,
        limitSignAgoSeconds: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val manager = alarmManager ?: return false
        if (!canScheduleExactAlarms(manager)) {
            return false
        }
        val targetEpochMillis = (startTimeEpochSeconds - limitSignAgoSeconds) * 1_000L
        val triggerAtMillis = max(targetEpochMillis, nowEpochMillis + MIN_LEAD_MILLIS)
        val intent = buildIntent(taskId)
        val pendingIntent =
            PendingIntent.getBroadcast(
                appContext,
                requestCodeFor(taskId),
                intent,
                pendingIntentFlags(updateCurrent = true),
            )
        return runCatching {
            // setExactAndAllowWhileIdle 是 Doze 下还能精确触发的 API。
            // 双轨设计意味着即使学校接口 ±1 分钟内出问题，WorkManager 那边也会重试。
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
            true
        }.getOrElse { error ->
            // 部分厂商 ROM 在「应用未启用自启动 / 后台」时会以 SecurityException 拒绝注册闹钟。
            // 这种降级直接吞掉，让 WorkManager 主链兜底，不破坏签到主流程。
            Log.w(TAG, "Failed to schedule exact alarm for $taskId: ${error.message}")
            false
        }
    }

    fun cancel(taskId: String) {
        val manager = alarmManager ?: return
        // FLAG_NO_CREATE：仅在已存在 PendingIntent 时拿到引用，避免 cancel 时反而创建新的对象。
        val existing =
            PendingIntent.getBroadcast(
                appContext,
                requestCodeFor(taskId),
                buildIntent(taskId),
                pendingIntentFlags(updateCurrent = false, noCreate = true),
            )
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
        }
    }

    fun canScheduleExactAlarms(): Boolean = alarmManager?.let(::canScheduleExactAlarms) == true

    private fun canScheduleExactAlarms(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }

    private fun buildIntent(taskId: String): Intent =
        Intent(appContext, GuardExactAlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER
            // setData 让两个相同 action 但不同 taskId 的 PendingIntent 不会被认成同一个，
            // PendingIntent 的"等价性"以 (action, data, type, class, categories) 判定。
            data = android.net.Uri.parse("$URI_SCHEME://$taskId")
            putExtra(EXTRA_TASK_ID, taskId)
        }

    private fun requestCodeFor(taskId: String): Int = taskId.hashCode()

    private fun pendingIntentFlags(
        updateCurrent: Boolean,
        noCreate: Boolean = false,
    ): Int {
        var flags = 0
        if (updateCurrent) flags = flags or PendingIntent.FLAG_UPDATE_CURRENT
        if (noCreate) flags = flags or PendingIntent.FLAG_NO_CREATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    companion object {
        const val ACTION_TRIGGER = "com.wuyi.libraryauto.action.GUARD_EXACT_ALARM_TRIGGER"
        const val EXTRA_TASK_ID = "taskId"
        private const val URI_SCHEME = "wuyi-guard-alarm"
        private const val MIN_LEAD_MILLIS = 1_000L
        private const val TAG = "GuardExactAlarmScheduler"
    }
}
