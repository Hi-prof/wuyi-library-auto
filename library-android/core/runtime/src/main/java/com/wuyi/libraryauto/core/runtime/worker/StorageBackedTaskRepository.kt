package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import com.wuyi.libraryauto.core.domain.port.TaskRepository
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider

internal class StorageBackedTaskRepository(
    context: Context,
) : TaskRepository {
    private val reservationTaskDao =
        StorageDatabaseProvider.get(context).reservationTaskDao()

    override suspend fun hasGuardableTask(taskId: String, nowEpochSeconds: Long): Boolean =
        reservationTaskDao.findById(taskId)
            ?.toRestorableGuardTask()
            ?.let { GuardRestoreCoordinator.isGuardable(it, nowEpochSeconds) }
            ?: false

    suspend fun listGuardTasks(): List<RestorableGuardTask> =
        reservationTaskDao.listAll()
            .map { it.toRestorableGuardTask() }
            .filter { GuardRestoreCoordinator.isGuardable(it.state) }

    private fun ReservationTaskEntity.toRestorableGuardTask(): RestorableGuardTask =
        RestorableGuardTask(
            taskId = id,
            state = state,
            startTimeEpochSeconds = startTimeEpochSeconds,
            limitSignAgoSeconds = limitSignAgoSeconds,
            limitSignBackSeconds = limitSignBackSeconds,
        )
}
