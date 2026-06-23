"""``services/automation_task_service.py`` 单元测试。

覆盖 spec ``account-pool-tri-sync`` task 5.1 的核心契约：

* ``list_for_active_account``：仅在账号处于 Active_Pool 且未软删时返回非软删
  Automation_Task；非活跃 / 不存在 / 软删一律抛 :class:`AccountNotInActivePool`。
* ``upsert``：字段校验失败 / 账号不在活跃池 / ``revision`` 冲突一律写
  ``task_upload_rejected`` 审计且不动 ``automation_tasks`` 表；通过则在单事务
  内完成「写自动任务 + ``task_upload`` 成功审计」。
* ``soft_delete``：与 ``upsert`` 同样的乐观并发与同事务审计契约。
* 持久化阶段失败（用 mock 注入 ``audit_repo.append`` 抛错）时审计与数据一起回滚，
  不留下「日志说成功但数据没落库」的中间态。
"""

from __future__ import annotations

import json
import sqlite3
import tempfile
import unittest
from datetime import UTC, datetime, time as dtime, timedelta
from pathlib import Path
from unittest import mock

from prevent_auto.account_pool.models import (
    AccountNotInActivePool,
    BulkImportItemStatus,
    BulkImportRow,
    ClientKind,
    CustomWindow,
    PoolMigrationTrigger,
    PoolStatus,
    RevisionConflict,
)
from prevent_auto.database import connect_database, initialize_database
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogRepository,
)
from prevent_auto.services.account_password_cipher import (
    AccountPasswordCipher,
    generate_secret_key,
)
from prevent_auto.services.account_pool_service import (
    AccountPoolService,
    InMemoryLoginStatusCache,
)
from prevent_auto.services.automation_task_service import (
    AutomationTaskService,
    AutomationTaskUpsertPayload,
    AutomationTaskValidationError,
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
    def __init__(self, start: datetime) -> None:
        if start.tzinfo is None:
            raise ValueError("start 必须带时区")
        self._current = start.astimezone(UTC)

    def __call__(self) -> datetime:
        return self._current

    def advance(self, delta: timedelta) -> None:
        self._current = self._current + delta


def _valid_payload(**overrides: object) -> AutomationTaskUpsertPayload:
    """构造一份合法 payload，便于在每个测试里只覆盖关心的字段。"""

    base: dict[str, object] = {
        "room_name": "三层东区",
        "seat_number": "A12",
        "mode": "preferred",
        "custom_windows": (
            CustomWindow(date="2026-04-28", start_hour=8, end_hour=12),
        ),
        "enabled": True,
    }
    base.update(overrides)
    return AutomationTaskUpsertPayload(**base)  # type: ignore[arg-type]


class AutomationTaskServiceTestBase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "data" / "prevent_auto.db"
        self.secret_key = generate_secret_key()
        self.settings = _build_settings(self.temp_dir, self.secret_key)
        initialize_database(self.database_path, settings=self.settings)
        self.cipher = AccountPasswordCipher(self.secret_key)
        self.cache = InMemoryLoginStatusCache()
        self.clock = _FakeClock(datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC))

        # AccountPoolService 用来 seed 活跃账号；Automation_Task_Service 复用同
        # 一份数据库与三类仓库。
        self.account_pool_service = AccountPoolService(
            self.database_path,
            cipher=self.cipher,
            login_status_cache=self.cache,
            clock=self.clock,
        )
        self.automation_tasks_repo = AutomationTasksRepository(self.database_path)
        self.account_pool_repo = AccountPoolRepository(self.database_path)
        self.audit_repo = PoolAuditLogRepository(self.database_path)
        self.service = AutomationTaskService(
            self.database_path,
            automation_tasks_repo=self.automation_tasks_repo,
            account_pool_repo=self.account_pool_repo,
            audit_repo=self.audit_repo,
            clock=self.clock,
        )

    # ------------------ 共用 seed 工具 ------------------

    def _seed_active_account(self, *, student_id: str = "20231121800") -> int:
        result = self.account_pool_service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id=student_id,
                    password=f"pw-{student_id}",
                    login_url="https://x.test/login",
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        item = result.items[0]
        assert item.status is BulkImportItemStatus.OK
        assert item.account_id is not None
        account_id = item.account_id
        self.clock.advance(timedelta(seconds=1))
        self.account_pool_service.migrate(
            account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return account_id

    def _seed_idle_account(self, *, student_id: str = "20231121801") -> int:
        result = self.account_pool_service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id=student_id,
                    password=f"pw-{student_id}",
                    login_url="https://x.test/login",
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        item = result.items[0]
        assert item.status is BulkImportItemStatus.OK
        assert item.account_id is not None
        return item.account_id

    # ------------------ 通用断言 ------------------

    def _audit_rows(self) -> list[sqlite3.Row]:
        with connect_database(self.database_path) as conn:
            return list(
                conn.execute(
                    "SELECT * FROM pool_audit_log ORDER BY id ASC"
                ).fetchall()
            )

    def _automation_task_rows(self) -> list[sqlite3.Row]:
        with connect_database(self.database_path) as conn:
            return list(
                conn.execute(
                    "SELECT * FROM automation_tasks ORDER BY id ASC"
                ).fetchall()
            )


# ============================== list_for_active_account ==============================


class ListForActiveAccountTestCase(AutomationTaskServiceTestBase):
    def test_returns_only_non_soft_deleted_for_active(self) -> None:
        account_id = self._seed_active_account()
        # 新建两条任务，再软删其中一条
        first = self.service.upsert(
            account_id,
            None,
            _valid_payload(seat_number="A11"),
            0,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )
        second = self.service.upsert(
            account_id,
            None,
            _valid_payload(seat_number="A22"),
            0,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )
        self.service.soft_delete(
            account_id,
            second.task_id,
            expected_revision=second.revision,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )

        tasks = self.service.list_for_active_account(account_id)
        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0].task_id, first.task_id)
        self.assertIsNone(tasks[0].deleted_at)

    def test_empty_list_for_active_without_tasks(self) -> None:
        account_id = self._seed_active_account()
        self.assertEqual(self.service.list_for_active_account(account_id), [])

    def test_unknown_account_raises(self) -> None:
        with self.assertRaises(AccountNotInActivePool):
            self.service.list_for_active_account(999_999)

    def test_idle_account_raises(self) -> None:
        idle_id = self._seed_idle_account()
        with self.assertRaises(AccountNotInActivePool):
            self.service.list_for_active_account(idle_id)

    def test_soft_deleted_account_raises(self) -> None:
        account_id = self._seed_active_account()
        with connect_database(self.database_path) as conn:
            conn.execute(
                "UPDATE accounts SET deleted_at = ? WHERE id = ?",
                ("2026-04-26T00:00:00Z", account_id),
            )
            conn.commit()
        with self.assertRaises(AccountNotInActivePool):
            self.service.list_for_active_account(account_id)

    def test_list_does_not_write_audit(self) -> None:
        account_id = self._seed_active_account()
        before = len(self._audit_rows())
        self.service.list_for_active_account(account_id)
        self.assertEqual(len(self._audit_rows()), before)


