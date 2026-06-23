package com.wuyi.libraryauto.core.runtime.network

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRecoveryEventBusTest {

    @Test
    fun `merged events collapse dense emissions into one event within thirty seconds`() = runTest {
        val received = mutableListOf<TriggerSource>()
        val job =
            launch {
                NetworkRecoveryEventBus
                    .mergedEvents(debounceMillis = 5_000L, sampleMillis = 30_000L)
                    .collect { source -> received += source }
            }
        runCurrent()

        NetworkRecoveryEventBus.emit(TriggerSource.NetworkRestored)
        advanceTimeBy(1_000L)
        NetworkRecoveryEventBus.emit(TriggerSource.NetworkRestored)
        advanceTimeBy(1_000L)
        NetworkRecoveryEventBus.emit(TriggerSource.NetworkRestored)
        advanceTimeBy(34_000L)
        runCurrent()

        assertThat(received).containsExactly(TriggerSource.NetworkRestored)
        job.cancel()
    }

    @Test
    fun `merged events emit different sources in order when outside sample window`() = runTest {
        val received = mutableListOf<TriggerSource>()
        val job =
            launch {
                NetworkRecoveryEventBus
                    .mergedEvents(debounceMillis = 5_000L, sampleMillis = 30_000L)
                    .collect { source -> received += source }
            }
        runCurrent()

        NetworkRecoveryEventBus.emit(TriggerSource.NetworkRestored)
        advanceTimeBy(36_000L)
        NetworkRecoveryEventBus.emit(TriggerSource.CampusAuthRecovery)
        advanceTimeBy(36_000L)
        runCurrent()

        assertThat(received)
            .containsExactly(TriggerSource.NetworkRestored, TriggerSource.CampusAuthRecovery)
            .inOrder()
        job.cancel()
    }
}
