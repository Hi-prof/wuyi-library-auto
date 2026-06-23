"""``services/account_pool_service.py`` 单元测试。

覆盖 spec ``account-pool-tri-sync`` task 4.1 的核心契约：

* ``migrate`` 合法 / 非法路径 + 副作用契约（Login_Status_Cache、暂停时段）。
* ``bulk_import_to_idle`` 条目级校验 + 容量、唯一键拒绝；审计 ``payload_json`` 不含明文。
* ``random_pick_from_idle`` 命中 / 空池行为。
* ``mark_blacklisted_by_client`` 仅对 Active_Pool 生效，非活跃一律 404 语义异常。
* ``list_active_for_sync`` 仅暴露非敏感字段；``get_active_detail`` 软删 / 非活跃统一抛错。
"""

from __future__ import annotations

import json
import sqlite3
import tempfile
import unittest
from datetime import UTC, datetime, time as dtime, timedelta
from pathlib import Path

from prevent_auto.account_pool.constants import POOL_CAPACITY
from prevent_auto.account_pool.models import (
    AccountNotInActivePool,
    BulkImportItemStatus,
    BulkImportRejectReason,
    BulkImportRow,
    ClientKind,
    IdleEmpty,
    IllegalPoolTransition,
    MissingLoginCredentials,
    PoolCapacityExceeded,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import connect_database, initialize_database
from prevent_auto.services.account_password_cipher import (
    AccountPasswordCipher,
    generate_secret_key,
)
from prevent_auto.services.account_pool_service import (
    AccountPoolService,
    InMemoryLoginStatusCache,
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


class _FakeClock:
    """一个可推进的 UTC 时钟，便于断言 ``pool_updated_at`` 严格单调。"""

    def __init__(self, start: datetime) -> None:
        if start.tzinfo is None:
            raise ValueError("start 必须带时区")
        self._current = start.astimezone(UTC)

    def __call__(self) -> datetime:
        return self._current

    def advance(self, delta: timedelta) -> None:
        self._current = self._current + delta


class AccountPoolServiceTestBase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "data" / "prevent_auto.db"
        self.secret_key = generate_secret_key()
        self.settings = _build_settings(self.temp_dir, self.secret_key)
        initialize_database(self.database_path, settings=self.settings)
        self.cipher = AccountPasswordCipher(self.secret_key)
        self.cache = InMemoryLoginStatusCache()
        self.clock = _FakeClock(datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC))
        self.service = AccountPoolService(
            self.database_path,
            cipher=self.cipher,
            login_status_cache=self.cache,
            clock=self.clock,
        )

    # ------------------ 共用 seed 工具 ------------------

    def _seed_idle(self, *, student_id: str, login_url: str = "https://x.test/login") -> int:
        """通过批量导入把一条账号写到 Idle_Pool，返回新 ``account_id``。"""

        result = self.service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id=student_id,
                    password="pw-" + student_id,
                    login_url=login_url,
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        item = result.items[0]
        assert item.status is BulkImportItemStatus.OK
        assert item.account_id is not None
        return item.account_id

    def _seed_active(self, *, student_id: str) -> int:
        """seed 一个已迁入 Active_Pool 的账号。"""

        account_id = self._seed_idle(student_id=student_id)
        self.clock.advance(timedelta(seconds=1))
        self.service.migrate(
            account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return account_id

    # ------------------ 通用断言 ------------------

    def _audit_rows(self) -> list[sqlite3.Row]:
        with connect_database(self.database_path) as conn:
            return list(
                conn.execute(
                    "SELECT * FROM pool_audit_log ORDER BY id ASC"
                ).fetchall()
            )


class MigrateTestCase(AccountPoolServiceTestBase):
    def test_idle_to_active_marks_login_cache_and_writes_audit(self) -> None:
        account_id = self._seed_idle(student_id="20231121130")

        self.clock.advance(timedelta(seconds=10))
        before_audit_count = len(self._audit_rows())
        entry = self.service.migrate(
            account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )

        self.assertEqual(entry.pool_status, PoolStatus.ACTIVE)
        self.assertEqual(entry.pool_previous, PoolStatus.IDLE.value)
        self.assertIsNone(entry.suspended_at)
        self.assertIsNone(entry.suspension_expires_at)
        self.assertTrue(self.cache.contains(account_id))

        after = self._audit_rows()
        self.assertEqual(len(after) - before_audit_count, 1)
        latest = after[-1]
        self.assertEqual(latest["audit_action"], "migrate")
        self.assertEqual(latest["from_pool"], "idle")
        self.assertEqual(latest["to_pool"], "active")
        self.assertEqual(latest["trigger_source"], "manual")
        self.assertEqual(latest["operator"], "admin")
        self.assertEqual(int(latest["success"]), 1)

    def test_active_to_suspended_sets_suspension_window(self) -> None:
        account_id = self._seed_active(student_id="20231121131")
        self.cache.mark_active(account_id)  # 之前 migrate 已经 mark
        self.clock.advance(timedelta(minutes=1))

        entry = self.service.migrate(
            account_id,
            PoolStatus.SUSPENDED,
            operator="admin",
            trigger_source=PoolMigrationTrigger.BLACKLIST,
        )

        self.assertEqual(entry.pool_status, PoolStatus.SUSPENDED)
        assert entry.suspended_at is not None
        assert entry.suspension_expires_at is not None
        self.assertEqual(
            entry.suspension_expires_at - entry.suspended_at,
            timedelta(hours=168),
        )
        self.assertFalse(self.cache.contains(account_id))

    def test_idle_to_suspended_is_illegal_and_writes_failure_audit(self) -> None:
        account_id = self._seed_idle(student_id="20231121132")
        before = len(self._audit_rows())

        with self.assertRaises(IllegalPoolTransition):
            self.service.migrate(
                account_id,
                PoolStatus.SUSPENDED,
                operator="admin",
                trigger_source=PoolMigrationTrigger.MANUAL,
            )

        rows = self._audit_rows()
        self.assertEqual(len(rows) - before, 1)
        last = rows[-1]
        self.assertEqual(last["audit_action"], "migrate")
        self.assertEqual(int(last["success"]), 0)
        self.assertEqual(last["reason"], "illegal_transition")

    def test_active_to_active_is_illegal_same_pool(self) -> None:
        account_id = self._seed_active(student_id="20231121133")
        with self.assertRaises(IllegalPoolTransition):
            self.service.migrate(
                account_id,
                PoolStatus.ACTIVE,
                operator="admin",
                trigger_source=PoolMigrationTrigger.MANUAL,
            )

    def test_unknown_account_raises_account_not_in_active(self) -> None:
        before = len(self._audit_rows())
        with self.assertRaises(AccountNotInActivePool):
            self.service.migrate(
                999,
                PoolStatus.ACTIVE,
                operator="admin",
                trigger_source=PoolMigrationTrigger.MANUAL,
            )
        # 未知账号路径不写审计，避免 404 探测拉爆审计
        self.assertEqual(len(self._audit_rows()), before)

    def test_missing_login_credentials_blocks_migration(self) -> None:
        # 直接 INSERT 一条缺 login_url 的 idle 账号
        with connect_database(self.database_path) as conn:
            cursor = conn.execute(
                """
                INSERT INTO accounts (
                    name, student_id, password, login_url, seat_url,
                    rebook_enabled, rebook_trigger_minutes, state_file,
                    account_status, enabled, last_check_at, last_status,
                    created_at, updated_at,
                    pool_status, pool_updated_at, pool_previous,
                    suspended_at, suspension_expires_at, display_name,
                    password_cipher, password_nonce, password_tag,
                    revision, deleted_at, last_blacklist_evidence
                )
                VALUES (
                    'no-login', 'sid-no-login', '', '', '',
                    0, 5, 'runtime/auth-no.json', 'unknown', 1,
                    NULL, '', '2026-04-26T00:00:00Z', '2026-04-26T00:00:00Z',
                    'idle', '2026-04-26T00:00:00Z', '',
                    NULL, NULL, '',
                    X'', X'', X'',
                    0, NULL, ''
                )
                """
            )
            account_id = int(cursor.lastrowid)
            conn.commit()

        with self.assertRaises(MissingLoginCredentials):
            self.service.migrate(
                account_id,
                PoolStatus.ACTIVE,
                operator="admin",
                trigger_source=PoolMigrationTrigger.MANUAL,
            )

        last = self._audit_rows()[-1]
        self.assertEqual(int(last["success"]), 0)
        self.assertEqual(last["reason"], "missing_login_credentials")

    def test_active_pool_capacity_can_block_migration(self) -> None:
        capped_service = AccountPoolService(
            self.database_path,
            cipher=self.cipher,
            login_status_cache=self.cache,
            clock=self.clock,
            active_pool_capacity=1,
        )
        first = self._seed_active(student_id="20231121140")
        second = self._seed_idle(student_id="20231121141")
        with self.assertRaises(PoolCapacityExceeded):
            capped_service.migrate(
                second,
                PoolStatus.ACTIVE,
                operator="admin",
                trigger_source=PoolMigrationTrigger.MANUAL,
            )
        # 第一个还在 Active
        with connect_database(self.database_path) as conn:
            row = conn.execute(
                "SELECT pool_status FROM accounts WHERE id = ?", (first,)
            ).fetchone()
        self.assertEqual(row["pool_status"], "active")


class BulkImportTestCase(AccountPoolServiceTestBase):
    def test_minimal_required_fields_succeed_and_audit_excludes_password(
        self,
    ) -> None:
        rows = [
            BulkImportRow(
                student_id="20231121200",
                password="secret-中文-1",
                source_row=1,
            ),
            BulkImportRow(
                student_id="20231121201",
                password="secret-中文-2",
                login_url="https://other.test/login",
                source_row=2,
            ),
        ]
        result = self.service.bulk_import_to_idle(rows, operator="admin")
        self.assertEqual(result.total, 2)
        self.assertEqual(result.success_count, 2)
        self.assertEqual(result.failure_count, 0)
        for item in result.items:
            self.assertEqual(item.status, BulkImportItemStatus.OK)
            self.assertIsNotNone(item.account_id)

        with connect_database(self.database_path) as conn:
            payload = json.loads(
                conn.execute(
                    "SELECT payload_json FROM pool_audit_log "
                    "WHERE audit_action='bulk_import' "
                    "ORDER BY id DESC LIMIT 1"
                ).fetchone()[0]
            )
        # 不含密码原文
        text = json.dumps(payload, ensure_ascii=False)
        self.assertNotIn("secret-中文-1", text)
        self.assertNotIn("secret-中文-2", text)
        self.assertEqual(payload["success_count"], 2)

    def test_validation_error_for_missing_required(self) -> None:
        result = self.service.bulk_import_to_idle(
            [
                BulkImportRow(student_id="", password="pw", source_row=1),
                BulkImportRow(student_id="abc", password="", source_row=2),
            ],
            operator="admin",
        )
        self.assertEqual(result.success_count, 0)
        self.assertEqual(result.failure_count, 2)
        for item in result.items:
            self.assertEqual(item.status, BulkImportItemStatus.REJECTED)
            self.assertEqual(item.reason, BulkImportRejectReason.VALIDATION_ERROR)

    def test_duplicate_student_id_in_batch_and_in_db(self) -> None:
        # 先成功导入一条
        first = self.service.bulk_import_to_idle(
            [BulkImportRow(student_id="20231121300", password="x", source_row=1)],
            operator="admin",
        )
        self.assertEqual(first.success_count, 1)

        # 再次导入同 (student_id, login_url=空) 与同批内重复
        result = self.service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id="20231121300",
                    password="y",
                    source_row=1,
                ),
                BulkImportRow(
                    student_id="20231121301",
                    password="z1",
                    source_row=2,
                ),
                BulkImportRow(
                    student_id="20231121301",
                    password="z2",
                    source_row=3,
                ),
            ],
            operator="admin",
        )
        self.assertEqual(result.success_count, 1)
        self.assertEqual(result.failure_count, 2)
        reasons = [item.reason for item in result.items]
        self.assertEqual(
            reasons,
            [
                BulkImportRejectReason.DUPLICATE_STUDENT_ID,
                None,
                BulkImportRejectReason.DUPLICATE_STUDENT_ID,
            ],
        )

    def test_pool_capacity_blocks_remaining_rows(self) -> None:
        capped_service = AccountPoolService(
            self.database_path,
            cipher=self.cipher,
            login_status_cache=self.cache,
            clock=self.clock,
            pool_capacity=2,
        )
        rows = [
            BulkImportRow(
                student_id=f"20231121{idx:03d}",
                password="pw",
                login_url=f"https://x.test/{idx}/login",
                source_row=idx + 1,
            )
            for idx in range(5)
        ]
        result = capped_service.bulk_import_to_idle(rows, operator="admin")
        self.assertEqual(result.success_count, 2)
        self.assertEqual(result.failure_count, 3)
        for item in result.items[2:]:
            self.assertEqual(item.reason, BulkImportRejectReason.POOL_FULL)

    def test_global_pool_capacity_default_is_100(self) -> None:
        # 仅做存在性断言，避免在 CI 环境真的导入 100 条
        self.assertEqual(self.service.pool_capacity, POOL_CAPACITY)


