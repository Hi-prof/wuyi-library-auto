from __future__ import annotations

import urllib.error
from datetime import datetime, timedelta
from http import HTTPStatus
from typing import Any

from wuyi_seat_bot.automation_plans import AutomationPlan
from wuyi_seat_bot.models import ActionType
from wuyi_seat_bot.scheduler import ScheduledTask
from wuyi_seat_bot.seat_api import (
    SHANGHAI_TZ,
    SearchFilters,
    append_lab_json,
    build_begin_time,
    build_book_api_url,
    build_book_form,
    build_book_token,
    build_checkin_candidates,
    build_checkout_candidates,
    build_custom_search_form_payload,
    build_date_options,
    build_duration_options,
    build_people_options,
    build_room_seat_maps,
    build_time_options,
    current_api_time,
    describe_seat_booking_status,
    fetch_json,
    fetch_my_booking_list,
    is_checkin_window_open,
    perform_seat_cancel_booking,
    serialize_seat_map,
)
from wuyi_seat_bot.web_errors import ApiRequestError


def build_query_constraints(search_page_payload: dict[str, Any]) -> dict[str, int]:
    range_data = search_page_payload["data"]["range"]
    return {
        "minBeginTime": int(range_data["minBeginTime"]),
        "maxEndTime": int(range_data["maxEndTime"]),
        "minDuration": int(range_data["min_duration"]),
        "maxDuration": int(range_data["max_duration"]),
        "maxPeopleCount": int(range_data["max_num"]),
    }


def is_invalid_login_state_error(error: ApiRequestError) -> bool:
    if error.status_code == HTTPStatus.PRECONDITION_FAILED:
        return True
    return "登录态已失效" in error.message


def parse_filters_payload(
    payload: dict[str, Any],
    seat_urls: tuple[str, ...],
    *,
    account_name: str = "",
) -> SearchFilters:
    if not isinstance(payload, dict):
        raise ApiRequestError("请求体必须是 JSON 对象")

    seat_url = str(payload.get("seatUrl", "")).strip()
    if seat_url not in seat_urls:
        raise ApiRequestError("seatUrl 不在配置允许范围内")

    date_value = str(payload.get("date", "")).strip()
    try:
        datetime.strptime(date_value, "%Y-%m-%d")
    except ValueError as exc:
        raise ApiRequestError("date 必须是 YYYY-MM-DD 格式") from exc

    return SearchFilters(
        date_value=date_value,
        start_hour=_read_int_field(payload.get("startHour"), "startHour"),
        duration_hours=_read_int_field(payload.get("durationHours"), "durationHours"),
        people_count=_read_int_field(payload.get("peopleCount"), "peopleCount"),
        seat_url=seat_url,
        account_name=account_name,
    )


def normalize_selected_seat_ids(raw_value: object) -> tuple[str, ...]:
    if raw_value is None:
        return ()
    if not isinstance(raw_value, list):
        raise ApiRequestError("selectedSeatIds 必须是数组")

    seat_ids: list[str] = []
    seen: set[str] = set()
    for item in raw_value:
        seat_id = str(item).strip()
        if not seat_id or seat_id in seen:
            continue
        seen.add(seat_id)
        seat_ids.append(seat_id)
    return tuple(seat_ids)


def validate_filters(
    search_page_payload: dict[str, Any], filters: SearchFilters
) -> None:
    allowed_dates = {
        option["value"] for option in build_date_options(search_page_payload)
    }
    if filters.date_value not in allowed_dates:
        raise ApiRequestError("所选日期不在当前可预约范围内")

    allowed_start_hours = {
        int(option["value"]) for option in build_time_options(search_page_payload)
    }
    if filters.start_hour not in allowed_start_hours:
        raise ApiRequestError("所选开始时间不在当前可预约范围内")

    allowed_durations = {
        int(option["value"])
        for option in build_duration_options(
            search_page_payload, start_hour=filters.start_hour
        )
    }
    if filters.duration_hours not in allowed_durations:
        raise ApiRequestError("所选使用时长不在当前可预约范围内")

    allowed_people_counts = {
        int(option["value"]) for option in build_people_options(search_page_payload)
    }
    if filters.people_count not in allowed_people_counts:
        raise ApiRequestError("所选预约人数不在当前可预约范围内")


def build_reservation_hint(people_count: int) -> str:
    if people_count == 1:
        return "当前条件已支持单人直接预约。请选择 1 个座位后提交。"
    return "当前网页已展示多人预约的选座结果，但学校接口还需要同行人信息，暂不支持直接提交多人预约。"


