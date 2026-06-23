"""account-pool-tri-sync · task 9.3 单元测试。

覆盖：

* ``GET /accounts?pool=idle``：未启用池 Tab 顶部渲染「随机抽一个」按钮，并指向
  ``POST /accounts/random-pick`` 端点；其它 Tab 不渲染该按钮。
* ``POST /accounts/random-pick``：
  * 命中后 303 重定向到 ``/accounts?pool=idle&random_picked_account_id=N``，且账号
    仍在 Idle_Pool（不改池归属）；后续 ``GET`` 渲染 modal 仅展示学号 / 备注，
    **不**展示密码（Requirement 5.6 / 5.7）。
  * 空池命中 :class:`IdleEmpty` 时不抛异常，重定向 notice 带 ``idle_empty`` 前缀
    （Requirement 5.8）。
  * 号池服务未装配时给出中文 toast，不 5xx。
* ``GET /accounts?random_picked_account_id=...``：
  * 非整数 / 非 Idle 账号 / 不存在账号时不渲染 modal，避免在迁出后仍弹窗。
"""

from __future__ import annotations

import tempfile
import unittest
from datetime import time as dtime
from pathlib import Path
from urllib.parse import unquote, urlparse, parse_qs

from fastapi.testclient import TestClient

from prevent_auto.account_pool.models import (
    BulkImportRow,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import initialize_database
from prevent_auto.services.account_password_cipher import generate_secret_key
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.app import create_app


def _build_settings(temp_dir: Path, secret_key: str) -> PreventAutoSettings:
    return PreventAutoSettings(
        project_root=temp_dir,
        package_root=Path(__file__).resolve().parents[1],
        data_dir=temp_dir,
        runtime_dir=temp_dir / "runtime",
        database_path=temp_dir / "prevent_auto.db",
        host="127.0.0.1",
        port=8080,
        monitor_interval_seconds=1500,
        rebook_poll_interval_seconds=15,
        log_retention_days=30,
        daily_status_refresh_time=dtime(8, 10),
        account_pool_secret_key=secret_key,
    )


class _RandomPickTestBase(unittest.TestCase):
    """通用启动逻辑：配密钥 + 初始化 DB + 登录 cookie。"""

    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.secret_key = generate_secret_key()
        self.settings = _build_settings(self.temp_dir, self.secret_key)
        initialize_database(self.settings.database_path, settings=self.settings)
        self.client = TestClient(
            create_app(self.settings, start_background_workers=False)
        )
        self._login()

    def _login(self) -> None:
        response = self.client.post(
            "/login",
            data={
                "username": self.settings.auth_username,
                "password": self.settings.auth_password,
                "next": "/",
            },
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 303)

    def _get_pool_service(self):
        pool_service = self.client.app.state.services.account_pool_service
        assert pool_service is not None
        return pool_service

    def _seed_idle(
        self,
        *,
        student_id: str = "20231121400",
        password: str = "pw-pick",
        display_name: str = "随机抽取-备注",
        login_url: str = "https://x.test/login",
    ) -> int:
        result = self._get_pool_service().bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id=student_id,
                    password=password,
                    display_name=display_name,
                    login_url=login_url,
                ),
            ],
            operator=self.settings.auth_username,
        )
        item = result.items[0]
        assert item.account_id is not None
        return item.account_id

    def _read_redirect_qs(self, response) -> dict[str, list[str]]:
        location = response.headers["location"]
        parsed = urlparse(location)
        return parse_qs(parsed.query, keep_blank_values=True)


class RandomPickButtonRenderingTestCase(_RandomPickTestBase):
    """``GET /accounts?pool=...`` 顶部按钮的可见性。"""

    def test_button_visible_only_on_idle_tab(self) -> None:
        idle_response = self.client.get("/accounts?pool=idle")
        self.assertEqual(idle_response.status_code, 200)
        self.assertIn("随机抽一个", idle_response.text)
        self.assertIn(
            "action=\"/accounts/random-pick\"",
            idle_response.text,
        )

        active_response = self.client.get("/accounts?pool=active")
        self.assertEqual(active_response.status_code, 200)
        self.assertNotIn("随机抽一个", active_response.text)
        self.assertNotIn(
            "action=\"/accounts/random-pick\"",
            active_response.text,
        )

        suspended_response = self.client.get("/accounts?pool=suspended")
        self.assertEqual(suspended_response.status_code, 200)
        self.assertNotIn("随机抽一个", suspended_response.text)
        self.assertNotIn(
            "action=\"/accounts/random-pick\"",
            suspended_response.text,
        )


