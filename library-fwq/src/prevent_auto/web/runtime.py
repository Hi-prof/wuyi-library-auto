from __future__ import annotations

import asyncio
import logging
from collections import defaultdict
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
import threading
import re

from fastapi import FastAPI
from wuyi_seat_bot.seat_api import SHANGHAI_TZ, describe_seat_booking_status

from prevent_auto.models import Account
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.account_login_states import AccountLoginStatesRepository
from prevent_auto.repositories.action_logs import ActionLogsRepository
from prevent_auto.repositories.app_settings import AppSettingsRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.booking_snapshots import BookingSnapshotsRepository
from prevent_auto.repositories.login_status_cache import LoginStatusCacheRepository
from prevent_auto.repositories.monitor_records import MonitorRecordsRepository
from prevent_auto.repositories.pool_audit_log import PoolAuditLogRepository
from prevent_auto.scheduler.pool_reaper_job import PoolReaperJob
from prevent_auto.services.account_password_cipher import AccountPasswordCipher
from prevent_auto.services.account_pool_service import (
    AccountPoolService,
    SqliteLoginStatusCache,
)
from prevent_auto.services.account_service import AccountService
from prevent_auto.services.auto_reservation_service import AutoReservationService
from prevent_auto.services.booking_status_service import BookingStatusService
from prevent_auto.services.bridge_to_wuyi import WuyiBridge
from prevent_auto.services.cancel_service import CancelService
from prevent_auto.services.rebook_service import RebookService
from prevent_auto.scheduler.daily_status_refresher import DailyStatusRefresher
from prevent_auto.scheduler.monitor_loop import MonitorLoop
from prevent_auto.scheduler.auto_reservation_loop import AutoReservationLoop
from prevent_auto.settings import PreventAutoSettings


logger = logging.getLogger(__name__)


POOL_REAPER_TASK_NAME = "prevent-auto-pool-reaper"
POOL_REAPER_SHUTDOWN_TIMEOUT_SECONDS = 5.0

DAILY_STATUS_REFRESHER_TICK_SECONDS = 60
# AutoReservationLoop 自己用 ``interval_seconds`` 做"到点才跑"判断，
# 这里只控制 worker 线程的轮询粒度——每分钟问一次"该跑了吗"，被
# loop 内部的间隔挡住时几乎是 no-op。
AUTO_RESERVATION_LOOP_TICK_SECONDS = 60

NORMAL_STATUS_PREFIXES = (
    "今日预约：",
    "今日最近预约：",
    "今日无预约",
    "未发现超时待签到预约",
    "登录态已刷新",
    "状态检测：正常",
    "手动预约成功：",
    "测试补约成功：",
    "自动预约成功：",
)

VISIBLE_SEAT_DISPLAY_STATUSES = {"0", "1", "2", "8"}
AUTO_RESERVATION_DETAILED_LOG_KEY = "auto_reservation_detailed_log_enabled"

DASHBOARD_LOGIN_ISSUE_KEYWORDS = (
    "登录失败",
    "刷新登录失败",
    "登录态失效",
    "登录态过期",
    "账号密码错误",
    "密码错误",
    "认证失败",
    "凭据",
)

DASHBOARD_NORMAL_STATUS_PREFIXES = NORMAL_STATUS_PREFIXES + (
    "今日无预约",
)


@dataclass(frozen=True)
class AppServices:
    account_service: AccountService
    action_logs_repository: ActionLogsRepository
    monitor_records_repository: MonitorRecordsRepository
    bridge: WuyiBridge
    monitor_loop: MonitorLoop
    rebook_service: RebookService
    auto_reservation_service: AutoReservationService
    daily_status_refresher: DailyStatusRefresher
    auto_reservation_loop: AutoReservationLoop
    booking_snapshots_repository: BookingSnapshotsRepository | None = None
    # account-pool-tri-sync 池审计仓库，供 /logs 页面「池审计」Tab 复用
    # （Requirement 10.5）。生产 / 测试路径都注入该字段，因为 ``pool_audit_log``
    # 表本身在 ``initialize_database`` 启动钩子里无条件建好。
    pool_audit_log_repository: PoolAuditLogRepository | None = None
    # account-pool-tri-sync 三池领域服务；``ACCOUNT_POOL_SECRET_KEY`` 缺失时为 ``None``
    # （仅在测试 / 开发场景成立，生产路径会在更早处校验密钥存在）。
    account_pool_service: AccountPoolService | None = None
    account_login_states_repository: AccountLoginStatesRepository | None = None
    app_settings_repository: AppSettingsRepository | None = None


