from __future__ import annotations

import os
import tempfile
import unittest
from datetime import datetime, time as dtime
from pathlib import Path
from unittest import mock

from wuyi_seat_bot.seat_api import SHANGHAI_TZ

from prevent_auto.database import initialize_database
from prevent_auto.scheduler.daily_status_refresher import DailyStatusRefresher
from prevent_auto.services.account_service import AccountService
from prevent_auto.settings import (
    DEFAULT_DAILY_STATUS_REFRESH_TIME,
    load_settings,
)


class _FakeMonitorLoop:
    def __init__(self) -> None:
        self.calls: list[int] = []
        self.error_for_account_id: int | None = None

    def run_account_once(self, account_id: int) -> None:
        if self.error_for_account_id == account_id:
            raise RuntimeError("monitor failed")
        self.calls.append(account_id)


class DailyStatusRefresherTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        initialize_database(self.database_path)
        self.account_service = AccountService(self.database_path)
        self.account_a = self.account_service.create_account(
            name="账号A",
            student_id="20231121130",
            password="secret",
            login_url="https://example.com",
            seat_url="https://example.com",
            enabled=True,
        )
        self.account_b = self.account_service.create_account(
            name="账号B",
            student_id="20231121131",
            password="secret",
            login_url="https://example.com",
            seat_url="https://example.com",
            enabled=True,
        )
        self.account_disabled = self.account_service.create_account(
            name="停用号",
            student_id="20231121132",
            password="secret",
            login_url="https://example.com",
            seat_url="https://example.com",
            enabled=False,
        )
        self.monitor_loop = _FakeMonitorLoop()
        self.sleep_calls: list[float] = []
        self.refresher = DailyStatusRefresher(
            account_service=self.account_service,
            monitor_loop=self.monitor_loop,
            refresh_time=dtime(8, 30),
            batch_size=2,
            batch_delay_seconds=3,
            sleep_func=self.sleep_calls.append,
        )

    def test_skips_when_before_refresh_time(self) -> None:
        ran = self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 8, 29, tzinfo=SHANGHAI_TZ)
        )

        self.assertFalse(ran)
        self.assertEqual(self.monitor_loop.calls, [])
        self.assertIsNone(self.refresher.last_run_date)

    def test_runs_for_all_enabled_accounts_at_target_time(self) -> None:
        ran = self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 8, 30, tzinfo=SHANGHAI_TZ)
        )

        self.assertTrue(ran)
        self.assertEqual(
            self.monitor_loop.calls, [self.account_a.id, self.account_b.id]
        )
        self.assertEqual(self.refresher.last_run_date.isoformat(), "2026-04-03")

    def test_does_not_run_twice_on_same_day(self) -> None:
        self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 8, 31, tzinfo=SHANGHAI_TZ)
        )
        ran_again = self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 9, 0, tzinfo=SHANGHAI_TZ)
        )

        self.assertFalse(ran_again)
        self.assertEqual(
            self.monitor_loop.calls, [self.account_a.id, self.account_b.id]
        )

    def test_runs_again_on_next_day(self) -> None:
        self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 8, 31, tzinfo=SHANGHAI_TZ)
        )
        ran_next_day = self.refresher.run_due_refresh_once(
            datetime(2026, 4, 4, 8, 31, tzinfo=SHANGHAI_TZ)
        )

        self.assertTrue(ran_next_day)
        self.assertEqual(self.refresher.last_run_date.isoformat(), "2026-04-04")
        self.assertEqual(
            self.monitor_loop.calls,
            [self.account_a.id, self.account_b.id] * 2,
        )

    def test_throttles_with_batch_delay(self) -> None:
        # 增加更多账号以触发多个批次。
        for index in range(3, 6):
            self.account_service.create_account(
                name=f"账号{index}",
                student_id=f"2023112113{index}",
                password="secret",
                login_url="https://example.com",
                seat_url="https://example.com",
                enabled=True,
            )

        self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 8, 30, tzinfo=SHANGHAI_TZ)
        )

        # batch_size=2，5 个启用账号 → 在第 3 和第 5 个之前各睡一次 = 2 次。
        self.assertEqual(self.sleep_calls, [3, 3])

    def test_continues_when_single_account_raises(self) -> None:
        self.monitor_loop.error_for_account_id = self.account_a.id

        ran = self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 8, 30, tzinfo=SHANGHAI_TZ)
        )

        self.assertTrue(ran)
        self.assertEqual(self.monitor_loop.calls, [self.account_b.id])

    def test_localizes_naive_datetime_as_shanghai(self) -> None:
        ran_before = self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 8, 29)
        )
        self.assertFalse(ran_before)

        ran_after = self.refresher.run_due_refresh_once(
            datetime(2026, 4, 3, 8, 30)
        )
        self.assertTrue(ran_after)


class SettingsParsingTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())

    def _required_env(self) -> dict[str, str]:
        return {
            "PREVENT_AUTO_HOST": "127.0.0.1",
            "PREVENT_AUTO_AUTH_PASSWORD": "test-password",
            "PREVENT_AUTO_SESSION_SECRET": "test-session-secret-32-bytes-long",
        }

    def test_default_refresh_time_is_08_10(self) -> None:
        env = {key: value for key, value in os.environ.items()
               if not key.startswith("PREVENT_AUTO_")}
        env.update(self._required_env())
        with mock.patch.dict(os.environ, env, clear=True):
            settings = load_settings(base_dir=self.temp_dir)

        self.assertEqual(
            settings.daily_status_refresh_time, DEFAULT_DAILY_STATUS_REFRESH_TIME
        )
        self.assertEqual(settings.daily_status_refresh_time, dtime(8, 10))

    def test_env_overrides_refresh_time(self) -> None:
        env = {key: value for key, value in os.environ.items()
               if not key.startswith("PREVENT_AUTO_")}
        env.update(self._required_env())
        env["PREVENT_AUTO_DAILY_STATUS_REFRESH_TIME"] = "07:15"
        with mock.patch.dict(os.environ, env, clear=True):
            settings = load_settings(base_dir=self.temp_dir)

        self.assertEqual(settings.daily_status_refresh_time, dtime(7, 15))

    def test_invalid_refresh_time_raises_value_error(self) -> None:
        env = {key: value for key, value in os.environ.items()
               if not key.startswith("PREVENT_AUTO_")}
        env.update(self._required_env())
        env["PREVENT_AUTO_DAILY_STATUS_REFRESH_TIME"] = "8.30"
        with mock.patch.dict(os.environ, env, clear=True):
            with self.assertRaises(ValueError):
                load_settings(base_dir=self.temp_dir)


if __name__ == "__main__":
    unittest.main()
