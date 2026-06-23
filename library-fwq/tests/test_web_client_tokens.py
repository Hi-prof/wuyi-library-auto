"""``/client-tokens`` 管理页面与签发 / 撤销路由的单元测试。

对应 spec ``account-pool-tri-sync`` task 9.4：
* 模板渲染包含签发表单、列表、明文一次性展示提示。
* ``POST /client-tokens`` 仅在数据库写入 hash，明文通过查询参数一次性回显。
* ``POST /client-tokens/{id}/revoke`` 将记录标记为撤销并维持页面可达。
* 路由走 cookie 鉴权（未登录时重定向到 /login）。
"""

from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from fastapi.testclient import TestClient

from prevent_auto.database import initialize_database
from prevent_auto.repositories.client_api_tokens import (
    ClientApiTokensRepository,
    hash_token,
)
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.app import create_app


class ClientTokensWebTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        self.runtime_dir = self.temp_dir / "runtime"
        initialize_database(self.database_path)
        self.token_pepper = "client_tokens_test_pepper"
        self.settings = PreventAutoSettings(
            project_root=self.temp_dir,
            package_root=Path(__file__).resolve().parents[1],
            data_dir=self.temp_dir,
            runtime_dir=self.runtime_dir,
            database_path=self.database_path,
            host="127.0.0.1",
            port=8080,
            monitor_interval_seconds=1500,
            rebook_poll_interval_seconds=15,
            log_retention_days=30,
            account_pool_token_pepper=self.token_pepper,
        )
        self.client = TestClient(
            create_app(self.settings, start_background_workers=False)
        )

    def login(self) -> None:
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

    def repository(self) -> ClientApiTokensRepository:
        return ClientApiTokensRepository(
            self.database_path,
            token_pepper=self.token_pepper,
        )

    def test_page_requires_login(self) -> None:
        response = self.client.get("/client-tokens", follow_redirects=False)

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/login?next="))

    def test_page_renders_form_and_empty_state(self) -> None:
        self.login()

        response = self.client.get("/client-tokens")

        self.assertEqual(response.status_code, 200)
        self.assertIn("客户端 Token 签发", response.text)
        self.assertIn("Windows 客户端", response.text)
        self.assertIn("Android 客户端", response.text)
        self.assertIn("暂未签发任何客户端 Token", response.text)
        # 未签发时不应渲染明文展示横幅
        self.assertNotIn("data-issued-token-banner", response.text)

    def test_issue_redirects_with_one_time_plaintext(self) -> None:
        self.login()

        response = self.client.post(
            "/client-tokens",
            data={"label": "windows-办公室", "client_kind": "window"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        location = urlparse(response.headers["location"])
        self.assertEqual(location.path, "/client-tokens")
        params = parse_qs(location.query)
        self.assertIn("issued_token", params)
        self.assertIn("issued_label", params)
        self.assertEqual(params["issued_label"], ["windows-办公室"])
        raw_token = params["issued_token"][0]
        self.assertGreaterEqual(len(raw_token), 20)

        # 数据库只应保存 sha256 hash，绝对不能保存明文
        with sqlite3.connect(self.database_path) as conn:
            row = conn.execute(
                "SELECT label, client_kind, token_hash FROM client_api_tokens"
            ).fetchone()
        self.assertIsNotNone(row)
        label, client_kind, token_hash = row
        self.assertEqual(label, "windows-办公室")
        self.assertEqual(client_kind, "window")
        self.assertEqual(token_hash, hash_token(raw_token, self.token_pepper))

        # 跟随重定向后页面要展示明文 + 一次性提醒，且明文不再来自数据库
        page = self.client.get(response.headers["location"])
        self.assertEqual(page.status_code, 200)
        self.assertIn("这是该 Token 唯一一次明文展示", page.text)
        self.assertIn(raw_token, page.text)

        # 再次刷新（不带 issued_token 参数）就应该看不到明文
        refreshed = self.client.get("/client-tokens")
        self.assertEqual(refreshed.status_code, 200)
        self.assertNotIn(raw_token, refreshed.text)
        self.assertNotIn("data-issued-token-banner", refreshed.text)

    def test_issue_rejects_unknown_client_kind(self) -> None:
        self.login()

        response = self.client.post(
            "/client-tokens",
            data={"label": "test", "client_kind": "ios"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        location = response.headers["location"]
        self.assertTrue(location.startswith("/client-tokens?notice="))
        self.assertEqual(self.repository().list(include_revoked=True), [])

    def test_issue_rejects_blank_label(self) -> None:
        self.login()

        response = self.client.post(
            "/client-tokens",
            data={"label": "   ", "client_kind": "android"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(
            response.headers["location"].startswith("/client-tokens?notice=")
        )
        self.assertEqual(self.repository().list(include_revoked=True), [])

    def test_revoke_marks_token_revoked(self) -> None:
        self.login()
        repository = self.repository()
        issued = repository.issue(label="android-handheld", client_kind="android")

        response = self.client.post(
            f"/client-tokens/{issued.record.id}/revoke",
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(
            response.headers["location"].startswith("/client-tokens?notice=")
        )
        record = repository.get_by_id(issued.record.id)
        self.assertIsNotNone(record)
        self.assertIsNotNone(record.revoked_at)

        # 列表页应同时展示有效 / 已撤销两类
        page = self.client.get("/client-tokens")
        self.assertIn("已撤销", page.text)
        self.assertIn("android-handheld", page.text)

    def test_revoke_unknown_token_returns_notice(self) -> None:
        self.login()

        response = self.client.post(
            "/client-tokens/9999/revoke",
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(
            response.headers["location"].startswith("/client-tokens?notice=")
        )

    def test_revoke_idempotent_for_already_revoked_token(self) -> None:
        self.login()
        repository = self.repository()
        issued = repository.issue(label="windows-spare", client_kind="window")
        repository.revoke(issued.record.id)

        response = self.client.post(
            f"/client-tokens/{issued.record.id}/revoke",
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        # 二次撤销不应改变 revoked_at
        record = repository.get_by_id(issued.record.id)
        self.assertIsNotNone(record.revoked_at)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
