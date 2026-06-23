from __future__ import annotations

import random
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from http import HTTPStatus
from typing import Any

from wuyi_seat_bot.automation_plans import (
    AutomationActionResult,
    AutomationPlan,
    build_automation_plan,
)
from wuyi_seat_bot.seat_api import (
    SHANGHAI_TZ,
    SearchFilters,
    build_date_options,
    build_duration_options,
)
from wuyi_seat_bot.web_errors import ApiRequestError
from wuyi_seat_bot.web_payload import (
    read_bool_field,
    read_int_field,
    read_optional_int_field,
    read_optional_text_field,
    read_required_text_field,
    read_time_field,
)
from wuyi_seat_bot.web_reservation_service import (
    extract_active_booking_dates,
    serialize_automation_plan,
    validate_filters,
)

CHECKIN_SAME_DAY_RETRY_DELAY_MINUTES = 5
CHECKIN_SAME_DAY_RETRY_MESSAGES = (
    "当前还不在签到时间窗内",
    "蓝牙扫描未命中预约房间设备",
    "蓝牙扫描失败",
    "签到接口执行异常",
    "请求太频繁",
    "稍后再试",
)


@dataclass(frozen=True)
class AutomationPlanRequest:
    raw_plan_id: str | None
    account_name: str
    seat_url: str
    selected_date: str
    start_hour: int
    duration_hours: int
    reserve_enabled: bool
    checkin_enabled: bool
    checkout_enabled: bool
    continuous_reserve: bool
    reserve_time: str
    checkin_time: str
    checkout_time: str
    reserve_check_interval_minutes: int
    room_id: str
    room_name: str
    seat_number: str


def build_automation_target_dates(
    plan: AutomationPlan,
    search_page_payload: dict[str, Any],
) -> tuple[str, ...]:
    available_dates = [
        option["value"] for option in build_date_options(search_page_payload)
    ]
    if not available_dates:
        return ()

    if plan.continuous_reserve:
        if plan.selected_date > available_dates[-1]:
            return ()
        target_index = next(
            (
                index
                for index, value in enumerate(available_dates)
                if value >= plan.selected_date
            ),
            0,
        )
        return tuple(available_dates[target_index:])
    if plan.selected_date not in available_dates:
        return ()
    return (plan.selected_date,)


def build_automation_reserve_filters(
    plan: AutomationPlan,
    search_page_payload: dict[str, Any],
    target_date: str,
    now: datetime,
) -> SearchFilters | None:
    start_hour = plan.start_hour
    duration_hours = plan.duration_hours

    current_time = now.astimezone(SHANGHAI_TZ) if now.tzinfo is not None else now
    if target_date == current_time.date().isoformat():
        current_day_window = build_current_day_reserve_window(
            search_page_payload, current_time
        )
        if current_day_window is None:
            return None
        start_hour, duration_hours = current_day_window
    elif plan.continuous_reserve and target_date != plan.selected_date:
        default_filters = build_automation_future_default_filters(
            search_page_payload,
            target_date,
        )
        if default_filters is None:
            return None
        start_hour = default_filters.start_hour
        duration_hours = default_filters.duration_hours

    filters = SearchFilters(
        account_name=plan.account_name,
        seat_url=plan.seat_url,
        date_value=target_date,
        start_hour=start_hour,
        duration_hours=duration_hours,
        people_count=1,
    )
    validate_filters(search_page_payload, filters)
    return filters


def build_current_day_reserve_window(
    search_page_payload: dict[str, Any],
    current_time: datetime,
) -> tuple[int, int] | None:
    range_data = search_page_payload["data"]["range"]
    min_begin = int(range_data["minBeginTime"])
    max_end = int(range_data["maxEndTime"])
    min_duration = int(range_data["min_duration"])
    start_hour = max(current_time.hour, min_begin)
    if start_hour > max_end - min_duration:
        return None

    available_durations = build_duration_options(
        search_page_payload, start_hour=start_hour
    )
    if not available_durations:
        return None
    duration_hours = min(
        max_end - start_hour,
        max(int(option["value"]) for option in available_durations),
    )
    return start_hour, duration_hours


