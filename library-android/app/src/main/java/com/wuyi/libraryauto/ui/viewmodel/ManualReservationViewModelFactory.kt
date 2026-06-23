package com.wuyi.libraryauto.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationGateway
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.session.SessionRepository

class ManualReservationViewModelFactory(
    private val accountRepository: SavedAccountRepository,
    private val seatLookupRepository: SeatLookupRepository,
    private val manualReservationRepository: ManualReservationGateway,
    private val sessionRepository: SessionRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ManualReservationViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return ManualReservationViewModel(
            accountRepository = accountRepository,
            seatLookupRepository = seatLookupRepository,
            manualReservationRepository = manualReservationRepository,
            sessionRepository = sessionRepository,
        ) as T
    }
}
