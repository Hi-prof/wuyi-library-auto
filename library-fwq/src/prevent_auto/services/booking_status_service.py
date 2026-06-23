from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from wuyi_seat_bot.seat_api import SHANGHAI_TZ, describe_seat_booking_status

from prevent_auto.models import BookingSnapshot
from prevent_auto.services.rebook_service import build_rebook_window

PROTECTED_BOOKING_STATUSES = {"0", "1", "2", "8"}
CANCELABLE_BOOKING_STATUSES = {"0", "8"}


@dataclass(frozen=True)
class RebookSourceDecision:
    booking: BookingSnapshot | None
    skipped_reason: str | None = None


class BookingStatusService:
    def __init__(self, bridge) -> None:
        self.bridge = bridge

    def list_today_bookings(self, account, now: datetime) -> list[BookingSnapshot]:
        current_time = now.astimezone(SHANGHAI_TZ)
        return [
            booking
            for booking in self.bridge.fetch_bookings(account)
            if _booking_start_at(booking).date() == current_time.date()
        ]

    def fetch_all_bookings(self, account) -> list[BookingSnapshot]:
        """直接透传 :meth:`WuyiBridge.fetch_bookings` 的全量结果。

        :class:`MonitorLoop` 会用这份全量结果做两件事：写入 ``booking_snapshots``
        缓存（供号池管理页活跃池 Tab 渲染）+ 调用
        :func:`filter_bookings_for_today` 筛今天用于既有的状态判定。
        """

        return list(self.bridge.fetch_bookings(account))

    @staticmethod
    def filter_bookings_for_today(
        bookings: list[BookingSnapshot],
        now: datetime,
    ) -> list[BookingSnapshot]:
        """从全量预约里挑出与 ``now`` 同一天（Asia/Shanghai）的预约。"""

        current_time = now.astimezone(SHANGHAI_TZ)
        return [
            booking
            for booking in bookings
            if _booking_start_at(booking).date() == current_time.date()
        ]

    def find_rebook_source_booking(
        self,
        account,
        now: datetime,
        *,
        bookings: list[BookingSnapshot] | None = None,
    ) -> BookingSnapshot | None:
        decision = self.evaluate_rebook_source_booking(
            account,
            now,
            bookings=bookings,
        )
        return decision.booking

    def evaluate_rebook_source_booking(
        self,
        account,
        now: datetime,
        *,
        bookings: list[BookingSnapshot] | None = None,
    ) -> RebookSourceDecision:
        current_time = now.astimezone(SHANGHAI_TZ)
        today_bookings = (
            bookings
            if bookings is not None
            else self.list_today_bookings(account, current_time)
        )
        window = build_rebook_window(current_time)
        if window is None:
            return RebookSourceDecision(
                booking=None,
                skipped_reason="今日已无可重约时段",
            )
        target_date = datetime.fromisoformat(window.date_value).date()
        target_start = datetime(
            target_date.year,
            target_date.month,
            target_date.day,
            window.start_hour,
            tzinfo=SHANGHAI_TZ,
        ).timestamp()
        if any(
            _covers_target_window(booking, target_start) for booking in today_bookings
        ):
            return RebookSourceDecision(
                booking=None,
                skipped_reason="下一时段已存在预约，无需保号",
            )
        candidates = [
            booking
            for booking in today_bookings
            if is_rebook_source_booking(booking, current_time, target_start)
        ]
        if not candidates:
            return RebookSourceDecision(
                booking=None,
                skipped_reason="今天没有可用于保号的待签到预约",
            )
        booking = sorted(candidates, key=lambda item: item.start_time)[0]
        trigger_at = _build_rebook_trigger_at(
            booking,
            trigger_minutes=max(account.rebook_trigger_minutes, 0),
        )
        if current_time.timestamp() < trigger_at:
            trigger_label = datetime.fromtimestamp(trigger_at, SHANGHAI_TZ).strftime(
                "%H:%M"
            )
            return RebookSourceDecision(
                booking=None,
                skipped_reason=(
                    f"还没到保号触发时间，计划在 {trigger_label} 开始检测"
                ),
            )
        return RebookSourceDecision(booking=booking)

    def pick_primary_booking(
        self,
        bookings: list[BookingSnapshot],
        now: datetime,
    ) -> BookingSnapshot | None:
        return _pick_primary_booking(bookings, now.astimezone(SHANGHAI_TZ))

    def describe_today_booking(
        self,
        account,
        now: datetime,
        *,
        bookings: list[BookingSnapshot] | None = None,
    ) -> str | None:
        today_bookings = (
            bookings if bookings is not None else self.list_today_bookings(account, now)
        )
        selected_booking = self.pick_primary_booking(today_bookings, now)
        if selected_booking is None:
            return None

        prefix = (
            "今日预约"
            if selected_booking.status in PROTECTED_BOOKING_STATUSES
            else "今日最近预约"
        )
        status_label = describe_seat_booking_status(selected_booking.status)
        time_range = _format_booking_time_range(selected_booking)
        parts = [f"{selected_booking.room_name} {selected_booking.seat_number} 号座位"]
        if time_range:
            parts.append(time_range)
        parts.append(status_label)
        return f"{prefix}：{' · '.join(parts)}"


