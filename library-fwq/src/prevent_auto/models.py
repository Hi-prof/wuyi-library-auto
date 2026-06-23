from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Account:
    id: int
    name: str
    student_id: str
    password: str
    login_url: str
    seat_url: str
    rebook_enabled: bool
    rebook_trigger_minutes: int
    last_detected_room_name: str
    last_detected_seat_number: str
    last_detected_booking_start_at: str | None
    last_detected_booking_status: str
    state_file: str
    enabled: bool
    account_status: str
    last_check_at: str | None
    last_status: str
    created_at: str
    updated_at: str


@dataclass(frozen=True)
class BookingSnapshot:
    booking_id: str
    room_name: str
    seat_number: str
    status: str
    start_time: int
    duration_seconds: int
    checkin_deadline_at: int | None = None


@dataclass(frozen=True)
class RebookWindow:
    date_value: str
    start_hour: int
    end_hour: int


@dataclass(frozen=True)
class RebookJob:
    id: int
    account_id: int
    source_booking_id: str
    target_date: str
    target_start_hour: int
    target_end_hour: int
    room_name: str
    seat_number: str
    seat_id: str
    run_at: str
    status: str
    attempt_count: int
    last_error: str
    created_at: str
    updated_at: str


@dataclass(frozen=True)
class ActionLogEntry:
    id: int
    account_id: int
    action_type: str
    success: bool
    message: str
    payload_json: str
    created_at: str
