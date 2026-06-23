from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path

from prevent_auto.database import initialize_database


class InitializeDatabaseTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"

    def test_initialize_database_creates_required_tables(self) -> None:
        initialize_database(self.database_path)

        with sqlite3.connect(self.database_path) as connection:
            names = {
                row[0]
                for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                )
            }

        self.assertTrue(
            {
                "accounts",
                "monitor_records",
                "rebook_jobs",
                "action_logs",
                "account_login_states",
            }
            <= names
        )

    def test_initialize_database_creates_log_indexes(self) -> None:
        initialize_database(self.database_path)

        with sqlite3.connect(self.database_path) as connection:
            names = {
                row[0]
                for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='index'"
                )
            }

        self.assertTrue(
            {
                "idx_monitor_records_account_detected_at",
                "idx_action_logs_account_created_at",
                "idx_rebook_jobs_status_run_at",
                "idx_account_login_states_refreshed_at",
            }
            <= names
        )