# ============================== upsert ==============================


class UpsertCreateTestCase(AutomationTaskServiceTestBase):
    def test_create_writes_task_and_success_audit_in_same_transaction(self) -> None:
        account_id = self._seed_active_account()
        before_audit = len(self._audit_rows())

        task = self.service.upsert(
            account_id,
            None,
            _valid_payload(),
            expected_revision=0,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )

        self.assertEqual(task.account_id, account_id)
        self.assertEqual(task.revision, 1)
        self.assertEqual(task.room_name, "三层东区")
        self.assertEqual(task.seat_number, "A12")
        self.assertEqual(task.enabled, True)
        self.assertEqual(len(task.custom_windows), 1)

        # 数据库中存在一行 + 一条 task_upload 审计
        rows = self._automation_task_rows()
        self.assertEqual(len(rows), 1)
        audits = self._audit_rows()
        self.assertEqual(len(audits) - before_audit, 1)
        latest = audits[-1]
        self.assertEqual(latest["audit_action"], "task_upload")
        self.assertEqual(int(latest["account_id"]), account_id)
        self.assertEqual(int(latest["task_id"]), task.task_id)
        self.assertEqual(latest["client_kind"], "android")
        self.assertEqual(int(latest["success"]), 1)
        payload = json.loads(latest["payload_json"])
        self.assertEqual(payload["change_type"], "create")
        self.assertEqual(payload["revision"], 1)

    def test_create_with_explicit_task_id(self) -> None:
        account_id = self._seed_active_account()
        task = self.service.upsert(
            account_id,
            42,
            _valid_payload(),
            expected_revision=0,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )
        self.assertEqual(task.task_id, 42)
        self.assertEqual(task.revision, 1)