def is_rebook_source_booking(
    booking: BookingSnapshot,
    now: datetime,
    target_start: float,
) -> bool:
    if booking.status not in CANCELABLE_BOOKING_STATUSES:
        return False
    booking_start = datetime.fromtimestamp(booking.start_time, SHANGHAI_TZ)
    if booking_start.date() != now.astimezone(SHANGHAI_TZ).date():
        return False
    booking_end = _booking_end_timestamp(booking)
    return booking.start_time < target_start and now.timestamp() < booking_end


def _covers_target_window(booking: BookingSnapshot, target_start: float) -> bool:
    if booking.status not in PROTECTED_BOOKING_STATUSES:
        return False
    return booking.start_time <= target_start < _booking_end_timestamp(booking)


def _pick_primary_booking(
    bookings: list[BookingSnapshot],
    now: datetime,
) -> BookingSnapshot | None:
    active_candidates = [
        booking
        for booking in bookings
        if booking.status in {"1", "2"}
        and _booking_end_timestamp(booking) > now.timestamp()
    ]
    if active_candidates:
        return sorted(active_candidates, key=lambda item: item.start_time)[0]

    pending_candidates = [
        booking
        for booking in bookings
        if booking.status in CANCELABLE_BOOKING_STATUSES
        and _booking_end_timestamp(booking) > now.timestamp()
    ]
    if pending_candidates:
        return sorted(pending_candidates, key=lambda item: item.start_time)[0]

    if not bookings:
        return None
    return sorted(bookings, key=lambda item: item.start_time, reverse=True)[0]


def _format_booking_time_range(booking: BookingSnapshot) -> str:
    if booking.duration_seconds <= 0:
        return ""
    booking_start = _booking_start_at(booking)
    booking_end = datetime.fromtimestamp(_booking_end_timestamp(booking), SHANGHAI_TZ)
    return f"{booking_start.strftime('%H:%M')}-{booking_end.strftime('%H:%M')}"


def _booking_start_at(booking: BookingSnapshot) -> datetime:
    return datetime.fromtimestamp(booking.start_time, SHANGHAI_TZ)


def _booking_end_timestamp(booking: BookingSnapshot) -> int:
    return booking.start_time + max(booking.duration_seconds, 0)


def _build_rebook_trigger_at(booking: BookingSnapshot, *, trigger_minutes: int) -> float:
    checkin_deadline_at = booking.checkin_deadline_at or booking.start_time
    return checkin_deadline_at - max(trigger_minutes, 0) * 60
