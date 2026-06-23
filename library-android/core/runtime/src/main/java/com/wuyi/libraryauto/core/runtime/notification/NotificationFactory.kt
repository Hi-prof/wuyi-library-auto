package com.wuyi.libraryauto.core.runtime.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationFactory {
    const val SCAN_CHANNEL_ID = "beacon-scan"
    const val SCAN_NOTIFICATION_ID = 1001
    const val GUARD_SCHEDULER_CHANNEL_ID = "guard-scheduler"
    const val GUARD_SCHEDULER_NOTIFICATION_ID = 1002

    fun createScanningNotification(context: Context): Notification {
        ensureScanChannel(context)
        val builder = NotificationCompat.Builder(context, SCAN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("小鱼签到守护")
            .setContentText("正在扫描周边 iBeacon")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        createLaunchPendingIntent(context)?.let { pendingIntent ->
            builder.setContentIntent(pendingIntent)
        }

        return builder.build()
    }

    /**
     * 创建/更新「常驻调度守护」前台通知。
     *
     * 该通知由 [com.wuyi.libraryauto.core.runtime.service.GuardSchedulerService] 持有：
     * 服务启动时 setForegroundService 用它换取常驻进程身份；运行期间用 NotificationManager
     * 直接 notify 同一 ID 来更新 contentText（活跃任务数变化）。
     *
     * @param activeTaskCount 当前正在守护中的预约数量；0 表示无活跃任务，文案给出回落说明。
     */
    fun createGuardSchedulerNotification(
        context: Context,
        activeTaskCount: Int,
    ): Notification {
        ensureGuardSchedulerChannel(context)
        val text =
            if (activeTaskCount > 0) {
                "正在守护 $activeTaskCount 条预约，到点自动签到"
            } else {
                "暂无待签到预约，登录或预约后自动接管"
            }
        val builder = NotificationCompat.Builder(context, GUARD_SCHEDULER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("小鱼签到守护")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)

        createLaunchPendingIntent(context)?.let { pendingIntent ->
            builder.setContentIntent(pendingIntent)
        }
        return builder.build()
    }

    private fun ensureScanChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            SCAN_CHANNEL_ID,
            "小鱼签到守护",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun ensureGuardSchedulerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                GUARD_SCHEDULER_CHANNEL_ID,
                "签到调度守护",
                // MIN：最低优先级，不弹横幅、不响铃，仅在通知中心保留一条入口。
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "保持后台调度运行，确保到点自动签到不被系统延后。"
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun createLaunchPendingIntent(context: Context): PendingIntent? {
        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
}
