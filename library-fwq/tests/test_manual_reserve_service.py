from __future__ import annotations

import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from wuyi_seat_bot.seat_api import SHANGHAI_TZ

from prevent_auto.database import initialize_database
from prevent_auto.repositories.action_logs import ActionLogsRepository
from prevent_auto.services.account_service import AccountService
from prevent_auto.services.manual_reserve_service import ManualReserveService


class _FakeBridge:
    def __init__(self) -> None:
        self.reserve_calls: list[dict[str, object]] = []
        self.reserve_result = (True, "预约成功")

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


class ManualReserveServiceTestCase(unittest.TestCase):
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
        self.service = ManualReserveService(
            bridge=self.bridge,
            action_logs_repository=self.logs_repository,
        )

    def test_manual_reserve_uses_last_detected_booking_seat(self) -> None:
        self.account_service.update_account(
            self.account.id,
            name="主号",
            student_id="20231121130",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )
        self.account_service.repository.update_detected_booking(
            self.account.id,
            room_name="自习室圆形一楼",
            seat_number="23",
            booking_start_at="2026-04-03T08:00:00+08:00",
            booking_status="0",
        )
        account = self.account_service.get_account(self.account.id)

        result = self.service.reserve_next_window(
            account,
            datetime(2026, 4, 3, 8, 25, tzinfo=SHANGHAI_TZ),
        )

        self.assertTrue(result.success)
        self.assertEqual(len(self.bridge.reserve_calls), 1)
        self.assertEqual(self.bridge.reserve_calls[0]["room_name"], "自习室圆形一楼")
        self.assertEqual(self.bridge.reserve_calls[0]["seat_number"], "23")

    def test_manual_reserve_rejects_missing_detected_booking_seat(self) -> None:
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

        result = self.service.reserve_next_window(
            account,
            datetime(2026, 4, 3, 8, 25, tzinfo=SHANGHAI_TZ),
        )

        self.assertFalse(result.success)
        self.assertIn("未识别到最近预约座位", result.message)
        self.assertEqual(self.bridge.reserve_calls, [])
