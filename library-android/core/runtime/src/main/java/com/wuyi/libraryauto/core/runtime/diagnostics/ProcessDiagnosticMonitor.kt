package com.wuyi.libraryauto.core.runtime.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ProcessDiagnosticMonitor {
    private const val PREFERENCES_NAME = "library_auto_process_diagnostics"
    private const val HEARTBEAT_INTERVAL_MILLIS = 60_000L
    private const val PREVIOUS_PROCESS_THRESHOLD_MILLIS = 90_000L
    private const val MAX_STACK_TRACE_CHARS = 6_000
    private const val KEY_STARTED_AT = "started_at_"
    private const val KEY_HEARTBEAT_AT = "heartbeat_at_"
    private const val KEY_LAST_CRASH_AT = "last_crash_at_"

    @Volatile
    private var installed = false

    fun install(
        context: Context,
        processName: String = resolveProcessName(context),
    ) {
        val appContext = context.applicationContext
        LocalDiagnosticLogger.install(appContext)
        if (installed) {
            return
        }
        installed = true

        val safeProcessName = processName.ifBlank { "unknown" }
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previousStartedAt = preferences.getLong(KEY_STARTED_AT + safeProcessName, 0L)
        val previousHeartbeatAt = preferences.getLong(KEY_HEARTBEAT_AT + safeProcessName, 0L)
        val previousCrashAt = preferences.getLong(KEY_LAST_CRASH_AT + safeProcessName, 0L)

        if (previousCrashAt >= previousStartedAt && previousCrashAt > 0L) {
            LocalDiagnosticLogger.warn(
                source = "Process",
                title = "上次进程发生未捕获异常",
                detailLines =
                    listOf(
                        "process=$safeProcessName",
                        "crashAt=${formatMillis(previousCrashAt)}",
                    ),
            )
        } else if (previousStartedAt > 0L &&
            previousHeartbeatAt > 0L &&
            now - previousHeartbeatAt > PREVIOUS_PROCESS_THRESHOLD_MILLIS
        ) {
            LocalDiagnosticLogger.warn(
                source = "Process",
                title = "上次进程没有正常收尾记录",
                detailLines =
                    listOf(
                        "process=$safeProcessName",
                        "previousStart=${formatMillis(previousStartedAt)}",
                        "lastHeartbeat=${formatMillis(previousHeartbeatAt)}",
                        "note=可能是系统回收、用户划掉后台、厂商管家清理或进程崩溃。",
                    ),
            )
        }

        preferences.edit()
            .putLong(KEY_STARTED_AT + safeProcessName, now)
            .putLong(KEY_HEARTBEAT_AT + safeProcessName, now)
            .apply()
        LocalDiagnosticLogger.info(
            source = "Process",
            title = "进程启动",
            detailLines = listOf("process=$safeProcessName", "pid=${Process.myPid()}"),
        )

        installUncaughtExceptionHandler(preferences, safeProcessName)
        startHeartbeat(preferences, safeProcessName)
    }

    fun resolveProcessName(context: Context): String {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return ""
        val pid = Process.myPid()
        return activityManager.runningAppProcesses
            ?.firstOrNull { process -> process.pid == pid }
            ?.processName
            .orEmpty()
    }

    private fun installUncaughtExceptionHandler(
        preferences: android.content.SharedPreferences,
        processName: String,
    ) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val now = System.currentTimeMillis()
            preferences.edit()
                .putLong(KEY_LAST_CRASH_AT + processName, now)
                .putLong(KEY_HEARTBEAT_AT + processName, now)
                .apply()
            LocalDiagnosticLogger.error(
                source = "Crash",
                title = "未捕获异常",
                detailLines =
                    listOf(
                        "process=$processName",
                        "thread=${thread.name}",
                        "type=${throwable::class.java.name}",
                        "message=${throwable.message.orEmpty()}",
                        stackTraceText(throwable),
                    ),
            )
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun startHeartbeat(
        preferences: android.content.SharedPreferences,
        processName: String,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val heartbeat =
            object : Runnable {
                override fun run() {
                    preferences.edit()
                        .putLong(KEY_HEARTBEAT_AT + processName, System.currentTimeMillis())
                        .apply()
                    handler.postDelayed(this, HEARTBEAT_INTERVAL_MILLIS)
                }
            }
        handler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MILLIS)
    }

    private fun stackTraceText(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().take(MAX_STACK_TRACE_CHARS)
    }

    private fun formatMillis(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .format(timeFormatter)

    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
