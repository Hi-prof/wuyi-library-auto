from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from datetime import UTC, datetime, time as dtime, timedelta
from pathlib import Path

from wuyi_seat_bot.seat_api import SHANGHAI_TZ

from prevent_auto.account_pool.models import (
    BulkImportItemStatus,
    BulkImportRow,
    ClientKind,
    CustomWindow,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import initialize_database
from prevent_auto.models import BookingSnapshot
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.action_logs import ActionLogsRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.booking_snapshots import BookingSnapshotsRepository
from prevent_auto.repositories.monitor_records import MonitorRecordsRepository
from prevent_auto.services.account_password_cipher import (
    AccountPasswordCipher,
    generate_secret_key,
)
from prevent_auto.services.account_pool_service import (
    AccountPoolService,
    InMemoryLoginStatusCache,
)
from prevent_auto.services.account_service import AccountService
from prevent_auto.services.auto_reservation_service import AutoReservationService
from prevent_auto.services.automation_task_service import (
    AutomationTaskService,
    AutomationTaskUpsertPayload,
)
from prevent_auto.services.booking_status_service import BookingStatusService
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
        self.current = start

    def __call__(self) -> datetime:
        return self.current

    def advance(self, delta: timedelta) -> None:
        self.current = self.current + delta


class _FakeBridge:
    def __init__(self) -> None:
        self.bookings_by_account: dict[int, list[BookingSnapshot]] = {}
        self.fetch_accounts: list[dict[str, str]] = []
        self.reserve_accounts: list[dict[str, str]] = []
        self.reserve_calls: list[dict[str, object]] = []
        self.reserve_success = True

    def fetch_bookings(self, account) -> list[BookingSnapshot]:
        self.fetch_accounts.append(
            {
                "password": account.password,
                "seat_url": account.seat_url,
            }
        )
        return list(self.bookings_by_account.get(account.id, []))

    def reserve_specific_seat(
        self,
        account,
        *,
        room_name: str,
        seat_number: str,
        date_value: str,
        start_hour: int,
        end_hour: int,
    ) -> tuple[bool, str]:
        self.reserve_accounts.append(
            {
                "password": account.password,
                "seat_url": account.seat_url,
            }
        )
        self.reserve_calls.append(
            {
                "account_id": account.id,
                "room_name": room_name,
                "seat_number": seat_number,
                "date_value": date_value,
                "start_hour": start_hour,
                "end_hour": end_hour,
            }
        )
        if not self.reserve_success:
            return False, "座位不可预约"
        start_at = _timestamp(date_value, start_hour)
        self.bookings_by_account.setdefault(account.id, []).append(
            BookingSnapshot(
                booking_id=f"auto-{len(self.reserve_calls)}",
                room_name=room_name,
                seat_number=seat_number,
                status="0",
                start_time=start_at,
                duration_seconds=(end_hour - start_hour) * 3600,
            )
        )
        return True, f"已重约：{room_name} {seat_number} 号座位"


def _timestamp(date_value: str, hour: int) -> int:
    date = datetime.fromisoformat(date_value).date()
    return int(
        datetime(date.year, date.month, date.day, hour, tzinfo=SHANGHAI_TZ).timestamp()
    )


class AutoReservationServiceTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "data" / "prevent_auto.db"
        self.secret_key = generate_secret_key()
        self.settings = _build_settings(self.temp_dir, self.secret_key)
        initialize_database(self.database_path, settings=self.settings)
        self.clock = _FakeClock(datetime(2026, 4, 27, 8, 0, tzinfo=SHANGHAI_TZ))
        self.account_service = AccountService(self.database_path)
        self.pool_service = AccountPoolService(
            self.database_path,
            cipher=AccountPasswordCipher(self.secret_key),
            login_status_cache=InMemoryLoginStatusCache(),
            clock=lambda: self.clock().astimezone(UTC),
        )
        self.automation_tasks_repo = AutomationTasksRepository(self.database_path)
        self.account_pool_repo = AccountPoolRepository(self.database_path)
        self.audit_repo = __import__(
            "prevent_auto.repositories.pool_audit_log",
            fromlist=["PoolAuditLogRepository"],
        ).PoolAuditLogRepository(self.database_path)
        self.task_service = AutomationTaskService(
            self.database_path,
            automation_tasks_repo=self.automation_tasks_repo,
            account_pool_repo=self.account_pool_repo,
            audit_repo=self.audit_repo,
            clock=lambda: self.clock().astimezone(UTC),
        )
        self.bridge = _FakeBridge()
        self.service = AutoReservationService(
            account_service=self.account_service,
            booking_status_service=BookingStatusService(self.bridge),
            bridge=self.bridge,
            automation_tasks_repo=self.automation_tasks_repo,
            account_pool_repo=self.account_pool_repo,
            monitor_records_repository=MonitorRecordsRepository(self.database_path),
            action_logs_repository=ActionLogsRepository(self.database_path),
            booking_snapshots_repository=BookingSnapshotsRepository(self.database_path),
            now_provider=self.clock,
        )

    def _seed_active_account(self, student_id: str = "20231121901") -> int:
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
        self.pool_service.migrate(
            item.account_id,
            PoolStatus.ACTIVE,
            operator="admin",
            trigger_source=PoolMigrationTrigger.MANUAL,
        )
        return item.account_id

    def _seed_task(
        self,
        account_id: int,
        *,
        enabled: bool = True,
        windows: tuple[CustomWindow, ...],
    ) -> None:
        self.task_service.upsert(
            account_id,
            None,
            AutomationTaskUpsertPayload(
                room_name="三层东区",
                seat_number="A12",
                mode="preferred",
                custom_windows=windows,
                enabled=enabled,
            ),
            0,
            operator="seed",
            client_kind=ClientKind.WINDOW,
        )

    def test_reserves_only_missing_enabled_task_windows(self) -> None:
        account_id = self._seed_active_account()
        self._seed_task(
            account_id,
            windows=(
                CustomWindow(date="2026-04-28", start_hour=8, end_hour=10),
                CustomWindow(date="2026-04-28", start_hour=10, end_hour=12),
            ),
        )
        self.bridge.bookings_by_account[account_id] = [
            BookingSnapshot(
                booking_id="existing-1",
                room_name="任意自习室",
                seat_number="B09",
                status="0",
                start_time=_timestamp("2026-04-28", 8),
                duration_seconds=2 * 3600,
            )
        ]

        result = self.service.run_account_once(account_id)

        self.assertEqual(result.checked_accounts, 1)
        self.assertEqual(result.checked_tasks, 1)
        self.assertEqual(result.checked_windows, 2)
        self.assertEqual(result.already_reserved, 1)
        self.assertEqual(result.reserved, 1)
        self.assertEqual(self.bridge.reserve_calls, [
            {
                "account_id": account_id,
                "room_name": "三层东区",
                "seat_number": "A12",
                "date_value": "2026-04-28",
                "start_hour": 10,
                "end_hour": 12,
            }
        ])

        action_logs = ActionLogsRepository(self.database_path).list_recent()
        self.assertEqual(action_logs[0].action_type, "auto_reserve")
        self.assertTrue(action_logs[0].success)

    def test_skips_disabled_tasks_and_expired_windows(self) -> None:
        account_id = self._seed_active_account()
        self._seed_task(
            account_id,
            enabled=False,
            windows=(CustomWindow(date="2026-04-28", start_hour=8, end_hour=10),),
        )
        self._seed_task(
            account_id,
            windows=(CustomWindow(date="2026-04-26", start_hour=8, end_hour=10),),
        )

        result = self.service.run_account_once(account_id)

        self.assertEqual(result.checked_tasks, 1)
        self.assertEqual(result.checked_windows, 1)
        self.assertEqual(result.skipped, 1)
        self.assertEqual(self.bridge.reserve_calls, [])

    def test_failed_reservation_is_reported_without_stopping_other_windows(self) -> None:
        account_id = self._seed_active_account()
        self._seed_task(
            account_id,
            windows=(
                CustomWindow(date="2026-04-28", start_hour=8, end_hour=10),
                CustomWindow(date="2026-04-28", start_hour=10, end_hour=12),
            ),
        )
        self.bridge.reserve_success = False

        result = self.service.run_account_once(account_id)

        self.assertEqual(result.failed, 2)
        self.assertEqual(len(self.bridge.reserve_calls), 2)
        action_logs = ActionLogsRepository(self.database_path).list_recent()
        self.assertEqual(len(action_logs), 2)
        self.assertFalse(action_logs[0].success)

    def test_rolling_once_expands_hour_template_for_each_day(self) -> None:
        account_id = self._seed_active_account()
        # 客户端写死 2026-04-26（昨天）；滚动模式应忽略 date，按小时模板抢
        # 今天到今天+2 共 3 天。
        self._seed_task(
            account_id,
            windows=(
                CustomWindow(date="2026-04-26", start_hour=10, end_hour=12),
            ),
        )

        result = self.service.run_rolling_once(days_ahead=3)

        self.assertEqual(result.checked_accounts, 1)
        self.assertEqual(result.checked_windows, 3)
        self.assertEqual(result.reserved, 3)
        self.assertEqual(
            [call["date_value"] for call in self.bridge.reserve_calls],
            ["2026-04-27", "2026-04-28", "2026-04-29"],
        )
        snapshots = BookingSnapshotsRepository(self.database_path).list_by_account_ids(
            [account_id]
        )
        self.assertEqual(
            sorted(
                datetime.fromtimestamp(
                    booking.start_time, SHANGHAI_TZ
                ).date().isoformat()
                for booking in snapshots[account_id]
            ),
            ["2026-04-27", "2026-04-28", "2026-04-29"],
        )

    def test_rolling_once_skips_days_already_covered(self) -> None:
        account_id = self._seed_active_account()
        self._seed_task(
            account_id,
            windows=(
                CustomWindow(date="2026-04-27", start_hour=10, end_hour=12),
            ),
        )
        # 28 号已经有同一时段的活跃预约 → 应跳过。
        self.bridge.bookings_by_account[account_id] = [
            BookingSnapshot(
                booking_id="existing-28",
                room_name="三层东区",
                seat_number="A12",
                status="0",
                start_time=_timestamp("2026-04-28", 10),
                duration_seconds=2 * 3600,
            )
        ]

        result = self.service.run_rolling_once(days_ahead=3)

        self.assertEqual(result.reserved, 2)
        self.assertEqual(result.already_reserved, 1)
        self.assertEqual(
            [call["date_value"] for call in self.bridge.reserve_calls],
            ["2026-04-27", "2026-04-29"],
        )

    def test_detailed_logging_records_already_reserved_reason(self) -> None:
        account_id = self._seed_active_account()
        self._seed_task(
            account_id,
            windows=(
                CustomWindow(date="2026-04-27", start_hour=10, end_hour=12),
            ),
        )
        self.bridge.bookings_by_account[account_id] = [
            BookingSnapshot(
                booking_id="existing-27",
                room_name="三层东区",
                seat_number="A12",
                status="0",
                start_time=_timestamp("2026-04-27", 10),
                duration_seconds=2 * 3600,
            )
        ]
        self.service.detailed_log_enabled_provider = lambda: True

        result = self.service.run_rolling_once(days_ahead=1)

        self.assertEqual(result.already_reserved, 1)
        action_logs = ActionLogsRepository(self.database_path).list_recent(
            action_type="auto_reserve"
        )
        self.assertEqual(len(action_logs), 1)
        self.assertTrue(action_logs[0].success)
        self.assertIn("已有覆盖预约", action_logs[0].message)
        self.assertIn('"stage": "already_reserved"', action_logs[0].payload_json)

    def test_rolling_once_dedupes_repeated_hour_templates(self) -> None:
        account_id = self._seed_active_account()
        # 同一小时模板出现两次（不同日期），只应被尝试一次/天。
        self._seed_task(
            account_id,
            windows=(
                CustomWindow(date="2026-04-26", start_hour=10, end_hour=12),
                CustomWindow(date="2026-04-27", start_hour=10, end_hour=12),
            ),
        )

        result = self.service.run_rolling_once(days_ahead=2)

        self.assertEqual(result.reserved, 2)
        self.assertEqual(len(self.bridge.reserve_calls), 2)

    def test_resolves_pool_credentials_before_fetching_and_reserving(self) -> None:
        account_id = self._seed_active_account()
        self._seed_task(
            account_id,
            windows=(
                CustomWindow(date="2026-04-28", start_hour=10, end_hour=12),
            ),
        )
        self.service.account_resolver = lambda account: replace(
            account,
            password=self.pool_service.get_login_password(account.id),
            seat_url=account.seat_url or account.login_url,
        )

        result = self.service.run_rolling_once(account_id=account_id, days_ahead=2)

        self.assertEqual(result.reserved, 2)
        self.assertEqual(
            self.bridge.fetch_accounts,
            [
                {
                    "password": "pw-20231121901",
                    "seat_url": "https://wuyi.test/login",
                }
            ],
        )
        self.assertEqual(
            self.bridge.reserve_accounts[0],
            {
                "password": "pw-20231121901",
                "seat_url": "https://wuyi.test/login",
            },
        )

    def test_notice_explains_zero_tasks_did_not_attempt_reservation(self) -> None:
        result = self.service.run_rolling_once(days_ahead=3)

        self.assertEqual(result.checked_accounts, 0)
        self.assertNotIn("未找到可执行自动任务", result.to_notice())

        account_id = self._seed_active_account()
        result = self.service.run_rolling_once(account_id=account_id, days_ahead=3)

        self.assertEqual(result.checked_accounts, 1)
        self.assertEqual(result.checked_tasks, 0)
        self.assertIn("未找到可执行自动任务", result.to_notice())
        self.assertIn("本轮未尝试补约", result.to_notice())


if __name__ == "__main__":
    unittest.main()
