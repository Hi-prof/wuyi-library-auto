package com.wuyi.libraryauto.core.network.seat

import com.wuyi.libraryauto.core.domain.model.CheckInWindow

data class BookingDetail(
    val bookingId: String,
    val window: CheckInWindow,
    val expectedMinors: List<Int>,
    val statusLabel: String,
    val isAlreadySignedIn: Boolean,
)
