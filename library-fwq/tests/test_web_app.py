from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from prevent_auto.database import initialize_database
from prevent_auto.database import connect_database
from prevent_auto.repositories.action_logs import ActionLogsRepository
from prevent_auto.repositories.app_settings import AppSettingsRepository
from prevent_auto.services.account_service import AccountService
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.app import create_app
from prevent_auto.web.runtime import AUTO_RESERVATION_DETAILED_LOG_KEY
from prevent_auto.web.runtime import build_dashboard_health


def _health_snapshot(
    *,
    account_id: int = 1,
    student_id: str = "20231121130",
    name: str = "主号",
    current_status: str = "状态检测：正常",
    last_check_label: str = "2026年07月05日08时10分00秒",
    checked_in: bool = False,
    not_reserved: bool = False,
) -> dict[str, object]:
    return {
        "id": account_id,
        "name": name,
        "studentId": student_id,
        "currentStatus": current_status,
        "lastCheckLabel": last_check_label,
        "isCheckedInToday": checked_in,
        "isNotReservedToday": not_reserved,
    }


class WebAppTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        self.runtime_dir = self.temp_dir / "runtime"
        self.static_dir = self.temp_dir / "static"
        self.static_dir.mkdir(parents=True, exist_ok=True)
        initialize_database(self.database_path)
        self.account_service = AccountService(self.database_path)
        self.account_service.create_account(
            name="主号",
            student_id="20231121130",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE accounts
                SET pool_status = 'active',
                    pool_updated_at = '2026-04-03T00:00:00Z',
                    pool_previous = ''
                WHERE id = 1
                """
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
        self.client = TestClient(create_app(settings, start_background_workers=False))

    def write_login_state(self, filename: str) -> Path:
        state_path = self.runtime_dir / filename
        state_path.parent.mkdir(parents=True, exist_ok=True)
        state_path.write_text(
            '{"cookies":[{"name":"SESSION","value":"test"}],"origins":[]}',
            encoding="utf-8",
        )
        return state_path

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
        self.assertEqual(response.headers["location"], "/")

    def test_login_page_renders(self) -> None:
        response = self.client.get("/login")

        self.assertEqual(response.status_code, 200)
        self.assertIn("登录后继续", response.text)
        self.assertIn("访问验证", response.text)
        self.assertIn("mobile.css", response.text)

    def test_dashboard_requires_login(self) -> None:
        response = self.client.get("/", follow_redirects=False)

        self.assertEqual(response.status_code, 303)
        self.assertEqual(response.headers["location"], "/login?next=%2F")

    def test_login_rejects_invalid_credentials(self) -> None:
        response = self.client.post(
            "/login",
            data={
                "username": "wrong",
                "password": "wrong",
                "next": "/",
            },
        )

        self.assertEqual(response.status_code, 401)
        self.assertIn("账号或密码错误", response.text)

    def test_dashboard_api_requires_login(self) -> None:
        response = self.client.get("/api/dashboard")

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "未登录")

    def test_dashboard_page_renders_summary(self) -> None:
        self.login()
        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("防超时签到自动补约", response.text)
        self.assertIn("仪表盘", response.text)
        self.assertIn("账号总数", response.text)
        self.assertIn("今日已签到", response.text)
        self.assertIn("今日未预约", response.text)
        self.assertNotIn("已启用账号", response.text)
        self.assertNotIn("检查方式", response.text)
        self.assertNotIn("手动检查", response.text)
        self.assertNotIn("账号预约概览", response.text)
        self.assertNotIn("今日取消次数", response.text)
        self.assertNotIn("需要关注的账号", response.text)
        self.assertIn("自习室预约分布", response.text)
        self.assertIn("mobile.css", response.text)

    def test_dashboard_health_reports_normal_state(self) -> None:
        health = build_dashboard_health(
            {
                "accountCount": 1,
                "checkedInTodayCount": 1,
                "notReservedTodayCount": 0,
                "accountSnapshots": [
                    _health_snapshot(checked_in=True),
                ],
            }
        )

        self.assertEqual(health["overallState"], "healthy")
        self.assertEqual(health["overallLabel"], "运行正常")
        self.assertEqual(health["counters"]["attentionCount"], 0)
        self.assertEqual(health["attentionItems"], [])

    def test_dashboard_health_prioritizes_login_issue(self) -> None:
        health = build_dashboard_health(
            {
                "accountCount": 1,
                "checkedInTodayCount": 0,
                "notReservedTodayCount": 0,
                "accountSnapshots": [
                    _health_snapshot(
                        current_status="刷新登录失败：账号密码错误",
                        not_reserved=True,
                    ),
                ],
            }
        )

        self.assertEqual(health["overallState"], "attention")
        self.assertEqual(health["counters"]["loginIssueCount"], 1)
        self.assertEqual(health["attentionItems"][0]["issueType"], "login")
        self.assertEqual(health["attentionItems"][0]["issueLabel"], "登录态异常")
        self.assertIn("刷新登录态", health["attentionItems"][0]["recommendedActions"])

    def test_dashboard_health_reports_not_reserved_account(self) -> None:
        health = build_dashboard_health(
            {
                "accountCount": 1,
                "checkedInTodayCount": 0,
                "notReservedTodayCount": 1,
                "accountSnapshots": [
                    _health_snapshot(
                        current_status="今日无预约",
                        not_reserved=True,
                    ),
                ],
            }
        )

        self.assertEqual(health["overallState"], "attention")
        self.assertEqual(health["attentionItems"][0]["issueType"], "not_reserved")
        self.assertEqual(health["attentionItems"][0]["issueLabel"], "未预约")
        self.assertIn("立即检测", health["attentionItems"][0]["recommendedActions"])

    def test_dashboard_health_reports_unchecked_account(self) -> None:
        health = build_dashboard_health(
            {
                "accountCount": 1,
                "checkedInTodayCount": 0,
                "notReservedTodayCount": 0,
                "accountSnapshots": [
                    _health_snapshot(
                        current_status="尚未检测",
                        last_check_label="尚未检测",
                    ),
                ],
            }
        )

        self.assertEqual(health["overallState"], "unchecked")
        self.assertEqual(health["counters"]["uncheckedCount"], 1)
        self.assertEqual(health["attentionItems"][0]["issueType"], "unchecked")
        self.assertEqual(health["attentionItems"][0]["issueLabel"], "尚未检测")

    def test_dashboard_summary_counts_checked_in_and_not_reserved_accounts(self) -> None:
        self.login()
        account_service = self.client.app.state.services.account_service
        checked_in_account = account_service.create_account(
            name="已签到号",
            student_id="20231121131",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )
        account_service.repository.update_detected_booking(
            checked_in_account.id,
            room_name="自习室",
            seat_number="2",
            booking_start_at="1775160000",
            booking_status="1",
        )
        account_service.update_status(
            1,
            last_check_at="2026-05-03T08:00:00+08:00",
            last_status="今日无预约",
        )

        page_response = self.client.get("/")
        api_response = self.client.get("/api/dashboard")

        self.assertEqual(page_response.status_code, 200)
        self.assertIn("今日已签到", page_response.text)
        self.assertIn("今日未预约", page_response.text)
        self.assertIn("<strong>1</strong>", page_response.text)
        self.assertEqual(api_response.status_code, 200)
        self.assertEqual(api_response.json()["checkedInTodayCount"], 1)
        self.assertEqual(api_response.json()["notReservedTodayCount"], 1)

    def test_protected_html_pages_include_mobile_navigation_assets(self) -> None:
        self.login()

        for path in (
            "/",
            "/accounts",
            "/accounts/1/edit",
            "/logs",
        ):
            with self.subTest(path=path):
                response = self.client.get(path)

                self.assertEqual(response.status_code, 200)
                self.assertIn(
                    'name="viewport" content="width=device-width, initial-scale=1"',
                    response.text,
                )
                self.assertIn("mobile.css", response.text)
                self.assertIn("data-nav-toggle", response.text)

    def test_dashboard_no_longer_renders_account_overview_card(self) -> None:
        self.login()
        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertNotIn('class="account-overview-card"', response.text)
        self.assertNotIn("account-overview-toggle-button", response.text)
        self.assertNotIn("account-overview-refresh-button", response.text)
        self.assertNotIn("account-overview-grid", response.text)

    def test_dashboard_expanded_card_only_shows_recent_booking(self) -> None:
        self.login()
        self.client.app.state.services.account_service.update_status(
            1,
            last_check_at="2026-05-03T08:00:00+08:00",
            last_status="今日无预约",
        )

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertNotIn('class="account-recent-booking"', response.text)
        self.assertNotIn("<dt>当前状态</dt>", response.text)
        self.assertNotIn("<dt>取消记录</dt>", response.text)
        self.assertNotIn("<dt>目标座位</dt>", response.text)
        self.assertNotIn("查看账号详情", response.text)

    def test_dashboard_no_longer_renders_per_account_check_now_button(
        self,
    ) -> None:
        self.login()

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertNotIn('action="/accounts/1/check-now"', response.text)
        self.assertNotIn('class="account-overview-refresh-button"', response.text)

    def test_dashboard_page_links_to_account_management(self) -> None:
        self.login()

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertNotIn('action="/accounts/check-first-enabled"', response.text)
        self.assertNotIn("刷新预约位置", response.text)
        self.assertIn('href="/accounts"', response.text)

    def test_accounts_page_renders_check_first_enabled_button(self) -> None:
        self.login()

        response = self.client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        self.assertIn('action="/accounts/check-first-enabled"', response.text)
        self.assertIn("刷新预约位置", response.text)

    def test_accounts_page_renders_refresh_all_login_states_button(self) -> None:
        self.login()

        response = self.client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        self.assertIn('action="/accounts/refresh-login-all"', response.text)
        self.assertIn("一键刷新所有登录态", response.text)

    def test_accounts_page_renders_daily_refresh_label(self) -> None:
        self.login()

        response = self.client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        self.assertIn("自动刷新账号状态", response.text)

    def test_dashboard_page_uses_booking_status_as_booking_summary(self) -> None:
        self.login()
        self.client.app.state.services.account_service.update_status(
            1,
            last_check_at="2026-04-03T08:00:00+08:00",
            last_status="今日预约：自习室圆形一楼 19 号座位 12:00-16:00 · 待签到",
        )

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("自习室圆形一楼", response.text)
        self.assertIn("19 号", response.text)
        self.assertIn("12:00-16:00", response.text)
        self.assertIn("待签到", response.text)
        self.assertNotIn("暂无当天预约记录", response.text)

    def test_dashboard_page_shows_detected_booking_when_preference_missing(
        self,
    ) -> None:
        self.login()
        self.client.app.state.services.account_service.update_account(
            1,
            name="主号",
            student_id="20231121130",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )
        self.client.app.state.services.account_service.update_status(
            1,
            last_check_at="2026-04-03T08:00:00+08:00",
            last_status="今日预约：自习室圆形二楼 166 号座位 · 08:00-22:00 · 待签到",
        )

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("自习室圆形二楼", response.text)
        self.assertIn("166 号", response.text)
        self.assertIn("08:00-22:00", response.text)
        self.assertIn("待签到", response.text)
        self.assertNotIn("未识别到预约座位", response.text)

    def test_accounts_page_lists_existing_account(self) -> None:
        self.login()
        response = self.client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        self.assertIn("号池管理", response.text)
        self.assertIn("20231121130", response.text)
        self.assertNotIn("<th>学号</th>", response.text)
        self.assertIn('action="/accounts/1/delete"', response.text)
        self.assertIn("删除", response.text)
        self.assertIn("account-pool-table", response.text)
        self.assertIn("account-pool-tabs-panel", response.text)
        self.assertIn('action="/accounts/bulk-create"', response.text)
        self.assertIn('action="/accounts/bulk-delete"', response.text)
        self.assertIn('action="/accounts/refresh-login-all"', response.text)
        self.assertIn("批量导入到活跃号池", response.text)
        self.assertIn("批量删除", response.text)
        self.assertIn('data-account-dialog-open="bulk-delete-dialog"', response.text)
        self.assertIn('id="bulk-delete-dialog"', response.text)
        self.assertIn('data-account-dialog-open="new-account-dialog"', response.text)
        self.assertIn('id="new-account-dialog"', response.text)
        self.assertIn('data-account-dialog-open="account-dialog-1"', response.text)
        self.assertIn('class="account-dialog"', response.text)
        self.assertIn('id="account-dialog-1"', response.text)
        self.assertIn('id="account-edit-dialog-1"', response.text)
        self.assertIn('data-account-dialog-open="account-edit-dialog-1"', response.text)
        self.assertIn('id="account-log-dialog-1"', response.text)
        self.assertIn('id="runtime-log-dialog"', response.text)
        self.assertIn("立即检测", response.text)
        self.assertIn("取消当前预约", response.text)
        self.assertIn("刷新登录态", response.text)
        self.assertIn('aria-label="刷新账号 20231121130 的登录态"', response.text)
        self.assertIn("查看日志", response.text)
        self.assertIn('action="/accounts/1"', response.text)
        self.assertIn("更多", response.text)
        self.assertIn("data-confirm-message", response.text)
        self.assertNotIn('href="/accounts/1/edit"', response.text)
        self.assertNotIn("查看账号详情", response.text)
        self.assertNotIn("<details", response.text)

    def test_dashboard_page_groups_current_bookings_by_room(self) -> None:
        self.login()
        account_service = self.client.app.state.services.account_service
        second_account = account_service.create_account(
            name="二号",
            student_id="20231121131",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )
        finished_account = account_service.create_account(
            name="已结束",
            student_id="20231121132",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )
        account_service.repository.update_detected_booking(
            1,
            room_name="自习室圆形二楼",
            seat_number="184",
            booking_start_at="1775160000",
            booking_status="1",
        )
        account_service.repository.update_detected_booking(
            second_account.id,
            room_name="自习室圆形一楼",
            seat_number="20",
            booking_start_at="1775163600",
            booking_status="0",
        )
        account_service.repository.update_detected_booking(
            finished_account.id,
            room_name="综合阅览室",
            seat_number="5",
            booking_start_at="1775167200",
            booking_status="3",
        )

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("仪表盘", response.text)
        self.assertIn("自习室预约分布", response.text)
        self.assertIn("自习室圆形二楼", response.text)
        self.assertIn("184 号", response.text)
        self.assertIn("签到成功，使用中", response.text)
        self.assertIn("自习室圆形一楼", response.text)
        self.assertIn("20 号", response.text)
        self.assertIn("待签到", response.text)
        self.assertNotIn("综合阅览室", response.text)
        self.assertIn('href="/accounts/1"', response.text)
        self.assertNotIn('action="/accounts/check-first-enabled"', response.text)

    def test_dashboard_page_parses_booking_summary_when_detected_fields_missing(
        self,
    ) -> None:
        self.login()
        self.client.app.state.services.account_service.update_status(
            1,
            last_check_at="2026-04-03T08:00:00+08:00",
            last_status="今日预约：自习室圆形二楼 166 号座位 · 08:00-22:00 · 待签到",
        )

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("自习室圆形二楼", response.text)
        self.assertIn("166 号", response.text)
        self.assertNotIn("当前没有可展示的预约位置", response.text)

    def test_seat_display_path_redirects_to_dashboard(self) -> None:
        """仪表盘和座位展示已合并到 ``/``，旧链接保持 303 兼容重定向。"""

        self.login()
        response = self.client.get("/seat-display", follow_redirects=False)

        self.assertEqual(response.status_code, 303)
        self.assertEqual(response.headers["location"], "/")

    def test_seat_display_check_first_enabled_legacy_endpoint_redirects_to_dashboard(
        self,
    ) -> None:
        """老的 ``POST /seat-display/check-first-enabled`` 仍执行批量检测，回到 ``/``。"""

        self.login()
        with patch.object(
            self.client.app.state.services.monitor_loop,
            "run_account_once",
        ) as run_account_once:
            response = self.client.post(
                "/seat-display/check-first-enabled",
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/?notice="))
        run_account_once.assert_called_once_with(1)

    def test_accounts_page_does_not_render_blacklist_status_after_refresh(self) -> None:
        self.login()
        self.client.app.state.services.account_service.update_account_status(
            1,
            account_status="blacklisted",
        )
        self.client.app.state.services.account_service.update_status(
            1,
            last_check_at="2026-04-03T08:00:00+08:00",
            last_status="登录态已刷新",
        )

        reloaded_client = TestClient(
            create_app(self.client.app.state.settings, start_background_workers=False)
        )
        settings = reloaded_client.app.state.settings
        login_response = reloaded_client.post(
            "/login",
            data={
                "username": settings.auth_username,
                "password": settings.auth_password,
                "next": "/accounts",
            },
            follow_redirects=False,
        )
        self.assertEqual(login_response.status_code, 303)

        response = reloaded_client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        self.assertNotIn("账号已被拉黑", response.text)
        self.assertNotIn("暂时无法预约，后面可能会恢复", response.text)

    def test_delete_account_route_removes_account(self) -> None:
        self.login()

        response = self.client.post("/accounts/1/delete", follow_redirects=False)

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        self.assertEqual(self.client.app.state.services.account_service.list_accounts(), [])

    def test_logs_page_renders_recent_action_area(self) -> None:
        self.login()
        response = self.client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        self.assertIn("运行日志", response.text)
        self.assertIn("巡检和取消记录", response.text)
        self.assertNotIn("重约任务", response.text)
        self.assertIn('id="runtime-log-dialog"', response.text)
        self.assertIn("复制动作日志", response.text)
        self.assertIn('data-copy-source="action-log-list"', response.text)

    def test_refresh_login_failure_redirects_back_with_status(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            side_effect=ValueError(
                "当前座位系统旧接口已失效（/User/Index/login 返回 HTTP 404），请更新 login_url / seat_url 到学校当前仍在使用的入口"
            ),
        ):
            response = self.client.post(
                "/accounts/1/refresh-login", follow_redirects=False
            )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        account = self.client.app.state.services.account_service.get_account(1)
        self.assertIn("刷新登录失败", account.last_status)
        self.assertIn("HTTP 404", account.last_status)
        self.assertIsNotNone(account.last_check_at)

    def test_refresh_login_route_persists_state(self) -> None:
        self.login()

        def refresh_login(account):
            return self.write_login_state(f"auth-{account.student_id}.json")

        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            side_effect=refresh_login,
        ) as refresh_login_mock:
            response = self.client.post(
                "/accounts/1/refresh-login", follow_redirects=False
            )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        refresh_login_mock.assert_called_once()
        self.assertEqual(refresh_login_mock.call_args.args[0].id, 1)
        with sqlite3.connect(self.database_path) as connection:
            row = connection.execute(
                """
                SELECT account_id, state_file, state_json
                FROM account_login_states
                WHERE account_id = 1
                """
            ).fetchone()
        self.assertIsNotNone(row)
        assert row is not None
        self.assertEqual(row[0], 1)
        self.assertEqual(row[1], "runtime/auth-20231121130.json")
        self.assertIn('"SESSION"', row[2])
        account = self.client.app.state.services.account_service.get_account(1)
        self.assertEqual(account.last_status, "登录态已刷新")
        self.assertIsNotNone(account.last_check_at)

    def test_account_detail_page_renders_manual_test_button(self) -> None:
        self.login()
        self.client.app.state.services.account_service.update_status(
            1,
            last_check_at="2026-04-03T08:00:00+08:00",
            last_status="今日预约：自习室圆形二楼 165 号座位 · 08:00-22:00 · 待签到",
        )
        response = self.client.get("/accounts/1")

        self.assertEqual(response.status_code, 200)
        self.assertIn("立即检测", response.text)
        self.assertIn("取消当前预约", response.text)
        self.assertIn("确认取消当前可取消的预约", response.text)
        self.assertNotIn("测试取消并补约", response.text)
        self.assertNotIn("手动预约下个时段", response.text)
        self.assertIn("mobile-action-grid", response.text)
        self.assertIn("account-detail-summary-grid", response.text)
        self.assertIn("2026年04月03日08时00分00秒", response.text)

    def test_cancel_current_route_runs_single_account_cancel(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.monitor_loop,
            "cancel_current_booking_once",
            return_value="取消成功",
        ) as cancel_current_booking_once:
            response = self.client.post("/accounts/1/cancel-current", follow_redirects=False)

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        cancel_current_booking_once.assert_called_once_with(1)

    def test_check_now_route_runs_single_account_monitor_cycle(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.monitor_loop,
            "run_account_once",
        ) as run_account_once:
            response = self.client.post("/accounts/1/check-now", follow_redirects=False)

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        run_account_once.assert_called_once_with(1)

    def test_check_now_route_can_redirect_back_to_dashboard(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.monitor_loop,
            "run_account_once",
        ) as run_account_once:
            response = self.client.post(
                "/accounts/1/check-now",
                data={"return_to": "/"},
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/?notice="))
        run_account_once.assert_called_once_with(1)

    def test_check_first_enabled_route_runs_all_enabled_accounts_in_batches(self) -> None:
        self.login()
        account_service = self.client.app.state.services.account_service
        for index in range(2, 8):
            account_service.create_account(
                name=f"{index}号",
                student_id=f"2023112113{index}",
                password="secret",
                login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
                seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
                enabled=index != 3,
            )

        with (
            patch.object(
                self.client.app.state.services.monitor_loop,
                "run_account_once",
            ) as run_account_once,
            patch("prevent_auto.web.app.time.sleep") as sleep,
        ):
            response = self.client.post(
                "/accounts/check-first-enabled",
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/?notice="))
        self.assertEqual(
            [call.args[0] for call in run_account_once.call_args_list],
            [2, 4, 5, 6, 7, 1],
        )
        sleep.assert_called_once_with(3)

    def test_new_account_path_redirects_to_main_page(self) -> None:
        """`/accounts/new` 已收敛到主页面弹窗，旧入口保持兼容性重定向。"""

        self.login()
        response = self.client.get("/accounts/new", follow_redirects=False)

        self.assertEqual(response.status_code, 303)
        self.assertEqual(response.headers["location"], "/accounts")

    def test_create_account_form_accepts_student_id_only(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            return_value=self.write_login_state("auth-20231121199.json"),
        ) as refresh_login:
            response = self.client.post(
                "/accounts",
                data={
                    "student_id": "20231121199",
                    "enabled": "on",
                },
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        created_account = next(
            account
            for account in self.client.app.state.services.account_service.list_accounts()
            if account.student_id == "20231121199"
        )
        refresh_login.assert_called_once()
        self.assertEqual(created_account.name, "20231121199")
        self.assertEqual(created_account.password, "20231121199")
        self.assertEqual(
            created_account.login_url,
            "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
        )
        self.assertEqual(
            created_account.seat_url,
            "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
        )
        self.assertFalse(created_account.rebook_enabled)
        self.assertEqual(created_account.rebook_trigger_minutes, 5)
        self.assertTrue(created_account.enabled)
        self.assertEqual(created_account.last_status, "登录态已刷新")

    def test_bulk_create_accounts_adds_multiple_accounts(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            return_value=self.write_login_state("auth-bulk.json"),
        ) as refresh_login:
            response = self.client.post(
                "/accounts/bulk-create",
                data={
                    "bulk_accounts": (
                        "20231121201\n"
                        "20231121202 custom-pass\n"
                        "20231121130\n"
                        "bad line with too many columns"
                    ),
                    "enabled": "on",
                },
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        accounts_by_student_id = {
            account.student_id: account
            for account in self.client.app.state.services.account_service.list_accounts()
        }
        self.assertEqual(accounts_by_student_id["20231121201"].password, "20231121201")
        self.assertEqual(accounts_by_student_id["20231121202"].password, "custom-pass")
        self.assertEqual(refresh_login.call_count, 2)

    def test_bulk_delete_accounts_removes_selected_accounts(self) -> None:
        self.login()
        extra_account = self.client.app.state.services.account_service.create_account(
            name="二号",
            student_id="20231121209",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )

        response = self.client.post(
            "/accounts/bulk-delete",
            data={"account_ids": ["1", str(extra_account.id)]},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        self.assertEqual(
            self.client.app.state.services.account_service.list_accounts(),
            [],
        )

    def test_create_account_form_uses_default_rebook_settings(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            return_value=self.write_login_state("auth-20231121222.json"),
        ):
            response = self.client.post(
                "/accounts",
                data={
                    "student_id": "20231121222",
                    "password": "secret-3",
                    "enabled": "on",
                },
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        created_account = next(
            account
            for account in self.client.app.state.services.account_service.list_accounts()
            if account.student_id == "20231121222"
        )
        self.assertFalse(created_account.rebook_enabled)

    def test_create_account_form_can_enable_rebook_settings(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            return_value=self.write_login_state("auth-20231121333.json"),
        ):
            response = self.client.post(
                "/accounts",
                data={
                    "student_id": "20231121333",
                    "password": "secret-4",
                    "rebook_enabled": "on",
                    "rebook_trigger_minutes": "7",
                    "enabled": "on",
                },
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        created_account = next(
            account
            for account in self.client.app.state.services.account_service.list_accounts()
            if account.student_id == "20231121333"
        )
        self.assertTrue(created_account.rebook_enabled)
        self.assertEqual(created_account.rebook_trigger_minutes, 7)

    def test_update_account_form_auto_refreshes_login_state(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            return_value=self.write_login_state("auth-20231121130.json"),
        ) as refresh_login:
            response = self.client.post(
                "/accounts/1",
                data={
                    "student_id": "20231121130",
                    "password": "secret-updated",
                    "enabled": "on",
                },
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        refresh_login.assert_called_once()
        updated_account = self.client.app.state.services.account_service.get_account(1)
        self.assertEqual(updated_account.password, "secret-updated")
        self.assertEqual(updated_account.last_status, "登录态已刷新")

    def test_edit_account_form_uses_student_id_as_blank_password_default(self) -> None:
        self.login()
        response = self.client.get("/accounts/1/edit")

        self.assertEqual(response.status_code, 200)
        self.assertIn("留空时默认和学号一致", response.text)
        self.assertNotIn('value="secret"', response.text)

    def test_update_account_form_uses_student_id_when_password_blank(self) -> None:
        self.login()
        with patch.object(
            self.client.app.state.services.bridge,
            "refresh_login",
            return_value=self.write_login_state("auth-20231121130.json"),
        ):
            response = self.client.post(
                "/accounts/1",
                data={
                    "student_id": "20231121130",
                    "password": "",
                    "enabled": "on",
                },
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        updated_account = self.client.app.state.services.account_service.get_account(1)
        self.assertEqual(updated_account.password, "20231121130")

    def test_refresh_login_all_route_refreshes_all_accounts_and_persists_states(
        self,
    ) -> None:
        self.login()
        account_service = self.client.app.state.services.account_service
        second_account = account_service.create_account(
            name="停用号",
            student_id="20231121131",
            password="secret-2",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=False,
        )

        def refresh_login(account):
            return self.write_login_state(f"auth-{account.student_id}.json")

        with (
            patch.object(
                self.client.app.state.services.bridge,
                "refresh_login",
                side_effect=refresh_login,
            ) as refresh_login_mock,
            patch("prevent_auto.web.app.time.sleep") as sleep,
        ):
            response = self.client.post(
                "/accounts/refresh-login-all",
                follow_redirects=False,
            )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        self.assertEqual(
            [call.args[0].id for call in refresh_login_mock.call_args_list],
            [1, second_account.id],
        )
        sleep.assert_not_called()
        with sqlite3.connect(self.database_path) as connection:
            rows = connection.execute(
                """
                SELECT account_id, state_file, state_json
                FROM account_login_states
                ORDER BY account_id ASC
                """
            ).fetchall()
        self.assertEqual([row[0] for row in rows], [1, second_account.id])
        self.assertEqual(rows[0][1], "runtime/auth-20231121130.json")
        self.assertIn('"cookies"', rows[0][2])
        self.assertEqual(account_service.get_account(1).last_status, "登录态已刷新")
        self.assertEqual(
            account_service.get_account(second_account.id).last_status,
            "登录态已刷新",
        )

    def test_app_starts_with_background_workers_enabled(self) -> None:
        empty_database_path = self.temp_dir / "startup.db"
        initialize_database(empty_database_path)
        settings = PreventAutoSettings(
            project_root=self.temp_dir,
            package_root=Path(__file__).resolve().parents[1],
            data_dir=self.temp_dir,
            runtime_dir=self.runtime_dir,
            database_path=empty_database_path,
            host="127.0.0.1",
            port=8080,
            monitor_interval_seconds=1500,
            rebook_poll_interval_seconds=15,
            log_retention_days=30,
        )

        with TestClient(create_app(settings)):
            pass

    def test_run_rolling_reservation_endpoint_invokes_loop_and_redirects(
        self,
    ) -> None:
        self.login()

        captured: dict[str, object] = {"reset_count": 0, "ran": 0}

        class _StubResult:
            def to_notice(self) -> str:
                return "stub-notice"

        class _StubLoop:
            def reset(self) -> None:
                captured["reset_count"] = int(captured["reset_count"]) + 1

            def run_due_once(self, current_time):
                captured["ran"] = int(captured["ran"]) + 1
                captured["current_time"] = current_time

                class _Tick:
                    run_result = _StubResult()

                return _Tick()

        services = self.client.app.state.services
        # 用桩替换调度器，避免触发真实的 monitor cycle / 学校接口。
        object.__setattr__(services, "auto_reservation_loop", _StubLoop())

        response = self.client.post(
            "/accounts/run-rolling-reservation",
            data={"return_to": "/accounts"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(
            response.headers["location"].startswith("/accounts?notice=")
        )
        self.assertIn("stub-notice", response.headers["location"])
        self.assertEqual(captured["reset_count"], 1)
        self.assertEqual(captured["ran"], 1)

    def test_run_rolling_reservation_bootstraps_tasks_when_none_exist(
        self,
    ) -> None:
        self.login()

        captured: dict[str, object] = {"reset_count": 0, "ran": 0}

        class _FirstResult:
            checked_accounts = 1
            checked_tasks = 0

            def to_notice(self) -> str:
                return "zero-task-notice"

        class _RetryResult:
            checked_accounts = 1
            checked_tasks = 1

            def to_notice(self) -> str:
                return "retry-notice"

        class _StubLoop:
            def reset(self) -> None:
                captured["reset_count"] = int(captured["reset_count"]) + 1

            def run_due_once(self, current_time):
                captured["ran"] = int(captured["ran"]) + 1
                run_index = int(captured["ran"])
                captured["current_time"] = current_time

                class _Tick:
                    run_result = _FirstResult() if run_index == 1 else _RetryResult()

                return _Tick()

        class _StubBootstrapResult:
            created_count = 1
            updated_count = 0

            def to_notice(self) -> str:
                return "bootstrap-notice"

        class _StubTaskService:
            def bootstrap_tasks_from_bookings(
                self,
                account_ids,
                *,
                operator,
                client_kind,
                skip_accounts_with_existing_tasks,
            ):
                captured["bootstrap_account_ids"] = list(account_ids)
                captured["bootstrap_skip_existing"] = skip_accounts_with_existing_tasks
                return _StubBootstrapResult()

        from prevent_auto.web.api import automation_task as automation_task_api

        services = self.client.app.state.services
        object.__setattr__(services, "auto_reservation_loop", _StubLoop())
        setattr(
            self.client.app.state,
            automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR,
            _StubTaskService(),
        )

        response = self.client.post(
            "/accounts/run-rolling-reservation",
            data={"return_to": "/accounts"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertIn("retry-notice", response.headers["location"])
        self.assertEqual(captured["reset_count"], 2)
        self.assertEqual(captured["ran"], 2)
        self.assertEqual(captured["bootstrap_account_ids"], [1])
        self.assertTrue(captured["bootstrap_skip_existing"])

    def test_run_rolling_reservation_button_is_rendered_on_dashboard(self) -> None:
        self.login()

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("/accounts/run-rolling-reservation", response.text)
        self.assertIn("立刻检查并补齐预约", response.text)
        self.assertIn("补约日志", response.text)
        self.assertIn('id="auto-reservation-log-dialog"', response.text)
        self.assertIn('action="/auto-reservation/logging"', response.text)

    def test_auto_reservation_log_dialog_shows_recent_failures(self) -> None:
        self.login()
        ActionLogsRepository(self.database_path).create(
            account_id=1,
            action_type="auto_reserve",
            success=False,
            message="座位已被占用",
            payload_json='{"stage":"reserve_specific_seat"}',
        )

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("座位已被占用", response.text)
        self.assertIn("reserve_specific_seat", response.text)
        self.assertIn("复制补约日志", response.text)

    def test_auto_reservation_logging_toggle_persists_setting(self) -> None:
        self.login()

        response = self.client.post(
            "/auto-reservation/logging",
            data={"return_to": "/automation-tasks", "detailed_logging": "on"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/automation-tasks?notice="))
        self.assertTrue(
            AppSettingsRepository(self.database_path).get_bool(
                AUTO_RESERVATION_DETAILED_LOG_KEY
            )
        )

        response = self.client.get("/automation-tasks")
        self.assertEqual(response.status_code, 200)
        self.assertIn('name="detailed_logging"', response.text)
        self.assertIn("checked", response.text)

    def test_automation_task_run_auto_reserve_uses_rolling_loop_for_all(
        self,
    ) -> None:
        self.login()

        captured: dict[str, object] = {"reset_count": 0, "ran": 0}

        class _StubResult:
            def to_notice(self) -> str:
                return "rolling-all-notice"

        class _StubLoop:
            def reset(self) -> None:
                captured["reset_count"] = int(captured["reset_count"]) + 1

            def run_due_once(self, current_time):
                captured["ran"] = int(captured["ran"]) + 1
                captured["current_time"] = current_time

                class _Tick:
                    run_result = _StubResult()

                return _Tick()

        class _StubAutoReservationService:
            def run_all_once(self, **kwargs):
                raise AssertionError("old run_all_once path should not be used")

        services = self.client.app.state.services
        object.__setattr__(services, "auto_reservation_loop", _StubLoop())
        object.__setattr__(
            services, "auto_reservation_service", _StubAutoReservationService()
        )

        response = self.client.post(
            "/automation-tasks/run-auto-reserve",
            data={"account_id": ""},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(
            response.headers["location"].startswith("/automation-tasks?notice=")
        )
        self.assertIn("rolling-all-notice", response.headers["location"])
        self.assertEqual(captured["reset_count"], 1)
        self.assertEqual(captured["ran"], 1)

    def test_automation_task_run_auto_reserve_uses_rolling_for_filtered_account(
        self,
    ) -> None:
        self.login()

        captured: dict[str, object] = {}

        class _StubResult:
            def to_notice(self) -> str:
                return "rolling-filtered-notice"

        class _StubMonitorLoop:
            def run_account_once(self, account_id):
                captured["monitor_account_id"] = account_id

        class _StubAutoReservationService:
            def run_all_once(self, **kwargs):
                raise AssertionError("old run_all_once path should not be used")

            def run_rolling_once(self, *, account_id, current_time, days_ahead):
                captured["account_id"] = account_id
                captured["current_time"] = current_time
                captured["days_ahead"] = days_ahead
                return _StubResult()

        services = self.client.app.state.services
        object.__setattr__(services, "monitor_loop", _StubMonitorLoop())
        object.__setattr__(
            services, "auto_reservation_service", _StubAutoReservationService()
        )

        response = self.client.post(
            "/automation-tasks/run-auto-reserve",
            data={"account_id": "7"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(
            response.headers["location"].startswith(
                "/automation-tasks?account_id=7&notice="
            )
        )
        self.assertIn("rolling-filtered-notice", response.headers["location"])
        self.assertEqual(captured["monitor_account_id"], 7)
        self.assertEqual(captured["account_id"], 7)
        self.assertEqual(captured["days_ahead"], 3)

    def test_generate_tasks_from_bookings_route_invokes_service(self) -> None:
        self.login()

        captured: dict[str, object] = {}

        class _StubResult:
            def to_notice(self) -> str:
                return "stub-bootstrap-notice"

        class _StubTaskService:
            def bootstrap_tasks_from_bookings(
                self,
                account_ids,
                *,
                operator,
                client_kind,
                skip_accounts_with_existing_tasks,
            ):
                captured["account_ids"] = list(account_ids)
                captured["operator"] = operator
                captured["client_kind"] = client_kind
                captured["skip_existing"] = skip_accounts_with_existing_tasks
                return _StubResult()

        monitor_calls: list[int] = []

        class _StubMonitorLoop:
            def run_account_once(self, account_id: int) -> None:
                monitor_calls.append(account_id)

        # 替换 service 与 monitor_loop 桩，避免触达学校接口。
        from prevent_auto.web.api import automation_task as automation_task_api

        setattr(
            self.client.app.state,
            automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR,
            _StubTaskService(),
        )
        services = self.client.app.state.services
        object.__setattr__(services, "monitor_loop", _StubMonitorLoop())

        response = self.client.post(
            "/accounts/generate-tasks-from-bookings",
            data={"account_ids": ["11", "13"], "return_to": "/accounts"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertTrue(response.headers["location"].startswith("/accounts?notice="))
        self.assertIn("stub-bootstrap-notice", response.headers["location"])
        self.assertEqual(captured["account_ids"], [11, 13])
        self.assertEqual(captured["skip_existing"], False)
        self.assertEqual(monitor_calls, [11, 13])

    def test_generate_tasks_from_bookings_route_rejects_empty_selection(self) -> None:
        self.login()
        response = self.client.post(
            "/accounts/generate-tasks-from-bookings",
            data={"return_to": "/accounts"},
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 303)
        self.assertIn("notice=", response.headers["location"])
        # 提示文案必须含"勾选"提示，且 service 不会被调用
        self.assertIn(
            "%E5%8B%BE%E9%80%89", response.headers["location"]  # 勾选 url-encoded
        )

    def test_automation_task_bulk_soft_delete_rejects_invalid_keys(self) -> None:
        self.login()
        response = self.client.post(
            "/automation-tasks/bulk-soft-delete",
            data={"task_keys": "1:2:bad", "return_to": "/automation-tasks"},
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 303)
        self.assertIn("notice=", response.headers["location"])

    def test_automation_task_bulk_soft_delete_processes_each_triple(self) -> None:
        self.login()

        captured: list[tuple[int, int, int]] = []

        class _StubService:
            def soft_delete(
                self,
                account_id,
                task_id,
                *,
                expected_revision,
                operator,
                client_kind,
            ):
                captured.append((account_id, task_id, expected_revision))

        from prevent_auto.web.api import automation_task as automation_task_api

        setattr(
            self.client.app.state,
            automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR,
            _StubService(),
        )

        response = self.client.post(
            "/automation-tasks/bulk-soft-delete",
            data={
                "task_keys": ["11:101:3", "12:202:7"],
                "return_to": "/automation-tasks",
            },
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertEqual(captured, [(11, 101, 3), (12, 202, 7)])
        self.assertIn("%E5%B7%B2%E4%B8%8B%E7%BA%BF%202", response.headers["location"])

    def test_accounts_page_renders_generate_task_dialog(self) -> None:
        self.login()
        response = self.client.get("/accounts")

        self.assertEqual(response.status_code, 200)
        self.assertIn("/accounts/generate-tasks-from-bookings", response.text)
        self.assertIn("按当前预约创建自动任务", response.text)
        self.assertIn("generate-task-dialog", response.text)

    def test_automation_tasks_page_renders_bulk_soft_delete_button(self) -> None:
        self.login()
        response = self.client.get("/automation-tasks")

        self.assertEqual(response.status_code, 200)
        self.assertIn("/automation-tasks/bulk-soft-delete", response.text)
        self.assertIn("批量删除", response.text)
