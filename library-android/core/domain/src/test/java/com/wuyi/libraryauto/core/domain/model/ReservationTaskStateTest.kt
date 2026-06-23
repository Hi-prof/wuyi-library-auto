package com.wuyi.libraryauto.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReservationTaskStateTest {
    @Test
    fun `state machine exposes scanning states used by runtime and ui`() {
        assertThat(ReservationTaskState.valueOf("RESERVED_WAITING_SIGNIN"))
            .isEqualTo(ReservationTaskState.RESERVED_WAITING_SIGNIN)
        assertThat(ReservationTaskState.valueOf("SCANNING"))
            .isEqualTo(ReservationTaskState.SCANNING)
        assertThat(ReservationTaskState.valueOf("SIGNIN_SUCCESS"))
            .isEqualTo(ReservationTaskState.SIGNIN_SUCCESS)
    }
}
