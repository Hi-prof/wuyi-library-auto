from __future__ import annotations

from collections.abc import Callable
from typing import Any

from wuyi_seat_bot.models import ActionResult, ActionType
from wuyi_seat_bot.web_account_service import read_account_name
from wuyi_seat_bot.web_errors import ApiRequestError
from wuyi_seat_bot.workflow import SeatWorkflow


def run_account_action(
    action: ActionType,
    payload: dict[str, Any],
    *,
    run_action_result: Callable[[ActionType, dict[str, Any]], ActionResult],
    error_type: type[Exception] = ApiRequestError,
) -> str:
    result = run_action_result(action, payload)
    if not result.success:
        raise error_type(result.message)
    return result.message


def run_account_action_result(
    action: ActionType,
    payload: dict[str, Any],
    *,
    account_names: tuple[str, ...],
    default_account_name: str,
    run_action_once: Callable[[ActionType, str], ActionResult],
    recover_checkins: Callable[[str, ActionResult], ActionResult | None],
    recover_network: Callable[[ActionResult], ActionResult] = lambda result: result,
) -> ActionResult:
    account_name = read_account_name(payload, account_names, default_account_name)
    result = run_action_once(action, account_name)
    if not result.success:
        if action == ActionType.CHECKIN:
            recovered_result = recover_checkins(account_name, result)
            if recovered_result is not None:
                return recovered_result
        else:
            return recover_network(result)
    return result


def run_account_action_result_once(
    action: ActionType,
    account_name: str,
    *,
    get_runtime: Callable[[str], Any],
) -> ActionResult:
    runtime = get_runtime(account_name)

    def run_workflow() -> ActionResult:
        return SeatWorkflow(runtime.config, runtime.automation).run(action)

    action_lock = getattr(runtime, "action_lock", None)
    if action_lock is None:
        return run_workflow()
    with action_lock:
        return run_workflow()


def build_checkin_all_response(results: list[dict[str, Any]]) -> dict[str, Any]:
    success_count = sum(1 for result in results if result.get("success") is True)
    failed_count = len(results) - success_count
    if not results:
        message = "没有账号，先新建后再签到"
    elif failed_count:
        message = f"签到完成：成功 {success_count}，失败 {failed_count}"
    else:
        message = f"已为 {success_count} 个账号执行签到"
    return {
        "message": message,
        "successCount": success_count,
        "failedCount": failed_count,
        "results": results,
    }
