package com.wuyi.libraryauto.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadEntity
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadDao
import com.wuyi.libraryauto.core.storage.db.TaskUploadConflictDao
import com.wuyi.libraryauto.core.storage.db.TaskUploadConflictEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「上传本地自动任务」按钮 + 冲突记录区的 ViewModel。
 *
 * 与 [ManualSyncCoverageViewModel]（拉取活跃池）解耦：上传是反向链路，触发条件、
 * 错误展示、冲突解决 UI 都不一样。这里只关心 [AutomationPlanUploadService] 的入队结果，
 * 真正的网络上传由 [AutomationTaskUploadWorker] 在后台 FIFO 完成。
 */
class AutomationPlanUploadViewModel(
    private val service: AutomationPlanUploadService,
    pendingDao: PendingTaskUploadDao,
    private val conflictDao: TaskUploadConflictDao,
) : ViewModel() {
    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val pendingQueue: StateFlow<List<PendingTaskUploadEntity>> =
        pendingDao.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val conflicts: StateFlow<List<TaskUploadConflictEntity>> =
        conflictDao.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    fun uploadAllPlans() {
        if (_state.value is State.Uploading) return
        _state.value = State.Uploading
        viewModelScope.launch {
            val outcome = runCatching { service.uploadAllPlans() }.getOrElse { e ->
                _state.value = State.Error(message = e.message ?: e.javaClass.simpleName)
                return@launch
            }
            _state.value = when (outcome) {
                is AutomationPlanUploadService.UploadOutcome.Disabled ->
                    State.Error(message = "未配置服务端，或未启用上行同步")

                is AutomationPlanUploadService.UploadOutcome.Empty ->
                    State.Empty

                is AutomationPlanUploadService.UploadOutcome.ActivePoolFailed ->
                    State.Error(message = "拉取服务端账号池失败：${outcome.reason}")

                is AutomationPlanUploadService.UploadOutcome.Completed ->
                    State.Completed(
                        enqueued = outcome.enqueued,
                        skipped = outcome.skipped,
                        rejected = outcome.rejected,
                        items = outcome.items,
                    )
            }
        }
    }

    fun dismissResult() {
        _state.value = State.Idle
    }

    fun deleteConflict(conflictHash: String) {
        viewModelScope.launch {
            conflictDao.deleteByHash(conflictHash)
        }
    }

    sealed class State {
        data object Idle : State()
        data object Uploading : State()
        data object Empty : State()
        data class Error(val message: String) : State()
        data class Completed(
            val enqueued: Int,
            val skipped: Int,
            val rejected: Int,
            val items: List<AutomationPlanUploadService.PlanItemResult>,
        ) : State()
    }
}

class AutomationPlanUploadViewModelFactory(
    private val service: AutomationPlanUploadService,
    private val pendingDao: PendingTaskUploadDao,
    private val conflictDao: TaskUploadConflictDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AutomationPlanUploadViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AutomationPlanUploadViewModel(
            service = service,
            pendingDao = pendingDao,
            conflictDao = conflictDao,
        ) as T
    }
}