def build_services(settings: PreventAutoSettings) -> AppServices:
    account_service = AccountService(settings.database_path)
    action_logs_repository = ActionLogsRepository(settings.database_path)
    app_settings_repository = AppSettingsRepository(settings.database_path)
    monitor_records_repository = MonitorRecordsRepository(settings.database_path)
    booking_snapshots_repository = BookingSnapshotsRepository(settings.database_path)
    automation_tasks_repository = AutomationTasksRepository(settings.database_path)
    account_pool_repository = AccountPoolRepository(settings.database_path)
    account_login_states_repository = AccountLoginStatesRepository(settings.database_path)
    pool_audit_log_repository = PoolAuditLogRepository(settings.database_path)
    rebook_service = RebookService(settings.database_path)
    bridge = WuyiBridge(settings.package_root)
    booking_status_service = BookingStatusService(bridge)
    cancel_service = CancelService(bridge, settings.database_path)
    account_locks: dict[int, threading.Lock] = defaultdict(threading.Lock)
    account_pool_service = build_account_pool_service(settings)
    account_resolver = build_account_resolver(account_pool_service)
    monitor_loop = MonitorLoop(
        account_service=account_service,
        booking_status_service=booking_status_service,
        cancel_service=cancel_service,
        now_provider=lambda: datetime.now().astimezone(),
        monitor_records_repository=monitor_records_repository,
        booking_snapshots_repository=booking_snapshots_repository,
        account_locks=account_locks,
        account_resolver=account_resolver,
    )
    auto_reservation_service = AutoReservationService(
        account_service=account_service,
        booking_status_service=booking_status_service,
        bridge=bridge,
        automation_tasks_repo=automation_tasks_repository,
        account_pool_repo=account_pool_repository,
        monitor_records_repository=monitor_records_repository,
        action_logs_repository=action_logs_repository,
        booking_snapshots_repository=booking_snapshots_repository,
        account_locks=account_locks,
        now_provider=lambda: datetime.now().astimezone(),
        detailed_log_enabled_provider=lambda: app_settings_repository.get_bool(
            AUTO_RESERVATION_DETAILED_LOG_KEY
        ),
        account_resolver=account_resolver,
        rolling_days_ahead=settings.rolling_reservation_days_ahead,
    )
    daily_status_refresher = DailyStatusRefresher(
        account_service=account_service,
        monitor_loop=monitor_loop,
        auto_reservation_service=auto_reservation_service,
        refresh_time=settings.daily_status_refresh_time,
    )
    auto_reservation_loop = AutoReservationLoop(
        monitor_loop=monitor_loop,
        auto_reservation_service=auto_reservation_service,
        interval_seconds=settings.auto_reservation_interval_seconds,
        rolling_days_ahead=settings.rolling_reservation_days_ahead,
    )
    return AppServices(
        account_service=account_service,
        action_logs_repository=action_logs_repository,
        monitor_records_repository=monitor_records_repository,
        bridge=bridge,
        monitor_loop=monitor_loop,
        rebook_service=rebook_service,
        auto_reservation_service=auto_reservation_service,
        daily_status_refresher=daily_status_refresher,
        auto_reservation_loop=auto_reservation_loop,
        booking_snapshots_repository=booking_snapshots_repository,
        pool_audit_log_repository=pool_audit_log_repository,
        account_pool_service=account_pool_service,
        account_login_states_repository=account_login_states_repository,
        app_settings_repository=app_settings_repository,
    )


def build_account_resolver(
    account_pool_service: AccountPoolService | None,
):
    def _resolve(account: Account) -> Account:
        password = account.password
        if not password and account_pool_service is not None:
            password = account_pool_service.get_login_password(account.id)
        seat_url = account.seat_url or account.login_url
        if password == account.password and seat_url == account.seat_url:
            return account
        return replace(account, password=password, seat_url=seat_url)

    return _resolve


