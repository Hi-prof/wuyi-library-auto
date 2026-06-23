from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from prevent_auto.database import connect_database


@dataclass(frozen=True)
class AccountLoginState:
    account_id: int
    state_file: str
    state_json: str
    refreshed_at: str


class AccountLoginStatesRepository:
    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    def upsert(
        self,
        account_id: int,
        *,
        state_file: str,
        state_json: str,
        refreshed_at: str | None = None,
    ) -> None:
        timestamp = refreshed_at or _now_iso()
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                INSERT INTO account_login_states (
                    account_id, state_file, state_json, refreshed_at
                )
                VALUES (?, ?, ?, ?)
                ON CONFLICT(account_id) DO UPDATE SET
                    state_file = excluded.state_file,
                    state_json = excluded.state_json,
                    refreshed_at = excluded.refreshed_at
                """,
                (int(account_id), state_file, state_json, timestamp),
            )

    def get(self, account_id: int) -> AccountLoginState | None:
        with connect_database(self.database_path) as connection:
            row = connection.execute(
                """
                SELECT account_id, state_file, state_json, refreshed_at
                FROM account_login_states
                WHERE account_id = ?
                """,
                (int(account_id),),
            ).fetchone()
        if row is None:
            return None
        return AccountLoginState(
            account_id=int(row["account_id"]),
            state_file=str(row["state_file"]),
            state_json=str(row["state_json"]),
            refreshed_at=str(row["refreshed_at"]),
        )


def _now_iso() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
