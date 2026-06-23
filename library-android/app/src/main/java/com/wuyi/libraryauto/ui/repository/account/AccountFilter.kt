package com.wuyi.libraryauto.ui.repository.account

import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry

class AccountFilter {
    fun filter(
        accounts: List<SavedAccountEntry>,
        query: String,
    ): List<SavedAccountEntry> {
        val safeQuery = query.trim()
        if (safeQuery.isEmpty()) {
            return accounts
        }
        return accounts.filter { account ->
            account.studentId.contains(safeQuery, ignoreCase = true) ||
                account.preferredRoomName.contains(safeQuery, ignoreCase = true) ||
                account.preferredSeatLabel.contains(safeQuery, ignoreCase = true)
        }
    }
}