def build_account_pool_service(
    settings: PreventAutoSettings,
) -> AccountPoolService | None:
    """根据 ``settings`` 装配 :class:`AccountPoolService`。

    ``ACCOUNT_POOL_SECRET_KEY`` 缺失时返回 ``None``：``AccountPoolService`` 强依赖
    AES-GCM 加解密，没有密钥就无法装配，与
    :func:`prevent_auto.database._ensure_account_pool_columns` 在测试环境退化为
    「不做密码加密」的口径保持一致。生产路径由更早的环境校验保障密钥存在。

    本函数同时把 :class:`SqliteLoginStatusCache` 作为 ``login_status_cache``
    注入，让号池管理页的「登录态」列在服务重启后保持一致。
    """

    if not settings.account_pool_secret_key:
        return None
    cipher = AccountPasswordCipher(settings.account_pool_secret_key)
    login_status_cache = SqliteLoginStatusCache(
        LoginStatusCacheRepository(settings.database_path),
    )
    return AccountPoolService(
        settings.database_path,
        cipher=cipher,
        login_status_cache=login_status_cache,
    )


def next_monitor_run_at(
    current_time: datetime,
    *,
    interval_seconds: int = 60,
) -> datetime:
    normalized = current_time.astimezone(SHANGHAI_TZ)
    return normalized.replace(microsecond=0) + timedelta(
        seconds=_normalize_poll_interval_seconds(interval_seconds)
    )


def build_dashboard_summary(
    services: AppServices,
    settings: PreventAutoSettings | None = None,
) -> dict[str, object]:
    accounts = services.account_service.list_accounts()
    logs = services.action_logs_repository.list_recent(limit=200)
    monitor_records = services.monitor_records_repository.list_recent(limit=200)
    latest_logs_by_account = _index_first(logs, key=lambda item: item.account_id)
    latest_monitor_by_account = _index_first(
        monitor_records,
        key=lambda item: int(item["account_id"]),
    )
    account_snapshots = [
        _build_account_snapshot(
            account,
            latest_monitor=latest_monitor_by_account.get(account.id),
            latest_log=latest_logs_by_account.get(account.id),
        )
        for account in accounts
    ]
    summary: dict[str, object] = {
        "enabledCount": sum(1 for account in accounts if account.enabled),
        "accountCount": len(accounts),
        "checkedInTodayCount": sum(
            1 for snapshot in account_snapshots if snapshot["isCheckedInToday"]
        ),
        "notReservedTodayCount": sum(
            1 for snapshot in account_snapshots if snapshot["isNotReservedToday"]
        ),
        "cancelCount": sum(1 for log in logs if log.action_type == "cancel"),
        "attentionAccounts": [
            account
            for account in accounts
            if _status_needs_attention(account.last_status)
        ],
        "accountSnapshots": account_snapshots,
    }
    if settings is not None:
        summary["runtime"] = build_runtime_summary(settings)
    return summary


def build_dashboard_health(summary: dict[str, object]) -> dict[str, object]:
    snapshots = [
        item
        for item in summary.get("accountSnapshots", [])
        if isinstance(item, dict)
    ]
    attention_items = [
        item
        for item in (
            _classify_dashboard_health_snapshot(snapshot)
            for snapshot in snapshots
        )
        if item is not None
    ]
    attention_items.sort(key=lambda item: (item["priority"], item["studentId"]))
    for item in attention_items:
        item.pop("priority", None)

    unchecked_count = sum(
        1 for snapshot in snapshots if _is_unchecked_dashboard_snapshot(snapshot)
    )
    login_issue_count = sum(
        1 for item in attention_items if item["issueType"] == "login"
    )
    account_count = int(summary.get("accountCount") or len(snapshots))
    if account_count == 0:
        overall_state = "unchecked"
        overall_label = "暂无账号"
        overall_tone = "muted"
    elif attention_items and all(
        item["issueType"] == "unchecked" for item in attention_items
    ):
        overall_state = "unchecked"
        overall_label = "尚未检测"
        overall_tone = "muted"
    elif attention_items:
        overall_state = "attention"
        overall_label = "需要处理"
        overall_tone = "warning"
    elif unchecked_count == account_count:
        overall_state = "unchecked"
        overall_label = "尚未检测"
        overall_tone = "muted"
    else:
        overall_state = "healthy"
        overall_label = "运行正常"
        overall_tone = "success"

    return {
        "overallState": overall_state,
        "overallLabel": overall_label,
        "overallTone": overall_tone,
        "counters": {
            "accountCount": account_count,
            "checkedInTodayCount": int(summary.get("checkedInTodayCount") or 0),
            "notReservedTodayCount": int(summary.get("notReservedTodayCount") or 0),
            "attentionCount": len(attention_items),
            "loginIssueCount": login_issue_count,
            "uncheckedCount": unchecked_count,
        },
        "attentionItems": attention_items,
    }


