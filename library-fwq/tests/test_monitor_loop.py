from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from datetime import datetime
from pathlib import Path

from wuyi_seat_bot.seat_api import SHANGHAI_TZ, build_begin_time

from prevent_auto.database import initialize_database
from prevent_auto.models import BookingSnapshot
from prevent_auto.scheduler.monitor_loop import MonitorLoop
from prevent_auto.services.account_service import AccountService
from prevent_auto.services.booking_status_service import BookingStatusService
from prevent_auto.services.cancel_service import CancelService


class _FakeBridge:
    def __init__(self) -> None:
        self.bookings: list[BookingSnapshot] = []
        self.cancel_calls: list[tuple[int, str]] = []
        self.fetch_accounts: list[dict[str, str]] = []
        self.cancel_accounts: list[dict[str, str]] = []
        self.fetch_error: Exception | None = None

    def fetch_bookings(self, account) -> list[BookingSnapshot]:
        self.fetch_accounts.append(
            {
                "password": account.password,
                "seat_url": account.seat_url,
            }
        )
        if self.fetch_error is not None:
            raise self.fetch_error
        return list(self.bookings)

    def cancel_booking(self, account, booking_id: str):
        self.cancel_accounts.append(
            {
                "password": account.password,
                "seat_url": account.seat_url,
            }
        )
        self.cancel_calls.append((account.id, booking_id))
        return True, "取消成功"


class MonitorLoopTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        initialize_database(self.database_path)
        self.account_service = AccountService(self.database_path)
        self.account = self.account_service.create_account(
            name="主号",
            student_id="20231121130",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            rebook_enabled=True,
            rebook_trigger_minutes=5,
            enabled=True,
        )
        self.bridge = _FakeBridge()
        self.booking_status_service = BookingStatusService(self.bridge)
        self.cancel_service = CancelService(self.bridge, self.database_path)

    def _build_monitor(self, now: datetime) -> MonitorLoop:
        return MonitorLoop(
            account_service=self.account_service,
            booking_status_service=self.booking_status_service,
            cancel_service=self.cancel_service,
            now_provider=lambda: now,
        )

    def test_monitor_cycle_only_records_current_status(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-1",
                room_name="自习室圆形二楼",
                seat_number="165",
                status="0",
                start_time=build_begin_time("2026-04-03", 8),
                duration_seconds=3600,
            )
        ]
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 2, tzinfo=SHANGHAI_TZ)
        )

        monitor.run_cycle_once()

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(self.bridge.cancel_calls, [])
        self.assertIn("今日预约：自习室圆形二楼 165 号座位", updated_account.last_status)

    def test_run_account_once_cancels_overdue_booking_after_grace(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-1",
                room_name="自习室圆形二楼",
                seat_number="165",
                status="0",
                start_time=build_begin_time("2026-04-03", 8),
                duration_seconds=3600,
            )
        ]
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 10, tzinfo=SHANGHAI_TZ)
        )

        monitor.run_account_once(self.account.id)

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(self.bridge.cancel_calls, [(self.account.id, "booking-1")])
        self.assertEqual(
            updated_account.last_status,
            "超过预约时间 3 分钟自动取消：取消成功",
        )

    def test_monitor_cycle_cancels_overdue_booking_after_grace(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-1",
                room_name="自习室圆形二楼",
                seat_number="165",
                status="0",
                start_time=build_begin_time("2026-04-03", 8),
                duration_seconds=3600,
            )
        ]
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 5, tzinfo=SHANGHAI_TZ)
        )

        monitor.run_cycle_once()

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(self.bridge.cancel_calls, [(self.account.id, "booking-1")])
        self.assertEqual(
            updated_account.last_status,
            "超过预约时间 3 分钟自动取消：取消成功",
        )

    def test_run_account_once_keeps_booking_before_grace(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-1",
                room_name="自习室圆形二楼",
                seat_number="165",
                status="0",
                start_time=build_begin_time("2026-04-03", 8),
                duration_seconds=3600,
            )
        ]
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 2, tzinfo=SHANGHAI_TZ)
        )

        monitor.run_account_once(self.account.id)

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(self.bridge.cancel_calls, [])
        self.assertIn("今日预约：自习室圆形二楼 165 号座位", updated_account.last_status)

    def test_monitor_cycle_records_no_booking(self) -> None:
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 10, tzinfo=SHANGHAI_TZ)
        )

        monitor.run_cycle_once()

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(updated_account.last_status, "今日无预约")

    def test_monitor_cycle_records_error_without_crashing_thread(self) -> None:
        self.bridge.fetch_error = ValueError("自动续登失败：请求失败：HTTP 404")
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 0, tzinfo=SHANGHAI_TZ)
        )

        monitor.run_cycle_once()

        updated_account = self.account_service.get_account(self.account.id)
        self.assertIn("自动续登失败", updated_account.last_status)
        self.assertIn("HTTP 404", updated_account.last_status)

    def test_monitor_cycle_ignores_finished_booking_statuses(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-4",
                room_name="自习室圆形二楼",
                seat_number="60",
                status="3",
                start_time=build_begin_time("2026-04-03", 9),
                duration_seconds=3600,
            ),
            BookingSnapshot(
                booking_id="booking-5",
                room_name="自习室圆形二楼",
                seat_number="60",
                status="4",
                start_time=build_begin_time("2026-04-03", 10),
                duration_seconds=3600,
            ),
        ]
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 10, tzinfo=SHANGHAI_TZ)
        )

        monitor.run_cycle_once()

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(self.bridge.cancel_calls, [])
        self.assertIn("今日最近预约", updated_account.last_status)
        self.assertIn("已取消", updated_account.last_status)

    def test_cancel_current_booking_cancels_cancelable_booking(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-1",
                room_name="自习室圆形二楼",
                seat_number="165",
                status="0",
                start_time=build_begin_time("2026-04-03", 8),
                duration_seconds=3600,
            )
        ]
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 10, tzinfo=SHANGHAI_TZ)
        )

        message = monitor.cancel_current_booking_once(self.account.id)

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(message, "取消成功")
        self.assertEqual(self.bridge.cancel_calls, [(self.account.id, "booking-1")])
        self.assertEqual(updated_account.last_status, "取消成功")

    def test_resolves_account_before_fetching_bookings(self) -> None:
        account = self.account_service.update_account(
            self.account.id,
            name=self.account.name,
            student_id=self.account.student_id,
            password="",
            login_url=self.account.login_url,
            seat_url="",
            enabled=self.account.enabled,
            rebook_enabled=self.account.rebook_enabled,
            rebook_trigger_minutes=self.account.rebook_trigger_minutes,
        )
        monitor = MonitorLoop(
            account_service=self.account_service,
            booking_status_service=self.booking_status_service,
            cancel_service=self.cancel_service,
            now_provider=lambda: datetime(2026, 4, 3, 8, 10, tzinfo=SHANGHAI_TZ),
            account_resolver=lambda raw_account: replace(
                raw_account,
                password="resolved-secret",
                seat_url=raw_account.seat_url or raw_account.login_url,
            ),
        )

        monitor.run_account_once(account.id)

        self.assertEqual(
            self.bridge.fetch_accounts,
            [
                {
                    "password": "resolved-secret",
                    "seat_url": "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
                }
            ],
        )

    def test_cancel_current_booking_rejects_uncancelable_booking(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-2",
                room_name="自习室圆形一楼",
                seat_number="19",
                status="1",
                start_time=build_begin_time("2026-04-03", 9),
                duration_seconds=13 * 3600,
            )
        ]
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 10, tzinfo=SHANGHAI_TZ)
        )

        message = monitor.cancel_current_booking_once(self.account.id)

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(self.bridge.cancel_calls, [])
        self.assertEqual(message, "取消预约失败：当前预约状态不可取消")
        self.assertEqual(updated_account.last_status, message)

    def test_cancel_current_booking_handles_missing_booking(self) -> None:
        monitor = self._build_monitor(
            datetime(2026, 4, 3, 8, 10, tzinfo=SHANGHAI_TZ)
        )

        message = monitor.cancel_current_booking_once(self.account.id)

        updated_account = self.account_service.get_account(self.account.id)
        self.assertEqual(self.bridge.cancel_calls, [])
        self.assertEqual(message, "取消预约失败：当前没有可取消的预约")
        self.assertEqual(updated_account.last_status, message)