def build_query_summary(filters: SearchFilters, room_name: str) -> str:
    account_prefix = f"{filters.account_name} · " if filters.account_name else ""
    return (
        f"{account_prefix}{room_name} · {filters.date_value} · {filters.start_hour}:00 开始 · "
        f"{filters.duration_hours} 小时 · {filters.people_count} 人"
    )


def build_room_options(
    rooms: list[dict[str, Any]] | tuple[dict[str, Any], ...],
    selected_room_id: str,
) -> list[dict[str, str]]:
    selected_value = str(selected_room_id or "").strip()
    options: list[dict[str, str]] = []
    for room in rooms:
        room_id = str(room.get("roomId", "")).strip()
        room_name = str(room.get("roomName", "")).strip() or "未命名自习室"
        option_value = room_id or room_name
        options.append(
            {
                "value": option_value,
                "label": room_name,
                "roomId": room_id,
                "roomName": room_name,
                "selected": "true" if option_value == selected_value else "false",
            }
        )
    return options


def find_room_map(
    rooms: list[dict[str, Any]] | tuple[dict[str, Any], ...],
    *,
    room_id: str = "",
    room_name: str = "",
) -> dict[str, Any] | None:
    normalized_room_id = str(room_id or "").strip()
    normalized_room_name = str(room_name or "").strip()
    if normalized_room_id:
        matched = next(
            (
                room
                for room in rooms
                if str(room.get("roomId", "")).strip() == normalized_room_id
            ),
            None,
        )
        if matched is not None:
            return matched
    if normalized_room_name:
        return next(
            (
                room
                for room in rooms
                if str(room.get("roomName", "")).strip() == normalized_room_name
            ),
            None,
        )
    return None


def select_room_map(
    rooms: list[dict[str, Any]] | tuple[dict[str, Any], ...],
    *,
    room_id: str = "",
    room_name: str = "",
    default_room: dict[str, Any] | None = None,
    strict: bool = False,
) -> dict[str, Any]:
    matched = find_room_map(rooms, room_id=room_id, room_name=room_name)
    if matched is not None:
        return matched
    if strict and (str(room_id or "").strip() or str(room_name or "").strip()):
        target_label = str(room_name or "").strip() or str(room_id or "").strip()
        raise ApiRequestError(
            f"当前查询结果里没有自习室 {target_label}，请重新查询后再试",
            HTTPStatus.CONFLICT,
        )
    if default_room is not None:
        return default_room
    if rooms:
        return rooms[0]
    raise ApiRequestError("当前查询结果里没有可用自习室", HTTPStatus.CONFLICT)


def search_seats_response(
    *,
    account_name: str,
    filters: SearchFilters,
    context: Any,
    saved_session: Any,
    payload: dict[str, Any],
) -> dict[str, Any]:
    validate_filters(context.search_page_payload, filters)
    rooms, seat_map = _build_seat_map_for_filters(
        context=context,
        saved_session=saved_session,
        filters=filters,
        room_id=str(payload.get("selectedRoomId", "")).strip(),
        room_name=str(payload.get("selectedRoomName", "")).strip(),
        error_prefix="查询座位失败",
    )
    return {
        "query": {
            "accountName": account_name,
            "seatUrl": filters.seat_url,
            "date": filters.date_value,
            "startHour": filters.start_hour,
            "durationHours": filters.duration_hours,
            "peopleCount": filters.people_count,
        },
        "roomName": seat_map["roomName"],
        "rooms": rooms,
        "roomOptions": build_room_options(rooms, seat_map["roomId"]),
        "selectedRoomId": seat_map["roomId"],
        "selectedRoomName": seat_map["roomName"],
        "seatMap": seat_map,
        "supportsDirectReserve": filters.people_count == 1,
        "reservationHint": build_reservation_hint(filters.people_count),
        "summary": build_query_summary(filters, seat_map["roomName"]),
    }


