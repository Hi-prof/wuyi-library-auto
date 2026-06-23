package com.wuyi.libraryauto.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupData
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupLoadResult
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatRoomSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeatLookupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `refresh shows loading until repository returns`() = runTest {
        val repository = DeferredSeatLookupRepository()
        val viewModel = SeatLookupViewModel(repository = repository)

        viewModel.refresh()

        assertThat(viewModel.uiState.isLoading).isTrue()
        assertThat(viewModel.uiState.statusMessage).isNull()

        repository.complete(SeatLookupLoadResult.Success(sampleLookupData()))
        advanceUntilIdle()

        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.rooms).hasSize(1)
        assertThat(viewModel.uiState.rooms.single().title).isEqualTo("三楼西区")
    }

    @Test
    fun `refresh shows chinese message when session is missing`() = runTest {
        val viewModel =
            SeatLookupViewModel(
                repository = ImmediateSeatLookupRepository(SeatLookupLoadResult.NotLoggedIn),
            )

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.rooms).isEmpty()
        assertThat(viewModel.uiState.statusMessage).isEqualTo("当前账号还没有可用认证，请先回账号页点“刷新认证”。")
    }

    @Test
    fun `refresh shows empty state when lookup returns no rooms`() = runTest {
        val viewModel =
            SeatLookupViewModel(
                repository = ImmediateSeatLookupRepository(SeatLookupLoadResult.Empty(sampleLookupData(rooms = emptyList()))),
            )

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.rooms).isEmpty()
        assertThat(viewModel.uiState.statusMessage).isEqualTo("当前条件下没有可用座位，请稍后刷新重试。")
    }

    @Test
    fun `refresh shows repository failure message in chinese`() = runTest {
        val viewModel =
            SeatLookupViewModel(
                repository = ImmediateSeatLookupRepository(SeatLookupLoadResult.Failure("查询座位失败，请稍后重试。")),
            )

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.statusMessage).isEqualTo("查询座位失败，请稍后重试。")
    }

    private fun sampleLookupData(
        rooms: List<SeatRoomSnapshot> =
            listOf(
                SeatRoomSnapshot(
                    roomName = "三楼西区",
                    storey = "3F",
                    availableCount = 2,
                    seatNumbers = listOf("301", "302"),
                    recommendedSeatNumber = "301",
                ),
            ),
    ): SeatLookupData =
        SeatLookupData(
            beginTimeEpochSeconds = 1_711_111_111,
            durationHours = 3,
            peopleCount = 1,
            rooms = rooms,
        )

    private class ImmediateSeatLookupRepository(
        private val result: SeatLookupLoadResult,
    ) : SeatLookupRepository {
        override suspend fun loadDefaultSeats(): SeatLookupLoadResult = result
    }

    private class DeferredSeatLookupRepository : SeatLookupRepository {
        private val deferred = CompletableDeferred<SeatLookupLoadResult>()

        override suspend fun loadDefaultSeats(): SeatLookupLoadResult = deferred.await()

        fun complete(result: SeatLookupLoadResult) {
            deferred.complete(result)
        }
    }
}
