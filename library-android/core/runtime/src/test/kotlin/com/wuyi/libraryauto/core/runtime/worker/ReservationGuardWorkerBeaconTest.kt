package com.wuyi.libraryauto.core.runtime.worker

import androidx.work.ListenableWorker.Result
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.ble.BleScanOutcome
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.model.SignInError
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReservationGuardWorkerBeaconTest {
    @Test
    fun `scan miss skips checkIn and keeps guard scheduled`() = runTest {
        val dependencies =
            FakeGuardWorkerDependencies(
                scanOutcome =
                    BleScanOutcome.Timeout(
                        seenMinors = listOf(1, 2),
                        durationMillis = 8_000L,
                    ),
            )
        var retryAtEpochSeconds: Long? = null

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "task-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
                scheduleRetry = { _, runAtEpochSeconds -> retryAtEpochSeconds = runAtEpochSeconds },
            )

        assertThat(result).isEqualTo(Result.success())
        assertThat(dependencies.checkInCallCount).isEqualTo(0)
        assertThat(dependencies.savedTask?.state).isEqualTo(ReservationTaskState.GUARD_SCHEDULED)
        assertThat(retryAtEpochSeconds).isEqualTo(1_712_800_060L)
        assertThat(dependencies.signInAuditRecords.single().signInError)
            .isEqualTo(SignInError.BeaconNotMatched)
    }

    @Test
    fun `scan match and successful checkIn completes task`() = runTest {
        val dependencies = FakeGuardWorkerDependencies()

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "task-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertThat(result).isEqualTo(Result.success())
        assertThat(dependencies.checkInCallCount).isEqualTo(1)
        assertThat(dependencies.lastCheckInBookingId).isEqualTo("booking-1")
        assertThat(dependencies.savedTask?.state).isEqualTo(ReservationTaskState.SIGNIN_SUCCESS)
        assertThat(dependencies.signInAuditRecords.single().signInError).isNull()
        assertThat(dependencies.signInAuditRecords.single().matchedMinor).isEqualTo(12)
    }

    @Test
    fun `scan match and http 401 refreshes login once then requeues`() = runTest {
        val dependencies =
            FakeGuardWorkerDependencies(
                checkInResult =
                    SeatBookingActionResult(
                        bookingId = "booking-1",
                        httpStatus = 401,
                        rawMessage = "Unauthorized",
                        signInError = null,
                    ),
            )
        var retryAtEpochSeconds: Long? = null

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "task-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
                scheduleRetry = { _, runAtEpochSeconds -> retryAtEpochSeconds = runAtEpochSeconds },
            )

        assertThat(result).isEqualTo(Result.success())
        assertThat(dependencies.checkInCallCount).isEqualTo(1)
        assertThat(dependencies.refreshLoginCallCount).isEqualTo(1)
        assertThat(dependencies.savedTask?.state).isEqualTo(ReservationTaskState.GUARD_SCHEDULED)
        assertThat(retryAtEpochSeconds).isEqualTo(1_712_800_060L)
        assertThat(dependencies.signInAuditRecords.single().signInError)
            .isEqualTo(SignInError.ServerRejected)
    }

    @Test
    fun `okhttp 401 exception triggers refreshLogin and requeues`() = runTest {
        // BUG 4 回归测试：真实路径下 OkHttp 把 4xx 抛成 HttpRequestException，
        // GuardWorker 必须识别 statusCode 并触发 refreshLogin，而不是误当成 NetworkError。
        val dependencies =
            FakeGuardWorkerDependencies(
                checkInOverride = {
                    throw com.wuyi.libraryauto.core.network.http.HttpRequestException(
                        url = "https://example.com/Seat/Index/checkIn",
                        statusCode = 401,
                        responseBody = "",
                    )
                },
            )
        var retryAtEpochSeconds: Long? = null

        val result =
            ReservationGuardWorker.executeOnce(
                taskId = "task-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
                scheduleRetry = { _, runAtEpochSeconds -> retryAtEpochSeconds = runAtEpochSeconds },
            )

        assertThat(result).isEqualTo(Result.success())
        assertThat(dependencies.checkInCallCount).isEqualTo(1)
        assertThat(dependencies.refreshLoginCallCount).isEqualTo(1)
        assertThat(dependencies.savedTask?.state).isEqualTo(ReservationTaskState.GUARD_SCHEDULED)
        assertThat(retryAtEpochSeconds).isEqualTo(1_712_800_060L)
        val audit = dependencies.signInAuditRecords.single()
        assertThat(audit.signInError).isEqualTo(SignInError.ServerRejected)
        assertThat(audit.httpStatus).isEqualTo(401)
    }

    @Test
    fun `concurrent workers serialize login calls`() = runTest {
        val tracker = LoginConcurrencyTracker(delayMillis = 100L)
        val firstDependencies = FakeGuardWorkerDependencies(loginConcurrencyTracker = tracker)
        val secondDependencies = FakeGuardWorkerDependencies(loginConcurrencyTracker = tracker)

        awaitAll(
            async(Dispatchers.Default) {
                ReservationGuardWorker.executeOnce(
                    taskId = "task-1",
                    nowEpochSeconds = 1_712_800_000L,
                    dependencies = firstDependencies,
                )
            },
            async(Dispatchers.Default) {
                ReservationGuardWorker.executeOnce(
                    taskId = "task-2",
                    nowEpochSeconds = 1_712_800_000L,
                    dependencies = secondDependencies,
                )
            },
        )

        assertThat(firstDependencies.loginCallCount).isEqualTo(1)
        assertThat(secondDependencies.loginCallCount).isEqualTo(1)
        assertThat(tracker.maxConcurrentCalls).isEqualTo(1)
    }
}
