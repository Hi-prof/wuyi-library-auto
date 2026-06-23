from __future__ import annotations

import unittest
from datetime import datetime, timedelta

from wuyi_seat_bot.seat_api import SHANGHAI_TZ

from prevent_auto.scheduler.auto_reservation_loop import AutoReservationLoop
from prevent_auto.services.auto_reservation_service import AutoReservationRunResult


class _FakeMonitorLoop:
    def __init__(self) -> None:
        self.cycles = 0
        self.fail_next = False

    def run_cycle_once(self) -> None:
        self.cycles += 1
        if self.fail_next:
            self.fail_next = False
            raise RuntimeError("monitor cycle boom")


class _FakeAutoReservationService:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []
        self.next_result = AutoReservationRunResult(checked_accounts=1, reserved=2)
        self.fail_next = False

    def run_rolling_once(self, *, current_time, days_ahead):  # noqa: D401
        self.calls.append({"current_time": current_time, "days_ahead": days_ahead})
        if self.fail_next:
            self.fail_next = False
            raise RuntimeError("rolling boom")
        return self.next_result


class AutoReservationLoopTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.monitor = _FakeMonitorLoop()
        self.service = _FakeAutoReservationService()
        self.loop = AutoReservationLoop(
            monitor_loop=self.monitor,
            auto_reservation_service=self.service,
            interval_seconds=5 * 60 * 60,
            rolling_days_ahead=3,
        )

    def test_first_tick_runs_immediately(self) -> None:
        now = datetime(2026, 5, 1, 9, 0, tzinfo=SHANGHAI_TZ)
        result = self.loop.run_due_once(now)

        self.assertTrue(result.ran)
        self.assertEqual(self.monitor.cycles, 1)
        self.assertEqual(len(self.service.calls), 1)
        self.assertEqual(self.service.calls[0]["days_ahead"], 3)

    def test_throttles_within_interval(self) -> None:
        start = datetime(2026, 5, 1, 9, 0, tzinfo=SHANGHAI_TZ)
        self.loop.run_due_once(start)
        result = self.loop.run_due_once(start + timedelta(hours=4))

        self.assertFalse(result.ran)
        self.assertEqual(self.monitor.cycles, 1)
        self.assertEqual(len(self.service.calls), 1)

    def test_runs_again_after_interval(self) -> None:
        start = datetime(2026, 5, 1, 9, 0, tzinfo=SHANGHAI_TZ)
        self.loop.run_due_once(start)
        result = self.loop.run_due_once(start + timedelta(hours=5))

        self.assertTrue(result.ran)
        self.assertEqual(self.monitor.cycles, 2)
        self.assertEqual(len(self.service.calls), 2)

    def test_monitor_failure_does_not_block_rolling(self) -> None:
        self.monitor.fail_next = True
        result = self.loop.run_due_once(
            datetime(2026, 5, 1, 9, 0, tzinfo=SHANGHAI_TZ)
        )

        self.assertTrue(result.ran)
        self.assertEqual(len(self.service.calls), 1)

    def test_rolling_failure_returns_ran_with_none_result(self) -> None:
        self.service.fail_next = True
        result = self.loop.run_due_once(
            datetime(2026, 5, 1, 9, 0, tzinfo=SHANGHAI_TZ)
        )

        self.assertTrue(result.ran)
        self.assertIsNone(result.run_result)

    def test_reset_lets_next_call_run_immediately(self) -> None:
        start = datetime(2026, 5, 1, 9, 0, tzinfo=SHANGHAI_TZ)
        self.loop.run_due_once(start)
        self.loop.reset()
        result = self.loop.run_due_once(start + timedelta(minutes=1))

        self.assertTrue(result.ran)
        self.assertEqual(len(self.service.calls), 2)


if __name__ == "__main__":
    unittest.main()