class UpsertUpdateTestCase(AutomationTaskServiceTestBase):
    def test_update_increments_revision_and_writes_update_audit(self) -> None:
        account_id = self._seed_active_account()
        first = self.service.upsert(
            account_id,
            None,
            _valid_payload(),
            expected_revision=0,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )
        before_audit = len(self._audit_rows())

        updated = self.service.upsert(
            account_id,
            first.task_id,
            _valid_payload(seat_number="B33", enabled=False),
            expected_revision=first.revision,
            operator="token-window-1",
            client_kind=ClientKind.WINDOW,
        )

        self.assertEqual(updated.task_id, first.task_id)
        self.assertEqual(updated.revision, first.revision + 1)
        self.assertEqual(updated.seat_number, "B33")
        self.assertFalse(updated.enabled)

        audits = self._audit_rows()
        self.assertEqual(len(audits) - before_audit, 1)
        latest = audits[-1]
        self.assertEqual(latest["audit_action"], "task_upload")
        payload = json.loads(latest["payload_json"])
        self.assertEqual(payload["change_type"], "update")
        self.assertEqual(payload["revision"], updated.revision)
        self.assertEqual(latest["client_kind"], "window")


class UpsertValidationTestCase(AutomationTaskServiceTestBase):
    def _expect_validation_error(
        self, payload: AutomationTaskUpsertPayload, *, account_id: int | None = None
    ) -> AutomationTaskValidationError:
        if account_id is None:
            account_id = self._seed_active_account()
        before_task_count = len(self._automation_task_rows())
        before_audit = len(self._audit_rows())

        with self.assertRaises(AutomationTaskValidationError) as ctx:
            self.service.upsert(
                account_id,
                None,
                payload,
                expected_revision=0,
                operator="token-android-1",
                client_kind=ClientKind.ANDROID,
            )

        # 字段校验失败：不动 automation_tasks 表，但写一条 rejection 审计
        self.assertEqual(len(self._automation_task_rows()), before_task_count)
        audits = self._audit_rows()
        self.assertEqual(len(audits) - before_audit, 1)
        latest = audits[-1]
        self.assertEqual(latest["audit_action"], "task_upload_rejected")
        self.assertEqual(latest["reason"], "validation_error")
        self.assertEqual(int(latest["success"]), 0)
        self.assertEqual(latest["client_kind"], "android")
        return ctx.exception

    def test_room_name_empty_rejected(self) -> None:
        exc = self._expect_validation_error(_valid_payload(room_name="   "))
        self.assertTrue(any(err.field == "room_name" for err in exc.errors))

    def test_room_name_too_long_rejected(self) -> None:
        exc = self._expect_validation_error(
            _valid_payload(room_name="x" * 65)
        )
        self.assertTrue(any(err.field == "room_name" for err in exc.errors))

    def test_seat_number_too_long_rejected(self) -> None:
        exc = self._expect_validation_error(
            _valid_payload(seat_number="x" * 33)
        )
        self.assertTrue(any(err.field == "seat_number" for err in exc.errors))

    def test_unknown_mode_rejected(self) -> None:
        exc = self._expect_validation_error(_valid_payload(mode="unknown"))
        self.assertTrue(any(err.field == "mode" for err in exc.errors))

    def test_enabled_must_be_bool(self) -> None:
        exc = self._expect_validation_error(_valid_payload(enabled=1))  # type: ignore[arg-type]
        self.assertTrue(any(err.field == "enabled" for err in exc.errors))

    def test_custom_window_hour_out_of_range_rejected(self) -> None:
        exc = self._expect_validation_error(
            _valid_payload(
                custom_windows=(
                    CustomWindow(date="2026-04-28", start_hour=-1, end_hour=12),
                )
            )
        )
        self.assertTrue(
            any(err.field == "custom_windows[0].start_hour" for err in exc.errors)
        )

    def test_custom_window_hour_24_rejected(self) -> None:
        exc = self._expect_validation_error(
            _valid_payload(
                custom_windows=(
                    CustomWindow(date="2026-04-28", start_hour=0, end_hour=24),
                )
            )
        )
        self.assertTrue(
            any(err.field == "custom_windows[0].end_hour" for err in exc.errors)
        )

    def test_custom_window_end_must_be_strictly_greater_than_start(self) -> None:
        exc = self._expect_validation_error(
            _valid_payload(
                custom_windows=(
                    CustomWindow(date="2026-04-28", start_hour=10, end_hour=10),
                )
            )
        )
        self.assertTrue(
            any(
                err.field == "custom_windows[0].end_hour"
                and "greater than start_hour" in err.message
                for err in exc.errors
            )
        )

    def test_validation_runs_before_active_check(self) -> None:
        # 即使账号不存在，字段错误优先翻译为 validation_error rejection
        before_audit = len(self._audit_rows())
        with self.assertRaises(AutomationTaskValidationError):
            self.service.upsert(
                999_999,
                None,
                _valid_payload(room_name=""),
                expected_revision=0,
                operator="token-android-1",
                client_kind=ClientKind.ANDROID,
            )
        latest = self._audit_rows()[-1]
        self.assertEqual(len(self._audit_rows()) - before_audit, 1)
        self.assertEqual(latest["reason"], "validation_error")