def _classify_dashboard_health_snapshot(
    snapshot: dict[str, object],
) -> dict[str, object] | None:
    status_text = str(snapshot.get("currentStatus", "") or "").strip()
    last_check_label = str(snapshot.get("lastCheckLabel", "") or "").strip()
    reason = status_text or "尚未检测"
    base_item = {
        "accountId": int(snapshot.get("id") or 0),
        "studentId": str(snapshot.get("studentId", "") or "").strip(),
        "accountName": str(snapshot.get("name", "") or "").strip(),
        "reason": reason,
        "lastCheckLabel": last_check_label or "尚未检测",
    }
    if _is_login_issue_status(status_text):
        return {
            **base_item,
            "priority": 0,
            "issueType": "login",
            "issueLabel": "登录态异常",
            "tone": "danger",
            "recommendedActions": ("刷新登录态", "查看详情"),
        }
    if _is_unchecked_dashboard_snapshot(snapshot):
        return {
            **base_item,
            "priority": 3,
            "issueType": "unchecked",
            "issueLabel": "尚未检测",
            "tone": "muted",
            "recommendedActions": ("立即检测", "查看详情"),
        }
    if _is_abnormal_dashboard_status(status_text):
        return {
            **base_item,
            "priority": 1,
            "issueType": "check_failed",
            "issueLabel": "检测失败",
            "tone": "danger",
            "recommendedActions": ("立即检测", "查看详情"),
        }
    if bool(snapshot.get("isNotReservedToday")):
        return {
            **base_item,
            "priority": 2,
            "issueType": "not_reserved",
            "issueLabel": "未预约",
            "tone": "warning",
            "recommendedActions": ("立即检测", "查看详情"),
        }
    return None


def _is_login_issue_status(status_text: str) -> bool:
    return any(keyword in status_text for keyword in DASHBOARD_LOGIN_ISSUE_KEYWORDS)


def _is_abnormal_dashboard_status(status_text: str) -> bool:
    text = status_text.strip()
    if not text:
        return False
    return not any(
        text.startswith(prefix) for prefix in DASHBOARD_NORMAL_STATUS_PREFIXES
    )


def _is_unchecked_dashboard_snapshot(snapshot: dict[str, object]) -> bool:
    last_check_label = str(snapshot.get("lastCheckLabel", "") or "").strip()
    status_text = str(snapshot.get("currentStatus", "") or "").strip()
    return last_check_label in {"", "尚未检测"} or status_text in {"", "尚未检测"}


def build_runtime_summary(
    settings: PreventAutoSettings,
    *,
    current_time: datetime | None = None,
) -> dict[str, object]:
    refresh_time_label = settings.daily_status_refresh_time.strftime("%H:%M")
    interval_label = _format_duration_seconds(
        settings.auto_reservation_interval_seconds
    )
    return {
        "monitorModeLabel": "手动检查",
        "configuredMonitorIntervalSeconds": settings.monitor_interval_seconds,
        "logRetentionDays": settings.log_retention_days,
        "logRetentionLabel": f"{settings.log_retention_days} 天",
        "dailyStatusRefreshTime": refresh_time_label,
        "dailyStatusRefreshLabel": f"每天 {refresh_time_label} 自动刷新账号状态",
        "autoReservationIntervalSeconds": settings.auto_reservation_interval_seconds,
        "autoReservationIntervalLabel": interval_label,
        "rollingReservationDaysAhead": settings.rolling_reservation_days_ahead,
        "autoReservationLoopLabel": (
            f"每 {interval_label} 自动检查并补齐未来 "
            f"{settings.rolling_reservation_days_ahead} 天的预约"
        ),
    }


def _build_account_snapshot(
    account,
    *,
    latest_monitor: dict[str, object] | None,
    latest_log,
) -> dict[str, object]:
    badge_label, badge_tone = _build_badge(account)
    booking_status = _read_booking_status(account, latest_monitor)
    return {
        "id": account.id,
        "name": account.name,
        "studentId": account.student_id,
        "enabled": account.enabled,
        "enabledLabel": "启用中" if account.enabled else "已停用",
        "badgeLabel": badge_label,
        "badgeTone": badge_tone,
        "currentStatus": account.last_status or "尚未检测",
        "bookingSummary": _build_booking_summary(account, latest_monitor),
        "bookingTimeLabel": _build_booking_time_label(account, latest_monitor),
        "bookingStatusLabel": _build_booking_status_label(account, latest_monitor),
        "bookingStatusCode": booking_status,
        "roomName": account.last_detected_room_name.strip(),
        "seatNumber": account.last_detected_seat_number.strip(),
        "isCheckedInToday": booking_status in {"1", "2"},
        "isNotReservedToday": _is_not_reserved_today(account, latest_monitor),
        "cancelSummary": _build_cancel_summary(latest_log=latest_log),
        "seatLabel": _build_booking_seat_label(
            account,
        ),
        "lastCheckLabel": _format_iso_datetime(
            account.last_check_at, default="尚未检测"
        ),
    }


