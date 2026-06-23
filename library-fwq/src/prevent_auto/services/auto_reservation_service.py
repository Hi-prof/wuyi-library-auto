from __future__ import annotations

import threading
from collections import defaultdict
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta

from wuyi_seat_bot.seat_api import SHANGHAI_TZ

from prevent_auto.account_pool.models import (
    AutomationTask,
    CustomWindow,
    PoolStatus,
)
from prevent_auto.models import Account, BookingSnapshot
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.action_logs import (
    ActionLogsRepository,
    safe_payload_json,
)
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.booking_snapshots import BookingSnapshotsRepository
from prevent_auto.repositories.monitor_records import MonitorRecordsRepository
from prevent_auto.services.booking_status_service import PROTECTED_BOOKING_STATUSES


@dataclass(frozen=True)
class AutoReservationRunResult:
    """一次自动补约检查的汇总结果。"""

    checked_accounts: int = 0
    checked_tasks: int = 0
    checked_windows: int = 0
    already_reserved: int = 0
    reserved: int = 0
    failed: int = 0
    skipped: int = 0

    def merge(self, other: "AutoReservationRunResult") -> "AutoReservationRunResult":
        return AutoReservationRunResult(
            checked_accounts=self.checked_accounts + other.checked_accounts,
            checked_tasks=self.checked_tasks + other.checked_tasks,
            checked_windows=self.checked_windows + other.checked_windows,
            already_reserved=self.already_reserved + other.already_reserved,
            reserved=self.reserved + other.reserved,
            failed=self.failed + other.failed,
            skipped=self.skipped + other.skipped,
        )

    def to_notice(self) -> str:
        notice = (
            f"检查完成：账号 {self.checked_accounts} 个，任务 {self.checked_tasks} 个，"
            f"时段 {self.checked_windows} 个；已预约 {self.already_reserved} 个，"
            f"补约成功 {self.reserved} 个，失败 {self.failed} 个，跳过 {self.skipped} 个"
        )
        if self.checked_accounts > 0 and self.checked_tasks == 0:
            return f"{notice}；未找到可执行自动任务，本轮未尝试补约"
        return notice


@dataclass(frozen=True)
class _WindowTarget:
    task_id: int
    room_name: str
    seat_number: str
    window: CustomWindow
    start_at: datetime
    end_at: datetime

    @property
    def key(self) -> tuple[str, int, int]:
        return (self.window.date, self.window.start_hour, self.window.end_hour)

    @property
    def label(self) -> str:
        return (
            f"任务 #{self.task_id} {self.room_name} {self.seat_number} "
            f"{self.window.date} {self.window.start_hour:02d}:00-"
            f"{self.window.end_hour:02d}:00"
        )


