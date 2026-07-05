package com.wuyi.libraryauto.core.runtime.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAccountResult
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInAccountStatus
import com.wuyi.libraryauto.core.domain.usecase.PeriodicCheckInSummary
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PeriodicCheckInWorkerTest {

    @Test
    fun `buildPeriodicRequest uses thirty minute period ten minute flex without network constraint`() {
        val request = PeriodicCheckInWorker.buildPeriodicRequest()

        assertThat(request.workSpec.intervalDuration).isEqualTo(TimeUnit.MINUTES.toMillis(30))
        assertThat(request.workSpec.flexDuration).isEqualTo(TimeUnit.MINUTES.toMillis(10))
        // Keep scheduling independent from connectivity; request failures are handled by the worker and backoff.
        assertThat(request.workSpec.constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(request.workSpec.backoffPolicy).isEqualTo(BackoffPolicy.LINEAR)
        assertThat(request.workSpec.backoffDelayDuration).isEqualTo(TimeUnit.SECONDS.toMillis(60))
    }

    @Test
    fun `enqueuePeriodicWork uses unique periodic work with keep policy`() {
        val enqueuer = RecordingEnqueuer()
        val request = PeriodicCheckInWorker.buildPeriodicRequest()

        PeriodicCheckInWorker.enqueuePeriodicWork(enqueuer, request)
        PeriodicCheckInWorker.enqueuePeriodicWork(enqueuer, request)

        assertThat(enqueuer.calls).hasSize(2)
        assertThat(enqueuer.calls.map { it.uniqueWorkName })
            .containsExactly(PeriodicCheckInWorker.UNIQUE_NAME, PeriodicCheckInWorker.UNIQUE_NAME)
        assertThat(enqueuer.calls.map { it.policy })
            .containsExactly(ExistingPeriodicWorkPolicy.KEEP, ExistingPeriodicWorkPolicy.KEEP)
    }

    @Test
    fun `runOnce stops a slow round at timeout updates heartbeat and retries`() = runTest {
        val runner = HangingRunner()
        val heartbeat = RecordingHeartbeatWriter()

        val result =
            PeriodicCheckInWorker.runOnce(
                source = TriggerSource.PeriodicMonitor,
                timeoutMillis = 100L,
                runner = runner,
                heartbeatWriter = heartbeat,
                runGate = PeriodicCheckInRunGate(),
                nowEpochSeconds = { 1_234L },
            )

        assertThat(runner.started.isCompleted).isTrue()
        assertThat(heartbeat.heartbeats).containsExactly(1_234L)
        assertThat(result).isEqualTo(Result.retry())
    }

    @Test
    fun `runOnce retries when reservation attempts fail`() = runTest {
        val runner =
            FixedSummaryRunner(
                PeriodicCheckInSummary(
                    listOf(
                        PeriodicCheckInAccountResult(
                            studentId = "20230001",
                            status = PeriodicCheckInAccountStatus.Completed,
                            attemptedReservations = 1,
                            skippedReservations = 0,
                            failedReservations = 1,
                        ),
                    ),
                ),
            )
        val heartbeat = RecordingHeartbeatWriter()

        val result =
            PeriodicCheckInWorker.runOnce(
                source = TriggerSource.PeriodicMonitor,
                timeoutMillis = PeriodicCheckInWorker.RUN_TIMEOUT_MILLIS,
                runner = runner,
                heartbeatWriter = heartbeat,
                runGate = PeriodicCheckInRunGate(),
                nowEpochSeconds = { 1_500L },
            )

        assertThat(heartbeat.heartbeats).containsExactly(1_500L)
        assertThat(result).isEqualTo(Result.retry())
    }

    @Test
    fun `runOnce skips periodic scan when batch check in is running`() = runTest {
        val runner = RecordingRunner()
        val heartbeat = RecordingHeartbeatWriter()
        val runGate = PeriodicCheckInRunGate().apply { markBatchCheckInRunning(true) }

        val result =
            PeriodicCheckInWorker.runOnce(
                source = TriggerSource.PeriodicMonitor,
                timeoutMillis = PeriodicCheckInWorker.RUN_TIMEOUT_MILLIS,
                runner = runner,
                heartbeatWriter = heartbeat,
                runGate = runGate,
                nowEpochSeconds = { 2_000L },
            )

        assertThat(runner.sources).isEmpty()
        assertThat(heartbeat.heartbeats).containsExactly(2_000L)
        assertThat(result).isEqualTo(Result.success())
    }

    private class RecordingEnqueuer : PeriodicCheckInEnqueuer {
        val calls = mutableListOf<Call>()

        override fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            calls += Call(uniqueWorkName, policy, request)
        }
    }

    private data class Call(
        val uniqueWorkName: String,
        val policy: ExistingPeriodicWorkPolicy,
        val request: PeriodicWorkRequest,
    )

    private class RecordingRunner : PeriodicCheckInRunner {
        val sources = mutableListOf<TriggerSource>()

        override suspend fun run(source: TriggerSource): PeriodicCheckInSummary {
            sources += source
            return PeriodicCheckInSummary(emptyList())
        }
    }

    private class HangingRunner : PeriodicCheckInRunner {
        val started = CompletableDeferred<Unit>()

        override suspend fun run(source: TriggerSource): PeriodicCheckInSummary {
            started.complete(Unit)
            delay(Long.MAX_VALUE)
            return PeriodicCheckInSummary(emptyList())
        }
    }

    private class FixedSummaryRunner(
        private val summary: PeriodicCheckInSummary,
    ) : PeriodicCheckInRunner {
        override suspend fun run(source: TriggerSource): PeriodicCheckInSummary = summary
    }

    private class RecordingHeartbeatWriter : PeriodicCheckInHeartbeatWriter {
        val heartbeats = mutableListOf<Long>()

        override fun markPeriodicCheckInHeartbeat(epochSeconds: Long) {
            heartbeats += epochSeconds
        }
    }
}