def reserve_selected_seat_response(
    *,
    account_name: str,
    filters: SearchFilters,
    context: Any,
    saved_session: Any,
    payload: dict[str, Any],
) -> dict[str, Any]:
    selected_seat_ids = normalize_selected_seat_ids(payload.get("selectedSeatIds"))
    if filters.people_count != 1:
        raise ApiRequestError(
            build_reservation_hint(filters.people_count), HTTPStatus.CONFLICT
        )
    if len(selected_seat_ids) != 1:
        raise ApiRequestError("请先选择 1 个座位后再提交预约", HTTPStatus.CONFLICT)

    validate_filters(context.search_page_payload, filters)
    begin_time = build_begin_time(filters.date_value, filters.start_hour)
    duration_seconds = filters.duration_hours * 3600
    _, seat_map = _build_seat_map_for_filters(
        context=context,
        saved_session=saved_session,
        filters=filters,
        room_id=str(payload.get("selectedRoomId", "")).strip(),
        room_name=str(payload.get("selectedRoomName", "")).strip(),
        strict=True,
        error_prefix="预约前校验失败",
    )
    selected_seat = _find_seat_by_id(seat_map, selected_seat_ids[0])
    if selected_seat is None:
        raise ApiRequestError(
            "所选座位不存在，请重新查询后再试", HTTPStatus.CONFLICT
        )
    if not selected_seat["selectable"]:
        raise ApiRequestError(
            f"{selected_seat['seatNumber']} 号座位当前不可预约，请重新选择其他座位",
            HTTPStatus.CONFLICT,
        )

    result_data = _submit_seat_booking(
        search_api_url=context.search_api_url,
        saved_session=saved_session,
        begin_time=begin_time,
        duration_seconds=duration_seconds,
        selected_seat=selected_seat,
    )
    return {
        "message": (
            f"{account_name} 预约成功：{seat_map['roomName']} {selected_seat['seatNumber']} 号座位，"
            f"{filters.date_value} {filters.start_hour}:00 开始，"
            f"{filters.duration_hours} 小时，{filters.people_count} 人"
        ),
        "bookingId": result_data.get("bookingId", ""),
        "seat": selected_seat,
        "summary": build_query_summary(filters, seat_map["roomName"]),
    }


def cancel_booking_response(
    *,
    account_name: str,
    saved_session: Any,
    booking_id: str,
) -> dict[str, Any]:
    try:
        booking_list_payload = fetch_my_booking_list(saved_session.cookie_header)
        bookings = serialize_booking_status_items(booking_list_payload)
    except ApiRequestError:
        raise
    except Exception as exc:  # noqa: BLE001
        raise ApiRequestError(
            f"读取预约记录失败：{exc}", HTTPStatus.BAD_GATEWAY
        ) from exc

    booking = next(
        (item for item in bookings if item["bookingId"] == booking_id), None
    )
    if booking is None:
        raise ApiRequestError("未找到要取消的预约记录", HTTPStatus.NOT_FOUND)

    if str(booking.get("status", "")).strip() not in {"0", "8"}:
        raise ApiRequestError(
            f"当前预约状态不支持取消：{booking['statusLabel']}",
            HTTPStatus.CONFLICT,
        )

    try:
        result = perform_seat_cancel_booking(saved_session.cookie_header, booking_id)
    except Exception as exc:  # noqa: BLE001
        raise ApiRequestError(f"取消预约失败：{exc}", HTTPStatus.BAD_GATEWAY) from exc

    if not result.success:
        raise ApiRequestError(result.message, HTTPStatus.CONFLICT)

    return {
        "message": (
            f"已取消预约：{account_name} · "
            f"{booking['roomName']} {booking['seatNumber']} 号座位"
        ),
        "bookingId": booking_id,
    }


def reserve_seat_by_number(
    *,
    filters: SearchFilters,
    context: Any,
    saved_session: Any,
    room_id: str,
    room_name: str,
    seat_number: str,
) -> None:
    begin_time = build_begin_time(filters.date_value, filters.start_hour)
    duration_seconds = filters.duration_hours * 3600
    _, seat_map = _build_seat_map_for_filters(
        context=context,
        saved_session=saved_session,
        filters=filters,
        room_id=room_id,
        room_name=room_name,
        strict=bool(room_id or room_name),
        error_prefix="预约前校验失败",
    )
    selected_seat = _find_seat_by_number(seat_map, seat_number)
    if selected_seat is None:
        raise ApiRequestError(
            f"当前查询结果里没有 {seat_number} 号座位", HTTPStatus.CONFLICT
        )
    if not selected_seat["selectable"]:
        raise ApiRequestError(
            f"{seat_number} 号座位当前不可预约", HTTPStatus.CONFLICT
        )

    _submit_seat_booking(
        search_api_url=context.search_api_url,
        saved_session=saved_session,
        begin_time=begin_time,
        duration_seconds=duration_seconds,
        selected_seat=selected_seat,
    )


