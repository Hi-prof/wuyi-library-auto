"""account-pool-tri-sync · task 9.2 单元测试。

覆盖：

* ``GET /accounts``：批量处理对话框新增「粘贴」与「上传 CSV」两个 Tab，
  以及 ``POST /accounts/bulk-import-to-idle`` 表单 action 渲染。
* ``POST /accounts/bulk-import-to-idle``：
  * 粘贴 + ``application/x-www-form-urlencoded`` 入参解析与条目级落库；
  * 上传 CSV + ``multipart/form-data`` 入参解析（带表头 / 不带表头）；
  * 必填缺失返回 ``validation_error``；
  * 同一批次重复 ``student_id`` 返回 ``duplicate_student_id``；
  * 容量耗尽返回 ``pool_full``；
  * 号池服务未装配（缺少密钥）落到中文错误 toast。

测试不发起任何外部 HTTP，全部使用真实 SQLite + 注入 ``ACCOUNT_POOL_SECRET_KEY``。
"""

from __future__ import annotations

import io
import tempfile
import unittest
from datetime import time as dtime
from pathlib import Path
from unittest.mock import patch
from urllib.parse import unquote

from fastapi.testclient import TestClient

from prevent_auto.account_pool.models import PoolStatus
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


class BulkImportToIdleTestCase(unittest.TestCase):
    """常规路径：号池服务装配齐全，端点可正常落库。"""

    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.secret_key = generate_secret_key()
        self.settings = _build_settings(self.temp_dir, self.secret_key)
        initialize_database(self.settings.database_path, settings=self.settings)
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

    def _get_pool_service(self):
        pool_service = self.client.app.state.services.account_pool_service
        assert pool_service is not None
        return pool_service

    def _write_login_state(self, filename: str) -> Path:
        state_path = self.settings.runtime_dir / filename
        state_path.parent.mkdir(parents=True, exist_ok=True)
        state_path.write_text(
            '{"cookies":[{"name":"SESSION","value":"test"}],"origins":[]}',
            encoding="utf-8",
        )
        return state_path

    def _read_notice(self, response) -> str:
        location = response.headers["location"]
        self.assertIn("/accounts?pool=idle", location)
        # 提取 ``notice=`` 参数值
        if "notice=" not in location:
            return ""
        return unquote(location.split("notice=", 1)[1])

    # ---------- 模板渲染 ----------

    def test_accounts_page_renders_paste_and_csv_tabs(self) -> None:
        self.login()

        response = self.client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        # 两个 Tab + 两个对应的表单 action 都指向新端点
        self.assertIn("批量处理", response.text)
        self.assertIn("data-bulk-import-tab=\"paste\"", response.text)
        self.assertIn("data-bulk-import-tab=\"csv\"", response.text)
        self.assertEqual(
            response.text.count("action=\"/accounts/bulk-import-to-idle\""),
            2,
        )
        self.assertIn("name=\"paste_text\"", response.text)
        self.assertIn("name=\"csv_file\"", response.text)
        self.assertIn("multipart/form-data", response.text)
        self.assertIn(
            "application/x-www-form-urlencoded",
            response.text,
        )

    # ---------- 粘贴模式 ----------

    def test_paste_form_imports_rows_into_idle_pool(self) -> None:
        self.login()

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            data={
                "mode": "paste",
                "paste_text": (
                    "20231121130 pw-1 张三 来自粘贴 自习室A 18\n"
                    "20231121131,pw-2,李四,,,,\n"
                    "  \n"
                    "20231121132 pw-3"
                ),
            },
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        notice = self._read_notice(response)
        self.assertIn("共 3 条", notice)
        self.assertIn("成功 3 条", notice)
        self.assertIn("失败 0 条", notice)
        # 数据库三条记录全部进入 idle 池
        idle_entries = self._get_pool_service().list_by_pool(PoolStatus.IDLE)
        self.assertEqual(
            {entry.student_id for entry in idle_entries},
            {"20231121130", "20231121131", "20231121132"},
        )

    def test_refresh_login_all_uses_decrypted_idle_pool_password(self) -> None:
        self.login()
        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            data={
                "mode": "paste",
                "paste_text": "20231121999 encrypted-password",
            },
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 303)

        seen_passwords: list[str] = []

        def refresh_login(account):
            seen_passwords.append(account.password)
            return self._write_login_state(f"auth-{account.student_id}.json")

        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            side_effect=refresh_login,
        ):
            refresh_response = self.client.post(
                "/accounts/refresh-login-all",
                follow_redirects=False,
            )

        self.assertEqual(refresh_response.status_code, 303)
        self.assertEqual(seen_passwords, ["encrypted-password"])
        saved_state = (
            self.client.app.state.services.account_login_states_repository.get(1)
        )
        self.assertIsNotNone(saved_state)
        assert saved_state is not None
        self.assertIn('"cookies"', saved_state.state_json)

    def test_paste_form_defaults_password_to_student_id_when_missing(
        self,
    ) -> None:
        """粘贴行只有学号时，密码默认填回学号本身（与 bulk-create / 编辑表单一致）。"""

        self.login()

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            data={
                "mode": "paste",
                "paste_text": "20231121130\n20231121131 pw-1",
            },
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        notice = self._read_notice(response)
        self.assertIn("成功 2 条", notice)
        self.assertIn("失败 0 条", notice)
        idle_entries = self._get_pool_service().list_by_pool(PoolStatus.IDLE)
        self.assertEqual(
            {e.student_id for e in idle_entries},
            {"20231121130", "20231121131"},
        )

    def test_paste_form_returns_duplicate_student_id_for_repeats(self) -> None:
        self.login()

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            data={
                "mode": "paste",
                "paste_text": (
                    "20231121130 pw-1\n"
                    "20231121130 pw-2"
                ),
            },
            follow_redirects=False,
        )

        notice = self._read_notice(response)
        self.assertIn("成功 1 条", notice)
        self.assertIn("学号已存在", notice)
        idle_entries = self._get_pool_service().list_by_pool(PoolStatus.IDLE)
        self.assertEqual({e.student_id for e in idle_entries}, {"20231121130"})

    def test_paste_form_returns_pool_full_when_capacity_exceeded(self) -> None:
        self.login()
        # 把 pool_capacity 临时调小到 1，第二条就会撞 pool_full
        pool_service = self._get_pool_service()
        pool_service.pool_capacity = 1

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            data={
                "mode": "paste",
                "paste_text": "20231121130 pw-1\n20231121131 pw-2",
            },
            follow_redirects=False,
        )

        notice = self._read_notice(response)
        self.assertIn("成功 1 条", notice)
        self.assertIn("号池已满", notice)
        idle_entries = pool_service.list_by_pool(PoolStatus.IDLE)
        self.assertEqual(len(idle_entries), 1)

    def test_paste_form_handles_empty_text_with_failure_notice(self) -> None:
        self.login()

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            data={"mode": "paste", "paste_text": "   \n\n"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        notice = self._read_notice(response)
        self.assertIn("未解析到任何账号行", notice)

    # ---------- CSV 上传模式 ----------

    def test_csv_upload_with_header_imports_rows(self) -> None:
        self.login()

        csv_text = (
            "student_id,password,display_name,note,default_room_name,default_seat_number\n"
            "20231121200,pw-200,张三,csv-导入,自习室A,12\n"
            "20231121201,pw-201,李四,,自习室B,7\n"
        ).encode("utf-8")

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            files={"csv_file": ("accounts.csv", io.BytesIO(csv_text), "text/csv")},
            data={"mode": "csv"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        notice = self._read_notice(response)
        self.assertIn("成功 2 条", notice)
        idle_entries = self._get_pool_service().list_by_pool(PoolStatus.IDLE)
        self.assertEqual(
            {entry.student_id for entry in idle_entries},
            {"20231121200", "20231121201"},
        )

    def test_csv_upload_without_header_imports_rows_by_position(self) -> None:
        self.login()

        csv_text = (
            "20231121300,pw-300,张三,note-1,自习室A,18\n"
            "20231121301,pw-301\n"
        ).encode("utf-8")

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            files={"csv_file": ("noheader.csv", io.BytesIO(csv_text), "text/csv")},
            data={"mode": "csv"},
            follow_redirects=False,
        )

        notice = self._read_notice(response)
        self.assertIn("成功 2 条", notice)
        idle_entries = self._get_pool_service().list_by_pool(PoolStatus.IDLE)
        self.assertEqual(
            {entry.student_id for entry in idle_entries},
            {"20231121300", "20231121301"},
        )

    def test_csv_upload_rejects_empty_file(self) -> None:
        self.login()

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            files={"csv_file": ("empty.csv", io.BytesIO(b""), "text/csv")},
            data={"mode": "csv"},
            follow_redirects=False,
        )

        notice = self._read_notice(response)
        self.assertIn("批量导入失败：CSV 文件为空", notice)

    def test_csv_upload_with_utf8_bom_decodes_correctly(self) -> None:
        self.login()

        csv_text = (
            "\ufeffstudent_id,password,display_name\n"
            "20231121400,pw-400,张三\n"
        ).encode("utf-8")

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            files={"csv_file": ("bom.csv", io.BytesIO(csv_text), "text/csv")},
            data={"mode": "csv"},
            follow_redirects=False,
        )

        notice = self._read_notice(response)
        self.assertIn("成功 1 条", notice)


class BulkImportToIdleWithoutPoolServiceTestCase(unittest.TestCase):
    """``ACCOUNT_POOL_SECRET_KEY`` 缺失：服务未装配，应给出中文错误 toast。"""

    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        # 不传 ``account_pool_secret_key``，让 services.account_pool_service 为 None
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

    def test_endpoint_redirects_with_service_missing_notice(self) -> None:
        self.login()

        response = self.client.post(
            "/accounts/bulk-import-to-idle",
            data={"mode": "paste", "paste_text": "20231121130 pw-1"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        location = response.headers["location"]
        self.assertIn("/accounts?pool=idle", location)
        self.assertIn("notice=", location)
        notice = unquote(location.split("notice=", 1)[1])
        self.assertIn("号池服务未装配", notice)

    def test_accounts_page_does_not_render_bulk_import_section(self) -> None:
        self.login()

        response = self.client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        # 缺密钥时该 section 不渲染（与三池 Tab 视图一致）
        self.assertNotIn("data-bulk-import-tab=\"paste\"", response.text)
        self.assertNotIn(
            "action=\"/accounts/bulk-import-to-idle\"",
            response.text,
        )


if __name__ == "__main__":
    unittest.main()
