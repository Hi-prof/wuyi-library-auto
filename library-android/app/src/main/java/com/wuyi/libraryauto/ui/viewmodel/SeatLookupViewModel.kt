package com.wuyi.libraryauto.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.ui.adapter.seat.SeatLookupPresentation
import com.wuyi.libraryauto.ui.adapter.seat.SeatLookupSummaryUiModel
import com.wuyi.libraryauto.ui.adapter.seat.SeatLookupUiMapper
import com.wuyi.libraryauto.ui.adapter.seat.SeatRoomUiModel
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupLoadResult
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import kotlinx.coroutines.launch

data class SeatLookupUiState(
    val isLoading: Boolean = false,
    val querySummary: SeatLookupSummaryUiModel? = null,
    val rooms: List<SeatRoomUiModel> = emptyList(),
    val catalogOnly: Boolean = false,
    val statusMessage: String? = null,
)

class SeatLookupViewModel(
    private val repository: SeatLookupRepository,
    private val uiMapper: SeatLookupUiMapper = SeatLookupUiMapper(),
) : ViewModel() {

    var uiState by mutableStateOf(SeatLookupUiState())
        private set

    fun refresh() {
        if (uiState.isLoading) {
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, statusMessage = null)

            uiState =
                when (val result = repository.loadDefaultSeats()) {
                    SeatLookupLoadResult.NotLoggedIn ->
                        SeatLookupUiState(statusMessage = "当前账号还没有可用认证，请先回账号页点“刷新认证”。")

                    is SeatLookupLoadResult.Failure ->
                        SeatLookupUiState(statusMessage = result.message)

                    is SeatLookupLoadResult.Empty ->
                        uiMapper
                            .map(result.data)
                            .toUiState(
                                isLoading = false,
                                statusMessage = result.data.notice ?: "当前条件下没有可用座位，请稍后刷新重试。",
                            )

                    is SeatLookupLoadResult.Success ->
                        uiMapper
                            .map(result.data)
                            .toUiState(statusMessage = result.data.notice)
                }
        }
    }
}

class SeatLookupViewModelFactory(
    private val repository: SeatLookupRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SeatLookupViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return SeatLookupViewModel(repository = repository) as T
    }
}

private fun SeatLookupPresentation.toUiState(
    isLoading: Boolean = false,
    statusMessage: String? = null,
): SeatLookupUiState =
    SeatLookupUiState(
        isLoading = isLoading,
        querySummary = summary,
        rooms = rooms,
        catalogOnly = catalogOnly,
        statusMessage = statusMessage,
    )
