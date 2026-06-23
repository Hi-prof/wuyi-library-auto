"""account-pool-tri-sync task 8.2 路由单元测试.

覆盖 :mod:`prevent_auto.web.api.active_account` 的三条端点：

* ``GET /api/v1/active-accounts``：仅返回 Active_Pool 账号；响应字段集严格限定为
  design 约定的 5 列 + 顶层 ``server_time``，不得含密码 / cookie / token /
  ``automation_tasks``。
* ``GET /api/v1/active-accounts/{account_id}/detail``：命中活跃账号返回 AES-GCM
  解密后的明文密码、``revision`` 与 ``automation_tasks``；非活跃 / 不存在 / 软删
  统一 ``404 {"detail":"account not found"}``（字节级一致）。
* ``POST /api/v1/active-accounts/{account_id}/blacklist-events``：对活跃账号迁入
  Suspended_Pool 并写带 ``client_kind`` 的审计；非活跃账号同样 ``404``，不泄露
  真实状态。

所有请求都带合法的 Bearer Token；401 / 426 / 429 等中间件路径已在
:mod:`tests.test_web_api_middleware` 验证，本文件不重复。
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
from prevent_auto.database import connect_database, initialize_database
from prevent_auto.repositories.client_api_tokens import ClientApiTokensRepository
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
)
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.pool_audit_log import PoolAuditLogRepository
from prevent_auto.account_pool.models import CustomWindow
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.api import active_account as active_account_api
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
    """可推进的 UTC 时钟，便于在测试中固定 ``pool_updated_at`` 顺序。"""

    def __init__(self, start: datetime) -> None:
        if start.tzinfo is None:
            raise ValueError("start 必须带时区")
        self._current = start.astimezone(UTC)

    def __call__(self) -> datetime:
        return self._current

    def advance(self, delta: timedelta) -> None:
        self._current = self._current + delta


class ActiveAccountApiTestBase(unittest.TestCase):
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
        self.automation_task_service = AutomationTaskService(
            self.database_path,
            automation_tasks_repo=AutomationTasksRepository(self.database_path),
            account_pool_repo=AccountPoolRepository(self.database_path),
            audit_repo=PoolAuditLogRepository(self.database_path),
            clock=self.clock,
        )
        self.audit_repo = PoolAuditLogRepository(self.database_path)
        self.tokens_repo = ClientApiTokensRepository(
            self.database_path, token_pepper="unit-pepper"
        )

        self.app = FastAPI()
        self.app.state.settings = self.settings
        # 中间件顺序与 create_app 一致：HTTPS → 鉴权 → 限频 → 异常处理器 → 路由
        self.app.add_middleware(HttpsRequiredMiddleware, required=False)
        install_auth_dependency_state(self.app, tokens_repository=self.tokens_repo)
        install_detail_rate_limiter(
            self.app, limiter=DetailRateLimiter(limit_per_minute=120)
        )
        register_account_pool_exception_handlers(self.app)
        active_account_api.register(
            self.app, account_pool_service=self.account_pool_service
        )
        self.client = TestClient(self.app)

        self.window_token = self._issue_token(
            label="window-bot", client_kind=ClientKind.WINDOW
        )
        self.android_token = self._issue_token(
            label="android-pad", client_kind=ClientKind.ANDROID
        )

    # ------------------ helpers ------------------

    def _issue_token(self, *, label: str, client_kind: ClientKind) -> str:
        issued = self.tokens_repo.issue(label=label, client_kind=client_kind)
        return issued.raw_token

    def _auth_headers(self, token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    def _seed_idle_account(self, *, student_id: str) -> int:
        """通过既有 service 写入一条 idle 账号，返回 ``account_id``。"""

        result = self.account_pool_service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id=student_id,
                    password="pw-" + student_id,
                    login_url=f"https://x.test/{student_id}/login",
                    display_name="姓名-" + student_id,
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        item = result.items[0]
        assert item.status is BulkImportItemStatus.OK
        assert item.account_id is not None
        return item.account_id

    def _seed_active_account(self, *, student_id: str) -> int:
        account_id = self._seed_idle_account(student_id=student_id)
        self.clock.advance(timedelta(seconds=1))
        self.account_pool_service.migrate(
            account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return account_id


# ============================== 接口 A：列表 ==============================


class ListActiveAccountsTestCase(ActiveAccountApiTestBase):
    def test_returns_empty_list_when_no_active_account(self) -> None:
        response = self.client.get(
            "/api/v1/active-accounts",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertIn("server_time", body)
        self.assertEqual(body["accounts"], [])
        self.assertTrue(body["server_time"].endswith("Z"))

    def test_only_active_accounts_appear(self) -> None:
        active_id = self._seed_active_account(student_id="20231121600")
        idle_id = self._seed_idle_account(student_id="20231121601")
        # 再 seed 一个活跃后挂起到 suspended
        suspended_id = self._seed_active_account(student_id="20231121602")
        self.clock.advance(timedelta(seconds=1))
        self.account_pool_service.migrate(
            suspended_id,
            PoolStatus.SUSPENDED,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )

        response = self.client.get(
            "/api/v1/active-accounts",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(len(body["accounts"]), 1)
        item = body["accounts"][0]
        self.assertEqual(item["account_id"], active_id)
        self.assertEqual(item["student_id"], "20231121600")
        self.assertEqual(item["display_name"], "姓名-20231121600")
        self.assertEqual(item["pool_status"], "active")
        self.assertTrue(str(item["updated_at"]).endswith("Z"))
        # 不含密码 / cookie / token / automation_tasks
        self.assertNotIn("password", item)
        self.assertNotIn("automation_tasks", item)
        self.assertNotIn("password_cipher", item)
        # 顶层只包含 server_time + accounts
        self.assertEqual(set(body.keys()), {"server_time", "accounts"})
        # 单条记录字段集严格 5 列
        self.assertEqual(
            set(item.keys()),
            {"account_id", "student_id", "display_name", "pool_status", "updated_at"},
        )
        # 整个响应文本不带任何明文密码迹象
        self.assertNotIn("pw-20231121600", response.text)

    def test_soft_deleted_active_is_excluded(self) -> None:
        active_id = self._seed_active_account(student_id="20231121603")
        with connect_database(self.database_path) as conn:
            conn.execute(
                "UPDATE accounts SET deleted_at = ? WHERE id = ?",
                ("2026-04-26T00:00:00Z", active_id),
            )
            conn.commit()

        response = self.client.get(
            "/api/v1/active-accounts",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["accounts"], [])

    def test_requires_bearer_token(self) -> None:
        response = self.client.get("/api/v1/active-accounts")

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"reason": "unauthorized"})


# ============================== Windows 上行 ==============================


class UploadActiveAccountsTestCase(ActiveAccountApiTestBase):
    def test_upload_creates_and_updates_active_accounts(self) -> None:
        existing_id = self._seed_idle_account(student_id="20231121700")

        response = self.client.post(
            "/api/v1/active-accounts",
            headers=self._auth_headers(self.window_token),
            json={
                "accounts": [
                    {
                        "student_id": "20231121700",
                        "password": "new-pw-20231121700",
                        "display_name": "更新账号",
                        "login_url": "https://login.test/updated",
                    },
                    {
                        "student_id": "20231121701",
                        "password": "pw-20231121701",
                        "display_name": "新增账号",
                        "login_url": "https://login.test/created",
                    },
                ]
            },
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["total"], 2)
        self.assertEqual(body["created"], 1)
        self.assertEqual(body["updated"], 1)
        self.assertEqual(body["rejected"], 0)
        self.assertNotIn("new-pw-20231121700", response.text)
        self.assertNotIn("pw-20231121701", response.text)

        updated = self.account_pool_service.get_active_detail(existing_id)
        self.assertEqual(updated.display_name, "更新账号")
        self.assertEqual(updated.password, "new-pw-20231121700")

        created_item = next(
            item for item in body["items"] if item["action"] == "created"
        )
        created = self.account_pool_service.get_active_detail(
            created_item["account_id"]
        )
        self.assertEqual(created.student_id, "20231121701")
        self.assertEqual(created.display_name, "新增账号")
        self.assertEqual(created.password, "pw-20231121701")


# ============================== 接口 B：详情 ==============================


class GetActiveAccountDetailTestCase(ActiveAccountApiTestBase):
    def test_returns_decrypted_password_and_tasks(self) -> None:
        account_id = self._seed_active_account(student_id="20231121610")
        # 给该账号挂一条 automation_task
        task = self.automation_task_service.upsert(
            account_id,
            None,
            AutomationTaskUpsertPayload(
                room_name="三层东区",
                seat_number="A12",
                mode="preferred",
                custom_windows=(
                    CustomWindow(date="2026-04-28", start_hour=8, end_hour=12),
                ),
                enabled=True,
            ),
            expected_revision=0,
            operator="admin",
            client_kind=ClientKind.WEB,
        )

        response = self.client.get(
            f"/api/v1/active-accounts/{account_id}/detail",
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(set(body.keys()), {"server_time", "account", "automation_tasks"})
        self.assertTrue(body["server_time"].endswith("Z"))
        # account 字段集
        self.assertEqual(
            set(body["account"].keys()),
            {"account_id", "student_id", "display_name", "password", "revision"},
        )
        self.assertEqual(body["account"]["account_id"], account_id)
        self.assertEqual(body["account"]["student_id"], "20231121610")
        self.assertEqual(body["account"]["display_name"], "姓名-20231121610")
        self.assertEqual(body["account"]["password"], "pw-20231121610")
        self.assertGreaterEqual(int(body["account"]["revision"]), 0)
        # automation_tasks 字段集
        self.assertEqual(len(body["automation_tasks"]), 1)
        task_dict = body["automation_tasks"][0]
        self.assertEqual(
            set(task_dict.keys()),
            {
                "task_id",
                "room_name",
                "seat_number",
                "mode",
                "custom_windows",
                "enabled",
                "revision",
                "updated_at",
            },
        )
        self.assertEqual(task_dict["task_id"], task.task_id)
        self.assertEqual(task_dict["room_name"], "三层东区")
        self.assertEqual(task_dict["seat_number"], "A12")
        self.assertEqual(task_dict["mode"], "preferred")
        self.assertEqual(task_dict["enabled"], True)
        self.assertTrue(str(task_dict["updated_at"]).endswith("Z"))
        self.assertEqual(
            task_dict["custom_windows"],
            [{"date": "2026-04-28", "start_hour": 8, "end_hour": 12}],
        )

    def test_non_active_account_returns_byte_exact_404(self) -> None:
        # 三种「非活跃」语义：不存在 / suspended / 软删
        # 1) 不存在
        for path_account_id in (999_999,):
            with self.subTest(case="not_exist"):
                response = self.client.get(
                    f"/api/v1/active-accounts/{path_account_id}/detail",
                    headers=self._auth_headers(self.window_token),
                )
                self.assertEqual(response.status_code, 404)
                self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)

        # 2) suspended
        suspended_id = self._seed_active_account(student_id="20231121611")
        self.clock.advance(timedelta(seconds=1))
        self.account_pool_service.migrate(
            suspended_id,
            PoolStatus.SUSPENDED,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        response = self.client.get(
            f"/api/v1/active-accounts/{suspended_id}/detail",
            headers=self._auth_headers(self.window_token),
        )
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)

        # 3) 软删一个原本活跃的账号
        active_id = self._seed_active_account(student_id="20231121612")
        with connect_database(self.database_path) as conn:
            conn.execute(
                "UPDATE accounts SET deleted_at = ? WHERE id = ?",
                ("2026-04-26T00:00:00Z", active_id),
            )
            conn.commit()
        response = self.client.get(
            f"/api/v1/active-accounts/{active_id}/detail",
            headers=self._auth_headers(self.window_token),
        )
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)

    def test_requires_bearer_token(self) -> None:
        response = self.client.get("/api/v1/active-accounts/1/detail")

        self.assertEqual(response.status_code, 401)


# ============================== 拉黑事件 ==============================


class BlacklistEventTestCase(ActiveAccountApiTestBase):
    def _valid_body(self, **overrides: object) -> dict[str, object]:
        base: dict[str, object] = {
            "evidence": "人机验证失败 5 次",
            "client_kind": "window",
            "client_observed_at": "2026-04-26T08:30:00Z",
        }
        base.update(overrides)
        return base

    def test_active_account_is_moved_to_suspended(self) -> None:
        account_id = self._seed_active_account(student_id="20231121620")

        response = self.client.post(
            f"/api/v1/active-accounts/{account_id}/blacklist-events",
            json=self._valid_body(),
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["account_id"], account_id)
        self.assertEqual(body["pool_status"], "suspended")
        self.assertTrue(str(body["server_time"]).endswith("Z"))
        self.assertTrue(str(body["suspended_at"]).endswith("Z"))
        self.assertTrue(str(body["suspension_expires_at"]).endswith("Z"))

        # 数据库里 last_blacklist_evidence 落库
        with connect_database(self.database_path) as conn:
            row = conn.execute(
                "SELECT pool_status, last_blacklist_evidence "
                "FROM accounts WHERE id = ?",
                (account_id,),
            ).fetchone()
        self.assertEqual(row["pool_status"], "suspended")
        self.assertEqual(row["last_blacklist_evidence"], "人机验证失败 5 次")

        # 审计：写入了一条 trigger_source='blacklist' / client_kind='window'
        with connect_database(self.database_path) as conn:
            audit = conn.execute(
                "SELECT * FROM pool_audit_log "
                "WHERE audit_action='migrate' AND trigger_source='blacklist' "
                "ORDER BY id DESC LIMIT 1"
            ).fetchone()
        self.assertIsNotNone(audit)
        self.assertEqual(int(audit["account_id"]), account_id)
        self.assertEqual(audit["from_pool"], "active")
        self.assertEqual(audit["to_pool"], "suspended")
        self.assertEqual(audit["client_kind"], "window")
        payload = json.loads(audit["payload_json"])
        self.assertEqual(payload.get("evidence"), "人机验证失败 5 次")

    def test_android_client_kind_is_recorded(self) -> None:
        account_id = self._seed_active_account(student_id="20231121621")

        response = self.client.post(
            f"/api/v1/active-accounts/{account_id}/blacklist-events",
            json=self._valid_body(client_kind="android"),
            headers=self._auth_headers(self.android_token),
        )

        self.assertEqual(response.status_code, 200)
        with connect_database(self.database_path) as conn:
            audit = conn.execute(
                "SELECT client_kind FROM pool_audit_log "
                "WHERE audit_action='migrate' AND trigger_source='blacklist' "
                "ORDER BY id DESC LIMIT 1"
            ).fetchone()
        self.assertEqual(audit["client_kind"], "android")

    def test_non_active_account_returns_byte_exact_404(self) -> None:
        # 1) 不存在
        response = self.client.post(
            "/api/v1/active-accounts/999999/blacklist-events",
            json=self._valid_body(),
            headers=self._auth_headers(self.window_token),
        )
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)

        # 2) 已迁出活跃池的账号同样 404，不泄露真实状态
        suspended_id = self._seed_active_account(student_id="20231121622")
        self.clock.advance(timedelta(seconds=1))
        self.account_pool_service.migrate(
            suspended_id,
            PoolStatus.SUSPENDED,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        response = self.client.post(
            f"/api/v1/active-accounts/{suspended_id}/blacklist-events",
            json=self._valid_body(),
            headers=self._auth_headers(self.window_token),
        )
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)

    def test_missing_evidence_returns_validation_error(self) -> None:
        account_id = self._seed_active_account(student_id="20231121623")

        response = self.client.post(
            f"/api/v1/active-accounts/{account_id}/blacklist-events",
            json=self._valid_body(evidence=""),
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 400)
        body = response.json()
        self.assertEqual(body["reason"], "validation_error")
        fields = {item["field"] for item in body["errors"]}
        self.assertIn("evidence", fields)

    def test_invalid_client_kind_rejected(self) -> None:
        account_id = self._seed_active_account(student_id="20231121624")

        response = self.client.post(
            f"/api/v1/active-accounts/{account_id}/blacklist-events",
            json=self._valid_body(client_kind="web"),  # 不允许 web/system
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 400)
        body = response.json()
        self.assertEqual(body["reason"], "validation_error")
        fields = {item["field"] for item in body["errors"]}
        self.assertIn("client_kind", fields)

    def test_missing_observed_at_rejected(self) -> None:
        account_id = self._seed_active_account(student_id="20231121625")

        response = self.client.post(
            f"/api/v1/active-accounts/{account_id}/blacklist-events",
            json={
                "evidence": "x",
                "client_kind": "window",
                # 缺 client_observed_at
            },
            headers=self._auth_headers(self.window_token),
        )

        self.assertEqual(response.status_code, 400)
        body = response.json()
        self.assertEqual(body["reason"], "validation_error")
        fields = {item["field"] for item in body["errors"]}
        self.assertIn("client_observed_at", fields)

    def test_requires_bearer_token(self) -> None:
        response = self.client.post(
            "/api/v1/active-accounts/1/blacklist-events",
            json=self._valid_body(),
        )

        self.assertEqual(response.status_code, 401)


# ============================== register 装配 ==============================


class RegisterTestCase(unittest.TestCase):
    def test_register_skips_when_secret_key_missing(self) -> None:
        """``ACCOUNT_POOL_SECRET_KEY`` 缺失时 register 直接退出，不挂路由。

        该退化路径与 :func:`prevent_auto.web.runtime.start_pool_reaper_async` 在
        缺密钥时的处理方式一致；现有 :mod:`tests.test_web_app` 套件依赖此分支
        正常工作。
        """

        app = FastAPI()
        app.state.settings = PreventAutoSettings(
            project_root=Path("/tmp"),
            package_root=Path("/tmp"),
            data_dir=Path("/tmp"),
            runtime_dir=Path("/tmp"),
            database_path=Path("/tmp/no.db"),
            host="127.0.0.1",
            port=5000,
            monitor_interval_seconds=60,
            rebook_poll_interval_seconds=15,
            log_retention_days=30,
            account_pool_secret_key="",
        )

        result = active_account_api.register(app)

        self.assertIsNone(result)
        # 应用上没有挂载 active_account 路由
        paths = {route.path for route in app.routes}
        self.assertNotIn("/api/v1/active-accounts", paths)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
