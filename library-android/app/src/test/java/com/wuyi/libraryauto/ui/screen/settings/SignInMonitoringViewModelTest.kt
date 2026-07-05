package com.wuyi.libraryauto.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.viewmodel.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInMonitoringViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun `refresh shows placeholder when loading exceeds 200 ms`() = runTest(dispatcher.scheduler) {
        val viewModel =
            SignInMonitoringViewModel(
                source = FakeSignInMonitoringDataSource(delayMillis = 250L),
                nowEpochSeconds = { 1_000L },
            )

        runCurrent()
        assertThat(viewModel.uiState.showPlaceholder).isFalse()

        advanceTimeBy(201L)
        runCurrent()

        assertThat(viewModel.uiState.showPlaceholder).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.uiState.isLoading).isFalse()
    }

    @Test
    fun `empty audit lists expose no records message`() = runTest(dispatcher.scheduler) {
        val viewModel =
            SignInMonitoringViewModel(
                source = FakeSignInMonitoringDataSource(),
                nowEpochSeconds = { 1_000L },
            )

        advanceUntilIdle()

        assertThat(viewModel.uiState.emptyMessage).isEqualTo("暂无记录")
    }

    @Test
    fun `sensitive fields are masked after loading`() = runTest(dispatcher.scheduler) {
        val viewModel =
            SignInMonitoringViewModel(
                source =
                    FakeSignInMonitoringDataSource(
                        snapshot =
                            SignInMonitoringSnapshot(
                                signInAudits =
                                    listOf(
                                        SignInAuditDisplay(
                                            studentId = "202300001234",
                                            bookingId = "booking-abcdef",
                                            result = "成功",
                                            triggerSource = "ManualBatch",
                                        ),
                                    ),
                                beaconScanAudits = emptyList(),
                                errorAggregates = emptyList(),
                            ),
                    ),
                nowEpochSeconds = { 1_000L },
            )

        advanceUntilIdle()

        val item = viewModel.uiState.signInAudits.single()
        assertThat(item.studentId).isEqualTo("202***234")
        assertThat(item.bookingId).isEqualTo("boo***def")
    }

    private class FakeSignInMonitoringDataSource(
        private val delayMillis: Long = 0L,
        private val snapshot: SignInMonitoringSnapshot =
            SignInMonitoringSnapshot(
                signInAudits = emptyList(),
                beaconScanAudits = emptyList(),
                errorAggregates = emptyList(),
            ),
    ) : SignInMonitoringDataSource {
        override suspend fun load(
            rangeStartEpochSeconds: Long,
            rangeEndEpochSeconds: Long,
        ): SignInMonitoringSnapshot {
            if (delayMillis > 0L) {
                delay(delayMillis)
            }
            return snapshot
        }
    }
}
