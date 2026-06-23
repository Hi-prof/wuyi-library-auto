"""``scheduler/pool_reaper_job.py`` 单元测试。

覆盖 spec ``account-pool-tri-sync`` task 6.1 的核心契约：

* ``_tick_sync`` 正确把已到期 suspended 账号迁移到 idle，每次 tick 至少写一条
  ``reaper_tick`` 审计行（无论是否回收账号）。
* 未到期的 suspended 账号保持不变。
* 服务停机期间累积的到期项可在恢复后的首次 tick 中一次性处理（Requirement 3.5）。
* 数据库扫描异常时写一条 ``success=0`` 的 ``reaper_tick`` 审计行，并把 tick 结果
  标记为 ``error``。
* 单条迁移失败不打断剩余账号的处理，并把 tick 结果标记为 ``partial_failure``。
* ``run_forever`` 在 ``stop_event`` 设置后及时退出，且只触发一次首轮 tick。
"""

from __future__ import annotations

import asyncio
import sqlite3
import tempfile
import unittest
from datetime import UTC, datetime, time as dtime, timedelta
from pathlib import Path

from prevent_auto.account_pool.models import (
    BulkImportItemStatus,
    BulkImportRow,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import connect_database, initialize_database
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogQuery,
    PoolAuditLogRepository,
)
from prevent_auto.scheduler.pool_reaper_job import (
    INTERVAL_SECONDS,
    MAX_DRIFT_SECONDS,
    PoolReaperJob,
    ReaperTickResult,
)
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
    """可推进的 UTC 时钟，与 test_account_pool_service 风格保持一致。"""

    def __init__(self, start: datetime) -> None:
        if start.tzinfo is None:
            raise ValueError("start 必须带时区")
        self._current = start.astimezone(UTC)

    def __call__(self) -> datetime:
        return self._current

    def advance(self, delta: timedelta) -> None:
        self._current = self._current + delta

    def set(self, value: datetime) -> None:
        if value.tzinfo is None:
            raise ValueError("value 必须带时区")
        self._current = value.astimezone(UTC)


