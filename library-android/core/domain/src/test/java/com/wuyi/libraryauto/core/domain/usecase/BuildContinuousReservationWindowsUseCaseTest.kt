package com.wuyi.libraryauto.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

class BuildContinuousReservationWindowsUseCaseTest {

    @Test
    fun `continuous planner rounds today start up to next hour`() {
        val windows =
            BuildContinuousReservationWindowsUseCase()(
                now = LocalDateTime.of(2026, 4, 11, 9, 30),
            )

        assertThat(windows).containsExactly(
            ReservationWindow(targetDate = "2026-04-11", startHour = 10, endHour = 22),
            ReservationWindow(targetDate = "2026-04-12", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-13", startHour = 8, endHour = 22),
        ).inOrder()
    }

    @Test
    fun `continuous planner keeps exact current hour when now is on the hour`() {
        val windows =
            BuildContinuousReservationWindowsUseCase()(
                now = LocalDateTime.of(2026, 4, 11, 14, 0),
            )

        assertThat(windows).containsExactly(
            ReservationWindow(targetDate = "2026-04-11", startHour = 14, endHour = 22),
            ReservationWindow(targetDate = "2026-04-12", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-13", startHour = 8, endHour = 22),
        ).inOrder()
    }

    @Test
    fun `continuous planner skips today when rounded current hour reaches close time`() {
        val windows =
            BuildContinuousReservationWindowsUseCase()(
                now = LocalDateTime.of(2026, 4, 11, 21, 30),
            )

        assertThat(windows).containsExactly(
            ReservationWindow(targetDate = "2026-04-12", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-13", startHour = 8, endHour = 22),
            ReservationWindow(targetDate = "2026-04-14", startHour = 8, endHour = 22),
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
