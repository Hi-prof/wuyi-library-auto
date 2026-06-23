package com.wuyi.libraryauto.ui.repository.account

import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.ui.repository.task.AccountPreferenceWriter
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSnapshot
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSource
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository

class StoredSavedAccountRepository(
    private val store: SavedAccountStore,
) : SavedAccountRepository, StoredAccountSource, AccountPreferenceWriter {
    override fun readAll(): List<SavedAccountEntry> =
        store.readAll().map { account ->
            SavedAccountEntry(
                studentId = account.studentId,
                password = account.password,
                preferredRoomName = account.preferredRoomName,
                preferredSeatNumber = account.preferredSeatNumber,
            )
        }

    override fun readStoredAccounts(): List<StoredAccountSnapshot> =
        store.readAll().map { account ->
            StoredAccountSnapshot(
                studentId = account.studentId,
                password = account.password,
                preferredRoomName = account.preferredRoomName,
                preferredSeatNumber = account.preferredSeatNumber,
            )
        }

    override fun remove(studentId: String) {
        store.remove(studentId)
    }

    override fun saveImported(account: SavedAccountEntry) {
        store.save(
            studentId = account.studentId,
            password = account.password.ifBlank { account.studentId },
        )
    }

    override fun updatePreferredSeat(
        studentId: String,
        preferredRoomName: String,
        preferredSeatNumber: String,
    ) {
        store.updatePreferredSeat(studentId, preferredRoomName, preferredSeatNumber)
    }
}