def build_manual_reserve_filters(
    plan: AutomationPlan,
    search_page_payload: dict[str, Any],
    target_date: str,
    now: datetime,
) -> SearchFilters | None:
    current_time = now.astimezone(SHANGHAI_TZ) if now.tzinfo is not None else now
    if target_date == current_time.date().isoformat():
        current_day_window = build_current_day_reserve_window(
            search_page_payload, current_time
        )
        if current_day_window is None:
            return None
        start_hour, duration_hours = current_day_window
    elif plan.continuous_reserve and target_date != plan.selected_date:
        default_filters = build_automation_future_default_filters(
            search_page_payload,
            target_date,
        )
        if default_filters is None:
            return None
        start_hour = default_filters.start_hour
        duration_hours = default_filters.duration_hours
    else:
        start_hour = plan.start_hour
        duration_hours = plan.duration_hours

    filters = SearchFilters(
        account_name=plan.account_name,
        seat_url=plan.seat_url,
        date_value=target_date,
        start_hour=start_hour,
        duration_hours=duration_hours,
        people_count=1,
    )
    validate_filters(search_page_payload, filters)
    return filters


def build_automation_future_default_filters(
    search_page_payload: dict[str, Any],
    target_date: str,
) -> SearchFilters | None:
    range_data = search_page_payload["data"]["range"]
    default_data = search_page_payload["data"]["default"]
    start_hour = int(range_data["minBeginTime"])
    available_durations = build_duration_options(
        search_page_payload, start_hour=start_hour
    )
    if not available_durations:
        return None

    return SearchFilters(
        date_value=target_date,
        start_hour=start_hour,
        duration_hours=max(int(option["value"]) for option in available_durations),
        people_count=int(default_data["num"]),
    )


def is_rate_limit_message(message: str) -> bool:
    normalized_message = str(message or "").strip()
    return "请求太频繁" in normalized_message or "稍后再试" in normalized_message


def should_retry_checkin_same_day(message: str) -> bool:
    normalized_message = str(message or "").strip()
    return any(
        retry_message in normalized_message
        for retry_message in CHECKIN_SAME_DAY_RETRY_MESSAGES
    )


def wait_automation_reserve_gap(*, reason: str) -> None:
    delay_seconds = (
        random.uniform(18.0, 26.0)
        if reason == "retry-after-rate-limit"
        else random.uniform(2.5, 5.5)
    )
    time.sleep(delay_seconds)


def execute_automation_action(
    *,
    plan: AutomationPlan,
    action: str,
    now: datetime,
    execute_reserve: Callable[[AutomationPlan, datetime], AutomationActionResult],
    run_checkin: Callable[[str], Any],
    run_checkout: Callable[[str], str],
) -> AutomationActionResult:
    if action == "reserve":
        return execute_reserve(plan, now)
    if action == "checkin":
        result = run_checkin(plan.account_name)
        retry_delay_minutes = None
        if not result.success and should_retry_checkin_same_day(result.message):
            retry_delay_minutes = CHECKIN_SAME_DAY_RETRY_DELAY_MINUTES
        return AutomationActionResult(
            message=result.message,
            retry_delay_minutes=retry_delay_minutes,
        )
    if action == "checkout":
        return AutomationActionResult(message=run_checkout(plan.account_name))
    raise RuntimeError(f"不支持的自动任务动作：{action}")


def execute_automation_reserve(
    *,
    plan: AutomationPlan,
    now: datetime,
    search_page_payload: dict[str, Any],
    booking_list_payload: dict[str, Any],
    reserve_once: Callable[[SearchFilters], str | None],
    wait_reserve_gap: Callable[[str], None],
) -> AutomationActionResult:
    return _execute_reserve_loop(
        plan=plan,
        now=now,
        search_page_payload=search_page_payload,
        booking_list_payload=booking_list_payload,
        reserve_once=reserve_once,
        wait_reserve_gap=wait_reserve_gap,
        build_filters=build_automation_reserve_filters,
        unavailable_message="当前已没有足够的可预约时段",
    )


