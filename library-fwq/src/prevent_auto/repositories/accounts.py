from __future__ import annotations

from datetime import datetime
from pathlib import Path
import re

from prevent_auto.database import connect_database
from prevent_auto.models import Account


class AccountsRepository:
    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    def create(
        self,
        *,
        name: str,
        student_id: str,
        password: str,
        login_url: str,
        seat_url: str,
        rebook_enabled: bool,
        rebook_trigger_minutes: int,
        enabled: bool,
    ) -> Account:
        now = _now_iso()
        state_file = _build_state_file(student_id)
        with connect_database(self.database_path) as connection:
            cursor = connection.execute(
                """
                INSERT INTO accounts (
                    name, student_id, password, login_url, seat_url,
                    rebook_enabled, rebook_trigger_minutes, state_file,
                    account_status,
                    enabled, last_check_at, last_status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    name,
                    student_id,
                    password,
                    login_url,
                    seat_url,
                    1 if rebook_enabled else 0,
                    rebook_trigger_minutes,
                    state_file,
                    "unknown",
                    1 if enabled else 0,
                    None,
                    "",
                    now,
                    now,
                ),
            )
            account_id = int(cursor.lastrowid)
        return self.get(account_id)

    def list_all(self) -> list[Account]:
        with connect_database(self.database_path) as connection:
            rows = connection.execute(
                "SELECT * FROM accounts ORDER BY enabled DESC, name ASC"
            ).fetchall()
        return [_row_to_account(row) for row in rows]

    def list_enabled(self) -> list[Account]:
        with connect_database(self.database_path) as connection:
            rows = connection.execute(
                "SELECT * FROM accounts WHERE enabled = 1 ORDER BY name ASC"
            ).fetchall()
        return [_row_to_account(row) for row in rows]

    def get(self, account_id: int) -> Account:
        with connect_database(self.database_path) as connection:
            row = connection.execute(
                "SELECT * FROM accounts WHERE id = ?",
                (account_id,),
            ).fetchone()
        if row is None:
            raise ValueError(f"未找到账号：{account_id}")
        return _row_to_account(row)

    def update(
        self,
        account_id: int,
        *,
        name: str,
        student_id: str,
        password: str,
        login_url: str,
        seat_url: str,
        rebook_enabled: bool,
        rebook_trigger_minutes: int,
        enabled: bool,
    ) -> Account:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE accounts
                SET name = ?, student_id = ?, password = ?, login_url = ?, seat_url = ?,
                    rebook_enabled = ?, rebook_trigger_minutes = ?, enabled = ?, updated_at = ?
                WHERE id = ?
                """,
                (
                    name,
                    student_id,
                    password,
                    login_url,
                    seat_url,
                    1 if rebook_enabled else 0,
                    rebook_trigger_minutes,
                    1 if enabled else 0,
                    _now_iso(),
                    account_id,
                ),
            )
        return self.get(account_id)

    def set_enabled(self, account_id: int, enabled: bool) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                "UPDATE accounts SET enabled = ?, updated_at = ? WHERE id = ?",
                (1 if enabled else 0, _now_iso(), account_id),
            )

    def update_account_status(self, account_id: int, *, account_status: str) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE accounts
                SET account_status = ?, updated_at = ?
                WHERE id = ?
                """,
                (account_status, _now_iso(), account_id),
            )

    def delete(self, account_id: int) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute("DELETE FROM accounts WHERE id = ?", (account_id,))

    def update_status(
        self,
        account_id: int,
        *,
        last_check_at: str,
        last_status: str,
    ) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE accounts
                SET last_check_at = ?, last_status = ?, updated_at = ?
                WHERE id = ?
                """,
                (last_check_at, last_status, _now_iso(), account_id),
            )

    def update_detected_booking(
        self,
        account_id: int,
        *,
        room_name: str,
        seat_number: str,
        booking_start_at: str | None,
        booking_status: str,
    ) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE accounts
                SET last_detected_room_name = ?,
                    last_detected_seat_number = ?,
                    last_detected_booking_start_at = ?,
                    last_detected_booking_status = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (
                    room_name,
                    seat_number,
                    booking_start_at,
                    booking_status,
                    _now_iso(),
                    account_id,
                ),
            )


def _row_to_account(row) -> Account:
    return Account(
        id=int(row["id"]),
        name=str(row["name"]),
        student_id=str(row["student_id"]),
        password=str(row["password"]),
        login_url=str(row["login_url"]),
        seat_url=str(row["seat_url"]),
        rebook_enabled=bool(int(row["rebook_enabled"] or 0)),
        rebook_trigger_minutes=int(row["rebook_trigger_minutes"] or 5),
        last_detected_room_name=str(row["last_detected_room_name"] or ""),
        last_detected_seat_number=str(row["last_detected_seat_number"] or ""),
        last_detected_booking_start_at=row["last_detected_booking_start_at"],
        last_detected_booking_status=str(row["last_detected_booking_status"] or ""),
        state_file=str(row["state_file"]),
        enabled=bool(int(row["enabled"])),
        account_status=str(row["account_status"] or "unknown"),
        last_check_at=row["last_check_at"],
        last_status=str(row["last_status"]),
        created_at=str(row["created_at"]),
        updated_at=str(row["updated_at"]),
    )


def _build_state_file(student_id: str) -> str:
    slug = re.sub(r"[^0-9A-Za-z_-]+", "-", student_id.strip()).strip("-").lower()
    slug = slug or "account"
    return f"runtime/auth-{slug}.json"


def _now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat()
