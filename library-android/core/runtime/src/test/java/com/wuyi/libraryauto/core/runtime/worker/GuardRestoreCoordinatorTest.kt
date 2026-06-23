package com.wuyi.libraryauto.core.runtime.worker

import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class GuardRestoreCoordinatorTest {

    @Test
    fun `restore re-enqueues only guardable tasks`() {
        val restored = mutableListOf<RestorableGuardTask>()
        val coordinator = GuardRestoreCoordinator(enqueueGuard = restored::add)

        val restoredCount =
            coordinator.restore(
                tasks = listOf(
                    RestorableGuardTask(
                        taskId = "guard-1",
                        state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                        startTimeEpochSeconds = 1_712_800_000L,
                        limitSignAgoSeconds = 1_800L,
                    ),
                    RestorableGuardTask(
                        taskId = "guard-2",
                        state = ReservationTaskState.GUARD_SCHEDULED,
                        startTimeEpochSeconds = 1_712_900_000L,
                        limitSignAgoSeconds = 900L,
                    ),
                    RestorableGuardTask(
                        taskId = "skip-1",
                        state = ReservationTaskState.SIGNIN_SUCCESS,
                        startTimeEpochSeconds = 1_713_000_000L,
                        limitSignAgoSeconds = 600L,
                    ),
                ),
                nowEpochSeconds = 1_712_798_200L,
            )

        assertEquals(2, restoredCount)
        assertEquals(
            listOf(
                RestorableGuardTask(
                    taskId = "guard-1",
                    state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                    startTimeEpochSeconds = 1_712_800_000L,
                    limitSignAgoSeconds = 1_800L,
                ),
                RestorableGuardTask(
                    taskId = "guard-2",
                    state = ReservationTaskState.GUARD_SCHEDULED,
                    startTimeEpochSeconds = 1_712_900_000L,
                    limitSignAgoSeconds = 900L,
                ),
            ),
            restored,
        )
    }

    @Test
    fun `restore skips expired guard task`() {
        val restored = mutableListOf<RestorableGuardTask>()
        val coordinator = GuardRestoreCoordinator(enqueueGuard = restored::add)

        val restoredCount =
            coordinator.restore(
                tasks = listOf(
                    RestorableGuardTask(
                        taskId = "expired",
                        state = ReservationTaskState.GUARD_SCHEDULED,
                        startTimeEpochSeconds = 2_000L,
                        limitSignAgoSeconds = 300L,
                        limitSignBackSeconds = 200L,
                    ),
                    RestorableGuardTask(
                        taskId = "active",
                        state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                        startTimeEpochSeconds = 2_500L,
                        limitSignAgoSeconds = 300L,
                        limitSignBackSeconds = 200L,
                    ),
                ),
                nowEpochSeconds = 2_401L,
            )

        assertEquals(1, restoredCount)
        assertEquals(
            listOf(
                RestorableGuardTask(
                    taskId = "active",
                    state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                    startTimeEpochSeconds = 2_500L,
                    limitSignAgoSeconds = 300L,
                    limitSignBackSeconds = 200L,
                ),
            ),
            restored,
        )
    }

    @Test
    fun `restore keeps task whose start time has passed but is still within signback window`() {
        // BUG 3 回归测试：startTime 已过但仍在 startTime + limitSignBackSeconds 之内时必须恢复 GuardWorker。
        val restored = mutableListOf<RestorableGuardTask>()
        val coordinator = GuardRestoreCoordinator(enqueueGuard = restored::add)
        val task =
            RestorableGuardTask(
                taskId = "post-start-but-in-window",
                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                startTimeEpochSeconds = 1_000L,
                limitSignAgoSeconds = 300L,
                limitSignBackSeconds = 1_800L,
            )

        val restoredCount =
            coordinator.restore(
                tasks = listOf(task),
                nowEpochSeconds = 1_500L,
            )

        assertEquals(1, restoredCount)
        assertEquals(listOf(task), restored)
    }

    @Test
    fun `restore keeps task after signin window opens but before start time`() {
        val restored = mutableListOf<RestorableGuardTask>()
        val coordinator = GuardRestoreCoordinator(enqueueGuard = restored::add)
        val task =
            RestorableGuardTask(
                taskId = "window-open",
                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                startTimeEpochSeconds = 2_000L,
                limitSignAgoSeconds = 300L,
            )

        val restoredCount =
            coordinator.restore(
                tasks = listOf(task),
                nowEpochSeconds = 1_800L,
            )

        assertEquals(1, restoredCount)
        assertEquals(listOf(task), restored)
    }
}