def execute_manual_reserve_check(
    *,
    plan: AutomationPlan,
    now: datetime,
    search_page_payload: dict[str, Any],
    booking_list_payload: dict[str, Any],
    reserve_once: Callable[[SearchFilters], str | None],
    wait_reserve_gap: Callable[[str], None],
) -> AutomationActionResult:
    return _execute_reserve_loop(
        plan=plan,
        now=now,
        search_page_payload=search_page_payload,
        booking_list_payload=booking_list_payload,
        reserve_once=reserve_once,
        wait_reserve_gap=wait_reserve_gap,
        build_filters=build_manual_reserve_filters,
        unavailable_message="当前时间已超过当天可预约窗口",
    )


def _execute_reserve_loop(
    *,
    plan: AutomationPlan,
    now: datetime,
    search_page_payload: dict[str, Any],
    booking_list_payload: dict[str, Any],
    reserve_once: Callable[[SearchFilters], str | None],
    wait_reserve_gap: Callable[[str], None],
    build_filters: Callable[
        [AutomationPlan, dict[str, Any], str, datetime], SearchFilters | None
    ],
    unavailable_message: str,
) -> AutomationActionResult:
    target_dates = build_automation_target_dates(plan, search_page_payload)
    if not target_dates:
        return AutomationActionResult(message="当前入口没有可维护的预约日期")

    booked_dates = extract_active_booking_dates(booking_list_payload).intersection(
        target_dates
    )
    created_dates: list[str] = []
    skipped_dates = [date_value for date_value in target_dates if date_value in booked_dates]
    failed_messages: list[str] = []
    pending_target_dates = [
        date_value for date_value in target_dates if date_value not in booked_dates
    ]

    for index, target_date in enumerate(pending_target_dates):
        if index > 0:
            wait_reserve_gap("between-attempts")
        filters = build_filters(plan, search_page_payload, target_date, now)
        if filters is None:
            failed_messages.append(f"{target_date} {unavailable_message}")
            continue

        failure_message = reserve_once(filters)
        if failure_message:
            failed_messages.append(f"{target_date} {failure_message}")
            continue

        created_dates.append(target_date)
        booked_dates.add(target_date)

    return build_automation_reserve_result(
        target_dates=target_dates,
        booked_dates=booked_dates,
        created_dates=created_dates,
        skipped_dates=skipped_dates,
        failed_messages=failed_messages,
    )


def build_automation_reserve_result(
    *,
    target_dates: tuple[str, ...],
    booked_dates: set[str],
    created_dates: list[str],
    skipped_dates: list[str],
    failed_messages: list[str],
) -> AutomationActionResult:
    if created_dates:
        message_parts = [f"已补订 {len(created_dates)} 天：{'、'.join(created_dates)}"]
    elif skipped_dates and not failed_messages:
        message_parts = [f"目标日期都已有预约：{'、'.join(skipped_dates)}"]
    else:
        message_parts = ["自动预约本轮没有补订成功"]

    if skipped_dates and created_dates:
        message_parts.append(f"已保留已有预约：{'、'.join(skipped_dates)}")
    if failed_messages:
        message_parts.append("失败原因：" + "；".join(failed_messages))

    return AutomationActionResult(
        message="；".join(message_parts),
        target_dates=target_dates,
        booked_dates=tuple(sorted(booked_dates)),
        created_dates=tuple(created_dates),
    )


def classify_manual_reserve_result(
    result: AutomationActionResult, *, login_state_ready: bool
) -> str:
    if not login_state_ready or not result.target_dates:
        return "skipped"
    if result.created_dates:
        return "booked"
    if set(result.target_dates).issubset(set(result.booked_dates)):
        return "already-booked"
    if "当前时间已超过当天可预约窗口" in result.message:
        return "skipped"
    if "当前入口没有可维护的预约日期" in result.message:
        return "skipped"
    return "failed"


