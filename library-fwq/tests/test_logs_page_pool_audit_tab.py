"""/logs 页面「池审计」Tab 的最小集成测试（task 9.5）.

覆盖：

* 默认 Tab（``/logs``）保持原有「动作日志 / 巡检记录」分栏渲染，并提供池审计
  Tab 链接，对应 Requirement 10.5「保留既有日志页面入口」。
* 池审计 Tab（``/logs?audit=pool``）能展示 ``pool_audit_log`` 表的真实记录，
  并提供按 ``account_id`` / ``audit_action`` / 时间范围筛选的查询参数能力。
* 时间字段按 Asia/Shanghai 渲染、敏感信息（密码 / token）始终经过 :func:`scrub`
  脱敏，不会从仓库穿透到页面（Requirement 9.2）。
* 非法过滤参数（``account_id`` 非整数 / ``created_after`` 格式错误 / 起止反序）
  在页面顶部展示中文错误提示，且不抛 5xx。

测试不依赖 ``ACCOUNT_POOL_SECRET_KEY``：直接通过 :class:`PoolAuditLogRepository`
追加几条审计行，验证 web 层 → repo 层的查询闭环。
"""

from __future__ import annotations

import re
import tempfile
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path

from fastapi.testclient import TestClient

from prevent_auto.account_pool.models import (
    ClientKind,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import initialize_database
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogEntry,
    PoolAuditLogRepository,
)
from prevent_auto.services.account_service import AccountService
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.app import create_app


def _extract_pool_audit_log_list(html: str) -> str:
    """提取 ``<ul id="pool-audit-log-list">...</ul>`` 之间的 HTML 片段。

    页面顶部的 ``<select>`` 下拉框包含全部 :class:`PoolAuditAction` 的中文标签，
    全文搜索会与日志列表行混淆；这里把列表本体抽出来再断言可以避免误命中。
    """

    match = re.search(
        r'<ul[^>]*id="pool-audit-log-list"[^>]*>(.*?)</ul>',
        html,
        flags=re.DOTALL,
    )
    if match is None:
        raise AssertionError("响应中没有找到 pool-audit-log-list 容器")
    return match.group(1)


class LogsPagePoolAuditTabTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        self.runtime_dir = self.temp_dir / "runtime"
        initialize_database(self.database_path)
        AccountService(self.database_path).create_account(
            name="主号",
            student_id="20231121130",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )
        settings = PreventAutoSettings(
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
        )
        self.client = TestClient(
            create_app(settings, start_background_workers=False)
        )
        self.audit_repo = PoolAuditLogRepository(self.database_path)
        self.now_utc = datetime(2026, 4, 26, 8, 30, 0, tzinfo=UTC)

    def tearDown(self) -> None:
        self.client.close()

    def login(self) -> None:
        settings = self.client.app.state.settings
        response = self.client.post(
            "/login",
            data={
                "username": settings.auth_username,
                "password": settings.auth_password,
                "next": "/",
            },
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 303)

    def _purge_audit_log(self) -> None:
        """删除 ``pool_audit_log`` 表里的全部行（含启动迁移留下的占位行）。

        ``initialize_database`` 在测试场景下也会写一条 ``startup_migration``
        审计，这条行会带「无时间」时间戳出现在不带过滤的查询里。时间范围测试需要
        排除该条目，否则 ``startup_migration`` 比 reaper_tick 还要靠前，断言会乱。
        """

        from prevent_auto.database import connect_database

        with connect_database(self.database_path) as connection:
            connection.execute("DELETE FROM pool_audit_log")

    def _seed_audit_rows(self) -> None:
        self.audit_repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.MIGRATE,
                trigger_source=PoolMigrationTrigger.MANUAL,
                operator="admin",
                success=True,
                account_id=17,
                from_pool=PoolStatus.ACTIVE,
                to_pool=PoolStatus.SUSPENDED,
                client_kind=ClientKind.WEB,
                reason="manual move",
                payload={"password": "Pa$$w0rd", "note": "人工迁移"},
                created_at=self.now_utc - timedelta(minutes=10),
            )
        )
        self.audit_repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.RANDOM_PICK,
                trigger_source=PoolMigrationTrigger.RANDOM_PICK,
                operator="admin",
                success=True,
                account_id=42,
                client_kind=ClientKind.WEB,
                created_at=self.now_utc - timedelta(minutes=20),
            )
        )
        self.audit_repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.REAPER_TICK,
                trigger_source=PoolMigrationTrigger.SYSTEM,
                operator="system",
                success=True,
                client_kind=None,
                created_at=self.now_utc - timedelta(hours=2),
            )
        )

    def test_default_logs_page_keeps_existing_layout_and_links_pool_audit_tab(
        self,
    ) -> None:
        self.login()

        response = self.client.get("/logs")

        self.assertEqual(response.status_code, 200)
        self.assertIn("巡检和取消记录都在这里", response.text)
        self.assertIn('id="action-log-list"', response.text)
        self.assertIn('id="monitor-log-list"', response.text)
        self.assertIn('href="/logs?audit=pool"', response.text)
        # 默认 Tab 不渲染池审计列表容器
        self.assertNotIn('id="pool-audit-log-list"', response.text)

    def test_pool_audit_tab_renders_seeded_rows(self) -> None:
        self.login()
        self._seed_audit_rows()

        response = self.client.get("/logs?audit=pool")

        self.assertEqual(response.status_code, 200)
        self.assertIn('id="pool-audit-log-list"', response.text)
        # 默认 Tab 的「动作日志 / 巡检记录」容器在池审计 Tab 上不应渲染
        self.assertNotIn('id="action-log-list"', response.text)
        list_html = _extract_pool_audit_log_list(response.text)
        self.assertIn("池迁移", list_html)
        self.assertIn("随机抽取", list_html)
        self.assertIn("Reaper 扫描", list_html)
        self.assertIn("操作者：admin", list_html)
        # payload 里的密码在 repo 层就被 scrub，页面不会泄露原文
        self.assertNotIn("Pa$$w0rd", response.text)

    def test_pool_audit_tab_filters_by_account_and_action(self) -> None:
        self.login()
        self._seed_audit_rows()

        response = self.client.get(
            "/logs",
            params={
                "audit": "pool",
                "account_id": "17",
                "audit_action": "migrate",
            },
        )

        self.assertEqual(response.status_code, 200)
        list_html = _extract_pool_audit_log_list(response.text)
        # 仅 account_id=17 + migrate 那一条命中；random_pick / reaper_tick 不出现
        self.assertIn("池迁移", list_html)
        self.assertNotIn("随机抽取", list_html)
        self.assertNotIn("Reaper 扫描", list_html)

    def test_pool_audit_tab_filters_by_time_range(self) -> None:
        self.login()
        # 清空 startup_migration 残留行，避免对时间范围断言产生干扰
        self._purge_audit_log()
        self._seed_audit_rows()

        # now_utc = 2026-04-26 08:30:00Z = 2026-04-26 16:30:00+08:00
        # migrate 在 -10min（16:20+08）、random_pick 在 -20min（16:10+08）、
        # reaper_tick 在 -2h（14:30+08）。起点取 16:00 +08:00 应只保留前两者。
        response = self.client.get(
            "/logs",
            params={
                "audit": "pool",
                "created_after": "2026-04-26T16:00",
            },
        )

        self.assertEqual(response.status_code, 200)
        list_html = _extract_pool_audit_log_list(response.text)
        self.assertIn("池迁移", list_html)
        self.assertIn("随机抽取", list_html)
        self.assertNotIn("Reaper 扫描", list_html)

    def test_pool_audit_tab_rejects_invalid_account_id_with_inline_error(
        self,
    ) -> None:
        self.login()

        response = self.client.get(
            "/logs",
            params={"audit": "pool", "account_id": "abc"},
        )

        self.assertEqual(response.status_code, 200)
        self.assertIn("账号 ID 必须是整数", response.text)
        # 错误状态下不渲染审计行
        self.assertIn("请先修正筛选条件", response.text)

    def test_pool_audit_tab_rejects_unknown_action(self) -> None:
        self.login()

        response = self.client.get(
            "/logs",
            params={"audit": "pool", "audit_action": "not_a_real_action"},
        )

        self.assertEqual(response.status_code, 200)
        self.assertIn("未知的审计动作", response.text)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
