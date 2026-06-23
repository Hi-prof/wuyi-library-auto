package com.wuyi.libraryauto.core.runtime.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequest
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogHeartbeatWriter
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogState
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogStateRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchdogWorkerBackoffTest {

    @Test
    fun `buildPeriodicRequest uses six hour period thirty minute flex and linear backoff`() {
        val request = WatchdogWorker.buildPeriodicRequest()

        assertThat(request.workSpec.intervalDuration).isEqualTo(TimeUnit.HOURS.toMillis(6))
        assertThat(request.workSpec.flexDuration).isEqualTo(TimeUnit.MINUTES.toMillis(30))
        assertThat(request.workSpec.backoffPolicy).isEqualTo(BackoffPolicy.LINEAR)
        assertThat(request.workSpec.backoffDelayDuration).isEqualTo(TimeUnit.MINUTES.toMillis(10))
    }

    @Test
    fun `enqueuePeriodicWork uses watchdog unique name with keep policy`() {
        val enqueuer = RecordingEnqueuer()
        val request = WatchdogWorker.buildPeriodicRequest()

        WatchdogWorker.enqueuePeriodicWork(enqueuer, request)

        assertThat(enqueuer.calls).hasSize(1)
        assertThat(enqueuer.calls.single().uniqueWorkName).isEqualTo(WatchdogWorker.UNIQUE_NAME)
        assertThat(enqueuer.calls.single().policy).isEqualTo(ExistingPeriodicWorkPolicy.KEEP)
    }

    @Test
    fun `runOnce schedules missing work and records degraded state`() = runTest {
        val inspector = FakeInspector(missingPeriodicCheckIn())
        val scheduler = RecordingRecoveryScheduler()
        val stateRepository = FakeStateRepository(WatchdogState.Healthy)
        val heartbeatWriter = RecordingHeartbeatWriter()

        val result = WatchdogWorker.runOnce(
            inspector = inspector,
            recoveryScheduler = scheduler,
            stateRepository = stateRepository,
            heartbeatWriter = heartbeatWriter,
            nowEpochSeconds = { 1_000L },
        )

        assertThat(result).isEqualTo(Result.success())
        assertThat(scheduler.calls).containsExactly(
            RecoveryCall(missingPeriodicCheckIn(), 1_000L),
        )
        assertThat(stateRepository.state).isEqualTo(WatchdogState.Degraded(1))
        assertThat(heartbeatWriter.heartbeats).containsExactly(1_000L)
    }

    @Test
    fun `runOnce backs off for twelve hours after third consecutive miss`() = runTest {
        val stateRepository = FakeStateRepository(WatchdogState.Degraded(2))

        WatchdogWorker.runOnce(
            inspector = FakeInspector(missingPeriodicCheckIn()),
            recoveryScheduler = RecordingRecoveryScheduler(),
            stateRepository = stateRepository,
            heartbeatWriter = RecordingHeartbeatWriter(),
            nowEpochSeconds = { 2_000L },
        )

        assertThat(stateRepository.state)
            .isEqualTo(
                WatchdogState.Backoff(
                    backoffStartEpochSeconds = 2_000L,
                    backoffDurationSeconds = WatchdogState.Backoff.DEFAULT_BACKOFF_SECONDS,
                ),
            )
    }

    @Test
    fun `runOnce resets state when all required work is ready`() = runTest {
        val scheduler = RecordingRecoveryScheduler()
        val stateRepository = FakeStateRepository(WatchdogState.Degraded(2))
        val heartbeatWriter = RecordingHeartbeatWriter()

        val result = WatchdogWorker.runOnce(
            inspector = FakeInspector(allReady()),
            recoveryScheduler = scheduler,
            stateRepository = stateRepository,
            heartbeatWriter = heartbeatWriter,
            nowEpochSeconds = { 3_000L },
        )

        assertThat(result).isEqualTo(Result.success())
        assertThat(scheduler.calls).isEmpty()
        assertThat(stateRepository.state).isEqualTo(WatchdogState.Healthy)
        assertThat(heartbeatWriter.heartbeats).containsExactly(3_000L)
    }

    private fun allReady(): WatchdogInspection =
        WatchdogInspection(
            periodicCheckInReady = true,
            watchdogReady = true,
            recentGuardWorkersReady = true,
            guardServiceReady = true,
        )

    private fun missingPeriodicCheckIn(): WatchdogInspection =
        WatchdogInspection(
            periodicCheckInReady = false,
            watchdogReady = true,
            recentGuardWorkersReady = true,
            guardServiceReady = true,
        )

    private class RecordingEnqueuer : WatchdogEnqueuer {
        val calls = mutableListOf<Call>()

        override fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            calls += Call(uniqueWorkName, policy, request)
        }

        data class Call(
            val uniqueWorkName: String,
            val policy: ExistingPeriodicWorkPolicy,
            val request: PeriodicWorkRequest,
        )
    }

    private class FakeInspector(
        private val inspection: WatchdogInspection,
    ) : WatchdogWorkInspector {
        override suspend fun inspect(nowEpochSeconds: Long): WatchdogInspection = inspection
    }

    private class RecordingRecoveryScheduler : WatchdogRecoveryScheduler {
        val calls = mutableListOf<RecoveryCall>()

        override suspend fun ensureScheduled(
            inspection: WatchdogInspection,
            nowEpochSeconds: Long,
        ) {
            calls += RecoveryCall(inspection, nowEpochSeconds)
        }
    }

    private data class RecoveryCall(
        val inspection: WatchdogInspection,
        val nowEpochSeconds: Long,
    )

    private class FakeStateRepository(
        initialState: WatchdogState,
    ) : WatchdogStateRepository {
        var state: WatchdogState = initialState

        override fun read(): WatchdogState = state

        override fun update(state: WatchdogState) {
            this.state = state
        }

        override fun reset() {
            state = WatchdogState.Healthy
        }
    }

    private class RecordingHeartbeatWriter : WatchdogHeartbeatWriter {
        val heartbeats = mutableListOf<Long>()

        override fun markWatchdogHeartbeat(epochSeconds: Long) {
            heartbeats += epochSeconds
        }
    }
}
