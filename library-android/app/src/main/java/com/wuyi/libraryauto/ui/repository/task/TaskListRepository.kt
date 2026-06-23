package com.wuyi.libraryauto.ui.repository.task

import android.content.Context
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import kotlinx.coroutines.flow.Flow

interface TaskListRepository {
    fun observeTasks(): Flow<List<ReservationTaskEntity>>
}

class StorageTaskListRepository(
    context: Context,
) : TaskListRepository {
    private val reservationTaskDao =
        StorageDatabaseProvider.get(context).reservationTaskDao()

    override fun observeTasks(): Flow<List<ReservationTaskEntity>> = reservationTaskDao.observeAll()
}
