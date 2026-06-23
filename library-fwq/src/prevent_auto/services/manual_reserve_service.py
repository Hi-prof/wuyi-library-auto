from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime

from prevent_auto.services.rebook_service import build_rebook_window
from prevent_auto.services.target_seat import read_last_booking_target_seat


@dataclass(frozen=True)
class ManualReserveResult:
    success: bool
    message: str


class ManualReserveService:
    def __init__(self, *, bridge, action_logs_repository) -> None:
        self.bridge = bridge
        self.action_logs_repository = action_logs_repository

    def reserve_next_window(self, account, now: datetime) -> ManualReserveResult:
        target_seat = read_last_booking_target_seat(account)
        if target_seat is None:
            return ManualReserveResult(
                success=False,
                message="手动预约失败：未识别到最近预约座位",
            )

        window = build_rebook_window(now)
        if window is None:
            return ManualReserveResult(
                success=False,
                message="手动预约失败：今天已无可预约时段",
            )

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
            action_type="manual_reserve",
            success=success,
            message=message,
            payload_json=json.dumps(
                {
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
            return ManualReserveResult(
                success=False,
                message=f"手动预约失败：{message}",
            )
        return ManualReserveResult(
            success=True,
            message=(
                "手动预约成功："
                f"{window.date_value} {window.start_hour}:00-{window.end_hour}:00 · "
                f"{target_seat.room_name} {target_seat.seat_number} 号座位"
            ),
        )