class AutoReservationService:
    """按启用 Automation_Task 检查并补齐缺失预约。"""

    def __init__(
        self,
        *,
        account_service,
        booking_status_service,
        bridge,
        automation_tasks_repo: AutomationTasksRepository,
        account_pool_repo: AccountPoolRepository,
        monitor_records_repository: MonitorRecordsRepository,
        action_logs_repository: ActionLogsRepository,
        booking_snapshots_repository: BookingSnapshotsRepository | None = None,
        account_locks: dict[int, threading.Lock] | None = None,
        now_provider: Callable[[], datetime] | None = None,
        detailed_log_enabled_provider: Callable[[], bool] | None = None,
        account_resolver: Callable[[Account], Account] | None = None,
        rolling_days_ahead: int = 1,
    ) -> None:
        if rolling_days_ahead <= 0:
            raise ValueError("rolling_days_ahead 必须大于 0")
        self.account_service = account_service
        self.booking_status_service = booking_status_service
        self.bridge = bridge
        self.automation_tasks_repo = automation_tasks_repo
        self.account_pool_repo = account_pool_repo
        self.monitor_records_repository = monitor_records_repository
        self.action_logs_repository = action_logs_repository
        self.booking_snapshots_repository = booking_snapshots_repository
        self.account_locks = (
            account_locks if account_locks is not None else defaultdict(threading.Lock)
        )
        self.now_provider = now_provider or (lambda: datetime.now().astimezone())
        self.detailed_log_enabled_provider = detailed_log_enabled_provider
        self.account_resolver = account_resolver or (lambda account: account)
        self.rolling_days_ahead = rolling_days_ahead

    def run_all_once(
        self,
        *,
        current_time: datetime | None = None,
        account_id: int | None = None,
    ) -> AutoReservationRunResult:
        """检查全部活跃账号或单个账号，并按任务补齐缺失预约。"""

        now = _localize(current_time or self.now_provider())
        account_ids = [account_id] if account_id is not None else self._active_account_ids()
        result = AutoReservationRunResult()
        for target_account_id in account_ids:
            result = result.merge(
                self.run_account_once(target_account_id, current_time=now)
            )
        return result

    def run_account_once(
        self,
        account_id: int,
        *,
        current_time: datetime | None = None,
    ) -> AutoReservationRunResult:
        """检查单个账号的启用任务，缺失的任务时段会立即尝试预约。"""

        now = _localize(current_time or self.now_provider())
        return self._run_account_with_strategy(
            account_id,
            now=now,
            target_builder=_build_targets_from_task_windows,
        )

    def run_rolling_once(
        self,
        *,
        current_time: datetime | None = None,
        account_id: int | None = None,
        days_ahead: int | None = None,
    ) -> AutoReservationRunResult:
        """滚动预约：按任务里的小时模板把今天到今天+N-1 天的空缺补齐。

        - 取每个 task 的 ``custom_windows`` 按 ``(start_hour, end_hour)`` 去重作为
          每日小时模板（``date`` 字段被忽略，因此不会因为客户端写死了昨天的日期
          而漏抢）。
        - 对今天到今天 + ``days_ahead`` - 1 共 N 天逐天展开，已被该账号其它预约
          覆盖的时段跳过；过去时段也会被通用过滤器丢掉。
        - 单账号 / 全活跃账号入口与 :meth:`run_all_once` 保持一致。
        """

        now = _localize(current_time or self.now_provider())
        days = days_ahead if days_ahead is not None else self.rolling_days_ahead
        if days <= 0:
            raise ValueError("days_ahead 必须大于 0")
        target_builder = _make_rolling_target_builder(now=now, days_ahead=days)
        account_ids = (
            [account_id] if account_id is not None else self._active_account_ids()
        )
        result = AutoReservationRunResult()
        for target_account_id in account_ids:
            result = result.merge(
                self._run_account_with_strategy(
                    target_account_id,
                    now=now,
                    target_builder=target_builder,
                )
            )
        return result

    def _run_account_with_strategy(
        self,
        account_id: int,
        *,
        now: datetime,
        target_builder: Callable[
            [AutomationTask], list["_WindowTarget | None"]
        ],
    ) -> AutoReservationRunResult:
        """共用内核：按指定的 ``target_builder`` 展开任务并尝试补预约。

        ``target_builder`` 接受单个 :class:`AutomationTask`，返回该任务在本轮
        要尝试的目标窗口列表（``None`` 表示构造失败的窗口，会被记为 ``skipped``）。
        现有的 ``run_account_once`` 走 :func:`_build_targets_from_task_windows`，
        新增的 ``run_rolling_once`` 走 :func:`_make_rolling_target_builder`。
        """

        detected_at = now.replace(microsecond=0).isoformat()
        with self.account_locks[account_id]:
            if not self._is_active_account(account_id):
                return AutoReservationRunResult(skipped=1)

            account = self.account_service.get_account(account_id)
            if not account.enabled:
                return AutoReservationRunResult(checked_accounts=1, skipped=1)

            tasks = [
                task
                for task in self.automation_tasks_repo.list_for_account(account_id)
                if task.enabled
            ]
            if not tasks:
                return AutoReservationRunResult(checked_accounts=1)

            try:
                account = self._resolve_account(account)
            except Exception as exc:  # noqa: BLE001
                message = f"自动预约检查失败：{exc}"
                self._record_diagnostic_action(
                    account_id=account_id,
                    success=False,
                    message=message,
                    payload={
                        "stage": "resolve_account",
                        "checkedTasks": len(tasks),
                    },
                    force=True,
                )
                self._record_monitor(
                    account_id=account_id,
                    detected_at=detected_at,
                    decision="auto_reserve_failed",
                    detail=message,
                )
                self.account_service.update_status(
                    account_id,
                    last_check_at=detected_at,
                    last_status=message,
                )
                return AutoReservationRunResult(
                    checked_accounts=1,
                    checked_tasks=len(tasks),
                    failed=1,
                )

            try:
                bookings = self.booking_status_service.fetch_all_bookings(account)
            except Exception as exc:  # noqa: BLE001
                message = f"自动预约检查失败：{exc}"
                self._record_diagnostic_action(
                    account_id=account_id,
                    success=False,
                    message=message,
                    payload={
                        "stage": "fetch_bookings",
                        "checkedTasks": len(tasks),
                    },
                    force=True,
                )
                self._record_monitor(
                    account_id=account_id,
                    detected_at=detected_at,
                    decision="auto_reserve_failed",
                    detail=message,
                )
                self.account_service.update_status(
                    account_id,
                    last_check_at=detected_at,
                    last_status=message,
                )
                return AutoReservationRunResult(
                    checked_accounts=1,
                    checked_tasks=len(tasks),
                    failed=1,
                )

            self._replace_booking_snapshots(
                account_id=account_id,
                bookings=bookings,
                detected_at=detected_at,
            )

            result = AutoReservationRunResult(
                checked_accounts=1,
                checked_tasks=len(tasks),
            )
            reserved_windows: set[tuple[str, int, int]] = set()
            for task in tasks:
                targets = target_builder(task)
                if not targets:
                    self._record_diagnostic_action(
                        account_id=account_id,
                        success=True,
                        message=f"任务 #{task.task_id} 没有可展开的预约时段，已跳过",
                        payload={
                            "stage": "no_targets",
                            "taskId": task.task_id,
                            "roomName": task.room_name,
                            "seatNumber": task.seat_number,
                        },
                    )
                    result = result.merge(AutoReservationRunResult(skipped=1))
                    continue
                for target in targets:
                    result = result.merge(AutoReservationRunResult(checked_windows=1))
                    if target is None:
                        self._record_diagnostic_action(
                            account_id=account_id,
                            success=True,
                            message=f"任务 #{task.task_id} 存在无效预约时段，已跳过",
                            payload={
                                "stage": "invalid_window",
                                "taskId": task.task_id,
                                "roomName": task.room_name,
                                "seatNumber": task.seat_number,
                            },
                        )
                        result = result.merge(AutoReservationRunResult(skipped=1))
                        continue
                    if target.end_at.timestamp() <= now.timestamp():
                        self._record_diagnostic_action(
                            account_id=account_id,
                            success=True,
                            message=f"{target.label} 已过期，跳过补约",
                            payload={
                                "stage": "expired_window",
                                "taskId": target.task_id,
                                "roomName": target.room_name,
                                "seatNumber": target.seat_number,
                                "date": target.window.date,
                                "startHour": target.window.start_hour,
                                "endHour": target.window.end_hour,
                            },
                        )
                        result = result.merge(AutoReservationRunResult(skipped=1))
                        continue
                    if target.key in reserved_windows or _bookings_cover_target(
                        bookings, target
                    ):
                        self._record_diagnostic_action(
                            account_id=account_id,
                            success=True,
                            message=f"{target.label} 已有覆盖预约，跳过补约",
                            payload={
                                "stage": "already_reserved",
                                "taskId": target.task_id,
                                "roomName": target.room_name,
                                "seatNumber": target.seat_number,
                                "date": target.window.date,
                                "startHour": target.window.start_hour,
                                "endHour": target.window.end_hour,
                            },
                        )
                        result = result.merge(
                            AutoReservationRunResult(already_reserved=1)
                        )
                        continue

                    success, message = self.bridge.reserve_specific_seat(
                        account,
                        room_name=target.room_name,
                        seat_number=target.seat_number,
                        date_value=target.window.date,
                        start_hour=target.window.start_hour,
                        end_hour=target.window.end_hour,
                    )
                    self._record_action(
                        account_id=account_id,
                        target=target,
                        success=success,
                        message=message,
                    )
                    self._record_monitor(
                        account_id=account_id,
                        detected_at=detected_at,
                        decision="auto_reserve" if success else "auto_reserve_failed",
                        detail=message,
                        booking_start_at=str(int(target.start_at.timestamp())),
                    )
                    self.account_service.update_status(
                        account_id,
                        last_check_at=detected_at,
                        last_status=(
                            f"自动预约成功：{message}"
                            if success
                            else f"自动预约失败：{message}"
                        ),
                    )
                    if success:
                        reserved_windows.add(target.key)
                        # 把刚抢到的座位写回已知预约列表，避免同一轮内对同一时段
                        # 重复扣预约。学校接口端真正的可见性会等下一轮 cycle 刷新。
                        bookings = list(bookings) + [
                            BookingSnapshot(
                                booking_id=f"auto-{target.task_id}-"
                                f"{target.window.date}-{target.window.start_hour}",
                                room_name=target.room_name,
                                seat_number=target.seat_number,
                                status="0",
                                start_time=int(target.start_at.timestamp()),
                                duration_seconds=int(
                                    (target.end_at - target.start_at).total_seconds()
                                ),
                            )
                        ]
                        self._replace_booking_snapshots(
                            account_id=account_id,
                            bookings=bookings,
                            detected_at=detected_at,
                        )
                        result = result.merge(AutoReservationRunResult(reserved=1))
                    else:
                        result = result.merge(AutoReservationRunResult(failed=1))
            return result

    def _active_account_ids(self) -> list[int]:
        return [
            entry.account_id
            for entry in self.account_pool_repo.list_by_pool(PoolStatus.ACTIVE)
        ]

    def _is_active_account(self, account_id: int) -> bool:
        entry = self.account_pool_repo.get_by_id(account_id)
        return entry is not None and entry.pool_status is PoolStatus.ACTIVE

    def _resolve_account(self, account: Account) -> Account:
        return self.account_resolver(account)

    def _replace_booking_snapshots(
        self,
        *,
        account_id: int,
        bookings: list[BookingSnapshot],
        detected_at: str,
    ) -> None:
        if self.booking_snapshots_repository is None:
            return
        self.booking_snapshots_repository.replace_for_account(
            account_id=account_id,
            bookings=bookings,
            refreshed_at=detected_at,
        )

    def _record_action(
        self,
        *,
        account_id: int,
        target: _WindowTarget,
        success: bool,
        message: str,
    ) -> None:
        self.action_logs_repository.create(
            account_id=account_id,
            action_type="auto_reserve",
            success=success,
            message=message,
            payload_json=safe_payload_json(
                {
                    "stage": "reserve_specific_seat",
                    "taskId": target.task_id,
                    "roomName": target.room_name,
                    "seatNumber": target.seat_number,
                    "date": target.window.date,
                    "startHour": target.window.start_hour,
                    "endHour": target.window.end_hour,
                }
            ),
        )

    def _record_diagnostic_action(
        self,
        *,
        account_id: int,
        success: bool,
        message: str,
        payload: dict[str, object],
        force: bool = False,
    ) -> None:
        if not force and not self._is_detailed_logging_enabled():
            return
        self.action_logs_repository.create(
            account_id=account_id,
            action_type="auto_reserve",
            success=success,
            message=message,
            payload_json=safe_payload_json(payload),
        )

    def _is_detailed_logging_enabled(self) -> bool:
        if self.detailed_log_enabled_provider is None:
            return False
        try:
            return bool(self.detailed_log_enabled_provider())
        except Exception:  # noqa: BLE001 - 日志开关异常不应影响补约主流程
            return False

    def _record_monitor(
        self,
        *,
        account_id: int,
        detected_at: str,
        decision: str,
        detail: str,
        booking_start_at: str | None = None,
    ) -> None:
        self.monitor_records_repository.create(
            account_id=account_id,
            booking_id="",
            booking_status="",
            booking_start_at=booking_start_at,
            detected_at=detected_at,
            decision=decision,
            detail=detail,
        )


