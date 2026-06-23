package com.wuyi.libraryauto.core.domain.port

import com.wuyi.libraryauto.core.domain.model.SignInError

interface SeatGateway {
    suspend fun performCheckIn(): SignInError?
}
