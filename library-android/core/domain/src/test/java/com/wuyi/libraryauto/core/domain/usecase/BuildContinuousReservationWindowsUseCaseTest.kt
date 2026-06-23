package com.wuyi.libraryauto.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

class BuildContinuousReservationWindowsUseCaseTest {

    @Test
    fun `continuous planner uses today and next two days`() {
        val windows =
            BuildContinuousReservationWindowsUseCase()(
                now = LocalDateTime.of(2026, 4, 11, 9, 30),
            )

        assertThat(windows).containsExactly(
            ReservationWindow(targetDate = "2026-04-11", startHour = 9, endHour = 22),
            ReservationWindow(targetDate = "2026-04-12", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-13", startHour = 8, endHour = 22),
        ).inOrder()
    }

    @Test
    fun `continuous planner keeps today even after 10am as long as before 22`() {
        val windows =
            BuildContinuousReservationWindowsUseCase()(
                now = LocalDateTime.of(2026, 4, 11, 14, 23),
            )

        assertThat(windows).containsExactly(
            ReservationWindow(targetDate = "2026-04-11", startHour = 14, endHour = 22),
            ReservationWindow(targetDate = "2026-04-12", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-13", startHour = 8, endHour = 22),
        ).inOrder()
    }

    @Test
    fun `continuous planner uses 8am floor when now is before 8am`() {
        val windows =
            BuildContinuousReservationWindowsUseCase()(
                now = LocalDateTime.of(2026, 4, 11, 6, 0),
            )

        assertThat(windows).containsExactly(
            ReservationWindow(targetDate = "2026-04-11", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-12", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-13", startHour = 8, endHour = 22),
        ).inOrder()
    }

    @Test
    fun `continuous planner keeps three future dates when today is unavailable`() {
        val windows =
            BuildContinuousReservationWindowsUseCase()(
                now = LocalDateTime.of(2026, 4, 11, 22, 30),
            )

        assertThat(windows).containsExactly(
            ReservationWindow(targetDate = "2026-04-12", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-13", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-14", startHour = 8, endHour = 22),
        ).inOrder()
    }
}
