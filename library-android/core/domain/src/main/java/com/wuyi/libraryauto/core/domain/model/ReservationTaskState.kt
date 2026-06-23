package com.wuyi.libraryauto.core.domain.model

enum class ReservationTaskState {
    PENDING_RESERVATION,
    RESERVING,
    RESERVED_WAITING_SIGNIN,
    GUARD_SCHEDULED,
    SCANNING,
    SIGNIN_SUCCESS,
    FAILED_RETRYABLE,
    FAILED_MANUAL_ACTION,
    CANCELLED,
}
