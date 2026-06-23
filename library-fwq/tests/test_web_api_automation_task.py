"""account-pool-tri-sync task 8.3 路由单元测试.

覆盖 :mod:`prevent_auto.web.api.automation_task` 的三条端点：

* ``GET /api/v1/active-accounts/{account_id}/automation-tasks``：默认过滤软删；
  非活跃账号统一 ``404 {"detail":"account not found"}``（与接口 B 字节级一致）。
* ``PUT /api/v1/active-accounts/{account_id}/automation-tasks/{task_id}``：字段
  校验失败映射 ``400 validation_error``；``revision`` 冲突映射 ``409 revision_conflict``
  并回带 ``server_payload``；正常 upsert 写入审计含 ``client_kind``。
* ``DELETE /api/v1/active-accounts/{account_id}/automation-tasks/{task_id}``：以
  ``?revision=`` 触发软删，并写带 ``client_kind`` 的成功审计。

所有请求都带合法的 Bearer Token；没有覆盖 401 / 426 / 429 等中间件路径，那些已经
由 :mod:`tests.test_web_api_middleware` 验证。
"""

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import UTC, datetime, time as dtime, timedelta
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from prevent_auto.account_pool.models import (
    BulkImportItemStatus,
    BulkImportRow,
    ClientKind,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import initialize_database
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.client_api_tokens import ClientApiTokensRepository
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogQuery,
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
from prevent_auto.services.automation_task_service import AutomationTaskService
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.api import automation_task as automation_task_api
from prevent_auto.web.exception_handlers import (
    ACCOUNT_NOT_FOUND_BODY,
    register_account_pool_exception_handlers,
)
from prevent_auto.web.middleware import (
    DetailRateLimiter,
    HttpsRequiredMiddleware,
    install_auth_dependency_state,
    install_detail_rate_limiter,
)


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


class AutomationTaskApiTestBase(unittest.TestCase):
    """为路由测试搭一份完整的 FastAPI 应用，使用真实 SQLite 与服务层。"""

    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "data" / "prevent_auto.db"
        self.secret_key = generate_secret_key()
        self.settings = _build_settings(self.temp_dir, self.secret_key)
        initialize_database(self.database_path, settings=self.settings)
        self.cipher = AccountPasswordCipher(self.secret_key)
        self.cache = InMemoryLoginStatusCache()
        self.clock = _FakeClock(datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC))

        self.account_pool_service = AccountPoolService(
            self.database_path,
            cipher=self.cipher,
            login_status_cache=self.cache,
            clock=self.clock,
        )
        self.automation_tasks_repo = AutomationTasksRepository(self.database_path)
        self.account_pool_repo = AccountPoolRepository(self.database_path)
        self.audit_repo = PoolAuditLogRepository(self.database_path)
        self.tokens_repo = ClientApiTokensRepository(
            self.database_path, token_pepper="unit-pepper"
        )
        self.service = AutomationTaskService(
            self.database_path,
            automation_tasks_repo=self.automation_tasks_repo,
            account_pool_repo=self.account_pool_repo,
            audit_repo=self.audit_repo,
            clock=self.clock,
        )

        self.app = FastAPI()
        self.app.state.settings = self.settings
        # 中间件顺序与 create_app 一致：HTTPS → 鉴权状态 → 限频 → 异常处理器 → 路由
        self.app.add_middleware(HttpsRequiredMiddleware, required=False)
        install_auth_dependency_state(self.app, tokens_repository=self.tokens_repo)
        install_detail_rate_limiter(
            self.app, limiter=DetailRateLimiter(limit_per_minute=120)
        )
        register_account_pool_exception_handlers(self.app)
        automation_task_api.register(self.app, automation_task_service=self.service)
        self.client = TestClient(self.app)

        # 颁发 token 并准备一个活跃账号
        self.window_token = self._issue_token(
            label="window-bot", client_kind=ClientKind.WINDOW
        )
        self.android_token = self._issue_token(
            label="android-pad", client_kind=ClientKind.ANDROID
        )
        self.active_account_id = self._seed_active_account()

    # ------------------ helpers ------------------

    def _issue_token(self, *, label: str, client_kind: ClientKind) -> str:
        issued = self.tokens_repo.issue(label=label, client_kind=client_kind)
        return issued.raw_token

    def _seed_active_account(self) -> int:
        """通过既有 service 把一条 idle 账号迁到 active 池，返回 ``account_id``。"""

        result = self.account_pool_service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id="20231121800",
                    password="pw-20231121800",
                    login_url="https://x.test/login",
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        item = result.items[0]
        assert item.status is BulkImportItemStatus.OK and item.account_id is not None
        account_id = item.account_id
        self.clock.advance(timedelta(seconds=1))
        self.account_pool_service.migrate(
            account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return account_id

    def _auth_headers(self, token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    def _valid_body(self, *, revision: int, **overrides: object) -> dict[str, object]:
        base: dict[str, object] = {
            "room_name": "三层东区",
            "seat_number": "A12",
            "mode": "preferred",
            "custom_windows": [
                {"date": "2026-04-28", "start_hour": 8, "end_hour": 12}
            ],
            "enabled": True,
            "revision": revision,
        }
        base.update(overrides)
        return base

    def _put_task(
        self,
        *,
        account_id: int | None = None,
        task_id: int = 1,
        body: dict[str, object] | None = None,
        token: str | None = None,
    ):
        return self.client.put(
            f"/api/v1/active-accounts/{account_id or self.active_account_id}"
            f"/automation-tasks/{task_id}",
            json=body if body is not None else self._valid_body(revision=0),
            headers=self._auth_headers(token or self.window_token),
        )


class GetAutomationTasksTestCase(AutomationTaskApiTestBase):
    def test_returns_empty_list_when_no_tasks(self) -> None:
        response = self.client.get(
            f"/api/v1/active-accounts/{self.active_account_id}/automation-tasks",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertIn("server_time", body)
        self.assertEqual(body["automation_tasks"], [])

    def test_returns_existing_tasks_excluding_soft_deleted(self) -> None:
        # 先 PUT 两条任务，再软删其中一条
        first = self._put_task(task_id=11)
        self.assertEqual(first.status_code, 200)
        second = self._put_task(task_id=12)
        self.assertEqual(second.status_code, 200)

        delete = self.client.delete(
            f"/api/v1/active-accounts/{self.active_account_id}"
            f"/automation-tasks/12?revision=1",
            headers=self._auth_headers(self.window_token),
        )
        self.assertEqual(delete.status_code, 200)

        response = self.client.get(
            f"/api/v1/active-accounts/{self.active_account_id}/automation-tasks",
            headers=self._auth_headers(self.window_token),
        )
        body = response.json()
        task_ids = [task["task_id"] for task in body["automation_tasks"]]
        self.assertEqual(task_ids, [11])
        self.assertEqual(body["automation_tasks"][0]["revision"], 1)
        self.assertIsNone(body["automation_tasks"][0]["deleted_at"])

    def test_non_active_account_returns_404_byte_exact(self) -> None:
        response = self.client.get(
            "/api/v1/active-accounts/999999/automation-tasks",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)


class UpsertAutomationTaskTestCase(AutomationTaskApiTestBase):
    def test_create_writes_task_and_audit_with_client_kind(self) -> None:
        response = self._put_task(task_id=21, token=self.android_token)

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["task"]["task_id"], 21)
        self.assertEqual(body["task"]["revision"], 1)

        audits = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.TASK_UPLOAD)
        )
        self.assertEqual(len(audits), 1)
        audit = audits[0]
        self.assertEqual(audit.client_kind, ClientKind.ANDROID)
        self.assertEqual(audit.operator, "android-pad")
        self.assertEqual(audit.task_id, 21)
        self.assertTrue(audit.success)

    def test_update_with_correct_revision_increments(self) -> None:
        self._put_task(task_id=31)
        # 当前 revision = 1，再次 PUT 时必须传 1
        response = self._put_task(
            task_id=31,
            body=self._valid_body(revision=1, seat_number="B07"),
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["task"]["seat_number"], "B07")
        self.assertEqual(response.json()["task"]["revision"], 2)

    def test_revision_conflict_returns_409_with_server_payload(self) -> None:
        self._put_task(task_id=41)
        response = self._put_task(
            task_id=41,
            body=self._valid_body(revision=99, seat_number="C03"),
        )

        self.assertEqual(response.status_code, 409)
        body = response.json()
        self.assertEqual(body["reason"], "revision_conflict")
        self.assertEqual(body["server_revision"], 1)
        self.assertEqual(body["server_payload"]["task_id"], 41)
        self.assertEqual(body["server_payload"]["seat_number"], "A12")

        # 同时审计上一定能看到一条 task_upload_rejected
        audits = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.TASK_UPLOAD_REJECTED)
        )
        self.assertGreaterEqual(len(audits), 1)
        latest = audits[0]
        self.assertEqual(latest.reason, "revision_conflict")
        self.assertEqual(latest.client_kind, ClientKind.WINDOW)

    def test_pydantic_validation_error_returns_400_validation_error(self) -> None:
        invalid_body = {
            "room_name": "",  # 非空校验失败
            "seat_number": "A12",
            "mode": "unknown",  # 字面量校验失败
            "custom_windows": [],
            "enabled": True,
            "revision": 0,
        }
        response = self._put_task(task_id=51, body=invalid_body)

        self.assertEqual(response.status_code, 400)
        body = response.json()
        self.assertEqual(body["reason"], "validation_error")
        self.assertIsInstance(body["errors"], list)
        fields = {item["field"] for item in body["errors"]}
        # 至少包含 mode 与 room_name 字段错误
        self.assertIn("mode", fields)
        self.assertTrue(any("room_name" in f for f in fields))

    def test_extra_field_in_body_rejected(self) -> None:
        body = self._valid_body(revision=0)
        body["password"] = "should-not-leak"  # 禁止 extra
        response = self._put_task(task_id=52, body=body)

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["reason"], "validation_error")

    def test_non_active_account_returns_404(self) -> None:
        response = self._put_task(account_id=999999, task_id=61)

        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)

    def test_empty_body_returns_400(self) -> None:
        response = self.client.put(
            f"/api/v1/active-accounts/{self.active_account_id}/automation-tasks/71",
            content=b"",
            headers={
                **self._auth_headers(self.window_token),
                "Content-Type": "application/json",
            },
        )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["reason"], "validation_error")


