from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from wuyi_seat_bot.models import ActionType
from wuyi_seat_bot.seat_api import SearchFilters
from wuyi_seat_bot.web_account_service import read_account_name
from wuyi_seat_bot.web_errors import ApiRequestError
from wuyi_seat_bot.web_payload import read_required_text_field
from wuyi_seat_bot.web_reservation_service import (
    build_scheduled_checkin_summary,
    build_scheduled_checkout_summary,
    build_scheduled_reserve_summary,
    normalize_selected_seat_ids,
    parse_filters_payload,
    parse_task_action,
    parse_task_run_at,
    serialize_task,
    validate_filters,
)


@dataclass(frozen=True)
class PreparedTaskRequest:
    action: str
    account_name: str
    run_at: str
    summary: str
    payload: dict[str, Any]


@dataclass(frozen=True)
class BaseTaskRequest:
    action: str
    account_name: str
    run_at: str


def build_tasks_response(tasks: list[Any], *, now: datetime | None = None) -> dict[str, Any]:
    current_time = now or datetime.now()
    return {
        "tasks": [serialize_task(task) for task in tasks],
        "serverTime": current_time.replace(microsecond=0).isoformat(),
    }


def build_created_task_response(task: Any) -> dict[str, Any]:
    return {"message": "定时任务已创建", "task": serialize_task(task)}


def build_created_task_batch_response(tasks: list[Any]) -> dict[str, Any]:
    return {
        "message": f"已创建 {len(tasks)} 个定时任务",
        "tasks": [serialize_task(task) for task in tasks],
    }


def read_base_task_request(
    payload: dict[str, Any],
    account_names: tuple[str, ...],
    default_account_name: str,
) -> BaseTaskRequest:
    return BaseTaskRequest(
        action=parse_task_action(payload),
        account_name=read_account_name(payload, account_names, default_account_name),
        run_at=parse_task_run_at(payload),
    )


def read_task_batch_items(payload: dict[str, Any]) -> list[dict[str, Any]]:
    raw_items = payload.get("items")
    if not isinstance(raw_items, list) or not raw_items:
        raise ApiRequestError("items 必须是非空数组")
    if not all(isinstance(item, dict) for item in raw_items):
        raise ApiRequestError("items 里的每一项都必须是 JSON 对象")
    return raw_items


def build_reserve_task_request(
    base_request: BaseTaskRequest,
    filters: SearchFilters,
    selected_seat: dict[str, Any],
) -> PreparedTaskRequest:
    return PreparedTaskRequest(
        action=base_request.action,
        account_name=base_request.account_name,
        run_at=base_request.run_at,
        summary=build_scheduled_reserve_summary(filters, selected_seat["seatNumber"]),
        payload={
            "accountName": base_request.account_name,
            "seatUrl": filters.seat_url,
            "date": filters.date_value,
            "startHour": filters.start_hour,
            "durationHours": filters.duration_hours,
            "peopleCount": filters.people_count,
            "selectedSeatIds": [selected_seat["seatId"]],
        },
    )


def build_account_action_task_request(
    base_request: BaseTaskRequest, summary: str, payload: dict[str, Any]
) -> PreparedTaskRequest:
    return PreparedTaskRequest(
        action=base_request.action,
        account_name=base_request.account_name,
        run_at=base_request.run_at,
        summary=summary,
        payload=payload,
    )


def delete_task_response(scheduler: Any, payload: dict[str, Any]) -> dict[str, Any]:
    task_id = read_required_text_field(payload.get("taskId"), "taskId")
    task = scheduler.delete_task(task_id)
    return {"message": f"已删除定时任务：{task.summary}", "taskId": task.task_id}


def execute_scheduled_task(
    task: Any,
    *,
    reserve: Callable[[dict[str, Any]], dict[str, Any]],
    run_account_action: Callable[[ActionType, dict[str, Any]], str],
) -> str:
    if task.action == ActionType.RESERVE.value:
        result = reserve(task.payload)
        return result["message"]

    if task.action == ActionType.CHECKIN.value:
        return run_account_action(ActionType.CHECKIN, task.payload)

    if task.action == ActionType.CHECKOUT.value:
        return run_account_action(ActionType.CHECKOUT, task.payload)

    raise RuntimeError(f"不支持的定时任务动作：{task.action}")


def prepare_task_request(
    *,
    payload: dict[str, Any],
    account_names: tuple[str, ...],
    default_account_name: str,
    load_account_config: Callable[[str], Any],
    load_search_page_payload: Callable[[str, str], dict[str, Any]],
    search_seat_map: Callable[[dict[str, Any]], dict[str, Any]],
) -> PreparedTaskRequest:
    base_request = read_base_task_request(
        payload, account_names, default_account_name
    )
    account_config = load_account_config(base_request.account_name)

    if base_request.action == ActionType.RESERVE.value:
        filters = parse_filters_payload(
            payload,
            account_config.seat_urls,
            account_name=base_request.account_name,
        )
        search_page_payload = load_search_page_payload(
            base_request.account_name, filters.seat_url
        )
        validate_filters(search_page_payload, filters)
        selected_seat = read_selected_task_seat(
            payload,
            filters,
            search_seat_map,
        )
        return build_reserve_task_request(base_request, filters, selected_seat)

    summary, task_payload = build_account_action_task(
        base_request.action,
        base_request.account_name,
        account_config.account_name,
    )
    return build_account_action_task_request(base_request, summary, task_payload)


def build_account_action_task(
    action: str,
    account_name: str,
    display_account_name: str,
) -> tuple[str, dict[str, Any]]:
    summary_builder = {
        ActionType.CHECKIN.value: build_scheduled_checkin_summary,
        ActionType.CHECKOUT.value: build_scheduled_checkout_summary,
    }[action]
    return summary_builder(display_account_name), {"accountName": account_name}


def read_selected_task_seat(
    payload: dict[str, Any],
    filters: SearchFilters,
    search_seat_map: Callable[[dict[str, Any]], dict[str, Any]],
) -> dict[str, Any]:
    selected_seat_ids = normalize_selected_seat_ids(payload.get("selectedSeatIds"))
    if filters.people_count != 1:
        raise ApiRequestError("当前定时预约只支持单人任务")
    if len(selected_seat_ids) != 1:
        raise ApiRequestError("定时预约前请先选中 1 个座位")

    seat_map = search_seat_map(payload)
    selected_seat = next(
        (
            seat
            for seat in seat_map["seats"]
            if seat["seatId"] == selected_seat_ids[0]
        ),
        None,
    )
    if selected_seat is None or not selected_seat["selectable"]:
        raise ApiRequestError(
            "当前所选座位不可用于创建定时预约任务，请重新查询后再试"
        )
    return selected_seat