class RandomPickEndpointHitTestCase(_RandomPickTestBase):
    """命中路径：抽取后跳转携带 ``random_picked_account_id``，模态展示学号 / 备注。"""

    def test_redirect_carries_random_picked_account_id_and_idle_pool_unchanged(
        self,
    ) -> None:
        account_id = self._seed_idle(
            student_id="20231121401",
            password="pw-secret-401",
            display_name="第一号",
        )

        response = self.client.post(
            "/accounts/random-pick",
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        location = response.headers["location"]
        self.assertTrue(location.startswith("/accounts?pool=idle"))
        qs = self._read_redirect_qs(response)
        self.assertEqual(qs.get("pool"), ["idle"])
        self.assertEqual(
            qs.get("random_picked_account_id"),
            [str(account_id)],
        )
        notice_values = qs.get("notice") or [""]
        self.assertIn("已随机抽中", unquote(notice_values[0]))

        # 池归属保持不变（Requirement 5.7）
        idle_entries = self._get_pool_service().list_by_pool(PoolStatus.IDLE)
        self.assertEqual({e.account_id for e in idle_entries}, {account_id})

    def test_modal_shows_student_id_and_display_name_without_password(
        self,
    ) -> None:
        account_id = self._seed_idle(
            student_id="20231121402",
            password="pw-secret-402",
            display_name="第二号",
        )

        get_response = self.client.get(
            f"/accounts?pool=idle&random_picked_account_id={account_id}"
        )

        self.assertEqual(get_response.status_code, 200)
        body = get_response.text
        self.assertIn("id=\"random-picked-dialog\"", body)
        self.assertIn("data-auto-open=\"true\"", body)
        self.assertIn("20231121402", body)
        self.assertIn("第二号", body)
        # 密码字段绝不出现在模态 / URL / 模板里
        self.assertNotIn("pw-secret-402", body)
        # 底部「迁入活跃池（二次确认）」按钮指向 migrate 端点
        self.assertIn(
            f"action=\"/accounts/{account_id}/migrate\"",
            body,
        )
        self.assertIn("迁入活跃池（二次确认）", body)
        self.assertIn(
            "data-confirm-message=\"确认把账号 20231121402 迁入活跃池\"",
            body,
        )

    def test_modal_not_rendered_for_non_idle_account(self) -> None:
        account_id = self._seed_idle(
            student_id="20231121403",
            password="pw-secret-403",
            display_name="第三号",
        )
        # 把账号迁到 Active_Pool，再带 random_picked_account_id 访问
        # （模拟用户刷新一段时间前抽到、但已被人为迁入活跃池的账号）。
        self._get_pool_service().migrate(
            account_id,
            PoolStatus.ACTIVE,
            operator=self.settings.auth_username,
            trigger_source=PoolMigrationTrigger.MANUAL,
        )

        get_response = self.client.get(
            f"/accounts?pool=idle&random_picked_account_id={account_id}"
        )

        self.assertEqual(get_response.status_code, 200)
        self.assertNotIn("id=\"random-picked-dialog\"", get_response.text)

    def test_modal_not_rendered_for_invalid_account_id(self) -> None:
        # 非整数、负数、缺失全部不渲染模态
        for raw in ("not-a-number", "-1", "0", ""):
            with self.subTest(raw=raw):
                response = self.client.get(
                    f"/accounts?pool=idle&random_picked_account_id={raw}"
                )
                self.assertEqual(response.status_code, 200)
                self.assertNotIn(
                    "id=\"random-picked-dialog\"", response.text
                )


class RandomPickEndpointEmptyPoolTestCase(_RandomPickTestBase):
    """空池路径：返回 idle_empty 文案，且不抛异常给前端（Requirement 5.8）。"""

    def test_idle_empty_redirects_with_idle_empty_notice(self) -> None:
        # 不 seed 任何账号，Idle_Pool 为空
        idle_entries = self._get_pool_service().list_by_pool(PoolStatus.IDLE)
        self.assertEqual(len(idle_entries), 0)

        response = self.client.post(
            "/accounts/random-pick",
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        location = response.headers["location"]
        self.assertTrue(location.startswith("/accounts?pool=idle"))
        qs = self._read_redirect_qs(response)
        notice = unquote((qs.get("notice") or [""])[0])
        self.assertTrue(notice.startswith("idle_empty"))
        self.assertIn("未启用池为空", notice)
        # 不应带 random_picked_account_id（避免误触模态）
        self.assertNotIn("random_picked_account_id", qs)


class RandomPickEndpointWithoutPoolServiceTestCase(unittest.TestCase):
    """``ACCOUNT_POOL_SECRET_KEY`` 缺失：服务未装配，应给出中文错误 toast。"""

    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.settings = PreventAutoSettings(
            project_root=self.temp_dir,
            package_root=Path(__file__).resolve().parents[1],
            data_dir=self.temp_dir,
            runtime_dir=self.temp_dir / "runtime",
            database_path=self.temp_dir / "prevent_auto.db",
            host="127.0.0.1",
            port=8080,
            monitor_interval_seconds=1500,
            rebook_poll_interval_seconds=15,
            log_retention_days=30,
            daily_status_refresh_time=dtime(8, 10),
            account_pool_secret_key="",
        )
        initialize_database(self.settings.database_path)
        self.client = TestClient(
            create_app(self.settings, start_background_workers=False)
        )
        login_response = self.client.post(
            "/login",
            data={
                "username": self.settings.auth_username,
                "password": self.settings.auth_password,
                "next": "/",
            },
            follow_redirects=False,
        )
        self.assertEqual(login_response.status_code, 303)

    def test_endpoint_redirects_with_service_missing_notice(self) -> None:
        response = self.client.post(
            "/accounts/random-pick",
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        location = response.headers["location"]
        self.assertIn("/accounts?pool=idle", location)
        self.assertIn("notice=", location)
        notice = unquote(location.split("notice=", 1)[1])
        self.assertIn("号池服务未装配", notice)

    def test_idle_tab_does_not_render_random_pick_button(self) -> None:
        response = self.client.get("/accounts?pool=idle")

        self.assertEqual(response.status_code, 200)
        # 缺密钥时三池区块整体不渲染，与 task 9.1 的兜底一致
        self.assertNotIn("随机抽一个", response.text)
        self.assertNotIn(
            "action=\"/accounts/random-pick\"",
            response.text,
        )


if __name__ == "__main__":
    unittest.main()
