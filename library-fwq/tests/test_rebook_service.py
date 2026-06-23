from __future__ import annotations

import unittest
from datetime import datetime

from wuyi_seat_bot.seat_api import SHANGHAI_TZ

from prevent_auto.models import Account
from prevent_auto.services.rebook_service import build_rebook_window
from prevent_auto.web.runtime import build_account_resolver, next_monitor_run_at


class RebookServiceTestCase(unittest.TestCase):
    def test_build_rebook_window_rounds_to_next_hour(self) -> None:
        current_time = datetime(2026, 4, 3, 8, 25, tzinfo=SHANGHAI_TZ)

        window = build_rebook_window(current_time)

        self.assertIsNotNone(window)
        self.assertEqual(window.start_hour, 9)
        self.assertEqual(window.end_hour, 22)
        self.assertEqual(window.date_value, "2026-04-03")

    def test_build_rebook_window_returns_none_when_no_slot_left(self) -> None:
        current_time = datetime(2026, 4, 3, 21, 40, tzinfo=SHANGHAI_TZ)

        window = build_rebook_window(current_time)

        self.assertIsNone(window)

    def test_next_monitor_run_at_uses_effective_poll_interval(self) -> None:
        current_time = datetime(2026, 4, 3, 8, 9, 30, tzinfo=SHANGHAI_TZ)

        next_run = next_monitor_run_at(current_time, interval_seconds=45)

        self.assertEqual(
            next_run,
            datetime(2026, 4, 3, 8, 10, 15, tzinfo=SHANGHAI_TZ),
        )

    def test_next_monitor_run_at_caps_long_poll_interval(self) -> None:
        current_time = datetime(2026, 4, 3, 8, 20, 1, tzinfo=SHANGHAI_TZ)

        next_run = next_monitor_run_at(current_time, interval_seconds=1500)

        self.assertEqual(next_run, datetime(2026, 4, 3, 8, 21, 1, tzinfo=SHANGHAI_TZ))

    def test_account_resolver_fills_pool_password_and_empty_seat_url(self) -> None:
        class _PoolService:
            def get_login_password(self, account_id: int) -> str:
                self.account_id = account_id
                return "resolved-password"

        pool_service = _PoolService()
        account = Account(
            id=31,
            name="账号 31",
            student_id="20231121131",
            password="",
            login_url="https://wuyi.test/login",
            seat_url="",
            rebook_enabled=False,
            rebook_trigger_minutes=5,
            last_detected_room_name="",
            last_detected_seat_number="",
            last_detected_booking_start_at=None,
            last_detected_booking_status="",
            state_file="runtime/auth-20231121131.json",
            enabled=True,
            account_status="unknown",
            last_check_at=None,
            last_status="",
            created_at="2026-04-03T08:00:00",
            updated_at="2026-04-03T08:00:00",
        )

        resolved = build_account_resolver(pool_service)(account)

        self.assertEqual(pool_service.account_id, 31)
        self.assertEqual(resolved.password, "resolved-password")
        self.assertEqual(resolved.seat_url, "https://wuyi.test/login")