class PoolReaperJobTestBase(unittest.TestCase):
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
        self.account_pool_repo = AccountPoolRepository(self.database_path)
        self.audit_repo = PoolAuditLogRepository(self.database_path)
        self.job = PoolReaperJob(
            account_pool_repo=self.account_pool_repo,
            audit_repo=self.audit_repo,
            account_pool_service=self.service,
            clock=self.clock,
        )

    # ------------- seed 工具 -------------

    def _seed_active(self, *, student_id: str) -> int:
        result = self.service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id=student_id,
                    password="pw-" + student_id,
                    login_url="https://x.test/login",
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        item = result.items[0]
        assert item.status is BulkImportItemStatus.OK
        assert item.account_id is not None
        self.clock.advance(timedelta(seconds=1))
        self.service.migrate(
            item.account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return item.account_id

    def _seed_suspended(self, *, student_id: str) -> int:
        account_id = self._seed_active(student_id=student_id)
        self.clock.advance(timedelta(seconds=1))
        self.service.migrate(
            account_id,
            PoolStatus.SUSPENDED,
            operator="admin",
            trigger_source=PoolMigrationTrigger.BLACKLIST,
        )
        return account_id

    def _pool_status(self, account_id: int) -> str:
        with connect_database(self.database_path) as conn:
            row = conn.execute(
                "SELECT pool_status FROM accounts WHERE id = ?",
                (account_id,),
            ).fetchone()
        assert row is not None
        return str(row["pool_status"])


class ConstantsTestCase(unittest.TestCase):
    def test_interval_and_drift_default_to_300_seconds(self) -> None:
        self.assertEqual(INTERVAL_SECONDS, 300)
        self.assertEqual(MAX_DRIFT_SECONDS, 300)
        self.assertEqual(PoolReaperJob.INTERVAL_SECONDS, 300)
        self.assertEqual(PoolReaperJob.MAX_DRIFT_SECONDS, 300)


class TickSyncTestCase(PoolReaperJobTestBase):
    def test_empty_database_writes_one_reaper_tick_audit(self) -> None:
        result = self.job._tick_sync()
        self.assertIsInstance(result, ReaperTickResult)
        self.assertEqual(result.processed_count, 0)
        self.assertEqual(result.failed_account_ids, ())
        self.assertTrue(result.success)
        self.assertEqual(result.error, "")

        rows = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.REAPER_TICK)
        )
        self.assertEqual(len(rows), 1)
        latest = rows[0]
        self.assertEqual(latest.audit_action, PoolAuditAction.REAPER_TICK)
        self.assertEqual(latest.trigger_source, PoolMigrationTrigger.SYSTEM)
        self.assertEqual(latest.operator, "system")
        self.assertTrue(latest.success)
        self.assertEqual(latest.payload["processed_count"], 0)
        self.assertEqual(latest.payload["failed_account_ids"], [])
        self.assertIn("now_utc", latest.payload)

    def test_expired_suspended_accounts_are_migrated_to_idle(self) -> None:
        first = self._seed_suspended(student_id="20231121700")
        second = self._seed_suspended(student_id="20231121701")
        self.assertEqual(self._pool_status(first), "suspended")
        self.assertEqual(self._pool_status(second), "suspended")

        # 推进时钟到 168 小时 + 1 秒，让两条 suspended 都到期。
        self.clock.advance(timedelta(hours=168, seconds=1))

        result = self.job._tick_sync()
        self.assertEqual(result.processed_count, 2)
        self.assertEqual(result.failed_account_ids, ())
        self.assertTrue(result.success)

        self.assertEqual(self._pool_status(first), "idle")
        self.assertEqual(self._pool_status(second), "idle")
        self.assertFalse(self.cache.contains(first))
        self.assertFalse(self.cache.contains(second))

        # suspension 字段被清空
        with connect_database(self.database_path) as conn:
            rows = conn.execute(
                "SELECT id, suspended_at, suspension_expires_at FROM accounts "
                "WHERE id IN (?, ?)",
                (first, second),
            ).fetchall()
        for row in rows:
            self.assertIsNone(row["suspended_at"])
            self.assertIsNone(row["suspension_expires_at"])

        # 一条 reaper_tick + 两条 migrate(trigger=expire)
        tick_rows = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.REAPER_TICK)
        )
        self.assertEqual(len(tick_rows), 1)
        self.assertEqual(tick_rows[0].payload["processed_count"], 2)
        self.assertEqual(
            sorted(tick_rows[0].payload["failed_account_ids"]), []
        )

        migrate_rows = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.MIGRATE)
        )
        expire_rows = [
            row
            for row in migrate_rows
            if row.trigger_source is PoolMigrationTrigger.EXPIRE
        ]
        self.assertEqual(len(expire_rows), 2)
        self.assertEqual(
            {row.account_id for row in expire_rows}, {first, second}
        )
        for row in expire_rows:
            self.assertEqual(row.from_pool, PoolStatus.SUSPENDED)
            self.assertEqual(row.to_pool, PoolStatus.IDLE)
            self.assertEqual(row.operator, "system")

    def test_not_yet_expired_account_is_kept(self) -> None:
        account_id = self._seed_suspended(student_id="20231121702")
        # 推进 1 小时（远未到 168h），不应被回收
        self.clock.advance(timedelta(hours=1))
        result = self.job._tick_sync()
        self.assertEqual(result.processed_count, 0)
        self.assertEqual(result.failed_account_ids, ())
        self.assertEqual(self._pool_status(account_id), "suspended")

        tick_rows = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.REAPER_TICK)
        )
        self.assertEqual(len(tick_rows), 1)
        self.assertTrue(tick_rows[0].success)

    def test_recovery_tick_processes_accumulated_expirations(self) -> None:
        # 模拟服务停机期间累积的多次到期：先 seed 两条 suspended，再把时钟一次性
        # 推进到「应有多次 tick 漏跑后」的状态，断言首次 tick 一次性回收完毕。
        first = self._seed_suspended(student_id="20231121703")
        second = self._seed_suspended(student_id="20231121704")
        # 假设漏跑了 12 个 tick（12 * 5min = 1h），且早已超过 168h
        self.clock.advance(timedelta(hours=170))
        result = self.job._tick_sync()
        self.assertEqual(result.processed_count, 2)
        self.assertTrue(result.success)
        self.assertEqual(self._pool_status(first), "idle")
        self.assertEqual(self._pool_status(second), "idle")

    def test_soft_deleted_suspended_account_is_skipped(self) -> None:
        account_id = self._seed_suspended(student_id="20231121705")
        # 软删除该账号
        with connect_database(self.database_path) as conn:
            conn.execute(
                "UPDATE accounts SET deleted_at = ? WHERE id = ?",
                ("2026-04-26T00:30:00Z", account_id),
            )
            conn.commit()

        self.clock.advance(timedelta(hours=168, seconds=1))
        result = self.job._tick_sync()
        self.assertEqual(result.processed_count, 0)
        self.assertEqual(result.failed_account_ids, ())

        # 软删账号仍保留 suspended 状态（未被改动）
        with connect_database(self.database_path) as conn:
            row = conn.execute(
                "SELECT pool_status, deleted_at FROM accounts WHERE id = ?",
                (account_id,),
            ).fetchone()
        self.assertEqual(row["pool_status"], "suspended")
        self.assertIsNotNone(row["deleted_at"])