class DeleteAutomationTaskTestCase(AutomationTaskApiTestBase):
    def test_soft_delete_with_correct_revision(self) -> None:
        self._put_task(task_id=81)

        response = self.client.delete(
            f"/api/v1/active-accounts/{self.active_account_id}"
            f"/automation-tasks/81?revision=1",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["task"]["task_id"], 81)
        self.assertEqual(body["task"]["revision"], 2)
        self.assertIsNotNone(body["task"]["deleted_at"])

        audits = self.audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.TASK_UPLOAD)
        )
        delete_audits = [a for a in audits if a.task_id == 81]
        self.assertGreaterEqual(len(delete_audits), 2)
        # 最新一条应该是 delete
        self.assertEqual(delete_audits[0].payload.get("change_type"), "delete")
        self.assertEqual(delete_audits[0].client_kind, ClientKind.WINDOW)

    def test_delete_revision_conflict_returns_409(self) -> None:
        self._put_task(task_id=82)

        response = self.client.delete(
            f"/api/v1/active-accounts/{self.active_account_id}"
            f"/automation-tasks/82?revision=99",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 409)
        body = response.json()
        self.assertEqual(body["reason"], "revision_conflict")
        self.assertEqual(body["server_revision"], 1)

    def test_delete_non_active_account_returns_404(self) -> None:
        response = self.client.delete(
            "/api/v1/active-accounts/999999/automation-tasks/1?revision=0",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)

    def test_delete_missing_revision_query_returns_422(self) -> None:
        # FastAPI 自带的 query 参数缺失校验返回 422 RequestValidationError
        response = self.client.delete(
            f"/api/v1/active-accounts/{self.active_account_id}"
            f"/automation-tasks/83",
            headers=self._auth_headers(self.window_token),
        )

        # FastAPI default 422 handler；非 /api/v1 之外的契约不在本任务范围内
        self.assertEqual(response.status_code, 422)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
