package com.wuyi.libraryauto.ui.screen.account

sealed class AccountSelectionState {
    object Idle : AccountSelectionState()

    data class MultiSelect(
        val selectedIds: Set<String> = emptySet(),
    ) : AccountSelectionState()

    data class ConfirmDelete(
        val targets: Set<String>,
    ) : AccountSelectionState()
}

fun AccountSelectionState.selectedIds(): Set<String> =
    when (this) {
        AccountSelectionState.Idle -> emptySet()
        is AccountSelectionState.MultiSelect -> selectedIds
        is AccountSelectionState.ConfirmDelete -> targets
    }

fun AccountSelectionState.toggle(studentId: String): AccountSelectionState {
    val safeStudentId = studentId.trim()
    if (safeStudentId.isBlank()) {
        return this
    }
    val current = selectedIds()
    val updated =
        if (safeStudentId in current) {
            current - safeStudentId
        } else {
            current + safeStudentId
        }
    return AccountSelectionState.MultiSelect(updated)
}

fun AccountSelectionState.selectAll(visibleIds: Collection<String>): AccountSelectionState =
    AccountSelectionState.MultiSelect(
        visibleIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet(),
    )

fun AccountSelectionState.clear(): AccountSelectionState = AccountSelectionState.Idle

fun AccountSelectionState.retainExisting(existingIds: Set<String>): AccountSelectionState =
    when (this) {
        AccountSelectionState.Idle -> this
        is AccountSelectionState.MultiSelect -> {
            val retained = selectedIds.intersect(existingIds)
            if (existingIds.isEmpty()) AccountSelectionState.Idle else copy(selectedIds = retained)
        }
        is AccountSelectionState.ConfirmDelete -> {
            val retained = targets.intersect(existingIds)
            if (retained.isEmpty()) AccountSelectionState.Idle else copy(targets = retained)
        }
    }

fun AccountSelectionState.isMultiSelectMode(): Boolean = this !is AccountSelectionState.Idle
