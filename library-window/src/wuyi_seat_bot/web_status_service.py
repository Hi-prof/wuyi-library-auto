from __future__ import annotations

from collections.abc import Callable
from datetime import datetime
from typing import Any

from wuyi_seat_bot.automation_plans import AutomationPlan
from wuyi_seat_bot.scheduler import ScheduledTask
from wuyi_seat_bot.web_reservation_service import (
    booking_status_sort_key,
    build_task_status_response,
    count_automation_plan_enabled_actions,
    derive_booking_state,
    serialize_booking_status_items,
)


def build_task_statuses_response(
    account_names: tuple[str, ...],
    tasks: list[ScheduledTask],
    plans: list[AutomationPlan],
    build_account_status: Callable[
        [str, list[ScheduledTask], AutomationPlan | None], dict[str, Any]
    ],
    *,
    now: datetime | None = None,
) -> dict[str, Any]:
    plan_map = {plan.account_name: plan for plan in plans}
    current_time = now or datetime.now()
    return {
        "statuses": [
            build_account_status(account_name, tasks, plan_map.get(account_name))
            for account_name in account_names
        ],
        "serverTime": current_time.replace(microsecond=0).isoformat(),
    }


def build_account_task_status(
    *,
    account_name: str,
    state_path_exists: bool,
    tasks: list[ScheduledTask],
    plan: AutomationPlan | None,
    load_booking_list_payload: Callable[[], dict[str, Any]],
) -> dict[str, Any]:
    pending_task_count, running_task_count = count_task_statuses(
        account_name, tasks, plan
    )
    if not state_path_exists:
        return build_missing_login_status(
            account_name, pending_task_count, running_task_count
        )
    try:
        bookings = serialize_booking_status_items(load_booking_list_payload())
    except Exception as exc:  # noqa: BLE001
        return build_booking_error_status(
            account_name, str(exc), pending_task_count, running_task_count
        )
    return build_booking_status(
        account_name, bookings, pending_task_count, running_task_count
    )


def count_task_statuses(
    account_name: str, tasks: list[ScheduledTask], plan: AutomationPlan | None
) -> tuple[int, int]:
    account_tasks = [task for task in tasks if task.account_name == account_name]
    pending_task_count = sum(1 for task in account_tasks if task.status == "pending")
    running_task_count = sum(1 for task in account_tasks if task.status == "running")
    pending_task_count += count_automation_plan_enabled_actions(plan)
    return pending_task_count, running_task_count


def build_missing_login_status(
    account_name: str, pending_task_count: int, running_task_count: int
) -> dict[str, Any]:
    return build_task_status_response(
        account_name=account_name,
        state="missing-login",
        summary="未保存登录态",
        detail="请先到账号管理里刷新认证，再检测预约状态。",
        login_state_ready=False,
        pending_task_count=pending_task_count,
        running_task_count=running_task_count,
    )


def build_booking_error_status(
    account_name: str,
    detail: str,
    pending_task_count: int,
    running_task_count: int,
) -> dict[str, Any]:
    return build_task_status_response(
        account_name=account_name,
        state="error",
        summary="状态检测失败",
        detail=detail,
        login_state_ready=True,
        pending_task_count=pending_task_count,
        running_task_count=running_task_count,
    )


def build_booking_status(
    account_name: str,
    bookings: list[dict[str, Any]],
    pending_task_count: int,
    running_task_count: int,
) -> dict[str, Any]:
    if not bookings:
        return build_task_status_response(
            account_name=account_name,
            state="empty",
            summary="当前没有预约记录",
            detail="还没有读到待签到、在馆或最近结束的预约。",
            login_state_ready=True,
            pending_task_count=pending_task_count,
            running_task_count=running_task_count,
        )

    primary_booking = sorted(bookings, key=booking_status_sort_key)[0]
    detail_parts = []
    if primary_booking["startAtLabel"]:
        detail_parts.append(f"开始时间：{primary_booking['startAtLabel']}")
    if primary_booking["checkinWindowOpen"]:
        detail_parts.append("当前处于可签到时间窗")
    return build_task_status_response(
        account_name=account_name,
        state=derive_booking_state(primary_booking),
        summary=(
            f"{primary_booking['statusLabel']} · "
            f"{primary_booking['roomName']} {primary_booking['seatNumber']} 号"
        ),
        detail=" · ".join(detail_parts) or "已读取到最近的预约状态。",
        login_state_ready=True,
        pending_task_count=pending_task_count,
        running_task_count=running_task_count,
        bookings=bookings,
    )