def _build_target(task, window: CustomWindow) -> _WindowTarget | None:
    try:
        date_value = datetime.fromisoformat(window.date).date()
        start_at = datetime(
            date_value.year,
            date_value.month,
            date_value.day,
            window.start_hour,
            tzinfo=SHANGHAI_TZ,
        )
        end_at = datetime(
            date_value.year,
            date_value.month,
            date_value.day,
            window.end_hour,
            tzinfo=SHANGHAI_TZ,
        )
    except ValueError:
        return None
    if end_at <= start_at:
        return None
    return _WindowTarget(
        task_id=task.task_id,
        room_name=task.room_name,
        seat_number=task.seat_number,
        window=window,
        start_at=start_at,
        end_at=end_at,
    )


def _build_targets_from_task_windows(
    task: AutomationTask,
) -> list[_WindowTarget | None]:
    """按任务里 ``custom_windows`` 原样展开（保留旧 ``run_account_once`` 语义）。"""

    return [_build_target(task, window) for window in task.custom_windows]


def _make_rolling_target_builder(
    *,
    now: datetime,
    days_ahead: int,
) -> Callable[[AutomationTask], list[_WindowTarget | None]]:
    """生成滚动预约用的 target builder：按小时模板展开到未来 N 天。"""

    today = now.astimezone(SHANGHAI_TZ).date()
    rolling_dates = [today + timedelta(days=offset) for offset in range(days_ahead)]

    def _build(task: AutomationTask) -> list[_WindowTarget | None]:
        # 按小时模板去重；保留最早出现的 (start_hour, end_hour)，避免客户端
        # 写了一堆同样小时不同日期的窗口导致单天里重复尝试同一个时段。
        hour_templates: list[tuple[int, int]] = []
        seen: set[tuple[int, int]] = set()
        for window in task.custom_windows:
            key = (window.start_hour, window.end_hour)
            if key in seen:
                continue
            seen.add(key)
            hour_templates.append(key)
        if not hour_templates:
            return []
        targets: list[_WindowTarget | None] = []
        for target_date in rolling_dates:
            date_value = target_date.isoformat()
            for start_hour, end_hour in hour_templates:
                window = CustomWindow(
                    date=date_value,
                    start_hour=start_hour,
                    end_hour=end_hour,
                )
                targets.append(_build_target(task, window))
        return targets

    return _build


def _bookings_cover_target(
    bookings: Iterable[BookingSnapshot],
    target: _WindowTarget,
) -> bool:
    target_start = int(target.start_at.timestamp())
    target_end = int(target.end_at.timestamp())
    return any(
        booking.status in PROTECTED_BOOKING_STATUSES
        and booking.start_time <= target_start
        and booking.start_time + max(booking.duration_seconds, 0) >= target_end
        for booking in bookings
    )


def _localize(current_time: datetime) -> datetime:
    if current_time.tzinfo is None:
        return current_time.replace(tzinfo=SHANGHAI_TZ)
    return current_time.astimezone(SHANGHAI_TZ)


__all__ = [
    "AutoReservationRunResult",
    "AutoReservationService",
]
