from __future__ import annotations

from datetime import datetime
from pathlib import Path

from prevent_auto.database import connect_database
from prevent_auto.models import RebookJob


class RebookJobsRepository:
    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    def create(
        self,
        *,
        account_id: int,
        source_booking_id: str,
        target_date: str,
        target_start_hour: int,
        target_end_hour: int,
        room_name: str,
        seat_number: str,
        seat_id: str,
        run_at: str,
        status: str = "pending",
    ) -> RebookJob:
        now = _now_iso()
        with connect_database(self.database_path) as connection:
            cursor = connection.execute(
                """
                INSERT INTO rebook_jobs (
                    account_id, source_booking_id, target_date, target_start_hour,
                    target_end_hour, room_name, seat_number, seat_id, run_at,
                    status, attempt_count, last_error, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, '', ?, ?)
                """,
                (
                    account_id,
                    source_booking_id,
                    target_date,
                    target_start_hour,
                    target_end_hour,
                    room_name,
                    seat_number,
                    seat_id,
                    run_at,
                    status,
                    now,
                    now,
                ),
            )
            job_id = int(cursor.lastrowid)
        return self.get(job_id)

    def get(self, job_id: int) -> RebookJob:
        with connect_database(self.database_path) as connection:
            row = connection.execute(
                "SELECT * FROM rebook_jobs WHERE id = ?",
                (job_id,),
            ).fetchone()
        if row is None:
            raise ValueError(f"未找到重约任务：{job_id}")
        return _row_to_job(row)

    def list_pending_jobs(self) -> list[RebookJob]:
        with connect_database(self.database_path) as connection:
            rows = connection.execute(
                "SELECT * FROM rebook_jobs WHERE status = 'pending' ORDER BY run_at ASC"
            ).fetchall()
        return [_row_to_job(row) for row in rows]

    def list_due_jobs(self, run_at: str) -> list[RebookJob]:
        with connect_database(self.database_path) as connection:
            rows = connection.execute(
                """
                SELECT * FROM rebook_jobs
                WHERE status = 'pending' AND run_at <= ?
                ORDER BY run_at ASC
                """,
                (run_at,),
            ).fetchall()
        return [_row_to_job(row) for row in rows]

    def list_recent(self, *, limit: int = 50) -> list[RebookJob]:
        with connect_database(self.database_path) as connection:
            rows = connection.execute(
                "SELECT * FROM rebook_jobs ORDER BY created_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [_row_to_job(row) for row in rows]

    def mark_running(self, job_id: int) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE rebook_jobs
                SET status = ?, updated_at = ?
                WHERE id = ?
                """,
                ("running", _now_iso(), job_id),
            )

    def mark_success(self, job_id: int) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE rebook_jobs
                SET status = ?, attempt_count = attempt_count + 1, updated_at = ?
                WHERE id = ?
                """,
                ("success", _now_iso(), job_id),
            )

    def mark_failed(self, job_id: int, *, last_error: str) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE rebook_jobs
                SET status = 'failed', attempt_count = attempt_count + 1,
                    last_error = ?, updated_at = ?
                WHERE id = ?
                """,
                (last_error, _now_iso(), job_id),
            )

    def mark_skipped(self, job_id: int, *, last_error: str) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE rebook_jobs
                SET status = 'skipped', last_error = ?, updated_at = ?
                WHERE id = ?
                """,
                (last_error, _now_iso(), job_id),
            )

    def delete_finished_older_than(self, cutoff_created_at: str) -> int:
        with connect_database(self.database_path) as connection:
            cursor = connection.execute(
                """
                DELETE FROM rebook_jobs
                WHERE created_at < ? AND status <> 'pending'
                """,
                (cutoff_created_at,),
            )
        return int(cursor.rowcount)


def _row_to_job(row) -> RebookJob:
    return RebookJob(
        id=int(row["id"]),
        account_id=int(row["account_id"]),
        source_booking_id=str(row["source_booking_id"]),
        target_date=str(row["target_date"]),
        target_start_hour=int(row["target_start_hour"]),
        target_end_hour=int(row["target_end_hour"]),
        room_name=str(row["room_name"]),
        seat_number=str(row["seat_number"]),
        seat_id=str(row["seat_id"]),
        run_at=str(row["run_at"]),
        status=str(row["status"]),
        attempt_count=int(row["attempt_count"]),
        last_error=str(row["last_error"]),
        created_at=str(row["created_at"]),
        updated_at=str(row["updated_at"]),
    )


def _now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat()
