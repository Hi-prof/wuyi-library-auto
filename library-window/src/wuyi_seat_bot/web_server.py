# 撤回 spec account-pool-tri-sync 11.4 中的 ConnectivityGate 守卫；本地执行不再依赖服务端可达性
from __future__ import annotations

import json
import logging
import threading
import urllib.error
import webbrowser
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from http import HTTPStatus
from pathlib import Path
from typing import Any

from wuyi_seat_bot.api_seat_automation import ApiSeatAutomation
from wuyi_seat_bot.automation_plans import (
    AutomationActionResult,
    AutomationPlan,
    LocalAutomationPlanScheduler,
)
from wuyi_seat_bot.checkin_monitor import PeriodicAccountCheckinMonitor
from wuyi_seat_bot.config import (
    load_config_bundle,
    resolve_project_path,
)
from wuyi_seat_bot.models import ActionResult, ActionType, AppConfig, ConfigBundle
from wuyi_seat_bot.network_monitor import NetworkMonitor
from wuyi_seat_bot.scheduler import (
    PENDING_STATUS,
    RUNNING_STATUS,
    LocalTaskScheduler,
    ScheduledTask,
)
from wuyi_seat_bot.web_automation_service import (
    build_automation_plans_response,
    build_manual_reserve_check_response,
    build_manual_reserve_result_entry,
    build_plan_from_request,
    build_saved_automation_plan_response,
    build_search_filters_for_plan,
    classify_manual_reserve_result,
    delete_automation_plan_response,
    execute_automation_action,
    execute_automation_reserve,
    execute_manual_reserve_check,
    read_automation_plan_request,
    resolve_existing_automation_plan,
    try_automation_reserve_once,
    wait_automation_reserve_gap,
)
from wuyi_seat_bot.web_action_service import (
    build_checkin_all_response,
    run_account_action,
    run_account_action_result,
    run_account_action_result_once,
)
from wuyi_seat_bot.web_errors import ApiRequestError
from wuyi_seat_bot.web_routes import SeatWebRequestHandler, SeatWebServer
from wuyi_seat_bot.web_account_service import (
    build_account_import_response,
    build_account_mutation_response,
    build_account_options,
    build_accounts_response,
    build_seat_url_options,
    delete_accounts_mutation,
    delete_account_mutation,
    import_accounts_mutation,
    read_account_name,
    read_account_name_field,
    read_account_names_field,
    read_submitted_student_id,
    save_account_bundle_from_payload,
    save_account_mutation,
    serialize_account_profile,
    set_default_account_mutation,
)
from wuyi_seat_bot.web_payload import (
    read_optional_text_field as _read_optional_text_field,
    read_required_text_field as _read_required_text_field,
)
from wuyi_seat_bot.web_recovery_service import (
    account_needs_immediate_checkin,
    list_recovery_checkin_accounts,
    recover_checkins_after_network_failure,
    recover_network_after_action_failure,
)
from wuyi_seat_bot.web_reservation_service import (
    build_daily_action_preview_runs,
    build_query_constraints,
    build_reserve_window_label,
    build_scheduled_checkin_summary,
    build_scheduled_checkout_summary,
    build_scheduled_reserve_summary,
    cancel_booking_response,
    is_invalid_login_state_error,
    normalize_selected_seat_ids,
    parse_filters_payload,
    parse_task_action,
    parse_task_run_at,
    reserve_seat_by_number,
    reserve_selected_seat_response,
    search_seats_response,
    serialize_booking_status_items,
    serialize_task,
    validate_filters,
)
from wuyi_seat_bot.web_settings_service import (
    build_network_settings_response,
    get_settings_response,
    open_diagnostics_target_response,
    save_settings_response,
    update_stability_enhancement_response,
)
from wuyi_seat_bot.web_status_service import (
    build_account_task_status,
    build_task_statuses_response,
)
from wuyi_seat_bot.web_sync_service import (
    ManualSyncCoordinator,
    compute_sync_button_state,
    get_server_sync_settings_response,
    save_server_sync_settings_response,
    sync_server_managed_automation_plans_to_local,
    upload_local_automation_plans_to_server,
    upload_local_accounts_to_server,
)
from wuyi_seat_bot.web_task_service import (
    PreparedTaskRequest,
    build_created_task_batch_response,
    build_created_task_response,
    build_tasks_response,
    delete_task_response,
    execute_scheduled_task,
    prepare_task_request,
    read_task_batch_items,
)
from wuyi_seat_bot.seat_api import (
    SearchFilters,
    append_lab_json,
    build_date_options,
    build_default_filters,
    build_duration_options,
    build_people_options,
    build_time_options,
    fetch_my_booking_list,
    fetch_json,
    load_saved_session,
)

LOGGER = logging.getLogger(__name__)
TASK_STATUS_CACHE_RELATIVE_PATH = "runtime/task_status_cache.json"



@dataclass(frozen=True)
class SeatEntryContext:
    entry_url: str
    search_api_url: str
    search_page_payload: dict[str, Any]


@dataclass
class AccountRuntime:
    config: AppConfig
    state_path: Path
    automation: ApiSeatAutomation
    entry_cache: dict[str, SeatEntryContext] = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)
    action_lock: threading.Lock = field(default_factory=threading.Lock)


