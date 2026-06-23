package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogHeartbeatStore
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogState
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogStateStore

class WatchdogStatusViewModel(
    private val heartbeatStore: WatchdogHeartbeatStore,
    private val stateStore: WatchdogStateStore,
) : ViewModel() {
    var uiState by mutableStateOf(WatchdogStatusUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        val state = stateStore.read()
        uiState =
            WatchdogStatusUiState(
                periodicHeartbeat = heartbeatStore.readPeriodicCheckInHeartbeat().toEpochLabel(),
                watchdogHeartbeat = heartbeatStore.readWatchdogHeartbeat().toEpochLabel(),
                failureCount = state.failureCount(),
                stateLabel = state.toLabel(),
                uniqueWorks =
                    listOf(
                        UniqueWorkStatus("periodic-check-in", "由 WorkManager 周期执行"),
                        UniqueWorkStatus("watchdog", "由 WorkManager 周期巡检"),
                        UniqueWorkStatus("reservation-guard:*", "按预约时间动态排程"),
                    ),
            )
    }
}

data class WatchdogStatusUiState(
    val periodicHeartbeat: String = "暂无",
    val watchdogHeartbeat: String = "暂无",
    val failureCount: Int = 0,
    val stateLabel: String = "健康",
    val uniqueWorks: List<UniqueWorkStatus> = emptyList(),
)

data class UniqueWorkStatus(
    val name: String,
    val nextRunLabel: String,
)

class WatchdogStatusViewModelFactory(
    private val heartbeatStore: WatchdogHeartbeatStore,
    private val stateStore: WatchdogStateStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(WatchdogStatusViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return WatchdogStatusViewModel(
            heartbeatStore = heartbeatStore,
            stateStore = stateStore,
        ) as T
    }
}

private fun Long.toEpochLabel(): String =
    if (this <= 0L) "暂无" else "$this"

private fun WatchdogState.failureCount(): Int =
    when (this) {
        WatchdogState.Healthy -> 0
        is WatchdogState.Degraded -> consecutiveMissCount
        is WatchdogState.Backoff -> 3
    }

private fun WatchdogState.toLabel(): String =
    when (this) {
        WatchdogState.Healthy -> "健康"
        is WatchdogState.Degraded -> "降级：连续缺失 $consecutiveMissCount 次"
        is WatchdogState.Backoff -> "退避中：自 $backoffStartEpochSeconds 起"
    }
