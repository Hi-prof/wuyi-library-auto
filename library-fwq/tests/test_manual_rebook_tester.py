from __future__ import annotations

import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from wuyi_seat_bot.seat_api import SHANGHAI_TZ, build_begin_time

from prevent_auto.database import initialize_database
from prevent_auto.models import BookingSnapshot
from prevent_auto.repositories.action_logs import ActionLogsRepository
from prevent_auto.services.account_service import AccountService
from prevent_auto.services.booking_status_service import BookingStatusService
from prevent_auto.services.cancel_service import CancelService
from prevent_auto.services.manual_rebook_tester import ManualRebookTester


class _FakeBridge:
    def __init__(self) -> None:
        self.bookings: list[BookingSnapshot] = []
        self.cancel_calls: list[tuple[int, str]] = []
        self.reserve_calls: list[dict[str, object]] = []
        self.cancel_result = (True, "取消成功")
        self.reserve_result = (True, "预约成功")

    def fetch_bookings(self, account) -> list[BookingSnapshot]:
        return list(self.bookings)

    def cancel_booking(self, account, booking_id: str):
        self.cancel_calls.append((account.id, booking_id))
        return self.cancel_result

    def reserve_specific_seat(
        self,
        account,
        *,
        room_name: str,
        seat_number: str,
        date_value: str,
        start_hour: int,
        end_hour: int,
    ):
        self.reserve_calls.append(
            {
                "account_id": account.id,
                "room_name": room_name,
                "seat_number": seat_number,
                "date_value": date_value,
                "start_hour": start_hour,
                "end_hour": end_hour,
            }
        )
        return self.reserve_result


class ManualRebookTesterTestCase(unittest.TestCase):
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
            enabled=True,
        )
        self.bridge = _FakeBridge()
        self.logs_repository = ActionLogsRepository(self.database_path)
        self.tester = ManualRebookTester(
            booking_status_service=BookingStatusService(self.bridge),
            cancel_service=CancelService(self.bridge, self.database_path),
            bridge=self.bridge,
            action_logs_repository=self.logs_repository,
        )

    def test_manual_rebook_test_cancels_and_rebooks_next_window(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-1",
                room_name="自习室圆形一楼",
                seat_number="19",
                status="0",
                start_time=build_begin_time("2026-04-03", 8),
                duration_seconds=14 * 3600,
            )
        ]

        result = self.tester.test_cancel_and_rebook_next_window(
            self.account,
            datetime(2026, 4, 3, 8, 25, tzinfo=SHANGHAI_TZ),
        )

        self.assertTrue(result.success)
        self.assertIn("测试补约成功", result.message)
        self.assertEqual(self.bridge.cancel_calls, [(self.account.id, "booking-1")])
        self.assertEqual(len(self.bridge.reserve_calls), 1)
        self.assertEqual(self.bridge.reserve_calls[0]["room_name"], "自习室圆形一楼")
        self.assertEqual(self.bridge.reserve_calls[0]["seat_number"], "19")
        self.assertEqual(self.bridge.reserve_calls[0]["start_hour"], 9)
        self.assertEqual(self.bridge.reserve_calls[0]["end_hour"], 22)
        logs = self.logs_repository.list_recent(account_id=self.account.id, limit=10)
        self.assertEqual(
            sorted(item.action_type for item in logs),
            ["cancel", "rebook_test"],
        )

    def test_manual_rebook_test_uses_original_seat_when_detected_booking_missing(self) -> None:
        self.account_service.update_account(
            self.account.id,
            name="主号",
            student_id="20231121130",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )
        account = self.account_service.get_account(self.account.id)
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-9",
                room_name="自习室圆形一楼",
                seat_number="23",
                status="0",
                start_time=build_begin_time("2026-04-03", 8),
                duration_seconds=14 * 3600,
            )
        ]

        result = self.tester.test_cancel_and_rebook_next_window(
            account,
            datetime(2026, 4, 3, 8, 25, tzinfo=SHANGHAI_TZ),
        )

        self.assertTrue(result.success)
        self.assertEqual(self.bridge.reserve_calls[0]["room_name"], "自习室圆形一楼")
        self.assertEqual(self.bridge.reserve_calls[0]["seat_number"], "23")

    def test_manual_rebook_test_requires_cancelable_booking(self) -> None:
        self.bridge.bookings = [
            BookingSnapshot(
                booking_id="booking-2",
                room_name="自习室圆形二楼",
                seat_number="165",
                status="1",
                start_time=build_begin_time("2026-04-03", 8),
                duration_seconds=14 * 3600,
            )
        ]

        result = self.tester.test_cancel_and_rebook_next_window(
            self.account,
            datetime(2026, 4, 3, 8, 25, tzinfo=SHANGHAI_TZ),
        )

        self.assertFalse(result.success)
        self.assertIn("今天没有可取消的待签到预约", result.message)
        self.assertEqual(self.bridge.cancel_calls, [])
        self.assertEqual(self.bridge.reserve_calls, [])
