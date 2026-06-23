package com.wuyi.libraryauto.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepository
import com.wuyi.libraryauto.ui.screen.seat.SeatDisplayCardUiState
import com.wuyi.libraryauto.ui.screen.seat.SeatDisplayUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SeatDisplayViewModel(
    private val repository: SeatDisplayRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SeatDisplayUiState())
    val uiState: StateFlow<SeatDisplayUiState> = _uiState.asStateFlow()

    private val refreshMutex = Mutex()
    private val singleRefreshing = mutableSetOf<String>()
    private var refreshAllJob: Job? = null

    fun loadInitialSnapshot() {
        viewModelScope.launch {
            val cards = withContext(ioDispatcher) { repository.readCachedFromLocal() }
            _uiState.update {
                it.copy(
                    cards = cards,
                    emptyHint = if (cards.isEmpty()) "暂无账号座位状态，先添加账号。" else "",
                )
            }
        }
    }

    fun refreshAll() {
        if (refreshAllJob?.isActive == true) {
            return
        }
        refreshAllJob =
            viewModelScope.launch {
                if (!refreshMutex.tryLock()) {
                    return@launch
                }
                try {
                    _uiState.update { it.copy(isRefreshingAll = true) }
                    val cards = withContext(ioDispatcher) { repository.readCachedFromLocal() }
                    _uiState.update { it.copy(cards = cards) }
                    val completed =
                        withTimeoutOrNull(REFRESH_ALL_TIMEOUT_MILLIS) {
                            cards.forEach { card ->
                                val refreshed =
                                    withTimeoutOrNull(SINGLE_REFRESH_TIMEOUT_MILLIS) {
                                        withContext(ioDispatcher) { repository.fetchSnapshot(card.studentId) }
                                    } ?: card.copy(
                                        statusLabel = "刷新超时",
                                        failureMessage = "刷新超时",
                                    )
                                replaceCard(refreshed)
                            }
                            true
                        } == true
                    if (!completed) {
                        _uiState.update { state ->
                            state.copy(
                                cards =
                                    state.cards.map { card ->
                                        card.takeUnless { it.failureMessage == null }
                                            ?: card.copy(failureMessage = "刷新超时", statusLabel = "刷新超时")
                                    },
                            )
                        }
                    }
                    if (completed) {
                        autoSignInWaitingCards()
                    }
                } finally {
                    _uiState.update { it.copy(isRefreshingAll = false) }
                    refreshMutex.unlock()
                }
            }
    }

    /**
     * 「刷新全部」完成后扫描结果，串行对仍处于「待签到」的账号触发签到。
     * 串行的目的：复用 AccountSeatActionExecutor 已有的账号级互斥（AccountOperationCoordinator），
     * 同时避免对学校接口产生瞬时并发压力；遇到失败也只标注当前卡片并继续。
     */
    private suspend fun autoSignInWaitingCards() {
        val targets =
            _uiState.value.cards.filter { card ->
                card.liveState == SeatBookingLiveState.RESERVED_WAITING_SIGNIN
            }
        if (targets.isEmpty()) return
        targets.forEach { card ->
            val updated =
                withTimeoutOrNull(SIGN_IN_TIMEOUT_MILLIS) {
                    withContext(ioDispatcher) { repository.signInWaitingCard(card) }
                } ?: card.copy(
                    statusLabel = "自动签到超时",
                    failureMessage = "自动签到超时",
                )
            replaceCard(updated)
        }
    }

    fun refreshSingle(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank() || !singleRefreshing.add(safeStudentId)) {
            return
        }
        _uiState.update { it.copy(singleRefreshing = singleRefreshing.toSet()) }
        viewModelScope.launch {
            try {
                val currentCard = _uiState.value.cards.firstOrNull { it.studentId == safeStudentId }
                val refreshed =
                    withTimeoutOrNull(SINGLE_REFRESH_TIMEOUT_MILLIS) {
                        withContext(ioDispatcher) { repository.fetchSnapshot(safeStudentId) }
                    } ?: currentCard?.copy(statusLabel = "刷新超时", failureMessage = "刷新超时")
                if (refreshed != null) {
                    replaceCard(refreshed)
                }
            } finally {
                singleRefreshing.remove(safeStudentId)
                _uiState.update { it.copy(singleRefreshing = singleRefreshing.toSet()) }
            }
        }
    }

    private fun replaceCard(card: SeatDisplayCardUiState) {
        _uiState.update { state ->
            state.copy(
                cards =
                    state.cards.map { current ->
                        if (current.studentId == card.studentId) card else current
                    },
            )
        }
    }

    private companion object {
        private const val SINGLE_REFRESH_TIMEOUT_MILLIS = 15_000L
        private const val REFRESH_ALL_TIMEOUT_MILLIS = 60_000L
        private const val SIGN_IN_TIMEOUT_MILLIS = 20_000L
    }
}

class SeatDisplayViewModelFactory(
    private val repository: SeatDisplayRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SeatDisplayViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return SeatDisplayViewModel(repository = repository) as T
    }
}
