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
 * 任务 12.14：Manual_Sync_Action 全流程 ViewModel。
 *
 * 设计依据：
 * - design.md「数据流 4：Manual_Sync_Action 全流程」：用户点击「从服务端同步活跃池」按钮后，
 *   依次校验 [ServerSyncConfig.isConfigured] → 调接口 A → 接口 B → [SyncPlanner.computeDiff] →
 *   弹 Sync_Coverage_Confirmation → 用户勾选 → [SyncApplier.apply] → toast 同步结果。
 * - Requirements 13.1 / 13.3 / 13.4 / 13.5 / 13.10 / 13.11 / 13.12 / 13.13 / 13.15 / 13.16：
 *   - 未配置服务端时直接 toast「未配置服务端」，不发任何网络请求；
 *   - 接口失败仅 toast 错误码，不修改 Local_Account_Store；
 *   - 弹窗按 student_id 行级勾选；add/replace 默认 true，remove 默认 false；
 *   - 「全选 / 全不选 / 反选」三个快捷按钮仅作用于当前 [SyncSelection]；
 *   - 「确认覆盖」时调 [SyncApplier.apply]；空选择视为无操作；
 *   - 「取消」直接关闭弹窗，不修改 Room；
 *   - Sync_Selection 弹窗关闭后不持久化，下次重新初始化。
 *
 * UI 仅观察 [state] 与 [event] 两个 Flow：
 * - [state]：当前 Manual_Sync_Action 的阶段（Idle / Loading / ConfirmationOpen / Applying）；
 * - [event]：一次性事件（Snackbar 文案、关闭对话框等），消费后置空。
 *
 * 该 ViewModel **不**直接持有 Compose UI 引用，所有交互通过 Flow + 函数调用驱动；UI 销毁后
 * [viewModelScope] 自动取消，不会泄漏正在进行的网络请求。
 *
 * _Requirements: 13.1, 13.3, 13.4, 13.5, 13.10, 13.11, 13.12, 13.13, 13.15, 13.16_
 */
