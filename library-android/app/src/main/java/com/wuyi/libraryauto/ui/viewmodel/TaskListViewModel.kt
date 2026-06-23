package com.wuyi.libraryauto.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.ui.adapter.task.TaskListItemUiModel
import com.wuyi.libraryauto.ui.adapter.task.toTaskListItemUiModel
import com.wuyi.libraryauto.ui.repository.task.TaskListRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class TaskListUiState(
    val tasks: List<TaskListItemUiModel> = emptyList(),
)

class TaskListViewModel(
    repository: TaskListRepository,
) : ViewModel() {
    val uiState: StateFlow<TaskListUiState> =
        repository.observeTasks()
            .map { entities ->
                TaskListUiState(tasks = entities.map { entity -> entity.toTaskListItemUiModel() })
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
                initialValue = TaskListUiState(),
            )
}

class TaskListViewModelFactory(
    private val repository: TaskListRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TaskListViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return TaskListViewModel(repository = repository) as T
    }
}