class UpsertActiveCheckTestCase(AutomationTaskServiceTestBase):
    def test_unknown_account_rejected_with_audit(self) -> None:
        before_task_count = len(self._automation_task_rows())
        before_audit = len(self._audit_rows())

        with self.assertRaises(AccountNotInActivePool):
            self.service.upsert(
                999_999,
                None,
                _valid_payload(),
                expected_revision=0,
                operator="token-android-1",
                client_kind=ClientKind.ANDROID,
            )

        self.assertEqual(len(self._automation_task_rows()), before_task_count)
        audits = self._audit_rows()
        self.assertEqual(len(audits) - before_audit, 1)
        latest = audits[-1]
        self.assertEqual(latest["audit_action"], "task_upload_rejected")
        self.assertEqual(latest["reason"], "account_not_active")

    def test_idle_account_rejected_with_audit(self) -> None:
        idle_id = self._seed_idle_account()
        with self.assertRaises(AccountNotInActivePool):
            self.service.upsert(
                idle_id,
                None,
                _valid_payload(),
                expected_revision=0,
                operator="token-android-1",
                client_kind=ClientKind.ANDROID,
            )
        latest = self._audit_rows()[-1]
        self.assertEqual(latest["reason"], "account_not_active")
        self.assertEqual(int(latest["account_id"]), idle_id)


class UpsertRevisionConflictTestCase(AutomationTaskServiceTestBase):
    def test_stale_revision_returns_server_revision_and_payload(self) -> None:
        account_id = self._seed_active_account()
        first = self.service.upsert(
            account_id,
            None,
            _valid_payload(seat_number="A11"),
            expected_revision=0,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )
        # 客户端再次以「认为还是 0」上传 → 冲突
        before_audit = len(self._audit_rows())
        with self.assertRaises(RevisionConflict) as ctx:
            self.service.upsert(
                account_id,
                first.task_id,
                _valid_payload(seat_number="B22"),
                expected_revision=0,
                operator="token-android-1",
                client_kind=ClientKind.ANDROID,
            )
        exc = ctx.exception
        self.assertEqual(exc.server_revision, first.revision)
        self.assertEqual(exc.server_payload["task_id"], first.task_id)
        self.assertEqual(exc.server_payload["revision"], first.revision)

        # automation_tasks 行未被改动
        with connect_database(self.database_path) as conn:
            row = conn.execute(
                "SELECT seat_number, revision FROM automation_tasks WHERE id = ?",
                (first.task_id,),
            ).fetchone()
        self.assertEqual(row["seat_number"], "A11")
        self.assertEqual(int(row["revision"]), first.revision)

        # 写了一条 rejection 审计，附带 expected / server revision
        audits = self._audit_rows()
        self.assertEqual(len(audits) - before_audit, 1)
        latest = audits[-1]
        self.assertEqual(latest["reason"], "revision_conflict")
        payload = json.loads(latest["payload_json"])
        self.assertEqual(payload["expected_revision"], 0)
        self.assertEqual(payload["server_revision"], first.revision)


