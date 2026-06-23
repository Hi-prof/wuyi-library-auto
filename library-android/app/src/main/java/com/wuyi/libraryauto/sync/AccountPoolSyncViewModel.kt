// 撤回 spec account-pool-tri-sync 12.4 中的 ConnectivityGate 阻塞改造；本地执行不再依赖服务端可达性
package com.wuyi.libraryauto.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务 12.8：Active_Pool 同步的 ViewModel 入口。
 *
 * 设计依据：
 * - design.md「Components and Interfaces · Android_Client」要求 Active_Pool 清单同步改为「**仅**
 *   用户主动点击 Manual_Sync_Action 时触发」；ViewModel 持有 [triggerManualSync] 方法供 UI 调用。
 * - Requirement 11.6 / 13.2：升级期不在用户首次启动时静默拉取一次；周期 WorkManager 同步已撤回
 *   （`ActivePoolListSyncWorker` 已删除）；本地执行入口在所有状态下保持原有交互。
 *
 * 行为契约：
 * - [triggerManualSync] 是 idempotent 的：UI 多次点击不会并发执行多次拉取；正在进行的同步会被
 *   `inFlight` 守卫合并为同一次调用，调用方拿到的 [SyncResult] 仍指向同一次实际执行的结果。
 * - 同步成功 / 失败均会通过 [SyncStatusIndicator] 上报给同步按钮三态展示；失败时 **不修改** Room
 *   缓存，由 [AccountPoolSyncRepository.refreshActiveList] 负责整批替换 / 不变的语义。
 * - 本 ViewModel **不** 主动注册任何 PeriodicWorkRequest、不发起任何后台轮询；仅在 [triggerManualSync]
 *   被调用时通过 [repository] 触发一次 [AccountPoolSyncRepository.refreshActiveList]。
 *
 * 失败语义：
 * - [SyncResult.Success]：UI 可显示「同步成功：拉取 N 条活跃账号」；按钮恢复 Enabled。
 * - [SyncResult.Failed]：UI 显示对应错误码（[SyncStatusIndicator.REASON_NETWORK] /
 *   `unauthorized` / `https_required` / `rate_limited` / `server_error` 等）；按钮置灰
 *   `disabled_unreachable`，但本地业务循环（执行任务 / 刷新登录态 / 座位监控）保持原有交互。
 *
 * _Requirements: 11.6, 13.2_
 */
class AccountPoolSyncViewModel(
    private val repository: AccountPoolSyncRepository,
    private val indicator: SyncStatusIndicator = SyncStatusIndicator.default(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<ManualSyncState>(ManualSyncState.Idle)

    /**
     * Manual_Sync_Action 状态。Compose UI 仅对此 Flow 做 collectAsState：
     * - [ManualSyncState.Idle] → 按钮静默；
     * - [ManualSyncState.InProgress] → 按钮可显示「同步中…」副文案；
     * - [ManualSyncState.LastResult] → UI 可读取上次结果做 toast / Snackbar，不影响按钮三态
     *   （三态由 [SyncStatusIndicator] 单独管理）。
     */
    val state: StateFlow<ManualSyncState> = _state.asStateFlow()

    /**
     * 用户主动点击「从服务端同步活跃池」时调用。
     *
     * - 若上一次调用仍在执行（`state == InProgress`），本次调用会被合并到同一次执行：不再发起
     *   额外网络请求，只是把按钮文案保持「同步中…」。
     * - 失败时仅更新 [state] 与 [SyncStatusIndicator]，不抛异常给调用方；本地 Room 不被修改。
     */
    fun triggerManualSync() {
        // 合并并发触发：只有 Idle / LastResult 时才允许启动新一次同步。
        val current = _state.value
        if (current is ManualSyncState.InProgress) return
        _state.value = ManualSyncState.InProgress
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    repository.refreshActiveList()
                }
            indicator.reportSyncResult(result)
            _state.value =
                when (result) {
                    is AccountPoolSyncResult.Success ->
                        ManualSyncState.LastResult(
                            outcome = SyncResult.Success(
                                count = result.value.size,
                                serverTime = result.serverTime,
                            ),
                        )
                    is AccountPoolSyncResult.Error ->
                        ManualSyncState.LastResult(
                            outcome = SyncResult.Failed(
                                reason = errorReasonOf(result),
                                statusCode = httpStatusCodeOf(result),
                            ),
                        )
                }
        }
    }

    /** 显式重置为 Idle，便于 UI 在 Snackbar 消失后清理上次结果。 */
    fun acknowledgeLastResult() {
        if (_state.value is ManualSyncState.LastResult) {
            _state.value = ManualSyncState.Idle
        }
    }

    sealed class ManualSyncState {
        data object Idle : ManualSyncState()

        data object InProgress : ManualSyncState()

        data class LastResult(val outcome: SyncResult) : ManualSyncState()
    }

    sealed class SyncResult {
        data class Success(val count: Int, val serverTime: String) : SyncResult()

        /** [reason] 与 [SyncStatusIndicator] 上报的语义化原因常量保持一致，便于 UI 翻译文案。 */
        data class Failed(val reason: String, val statusCode: Int? = null) : SyncResult()
    }

    companion object {
        private fun errorReasonOf(error: AccountPoolSyncResult.Error): String =
            when (error) {
                is AccountPoolSyncResult.Error.Network -> SyncStatusIndicator.REASON_NETWORK
                is AccountPoolSyncResult.Error.HttpsRequired -> SyncStatusIndicator.REASON_HTTPS_REQUIRED
                is AccountPoolSyncResult.Error.Unauthorized -> SyncStatusIndicator.REASON_UNAUTHORIZED
                is AccountPoolSyncResult.Error.RateLimited -> SyncStatusIndicator.REASON_RATE_LIMITED
                is AccountPoolSyncResult.Error.Server -> SyncStatusIndicator.REASON_SERVER
                is AccountPoolSyncResult.Error.AccountNotInActivePool -> SyncStatusIndicator.REASON_SERVER
                is AccountPoolSyncResult.Error.Unexpected -> SyncStatusIndicator.REASON_UNKNOWN
            }

        private fun httpStatusCodeOf(error: AccountPoolSyncResult.Error): Int? =
            when (error) {
                is AccountPoolSyncResult.Error.Unauthorized -> error.statusCode
                is AccountPoolSyncResult.Error.RateLimited -> error.statusCode
                is AccountPoolSyncResult.Error.AccountNotInActivePool -> error.statusCode
                is AccountPoolSyncResult.Error.Server -> error.statusCode
                else -> null
            }
    }
}

/**
 * Compose / Activity 入口的 [ViewModelProvider.Factory]：避免引入 Hilt 直接依赖。
 */
class AccountPoolSyncViewModelFactory(
    private val repository: AccountPoolSyncRepository,
    private val indicator: SyncStatusIndicator = SyncStatusIndicator.default(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AccountPoolSyncViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return AccountPoolSyncViewModel(
            repository = repository,
            indicator = indicator,
            ioDispatcher = ioDispatcher,
        ) as T
    }
}
