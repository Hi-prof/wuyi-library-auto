from __future__ import annotations

from collections.abc import Callable
from typing import Any

from wuyi_seat_bot.models import ActionResult
from wuyi_seat_bot.seat_api import build_checkin_candidates


CHECKIN_NETWORK_RECOVERY_STATES = {"offline", "degraded"}
ACTION_NETWORK_RECOVERY_STATES = {"offline"}
CHECKIN_RECOVERY_PENDING_STATUSES = {"0"}


def recover_checkins_after_network_failure(
    *,
    trigger_account_name: str,
    original_result: ActionResult,
    recovery_lock: Any,
    get_network_monitor: Callable[[], Any],
    list_recovery_checkin_accounts: Callable[[], tuple[str, ...]],
    run_checkin_once: Callable[[str], ActionResult],
) -> ActionResult | None:
    if not recovery_lock.acquire(blocking=False):
        return None
    try:
        try:
            monitor = get_network_monitor()
            detected_status = monitor.detect_once()
        except Exception:  # noqa: BLE001
            return None
        if not is_checkin_network_failure(detected_status):
            return None

        try:
            reconnect_status = monitor.reconnect_once()
        except Exception as exc:  # noqa: BLE001
            reconnect_status = {
                "networkState": "offline",
                "message": str(exc).strip() or type(exc).__name__,
            }
        if not is_network_online(reconnect_status):
            return append_checkin_recovery_message(
                original_result,
                f"网络检测异常，自动重连失败：{read_status_message(reconnect_status)}",
            )
        ready_account_names = list_recovery_checkin_accounts()
        if not ready_account_names:
            return append_checkin_recovery_message(
                original_result,
                "网络已恢复，但没有账号处于可签到状态",
            )

        checkin_results = {
            account_name: run_checkin_once(account_name)
            for account_name in ready_account_names
        }
    finally:
        recovery_lock.release()

    trigger_result = checkin_results.get(trigger_account_name)
    if trigger_result is not None and trigger_result.success:
        return trigger_result

    successful_account_names = [
        account_name
        for account_name, result in checkin_results.items()
        if result.success
    ]
    if successful_account_names:
        return append_checkin_recovery_message(
            original_result,
            f"网络已恢复，已补签：{'、'.join(successful_account_names)}",
        )
    return append_checkin_recovery_message(
        original_result,
        "网络已恢复，但补签仍未成功",
    )


def recover_network_after_action_failure(
    *,
    original_result: ActionResult,
    recovery_lock: Any,
    get_network_monitor: Callable[[], Any],
) -> ActionResult:
    """非签到 action 失败后的轻量恢复路径：检测网络，必要时重连，仅追加提示，不补做业务。"""
    if not recovery_lock.acquire(blocking=False):
        return original_result
    try:
        try:
            monitor = get_network_monitor()
            detected_status = monitor.detect_once()
        except Exception:  # noqa: BLE001
            return original_result
        if is_network_degraded(detected_status):
            return append_checkin_recovery_message(
                original_result,
                f"学校目标站点暂时不通：{read_status_message(detected_status)}",
            )
        if not is_action_network_failure(detected_status):
            return original_result

        try:
            reconnect_status = monitor.reconnect_once()
        except Exception as exc:  # noqa: BLE001
            reconnect_status = {
                "networkState": "offline",
                "message": str(exc).strip() or type(exc).__name__,
            }
        if is_network_online(reconnect_status):
            return append_checkin_recovery_message(
                original_result,
                "网络已自动重连，请稍后重试该操作",
            )
        return append_checkin_recovery_message(
            original_result,
            f"网络检测异常，自动重连失败：{read_status_message(reconnect_status)}",
        )
    finally:
        recovery_lock.release()


def list_recovery_checkin_accounts(
    account_names: tuple[str, ...],
    account_needs_checkin: Callable[[str], bool],
) -> tuple[str, ...]:
    return tuple(
        account_name
        for account_name in account_names
        if account_needs_checkin(account_name)
    )


def account_needs_immediate_checkin(
    load_booking_list_payload: Callable[[], dict[str, Any]],
) -> bool:
    try:
        booking_list_payload = load_booking_list_payload()
    except Exception:  # noqa: BLE001
        return False

    return any(
        candidate.status in CHECKIN_RECOVERY_PENDING_STATUSES
        for candidate in build_checkin_candidates(booking_list_payload)
    )


def is_checkin_network_failure(status: dict[str, Any]) -> bool:
    network_state = str(status.get("networkState", "")).strip()
    return network_state in CHECKIN_NETWORK_RECOVERY_STATES


def is_action_network_failure(status: dict[str, Any]) -> bool:
    network_state = str(status.get("networkState", "")).strip()
    return network_state in ACTION_NETWORK_RECOVERY_STATES


def is_network_degraded(status: dict[str, Any]) -> bool:
    return str(status.get("networkState", "")).strip() == "degraded"


def is_network_online(status: dict[str, Any]) -> bool:
    return str(status.get("networkState", "")).strip() == "online"


def read_status_message(status: dict[str, Any]) -> str:
    return str(status.get("message", "")).strip() or "未返回具体原因"


def append_checkin_recovery_message(
    result: ActionResult,
    recovery_message: str,
) -> ActionResult:
    return ActionResult(
        success=result.success,
        action=result.action,
        seat_url=result.seat_url,
        attempts=result.attempts,
        message=f"{result.message}；{recovery_message}",
    )
