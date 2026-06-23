from __future__ import annotations

import unittest
from types import SimpleNamespace

from prevent_auto.services.target_seat import read_last_booking_target_seat
from prevent_auto.services.target_seat import resolve_rebook_target_seat


def _build_account(**overrides):
    fields = {
        "last_detected_room_name": "",
        "last_detected_seat_number": "",
    }
    fields.update(overrides)
    return SimpleNamespace(**fields)


class TargetSeatServiceTestCase(unittest.TestCase):
    def test_read_last_booking_target_seat_returns_detected_booking(self) -> None:
        account = _build_account(
            last_detected_room_name="自习室圆形二楼",
            last_detected_seat_number="166",
        )

        target_seat = read_last_booking_target_seat(account)

        self.assertIsNotNone(target_seat)
        self.assertEqual(target_seat.room_name, "自习室圆形二楼")
        self.assertEqual(target_seat.seat_number, "166")

    def test_resolve_rebook_target_seat_uses_last_detected_booking_before_source_booking(
        self,
    ) -> None:
        account = _build_account(
            last_detected_room_name="自习室圆形二楼",
            last_detected_seat_number="166",
        )
        booking = SimpleNamespace(room_name="自习室圆形一楼", seat_number="19")

        target_seat = resolve_rebook_target_seat(account, booking)

        self.assertEqual(target_seat.room_name, "自习室圆形二楼")
        self.assertEqual(target_seat.seat_number, "166")
