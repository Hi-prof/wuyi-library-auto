from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from prevent_auto.database import connect_database
from prevent_auto.logging import scrub
from prevent_auto.models import ActionLogEntry


class ActionLogsRepository:
    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    def create(
        self,
        *,
        account_id: int,
        action_type: str,
        success: bool,
        message: str,
        payload_json: str = "{}",
    ) -> ActionLogEntry:
        created_at = _now_iso()
        with connect_database(self.database_path) as connection:
            cursor = connection.execute(
                """
                INSERT INTO action_logs (
                    account_id, action_type, success, message, payload_json, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    account_id,
                    action_type,
                    1 if success else 0,
                    message,
                    payload_json,
                    created_at,
                ),
            )
            log_id = int(cursor.lastrowid)
        return self.get(log_id)

    def get(self, log_id: int) -> ActionLogEntry:
        with connect_database(self.database_path) as connection:
            row = connection.execute(
                "SELECT * FROM action_logs WHERE id = ?",
                (log_id,),
            ).fetchone()
        if row is None:
            raise ValueError(f"未找到日志：{log_id}")
        return _row_to_action_log(row)

    def list_recent(
        self,
        *,
        account_id: int | None = None,
        action_type: str | None = None,
        limit: int = 50,
    ) -> list[ActionLogEntry]:
        query = "SELECT * FROM action_logs"
        filters: list[str] = []
        params: list[object] = []
        if account_id is not None:
            filters.append("account_id = ?")
            params.append(account_id)
        if action_type is not None:
            filters.append("action_type = ?")
            params.append(action_type)
        if filters:
            query += " WHERE " + " AND ".join(filters)
        query += " ORDER BY created_at DESC LIMIT ?"
        params.append(limit)
        with connect_database(self.database_path) as connection:
            rows = connection.execute(query, tuple(params)).fetchall()
        return [_row_to_action_log(row) for row in rows]

    def delete_older_than(self, cutoff_created_at: str) -> int:
        with connect_database(self.database_path) as connection:
            cursor = connection.execute(
                "DELETE FROM action_logs WHERE created_at < ?",
                (cutoff_created_at,),
            )
        return int(cursor.rowcount)


def _row_to_action_log(row) -> ActionLogEntry:
    return ActionLogEntry(
        id=int(row["id"]),
        account_id=int(row["account_id"]),
        action_type=str(row["action_type"]),
        success=bool(int(row["success"])),
        message=str(row["message"]),
        payload_json=str(row["payload_json"]),
        created_at=str(row["created_at"]),
    )


def _now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat()


def safe_payload_json(payload: dict[str, object]) -> str:
    return json.dumps(scrub(payload), ensure_ascii=False, sort_keys=True)