def _build_badge(account) -> tuple[str, str]:
    if not account.enabled:
        return "已停用", "muted"
    if not account.last_check_at:
        return "未检测", "muted"
    if _status_needs_attention(account.last_status):
        return "需关注", "warning"
    return "巡检正常", "success"


def _build_booking_summary(account, latest_monitor: dict[str, object] | None) -> str:
    if latest_monitor is None:
        if _looks_like_booking_summary(account.last_status):
            return account.last_status
        if account.last_status == "未发现超时待签到预约":
            return "今天未发现超时待签到预约"
        return "暂无当天预约记录"

    raw_booking_status = str(latest_monitor.get("booking_status", "")).strip()
    start_label = _format_booking_start(latest_monitor.get("booking_start_at"))
    detail = str(latest_monitor.get("detail", "")).strip()
    summary_parts = []
    if raw_booking_status:
        summary_parts.append(describe_seat_booking_status(raw_booking_status))
    if start_label:
        summary_parts.append(start_label)
    if detail:
        summary_parts.append(detail)
    if summary_parts:
        return " · ".join(summary_parts)
    return "暂无当天预约记录"


def _build_cancel_summary(*, latest_log) -> str:
    if latest_log is not None and latest_log.action_type == "cancel":
        return f"最近取消 · {latest_log.message}"
    return "今天暂无取消动作"


def _build_booking_time_label(account, latest_monitor: dict[str, object] | None) -> str:
    if latest_monitor is not None:
        return _format_booking_start(latest_monitor.get("booking_start_at")) or "无时间"
    return _format_booking_start(account.last_detected_booking_start_at) or "无时间"


def _build_booking_status_label(account, latest_monitor: dict[str, object] | None) -> str:
    raw_status = _read_booking_status(account, latest_monitor)
    if raw_status:
        return describe_seat_booking_status(raw_status)
    if account.last_status.strip() == "今日无预约":
        return "无预约"
    return "未知"


def _read_booking_status(account, latest_monitor: dict[str, object] | None) -> str:
    if latest_monitor is not None:
        raw_status = str(latest_monitor.get("booking_status", "")).strip()
        if raw_status:
            return raw_status
    return account.last_detected_booking_status.strip()


def _is_not_reserved_today(account, latest_monitor: dict[str, object] | None) -> bool:
    if latest_monitor is not None:
        return not str(latest_monitor.get("booking_status", "")).strip()
    return account.last_status.strip() in {
        "今日无预约",
        "未发现超时待签到预约",
    }


def _build_booking_seat_label(account) -> str:
    detected_room_name = account.last_detected_room_name.strip()
    detected_seat_number = account.last_detected_seat_number.strip()
    if detected_room_name and detected_seat_number:
        return _format_room_seat_label(detected_room_name, detected_seat_number)

    detected_seat_label = _extract_seat_label_from_text(account.last_status)
    if detected_seat_label:
        return detected_seat_label

    return "未识别到预约座位"


def _status_needs_attention(status_text: str) -> bool:
    text = status_text.strip()
    if not text:
        return False
    return not any(text.startswith(prefix) for prefix in NORMAL_STATUS_PREFIXES)


def _looks_like_booking_summary(status_text: str) -> bool:
    text = status_text.strip()
    return (
        text.startswith("今日预约：")
        or text.startswith("今日最近预约：")
        or text == "今日无预约"
    )


def _extract_booking_time_range(text: str) -> str:
    match = re.search(r"\b(\d{1,2}:\d{2}-\d{1,2}:\d{2})\b", text or "")
    if match is None:
        return ""
    return match.group(1)


def _extract_seat_label_from_text(text: str) -> str | None:
    room_seat = _extract_room_seat_from_text(text)
    if room_seat is None:
        return None
    return _format_room_seat_label(*room_seat)