class RandomPickTestCase(AccountPoolServiceTestBase):
    def test_idle_empty_raises(self) -> None:
        with self.assertRaises(IdleEmpty):
            self.service.random_pick_from_idle(operator="admin")

    def test_pick_returns_idle_entry_and_does_not_change_pool(self) -> None:
        account_id = self._seed_idle(student_id="20231121400")
        entry = self.service.random_pick_from_idle(operator="admin")
        self.assertEqual(entry.account_id, account_id)
        self.assertEqual(entry.pool_status, PoolStatus.IDLE)

        with connect_database(self.database_path) as conn:
            row = conn.execute(
                "SELECT pool_status FROM accounts WHERE id = ?",
                (account_id,),
            ).fetchone()
        self.assertEqual(row["pool_status"], "idle")

        last = self._audit_rows()[-1]
        self.assertEqual(last["audit_action"], "random_pick")
        self.assertEqual(int(last["account_id"]), account_id)


class BlacklistEventTestCase(AccountPoolServiceTestBase):
    def test_active_account_is_moved_to_suspended_with_evidence(self) -> None:
        account_id = self._seed_active(student_id="20231121500")
        entry = self.service.mark_blacklisted_by_client(
            account_id,
            client_kind=ClientKind.WINDOW,
            evidence="人机验证失败 5 次",
        )
        self.assertEqual(entry.pool_status, PoolStatus.SUSPENDED)
        assert entry.suspended_at is not None
        assert entry.suspension_expires_at is not None
        self.assertEqual(
            entry.suspension_expires_at - entry.suspended_at,
            timedelta(hours=168),
        )
        self.assertFalse(self.cache.contains(account_id))

        last = self._audit_rows()[-1]
        self.assertEqual(last["trigger_source"], "blacklist")
        self.assertEqual(last["client_kind"], "window")

        with connect_database(self.database_path) as conn:
            row = conn.execute(
                "SELECT last_blacklist_evidence FROM accounts WHERE id = ?",
                (account_id,),
            ).fetchone()
        self.assertEqual(row["last_blacklist_evidence"], "人机验证失败 5 次")

    def test_non_active_account_raises_404_semantic_error(self) -> None:
        suspended_id = self._seed_active(student_id="20231121501")
        self.service.migrate(
            suspended_id,
            PoolStatus.SUSPENDED,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        with self.assertRaises(AccountNotInActivePool):
            self.service.mark_blacklisted_by_client(
                suspended_id,
                client_kind=ClientKind.ANDROID,
                evidence="x",
            )

        with self.assertRaises(AccountNotInActivePool):
            self.service.mark_blacklisted_by_client(
                999,
                client_kind=ClientKind.ANDROID,
                evidence="x",
            )


class SyncEndpointsTestCase(AccountPoolServiceTestBase):
    def test_list_active_for_sync_only_exposes_non_sensitive_fields(self) -> None:
        account_id = self._seed_active(student_id="20231121600")
        items = self.service.list_active_for_sync()
        self.assertEqual(len(items), 1)
        item = items[0]
        self.assertEqual(item.account_id, account_id)
        self.assertEqual(item.student_id, "20231121600")
        self.assertEqual(item.pool_status, PoolStatus.ACTIVE)
        # 字段集只有 5 个；用 dataclass 的 ``__dataclass_fields__`` 反查
        from dataclasses import fields

        names = {f.name for f in fields(item)}
        self.assertEqual(
            names,
            {"account_id", "student_id", "display_name", "pool_status", "updated_at"},
        )

    def test_get_active_detail_decrypts_password(self) -> None:
        account_id = self._seed_active(student_id="20231121601")
        # _seed_active 内部 password 是 "pw-20231121601"
        detail = self.service.get_active_detail(account_id)
        self.assertEqual(detail.account_id, account_id)
        self.assertEqual(detail.password, "pw-20231121601")
        self.assertEqual(detail.automation_tasks, ())

    def test_get_login_password_decrypts_idle_account_password(self) -> None:
        account_id = self._seed_idle(student_id="20231121602")

        password = self.service.get_login_password(account_id)

        self.assertEqual(password, "pw-20231121602")

    def test_get_active_detail_404_for_non_active(self) -> None:
        suspended_id = self._seed_active(student_id="20231121602")
        self.service.migrate(
            suspended_id,
            PoolStatus.SUSPENDED,
            operator="admin",
            trigger_source=PoolMigrationTrigger.BLACKLIST,
        )
        with self.assertRaises(AccountNotInActivePool):
            self.service.get_active_detail(suspended_id)

        with self.assertRaises(AccountNotInActivePool):
            self.service.get_active_detail(999)

    def test_get_active_detail_404_for_soft_deleted(self) -> None:
        account_id = self._seed_active(student_id="20231121603")
        with connect_database(self.database_path) as conn:
            conn.execute(
                "UPDATE accounts SET deleted_at = ? WHERE id = ?",
                ("2026-04-26T00:00:00Z", account_id),
            )
            conn.commit()
        with self.assertRaises(AccountNotInActivePool):
            self.service.get_active_detail(account_id)


if __name__ == "__main__":
    unittest.main()
