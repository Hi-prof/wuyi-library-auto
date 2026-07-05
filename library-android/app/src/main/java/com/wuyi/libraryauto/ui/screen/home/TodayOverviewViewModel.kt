package com.wuyi.libraryauto.ui.screen.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.ui.repository.home.TodayOverviewRepository
import com.wuyi.libraryauto.ui.repository.home.TodayOverviewSnapshot
import com.wuyi.libraryauto.ui.repository.seat.BatchCheckInResult
import com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TodayOverviewViewModel(
    private val repository: TodayOverviewRepository,
    private val seatDisplayRepository: SeatDisplayRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    var uiState by mutableStateOf(TodayOverviewUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        if (uiState.isBusy()) {
            return
        }
        viewModelScope.launch {
            val previous = uiState.snapshot
            uiState =
                uiState.copy(
                    isLoading = true,
                    errorMessage = "",
                    actionMessage = "",
                )
            val result = loadOverviewSnapshot()
            uiState =
                result.fold(
                    onSuccess = { snapshot ->
                        TodayOverviewUiState(
                            isLoading = false,
                            snapshot = snapshot,
                        )
                    },
                    onFailure = { error ->
                        TodayOverviewUiState(
                            isLoading = false,
                            snapshot = previous,
                            errorMessage = error.message ?: "加载今日数据失败",
                        )
                    },
                )
        }
    }

    fun checkReservations() {
        if (uiState.isBusy()) {
            return
        }
        viewModelScope.launch {
            val previous = uiState.snapshot
            uiState =
                uiState.copy(
                    isCheckingReservations = true,
                    errorMessage = "",
                    actionMessage = "",
                )
            val checkResult =
                runCatching {
                    withContext(ioDispatcher) {
                        checkReservationsInternal()
                    }
                }
            val overviewResult = loadOverviewSnapshot()
            uiState =
                TodayOverviewUiState(
                    snapshot = overviewResult.getOrDefault(previous),
                    errorMessage =
                        overviewResult.exceptionOrNull()?.message
                            ?: checkResult.exceptionOrNull()?.message.orEmpty(),
                    actionMessage =
                        checkResult.fold(
                            onSuccess = ReservationCheckReport::toDisplayMessage,
                            onFailure = { "一键检查预约失败" },
                        ),
                )
        }
    }

    fun checkInAll() {
        if (uiState.isBusy()) {
            return
        }
        viewModelScope.launch {
            val previous = uiState.snapshot
            uiState =
                uiState.copy(
                    isSigningIn = true,
                    errorMessage = "",
                    actionMessage = "",
                )
            val actionResult =
                runCatching {
                    withContext(ioDispatcher) {
                        val checkReport = checkReservationsInternal()
                        val signInResult = seatDisplayRepository.batchCheckIn()
                        SignInActionReport(
                            checkReport = checkReport,
                            signInResult = signInResult,
                        )
                    }
                }
            val overviewResult = loadOverviewSnapshot()
            uiState =
                TodayOverviewUiState(
                    snapshot = overviewResult.getOrDefault(previous),
                    errorMessage =
                        overviewResult.exceptionOrNull()?.message
                            ?: actionResult.exceptionOrNull()?.message.orEmpty(),
                    actionMessage =
                        actionResult.fold(
                            onSuccess = SignInActionReport::toDisplayMessage,
                            onFailure = { "一键签到失败" },
                        ),
                )
        }
    }

    private suspend fun loadOverviewSnapshot(): Result<TodayOverviewSnapshot> =
        runCatching {
            withContext(ioDispatcher) {
                repository.load()
            }
        }

    private suspend fun checkReservationsInternal(): ReservationCheckReport {
        val cards = seatDisplayRepository.readCachedFromLocal()
        var success = 0
        var failed = 0
        cards.forEach { card ->
            val refreshed =
                withTimeoutOrNull(SINGLE_REFRESH_TIMEOUT_MILLIS) {
                    seatDisplayRepository.fetchSnapshot(card.studentId)
                }
            if (refreshed != null && refreshed.failureMessage.isNullOrBlank()) {
                success += 1
            } else {
                failed += 1
            }
        }
        return ReservationCheckReport(
            total = cards.size,
            success = success,
            failed = failed,
        )
    }

    private fun TodayOverviewUiState.isBusy(): Boolean =
        isLoading || isCheckingReservations || isSigningIn

    private companion object {
        const val SINGLE_REFRESH_TIMEOUT_MILLIS = 15_000L
    }
}

data class TodayOverviewUiState(
    val isLoading: Boolean = false,
    val isCheckingReservations: Boolean = false,
    val isSigningIn: Boolean = false,
    val snapshot: TodayOverviewSnapshot? = null,
    val errorMessage: String = "",
    val actionMessage: String = "",
)

class TodayOverviewViewModelFactory(
    private val repository: TodayOverviewRepository,
    private val seatDisplayRepository: SeatDisplayRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TodayOverviewViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return TodayOverviewViewModel(
            repository = repository,
            seatDisplayRepository = seatDisplayRepository,
        ) as T
    }
}

private data class ReservationCheckReport(
    val total: Int,
    val success: Int,
    val failed: Int,
)

private data class SignInActionReport(
    val checkReport: ReservationCheckReport,
    val signInResult: BatchCheckInResult,
)

private fun ReservationCheckReport.toDisplayMessage(): String =
    if (total == 0) {
        "暂无账号可检查"
    } else {
        "预约检查完成：成功 $success 个，失败 $failed 个"
    }

private fun SignInActionReport.toDisplayMessage(): String =
    if (signInResult.total == 0) {
        "${checkReport.toDisplayMessage()}，没有待签到预约"
    } else {
        "一键签到完成：成功 ${signInResult.success} 个，失败 ${signInResult.failed} 个"
    }
