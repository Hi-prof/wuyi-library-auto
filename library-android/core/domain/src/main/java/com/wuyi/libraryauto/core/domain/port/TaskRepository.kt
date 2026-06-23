package com.wuyi.libraryauto.core.domain.port

interface TaskRepository {
    suspend fun hasGuardableTask(taskId: String, nowEpochSeconds: Long): Boolean
}
