from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

from wuyi_seat_bot.seat_api import SHANGHAI_TZ

from prevent_auto.models import BookingSnapshot, RebookJob, RebookWindow
from prevent_auto.repositories.action_logs import ActionLogsRepository
from prevent_auto.repositories.rebook_jobs import RebookJobsRepository


@dataclass(frozen=True)
class RebookDecision:
    job: RebookJob | None
    skipped_reason: str | None = None


def build_rebook_window(current_time: datetime) -> RebookWindow | None:
    normalized = current_time.astimezone(SHANGHAI_TZ)
    next_hour = normalized.replace(minute=0, second=0, microsecond=0)
    if normalized.minute or normalized.second or normalized.microsecond:
        next_hour += timedelta(hours=1)
    if next_hour.hour >= 22:
        return None
    return RebookWindow(
        date_value=next_hour.date().isoformat(),
        start_hour=next_hour.hour,
        end_hour=22,
    )


class RebookService:
    def __init__(self, database_path: str | Path) -> None:
        self.jobs_repository = RebookJobsRepository(database_path)
        self.logs_repository = ActionLogsRepository(database_path)

    def schedule_rebook(
        self,
        *,
        account_id: int,
        booking: BookingSnapshot,
        now: datetime,
        room_name: str | None = None,
        seat_number: str | None = None,
        run_at: datetime | None = None,
    ) -> RebookDecision:
        window = build_rebook_window(now)
        if window is None:
            self.logs_repository.create(
                account_id=account_id,
                action_type="rebook",
                success=False,
                message="今日已无可重约时段",
            )
            return RebookDecision(job=None, skipped_reason="今日已无可重约时段")

        target_run_at = (run_at or now + timedelta(minutes=1)).replace(microsecond=0)
        job = self.jobs_repository.create(
            account_id=account_id,
            source_booking_id=booking.booking_id,
            target_date=window.date_value,
            target_start_hour=window.start_hour,
            target_end_hour=window.end_hour,
            room_name=room_name or booking.room_name,
            seat_number=seat_number or booking.seat_number,
            seat_id="",
            run_at=target_run_at.isoformat(),
        )
        return RebookDecision(job=job)
