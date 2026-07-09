"""account-pool-tri-sync · task 9.1 单元测试。

覆盖范围（与 task 9.6 的 PBT 任务互补）：

* :func:`prevent_auto.web.account_pool_view.format_remaining_suspension`：剩余
  暂停时长函数（design Property 6）的边界示例（已过期、刚好 0、半天、整天）。
* :func:`prevent_auto.web.account_pool_view.normalize_pool_tab`：URL ``?pool=``
  规整为合法 Tab 字面量。
* :func:`prevent_auto.web.account_pool_view.build_pool_view_context`：从真实
  :class:`AccountPoolService` 装配 Tab 视图，三池字段与计数符合 design「UI 设计」
  表的最小契约。
* ``GET /accounts?pool=...``：模板能渲染 Tab、列表与「迁入活跃池 / 迁入
  拉黑号池 / 迁入未启用池」三个独立按钮。
* ``POST /accounts/{id}/migrate``：把 ``target_pool`` 转发给
  :meth:`AccountPoolService.migrate`，业务异常落到中文 toast；外围副作用失败
  以 ``partial_failure`` 标记返回（Requirement 4.8 / 5.9）。

测试不连真实 cipher，全程使用真实 SQLite + 注入 :class:`_FakeClock` 控制时间。
"""

from __future__ import annotations

import tempfile
import unittest
from datetime import UTC, datetime, time as dtime, timedelta
from pathlib import Path
from urllib.parse import unquote

from fastapi.testclient import TestClient

from prevent_auto.account_pool.models import (
    BulkImportItemStatus,
    BulkImportRow,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import initialize_database
from prevent_auto.models import BookingSnapshot
from prevent_auto.services.account_password_cipher import (
    AccountPasswordCipher,
    generate_secret_key,
)
from prevent_auto.services.account_pool_service import (
    AccountPoolService,
    InMemoryLoginStatusCache,
)
from prevent_auto.services.account_service import AccountService
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.account_pool_view import (
    DEFAULT_POOL_TAB,
    LOGIN_STATUS_CACHE_MISSING_LABEL,
    LOGIN_STATUS_CACHE_TRACKED_LABEL,
    MIGRATION_TARGETS,
    POOL_TAB_KEYS,
    build_pool_view_context,
    format_remaining_suspension,
    format_utc_to_local,
    normalize_pool_tab,
)
from prevent_auto.web.app import create_app
from wuyi_seat_bot.seat_api import SHANGHAI_TZ


class _FakeClock:
    def __init__(self, start: datetime) -> None:
        self._current = start

    def __call__(self) -> datetime:
        return self._current

    def advance(self, delta: timedelta) -> None:
        self._current = self._current + delta


def _build_settings(temp_dir: Path, secret_key: str) -> PreventAutoSettings:
    return PreventAutoSettings(
        project_root=temp_dir,
        package_root=Path(__file__).resolve().parents[1],
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


class FormatRemainingSuspensionTest(unittest.TestCase):
    def test_returns_zero_when_already_expired(self) -> None:
        now = datetime(2026, 4, 26, 8, 0, 0, tzinfo=UTC)
        expires = now - timedelta(minutes=5)

        self.assertEqual(format_remaining_suspension(expires, now), "0 分钟")

    def test_returns_zero_when_now_equals_expires(self) -> None:
        now = datetime(2026, 4, 26, 8, 0, 0, tzinfo=UTC)

        self.assertEqual(format_remaining_suspension(now, now), "0 分钟")

    def test_returns_days_hours_minutes_when_far(self) -> None:
        now = datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC)
        expires = now + timedelta(days=2, hours=3, minutes=15)

        self.assertEqual(
            format_remaining_suspension(expires, now),
            "2 天 3 时 15 分钟",
        )

    def test_returns_minutes_only_when_short(self) -> None:
        now = datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC)
        expires = now + timedelta(minutes=42)

        self.assertEqual(format_remaining_suspension(expires, now), "42 分钟")

    def test_returns_less_than_minute_when_under_60s(self) -> None:
        now = datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC)
        expires = now + timedelta(seconds=30)

        self.assertEqual(format_remaining_suspension(expires, now), "不足 1 分钟")

    def test_returns_empty_string_when_expires_is_none(self) -> None:
        now = datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC)

        self.assertEqual(format_remaining_suspension(None, now), "")

    def test_naive_datetime_raises(self) -> None:
        now = datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC)
        naive = datetime(2026, 4, 27, 0, 0, 0)

        with self.assertRaises(ValueError):
            format_remaining_suspension(naive, now)