class TickFailureModeTestCase(PoolReaperJobTestBase):
    def test_db_scan_failure_writes_failed_reaper_tick_and_returns_error(
        self,
    ) -> None:
        # 注入一个会抛 DB 异常的 repo stub
        class _BrokenRepo:
            def list_by_pool(self, *args, **kwargs):  # noqa: ANN001, ANN002
                raise sqlite3.OperationalError("database is locked")

        broken_job = PoolReaperJob(
            account_pool_repo=_BrokenRepo(),  # type: ignore[arg-type]
            audit_repo=self.audit_repo,
            account_pool_service=self.service,
            clock=self.clock,
        )

        result = broken_job._tick_sync()
        self.assertEqual(result.processed_count, 0)
        self.assertEqual(result.failed_account_ids, ())
        self.assertEqual(result.error, "OperationalError")
        self.assertFalse(result.success)

        rows = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.REAPER_TICK)
        )
        self.assertEqual(len(rows), 1)
        self.assertFalse(rows[0].success)
        self.assertEqual(rows[0].reason, "OperationalError")

    def test_partial_failure_does_not_block_remaining_accounts(self) -> None:
        first = self._seed_suspended(student_id="20231121800")
        second = self._seed_suspended(student_id="20231121801")
        self.clock.advance(timedelta(hours=168, seconds=1))

        # 包装 service.migrate 让 first 抛业务异常，second 仍按原逻辑运行
        original_migrate = self.service.migrate

        def _flaky_migrate(account_id: int, *args, **kwargs):  # noqa: ANN001, ANN002
            if account_id == first:
                from prevent_auto.account_pool.models import (
                    IllegalPoolTransition,
                )

                raise IllegalPoolTransition("注入失败")
            return original_migrate(account_id, *args, **kwargs)

        self.service.migrate = _flaky_migrate  # type: ignore[assignment]
        try:
            result = self.job._tick_sync()
        finally:
            self.service.migrate = original_migrate  # type: ignore[assignment]

        self.assertEqual(result.processed_count, 1)
        self.assertEqual(result.failed_account_ids, (first,))
        self.assertFalse(result.success)
        # second 仍被成功迁移
        self.assertEqual(self._pool_status(second), "idle")
        self.assertEqual(self._pool_status(first), "suspended")

        tick_rows = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.REAPER_TICK)
        )
        self.assertEqual(len(tick_rows), 1)
        self.assertFalse(tick_rows[0].success)
        self.assertEqual(tick_rows[0].reason, "partial_failure")
        self.assertEqual(
            tick_rows[0].payload["failed_account_ids"], [first]
        )


class RunForeverTestCase(PoolReaperJobTestBase):
    def test_run_forever_exits_when_stop_event_set_after_initial_tick(
        self,
    ) -> None:
        async def _run() -> int:
            stop_event = asyncio.Event()
            tick_count = 0
            real_tick = self.job._tick

            async def _counting_tick() -> ReaperTickResult:
                nonlocal tick_count
                tick_count += 1
                result = await real_tick()
                stop_event.set()
                return result

            self.job._tick = _counting_tick  # type: ignore[assignment]
            try:
                await asyncio.wait_for(
                    self.job.run_forever(stop_event), timeout=1.0
                )
            finally:
                self.job._tick = real_tick  # type: ignore[assignment]
            return tick_count

        executed = asyncio.run(_run())
        self.assertEqual(executed, 1)


if __name__ == "__main__":
    unittest.main()
