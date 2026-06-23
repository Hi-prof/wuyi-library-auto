from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TargetSeat:
    room_name: str
    seat_number: str


def read_last_booking_target_seat(account) -> TargetSeat | None:
    room_name = account.last_detected_room_name.strip()
    seat_number = account.last_detected_seat_number.strip()
    if not room_name or not seat_number:
        return None
    return TargetSeat(room_name=room_name, seat_number=seat_number)


def resolve_rebook_target_seat(account, booking) -> TargetSeat:
    target_seat = read_last_booking_target_seat(account)
    if target_seat is not None:
        return target_seat
    return TargetSeat(
        room_name=booking.room_name,
        seat_number=booking.seat_number,
    )