def _extract_room_seat_from_text(text: str) -> tuple[str, str] | None:
    match = re.search(r"(?:：|^)(?P<room>.+?) (?P<seat>\S+) 号座位", text.strip())
    if match is None:
        return None
    return match.group("room").strip(), match.group("seat").strip()


def _format_room_seat_label(room_name: str, seat_number: str) -> str:
    return f"{room_name} · {seat_number} 号座位"


def _normalize_sort_number(value: object) -> tuple[int, object]:
    text = str(value or "").strip()
    if text.isdigit():
        return (0, int(text))
    return (1, text)


def _index_first(items, *, key):
    result = {}
    for item in items:
        current_key = key(item)
        if current_key not in result:
            result[current_key] = item
    return result


def _format_iso_datetime(value: str | None, *, default: str) -> str:
    if not value:
        return default
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return value
    localized = parsed.astimezone(SHANGHAI_TZ)
    return localized.strftime("%Y年%m月%d日%H时%M分%S秒")


def _format_booking_start(value: object) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    try:
        parsed = datetime.fromtimestamp(int(text), SHANGHAI_TZ)
    except (OverflowError, TypeError, ValueError):
        return ""
    return parsed.strftime("%Y年%m月%d日%H时%M分%S秒")


def start_background_workers(app: FastAPI) -> None:
    app.state.stop_event = threading.Event()
    settings = app.state.settings
    services = app.state.services
    cleanup_runtime_data(services, settings)
    app.state.threads = [
        threading.Thread(
            target=_worker_loop,
            name="prevent-auto-cleanup",
            args=(
                app.state.stop_event,
                24 * 60 * 60,
                lambda: cleanup_runtime_data(services, settings),
            ),
            daemon=True,
        ),
        threading.Thread(
            target=_worker_loop,
            name="prevent-auto-daily-status-refresh",
            args=(
                app.state.stop_event,
                DAILY_STATUS_REFRESHER_TICK_SECONDS,
                lambda: services.daily_status_refresher.run_due_refresh_once(
                    datetime.now().astimezone()
                ),
            ),
            daemon=True,
        ),
        threading.Thread(
            target=_worker_loop,
            name="prevent-auto-rolling-reservation",
            args=(
                app.state.stop_event,
                AUTO_RESERVATION_LOOP_TICK_SECONDS,
                lambda: services.auto_reservation_loop.run_due_once(
                    datetime.now().astimezone()
                ),
            ),
            daemon=True,
        ),
    ]
    for thread in app.state.threads:
        thread.start()


def stop_background_workers(app: FastAPI) -> None:
    app.state.stop_event.set()
    for thread in app.state.threads:
        thread.join(timeout=3)


def build_pool_reaper_job(
    settings: PreventAutoSettings,
    *,
    account_pool_service: AccountPoolService | None = None,
) -> PoolReaperJob | None:
    """根据 ``settings`` 装配 :class:`PoolReaperJob`。

    ``ACCOUNT_POOL_SECRET_KEY`` 缺失时返回 ``None``：``AccountPoolService`` 强依赖
    AES-GCM 加解密，没有密钥就无法装配，与 ``database._ensure_account_pool_columns``
    在测试环境退化为「不做密码加密」的口径保持一致。生产环境由
    ``_validate_auth_override`` 等更早期校验在启动时拒绝默认密钥之外的部署，
    这里再兜底一次让单元测试可以沿用 ``initialize_database(path)`` 的简短初始化。

    可选参数 ``account_pool_service`` 用于复用 ``app.state.services.account_pool_service``，
    避免一个 web 进程内同时维护两份 ``AccountPoolService`` 实例及其内部
    ``Login_Status_Cache``。
    """

    if not settings.account_pool_secret_key:
        logger.info(
            "ACCOUNT_POOL_SECRET_KEY 未配置，跳过 PoolReaperJob 启动；"
            "Suspended_Pool 到期回收将不会自动执行"
        )
        return None

    service = account_pool_service
    if service is None:
        cipher = AccountPasswordCipher(settings.account_pool_secret_key)
        login_status_cache = SqliteLoginStatusCache(
            LoginStatusCacheRepository(settings.database_path),
        )
        service = AccountPoolService(
            settings.database_path,
            cipher=cipher,
            login_status_cache=login_status_cache,
        )
    return PoolReaperJob(
        account_pool_repo=AccountPoolRepository(settings.database_path),
        audit_repo=PoolAuditLogRepository(settings.database_path),
        account_pool_service=service,
        interval_seconds=settings.account_pool_reaper_interval_seconds,
    )