def _build_seat_map_for_filters(
    *,
    context: Any,
    saved_session: Any,
    filters: SearchFilters,
    room_id: str,
    room_name: str,
    error_prefix: str,
    strict: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    begin_time = build_begin_time(filters.date_value, filters.start_hour)
    duration_seconds = filters.duration_hours * 3600
    try:
        search_result_payload = fetch_json(
            append_lab_json(context.search_api_url),
            cookie_header=saved_session.cookie_header,
            method="POST",
            form_data=build_custom_search_form_payload(
                context.search_page_payload,
                begin_time=begin_time,
                duration_seconds=duration_seconds,
                people_count=filters.people_count,
            ),
        )
        rooms = list(build_room_seat_maps(search_result_payload))
        seat_map = select_room_map(
            rooms,
            room_id=room_id,
            room_name=room_name,
            default_room=serialize_seat_map(search_result_payload),
            strict=strict,
        )
    except urllib.error.HTTPError as exc:
        raise ApiRequestError(
            f"{error_prefix}：HTTP {exc.code}", HTTPStatus.BAD_GATEWAY
        ) from exc
    except Exception as exc:  # noqa: BLE001
        raise ApiRequestError(
            f"{error_prefix}：{exc}", HTTPStatus.BAD_GATEWAY
        ) from exc
    return rooms, seat_map


def _find_seat_by_id(
    seat_map: dict[str, Any], seat_id: str
) -> dict[str, Any] | None:
    return next(
        (seat for seat in seat_map["seats"] if seat["seatId"] == seat_id),
        None,
    )


def _find_seat_by_number(
    seat_map: dict[str, Any], seat_number: str
) -> dict[str, Any] | None:
    return next(
        (
            seat
            for seat in seat_map["seats"]
            if str(seat.get("seatNumber", "")).strip() == seat_number
        ),
        None,
    )


def _submit_seat_booking(
    *,
    search_api_url: str,
    saved_session: Any,
    begin_time: int,
    duration_seconds: int,
    selected_seat: dict[str, Any],
) -> dict[str, Any]:
    api_time = current_api_time()
    seat_ids = (selected_seat["seatId"],)
    seat_booker_ids = (saved_session.user_id,)
    try:
        book_result = fetch_json(
            build_book_api_url(search_api_url),
            cookie_header=saved_session.cookie_header,
            method="POST",
            form_data=build_book_form(
                begin_time=begin_time,
                duration_seconds=duration_seconds,
                seat_ids=seat_ids,
                seat_booker_ids=seat_booker_ids,
                api_time=api_time,
                is_recommend=0,
            ),
            extra_headers={
                "Api-Token": build_book_token(
                    begin_time=begin_time,
                    duration_seconds=duration_seconds,
                    seat_ids=seat_ids,
                    seat_booker_ids=seat_booker_ids,
                    api_time=api_time,
                    is_recommend=0,
                )
            },
        )
    except urllib.error.HTTPError as exc:
        raise ApiRequestError(
            f"预约提交失败：HTTP {exc.code}", HTTPStatus.BAD_GATEWAY
        ) from exc
    except Exception as exc:  # noqa: BLE001
        raise ApiRequestError(f"预约提交失败：{exc}", HTTPStatus.BAD_GATEWAY) from exc

    return _validate_book_result(book_result)


def _validate_book_result(book_result: dict[str, Any]) -> dict[str, Any]:
    if book_result.get("CODE") != "ok":
        raise ApiRequestError(
            book_result.get("MESSAGE", "预约接口返回失败"), HTTPStatus.CONFLICT
        )

    result_data = book_result.get("DATA", {})
    if result_data.get("result") != "success":
        raise ApiRequestError(
            result_data.get("msg", "预约失败，接口未返回成功结果"),
            HTTPStatus.CONFLICT,
        )
    return result_data


def serialize_booking_status_items(
    booking_list_payload: dict[str, Any],
) -> list[dict[str, Any]]:
    checkin_candidates = {
        candidate.booking_id: candidate
        for candidate in build_checkin_candidates(booking_list_payload)
    }
    bookings: list[dict[str, Any]] = []
    for candidate in build_checkout_candidates(booking_list_payload):
        checkin_candidate = checkin_candidates.get(candidate.booking_id)
        bookings.append(
            {
                "bookingId": candidate.booking_id,
                "roomName": candidate.room_name,
                "seatNumber": candidate.seat_number,
                "status": candidate.status,
                "statusLabel": describe_seat_booking_status(candidate.status),
                "startAt": format_booking_time_iso(candidate.start_time),
                "startAtLabel": format_booking_time_label(candidate.start_time),
                "endAt": format_booking_time_iso(
                    candidate.start_time + candidate.duration_seconds
                )
                if candidate.start_time > 0 and candidate.duration_seconds > 0
                else None,
                "endAtLabel": format_booking_time_label(
                    candidate.start_time + candidate.duration_seconds
                )
                if candidate.start_time > 0 and candidate.duration_seconds > 0
                else "",
                "durationSeconds": candidate.duration_seconds,
                "checkinWindowOpen": bool(
                    candidate.status == "0"
                    and checkin_candidate is not None
                    and is_checkin_window_open(checkin_candidate)
                ),
                "startTimestamp": candidate.start_time,
            }
        )
    return sorted(bookings, key=booking_status_sort_key)


def build_task_status_response(
    *,
    account_name: str,
    state: str,
    summary: str,
    detail: str,
    login_state_ready: bool,
    pending_task_count: int,
    running_task_count: int,
    bookings: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "accountName": account_name,
        "state": state,
        "summary": summary,
        "detail": detail,
        "loginStateReady": login_state_ready,
        "pendingTaskCount": pending_task_count,
        "runningTaskCount": running_task_count,
        "bookings": bookings or [],
    }


def booking_status_sort_key(item: dict[str, Any]) -> tuple[int, int]:
    status = str(item.get("status", "")).strip()
    if status in {"1", "2"}:
        priority = 0
    elif bool(item.get("checkinWindowOpen")):
        priority = 1
    elif status in {"0", "8"}:
        priority = 2
    else:
        priority = 3
    return (priority, -int(item.get("startTimestamp", 0) or 0))


def derive_booking_state(item: dict[str, Any]) -> str:
    status = str(item.get("status", "")).strip()
    if status in {"1", "2"}:
        return "active"
    if bool(item.get("checkinWindowOpen")):
        return "checkin-ready"
    if status in {"0", "8"}:
        return "reserved"
    return "finished"


def format_booking_time_iso(timestamp: int) -> str | None:
    if timestamp <= 0:
        return None
    return (
        datetime.fromtimestamp(timestamp, SHANGHAI_TZ)
        .replace(microsecond=0)
        .isoformat()
    )


def format_booking_time_label(timestamp: int) -> str:
    if timestamp <= 0:
        return ""
    return datetime.fromtimestamp(timestamp, SHANGHAI_TZ).strftime("%Y-%m-%d %H:%M")


def serialize_task(task: ScheduledTask) -> dict[str, Any]:
    return {
        "taskId": task.task_id,
        "action": task.action,
        "accountName": task.account_name,
        "runAt": task.run_at,
        "createdAt": task.created_at,
        "summary": task.summary,
        "status": task.status,
        "lastMessage": task.last_message,
        "finishedAt": task.finished_at,
    }


def serialize_automation_plan(
    plan: AutomationPlan, *, now: datetime | None = None
) -> dict[str, Any]:
    current_time = now or datetime.now()
    reserve_preview_slots = build_automation_reserve_preview_slots(
        plan, now=current_time
    )
    return {
        "planId": plan.plan_id,
        "accountName": plan.account_name,
        "seatUrl": plan.seat_url,
        "roomId": plan.room_id,
        "roomName": plan.room_name,
        "seatNumber": plan.seat_number,
        "selectedDate": plan.selected_date,
        "startHour": plan.start_hour,
        "durationHours": plan.duration_hours,
        "continuousReserve": plan.continuous_reserve,
        "createdAt": plan.created_at,
        "updatedAt": plan.updated_at,
        "reserve": {
            "enabled": plan.reserve_enabled,
            "time": plan.reserve_time,
            "intervalMinutes": plan.reserve_check_interval_minutes,
            "nextRunAt": plan.reserve_next_run_at,
            "lastRunAt": plan.reserve_last_run_at,
            "lastMessage": plan.reserve_last_message,
            "targetDates": list(plan.reserve_target_dates),
            "bookedDates": list(plan.reserve_booked_dates),
            "previewSlots": reserve_preview_slots,
            "windowLabel": build_reserve_window_label(
                plan.start_hour, plan.duration_hours
            ),
        },
        "checkin": _serialize_daily_automation_action(
            plan, "checkin", now=current_time
        ),
        "checkout": _serialize_daily_automation_action(
            plan, "checkout", now=current_time
        ),
    }


def build_automation_reserve_preview_slots(
    plan: AutomationPlan, *, now: datetime
) -> list[dict[str, Any]]:
    current_time = now.astimezone(SHANGHAI_TZ) if now.tzinfo is not None else now
    current_date = current_time.date()
    booked_dates = set(plan.reserve_booked_dates)
    slots = []
    for offset, day_label in enumerate(("今天", "明天", "后天")):
        date_value = (current_date + timedelta(days=offset)).isoformat()
        start_hour = max(current_time.hour, 8) if offset == 0 else 8
        end_hour = 22
        slots.append(
            {
                "date": date_value,
                "label": day_label,
                "windowLabel": (
                    build_reserve_window_label(start_hour, end_hour - start_hour)
                    if start_hour < end_hour
                    else "已过可预约窗口"
                ),
                "booked": date_value in booked_dates,
            }
        )
    return slots


def parse_task_action(payload: dict[str, Any]) -> str:
    action = str(payload.get("action", "")).strip()
    if action not in {
        ActionType.RESERVE.value,
        ActionType.CHECKIN.value,
        ActionType.CHECKOUT.value,
    }:
        raise ApiRequestError("action 仅支持 reserve、checkin 或 checkout")
    return action


def parse_task_run_at(payload: dict[str, Any]) -> str:
    raw_value = str(payload.get("runAt", "")).strip()
    if not raw_value:
        raise ApiRequestError("runAt 不能为空")
    try:
        run_at = datetime.fromisoformat(raw_value)
    except ValueError as exc:
        raise ApiRequestError("runAt 必须是合法的日期时间") from exc
    if run_at <= datetime.now():
        raise ApiRequestError("定时任务执行时间必须晚于当前时间")
    return run_at.replace(microsecond=0).isoformat()


def build_scheduled_reserve_summary(filters: SearchFilters, seat_number: str) -> str:
    return (
        f"{filters.account_name} · 定时预约 · {filters.date_value} {filters.start_hour}:00 · "
        f"{filters.duration_hours} 小时 · {seat_number} 号座位"
    )


def build_scheduled_checkin_summary(account_name: str) -> str:
    return f"{account_name} · 定时签到"


def build_scheduled_checkout_summary(account_name: str) -> str:
    return f"{account_name} · 定时签退"


def count_automation_plan_enabled_actions(plan: AutomationPlan | None) -> int:
    if plan is None:
        return 0
    return sum((plan.reserve_enabled, plan.checkin_enabled, plan.checkout_enabled))


def extract_active_booking_dates(booking_list_payload: dict[str, Any]) -> set[str]:
    return {
        item["startAt"][:10]
        for item in serialize_booking_status_items(booking_list_payload)
        if item.get("startAt")
        and str(item.get("status", "")).strip() in {"0", "1", "2", "8"}
    }


def build_reserve_window_label(start_hour: int, duration_hours: int) -> str:
    end_hour = start_hour + duration_hours
    return f"{start_hour}:00 - {end_hour}:00"


def _serialize_daily_automation_action(
    plan: AutomationPlan,
    action: str,
    *,
    now: datetime,
) -> dict[str, Any]:
    run_time = getattr(plan, f"{action}_time")
    return {
        "enabled": getattr(plan, f"{action}_enabled"),
        "time": run_time,
        "nextRunAt": getattr(plan, f"{action}_next_run_at"),
        "lastRunAt": getattr(plan, f"{action}_last_run_at"),
        "lastMessage": getattr(plan, f"{action}_last_message"),
        "previewRuns": build_daily_action_preview_runs(
            plan.selected_date,
            run_time,
            now=now,
        ),
    }


def build_daily_action_preview_runs(
    selected_date: str,
    run_time: str,
    *,
    now: datetime | None = None,
    total_days: int = 3,
) -> list[str]:
    try:
        start_day = datetime.strptime(selected_date, "%Y-%m-%d").date()
    except ValueError:
        return []

    current_time = now or datetime.now()
    anchor_day = max(start_day, current_time.date())
    return [
        datetime.combine(
            anchor_day + timedelta(days=offset), _parse_time_text(run_time)
        )
        .replace(second=0, microsecond=0)
        .isoformat()
        for offset in range(max(total_days, 0))
    ]


def _parse_time_text(value: str):
    return datetime.strptime(value, "%H:%M").time()


def _read_int_field(value: object, field_name: str) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip():
        try:
            return int(value.strip())
        except ValueError as exc:
            raise ApiRequestError(f"{field_name} 必须是整数") from exc
    raise ApiRequestError(f"{field_name} 必须是整数")
