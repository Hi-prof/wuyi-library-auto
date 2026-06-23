from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime

from prevent_auto.models import BookingSnapshot
from prevent_auto.services.rebook_service import build_rebook_window
from prevent_auto.services.target_seat import resolve_rebook_target_seat


@dataclass(frozen=True)
class ManualRebookTestResult:
    success: bool
    message: str


class ManualRebookTester:
    def __init__(
        self,
        *,
        booking_status_service,
        cancel_service,
        bridge,
        action_logs_repository,
    ) -> None:
        self.booking_status_service = booking_status_service
        self.cancel_service = cancel_service
        self.bridge = bridge
        self.action_logs_repository = action_logs_repository

    def test_cancel_and_rebook_next_window(
        self,
        account,
        now: datetime,
    ) -> ManualRebookTestResult:
        booking = _pick_test_booking(
            self.booking_status_service.list_today_bookings(account, now),
            now,
        )
        if booking is None:
            return ManualRebookTestResult(
                success=False,
                message="测试补约失败：今天没有可取消的待签到预约",
            )

        window = build_rebook_window(now)
        if window is None:
            return ManualRebookTestResult(
                success=False,
                message="测试补约失败：当前时间之后已没有可预约的下一时段",
            )

        cancel_success, cancel_message = self.cancel_service.cancel_booking(
            account,
            booking.booking_id,
        )
        if not cancel_success:
            return ManualRebookTestResult(
                success=False,
                message=f"测试补约失败：{cancel_message}",
            )

        target_seat = resolve_rebook_target_seat(account, booking)
        success, message = self.bridge.reserve_specific_seat(
            account,
            room_name=target_seat.room_name,
            seat_number=target_seat.seat_number,
            date_value=window.date_value,
            start_hour=window.start_hour,
            end_hour=window.end_hour,
        )
        self.action_logs_repository.create(
            account_id=account.id,
            action_type="rebook_test",
            success=success,
            message=message,
            payload_json=json.dumps(
                {
                    "bookingId": booking.booking_id,
                    "roomName": target_seat.room_name,
                    "seatNumber": target_seat.seat_number,
                    "targetDate": window.date_value,
                    "targetStartHour": window.start_hour,
                    "targetEndHour": window.end_hour,
                },
                ensure_ascii=False,
            ),
        )
        if not success:
            return ManualRebookTestResult(
                success=False,
                message=f"测试补约失败：取消成功，但预约下一时段失败：{message}",
            )
        return ManualRebookTestResult(
            success=True,
            message=(
                "测试补约成功："
                f"已取消原预约，并预约 {window.date_value} "
                f"{window.start_hour}:00-{window.end_hour}:00 的 "
                f"{target_seat.room_name} {target_seat.seat_number} 号座位"
            ),
        )


def _pick_test_booking(
    bookings: list[BookingSnapshot],
    now: datetime,
) -> BookingSnapshot | None:
    candidates = [
        booking
        for booking in bookings
        if booking.status in {"0", "8"}
        and booking.start_time + max(booking.duration_seconds, 0) > now.timestamp()
    ]
    if not candidates:
        return None
    return sorted(candidates, key=lambda item: item.start_time)[0]
