from __future__ import annotations

from pathlib import Path

from prevent_auto.database import connect_database


class AppSettingsRepository:
    """SQLite-backed key/value settings for small server-side feature toggles."""

    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    def get(self, key: str, *, default: str = "") -> str:
        with connect_database(self.database_path) as connection:
            row = connection.execute(
                "SELECT value FROM app_settings WHERE key = ?",
                (key,),
            ).fetchone()
        if row is None:
            return default
        return str(row["value"])

    def set(self, key: str, value: str) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                INSERT INTO app_settings (key, value, updated_at)
                VALUES (?, ?, datetime('now'))
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """,
                (key, value),
            )

    def get_bool(self, key: str, *, default: bool = False) -> bool:
        value = self.get(key, default="true" if default else "false")
        return value.strip().lower() in {"1", "true", "yes", "on", "enabled"}

    def set_bool(self, key: str, value: bool) -> None:
        self.set(key, "true" if value else "false")
