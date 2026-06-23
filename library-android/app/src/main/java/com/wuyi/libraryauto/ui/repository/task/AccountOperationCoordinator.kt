package com.wuyi.libraryauto.ui.repository.task

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AccountOperationCoordinator {
    private val mutexByStudentId = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> run(
        studentId: String,
        block: suspend () -> T,
    ): T {
        val mutex = mutexByStudentId.getOrPut(studentId.trim()) { Mutex() }
        return mutex.withLock { block() }
    }
}
