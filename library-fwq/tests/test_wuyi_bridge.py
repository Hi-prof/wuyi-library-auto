from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

from prevent_auto.models import Account
from prevent_auto.services.bridge_to_wuyi import WuyiBridge


def _build_account() -> Account:
    return Account(
        id=1,
        name="主号",
        student_id="20231121130",
        password="secret",
        login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
        seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
        rebook_enabled=True,
        rebook_trigger_minutes=5,
        last_detected_room_name="",
        last_detected_seat_number="",
        last_detected_booking_start_at=None,
        last_detected_booking_status="",
        state_file="runtime/auth-20231121130.json",
        enabled=True,
        account_status="normal",
        last_check_at=None,
        last_status="",
        created_at="2026-04-03T08:00:00",
        updated_at="2026-04-03T08:00:00",
    )


class WuyiBridgeTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.package_root = Path(tempfile.mkdtemp())
        self.bridge = WuyiBridge(self.package_root)
        self.account = _build_account()

    def test_refresh_login_clears_entry_url_cache_before_saving_state(self) -> None:
        automation = MagicMock()
        with (
            patch.object(self.bridge, "_build_automation", return_value=automation),
            patch(
                "prevent_auto.services.bridge_to_wuyi.save_resolved_entry_urls"
            ) as clear_cache,
        ):
            self.bridge.refresh_login(self.account)

        clear_cache.assert_called_once_with(
            self.bridge.config_path, self.account.name, {}
        )
        automation.save_login_state.assert_called_once_with(wait_for_enter=False)

    def test_cancel_booking_refreshes_login_and_retries_once(self) -> None:
        with (
            patch.object(
                self.bridge,
                "_cancel_booking_once",
                side_effect=[RuntimeError("cookie 失效"), (True, "取消成功")],
            ),
            patch.object(self.bridge, "refresh_login") as refresh_login,
        ):
            success, message = self.bridge.cancel_booking(self.account, "booking-1")

        self.assertTrue(success)
        self.assertEqual(message, "取消成功")
        refresh_login.assert_called_once_with(self.account)

    def test_reserve_specific_seat_refreshes_login_and_retries_once(self) -> None:
        request_error = HTTPError(
            url="https://example.com",
            code=404,
            msg="Not Found",
            hdrs=None,
            fp=None,
        )
        with (
            patch.object(
                self.bridge,
                "_reserve_specific_seat_once",
                side_effect=[
                    request_error,
                    (True, "已重约：自习室圆形二楼 165 号座位"),
                ],
            ),
            patch.object(self.bridge, "refresh_login") as refresh_login,
        ):
            success, message = self.bridge.reserve_specific_seat(
                self.account,
                room_name="自习室圆形二楼",
                seat_number="165",
                date_value="2026-04-03",
                start_hour=9,
                end_hour=22,
            )

        self.assertTrue(success)
        self.assertEqual(message, "已重约：自习室圆形二楼 165 号座位")
        refresh_login.assert_called_once_with(self.account)

    def test_fetch_bookings_keeps_booking_list_status_for_finished_booking(
        self,
    ) -> None:
        payload = {
            "content": {
                "defaultItems": [
                    {
                        "id": 21702521,
                        "roomName": "自习室圆形二楼",
                        "seatNum": "60",
                        "status": "3",
                        "time": "1776430800",
                        "duration": "3600",
                        "nowTime": 1776432048,
                        "limitSignAgo": 1800,
                        "limitSignBack": 1800,
                        "limitLeftBack": 1800,
                        "ibeacons": [
                            {"minor": "34173", "id": "564"},
                            {"minor": "34174", "id": "559"},
                        ],
                    },
                    {
                        "id": 21701479,
                        "roomName": "自习室圆形二楼",
                        "seatNum": "161",
                        "status": "4",
                        "time": "1776556800",
                        "duration": "50400",
                        "nowTime": 1776432048,
                        "limitSignAgo": 1800,
                        "limitSignBack": 1800,
                        "limitLeftBack": 1800,
                        "ibeacons": [],
                    },
                ]
            }
        }

        with patch.object(self.bridge, "_fetch_booking_payload", return_value=payload):
            bookings = self.bridge.fetch_bookings(self.account)

        self.assertEqual(len(bookings), 2)
        self.assertEqual(bookings[0].booking_id, "21701479")
        self.assertEqual(bookings[0].status, "4")
        self.assertEqual(bookings[1].booking_id, "21702521")
        self.assertEqual(bookings[1].status, "3")
        self.assertEqual(bookings[1].room_name, "自习室圆形二楼")
        self.assertEqual(bookings[1].seat_number, "60")
        self.assertEqual(bookings[1].start_time, 1776430800)
        self.assertEqual(bookings[1].duration_seconds, 3600)