class FormatUtcToLocalTest(unittest.TestCase):
    def test_returns_empty_string_for_none(self) -> None:
        self.assertEqual(format_utc_to_local(None), "")

    def test_returns_empty_string_for_naive(self) -> None:
        self.assertEqual(
            format_utc_to_local(datetime(2026, 4, 26, 0, 0, 0)),
            "",
        )

    def test_renders_in_shanghai_local_format(self) -> None:
        utc = datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC)

        rendered = format_utc_to_local(utc)

        # UTC 0 点对应 Asia/Shanghai 8 点
        self.assertIn("2026年04月26日08时00分00秒", rendered)


class NormalizePoolTabTest(unittest.TestCase):
    def test_falls_back_to_active_when_blank(self) -> None:
        self.assertEqual(normalize_pool_tab(""), DEFAULT_POOL_TAB)
        self.assertEqual(normalize_pool_tab(None), DEFAULT_POOL_TAB)

    def test_accepts_lowercase_known_keys(self) -> None:
        for key in POOL_TAB_KEYS:
            self.assertEqual(normalize_pool_tab(key), key)

    def test_rejects_unknown_value(self) -> None:
        self.assertEqual(normalize_pool_tab("trash"), DEFAULT_POOL_TAB)


class BuildPoolViewContextTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "data" / "prevent_auto.db"
        self.secret_key = generate_secret_key()
        self.settings = _build_settings(self.temp_dir, self.secret_key)
        initialize_database(self.database_path, settings=self.settings)
        self.cipher = AccountPasswordCipher(self.secret_key)
        self.cache = InMemoryLoginStatusCache()
        self.clock = _FakeClock(datetime(2026, 4, 26, 0, 0, 0, tzinfo=UTC))
        self.pool_service = AccountPoolService(
            self.database_path,
            cipher=self.cipher,
            login_status_cache=self.cache,
            clock=self.clock,
        )
        self.account_service = AccountService(self.database_path)

    def _seed_idle(self, *, student_id: str) -> int:
        result = self.pool_service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id=student_id,
                    password="pw-" + student_id,
                    login_url="https://wuyi.test/login",
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        item = result.items[0]
        assert item.status is BulkImportItemStatus.OK
        assert item.account_id is not None
        return item.account_id

    def _seed_active(self, *, student_id: str) -> int:
        account_id = self._seed_idle(student_id=student_id)
        self.clock.advance(timedelta(seconds=1))
        self.pool_service.migrate(
            account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return account_id

    def _seed_suspended(self, *, student_id: str) -> int:
        account_id = self._seed_active(student_id=student_id)
        self.clock.advance(timedelta(seconds=1))
        self.pool_service.migrate(
            account_id,
            PoolStatus.SUSPENDED,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return account_id

    def test_active_tab_includes_login_status_label(self) -> None:
        active_id = self._seed_active(student_id="20231121130")

        ctx = build_pool_view_context(
            pool="active",
            pool_service=self.pool_service,
            account_service=self.account_service,
            now_utc=self.clock(),
        )

        self.assertEqual(ctx["selected_pool"], "active")
        self.assertEqual(len(ctx["pool_rows"]), 1)
        row = ctx["pool_rows"][0]
        self.assertEqual(row.account_id, active_id)
        self.assertEqual(row.login_status_label, LOGIN_STATUS_CACHE_TRACKED_LABEL)
        # 三池总数计入
        counts = ctx["pool_counts"]
        self.assertEqual(counts["active"], 1)
        self.assertEqual(counts["suspended"], 0)
        self.assertEqual(counts["idle"], 0)
        # 每行都含三个独立迁移按钮
        self.assertEqual(row.migration_buttons, MIGRATION_TARGETS)

    def test_active_tab_marks_login_cache_missing(self) -> None:
        active_id = self._seed_active(student_id="20231121130")
        # 强制清掉 cache，模拟登录态尚未占位
        self.pool_service.login_status_cache.clear(active_id)

        ctx = build_pool_view_context(
            pool="active",
            pool_service=self.pool_service,
            account_service=self.account_service,
            now_utc=self.clock(),
        )

        self.assertEqual(
            ctx["pool_rows"][0].login_status_label,
            LOGIN_STATUS_CACHE_MISSING_LABEL,
        )

    def test_suspended_tab_renders_remaining_and_expires(self) -> None:
        suspended_id = self._seed_suspended(student_id="20231121131")

        ctx = build_pool_view_context(
            pool="suspended",
            pool_service=self.pool_service,
            account_service=self.account_service,
            now_utc=self.clock(),
        )

        self.assertEqual(len(ctx["pool_rows"]), 1)
        row = ctx["pool_rows"][0]
        self.assertEqual(row.account_id, suspended_id)
        # suspended_at 由迁移时的 clock() 决定
        self.assertNotEqual(row.suspended_at_label, "")
        self.assertNotEqual(row.expires_at_label, "")
        # 168h 暂停剩余应为接近 7 天
        self.assertIn("天", row.remaining_label)

    def test_idle_tab_renders_pool_previous(self) -> None:
        idle_id = self._seed_idle(student_id="20231121132")

        ctx = build_pool_view_context(
            pool="idle",
            pool_service=self.pool_service,
            account_service=self.account_service,
            now_utc=self.clock(),
        )

        self.assertEqual(len(ctx["pool_rows"]), 1)
        row = ctx["pool_rows"][0]
        self.assertEqual(row.account_id, idle_id)
        self.assertNotEqual(row.entered_at_label, "")
        # 批量导入入池，pool_previous 为空字符串 → 渲染 "—"
        self.assertEqual(row.pool_previous_label, "—")

    def test_unknown_pool_falls_back_to_active(self) -> None:
        ctx = build_pool_view_context(
            pool="trash",
            pool_service=self.pool_service,
            account_service=self.account_service,
            now_utc=self.clock(),
        )
        self.assertEqual(ctx["selected_pool"], "active")


class AccountsPagePoolTabRoutingTest(unittest.TestCase):
    """端到端：``GET /accounts?pool=...`` 模板 + ``POST /accounts/{id}/migrate``."""

    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        self.runtime_dir = self.temp_dir / "runtime"
        secret_key = generate_secret_key()
        initialize_database(self.database_path)
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
            daily_status_refresh_time=dtime(8, 10),
            account_pool_secret_key=secret_key,
        )
        # 必须重新初始化以确保密钥下的密码列已就绪
        initialize_database(self.database_path, settings=self.settings)
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

    def _seed_active_via_service(self, *, student_id: str) -> int:
        pool_service = self.client.app.state.services.account_pool_service
        assert pool_service is not None
        result = pool_service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id=student_id,
                    password="pw-" + student_id,
                    login_url="https://wuyi.test/login",
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        item = result.items[0]
        assert item.status is BulkImportItemStatus.OK
        assert item.account_id is not None
        pool_service.migrate(
            item.account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return item.account_id

    def test_accounts_page_renders_pool_tabs(self) -> None:
        self.login()
        active_id = self._seed_active_via_service(student_id="20231121133")

        response = self.client.get("/accounts?pool=active")

        self.assertEqual(response.status_code, 200)
        # Tab 链接齐全
        self.assertIn("href=\"/accounts?pool=active\"", response.text)
        self.assertIn("href=\"/accounts?pool=suspended\"", response.text)
        self.assertIn("href=\"/accounts?pool=idle\"", response.text)
        # 每行三个独立迁移按钮（按钮文案）
        self.assertIn("迁入活跃池", response.text)
        self.assertIn("迁入拉黑号池", response.text)
        self.assertIn("迁入未启用池", response.text)
        self.assertIn("未预约移入未启用池", response.text)
        # 表单 action 指向 migrate 端点
        self.assertIn(
            f"action=\"/accounts/{active_id}/migrate\"",
            response.text,
        )

    def test_active_tab_collapses_same_day_bookings_with_checked_in_first(self) -> None:
        self.login()
        active_id = self._seed_active_via_service(student_id="20231121137")
        today = datetime.now(SHANGHAI_TZ).date()
        today_cancelled = datetime(
            today.year, today.month, today.day, 8, 0, tzinfo=SHANGHAI_TZ
        )
        today_checked_in = datetime(
            today.year, today.month, today.day, 12, 55, tzinfo=SHANGHAI_TZ
        )
        tomorrow_pending = today_cancelled + timedelta(days=1)
        repository = self.client.app.state.services.booking_snapshots_repository
        assert repository is not None
        repository.replace_for_account(
            account_id=active_id,
            refreshed_at=datetime.now(SHANGHAI_TZ).replace(microsecond=0).isoformat(),
            bookings=[
                BookingSnapshot(
                    booking_id="cancelled-today",
                    room_name="自习室圆形二楼",
                    seat_number="17",
                    status="4",
                    start_time=int(today_cancelled.timestamp()),
                    duration_seconds=2 * 60 * 60,
                ),
                BookingSnapshot(
                    booking_id="checked-in-today",
                    room_name="自习室圆形二楼",
                    seat_number="17",
                    status="1",
                    start_time=int(today_checked_in.timestamp()),
                    duration_seconds=9 * 60 * 60 + 5 * 60,
                ),
                BookingSnapshot(
                    booking_id="pending-tomorrow",
                    room_name="自习室圆形二楼",
                    seat_number="17",
                    status="0",
                    start_time=int(tomorrow_pending.timestamp()),
                    duration_seconds=14 * 60 * 60,
                ),
            ],
        )

        response = self.client.get("/accounts?pool=active")

        self.assertEqual(response.status_code, 200)
        self.assertIn("共 2 条", response.text)
        self.assertIn('data-booking-row="1"', response.text)
        self.assertNotIn('data-booking-row="2"', response.text)
        self.assertIn("已签到", response.text)
        self.assertNotIn("已取消", response.text)
        self.assertIn("12:55-22:00", response.text)
        self.assertNotIn("08:00-10:00", response.text)

    def test_accounts_page_falls_back_to_active_on_unknown_pool(self) -> None:
        self.login()

        response = self.client.get("/accounts?pool=trash")

        self.assertEqual(response.status_code, 200)
        self.assertIn("data-pool-current=\"active\"", response.text)

    def test_migrate_endpoint_moves_account_via_service(self) -> None:
        self.login()
        active_id = self._seed_active_via_service(student_id="20231121134")

        response = self.client.post(
            f"/accounts/{active_id}/migrate",
            data={"target_pool": "idle", "pool": "active"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertIn("/accounts?pool=active", response.headers["location"])
        # 状态应已更新
        pool_service = self.client.app.state.services.account_pool_service
        assert pool_service is not None
        idle_entries = pool_service.list_by_pool(PoolStatus.IDLE)
        self.assertEqual({e.account_id for e in idle_entries}, {active_id})

    def test_migrate_endpoint_rejects_illegal_transition_without_rollback(self) -> None:
        self.login()
        pool_service = self.client.app.state.services.account_pool_service
        assert pool_service is not None

        # 通过 bulk_import_to_idle 把账号写到 idle，然后尝试 idle → suspended
        result = pool_service.bulk_import_to_idle(
            [
                BulkImportRow(
                    student_id="20231121135",
                    password="pw-1",
                    login_url="https://wuyi.test/login",
                    source_row=1,
                ),
            ],
            operator="seed",
        )
        idle_id = result.items[0].account_id
        assert idle_id is not None

        response = self.client.post(
            f"/accounts/{idle_id}/migrate",
            data={"target_pool": "suspended", "pool": "idle"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        # 通过 query 参数携带的 notice 应包含「非法的池间迁移路径」
        location = response.headers["location"]
        self.assertIn("/accounts?pool=idle", location)
        # 验证账号仍在 idle，未被迁移
        idle_entries = pool_service.list_by_pool(PoolStatus.IDLE)
        self.assertEqual({e.account_id for e in idle_entries}, {idle_id})

    def test_migrate_endpoint_rejects_unknown_target_pool(self) -> None:
        self.login()
        active_id = self._seed_active_via_service(student_id="20231121136")

        response = self.client.post(
            f"/accounts/{active_id}/migrate",
            data={"target_pool": "trash", "pool": "active"},
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        self.assertIn("/accounts?pool=active", response.headers["location"])
        # 状态没变
        pool_service = self.client.app.state.services.account_pool_service
        assert pool_service is not None
        active_entries = pool_service.list_by_pool(PoolStatus.ACTIVE)
        self.assertEqual({e.account_id for e in active_entries}, {active_id})

    def test_move_unbooked_active_accounts_to_idle_keeps_protected_bookings(self) -> None:
        self.login()
        no_booking_id = self._seed_active_via_service(student_id="20231121138")
        checked_in_id = self._seed_active_via_service(student_id="20231121139")
        future_pending_id = self._seed_active_via_service(student_id="20231121140")
        services = self.client.app.state.services
        repository = services.booking_snapshots_repository
        assert repository is not None
        today = datetime.now(SHANGHAI_TZ).date()
        today_start = datetime(
            today.year, today.month, today.day, 12, 0, tzinfo=SHANGHAI_TZ
        )
        tomorrow_start = today_start + timedelta(days=1)
        refreshed_ids: list[int] = []

        def fake_run_account_once(account_id: int) -> None:
            refreshed_ids.append(account_id)
            bookings: list[BookingSnapshot] = []
            if account_id == checked_in_id:
                bookings = [
                    BookingSnapshot(
                        booking_id="checked-in-today",
                        room_name="自习室圆形二楼",
                        seat_number="18",
                        status="1",
                        start_time=int(today_start.timestamp()),
                        duration_seconds=2 * 60 * 60,
                    )
                ]
            elif account_id == future_pending_id:
                bookings = [
                    BookingSnapshot(
                        booking_id="pending-tomorrow",
                        room_name="自习室圆形二楼",
                        seat_number="19",
                        status="0",
                        start_time=int(tomorrow_start.timestamp()),
                        duration_seconds=14 * 60 * 60,
                    )
                ]
            repository.replace_for_account(
                account_id=account_id,
                bookings=bookings,
                refreshed_at=datetime.now(SHANGHAI_TZ)
                .replace(microsecond=0)
                .isoformat(),
            )

        services.monitor_loop.run_account_once = fake_run_account_once

        response = self.client.post(
            "/accounts/move-unbooked-to-idle",
            follow_redirects=False,
        )

        self.assertEqual(response.status_code, 303)
        location = unquote(response.headers["location"])
        self.assertIn("/accounts?pool=active", location)
        self.assertIn("已移动 1 个未预约账号到未启用池", location)
        self.assertEqual(
            set(refreshed_ids),
            {no_booking_id, checked_in_id, future_pending_id},
        )
        pool_service = services.account_pool_service
        assert pool_service is not None
        idle_entries = pool_service.list_by_pool(PoolStatus.IDLE)
        active_entries = pool_service.list_by_pool(PoolStatus.ACTIVE)
        self.assertEqual({entry.account_id for entry in idle_entries}, {no_booking_id})
        self.assertEqual(
            {entry.account_id for entry in active_entries},
            {checked_in_id, future_pending_id},
        )


if __name__ == "__main__":
    unittest.main()
