from __future__ import annotations

import json
import tempfile
import unittest
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

from prevent_auto.account_pool.models import (
    ClientKind,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import connect_database
from prevent_auto.logging import SCRUBBED_PLACEHOLDER
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogEntry,
    PoolAuditLogQuery,
    PoolAuditLogRepository,
)


# task 1.2 在并行实现 pool_audit_log 表迁移；为了让本仓库的单元测试不依赖那个 task，
# 直接在测试 setUp 阶段按 design「Data Models · pool_audit_log 表」的 DDL 建表。
POOL_AUDIT_LOG_DDL = """
CREATE TABLE pool_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    audit_action TEXT NOT NULL,
    account_id INTEGER,
    task_id INTEGER,
    from_pool TEXT,
    to_pool TEXT,
    trigger_source TEXT NOT NULL,
    operator TEXT NOT NULL,
    client_kind TEXT,
    success INTEGER NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    payload_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL
);
CREATE INDEX idx_pool_audit_log_account_created
    ON pool_audit_log(account_id, created_at DESC);
CREATE INDEX idx_pool_audit_log_action_created
    ON pool_audit_log(audit_action, created_at DESC);
CREATE INDEX idx_pool_audit_log_created_at
    ON pool_audit_log(created_at);
"""


class PoolAuditLogRepositoryTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        with connect_database(self.database_path) as connection:
            connection.executescript(POOL_AUDIT_LOG_DDL)
        self.repo = PoolAuditLogRepository(self.database_path)

    # ------------------------------ append ------------------------------

    def test_append_persists_row_and_scrubs_payload(self) -> None:
        entry = PoolAuditLogEntry(
            audit_action=PoolAuditAction.MIGRATE,
            trigger_source=PoolMigrationTrigger.MANUAL,
            operator="admin",
            success=True,
            account_id=17,
            from_pool=PoolStatus.ACTIVE,
            to_pool=PoolStatus.SUSPENDED,
            client_kind=ClientKind.WEB,
            reason="manual move",
            payload={
                "password": "Pa$$w0rd",
                "Authorization": "Bearer abc.def",
                "evidence": "人机验证失败",
                "nested": {"token": "secret"},
            },
        )

        saved = self.repo.append(entry)

        self.assertIsNotNone(saved.id)
        self.assertIsNotNone(saved.created_at)
        # 返回值与库中的 payload 都是脱敏后的版本
        self.assertEqual(saved.payload["password"], SCRUBBED_PLACEHOLDER)
        self.assertEqual(saved.payload["Authorization"], SCRUBBED_PLACEHOLDER)
        self.assertEqual(saved.payload["nested"]["token"], SCRUBBED_PLACEHOLDER)
        self.assertEqual(saved.payload["evidence"], "人机验证失败")

        with connect_database(self.database_path) as connection:
            row = connection.execute(
                "SELECT * FROM pool_audit_log WHERE id = ?", (saved.id,)
            ).fetchone()
        # 数据库中的 payload_json 不再包含原文密码 / token
        self.assertNotIn("Pa$$w0rd", row["payload_json"])
        self.assertNotIn("Bearer abc.def", row["payload_json"])
        self.assertNotIn("secret", row["payload_json"])
        self.assertEqual(row["audit_action"], "migrate")
        self.assertEqual(row["from_pool"], "active")
        self.assertEqual(row["to_pool"], "suspended")
        self.assertEqual(row["client_kind"], "web")
        self.assertEqual(int(row["success"]), 1)
        self.assertTrue(row["created_at"].endswith("Z"))

    def test_append_defaults_created_at_to_now_utc(self) -> None:
        before = datetime.now(UTC)
        saved = self.repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.REAPER_TICK,
                trigger_source=PoolMigrationTrigger.SYSTEM,
                operator="system",
                success=True,
            )
        )
        after = datetime.now(UTC)
        self.assertIsNotNone(saved.created_at)
        self.assertGreaterEqual(saved.created_at, before)
        self.assertLessEqual(saved.created_at, after)
        self.assertEqual(saved.created_at.tzinfo, UTC)

    def test_append_rejects_naive_datetime(self) -> None:
        entry = PoolAuditLogEntry(
            audit_action=PoolAuditAction.REAPER_TICK,
            trigger_source=PoolMigrationTrigger.SYSTEM,
            operator="system",
            success=True,
            created_at=datetime(2026, 4, 26, 8, 30, 0),
        )
        with self.assertRaises(ValueError):
            self.repo.append(entry)

    def test_append_normalizes_non_utc_datetime_to_utc(self) -> None:
        shanghai = timezone(timedelta(hours=8))
        local_dt = datetime(2026, 4, 26, 16, 30, 0, tzinfo=shanghai)
        saved = self.repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.REAPER_TICK,
                trigger_source=PoolMigrationTrigger.SYSTEM,
                operator="system",
                success=True,
                created_at=local_dt,
            )
        )
        # 落库时被转换成 UTC 文本
        self.assertEqual(
            saved.created_at, datetime(2026, 4, 26, 8, 30, 0, tzinfo=UTC)
        )
        with connect_database(self.database_path) as connection:
            row = connection.execute(
                "SELECT created_at FROM pool_audit_log WHERE id = ?",
                (saved.id,),
            ).fetchone()
        self.assertEqual(str(row["created_at"]), "2026-04-26T08:30:00Z")

    def test_append_payload_json_is_valid_json(self) -> None:
        saved = self.repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.BULK_IMPORT,
                trigger_source=PoolMigrationTrigger.IMPORT,
                operator="admin",
                success=True,
                payload={"success_count": 27, "failure_count": 3},
            )
        )
        with connect_database(self.database_path) as connection:
            row = connection.execute(
                "SELECT payload_json FROM pool_audit_log WHERE id = ?",
                (saved.id,),
            ).fetchone()
        decoded = json.loads(row["payload_json"])
        self.assertEqual(decoded, {"failure_count": 3, "success_count": 27})

    # ------------------------------ query ------------------------------

    def _seed(self) -> list[PoolAuditLogEntry]:
        base = datetime(2026, 4, 26, 8, 0, 0, tzinfo=UTC)
        seeds = [
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.MIGRATE,
                trigger_source=PoolMigrationTrigger.MANUAL,
                operator="admin",
                success=True,
                account_id=10,
                from_pool=PoolStatus.IDLE,
                to_pool=PoolStatus.ACTIVE,
                created_at=base,
            ),
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.MIGRATE,
                trigger_source=PoolMigrationTrigger.EXPIRE,
                operator="system",
                success=True,
                account_id=10,
                from_pool=PoolStatus.SUSPENDED,
                to_pool=PoolStatus.IDLE,
                created_at=base + timedelta(minutes=5),
            ),
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.RANDOM_PICK,
                trigger_source=PoolMigrationTrigger.RANDOM_PICK,
                operator="admin",
                success=True,
                account_id=20,
                created_at=base + timedelta(minutes=10),
            ),
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.REAPER_TICK,
                trigger_source=PoolMigrationTrigger.SYSTEM,
                operator="system",
                success=True,
                created_at=base + timedelta(minutes=15),
            ),
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.TASK_UPLOAD,
                trigger_source=PoolMigrationTrigger.MANUAL,
                operator="token-android-1",
                success=True,
                account_id=10,
                task_id=901,
                client_kind=ClientKind.ANDROID,
                created_at=base + timedelta(minutes=20),
            ),
        ]
        return [self.repo.append(seed) for seed in seeds]

    def test_query_without_filters_orders_by_created_at_desc(self) -> None:
        saved = self._seed()
        rows = self.repo.query()
        self.assertEqual(len(rows), len(saved))
        # 最新的 task_upload 排在第一行
        self.assertEqual(rows[0].audit_action, PoolAuditAction.TASK_UPLOAD)
        # 整体严格按 created_at DESC 排序
        timestamps = [row.created_at for row in rows]
        self.assertEqual(timestamps, sorted(timestamps, reverse=True))

    def test_query_filters_by_account_id(self) -> None:
        self._seed()
        rows = self.repo.query(PoolAuditLogQuery(account_id=10))
        self.assertEqual(len(rows), 3)
        for row in rows:
            self.assertEqual(row.account_id, 10)

    def test_query_filters_by_single_audit_action(self) -> None:
        self._seed()
        rows = self.repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.MIGRATE)
        )
        self.assertEqual(len(rows), 2)
        for row in rows:
            self.assertEqual(row.audit_action, PoolAuditAction.MIGRATE)

    def test_query_filters_by_multiple_audit_actions(self) -> None:
        self._seed()
        rows = self.repo.query(
            PoolAuditLogQuery(
                audit_action=(
                    PoolAuditAction.RANDOM_PICK,
                    PoolAuditAction.TASK_UPLOAD,
                )
            )
        )
        self.assertEqual(len(rows), 2)
        actions = {row.audit_action for row in rows}
        self.assertEqual(
            actions,
            {PoolAuditAction.RANDOM_PICK, PoolAuditAction.TASK_UPLOAD},
        )

    def test_query_returns_empty_for_empty_audit_action_sequence(self) -> None:
        self._seed()
        rows = self.repo.query(PoolAuditLogQuery(audit_action=()))
        self.assertEqual(rows, [])

    def test_query_filters_by_time_range(self) -> None:
        self._seed()
        base = datetime(2026, 4, 26, 8, 0, 0, tzinfo=UTC)
        rows = self.repo.query(
            PoolAuditLogQuery(
                created_after=base + timedelta(minutes=5),
                created_before=base + timedelta(minutes=15),
            )
        )
        # 命中 5 / 10 / 15 三个分钟点
        self.assertEqual(len(rows), 3)
        for row in rows:
            self.assertGreaterEqual(row.created_at, base + timedelta(minutes=5))
            self.assertLessEqual(row.created_at, base + timedelta(minutes=15))

    def test_query_combines_filters(self) -> None:
        self._seed()
        base = datetime(2026, 4, 26, 8, 0, 0, tzinfo=UTC)
        rows = self.repo.query(
            PoolAuditLogQuery(
                account_id=10,
                audit_action=PoolAuditAction.MIGRATE,
                created_after=base + timedelta(minutes=1),
            )
        )
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].trigger_source, PoolMigrationTrigger.EXPIRE)

    def test_query_respects_limit(self) -> None:
        self._seed()
        rows = self.repo.query(PoolAuditLogQuery(limit=2))
        self.assertEqual(len(rows), 2)
        # 仍是按 DESC 排序后取前两行
        self.assertEqual(rows[0].audit_action, PoolAuditAction.TASK_UPLOAD)
        self.assertEqual(rows[1].audit_action, PoolAuditAction.REAPER_TICK)

    def test_query_round_trips_optional_fields(self) -> None:
        saved = self.repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.MIGRATE,
                trigger_source=PoolMigrationTrigger.BLACKLIST,
                operator="token-window-1",
                success=False,
                account_id=42,
                task_id=None,
                from_pool=PoolStatus.ACTIVE,
                to_pool=PoolStatus.SUSPENDED,
                client_kind=ClientKind.WINDOW,
                reason="blacklist",
                payload={"evidence": "captcha failed"},
            )
        )
        rows = self.repo.query(PoolAuditLogQuery(account_id=42))
        self.assertEqual(len(rows), 1)
        loaded = rows[0]
        self.assertEqual(loaded.id, saved.id)
        self.assertEqual(loaded.audit_action, PoolAuditAction.MIGRATE)
        self.assertEqual(loaded.trigger_source, PoolMigrationTrigger.BLACKLIST)
        self.assertEqual(loaded.from_pool, PoolStatus.ACTIVE)
        self.assertEqual(loaded.to_pool, PoolStatus.SUSPENDED)
        self.assertEqual(loaded.client_kind, ClientKind.WINDOW)
        self.assertFalse(loaded.success)
        self.assertEqual(loaded.reason, "blacklist")
        self.assertEqual(loaded.payload, {"evidence": "captcha failed"})
        self.assertEqual(loaded.created_at.tzinfo, UTC)

    def test_query_rejects_negative_limit(self) -> None:
        with self.assertRaises(ValueError):
            self.repo.query(PoolAuditLogQuery(limit=-1))

    # -------------------------- 共享事务注入 --------------------------

    def test_append_with_shared_connection_supports_rollback(self) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute("BEGIN")
            self.repo.append(
                PoolAuditLogEntry(
                    audit_action=PoolAuditAction.TASK_UPLOAD,
                    trigger_source=PoolMigrationTrigger.MANUAL,
                    operator="token-android-1",
                    success=True,
                    account_id=99,
                    task_id=1,
                    client_kind=ClientKind.ANDROID,
                ),
                connection=connection,
            )
            connection.rollback()
        rows = self.repo.query(PoolAuditLogQuery(account_id=99))
        self.assertEqual(rows, [])


if __name__ == "__main__":
    unittest.main()
