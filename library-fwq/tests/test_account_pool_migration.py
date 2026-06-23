"""account-pool-tri-sync 启动迁移钩子单元测试。

覆盖 ``database._ensure_account_pool_columns`` 在以下场景下的行为：

1. 全新库：新增列 / 索引 / 三张配套表；存量为零时不打容量 WARN，写入一条
   ``startup_migration`` 审计行。
2. 已有账号 + 注入 ``ACCOUNT_POOL_SECRET_KEY`` 的库：明文密码迁移到
   ``password_cipher / nonce / tag``，旧 ``password`` 列清空；二次调用幂等，
   不会重复加密、不会改变密文长度。
3. 总行数超过 ``POOL_CAPACITY`` 时仅打 WARN，不阻断启动；审计行的
   ``payload_json`` 含 ``pool_capacity_exceeded=true``。
"""

from __future__ import annotations

import json
import logging
import sqlite3
import tempfile
import unittest
from datetime import time as dtime
from pathlib import Path

from prevent_auto.account_pool.constants import POOL_CAPACITY
from prevent_auto.database import initialize_database
from prevent_auto.services.account_password_cipher import (
    AccountPasswordCipher,
    EncryptedPassword,
    generate_secret_key,
)
from prevent_auto.settings import PreventAutoSettings


def _build_settings(temp_dir: Path, secret_key: str) -> PreventAutoSettings:
    return PreventAutoSettings(
        project_root=temp_dir,
        package_root=temp_dir,
        data_dir=temp_dir / "data",
        runtime_dir=temp_dir / "runtime",
        database_path=temp_dir / "data" / "prevent_auto.db",
        host="127.0.0.1",
        port=5000,
        monitor_interval_seconds=60,
        rebook_poll_interval_seconds=15,
        log_retention_days=30,
        daily_status_refresh_time=dtime(8, 10),
        account_pool_secret_key=secret_key,
    )


def _seed_legacy_account(
    database_path: Path,
    *,
    name: str,
    student_id: str,
    password: str,
    login_url: str = "https://example.test/login",
) -> None:
    """绕过迁移直接插入一行老 schema 的账号，模拟旧库存量数据。"""

    with sqlite3.connect(database_path) as connection:
        connection.execute(
            """
            INSERT INTO accounts (
                name, student_id, password, login_url, seat_url,
                rebook_enabled, rebook_trigger_minutes, state_file,
                account_status, enabled,
                last_check_at, last_status, created_at, updated_at,
                pool_status, pool_updated_at, pool_previous,
                suspended_at, suspension_expires_at,
                display_name,
                password_cipher, password_nonce, password_tag,
                revision, deleted_at, last_blacklist_evidence
            )
            VALUES (?, ?, ?, ?, ?,
                    0, 5, ?, 'unknown', 1,
                    NULL, '', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z',
                    '', '', '', NULL, NULL, '',
                    X'', X'', X'',
                    0, NULL, '')
            """,
            (
                name,
                student_id,
                password,
                login_url,
                "https://example.test/seat",
                f"runtime/auth-{student_id}.json",
            ),
        )
        connection.commit()


class AccountPoolMigrationTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "data" / "prevent_auto.db"
        self.secret_key = generate_secret_key()
        self.settings = _build_settings(self.temp_dir, self.secret_key)

    def test_fresh_database_creates_pool_schema_and_audit_row(self) -> None:
        initialize_database(self.database_path, settings=self.settings)

        with sqlite3.connect(self.database_path) as connection:
            connection.row_factory = sqlite3.Row
            tables = {
                row[0]
                for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                ).fetchall()
            }
            indexes = {
                row[0]
                for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='index'"
                ).fetchall()
            }
            account_columns = {
                row[1]
                for row in connection.execute("PRAGMA table_info(accounts)").fetchall()
            }
            audit_rows = connection.execute(
                "SELECT * FROM pool_audit_log ORDER BY id ASC"
            ).fetchall()

        self.assertTrue(
            {"automation_tasks", "pool_audit_log", "client_api_tokens"} <= tables
        )
        self.assertTrue(
            {
                "uniq_accounts_student_login_active",
                "idx_accounts_pool_status_pool_updated_at",
                "idx_accounts_pool_status_suspension_expires_at",
                "idx_automation_tasks_account_id",
                "idx_pool_audit_log_account_created",
                "idx_pool_audit_log_action_created",
                "idx_pool_audit_log_created_at",
                "idx_client_api_tokens_hash",
            }
            <= indexes
        )
        self.assertTrue(
            {
                "pool_status",
                "pool_updated_at",
                "pool_previous",
                "suspended_at",
                "suspension_expires_at",
                "display_name",
                "password_cipher",
                "password_nonce",
                "password_tag",
                "revision",
                "deleted_at",
                "last_blacklist_evidence",
            }
            <= account_columns
        )
        self.assertEqual(len(audit_rows), 1)
        first_row = audit_rows[0]
        self.assertEqual(first_row["audit_action"], "startup_migration")
        self.assertEqual(first_row["operator"], "system")
        self.assertEqual(first_row["client_kind"], "system")
        self.assertEqual(first_row["success"], 1)
        payload = json.loads(first_row["payload_json"])
        self.assertEqual(payload["capacity_total"], 0)
        self.assertFalse(payload["pool_capacity_exceeded"])
        self.assertTrue(payload["cipher_available"])
        self.assertEqual(payload["migrated_password_count"], 0)

    def test_existing_plaintext_passwords_are_encrypted_and_idempotent(self) -> None:
        # 第一次 init 建立 schema 并加列，但没有任何账号需要迁移。
        initialize_database(self.database_path, settings=self.settings)
        # 注入一行存量账号（plaintext 密码 + 空 cipher 列），模拟「旧库已被迁移过列、
        # 但加密迁移阶段失败 / 未跑过」的边缘情况。
        _seed_legacy_account(
            self.database_path,
            name="alice",
            student_id="20231121130",
            password="P@ssw0rd-中文",
        )
        _seed_legacy_account(
            self.database_path,
            name="bob",
            student_id="20231121200",
            password="another-password",
        )

        # 第二次 init 应该把两条 plaintext 都迁到 cipher。
        initialize_database(self.database_path, settings=self.settings)

        cipher = AccountPasswordCipher(self.secret_key)
        with sqlite3.connect(self.database_path) as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                "SELECT id, password, password_cipher, password_nonce, password_tag "
                "FROM accounts ORDER BY id"
            ).fetchall()
            audit_rows = connection.execute(
                "SELECT payload_json FROM pool_audit_log "
                "WHERE audit_action='startup_migration' "
                "ORDER BY id"
            ).fetchall()

        self.assertEqual(len(rows), 2)
        plaintexts = {
            "20231121130": "P@ssw0rd-中文",
            "20231121200": "another-password",
        }
        for row in rows:
            self.assertEqual(row["password"], "")
            self.assertGreater(len(row["password_cipher"]), 0)
            self.assertEqual(len(row["password_nonce"]), AccountPasswordCipher.NONCE_SIZE)
            self.assertEqual(len(row["password_tag"]), AccountPasswordCipher.TAG_SIZE)
            student_id = (
                "20231121130" if row["id"] == 1 else "20231121200"
            )
            decrypted = cipher.decrypt(
                EncryptedPassword(
                    cipher=bytes(row["password_cipher"]),
                    nonce=bytes(row["password_nonce"]),
                    tag=bytes(row["password_tag"]),
                )
            )
            self.assertEqual(decrypted, plaintexts[student_id])

        # 第二次 init 写入了第二条 startup_migration 审计行，且 migrated_password_count=2。
        self.assertEqual(len(audit_rows), 2)
        first_payload = json.loads(audit_rows[0]["payload_json"])
        second_payload = json.loads(audit_rows[1]["payload_json"])
        self.assertEqual(first_payload["migrated_password_count"], 0)
        self.assertEqual(second_payload["migrated_password_count"], 2)

        # 第三次 init 应该幂等：不再迁移（migrated_password_count=0），密文长度不变。
        initialize_database(self.database_path, settings=self.settings)
        with sqlite3.connect(self.database_path) as connection:
            connection.row_factory = sqlite3.Row
            rows_after = connection.execute(
                "SELECT id, password_cipher, password_nonce, password_tag "
                "FROM accounts ORDER BY id"
            ).fetchall()
            third_payload = json.loads(
                connection.execute(
                    "SELECT payload_json FROM pool_audit_log "
                    "WHERE audit_action='startup_migration' "
                    "ORDER BY id DESC LIMIT 1"
                ).fetchone()[0]
            )
        for before, after in zip(rows, rows_after, strict=True):
            self.assertEqual(bytes(before["password_cipher"]), bytes(after["password_cipher"]))
            self.assertEqual(bytes(before["password_nonce"]), bytes(after["password_nonce"]))
            self.assertEqual(bytes(before["password_tag"]), bytes(after["password_tag"]))
        self.assertEqual(third_payload["migrated_password_count"], 0)

    def test_existing_accounts_get_default_pool_status_active(self) -> None:
        initialize_database(self.database_path, settings=self.settings)
        with sqlite3.connect(self.database_path) as connection:
            connection.execute(
                """
                INSERT INTO accounts (
                    name, student_id, password, login_url, seat_url,
                    rebook_enabled, rebook_trigger_minutes, state_file,
                    account_status, enabled,
                    last_check_at, last_status, created_at, updated_at,
                    pool_status, pool_updated_at, pool_previous,
                    suspended_at, suspension_expires_at,
                    display_name,
                    password_cipher, password_nonce, password_tag,
                    revision, deleted_at, last_blacklist_evidence
                )
                VALUES (
                    'legacy', '20230000001', '', 'https://x', 'https://y',
                    0, 5, 'runtime/auth-x.json', 'unknown', 1,
                    NULL, '', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z',
                    '', '', '', NULL, NULL, '',
                    X'', X'', X'',
                    0, NULL, ''
                )
                """
            )
            connection.commit()

        # 二次 init 触发 backfill。
        initialize_database(self.database_path, settings=self.settings)

        with sqlite3.connect(self.database_path) as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute("SELECT * FROM accounts").fetchone()

        self.assertEqual(row["pool_status"], "active")
        self.assertNotEqual(row["pool_updated_at"], "")
        self.assertEqual(row["pool_previous"], "")
        self.assertIsNone(row["suspended_at"])
        self.assertIsNone(row["suspension_expires_at"])

    def test_capacity_exceeded_only_warns_and_continues(self) -> None:
        initialize_database(self.database_path, settings=self.settings)
        for index in range(POOL_CAPACITY + 1):
            _seed_legacy_account(
                self.database_path,
                name=f"acc-{index}",
                student_id=f"sid-{index}",
                password=f"pw-{index}",
                login_url=f"https://example.test/{index}/login",
            )

        with self.assertLogs("prevent_auto.database", level="WARNING") as captured:
            initialize_database(self.database_path, settings=self.settings)

        self.assertTrue(
            any("pool_capacity_exceeded=True" in record.message for record in captured.records),
            captured.records,
        )

        with sqlite3.connect(self.database_path) as connection:
            connection.row_factory = sqlite3.Row
            payload = json.loads(
                connection.execute(
                    "SELECT payload_json FROM pool_audit_log "
                    "WHERE audit_action='startup_migration' "
                    "ORDER BY id DESC LIMIT 1"
                ).fetchone()[0]
            )

        self.assertTrue(payload["pool_capacity_exceeded"])
        self.assertEqual(payload["capacity_total"], POOL_CAPACITY + 1)

    def test_partial_unique_index_excludes_soft_deleted(self) -> None:
        initialize_database(self.database_path, settings=self.settings)
        _seed_legacy_account(
            self.database_path,
            name="alpha",
            student_id="dup-001",
            password="x",
            login_url="https://same.test/login",
        )
        # 软删除既有行后再新增同 (student_id, login_url)，应被部分唯一索引允许。
        with sqlite3.connect(self.database_path) as connection:
            connection.execute(
                "UPDATE accounts SET deleted_at = '2026-01-02T00:00:00Z' "
                "WHERE name = 'alpha'"
            )
            connection.commit()
        _seed_legacy_account(
            self.database_path,
            name="alpha-revived",
            student_id="dup-001",
            password="y",
            login_url="https://same.test/login",
        )

        with sqlite3.connect(self.database_path) as connection:
            count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM accounts WHERE student_id='dup-001'"
                ).fetchone()[0]
            )
        self.assertEqual(count, 2)

        # 再插一条「未软删」的同键账号，应被部分唯一索引拒绝。
        with self.assertRaises(sqlite3.IntegrityError):
            _seed_legacy_account(
                self.database_path,
                name="alpha-dup",
                student_id="dup-001",
                password="z",
                login_url="https://same.test/login",
            )


