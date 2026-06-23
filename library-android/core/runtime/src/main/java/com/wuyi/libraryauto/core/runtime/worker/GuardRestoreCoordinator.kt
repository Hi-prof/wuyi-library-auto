package com.wuyi.libraryauto.core.runtime.worker

import com.wuyi.libraryauto.core.domain.model.ReservationTaskState

internal data class RestorableGuardTask(
    val taskId: String,
    val state: ReservationTaskState,
    val startTimeEpochSeconds: Long,
    val limitSignAgoSeconds: Long,
    val limitSignBackSeconds: Long = 1_800L,
)

internal class GuardRestoreCoordinator(
    private val enqueueGuard: (RestorableGuardTask) -> Unit,
) {
    fun restore(
        tasks: List<RestorableGuardTask>,
        nowEpochSeconds: Long,
    ): Int =
        tasks.asSequence()
            .filter { shouldRestore(it, nowEpochSeconds) }
            .onEach(enqueueGuard)
            .count()

    companion object {
        fun isGuardable(state: ReservationTaskState): Boolean =
            state == ReservationTaskState.RESERVED_WAITING_SIGNIN ||
                state == ReservationTaskState.GUARD_SCHEDULED

        fun isGuardable(
            task: RestorableGuardTask,
            nowEpochSeconds: Long,
        ): Boolean =
            isGuardable(task.state) &&
                nowEpochSeconds <= restorationDeadlineEpochSeconds(task)

        fun shouldRestore(
            task: RestorableGuardTask,
            nowEpochSeconds: Long,
        ): Boolean = isGuardable(task, nowEpochSeconds)

        // BUG 3 修复：deadline 必须延伸到签到窗口关闭时刻 (startTime + limitSignBackSeconds)，
        // 之前仅用 startTime 会让开机/重装时已开窗的任务被误判为过期，丢失自动签到。
        private fun restorationDeadlineEpochSeconds(task: RestorableGuardTask): Long =
            task.startTimeEpochSeconds + task.limitSignBackSeconds
    }
}
