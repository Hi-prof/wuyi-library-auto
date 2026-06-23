package com.wuyi.libraryauto.core.runtime.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogger
import com.wuyi.libraryauto.core.runtime.notification.NotificationFactory

class BeaconForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NotificationFactory.SCAN_NOTIFICATION_ID,
            NotificationFactory.createScanningNotification(this),
        )
        BeaconForegroundServiceController.notifyForegroundStarted()
        LocalDiagnosticLogger.info(
            source = "BeaconForegroundService",
            title = "蓝牙扫描前台服务启动",
            detailLines = listOf("startId=$startId"),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LocalDiagnosticLogger.info("BeaconForegroundService", "蓝牙扫描前台服务销毁")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, BeaconForegroundService::class.java)
    }
}