def build_manual_reserve_result_entry(
    plan: AutomationPlan,
    classification: str,
    result: AutomationActionResult,
) -> dict[str, Any]:
    return {
        "accountName": plan.account_name,
        "planId": plan.plan_id,
        "state": classification,
        "message": result.message,
        "targetDates": list(result.target_dates),
        "bookedDates": list(result.booked_dates),
    }


def build_manual_reserve_check_response(
    results: list[dict[str, Any]],
) -> dict[str, Any]:
    checked_count = len(results)
    booked_count = sum(1 for result in results if result.get("state") == "booked")
    skipped_count = sum(1 for result in results if result.get("state") == "skipped")
    failed_count = sum(1 for result in results if result.get("state") == "failed")

    if checked_count == 0:
        message = "当前没有启用自动预约的计划，无需检查"
    elif failed_count:
        message = (
            f"检查完成：成功 {booked_count}，跳过 {skipped_count}，失败 {failed_count}"
        )
    elif booked_count:
        booked_names = [
            str(result.get("accountName", ""))
            for result in results
            if result.get("state") == "booked"
        ]
        visible_names = "、".join(booked_names[:3])
        suffix = f"：{visible_names}" if visible_names else ""
        if len(booked_names) > 3:
            suffix += "等"
        message = f"已补订 {booked_count} 个账号（共检查 {checked_count} 个计划）{suffix}"
    elif skipped_count:
        message = (
            f"检查完成：成功 {booked_count}，跳过 {skipped_count}，失败 {failed_count}"
        )
    else:
        message = f"已检查 {checked_count} 个计划，全部已有预约"

    return {
        "message": message,
        "checkedCount": checked_count,
        "bookedCount": booked_count,
        "skippedCount": skipped_count,
        "failedCount": failed_count,
        "results": results,
    }


def try_automation_reserve_once(
    *,
    reserve_seat: Callable[[], None],
    wait_reserve_gap: Callable[[str], None],
) -> str | None:
    try:
        reserve_seat()
        return None
    except ApiRequestError as exc:
        if not is_rate_limit_message(exc.message):
            return exc.message

    wait_reserve_gap("retry-after-rate-limit")
    try:
        reserve_seat()
        return None
    except ApiRequestError as retry_exc:
        return retry_exc.message


def read_automation_plan_request(
    payload: dict[str, Any], *, account_name: str, seat_urls: tuple[str, ...]
) -> AutomationPlanRequest:
    seat_url = read_required_text_field(payload.get("seatUrl"), "seatUrl")
    if seat_url not in seat_urls:
        raise ApiRequestError("seatUrl 不在配置允许范围内")
    request = AutomationPlanRequest(
        raw_plan_id=read_optional_text_field(payload.get("planId")),
        account_name=account_name,
        seat_url=seat_url,
        selected_date=read_required_text_field(payload.get("selectedDate"), "selectedDate"),
        start_hour=read_int_field(payload.get("startHour"), "startHour"),
        duration_hours=read_int_field(payload.get("durationHours"), "durationHours"),
        reserve_enabled=read_bool_field(payload.get("reserveEnabled"), "reserveEnabled"),
        checkin_enabled=read_bool_field(payload.get("checkinEnabled"), "checkinEnabled"),
        checkout_enabled=read_bool_field(payload.get("checkoutEnabled"), "checkoutEnabled"),
        continuous_reserve=read_bool_field(payload.get("continuousReserve"), "continuousReserve"),
        reserve_time=read_time_field(payload.get("reserveTime"), "reserveTime"),
        checkin_time=read_time_field(payload.get("checkinTime"), "checkinTime"),
        checkout_time=read_time_field(payload.get("checkoutTime"), "checkoutTime"),
        reserve_check_interval_minutes=read_optional_int_field(
            payload.get("reserveCheckIntervalMinutes"),
            "reserveCheckIntervalMinutes",
            default=30,
        ),
        room_id=read_optional_text_field(payload.get("selectedRoomId")) or "",
        room_name=read_optional_text_field(payload.get("selectedRoomName")) or "",
        seat_number=read_optional_text_field(payload.get("seatNumber")) or "",
    )
    validate_automation_plan_request(request)
    return request