class UpsertAtomicityTestCase(AutomationTaskServiceTestBase):
    def test_audit_failure_during_persistence_rolls_back_data(self) -> None:
        """如果成功审计写入因系统原因失败，automation_tasks 也要一起回滚。

        通过给 ``audit_repo.append`` 注入一次 ``OperationalError``，模拟「数据写入
        阶段失败」；事务 ROLLBACK 后 automation_tasks 应该没有任何新行。
        """

        account_id = self._seed_active_account()

        # 之前 seed_active 与 bulk_import 已经写过审计；记录基线，接下来注入故障
        before_audit = len(self._audit_rows())
        before_tasks = len(self._automation_task_rows())

        original_append = self.audit_repo.append
        call_count = {"value": 0}

        def flaky_append(entry, *, connection=None):
            call_count["value"] += 1
            # 第一次进入「成功审计」时抛错，让事务回滚
            if (
                connection is not None
                and entry.audit_action is PoolAuditAction.TASK_UPLOAD
            ):
                raise sqlite3.OperationalError("inject persistence failure")
            return original_append(entry, connection=connection)

        with mock.patch.object(self.audit_repo, "append", side_effect=flaky_append):
            with self.assertRaises(sqlite3.OperationalError):
                self.service.upsert(
                    account_id,
                    None,
                    _valid_payload(),
                    expected_revision=0,
                    operator="token-android-1",
                    client_kind=ClientKind.ANDROID,
                )

        # automation_tasks 没有新增；审计也没有新增（成功审计被注入失败回滚）
        self.assertEqual(len(self._automation_task_rows()), before_tasks)
        self.assertEqual(len(self._audit_rows()), before_audit)


# ============================== soft_delete ==============================


class SoftDeleteTestCase(AutomationTaskServiceTestBase):
    def test_soft_delete_marks_deleted_and_writes_delete_audit(self) -> None:
        account_id = self._seed_active_account()
        first = self.service.upsert(
            account_id,
            None,
            _valid_payload(),
            expected_revision=0,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )
        before_audit = len(self._audit_rows())

        deleted = self.service.soft_delete(
            account_id,
            first.task_id,
            expected_revision=first.revision,
            operator="token-android-1",
            client_kind=ClientKind.ANDROID,
        )

        self.assertEqual(deleted.task_id, first.task_id)
        self.assertEqual(deleted.revision, first.revision + 1)
        self.assertIsNotNone(deleted.deleted_at)

        audits = self._audit_rows()
        self.assertEqual(len(audits) - before_audit, 1)
        latest = audits[-1]
        self.assertEqual(latest["audit_action"], "task_upload")
        payload = json.loads(latest["payload_json"])
        self.assertEqual(payload["change_type"], "delete")
        self.assertEqual(payload["revision"], deleted.revision)

    def test_soft_delete_unknown_task_revision_zero_rejected(self) -> None:
        account_id = self._seed_active_account()
        before_audit = len(self._audit_rows())
        with self.assertRaises(RevisionConflict):
            self.service.soft_delete(
                account_id,
                999_999,
                expected_revision=0,
                operator="token-android-1",
                client_kind=ClientKind.ANDROID,
            )
        latest = self._audit_rows()[-1]
        self.assertEqual(len(self._audit_rows()) - before_audit, 1)
        self.assertEqual(latest["reason"], "revision_conflict")

    def test_soft_delete_idle_account_rejected_with_audit(self) -> None:
        idle_id = self._seed_idle_account()
        before_audit = len(self._audit_rows())
        with self.assertRaises(AccountNotInActivePool):
            self.service.soft_delete(
                idle_id,
                123,
                expected_revision=0,
                operator="token-android-1",
                client_kind=ClientKind.ANDROID,
            )
        latest = self._audit_rows()[-1]
        self.assertEqual(len(self._audit_rows()) - before_audit, 1)
        self.assertEqual(latest["audit_action"], "task_upload_rejected")
        self.assertEqual(latest["reason"], "account_not_active")


if __name__ == "__main__":
    unittest.main()
