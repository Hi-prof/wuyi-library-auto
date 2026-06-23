package com.wuyi.libraryauto.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogRepository
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRepository

class AutomationTaskViewModelFactory(
    private val accountRepository: SavedAccountRepository,
    private val automationPlanRepository: AutomationPlanRepository,
    private val seatLookupRepository: SeatLookupRepository,
    private val sessionRepository: SessionRepository,
    private val initialStudentFilter: String = "",
    private val historyReader: AccountReservationHistoryReader,
    private val diagnosticsLogRepository: DiagnosticsLogRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AutomationTaskViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return AutomationTaskViewModel(
            accountRepository = accountRepository,
            automationPlanRepository = automationPlanRepository,
            seatLookupRepository = seatLookupRepository,
            sessionRepository = sessionRepository,
            initialStudentFilter = initialStudentFilter,
            historyReader = historyReader,
            diagnosticsLogRepository = diagnosticsLogRepository,
        ) as T
    }
}
