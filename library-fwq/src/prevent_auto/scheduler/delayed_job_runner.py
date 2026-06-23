from __future__ import annotations

import json
from collections import defaultdict
from datetime import datetime
import threading

from prevent_auto.repositories.action_logs import ActionLogsRepository


class DelayedJobRunner:
    def __init__(
        self,
        *,
        account_service,
        rebook_service,
        bridge,
        now_provider,
        account_locks: dict[int, threading.Lock] | None = None,
    ) -> None:
        self.account_service = account_service
        self.rebook_service = rebook_service
        self.bridge = bridge
        self.now_provider = now_provider
        self.account_locks = account_locks or defaultdict(threading.Lock)
        self.logs_repository = ActionLogsRepository(
            self.rebook_service.jobs_repository.database_path
        )

    def run_due_jobs_once(self) -> None:
        current_time = self.now_provider().replace(microsecond=0).isoformat()
        jobs = self.rebook_service.jobs_repository.list_due_jobs(current_time)
        for job in jobs:
            with self.account_locks[job.account_id]:
                self._run_job(job)

    def _run_job(self, job) -> None:
        account = self.account_service.get_account(job.account_id)
        self.rebook_service.jobs_repository.mark_running(job.id)
        success, message = self.bridge.reserve_specific_seat(
            account,
            room_name=job.room_name,
            seat_number=job.seat_number,
            date_value=job.target_date,
            start_hour=job.target_start_hour,
            end_hour=job.target_end_hour,
        )
        if success:
            self.rebook_service.jobs_repository.mark_success(job.id)
        else:
            self.rebook_service.jobs_repository.mark_failed(job.id, last_error=message)
        self.account_service.update_status(
            account.id,
            last_check_at=self.now_provider().replace(microsecond=0).isoformat(),
            last_status=message,
        )
        self.logs_repository.create(
            account_id=account.id,
            action_type="rebook",
            success=success,
            message=message,
            payload_json=json.dumps(
                {
                    "seatNumber": job.seat_number,
                    "targetDate": job.target_date,
                    "targetStartHour": job.target_start_hour,
                    "targetEndHour": job.target_end_hour,
                },
                ensure_ascii=False,
            ),
        )
