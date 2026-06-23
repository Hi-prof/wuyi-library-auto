package com.wuyi.libraryauto.core.runtime.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wuyi.libraryauto.core.runtime.worker.ReservationGuardWorker

/**
 * exact alarm 的兜底入口。被 [GuardExactAlarmScheduler] 注册的 [android.app.AlarmManager]
 * 唤醒后立即入队一个 0 延迟的 [ReservationGuardWorker]，以 [ExistingWorkPolicy.REPLACE]
 * 顶替原本仍在 delay 队列里的 GuardWorker，让签到能立刻执行。
 *
 * 不在 [onReceive] 里直接做网络/IO，因为 BroadcastReceiver 主线程窗口很短（10s 上限），
 * 把活转交给 WorkManager 比手动开 service 更稳。
 */
class GuardExactAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GuardExactAlarmScheduler.ACTION_TRIGGER) {
            return
        }
        val taskId = intent.getStringExtra(GuardExactAlarmScheduler.EXTRA_TASK_ID).orEmpty().trim()
        if (taskId.isBlank()) {
            return
        }
        val request =
            OneTimeWorkRequestBuilder<ReservationGuardWorker>()
                .setInputData(workDataOf(ReservationGuardWorker.KEY_TASK_ID to taskId))
                .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ReservationGuardWorker.uniqueWorkName(taskId),
                // REPLACE：抢占原本 delayed 的同名 work，让 GuardWorker 立刻被调度。
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to enqueue GuardWorker after alarm for $taskId: ${error.message}")
        }
    }

    private companion object {
        private const val TAG = "GuardExactAlarmReceiver"
    }
}
