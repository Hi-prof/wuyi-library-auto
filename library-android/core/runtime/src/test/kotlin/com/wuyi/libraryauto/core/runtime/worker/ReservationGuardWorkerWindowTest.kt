package com.wuyi.libraryauto.core.runtime.worker

import androidx.work.ListenableWorker.Result
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.ble.BleScanOutcome
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReservationGuardWorkerWindowTest {
    @Test
    fun `short task limitSignBackSeconds fails before old hardcoded deadline`() = runTest {
        val task =
            testReservationTask(
                startTimeEpochSeconds = 1_000L,
                limitSignAgoSeconds = 0L,
                limitSignBackSeconds = 120L,
            )
        val dependencies = FakeGuardWorkerDependencies(task = task)
        var retryScheduled = false

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "task-1",
                nowEpochSeconds = 2_500L,
                dependencies = dependencies,
                scheduleRetry = { _, _ -> retryScheduled = true },
            )

        assertThat(result).isEqualTo(Result.success())
        assertThat(dependencies.savedTask?.state).isEqualTo(ReservationTaskState.FAILED_MANUAL_ACTION)
        assertThat(dependencies.loginCallCount).isEqualTo(0)
        assertThat(retryScheduled).isFalse()
    }

    @Test
    fun `long task limitSignBackSeconds keeps guard scheduled until task deadline`() = runTest {
        val task =
            testReservationTask(
                startTimeEpochSeconds = 1_000L,
                limitSignAgoSeconds = 0L,
                limitSignBackSeconds = 2_000L,
            )
        val dependencies =
            FakeGuardWorkerDependencies(
                task = task,
                scanOutcome =
                    BleScanOutcome.Timeout(
                        seenMinors = emptyList(),
                        durationMillis = 8_000L,
                    ),
            )
        var retryAtEpochSeconds: Long? = null

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "task-1",
                nowEpochSeconds = 2_500L,
                dependencies = dependencies,
                scheduleRetry = { _, runAtEpochSeconds -> retryAtEpochSeconds = runAtEpochSeconds },
            )

        assertThat(result).isEqualTo(Result.success())
        assertThat(dependencies.savedTask?.state).isEqualTo(ReservationTaskState.GUARD_SCHEDULED)
        assertThat(retryAtEpochSeconds).isEqualTo(2_560L)
    }
}