def validate_automation_plan_request(request: AutomationPlanRequest) -> None:
    if not any(
        (request.reserve_enabled, request.checkin_enabled, request.checkout_enabled)
    ):
        raise ApiRequestError("请至少启用一种自动任务动作")
    if request.reserve_enabled and not request.seat_number:
        raise ApiRequestError("启用自动预约时必须填写座位号")
    if request.reserve_enabled and not request.seat_number.isdigit():
        raise ApiRequestError("座位号只能填写数字")


def build_automation_plans_response(
    plans: list[Any], *, now: datetime | None = None
) -> dict[str, Any]:
    current_time = now or datetime.now()
    return {
        "plans": [serialize_automation_plan(plan, now=current_time) for plan in plans],
        "serverTime": current_time.replace(microsecond=0).isoformat(),
    }


def build_saved_automation_plan_response(
    saved_plan: AutomationPlan, *, updated: bool, now: datetime | None = None
) -> dict[str, Any]:
    message = "自动任务计划已更新" if updated else "自动任务计划已创建"
    return {
        "message": message,
        "plan": serialize_automation_plan(saved_plan, now=now or datetime.now()),
    }


def delete_automation_plan_response(
    automation_scheduler: Any, payload: dict[str, Any]
) -> dict[str, Any]:
    plan_id = read_required_text_field(payload.get("planId"), "planId")
    plan = automation_scheduler.delete_plan(plan_id)
    return {
        "message": f"已删除自动任务计划：{plan.account_name}",
        "planId": plan.plan_id,
    }


def build_search_filters_for_plan(request: AutomationPlanRequest) -> SearchFilters:
    return SearchFilters(
        account_name=request.account_name,
        seat_url=request.seat_url,
        date_value=request.selected_date,
        start_hour=request.start_hour,
        duration_hours=request.duration_hours,
        people_count=1,
    )


def build_plan_from_request(
    request: AutomationPlanRequest, existing_plan: AutomationPlan | None
) -> AutomationPlan:
    return build_automation_plan(
        account_name=request.account_name,
        seat_url=request.seat_url,
        room_id=request.room_id,
        room_name=request.room_name,
        seat_number=request.seat_number,
        selected_date=request.selected_date,
        start_hour=request.start_hour,
        duration_hours=request.duration_hours,
        reserve_enabled=request.reserve_enabled,
        checkin_enabled=request.checkin_enabled,
        checkout_enabled=request.checkout_enabled,
        continuous_reserve=request.continuous_reserve,
        reserve_time=request.reserve_time,
        checkin_time=request.checkin_time,
        checkout_time=request.checkout_time,
        reserve_check_interval_minutes=request.reserve_check_interval_minutes,
        existing=existing_plan,
    )


def resolve_existing_automation_plan(
    automation_scheduler: Any,
    plan_id: str | None,
    account_name: str,
) -> AutomationPlan | None:
    existing_plan = automation_scheduler.get_plan(plan_id) if plan_id else None
    if plan_id and existing_plan is None:
        raise ApiRequestError("未找到要编辑的自动任务计划", HTTPStatus.NOT_FOUND)

    account_plan = automation_scheduler.find_plan_by_account(account_name)
    if existing_plan is None:
        return account_plan
    if existing_plan.account_name != account_name:
        raise ApiRequestError("编辑自动任务计划时不支持直接切换账号")
    if account_plan is not None and account_plan.plan_id != existing_plan.plan_id:
        raise ApiRequestError(
            f"账号“{account_name}”已经存在其他自动任务计划", HTTPStatus.CONFLICT
        )
    return existing_plan