class ManualSyncCoverageViewModel(
    private val serverSyncConfig: ServerSyncConfig,
    private val repository: AccountPoolSyncRepository,
    private val localAccountStore: RoomLocalAccountStore,
    private val indicator: SyncStatusIndicator = SyncStatusIndicator.default(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event.asStateFlow()

    /**
     * 用户点击「从服务端同步活跃池」按钮时调用。
     *
     * 守卫：
     * 1. 若当前已经有同步流程在进行（Loading / Applying / ConfirmationOpen），合并为同一次调用，
     *    UI 多次点击不会触发并发请求。
     * 2. [ServerSyncConfig.isConfigured] 返回 false 时直接发出 [Event.Snackbar]「未配置服务端」，
     *    **不**发起任何网络请求（Requirement 13.3）。
     * 3. 接口 A 失败 → [Event.Snackbar]（错误码）；接口 B 单条失败 → 同样降级，但已经成功的条目
     *    继续进入 diff（避免一条失败拖垮整批）；若全部失败则视为接口 A 失败。
     * 4. 接口 A / B 全部成功后调用 [SyncPlanner.computeDiff]，无差异时显示「服务端清单与本地一致」
     *    Snackbar 并直接关闭流程；有差异时进入 [State.ConfirmationOpen] 弹窗。
     */
    fun trigger() {
        val current = _state.value
        if (current !is State.Idle) {
            // 已经有同步流程在进行，忽略本次点击。
            return
        }
        if (!serverSyncConfig.isConfigured()) {
            _event.value = Event.Snackbar("未配置服务端")
            return
        }
        _state.value = State.Loading
        viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { runFetchPlan() }
            when (outcome) {
                is FetchOutcome.Failed -> {
                    indicator.reportFailure(reason = outcome.reason)
                    _state.value = State.Idle
                    _event.value = Event.Snackbar(translateError(outcome.reason))
                }
                is FetchOutcome.Empty -> {
                    indicator.reportSuccess()
                    _state.value = State.Idle
                    _event.value = Event.Snackbar("服务端清单与本地一致，无需同步")
                }
                is FetchOutcome.Ready -> {
                    indicator.reportSuccess()
                    _state.value = State.ConfirmationOpen(
                        candidates = outcome.candidates,
                        selection = initialSelection(outcome.candidates),
                    )
                }
            }
        }
    }

    /** 用户在弹窗中切换某条目勾选状态。 */
    fun toggleSelection(studentId: String) {
        val current = _state.value
        if (current !is State.ConfirmationOpen) return
        val next = current.selection.toMutableMap()
        next[studentId] = !(next[studentId] ?: false)
        _state.value = current.copy(selection = next)
    }

    /** 全选：把所有候选条目设为 true。 */
    fun selectAll() {
        val current = _state.value
        if (current !is State.ConfirmationOpen) return
        _state.value = current.copy(
            selection = current.candidates.associate { it.studentId to true },
        )
    }

    /** 全不选：把所有候选条目设为 false。 */
    fun clearAll() {
        val current = _state.value
        if (current !is State.ConfirmationOpen) return
        _state.value = current.copy(
            selection = current.candidates.associate { it.studentId to false },
        )
    }

    /** 反选：当前 true 的变 false，false 的变 true。 */
    fun invertAll() {
        val current = _state.value
        if (current !is State.ConfirmationOpen) return
        val next = current.candidates.associate { candidate ->
            candidate.studentId to !(current.selection[candidate.studentId] ?: false)
        }
        _state.value = current.copy(selection = next)
    }

    /**
     * 用户点击「确认覆盖」。
     *
     * - Sync_Selection 全空 → [Event.Snackbar]「未选择任何账号，本次同步未对本地数据做任何更改」，
     *   关闭弹窗（Requirement 13.15）。
     * - 至少一条勾选 → 调用 [SyncApplier.apply]，按 [SyncApplyResult] 拼接 toast 文案。
     * - apply 抛错 → [Event.Snackbar]（错误信息），保留 Room（事务回滚由
     *   [LocalAccountStore.runInTransaction] 保证）。
     */
    fun confirm() {
        val current = _state.value
        if (current !is State.ConfirmationOpen) return
        _state.value = State.Applying(current.candidates, current.selection)
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    SyncApplier.apply(
                        candidates = current.candidates,
                        selection = current.selection,
                        store = localAccountStore,
                    )
                }
            }
            _state.value = State.Idle
            result.fold(
                onSuccess = { applyResult ->
                    val message = when (applyResult) {
                        is SyncApplyResult.Noop -> "未选择任何账号，本次同步未对本地数据做任何更改"
                        is SyncApplyResult.Applied -> {
                            "同步成功：新增 ${applyResult.added}、替换 ${applyResult.replaced}、" +
                                "移除 ${applyResult.removed}"
                        }
                    }
                    _event.value = Event.Snackbar(message)
                },
                onFailure = { throwable ->
                    val reason = throwable.message ?: throwable::class.simpleName.orEmpty()
                    _event.value = Event.Snackbar("同步失败：${reason.ifBlank { "unknown" }}")
                },
            )
        }
    }

    /** 用户点击「取消」按钮或关闭弹窗：直接回到 Idle，不修改 Local_Account_Store。 */
    fun cancel() {
        val current = _state.value
        if (current !is State.ConfirmationOpen) return
        _state.value = State.Idle
    }

    /** UI 消费完 [event] 后调用。 */
    fun consumeEvent() {
        _event.value = null
    }

    // ───── 内部实现 ─────

    private suspend fun runFetchPlan(): FetchOutcome {
        // 1) 接口 A：拉活跃池清单。失败则整体失败。
        val listResult = repository.refreshActiveList()
        val listEntities = when (listResult) {
            is AccountPoolSyncResult.Success -> listResult.value
            is AccountPoolSyncResult.Error -> return FetchOutcome.Failed(reasonOf(listResult))
        }

        // 2) 接口 B：对每个活跃账号取详情。任一条失败仅记录错误，不阻塞其它条目。
        val snapshots = mutableListOf<ServerAccountSnapshot>()
        var firstFailureReason: String? = null
        for (entity in listEntities) {
            val detailResult = repository.getActiveAccountDetail(entity.accountId)
            when (detailResult) {
                is AccountPoolSyncResult.Success -> {
                    snapshots += ServerAccountSnapshot(
                        accountId = detailResult.value.account.accountId,
                        studentId = detailResult.value.account.studentId,
                        password = detailResult.value.account.password,
                        displayName = detailResult.value.account.displayName,
                        automationTasks = detailResult.value.automationTasks,
                    )
                }
                is AccountPoolSyncResult.Error -> {
                    if (firstFailureReason == null) {
                        firstFailureReason = reasonOf(detailResult)
                    }
                }
            }
        }
        if (snapshots.isEmpty() && listEntities.isNotEmpty() && firstFailureReason != null) {
            return FetchOutcome.Failed(firstFailureReason)
        }

        // 3) 计算 diff：与本地受管字段对比。
        val localAccounts = localAccountStore.loadAll()
        val candidates = SyncPlanner.computeDiff(snapshots, localAccounts)
        return if (candidates.isEmpty()) {
            FetchOutcome.Empty
        } else {
            FetchOutcome.Ready(candidates)
        }
    }

    private fun initialSelection(candidates: List<SyncCandidate>): SyncSelection =
        candidates.associate { candidate -> candidate.studentId to candidate.defaultChecked }

    private fun reasonOf(error: AccountPoolSyncResult.Error): String =
        when (error) {
            is AccountPoolSyncResult.Error.Network -> SyncStatusIndicator.REASON_NETWORK
            is AccountPoolSyncResult.Error.HttpsRequired -> SyncStatusIndicator.REASON_HTTPS_REQUIRED
            is AccountPoolSyncResult.Error.Unauthorized -> SyncStatusIndicator.REASON_UNAUTHORIZED
            is AccountPoolSyncResult.Error.RateLimited -> SyncStatusIndicator.REASON_RATE_LIMITED
            is AccountPoolSyncResult.Error.Server -> SyncStatusIndicator.REASON_SERVER
            is AccountPoolSyncResult.Error.AccountNotInActivePool -> SyncStatusIndicator.REASON_SERVER
            is AccountPoolSyncResult.Error.Unexpected -> SyncStatusIndicator.REASON_UNKNOWN
        }

    private fun translateError(reason: String): String =
        when (reason) {
            SyncStatusIndicator.REASON_NETWORK -> "服务端不可达（network_error）"
            SyncStatusIndicator.REASON_HTTPS_REQUIRED -> "需要 HTTPS（https_required_426）"
            SyncStatusIndicator.REASON_UNAUTHORIZED -> "鉴权失败（unauthorized_401）"
            SyncStatusIndicator.REASON_RATE_LIMITED -> "请求过于频繁（rate_limited_429）"
            SyncStatusIndicator.REASON_SERVER -> "服务端错误（server_5xx）"
            else -> "同步失败（$reason）"
        }

    sealed class State {
        data object Idle : State()

        data object Loading : State()

        data class ConfirmationOpen(
            val candidates: List<SyncCandidate>,
            val selection: SyncSelection,
        ) : State()

        data class Applying(
            val candidates: List<SyncCandidate>,
            val selection: SyncSelection,
        ) : State()
    }

    sealed class Event {
        data class Snackbar(val message: String) : Event()
    }

    private sealed class FetchOutcome {
        data class Failed(val reason: String) : FetchOutcome()

        data object Empty : FetchOutcome()

        data class Ready(val candidates: List<SyncCandidate>) : FetchOutcome()
    }
}

/**
 * 用户在 Sync_Coverage_Confirmation 弹窗内的勾选状态映射；key 是 student_id。
 *
 * Sync_Selection 仅在弹窗交互期间存活，弹窗关闭后由 ViewModel 直接置回 [ManualSyncCoverageViewModel.State.Idle]，
 * 不持久化（Requirement 13.16）。
 */
typealias SyncSelection = Map<String, Boolean>

/**
 * Compose 入口的 [ViewModelProvider.Factory]：避免引入 Hilt 直接依赖。
 */
class ManualSyncCoverageViewModelFactory(
    private val serverSyncConfig: ServerSyncConfig,
    private val repository: AccountPoolSyncRepository,
    private val localAccountStore: RoomLocalAccountStore,
    private val indicator: SyncStatusIndicator = SyncStatusIndicator.default(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ManualSyncCoverageViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return ManualSyncCoverageViewModel(
            serverSyncConfig = serverSyncConfig,
            repository = repository,
            localAccountStore = localAccountStore,
            indicator = indicator,
            ioDispatcher = ioDispatcher,
        ) as T
    }
}