class AccountPoolMigrationWithoutSettingsTestCase(unittest.TestCase):
    """单元测试沿用旧入口 ``initialize_database(path)`` 仍要能跑过。"""

    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "data" / "prevent_auto.db"

    def test_init_without_settings_skips_password_encryption(self) -> None:
        # 不传 settings 时若环境变量不全，``load_settings`` 会抛异常 → 走 None 分支。
        # 这里 patch 让 load_settings 抛异常，确保 ``_load_settings_safely`` 走兜底。
        from prevent_auto import database as database_module

        original = database_module.load_settings
        database_module.load_settings = lambda *args, **kwargs: (_ for _ in ()).throw(
            ValueError("no env")
        )
        try:
            initialize_database(self.database_path)
        finally:
            database_module.load_settings = original

        with sqlite3.connect(self.database_path) as connection:
            connection.row_factory = sqlite3.Row
            payload = json.loads(
                connection.execute(
                    "SELECT payload_json FROM pool_audit_log "
                    "WHERE audit_action='startup_migration' "
                    "ORDER BY id DESC LIMIT 1"
                ).fetchone()[0]
            )

        self.assertFalse(payload["cipher_available"])
        self.assertEqual(payload["migrated_password_count"], 0)


if __name__ == "__main__":
    logging.basicConfig(level=logging.WARNING)
    unittest.main()
