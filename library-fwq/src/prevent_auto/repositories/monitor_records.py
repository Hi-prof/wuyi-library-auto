from __future__ import annotations

from datetime import datetime
from pathlib import Path

from prevent_auto.database import connect_database


class MonitorRecordsRepository:
    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    def create(
        self,
        *,
        account_id: int,
        booking_id: str,
        booking_status: str,
        booking_start_at: str | None,
        detected_at: str,
        decision: str,
        detail: str,
    ) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                INSERT INTO monitor_records (
                    account_id, booking_id, booking_status, booking_start_at,
                    detected_at, decision, detail
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    account_id,
                    booking_id,
                    booking_status,
                    booking_start_at,
                    detected_at,
                    decision,
                    detail,
                ),
            )

    def list_recent(self, *, account_id: int | None = None, limit: int = 50) -> list[dict[str, str]]:
        query = "SELECT * FROM monitor_records"
        params: tuple[object, ...] = ()
        if account_id is not None:
            query += " WHERE account_id = ?"
            params = (account_id,)
        query += " ORDER BY detected_at DESC LIMIT ?"
        params = (*params, limit)
        with connect_database(self.database_path) as connection:
            rows = connection.execute(query, params).fetchall()
        return [dict(row) for row in rows]

    def delete_older_than(self, cutoff_detected_at: str) -> int:
        with connect_database(self.database_path) as connection:
            cursor = connection.execute(
                "DELETE FROM monitor_records WHERE detected_at < ?",
                (cutoff_detected_at,),
            )
        return int(cursor.rowcount)


def now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat()