class SeatWebApp:
    def __init__(
        self,
        config_path: str | Path,
        account_name: str | None = None,
    ) -> None:
        self.config_path = Path(config_path)
        self.config_bundle = load_config_bundle(self.config_path)
        self.account_names = tuple(
            account.account_name for account in self.config_bundle.accounts
        )
        self.default_account_name = (
            self.config_bundle.get_account(account_name).account_name
            if self.account_names
            else ""
        )
        self._runtimes: dict[str, AccountRuntime] = {}
        self._runtime_lock = threading.Lock()
        self._task_status_cache_path = resolve_project_path(
            self.config_path, TASK_STATUS_CACHE_RELATIVE_PATH
        )
        self._task_status_cache_lock = threading.Lock()
        self._service_state_lock = threading.Lock()
        self._service_restart_counts = {
            "taskScheduler": 0,
            "automationScheduler": 0,
            "checkinMonitor": 0,
        }
        self.scheduler = LocalTaskScheduler(
            resolve_project_path(self.config_path, "runtime/scheduled_tasks.json"),
            self._execute_scheduled_task,
        )
        self._checkin_recovery_lock = threading.Lock()
        self.automation_scheduler = LocalAutomationPlanScheduler(
            resolve_project_path(self.config_path, "runtime/automation_plans.json"),
            self._execute_automation_action,
        )
        self._reload_config_bundle(self.config_bundle)
        self.checkin_monitor = PeriodicAccountCheckinMonitor(
            list_account_names=self._list_account_names_for_periodic_checkin,
            execute_checkin=self._run_periodic_account_checkin,
        )
        self.network_monitor = NetworkMonitor(self.config_path)
        self.sync_coordinator = ManualSyncCoordinator(self.config_path)
        self.scheduler.start()
        self.automation_scheduler.start()
        self.checkin_monitor.start()

    def close(self) -> None:
        self.checkin_monitor.stop()
        self.scheduler.stop()
        self.automation_scheduler.stop()

    def ensure_background_services(self) -> None:
        with self._service_state_lock:
            if not self.scheduler.is_running():
                self.scheduler.start()
                self._service_restart_counts["taskScheduler"] += 1
            if not self.automation_scheduler.is_running():
                self.automation_scheduler.start()
                self._service_restart_counts["automationScheduler"] += 1
            if not self.checkin_monitor.is_running():
                self.checkin_monitor.start()
                self._service_restart_counts["checkinMonitor"] += 1

    def get_service_snapshot(self) -> dict[str, Any]:
        tasks = self.scheduler.list_tasks()
        plans = self.automation_scheduler.list_plans()
        with self._service_state_lock:
            restart_counts = dict(self._service_restart_counts)
        return {
            "defaultAccountName": self.default_account_name,
            "taskSchedulerAlive": self.scheduler.is_running(),
            "automationSchedulerAlive": self.automation_scheduler.is_running(),
            "checkinMonitorAlive": self.checkin_monitor.is_running(),
            "taskSchedulerRestartCount": restart_counts["taskScheduler"],
            "automationSchedulerRestartCount": restart_counts["automationScheduler"],
            "checkinMonitorRestartCount": restart_counts["checkinMonitor"],
            "pendingTaskCount": sum(
                1 for task in tasks if task.status in {PENDING_STATUS, RUNNING_STATUS}
            ),
            "automationPlanCount": len(plans),
            "enabledAutomationPlanCount": sum(1 for plan in plans if plan.enabled),
        }

    def get_settings(self) -> dict[str, Any]:
        return get_settings_response(
            config_path=self.config_path,
            service_snapshot=self.get_service_snapshot(),
        )

    def save_settings(self, payload: dict[str, Any]) -> dict[str, Any]:
        return save_settings_response(
            config_path=self.config_path,
            payload=payload,
            service_snapshot=self.get_service_snapshot(),
        )

    def run_network_check(self) -> dict[str, Any]:
        return build_network_settings_response(
            config_path=self.config_path,
            network_status=self._get_network_monitor().detect_once(),
            service_snapshot=self.get_service_snapshot(),
        )

    def run_network_reconnect(self) -> dict[str, Any]:
        return build_network_settings_response(
            config_path=self.config_path,
            network_status=self._get_network_monitor().reconnect_once(),
            service_snapshot=self.get_service_snapshot(),
        )

    def update_stability_enhancement(self, payload: dict[str, Any]) -> dict[str, Any]:
        return update_stability_enhancement_response(
            config_path=self.config_path,
            payload=payload,
            service_snapshot=self.get_service_snapshot(),
        )

    def open_diagnostics_target(self, payload: dict[str, Any]) -> dict[str, Any]:
        return open_diagnostics_target_response(
            config_path=self.config_path,
            payload=payload,
            service_snapshot=self.get_service_snapshot(),
        )

    # ------------------------------------------------------------------ #
    # Manual_Sync_Action（spec account-pool-tri-sync 任务 11.13）            #
    # ------------------------------------------------------------------ #

    def get_sync_state(self) -> dict[str, Any]:
        """返回同步按钮三态，供 UI 显示「服务端可达 / 不可达 / 未配置」指示。

        本端点仅读取 ``config.json`` 中的 ``server_sync`` 段，不发起网络请求；
        Local_Only_Mode 下也可安全调用（Requirement 13.3 / 13.9 / 12.6）。
        """

        return compute_sync_button_state(self.config_path)

    def preview_manual_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        """触发 Manual_Sync_Action 预览阶段：接口 A → 接口 B → compute_diff。

        参考 design.md「数据流 4：Manual_Sync_Action 全流程」与 Requirement 13.3 /
        13.4 / 13.5 / 13.8。响应字段见 :meth:`ManualSyncCoordinator.preview`。
        """

        del payload
        coordinator = self._get_sync_coordinator()
        return coordinator.preview()

    def apply_manual_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        """根据弹窗内 Sync_Selection 应用差异。

        ``payload`` 形如 ``{"token": "<uuid>", "selection": {sid: bool}}``；
        ``selection`` 为空 dict 或全部 false 时视为无操作（Requirement 13.15 /
        13.16）；token 过期时返回 ``token_expired``，由 UI 引导用户重新点击同步。
        """

        token = str(payload.get("token") or "")
        selection_raw = payload.get("selection")
        if not isinstance(selection_raw, dict):
            selection_raw = {}
        coordinator = self._get_sync_coordinator()
        result = coordinator.apply(token, selection_raw)
        if result.get("ok") and not result.get("noop"):
            automation_result = self._reload_config_bundle()
            if isinstance(automation_result, dict):
                result["automationPlanSync"] = automation_result
                automation_message = str(
                    automation_result.get("message") or ""
                ).strip()
                if automation_message:
                    result["message"] = f"{result['message']}；{automation_message}"
        return result

    def cancel_manual_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        """主动丢弃一次 Manual_Sync_Action 预览会话（弹窗「取消」时调用）。"""

        token = str(payload.get("token") or "")
        if not token:
            return {"ok": True}
        coordinator = self._get_sync_coordinator()
        return coordinator.cancel(token)

    def upload_local_accounts(self, payload: dict[str, Any]) -> dict[str, Any]:
        """把本地账号基础配置上传到服务端 Active_Pool。"""

        del payload
        return upload_local_accounts_to_server(config_path=self.config_path)

    def upload_local_automation_plans(self, payload: dict[str, Any]) -> dict[str, Any]:
        """把本地自动任务计划上传到服务端。"""

        del payload
        return upload_local_automation_plans_to_server(
            config_path=self.config_path,
            plans=self.automation_scheduler.list_plans(),
        )

    def get_server_sync_settings(self) -> dict[str, Any]:
        """读取当前 ``server_sync`` 配置，供账号管理页「服务端配置」弹窗渲染。"""

        return get_server_sync_settings_response(config_path=self.config_path)

    def save_server_sync_settings(self, payload: dict[str, Any]) -> dict[str, Any]:
        """保存账号管理页提交的 ``server_sync`` 配置；保存后同步按钮三态会按
        新配置在前端下次轮询时刷新。
        """

        return save_server_sync_settings_response(
            config_path=self.config_path, payload=payload
        )

    def _get_sync_coordinator(self) -> ManualSyncCoordinator:
        coordinator = getattr(self, "sync_coordinator", None)
        if coordinator is None:
            coordinator = ManualSyncCoordinator(self.config_path)
            self.sync_coordinator = coordinator
        return coordinator


    def get_bootstrap(
        self,
        account_name: str | None = None,
        seat_url: str | None = None,
    ) -> dict[str, Any]:
        task_status_cache = self._read_task_status_cache()
        if not self.account_names:
            return {
                "accounts": [],
                "selectedAccountName": "",
                "seatUrls": [],
                "supportsMultiReserve": False,
                "multiReserveHint": "请先新增账号后再查询座位。",
                "supportsTaskReserve": True,
                "taskHint": "没有账号也可以先空着；需要预约、签到或签退时，再到账号管理里新增账号。",
                "loginStateReady": False,
                "loginStatePath": "",
                "serverTime": datetime.now().replace(microsecond=0).isoformat(),
                "taskStatuses": [],
                "taskStatusLoadedAt": "",
                "defaults": {
                    "accountName": "",
                    "seatUrl": "",
                    "date": "",
                    "startHour": "",
                    "durationHours": 1,
                    "peopleCount": 1,
                },
                "dateOptions": [],
                "timeOptions": [],
                "durationOptions": [],
                "peopleOptions": [],
                "constraints": None,
                "message": "当前还没有账号，请先到“账号管理”里新建账号。",
            }

        runtime = self._get_account_runtime(account_name)
        target_url = _choose_target_seat_url(runtime.config.seat_urls, seat_url)
        login_state_ready = runtime.state_path.exists()
        base_payload = {
            "accounts": build_account_options(
                self.config_bundle, runtime.config.account_name
            ),
            "selectedAccountName": runtime.config.account_name,
            "seatUrls": build_seat_url_options(runtime.config.seat_urls),
            "supportsMultiReserve": False,
            "multiReserveHint": "当前网页已支持单人直接预约。多人预约会展示人数和选座结果，但还未补同行人信息，暂不直接提交。",
            "supportsTaskReserve": True,
            "taskHint": "定时任务依赖当前 exe 服务持续运行。现在默认会启用守护监控；如果工作进程异常退出，守护进程会自动拉起。",
            "loginStateReady": login_state_ready,
            "loginStateValid": login_state_ready,
            "loginStatePath": str(runtime.state_path),
            "serverTime": datetime.now().replace(microsecond=0).isoformat(),
            "taskStatuses": task_status_cache["statuses"],
            "taskStatusLoadedAt": task_status_cache["serverTime"],
        }

        try:
            context = self._get_entry_context(runtime, target_url)
        except ApiRequestError as exc:
            return {
                **base_payload,
                "loginStateValid": login_state_ready
                and not is_invalid_login_state_error(exc),
                "defaults": {
                    "accountName": runtime.config.account_name,
                    "seatUrl": target_url,
                    "date": "",
                    "startHour": "",
                    "durationHours": 1,
                    "peopleCount": 1,
                },
                "dateOptions": [],
                "timeOptions": [],
                "durationOptions": [],
                "peopleOptions": [],
                "constraints": None,
                "message": exc.message,
            }

        default_filters = build_default_filters(context.search_page_payload)
        return {
            **base_payload,
            "loginStateValid": True,
            "defaults": {
                "accountName": runtime.config.account_name,
                "seatUrl": target_url,
                "date": default_filters.date_value,
                "startHour": default_filters.start_hour,
                "durationHours": default_filters.duration_hours,
                "peopleCount": default_filters.people_count,
            },
            "dateOptions": build_date_options(context.search_page_payload),
            "timeOptions": build_time_options(context.search_page_payload),
            "durationOptions": build_duration_options(
                context.search_page_payload,
                start_hour=default_filters.start_hour,
            ),
            "peopleOptions": build_people_options(context.search_page_payload),
            "constraints": build_query_constraints(context.search_page_payload),
            "message": "",
        }

    def _get_network_monitor(self) -> NetworkMonitor:
        monitor = getattr(self, "network_monitor", None)
        if monitor is None:
            monitor = NetworkMonitor(self.config_path)
            self.network_monitor = monitor
        return monitor

    def search(self, payload: dict[str, Any]) -> dict[str, Any]:
        account_name = read_account_name(
            payload, self.account_names, self.default_account_name
        )
        runtime = self._get_account_runtime(account_name)
        filters = parse_filters_payload(
            payload, runtime.config.seat_urls, account_name=runtime.config.account_name
        )
        context = self._get_entry_context(runtime, filters.seat_url)
        saved_session = self._load_saved_session(runtime)
        return search_seats_response(
            account_name=runtime.config.account_name,
            filters=filters,
            context=context,
            saved_session=saved_session,
            payload=payload,
        )

    def reserve(self, payload: dict[str, Any]) -> dict[str, Any]:
        account_name = read_account_name(
            payload, self.account_names, self.default_account_name
        )
        runtime = self._get_account_runtime(account_name)
        filters = parse_filters_payload(
            payload, runtime.config.seat_urls, account_name=runtime.config.account_name
        )
        context = self._get_entry_context(runtime, filters.seat_url)
        saved_session = self._load_saved_session(runtime)
        return reserve_selected_seat_response(
            account_name=runtime.config.account_name,
            filters=filters,
            context=context,
            saved_session=saved_session,
            payload=payload,
        )

    def checkin(self, payload: dict[str, Any]) -> dict[str, Any]:
        return {"message": self._run_account_action(ActionType.CHECKIN, payload)}

    def checkin_all_accounts(self, payload: dict[str, Any]) -> dict[str, Any]:
        del payload
        results: list[dict[str, Any]] = []
        for account_name in self.account_names:
            try:
                action_result = self._run_account_action_result(
                    ActionType.CHECKIN,
                    {"accountName": account_name},
                )
                results.append(
                    {
                        "accountName": account_name,
                        "success": action_result.success,
                        "message": action_result.message,
                    }
                )
            except ApiRequestError as exc:
                results.append(
                    {
                        "accountName": account_name,
                        "success": False,
                        "message": exc.message,
                    }
                )
        return build_checkin_all_response(results)

    def checkout(self, payload: dict[str, Any]) -> dict[str, Any]:
        return {"message": self._run_account_action(ActionType.CHECKOUT, payload)}

    def cancel_booking(self, payload: dict[str, Any]) -> dict[str, Any]:
        account_name = read_account_name(
            payload, self.account_names, self.default_account_name
        )
        booking_id = _read_required_text_field(payload.get("bookingId"), "bookingId")
        runtime = self._get_account_runtime(account_name)

        def _cancel() -> dict[str, Any]:
            return cancel_booking_response(
                account_name=runtime.config.account_name,
                saved_session=self._load_saved_session(runtime),
                booking_id=booking_id,
            )

        action_lock = getattr(runtime, "action_lock", None)
        if action_lock is None:
            return _cancel()
        with action_lock:
            return _cancel()

    def list_accounts(self, selected_account_name: str | None = None) -> dict[str, Any]:
        return build_accounts_response(
            config_path=self.config_path,
            config_bundle=self.config_bundle,
            account_names=self.account_names,
            default_account_name=self.default_account_name,
            selected_account_name=selected_account_name,
        )

    def save_account(self, payload: dict[str, Any]) -> dict[str, Any]:
        result = save_account_mutation(self.config_path, payload)
        self._reload_config_bundle(result.bundle)
        return build_account_mutation_response(
            result,
            self.list_accounts(selected_account_name=result.selected_account_name),
        )

    def import_accounts(self, payload: dict[str, Any]) -> dict[str, Any]:
        result = import_accounts_mutation(
            self.config_path,
            payload,
            self.config_bundle,
        )
        self._reload_config_bundle(result.bundle)
        return build_account_import_response(
            result,
            self.list_accounts(selected_account_name=result.selected_account_name),
        )

    def refresh_account_login(self, payload: dict[str, Any]) -> dict[str, Any]:
        if read_submitted_student_id(payload) is not None:
            bundle, account_name = save_account_bundle_from_payload(
                self.config_path, payload
            )
            self._reload_config_bundle(bundle)
        else:
            account_name = read_account_name(
                payload, self.account_names, self.default_account_name
            )
        runtime = self._get_account_runtime(account_name)
        self._refresh_runtime_login_state(runtime)
        return {
            "message": f"登录态已刷新：{runtime.config.account_name}",
            **self.list_accounts(selected_account_name=runtime.config.account_name),
        }

    def refresh_account_logins(self, payload: dict[str, Any]) -> dict[str, Any]:
        del payload
        results: list[dict[str, Any]] = []
        for account_name in self.account_names:
            runtime = self._get_account_runtime(account_name)
            if runtime.state_path.exists():
                results.append(
                    {
                        "accountName": account_name,
                        "success": True,
                        "skipped": True,
                        "message": "已保存登录态",
                    }
                )
                continue
            try:
                self._refresh_runtime_login_state(runtime)
            except ApiRequestError as exc:
                results.append(
                    {
                        "accountName": account_name,
                        "success": False,
                        "skipped": False,
                        "message": exc.message,
                    }
                )
            else:
                results.append(
                    {
                        "accountName": account_name,
                        "success": True,
                        "skipped": False,
                        "message": "登录态已刷新",
                    }
                )

        success_count = sum(
            1
            for result in results
            if result["success"] and not result["skipped"]
        )
        skipped_count = sum(1 for result in results if result["skipped"])
        failed_count = sum(1 for result in results if not result["success"])
        if not results:
            message = "当前没有账号，请先新建账号"
        elif failed_count:
            message = (
                f"登录状态获取完成：补齐 {success_count} 个，"
                f"已保存 {skipped_count} 个，失败 {failed_count} 个"
            )
        elif success_count:
            message = f"已为 {success_count} 个账号保存登录态"
        else:
            message = f"{skipped_count} 个账号都已保存登录态"

        return {
            "message": message,
            "loginRefreshResult": {
                "successCount": success_count,
                "skippedCount": skipped_count,
                "failedCount": failed_count,
                "results": results,
            },
            **self.list_accounts(selected_account_name=self.default_account_name),
        }

    def _refresh_runtime_login_state(self, runtime: AccountRuntime) -> None:
        try:
            runtime.automation.save_login_state(wait_for_enter=False, timeout_ms=300000)
        except TimeoutError as exc:
            raise ApiRequestError(str(exc), HTTPStatus.REQUEST_TIMEOUT) from exc
        except Exception as exc:  # noqa: BLE001
            raise ApiRequestError(
                f"刷新认证失败：{exc}", HTTPStatus.BAD_GATEWAY
            ) from exc

        with runtime.lock:
            # 登录态刷新后立即清空入口缓存，避免继续复用旧会话下拿到的入口信息。
            runtime.entry_cache.clear()

    def set_default_account(self, payload: dict[str, Any]) -> dict[str, Any]:
        result = set_default_account_mutation(self.config_path, payload)
        self._reload_config_bundle(result.bundle)
        return build_account_mutation_response(
            result,
            self.list_accounts(selected_account_name=result.selected_account_name),
        )

    def delete_account(self, payload: dict[str, Any]) -> dict[str, Any]:
        account_name = read_account_name_field(payload)
        result = delete_account_mutation(
            self.config_path,
            account_name,
            self.scheduler.list_tasks(),
            automation_plan_exists=self.automation_scheduler.find_plan_by_account(
                account_name
            )
            is not None,
        )
        self._reload_config_bundle(result.bundle)
        return build_account_mutation_response(
            result,
            self.list_accounts(selected_account_name=result.selected_account_name),
        )

    def delete_accounts(self, payload: dict[str, Any]) -> dict[str, Any]:
        account_names = read_account_names_field(payload)
        plan_exists = self.automation_scheduler.find_plan_by_account
        result = delete_accounts_mutation(
            self.config_path,
            account_names,
            self.scheduler.list_tasks(),
            existing_account_names=self.account_names,
            automation_plan_exists=lambda account_name: plan_exists(account_name)
            is not None,
        )
        self._reload_config_bundle(result.bundle)
        return build_account_mutation_response(
            result,
            self.list_accounts(selected_account_name=result.selected_account_name),
        )

    def list_tasks(self) -> dict[str, Any]:
        return build_tasks_response(self.scheduler.list_tasks())

    def list_automation_plans(self) -> dict[str, Any]:
        return build_automation_plans_response(self.automation_scheduler.list_plans())

    def run_automation_reserve_now(self, payload: dict[str, Any]) -> dict[str, Any]:
        del payload
        plans = [
            plan
            for plan in self.automation_scheduler.list_plans()
            if plan.enabled and plan.reserve_enabled
        ]
        if not plans:
            return build_manual_reserve_check_response([])

        now = datetime.now()
        return build_manual_reserve_check_response(
            [self._run_manual_reserve_check_for_plan(plan, now) for plan in plans]
        )

    def inspect_task_statuses(self, account_name: str | None = None) -> dict[str, Any]:
        if account_name:
            account_names = (
                read_account_name(
                    {"accountName": account_name},
                    self.account_names,
                    self.default_account_name,
                ),
            )
        else:
            account_names = self.account_names

        tasks = self.scheduler.list_tasks()
        automation_scheduler = getattr(self, "automation_scheduler", None)
        plans = (
            automation_scheduler.list_plans()
            if automation_scheduler is not None
            else []
        )
        response = build_task_statuses_response(
            account_names,
            tasks,
            plans,
            self._build_account_task_status,
        )
        self._save_task_status_cache(response)
        return response

    def _get_task_status_cache_path(self) -> Path | None:
        return getattr(self, "_task_status_cache_path", None)

    def _get_task_status_cache_lock(self) -> threading.Lock:
        lock = getattr(self, "_task_status_cache_lock", None)
        if lock is None:
            lock = threading.Lock()
            self._task_status_cache_lock = lock
        return lock

    def _read_task_status_cache(self) -> dict[str, Any]:
        cache_path = self._get_task_status_cache_path()
        if cache_path is None or not cache_path.exists():
            return {"statuses": [], "serverTime": ""}
        with self._get_task_status_cache_lock():
            return self._read_task_status_cache_unlocked(cache_path)

    def _read_task_status_cache_unlocked(self, cache_path: Path) -> dict[str, Any]:
        try:
            payload = json.loads(cache_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            LOGGER.warning("读取账号状态缓存失败：%s", exc)
            return {"statuses": [], "serverTime": ""}
        return self._normalize_task_status_cache(payload)

    def _normalize_task_status_cache(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            return {"statuses": [], "serverTime": ""}
        account_names = set(getattr(self, "account_names", ()))
        statuses = [
            status
            for status in payload.get("statuses", [])
            if isinstance(status, dict)
            and str(status.get("accountName", "")).strip() in account_names
        ]
        status_by_account = {status["accountName"]: status for status in statuses}
        return {
            "statuses": [
                status_by_account[account_name]
                for account_name in getattr(self, "account_names", ())
                if account_name in status_by_account
            ],
            "serverTime": str(payload.get("serverTime", "") or ""),
        }

    def _save_task_status_cache(self, response: dict[str, Any]) -> None:
        cache_path = self._get_task_status_cache_path()
        if cache_path is None:
            return
        with self._get_task_status_cache_lock():
            cached = (
                self._read_task_status_cache_unlocked(cache_path)
                if cache_path.exists()
                else {"statuses": [], "serverTime": ""}
            )
            status_by_account = {
                status["accountName"]: status for status in cached["statuses"]
            }
            for status in response.get("statuses", []):
                if isinstance(status, dict) and str(
                    status.get("accountName", "")
                ).strip():
                    status_by_account[str(status["accountName"])] = status
            payload = {
                "serverTime": str(response.get("serverTime", "") or ""),
                "statuses": [
                    status_by_account[account_name]
                    for account_name in getattr(self, "account_names", ())
                    if account_name in status_by_account
                ],
            }
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            temporary_path = cache_path.with_name(cache_path.name + ".tmp")
            temporary_path.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            temporary_path.replace(cache_path)

    def save_automation_plan(self, payload: dict[str, Any]) -> dict[str, Any]:
        account_name = read_account_name(
            payload, self.account_names, self.default_account_name
        )
        runtime = self._get_account_runtime(account_name)
        request = read_automation_plan_request(
            payload,
            account_name=account_name,
            seat_urls=runtime.config.seat_urls,
        )
        context = self._get_entry_context(runtime, request.seat_url)
        validate_filters(
            context.search_page_payload,
            build_search_filters_for_plan(request),
        )

        existing_plan = resolve_existing_automation_plan(
            self.automation_scheduler, request.raw_plan_id, account_name
        )
        plan = build_plan_from_request(request, existing_plan)
        saved_plan = self.automation_scheduler.save_plan(plan)
        return build_saved_automation_plan_response(
            saved_plan, updated=existing_plan is not None
        )

    def delete_automation_plan(self, payload: dict[str, Any]) -> dict[str, Any]:
        return delete_automation_plan_response(self.automation_scheduler, payload)

    def _run_manual_reserve_check_for_plan(
        self,
        plan: AutomationPlan,
        now: datetime,
    ) -> dict[str, Any]:
        login_state_ready = False
        try:
            runtime = self._get_account_runtime(plan.account_name)
            login_state_ready = runtime.state_path.exists()
            if login_state_ready:
                result = self._execute_manual_reserve_check(plan, now)
            else:
                result = AutomationActionResult(message="未保存登录态")
            classification = classify_manual_reserve_result(
                result, login_state_ready=login_state_ready
            )
        except ApiRequestError as exc:
            result = AutomationActionResult(message=exc.message)
            classification = "failed"
        except Exception as exc:  # noqa: BLE001
            result = AutomationActionResult(message=str(exc))
            classification = "failed"

        self.automation_scheduler.apply_manual_reserve_result(
            plan.plan_id, now, result
        )
        return build_manual_reserve_result_entry(plan, classification, result)

    def create_task(self, payload: dict[str, Any]) -> dict[str, Any]:
        prepared = self._prepare_task_request(payload)
        task = self._add_prepared_task(prepared)
        return build_created_task_response(task)

    def create_task_batch(self, payload: dict[str, Any]) -> dict[str, Any]:
        prepared_items = [
            self._prepare_task_request(item) for item in read_task_batch_items(payload)
        ]
        created_tasks = [self._add_prepared_task(item) for item in prepared_items]
        return build_created_task_batch_response(created_tasks)

    def delete_task(self, payload: dict[str, Any]) -> dict[str, Any]:
        return delete_task_response(self.scheduler, payload)

    def _execute_scheduled_task(self, task: ScheduledTask) -> str:
        return execute_scheduled_task(
            task,
            reserve=self.reserve,
            run_account_action=lambda action, payload: self._run_account_action(
                action,
                payload,
                error_type=RuntimeError,
            ),
        )

    def _add_prepared_task(self, prepared: PreparedTaskRequest) -> ScheduledTask:
        return self.scheduler.add_task(
            action=prepared.action,
            account_name=prepared.account_name,
            run_at=prepared.run_at,
            summary=prepared.summary,
            payload=prepared.payload,
        )

    def _build_account_task_status(
        self,
        account_name: str,
        tasks: list[ScheduledTask],
        plan: AutomationPlan | None = None,
    ) -> dict[str, Any]:
        runtime = self._get_account_runtime(account_name)
        return build_account_task_status(
            account_name=runtime.config.account_name,
            state_path_exists=runtime.state_path.exists(),
            tasks=tasks,
            plan=plan,
            load_booking_list_payload=lambda: self._load_booking_list_payload(runtime),
        )

    def _load_booking_list_payload(self, runtime: AccountRuntime) -> dict[str, Any]:
        saved_session = self._load_saved_session(runtime)
        return fetch_my_booking_list(saved_session.cookie_header)

    def _prepare_task_request(self, payload: dict[str, Any]) -> PreparedTaskRequest:
        return prepare_task_request(
            payload=payload,
            account_names=self.account_names,
            default_account_name=self.default_account_name,
            load_account_config=lambda account_name: self._get_account_runtime(
                account_name
            ).config,
            load_search_page_payload=self._load_search_page_payload,
            search_seat_map=lambda search_payload: self.search(search_payload)["seatMap"],
        )

    def _load_search_page_payload(
        self, account_name: str, seat_url: str
    ) -> dict[str, Any]:
        runtime = self._get_account_runtime(account_name)
        context = self._get_entry_context(runtime, seat_url)
        return context.search_page_payload

    def _run_account_action(
        self,
        action: ActionType,
        payload: dict[str, Any],
        *,
        error_type: type[Exception] = ApiRequestError,
    ) -> str:
        return run_account_action(
            action,
            payload,
            run_action_result=self._run_account_action_result,
            error_type=error_type,
        )

    def _run_account_action_result(
        self,
        action: ActionType,
        payload: dict[str, Any],
    ) -> ActionResult:
        return run_account_action_result(
            action,
            payload,
            account_names=self.account_names,
            default_account_name=self.default_account_name,
            run_action_once=self._run_account_action_result_once,
            recover_checkins=lambda account_name, result: self._recover_checkins_after_network_failure(
                trigger_account_name=account_name,
                original_result=result,
            ),
            recover_network=self._recover_network_after_action_failure,
        )

    def _run_account_action_result_once(
        self,
        action: ActionType,
        account_name: str,
    ) -> ActionResult:
        return run_account_action_result_once(
            action,
            account_name,
            get_runtime=self._get_account_runtime,
        )

    def _recover_checkins_after_network_failure(
        self,
        *,
        trigger_account_name: str,
        original_result: ActionResult,
    ) -> ActionResult | None:
        recovery_lock = self._ensure_recovery_lock()
        return recover_checkins_after_network_failure(
            trigger_account_name=trigger_account_name,
            original_result=original_result,
            recovery_lock=recovery_lock,
            get_network_monitor=self._get_network_monitor,
            list_recovery_checkin_accounts=self._list_recovery_checkin_accounts,
            run_checkin_once=lambda account_name: self._run_account_action_result_once(
                ActionType.CHECKIN,
                account_name,
            ),
        )

    def _recover_network_after_action_failure(
        self,
        original_result: ActionResult,
    ) -> ActionResult:
        recovery_lock = self._ensure_recovery_lock()
        return recover_network_after_action_failure(
            original_result=original_result,
            recovery_lock=recovery_lock,
            get_network_monitor=self._get_network_monitor,
        )

    def _ensure_recovery_lock(self) -> threading.Lock:
        recovery_lock = getattr(self, "_checkin_recovery_lock", None)
        if recovery_lock is None:
            recovery_lock = threading.Lock()
            self._checkin_recovery_lock = recovery_lock
        return recovery_lock

    def _list_recovery_checkin_accounts(self) -> tuple[str, ...]:
        return list_recovery_checkin_accounts(
            self.account_names,
            self._account_needs_immediate_checkin,
        )

    def _account_needs_immediate_checkin(self, account_name: str) -> bool:
        return account_needs_immediate_checkin(
            lambda: self._load_booking_list_payload(
                self._get_account_runtime(account_name)
            )
        )

    def _load_saved_session(self, runtime: AccountRuntime):
        try:
            return load_saved_session(runtime.state_path)
        except FileNotFoundError as exc:
            raise ApiRequestError(
                (
                    f"未找到账号“{runtime.config.account_name}”的登录态文件：{runtime.state_path}。"
                    "请先在网页的“账号管理”里点击“刷新认证”；"
                    f'如果你更习惯命令行，也可以执行 save-login --account "{runtime.config.account_name}"。'
                ),
                HTTPStatus.PRECONDITION_FAILED,
            ) from exc
        except ValueError as exc:
            raise ApiRequestError(str(exc), HTTPStatus.PRECONDITION_FAILED) from exc

    def _get_account_runtime(self, account_name: str | None = None) -> AccountRuntime:
        account = self.config_bundle.get_account(
            account_name or self.default_account_name
        )
        with self._runtime_lock:
            runtime = self._runtimes.get(account.account_name)
            if runtime is None:
                runtime = AccountRuntime(
                    config=account,
                    state_path=resolve_project_path(
                        self.config_path, account.state_file
                    ),
                    automation=ApiSeatAutomation(
                        config=account,
                        config_path=self.config_path,
                    ),
                )
                self._runtimes[account.account_name] = runtime
        return runtime

    def _reload_config_bundle(
        self, config_bundle: ConfigBundle | None = None
    ) -> dict[str, Any]:
        self.config_bundle = config_bundle or load_config_bundle(self.config_path)
        self.default_account_name = self.config_bundle.default_account_name
        self.account_names = tuple(
            account.account_name for account in self.config_bundle.accounts
        )
        with self._runtime_lock:
            # 账号配置变更后直接清空运行时缓存，避免旧入口缓存和旧登录态路径残留。
            self._runtimes = {}
        self._prune_orphan_automation_plans()
        return sync_server_managed_automation_plans_to_local(
            config_path=self.config_path,
            automation_scheduler=self.automation_scheduler,
        )

    def _prune_orphan_automation_plans(self) -> None:
        scheduler = getattr(self, "automation_scheduler", None)
        if scheduler is None:
            return
        removed = scheduler.prune_plans_for_unknown_accounts(self.account_names)
        if not removed:
            return
        worker_logger = logging.getLogger("wuyi-seat-bot.service-worker")
        for plan in removed:
            worker_logger.warning(
                "已自动清理孤儿自动任务计划：plan_id=%s account_name=%s 自习室=%s 座位=%s",
                plan.plan_id,
                plan.account_name,
                plan.room_name or "未指定",
                plan.seat_number or "未设置",
            )

    def _list_account_names_for_periodic_checkin(self) -> tuple[str, ...]:
        return self.account_names

    def _run_periodic_account_checkin(self, account_name: str) -> bool:
        result = self._run_account_action_result(
            ActionType.CHECKIN,
            {"accountName": account_name},
        )
        if result.success:
            return True
        if "当前没有待签到预约" in result.message:
            return True
        if "当前没有座位预约记录" in result.message:
            return True
        return False

    def _get_entry_context(
        self,
        runtime: AccountRuntime,
        seat_url: str,
        *,
        force_refresh: bool = False,
    ) -> SeatEntryContext:
        if not force_refresh:
            with runtime.lock:
                cached = runtime.entry_cache.get(seat_url)
            if cached is not None:
                return cached

        saved_session = self._load_saved_session(runtime)

        try:
            search_api_url = runtime.automation.resolve_search_api_url(seat_url)
            search_page_payload = fetch_json(
                append_lab_json(search_api_url),
                cookie_header=saved_session.cookie_header,
            )
        except urllib.error.HTTPError as exc:
            raise ApiRequestError(
                f"获取预约入口失败：HTTP {exc.code}", HTTPStatus.BAD_GATEWAY
            ) from exc
        except ValueError as exc:
            raise ApiRequestError(str(exc), HTTPStatus.BAD_REQUEST) from exc
        except Exception as exc:  # noqa: BLE001
            raise ApiRequestError(
                f"获取预约入口失败：{exc}", HTTPStatus.BAD_GATEWAY
            ) from exc

        context = SeatEntryContext(
            entry_url=seat_url,
            search_api_url=search_api_url,
            search_page_payload=search_page_payload,
        )
        with runtime.lock:
            runtime.entry_cache[seat_url] = context
        return context

    def _execute_automation_action(
        self,
        plan: AutomationPlan,
        action: str,
        now: datetime,
    ) -> AutomationActionResult:
        return execute_automation_action(
            plan=plan,
            action=action,
            now=now,
            execute_reserve=self._execute_automation_reserve,
            run_checkin=lambda account_name: self._run_account_action_result(
                ActionType.CHECKIN,
                {"accountName": account_name},
            ),
            run_checkout=lambda account_name: self._run_account_action(
                ActionType.CHECKOUT,
                {"accountName": account_name},
                error_type=RuntimeError,
            ),
        )

    def _execute_automation_reserve(
        self,
        plan: AutomationPlan,
        now: datetime,
    ) -> AutomationActionResult:
        runtime = self._get_account_runtime(plan.account_name)
        context = self._get_entry_context(runtime, plan.seat_url, force_refresh=True)
        saved_session = self._load_saved_session(runtime)
        return execute_automation_reserve(
            plan=plan,
            now=now,
            search_page_payload=context.search_page_payload,
            booking_list_payload=self._load_booking_list_payload(runtime),
            reserve_once=lambda filters: self._try_automation_reserve_once(
                runtime=runtime,
                saved_session=saved_session,
                context=context,
                filters=filters,
                room_id=plan.room_id,
                room_name=plan.room_name,
                seat_number=plan.seat_number,
            ),
            wait_reserve_gap=lambda reason: self._wait_automation_reserve_gap(
                reason=reason
            ),
        )

    def _execute_manual_reserve_check(
        self,
        plan: AutomationPlan,
        now: datetime,
    ) -> AutomationActionResult:
        runtime = self._get_account_runtime(plan.account_name)
        context = self._get_entry_context(runtime, plan.seat_url, force_refresh=True)
        saved_session = self._load_saved_session(runtime)
        return execute_manual_reserve_check(
            plan=plan,
            now=now,
            search_page_payload=context.search_page_payload,
            booking_list_payload=self._load_booking_list_payload(runtime),
            reserve_once=lambda filters: self._try_automation_reserve_once(
                runtime=runtime,
                saved_session=saved_session,
                context=context,
                filters=filters,
                room_id=plan.room_id,
                room_name=plan.room_name,
                seat_number=plan.seat_number,
            ),
            wait_reserve_gap=lambda reason: self._wait_automation_reserve_gap(
                reason=reason
            ),
        )

    def _try_automation_reserve_once(
        self,
        *,
        runtime: AccountRuntime,
        saved_session,
        context: SeatEntryContext,
        filters: SearchFilters,
        room_id: str,
        room_name: str,
        seat_number: str,
    ) -> str | None:
        return try_automation_reserve_once(
            reserve_seat=lambda: self._reserve_by_seat_number(
                runtime=runtime,
                saved_session=saved_session,
                context=context,
                filters=filters,
                room_id=room_id,
                room_name=room_name,
                seat_number=seat_number,
            ),
            wait_reserve_gap=lambda reason: self._wait_automation_reserve_gap(
                reason=reason
            ),
        )

    def _wait_automation_reserve_gap(self, *, reason: str) -> None:
        wait_automation_reserve_gap(reason=reason)

    def _reserve_by_seat_number(
        self,
        *,
        runtime: AccountRuntime,
        saved_session,
        context: SeatEntryContext,
        filters: SearchFilters,
        room_id: str,
        room_name: str,
        seat_number: str,
    ) -> None:
        reserve_seat_by_number(
            filters=filters,
            context=context,
            saved_session=saved_session,
            room_id=room_id,
            room_name=room_name,
            seat_number=seat_number,
        )


def start_web_server(
    config_path: str | Path,
    *,
    host: str = "127.0.0.1",
    port: int = 8765,
    open_browser: bool = True,
    account_name: str | None = None,
) -> int:
    app = SeatWebApp(
        config_path,
        account_name=account_name,
    )
    server = SeatWebServer((host, port), app)
    actual_host, actual_port = server.server_address
    browser_host = "127.0.0.1" if actual_host in {"0.0.0.0", ""} else actual_host
    browser_url = f"http://{browser_host}:{actual_port}/"

    print(f"本地选座界面已启动：{browser_url}")
    print(f"当前默认账号：{app.default_account_name or '未配置账号'}")
    print("按 Ctrl+C 可停止服务。")

    if open_browser:
        try:
            webbrowser.open(browser_url)
        except Exception:  # noqa: BLE001
            print("自动打开浏览器失败，请手动复制上面的地址到浏览器访问。")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("已停止本地选座界面。")
    finally:
        server.server_close()
        app.close()
    return 0


def _choose_target_seat_url(
    seat_urls: tuple[str, ...], requested_url: str | None
) -> str:
    if requested_url and requested_url in seat_urls:
        return requested_url
    return seat_urls[0]