async def start_pool_reaper_async(app: FastAPI) -> None:
    """在 FastAPI lifespan 启动阶段拉起 :class:`PoolReaperJob`。

    * 依据 ``settings.account_pool_reaper_interval_seconds`` 装配 reaper；
      ``ACCOUNT_POOL_SECRET_KEY`` 缺失时直接跳过（仅测试场景）。
    * 用 ``asyncio.create_task`` 拉起 ``run_forever``；reaper 在
      :meth:`PoolReaperJob.run_forever` 内启动后立即触发首次 tick，
      自然处理服务停机期间累积的到期项（Requirement 3.5）。
    * 同时在 ``app.state`` 上挂 ``pool_reaper_stop_event`` /
      ``pool_reaper_task`` 便于 shutdown 与测试断言。
    """

    settings: PreventAutoSettings = app.state.settings
    services: AppServices = app.state.services
    job = build_pool_reaper_job(
        settings,
        account_pool_service=services.account_pool_service,
    )
    if job is None:
        app.state.pool_reaper_job = None
        app.state.pool_reaper_stop_event = None
        app.state.pool_reaper_task = None
        return

    stop_event = asyncio.Event()
    app.state.pool_reaper_job = job
    app.state.pool_reaper_stop_event = stop_event
    app.state.pool_reaper_task = asyncio.create_task(
        job.run_forever(stop_event),
        name=POOL_REAPER_TASK_NAME,
    )


async def stop_pool_reaper_async(app: FastAPI) -> None:
    """在 FastAPI lifespan shutdown 阶段优雅停止 :class:`PoolReaperJob`。

    * 设置 ``stop_event`` 让正在 ``asyncio.wait_for`` 等待的 reaper 立即退出。
    * ``await`` 任务结束，给当前 tick 留出落库时间；超过
      :data:`POOL_REAPER_SHUTDOWN_TIMEOUT_SECONDS` 仍未结束则取消并吞掉
      ``CancelledError``，避免阻塞 web 进程退出。
    """

    stop_event: asyncio.Event | None = getattr(
        app.state, "pool_reaper_stop_event", None
    )
    task: asyncio.Task[None] | None = getattr(
        app.state, "pool_reaper_task", None
    )
    if stop_event is None or task is None:
        return

    stop_event.set()
    try:
        await asyncio.wait_for(task, timeout=POOL_REAPER_SHUTDOWN_TIMEOUT_SECONDS)
    except asyncio.TimeoutError:
        logger.warning(
            "PoolReaperJob 在 %.1fs 内未退出，强制取消任务",
            POOL_REAPER_SHUTDOWN_TIMEOUT_SECONDS,
        )
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):  # noqa: BLE001
            pass
    except Exception:  # noqa: BLE001
        logger.exception("PoolReaperJob 退出时抛出异常")
    finally:
        app.state.pool_reaper_task = None
        app.state.pool_reaper_stop_event = None


def _worker_loop(stop_event: threading.Event, interval_seconds: int, action) -> None:
    while not stop_event.is_set():
        action()
        if stop_event.wait(interval_seconds):
            return


def _normalize_poll_interval_seconds(interval_seconds: int) -> int:
    return max(15, min(interval_seconds, 60))


def _format_duration_seconds(seconds: int) -> str:
    if seconds < 60:
        return f"{seconds} 秒"
    if seconds % 60 == 0:
        minutes = seconds // 60
        return f"{minutes} 分钟"
    minutes, remainder = divmod(seconds, 60)
    return f"{minutes} 分 {remainder} 秒"


def cleanup_runtime_data(
    services: AppServices,
    settings: PreventAutoSettings,
    *,
    current_time: datetime | None = None,
) -> dict[str, int]:
    cutoff = (
        current_time or datetime.now()
    ).replace(microsecond=0) - timedelta(days=settings.log_retention_days)
    cutoff_iso = cutoff.isoformat()
    return {
        "actionLogs": services.action_logs_repository.delete_older_than(cutoff_iso),
        "monitorRecords": services.monitor_records_repository.delete_older_than(
            cutoff_iso
        ),
        "rebookJobs": services.rebook_service.jobs_repository.delete_finished_older_than(
            cutoff_iso
        ),
    }
