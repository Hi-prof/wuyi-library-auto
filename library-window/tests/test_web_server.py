import json
import unittest
from datetime import datetime
from http import HTTPStatus
from io import BytesIO
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock, patch

from wuyi_seat_bot.automation_plans import (
    AutomationActionResult,
    LocalAutomationPlanScheduler,
    build_automation_plan,
)
from wuyi_seat_bot.seat_api import SearchFilters
from wuyi_seat_bot.web_account_service import (
    build_account_options,
    read_account_name,
    serialize_account_profile,
)
from wuyi_seat_bot.web_action_service import build_checkin_all_response
from wuyi_seat_bot.web_automation_service import (
    build_automation_reserve_filters,
    build_automation_target_dates,
    build_manual_reserve_check_response,
    build_manual_reserve_filters,
    build_manual_reserve_result_entry,
    classify_manual_reserve_result,
    execute_manual_reserve_check,
)
from wuyi_seat_bot.web_errors import ApiRequestError
from wuyi_seat_bot.web_reservation_service import (
    build_query_constraints,
    build_reservation_hint,
    build_scheduled_checkin_summary,
    build_scheduled_checkout_summary,
    build_scheduled_reserve_summary,
    normalize_selected_seat_ids,
    parse_filters_payload,
    parse_task_action,
    parse_task_run_at,
    serialize_automation_plan,
    serialize_booking_status_items,
    serialize_task,
    validate_filters,
)
from wuyi_seat_bot.web_routes import SeatWebRequestHandler
from wuyi_seat_bot.web_server import SeatWebApp
from wuyi_seat_bot.models import ActionResult, ActionType, AppConfig, ConfigBundle
from wuyi_seat_bot.scheduler import ScheduledTask


class WebServerHelpersTestCase(unittest.TestCase):
    def _build_search_page_payload(self) -> dict:
        return {
            "data": {
                "default": {"date": 1774656000, "duration": 1, "num": 1},
                "range": {
                    "max_date": 1774915200,
                    "minBeginTime": 8,
                    "maxEndTime": 22,
                    "min_duration": 1,
                    "max_duration": 14,
                    "max_num": 4,
                },
                "space_category": {"category_id": "591", "content_id": "28"},
            }
        }

    def test_parse_filters_payload_reads_valid_json_fields(self) -> None:
        filters = parse_filters_payload(
            {
                "seatUrl": "https://example.com/entry",
                "date": "2026-03-28",
                "startHour": 8,
                "durationHours": "2",
                "peopleCount": 1,
            },
            ("https://example.com/entry",),
        )

        self.assertEqual(
            filters,
            SearchFilters(
                seat_url="https://example.com/entry",
                date_value="2026-03-28",
                start_hour=8,
                duration_hours=2,
                people_count=1,
            ),
        )

    def test_parse_filters_payload_rejects_unknown_seat_url(self) -> None:
        with self.assertRaisesRegex(ApiRequestError, "seatUrl 不在配置允许范围内"):
            parse_filters_payload(
                {
                    "seatUrl": "https://example.com/other",
                    "date": "2026-03-28",
                    "startHour": 8,
                    "durationHours": 1,
                    "peopleCount": 1,
                },
                ("https://example.com/entry",),
            )

    def test_normalize_selected_seat_ids_deduplicates_blank_values(self) -> None:
        self.assertEqual(
            normalize_selected_seat_ids(["21422", "21422", " ", "21426"]),
            ("21422", "21426"),
        )

    def test_validate_filters_rejects_invalid_duration(self) -> None:
        with self.assertRaisesRegex(
            ApiRequestError, "所选使用时长不在当前可预约范围内"
        ):
            validate_filters(
                self._build_search_page_payload(),
                SearchFilters(
                    seat_url="https://example.com/entry",
                    date_value="2026-03-28",
                    start_hour=21,
                    duration_hours=3,
                    people_count=1,
                ),
            )

    def test_build_query_constraints_extracts_numeric_limits(self) -> None:
        self.assertEqual(
            build_query_constraints(self._build_search_page_payload()),
            {
                "minBeginTime": 8,
                "maxEndTime": 22,
                "minDuration": 1,
                "maxDuration": 14,
                "maxPeopleCount": 4,
            },
        )

    def test_serialize_booking_status_items_does_not_mark_cancelled_booking_as_checkin_ready(
        self,
    ) -> None:
        bookings = serialize_booking_status_items(
            {
                "content": {
                    "defaultItems": [
                        {
                            "id": 21546358,
                            "roomName": "自习室圆形二楼",
                            "seatNum": "165",
                            "status": "4",
                            "time": "1774656000",
                            "duration": "3600",
                            "nowTime": 1774656200,
                            "limitSignAgo": "900",
                            "limitSignBack": "900",
                            "ibeacons": [],
                        }
                    ]
                }
            }
        )

        self.assertEqual(len(bookings), 1)
        self.assertFalse(bookings[0]["checkinWindowOpen"])
        self.assertEqual(bookings[0]["durationSeconds"], 3600)
        self.assertEqual(bookings[0]["endAtLabel"], "2026-03-28 09:00")

    def test_build_reservation_hint_distinguishes_single_and_multi_people(self) -> None:
        self.assertIn("单人直接预约", build_reservation_hint(1))
        self.assertIn("暂不支持直接提交多人预约", build_reservation_hint(2))

    def test_read_account_name_uses_default_when_missing(self) -> None:
        self.assertEqual(
            read_account_name({}, ("主号", "室友"), "主号"),
            "主号",
        )

    def test_read_account_name_rejects_unknown_account(self) -> None:
        with self.assertRaisesRegex(ApiRequestError, "accountName 不在配置允许范围内"):
            read_account_name({"accountName": "陌生人"}, ("主号", "室友"), "主号")

    def test_read_account_name_rejects_when_no_accounts_exist(self) -> None:
        with self.assertRaisesRegex(ApiRequestError, "当前还没有账号"):
            read_account_name({}, (), "")

    def test_build_account_options_marks_default_account(self) -> None:
        bundle = ConfigBundle(
            accounts=(
                AppConfig(
                    login_url="https://example.com/login-a",
                    state_file="runtime/auth-a.json",
                    seat_urls=("https://example.com/seat/a",),
                    account_name="主号",
                ),
                AppConfig(
                    login_url="https://example.com/login-b",
                    state_file="runtime/auth-b.json",
                    seat_urls=("https://example.com/seat/b",),
                    account_name="室友",
                ),
            ),
            default_account_name="主号",
        )

        self.assertEqual(
            build_account_options(bundle, "室友"),
            [
                {"value": "主号", "label": "主号（默认）", "selected": "false"},
                {"value": "室友", "label": "室友", "selected": "true"},
            ],
        )

    def test_build_account_options_includes_student_id_when_name_differs(self) -> None:
        bundle = ConfigBundle(
            accounts=(
                AppConfig(
                    login_url="https://example.com/login-a",
                    state_file="runtime/auth-a.json",
                    seat_urls=("https://example.com/seat/a",),
                    account_name="主号",
                    student_id="20231121130",
                ),
            ),
            default_account_name="主号",
        )

        self.assertEqual(
            build_account_options(bundle, "主号"),
            [
                {
                    "value": "主号",
                    "label": "主号 · 20231121130（默认）",
                    "selected": "true",
                },
            ],
        )

    def test_parse_task_action_rejects_unknown_action(self) -> None:
        with self.assertRaisesRegex(
            ApiRequestError, "action 仅支持 reserve、checkin 或 checkout"
        ):
            parse_task_action({"action": "other"})

    def test_parse_task_action_accepts_checkout(self) -> None:
        self.assertEqual(parse_task_action({"action": "checkout"}), "checkout")

    def test_parse_task_run_at_accepts_future_datetime(self) -> None:
        run_at = parse_task_run_at({"runAt": "2099-03-28T08:00"})

        self.assertEqual(run_at, "2099-03-28T08:00:00")

    def test_serialize_task_returns_user_friendly_fields(self) -> None:
        task = ScheduledTask(
            task_id="abc123",
            action="reserve",
            account_name="主号",
            run_at="2026-03-28T08:00:00",
            created_at="2026-03-27T23:00:00",
            summary="主号 · 定时预约",
            payload={"accountName": "主号"},
        )

        self.assertEqual(
            serialize_task(task),
            {
                "taskId": "abc123",
                "action": "reserve",
                "accountName": "主号",
                "runAt": "2026-03-28T08:00:00",
                "createdAt": "2026-03-27T23:00:00",
                "summary": "主号 · 定时预约",
                "status": "pending",
                "lastMessage": "",
                "finishedAt": None,
            },
        )

    def test_delete_task_returns_deleted_summary(self) -> None:
        app = object.__new__(SeatWebApp)
        deleted_task = ScheduledTask(
            task_id="task001",
            action="checkin",
            account_name="主号",
            run_at="2026-03-28T08:00:00",
            created_at="2026-03-27T23:00:00",
            summary="主号 · 定时签到",
            payload={"accountName": "主号"},
        )
        app.scheduler = SimpleNamespace(
            delete_task=MagicMock(return_value=deleted_task)
        )

        result = app.delete_task({"taskId": "task001"})

        app.scheduler.delete_task.assert_called_once_with("task001")
        self.assertEqual(
            result, {"message": "已删除定时任务：主号 · 定时签到", "taskId": "task001"}
        )

    def test_delete_task_requires_task_id(self) -> None:
        app = object.__new__(SeatWebApp)
        app.scheduler = SimpleNamespace(delete_task=MagicMock())

        with self.assertRaisesRegex(ApiRequestError, "taskId 不能为空"):
            app.delete_task({})

    def test_build_scheduled_reserve_summary_includes_seat_number(self) -> None:
        summary = build_scheduled_reserve_summary(
            SearchFilters(
                account_name="主号",
                seat_url="https://example.com/entry",
                date_value="2026-03-28",
                start_hour=8,
                duration_hours=1,
                people_count=1,
            ),
            "58",
        )

        self.assertIn("58 号座位", summary)

    def test_build_scheduled_checkin_summary_contains_account_name(self) -> None:
        self.assertEqual(build_scheduled_checkin_summary("主号"), "主号 · 定时签到")

    def test_build_scheduled_checkout_summary_contains_account_name(self) -> None:
        self.assertEqual(build_scheduled_checkout_summary("主号"), "主号 · 定时签退")

    def test_serialize_account_profile_includes_state_flags(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            state_path = Path(tmp_dir) / "runtime" / "auth-main.json"
            state_path.parent.mkdir(parents=True, exist_ok=True)
            state_path.write_text("{}", encoding="utf-8")

            bundle = ConfigBundle(
                accounts=(
                    AppConfig(
                        login_url="https://example.com/login-a",
                        state_file="runtime/auth-main.json",
                        seat_urls=(
                            "https://example.com/seat/a",
                            "https://example.com/seat/b",
                        ),
                        account_name="主号",
                        preferred_seat_numbers=("18", "28"),
                    ),
                ),
                default_account_name="主号",
            )

            profile = serialize_account_profile(
                account=bundle.accounts[0],
                config_bundle=bundle,
                config_path=config_path,
                selected_account_name="主号",
            )

        self.assertTrue(profile["loginStateReady"])
        self.assertTrue(profile["isDefault"])
        self.assertTrue(profile["isSelected"])
        self.assertEqual(profile["studentId"], "主号")
        self.assertFalse(profile["passwordConfigured"])
        self.assertEqual(profile["seatUrlCount"], 2)
        self.assertEqual(profile["preferredSeatCount"], 2)

    def test_list_accounts_allows_empty_config(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.config_bundle = ConfigBundle(accounts=(), default_account_name="")
        app.account_names = ()
        app.default_account_name = ""

        result = app.list_accounts()

        self.assertEqual(result["defaultAccountName"], "")
        self.assertEqual(result["selectedAccountName"], "")
        self.assertEqual(result["accounts"], [])

    def test_get_settings_returns_app_settings_runtime_state_and_service_snapshot(
        self,
    ) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.get_service_snapshot = lambda: {"taskSchedulerAlive": True}

        with (
            patch(
                "wuyi_seat_bot.web_settings_service.load_app_settings",
                return_value={
                    "networkMonitoring": {
                        "enabled": True,
                        "intervalMinutes": 120,
                        "preferredWifiNames": ["Campus"],
                    }
                },
            ),
            patch(
                "wuyi_seat_bot.web_settings_service.load_network_monitor_status",
                return_value={"networkState": "online", "message": "网络连接正常"},
            ),
        ):
            result = app.get_settings()

        self.assertTrue(result["settings"]["networkMonitoring"]["enabled"])
        self.assertEqual(result["networkStatus"]["networkState"], "online")
        self.assertTrue(result["serviceSnapshot"]["taskSchedulerAlive"])

    def test_save_settings_returns_persisted_settings_and_latest_runtime_state(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.get_service_snapshot = lambda: {"taskSchedulerAlive": True}

        with (
            patch(
                "wuyi_seat_bot.web_settings_service.save_app_settings",
                return_value={
                    "networkMonitoring": {
                        "enabled": False,
                        "intervalMinutes": 180,
                        "preferredWifiNames": ["Dorm"],
                    }
                },
            ) as save_settings,
            patch(
                "wuyi_seat_bot.web_settings_service.load_network_monitor_status",
                return_value={"networkState": "unknown", "message": "尚未执行网络检测"},
            ),
        ):
            result = app.save_settings(
                {
                    "networkMonitoring": {
                        "enabled": False,
                        "intervalMinutes": 180,
                        "preferredWifiNames": ["Dorm"],
                    }
                }
            )

        save_settings.assert_called_once()
        self.assertFalse(result["settings"]["networkMonitoring"]["enabled"])
        self.assertEqual(result["networkStatus"]["networkState"], "unknown")
        self.assertTrue(result["serviceSnapshot"]["taskSchedulerAlive"])

    def test_run_network_check_returns_latest_monitor_result(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.get_service_snapshot = lambda: {"taskSchedulerAlive": True}
        app.network_monitor = SimpleNamespace(
            detect_once=MagicMock(
                return_value={"networkState": "offline", "message": "通用连通性检测失败"}
            )
        )

        with patch(
            "wuyi_seat_bot.web_settings_service.load_app_settings",
            return_value={
                "networkMonitoring": {
                    "enabled": True,
                    "intervalMinutes": 120,
                    "preferredWifiNames": [],
                }
            },
        ):
            result = app.run_network_check()

        self.assertEqual(result["networkStatus"]["networkState"], "offline")
        app.network_monitor.detect_once.assert_called_once()

    def test_update_stability_enhancement_enables_manager_and_returns_state(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.get_service_snapshot = lambda: {"taskSchedulerAlive": True}

        with (
            patch(
                "wuyi_seat_bot.web_settings_service.StabilityEnhancementManager",
            ) as manager_class,
            patch(
                "wuyi_seat_bot.web_settings_service.load_app_settings",
                return_value={
                    "networkMonitoring": {
                        "enabled": True,
                        "intervalMinutes": 120,
                        "preferredWifiNames": [],
                    }
                },
            ),
            patch(
                "wuyi_seat_bot.web_settings_service.load_network_monitor_status",
                return_value={"networkState": "online", "message": "网络连接正常"},
            ),
        ):
            manager = manager_class.return_value
            manager.enable.return_value = "已启用程序稳定性增强"
            manager.is_enabled.return_value = True

            result = app.update_stability_enhancement({"enabled": True})

        manager.enable.assert_called_once()
        self.assertTrue(result["stabilityEnhancementEnabled"])
        self.assertIn("已启用", result["message"])

    def test_open_diagnostics_target_opens_worker_log(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.get_service_snapshot = lambda: {"taskSchedulerAlive": True}

        with (
            patch("wuyi_seat_bot.web_settings_service.open_log_target") as open_target,
            patch(
                "wuyi_seat_bot.web_settings_service.load_app_settings",
                return_value={
                    "networkMonitoring": {
                        "enabled": True,
                        "intervalMinutes": 120,
                        "preferredWifiNames": [],
                    }
                },
            ),
            patch(
                "wuyi_seat_bot.web_settings_service.load_network_monitor_status",
                return_value={"networkState": "online", "message": "网络连接正常"},
            ),
        ):
            result = app.open_diagnostics_target({"target": "workerLog"})

        open_target.assert_called_once()
        self.assertIn("已打开", result["message"])

    def test_get_bootstrap_returns_empty_state_when_no_accounts_exist(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.config_bundle = ConfigBundle(accounts=(), default_account_name="")
        app.account_names = ()
        app.default_account_name = ""

        result = app.get_bootstrap()

        self.assertEqual(result["accounts"], [])
        self.assertEqual(result["selectedAccountName"], "")
        self.assertEqual(result["seatUrls"], [])
        self.assertEqual(result["defaults"]["accountName"], "")
        self.assertEqual(result["taskStatuses"], [])
        self.assertEqual(result["taskStatusLoadedAt"], "")
        self.assertIn("当前还没有账号", result["message"])

    def test_get_bootstrap_includes_cached_task_statuses(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.config_bundle = ConfigBundle(accounts=(), default_account_name="主号")
        app.account_names = ("主号",)
        app.default_account_name = "主号"

        with tempfile.TemporaryDirectory() as tmp_dir:
            cache_path = Path(tmp_dir) / "runtime" / "task_status_cache.json"
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            cache_path.write_text(
                json.dumps(
                    {
                        "serverTime": "2026-05-12T08:30:00",
                        "statuses": [
                            {
                                "accountName": "主号",
                                "state": "active",
                                "summary": "签到成功，使用中",
                            },
                            {
                                "accountName": "旧号",
                                "state": "empty",
                                "summary": "旧账号缓存",
                            },
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            app._task_status_cache_path = cache_path
            app._task_status_cache_lock = threading.Lock()

            runtime = SimpleNamespace(
                config=AppConfig(
                    login_url="https://example.com/login-a",
                    state_file="runtime/auth-main.json",
                    seat_urls=("https://example.com/seat/a",),
                    account_name="主号",
                ),
                state_path=Path("runtime/auth-main.json"),
            )
            app._get_account_runtime = lambda account_name=None: runtime
            app._get_entry_context = MagicMock(
                side_effect=ApiRequestError(
                    "当前登录态已失效，请重新执行 save-login",
                    HTTPStatus.PRECONDITION_FAILED,
                )
            )

            with patch("pathlib.Path.exists", return_value=True):
                result = app.get_bootstrap()

        self.assertEqual(result["taskStatusLoadedAt"], "2026-05-12T08:30:00")
        self.assertEqual(len(result["taskStatuses"]), 1)
        self.assertEqual(result["taskStatuses"][0]["accountName"], "主号")
        self.assertEqual(result["taskStatuses"][0]["summary"], "签到成功，使用中")

    def test_get_bootstrap_marks_invalid_saved_login_state(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("config.json")
        app.config_bundle = ConfigBundle(accounts=(), default_account_name="主号")
        app.account_names = ("主号",)
        app.default_account_name = "主号"

        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            ),
            state_path=Path("runtime/auth-main.json"),
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app._get_entry_context = MagicMock(
            side_effect=ApiRequestError(
                "当前登录态已失效，请重新执行 save-login",
                HTTPStatus.PRECONDITION_FAILED,
            )
        )

        with patch("pathlib.Path.exists", return_value=True):
            result = app.get_bootstrap()

        self.assertTrue(result["loginStateReady"])
        self.assertFalse(result["loginStateValid"])
        self.assertIn("当前登录态已失效", result["message"])

    def test_search_wraps_seat_map_parse_error_as_api_request_error(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"

        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            )
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app._get_entry_context = lambda runtime_arg, seat_url: SimpleNamespace(
            search_api_url="https://example.com/api/searchSeats",
            search_page_payload=self._build_search_page_payload(),
        )
        app._load_saved_session = lambda runtime_arg: SimpleNamespace(
            cookie_header="auth=token",
            user_id="405181",
        )

        payload = {
            "seatUrl": "https://example.com/seat/a",
            "date": "2026-03-28",
            "startHour": 8,
            "durationHours": 1,
            "peopleCount": 1,
        }

        with (
            patch("wuyi_seat_bot.web_reservation_service.fetch_json", return_value={}),
            patch(
                "wuyi_seat_bot.web_reservation_service.serialize_seat_map",
                side_effect=ValueError("未找到座位数据，当前查询条件可能暂无可选座位"),
            ),
        ):
            with self.assertRaisesRegex(
                ApiRequestError, "查询座位失败：未找到座位数据"
            ):
                app.search(payload)

    def test_search_returns_room_options_from_single_response(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"

        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            )
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app._get_entry_context = lambda runtime_arg, seat_url: SimpleNamespace(
            search_api_url="https://example.com/api/searchSeats",
            search_page_payload=self._build_search_page_payload(),
        )
        app._load_saved_session = lambda runtime_arg: SimpleNamespace(
            cookie_header="auth=token",
            user_id="405181",
        )

        payload = {
            "seatUrl": "https://example.com/seat/a",
            "date": "2026-03-28",
            "startHour": 8,
            "durationHours": 1,
            "peopleCount": 1,
        }
        search_result_payload = {
            "content": {
                "children": [
                    {
                        "children": {
                            "ui_type": "ht.Seat.RecommendSeatItem",
                            "roomName": "自习室圆形一楼",
                            "ifRecommend": True,
                            "seatMap": {
                                "info": {
                                    "id": "1152",
                                    "plan": "",
                                    "width": "100",
                                    "height": "50",
                                },
                                "POIs": [
                                    {
                                        "id": "seat-a",
                                        "title": "19",
                                        "x": "1",
                                        "y": "1",
                                        "w": "1",
                                        "h": "1",
                                        "state": 0,
                                    },
                                    {
                                        "id": "seat-rec",
                                        "title": "18",
                                        "x": "2",
                                        "y": "2",
                                        "w": "1",
                                        "h": "1",
                                        "state": 2,
                                    },
                                ],
                            },
                        }
                    }
                ]
            },
            "allContent": {
                "children": [
                    {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "综合阅览室",
                        "ifRecommend": False,
                        "seatMap": {
                            "info": {
                                "id": "1154",
                                "plan": "",
                                "width": "100",
                                "height": "50",
                            },
                            "POIs": [
                                {
                                    "id": "seat-main",
                                    "title": "88",
                                    "x": "3",
                                    "y": "3",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                }
                            ],
                        },
                    },
                    {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "自习室圆形二楼",
                        "ifRecommend": False,
                        "seatMap": {
                            "info": {
                                "id": "1153",
                                "plan": "",
                                "width": "100",
                                "height": "50",
                            },
                            "POIs": [
                                {
                                    "id": "seat-b",
                                    "title": "58",
                                    "x": "4",
                                    "y": "4",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                }
                            ],
                        },
                    },
                    {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "自习室圆形一楼",
                        "ifRecommend": False,
                        "seatMap": {
                            "info": {
                                "id": "1152",
                                "plan": "",
                                "width": "100",
                                "height": "50",
                            },
                            "POIs": [
                                {
                                    "id": "seat-c",
                                    "title": "28",
                                    "x": "5",
                                    "y": "5",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                }
                            ],
                        },
                    },
                ]
            },
        }

        with patch(
            "wuyi_seat_bot.web_reservation_service.fetch_json",
            return_value=search_result_payload,
        ):
            result = app.search(payload)

        self.assertEqual(result["selectedRoomId"], "1152")
        self.assertEqual(result["selectedRoomName"], "自习室圆形一楼")
        self.assertEqual(
            [option["roomName"] for option in result["roomOptions"]],
            ["综合阅览室", "自习室圆形二楼", "自习室圆形一楼"],
        )
        self.assertEqual(len(result["rooms"]), 3)
        self.assertEqual(result["seatMap"]["roomId"], "1152")
        self.assertEqual(result["seatMap"]["systemRecommendedSeatNumber"], "18")

    def test_refresh_account_login_calls_automation_and_clears_entry_cache(
        self,
    ) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"

        automation = MagicMock()
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            ),
            automation=automation,
            entry_cache={"https://example.com/seat/a": object()},
            lock=threading.Lock(),
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app.list_accounts = lambda selected_account_name=None: {
            "selectedAccountName": selected_account_name
        }

        result = app.refresh_account_login({"accountName": "主号"})

        automation.save_login_state.assert_called_once_with(
            wait_for_enter=False, timeout_ms=300000
        )
        self.assertEqual(runtime.entry_cache, {})
        self.assertEqual(
            result,
            {
                "message": "登录态已刷新：主号",
                "selectedAccountName": "主号",
            },
        )

    def test_refresh_account_login_saves_form_data_before_login(self) -> None:
        app = object.__new__(SeatWebApp)
        app.config_path = Path("C:/tmp/config.json")
        app.account_names = ()
        app.default_account_name = ""

        automation = MagicMock()
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="20231121130",
            ),
            automation=automation,
            entry_cache={"https://example.com/seat/a": object()},
            lock=threading.Lock(),
        )
        reloaded_bundles: list[object] = []
        app._reload_config_bundle = lambda bundle=None: reloaded_bundles.append(bundle)
        app._get_account_runtime = lambda account_name=None: runtime
        app.list_accounts = lambda selected_account_name=None: {
            "selectedAccountName": selected_account_name
        }

        bundle = MagicMock()
        with patch(
            "wuyi_seat_bot.web_account_service.save_account_config", return_value=bundle
        ) as save_account_config_mock:
            result = app.refresh_account_login(
                {
                    "studentId": "20231121130",
                    "password": "123456",
                }
            )

        save_account_config_mock.assert_called_once_with(
            app.config_path,
            original_name=None,
            account_name="20231121130",
            student_id="20231121130",
            password="123456",
            preferred_seat_numbers=[],
        )
        automation.save_login_state.assert_called_once_with(
            wait_for_enter=False, timeout_ms=300000
        )
        self.assertEqual(reloaded_bundles, [bundle])
        self.assertEqual(runtime.entry_cache, {})
        self.assertEqual(
            result,
            {
                "message": "登录态已刷新：20231121130",
                "selectedAccountName": "20231121130",
            },
        )

    def test_apply_manual_sync_reloads_config_bundle_after_success(self) -> None:
        app = object.__new__(SeatWebApp)
        coordinator = MagicMock()
        coordinator.apply.return_value = {
            "ok": True,
            "added": 1,
            "replaced": 0,
            "removed": 0,
            "total": 1,
            "noop": False,
            "message": "同步成功：新增 1、替换 0、移除 0",
        }
        app.sync_coordinator = coordinator
        app._reload_config_bundle = MagicMock()

        result = app.apply_manual_sync(
            {"token": "tk", "selection": {"20231121130": True}}
        )

        coordinator.apply.assert_called_once_with("tk", {"20231121130": True})
        app._reload_config_bundle.assert_called_once_with()
        self.assertEqual(result["total"], 1)

    def test_apply_manual_sync_includes_server_managed_automation_plan_result(
        self,
    ) -> None:
        app = object.__new__(SeatWebApp)
        coordinator = MagicMock()
        coordinator.apply.return_value = {
            "ok": True,
            "added": 1,
            "replaced": 0,
            "removed": 0,
            "total": 1,
            "noop": False,
            "message": "同步成功：新增 1、替换 0、移除 0",
        }
        app.sync_coordinator = coordinator
        app._reload_config_bundle = MagicMock(
            return_value={
                "ok": True,
                "created": 1,
                "updated": 0,
                "removed": 0,
                "skipped": 0,
                "message": "本地自动任务已同步：新增 1、更新 0、移除 0、跳过 0",
            }
        )

        result = app.apply_manual_sync(
            {"token": "tk", "selection": {"20231121130": True}}
        )

        self.assertEqual(result["automationPlanSync"]["created"], 1)
        self.assertEqual(
            result["message"],
            "同步成功：新增 1、替换 0、移除 0；本地自动任务已同步：新增 1、更新 0、移除 0、跳过 0",
        )

    def test_checkout_runs_checkout_workflow(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"

        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            ),
            automation=MagicMock(),
        )
        app._get_account_runtime = lambda account_name=None: runtime

        with patch("wuyi_seat_bot.web_action_service.SeatWorkflow") as workflow_class:
            workflow_class.return_value.run.return_value = SimpleNamespace(
                success=True,
                message="签退成功",
            )

            result = app.checkout({"accountName": "主号"})

        workflow_class.assert_called_once_with(runtime.config, runtime.automation)
        workflow_class.return_value.run.assert_called_once_with(ActionType.CHECKOUT)
        self.assertEqual(result, {"message": "签退成功"})

    def test_checkin_runs_checkin_workflow(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"

        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            ),
            automation=MagicMock(),
        )
        app._get_account_runtime = lambda account_name=None: runtime

        with patch("wuyi_seat_bot.web_action_service.SeatWorkflow") as workflow_class:
            workflow_class.return_value.run.return_value = SimpleNamespace(
                success=True,
                message="签到成功",
            )

            result = app.checkin({"accountName": "主号"})

        workflow_class.assert_called_once_with(runtime.config, runtime.automation)
        workflow_class.return_value.run.assert_called_once_with(ActionType.CHECKIN)
        self.assertEqual(result, {"message": "签到成功"})

    def test_checkin_all_accounts_runs_each_account_in_order(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号", "室友")
        calls: list[str] = []

        def run_action(action: ActionType, payload: dict[str, Any]) -> Any:
            calls.append(payload["accountName"])
            if payload["accountName"] == "室友":
                raise ApiRequestError("未保存登录态")
            return SimpleNamespace(success=True, message="签到成功")

        app._run_account_action_result = run_action

        result = app.checkin_all_accounts({})

        self.assertEqual(calls, ["主号", "室友"])
        self.assertEqual(result["successCount"], 1)
        self.assertEqual(result["failedCount"], 1)
        self.assertEqual(result["message"], "签到完成：成功 1，失败 1")
        self.assertEqual(
            result["results"],
            [
                {
                    "accountName": "主号",
                    "success": True,
                    "message": "签到成功",
                },
                {
                    "accountName": "室友",
                    "success": False,
                    "message": "未保存登录态",
                },
            ],
        )

    def test_build_checkin_all_response_handles_empty_accounts(self) -> None:
        result = build_checkin_all_response([])

        self.assertEqual(result["successCount"], 0)
        self.assertEqual(result["failedCount"], 0)
        self.assertEqual(result["message"], "没有账号，先新建后再签到")

    def test_cancel_booking_calls_cancel_endpoint_for_pending_booking(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"

        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            ),
            action_lock=threading.Lock(),
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app._load_saved_session = lambda runtime_arg: SimpleNamespace(
            cookie_header="auth=token"
        )

        booking_payload = {
            "content": {
                "defaultItems": [
                    {
                        "id": 21546358,
                        "roomName": "自习室圆形二楼",
                        "seatNum": "165",
                        "status": "0",
                        "time": "1774656000",
                        "duration": "3600",
                        "nowTime": 1774656200,
                        "limitSignAgo": "900",
                        "limitSignBack": "900",
                        "ibeacons": [],
                    }
                ]
            }
        }

        with (
            patch(
                "wuyi_seat_bot.web_reservation_service.fetch_my_booking_list",
                return_value=booking_payload,
            ),
            patch(
                "wuyi_seat_bot.web_reservation_service.perform_seat_cancel_booking",
                return_value=SimpleNamespace(success=True, message="取消成功"),
            ) as cancel_mock,
        ):
            result = app.cancel_booking(
                {"accountName": "主号", "bookingId": "21546358"}
            )

        cancel_mock.assert_called_once_with("auth=token", "21546358")
        self.assertEqual(
            result,
            {
                "message": "已取消预约：主号 · 自习室圆形二楼 165 号座位",
                "bookingId": "21546358",
            },
        )

    def test_run_account_action_result_uses_runtime_action_lock(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        action_lock = _TrackingLock()
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            ),
            automation=MagicMock(),
            action_lock=action_lock,
        )
        app._get_account_runtime = lambda account_name=None: runtime

        with patch("wuyi_seat_bot.web_action_service.SeatWorkflow") as workflow_class:
            workflow_class.return_value.run.return_value = SimpleNamespace(
                success=True,
                message="签到成功",
            )

            result = app._run_account_action_result(
                ActionType.CHECKIN, {"accountName": "主号"}
            )

        self.assertEqual(action_lock.enter_count, 1)
        self.assertTrue(result.success)

    def test_checkin_failure_reconnects_and_retries_pending_accounts(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号", "室友")
        app.default_account_name = "主号"
        app._checkin_recovery_lock = threading.Lock()
        monitor = SimpleNamespace(
            detect_once=MagicMock(
                return_value={"networkState": "offline", "message": "通用网络探测未通过"}
            ),
            reconnect_once=MagicMock(
                return_value={"networkState": "online", "message": "网络连接正常"}
            ),
        )
        app._get_network_monitor = lambda: monitor
        action_results = {
            "主号": [
                ActionResult(
                    success=False,
                    action=ActionType.CHECKIN,
                    seat_url=None,
                    attempts=2,
                    message="签到接口执行异常：urlopen error",
                ),
                ActionResult(
                    success=True,
                    action=ActionType.CHECKIN,
                    seat_url="https://example.com/seat/a",
                    attempts=1,
                    message="主号签到成功",
                ),
            ],
            "室友": [
                ActionResult(
                    success=True,
                    action=ActionType.CHECKIN,
                    seat_url="https://example.com/seat/b",
                    attempts=1,
                    message="室友签到成功",
                )
            ],
        }
        app._run_account_action_result_once = MagicMock(
            side_effect=lambda action, account_name: action_results[account_name].pop(0)
        )
        app._list_recovery_checkin_accounts = MagicMock(return_value=("主号", "室友"))

        result = app._run_account_action_result(
            ActionType.CHECKIN,
            {"accountName": "主号"},
        )

        self.assertTrue(result.success)
        self.assertEqual(result.message, "主号签到成功")
        monitor.detect_once.assert_called_once_with()
        monitor.reconnect_once.assert_called_once_with()
        app._list_recovery_checkin_accounts.assert_called_once_with()
        self.assertEqual(
            app._run_account_action_result_once.mock_calls,
            [
                unittest.mock.call(ActionType.CHECKIN, "主号"),
                unittest.mock.call(ActionType.CHECKIN, "主号"),
                unittest.mock.call(ActionType.CHECKIN, "室友"),
            ],
        )

    def test_checkin_failure_does_not_reconnect_when_network_is_online(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        app._checkin_recovery_lock = threading.Lock()
        monitor = SimpleNamespace(
            detect_once=MagicMock(
                return_value={"networkState": "online", "message": "网络连接正常"}
            ),
            reconnect_once=MagicMock(),
        )
        failed_result = ActionResult(
            success=False,
            action=ActionType.CHECKIN,
            seat_url=None,
            attempts=2,
            message="蓝牙扫描失败",
        )
        app._get_network_monitor = lambda: monitor
        app._run_account_action_result_once = MagicMock(return_value=failed_result)
        app._list_recovery_checkin_accounts = MagicMock()

        result = app._run_account_action_result(
            ActionType.CHECKIN,
            {"accountName": "主号"},
        )

        self.assertEqual(result, failed_result)
        monitor.detect_once.assert_called_once_with()
        monitor.reconnect_once.assert_not_called()
        app._list_recovery_checkin_accounts.assert_not_called()

    def test_checkout_failure_triggers_network_recovery_when_offline(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        app._checkin_recovery_lock = threading.Lock()
        monitor = SimpleNamespace(
            detect_once=MagicMock(
                return_value={"networkState": "offline", "message": "通用网络探测未通过"}
            ),
            reconnect_once=MagicMock(
                return_value={"networkState": "online", "message": "网络连接正常"}
            ),
        )
        failed_result = ActionResult(
            success=False,
            action=ActionType.CHECKOUT,
            seat_url=None,
            attempts=1,
            message="签退接口执行异常：urlopen error",
        )
        app._get_network_monitor = lambda: monitor
        app._run_account_action_result_once = MagicMock(return_value=failed_result)

        result = app._run_account_action_result(
            ActionType.CHECKOUT,
            {"accountName": "主号"},
        )

        # 失败但网络已恢复：原消息后追加一条提示，业务部分不补做
        self.assertFalse(result.success)
        self.assertIn("签退接口执行异常", result.message)
        self.assertIn("网络已自动重连", result.message)
        monitor.detect_once.assert_called_once_with()
        monitor.reconnect_once.assert_called_once_with()
        app._run_account_action_result_once.assert_called_once_with(
            ActionType.CHECKOUT, "主号"
        )

    def test_checkout_failure_skips_network_recovery_when_online(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        app._checkin_recovery_lock = threading.Lock()
        monitor = SimpleNamespace(
            detect_once=MagicMock(
                return_value={"networkState": "online", "message": "网络连接正常"}
            ),
            reconnect_once=MagicMock(),
        )
        failed_result = ActionResult(
            success=False,
            action=ActionType.CHECKOUT,
            seat_url=None,
            attempts=1,
            message="签退失败：座位已结束",
        )
        app._get_network_monitor = lambda: monitor
        app._run_account_action_result_once = MagicMock(return_value=failed_result)

        result = app._run_account_action_result(
            ActionType.CHECKOUT,
            {"accountName": "主号"},
        )

        # 网络在线时不应触发重连，也不在消息后追加恢复提示
        self.assertEqual(result, failed_result)
        monitor.detect_once.assert_called_once_with()
        monitor.reconnect_once.assert_not_called()

    def test_ensure_background_services_restarts_checkin_monitor(self) -> None:
        app = object.__new__(SeatWebApp)
        app._service_state_lock = threading.Lock()
        app._service_restart_counts = {
            "taskScheduler": 0,
            "automationScheduler": 0,
            "checkinMonitor": 0,
        }
        app.scheduler = SimpleNamespace(is_running=lambda: True, start=MagicMock())
        app.automation_scheduler = SimpleNamespace(
            is_running=lambda: True, start=MagicMock()
        )
        app.checkin_monitor = SimpleNamespace(
            is_running=lambda: False, start=MagicMock()
        )

        app.ensure_background_services()

        app.checkin_monitor.start.assert_called_once_with()
        self.assertEqual(app._service_restart_counts["checkinMonitor"], 1)

    def test_get_service_snapshot_includes_checkin_monitor_state(self) -> None:
        app = object.__new__(SeatWebApp)
        app.default_account_name = "主号"
        app._service_state_lock = threading.Lock()
        app._service_restart_counts = {
            "taskScheduler": 1,
            "automationScheduler": 2,
            "checkinMonitor": 3,
        }
        app.scheduler = SimpleNamespace(is_running=lambda: True, list_tasks=lambda: [])
        app.automation_scheduler = SimpleNamespace(
            is_running=lambda: True,
            list_plans=lambda: [
                SimpleNamespace(enabled=True),
                SimpleNamespace(enabled=False),
            ],
        )
        app.checkin_monitor = SimpleNamespace(is_running=lambda: True)

        snapshot = app.get_service_snapshot()

        self.assertTrue(snapshot["checkinMonitorAlive"])
        self.assertEqual(snapshot["checkinMonitorRestartCount"], 3)

    def test_create_task_batch_creates_multiple_tasks(self) -> None:
        app = object.__new__(SeatWebApp)
        app.scheduler = MagicMock()

        prepared_items = [
            SimpleNamespace(
                action="checkin",
                account_name="主号",
                run_at="2026-03-28T08:00:00",
                summary="主号 · 定时签到",
                payload={"accountName": "主号"},
            ),
            SimpleNamespace(
                action="checkout",
                account_name="主号",
                run_at="2026-03-28T21:59:00",
                summary="主号 · 定时签退",
                payload={"accountName": "主号"},
            ),
        ]
        app._prepare_task_request = MagicMock(side_effect=prepared_items)
        app.scheduler.add_task.side_effect = [
            ScheduledTask(
                task_id="task001",
                action="checkin",
                account_name="主号",
                run_at="2026-03-28T08:00:00",
                created_at="2026-03-27T23:00:00",
                summary="主号 · 定时签到",
                payload={"accountName": "主号"},
            ),
            ScheduledTask(
                task_id="task002",
                action="checkout",
                account_name="主号",
                run_at="2026-03-28T21:59:00",
                created_at="2026-03-27T23:00:00",
                summary="主号 · 定时签退",
                payload={"accountName": "主号"},
            ),
        ]

        result = app.create_task_batch({"items": [{}, {}]})

        self.assertEqual(app._prepare_task_request.call_count, 2)
        self.assertEqual(app.scheduler.add_task.call_count, 2)
        self.assertEqual(result["message"], "已创建 2 个定时任务")
        self.assertEqual(
            [task["taskId"] for task in result["tasks"]], ["task001", "task002"]
        )

    def test_save_automation_plan_creates_plan(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        app.automation_scheduler = SimpleNamespace(
            get_plan=MagicMock(return_value=None),
            find_plan_by_account=MagicMock(return_value=None),
            save_plan=MagicMock(side_effect=lambda plan: plan),
        )
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            )
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app._get_entry_context = lambda runtime_arg, seat_url: SimpleNamespace(
            search_page_payload=self._build_search_page_payload(),
        )

        result = app.save_automation_plan(
            {
                "accountName": "主号",
                "seatUrl": "https://example.com/seat/a",
                "selectedRoomId": "1153",
                "selectedRoomName": "自习室圆形二楼",
                "seatNumber": "58",
                "selectedDate": "2026-03-28",
                "startHour": 8,
                "durationHours": 14,
                "reserveEnabled": True,
                "checkinEnabled": True,
                "checkoutEnabled": True,
                "continuousReserve": True,
                "reserveTime": "08:00",
                "checkinTime": "08:00",
                "checkoutTime": "21:59",
                "reserveCheckIntervalMinutes": 30,
            }
        )

        saved_plan = app.automation_scheduler.save_plan.call_args.args[0]
        self.assertEqual(result["message"], "自动任务计划已创建")
        self.assertEqual(saved_plan.account_name, "主号")
        self.assertEqual(saved_plan.room_id, "1153")
        self.assertEqual(saved_plan.room_name, "自习室圆形二楼")
        self.assertEqual(saved_plan.seat_number, "58")
        self.assertEqual(result["plan"]["roomId"], "1153")
        self.assertEqual(result["plan"]["roomName"], "自习室圆形二楼")
        self.assertTrue(result["plan"]["reserve"]["enabled"])
        self.assertEqual(result["plan"]["reserve"]["windowLabel"], "8:00 - 22:00")

    def test_serialize_automation_plan_includes_three_day_reserve_preview_slots(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            room_id="1153",
            room_name="自习室圆形二楼",
            seat_number="58",
            selected_date="2026-05-08",
            start_hour=15,
            duration_hours=7,
            reserve_enabled=True,
            checkin_enabled=True,
            checkout_enabled=True,
            continuous_reserve=True,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            reserve_check_interval_minutes=30,
            reserve_target_dates=("2026-05-08", "2026-05-09", "2026-05-10"),
            reserve_booked_dates=("2026-05-08", "2026-05-10"),
            now=datetime(2026, 5, 8, 15, 10),
        )

        result = serialize_automation_plan(plan, now=datetime(2026, 5, 8, 15, 10))

        self.assertEqual(
            result["reserve"]["previewSlots"],
            [
                {
                    "date": "2026-05-08",
                    "label": "今天",
                    "windowLabel": "15:00 - 22:00",
                    "booked": True,
                },
                {
                    "date": "2026-05-09",
                    "label": "明天",
                    "windowLabel": "8:00 - 22:00",
                    "booked": False,
                },
                {
                    "date": "2026-05-10",
                    "label": "后天",
                    "windowLabel": "8:00 - 22:00",
                    "booked": True,
                },
            ],
        )

    def test_execute_automation_reserve_books_all_missing_interface_dates(self) -> None:
        app = object.__new__(SeatWebApp)
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            )
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app._get_entry_context = (
            lambda runtime_arg, seat_url, force_refresh=False: SimpleNamespace(
                search_page_payload=self._build_search_page_payload(),
                search_api_url="https://example.com/api/searchSeats",
            )
        )
        app._load_saved_session = lambda runtime_arg: SimpleNamespace(
            cookie_header="auth=token",
            user_id="405181",
        )
        app._reserve_by_seat_number = MagicMock()
        app._wait_automation_reserve_gap = MagicMock()
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=True,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )
        booking_payload = {
            "content": {
                "defaultItems": [
                    {
                        "id": 21546358,
                        "roomName": "自习室圆形二楼",
                        "seatNum": "165",
                        "status": "0",
                        "time": "1774656000",
                        "duration": "3600",
                        "nowTime": 1774656200,
                        "limitSignAgo": "900",
                        "limitSignBack": "900",
                        "ibeacons": [],
                    }
                ]
            }
        }

        with patch(
            "wuyi_seat_bot.web_server.fetch_my_booking_list",
            return_value=booking_payload,
        ):
            result = app._execute_automation_reserve(plan, datetime(2026, 3, 28, 8, 0))

        reserved_dates = [
            call.kwargs["filters"].date_value
            for call in app._reserve_by_seat_number.call_args_list
        ]
        self.assertEqual(reserved_dates, ["2026-03-29", "2026-03-30", "2026-03-31"])
        self.assertEqual(
            [
                call.kwargs["reason"]
                for call in app._wait_automation_reserve_gap.call_args_list
            ],
            ["between-attempts", "between-attempts"],
        )
        self.assertEqual(
            result.target_dates,
            ("2026-03-28", "2026-03-29", "2026-03-30", "2026-03-31"),
        )
        self.assertEqual(
            result.booked_dates,
            ("2026-03-28", "2026-03-29", "2026-03-30", "2026-03-31"),
        )

    def test_execute_automation_reserve_refreshes_entry_context_before_building_target_dates(
        self,
    ) -> None:
        app = object.__new__(SeatWebApp)
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            )
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app._get_entry_context = MagicMock(
            return_value=SimpleNamespace(
                search_page_payload=self._build_search_page_payload(),
                search_api_url="https://example.com/api/searchSeats",
            )
        )
        app._load_saved_session = lambda runtime_arg: SimpleNamespace(
            cookie_header="auth=token",
            user_id="405181",
        )
        app._reserve_by_seat_number = MagicMock()
        app._wait_automation_reserve_gap = MagicMock()
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )

        with patch(
            "wuyi_seat_bot.web_server.fetch_my_booking_list",
            return_value={"content": {"defaultItems": []}},
        ):
            app._execute_automation_reserve(plan, datetime(2026, 3, 28, 8, 0))

        app._get_entry_context.assert_called_once_with(
            runtime,
            "https://example.com/seat/a",
            force_refresh=True,
        )

    def test_execute_automation_reserve_retries_after_rate_limit(self) -> None:
        app = object.__new__(SeatWebApp)
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            )
        )
        app._get_account_runtime = lambda account_name=None: runtime
        app._get_entry_context = (
            lambda runtime_arg, seat_url, force_refresh=False: SimpleNamespace(
                search_page_payload=self._build_search_page_payload(),
                search_api_url="https://example.com/api/searchSeats",
            )
        )
        app._load_saved_session = lambda runtime_arg: SimpleNamespace(
            cookie_header="auth=token",
            user_id="405181",
        )
        app._reserve_by_seat_number = MagicMock(
            side_effect=[
                ApiRequestError("请求太频繁了，请稍后再试"),
                None,
            ]
        )
        app._wait_automation_reserve_gap = MagicMock()
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )

        with patch(
            "wuyi_seat_bot.web_server.fetch_my_booking_list",
            return_value={"content": {"defaultItems": []}},
        ):
            result = app._execute_automation_reserve(plan, datetime(2026, 3, 28, 8, 0))

        self.assertEqual(app._reserve_by_seat_number.call_count, 2)
        app._wait_automation_reserve_gap.assert_called_once_with(
            reason="retry-after-rate-limit"
        )
        self.assertEqual(result.booked_dates, ("2026-03-28",))
        self.assertIn("已补订 1 天", result.message)

    def test_execute_automation_action_requests_same_day_checkin_retry_when_window_not_open(
        self,
    ) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            ),
            automation=MagicMock(),
        )
        app._get_account_runtime = lambda account_name=None: runtime
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=False,
            checkin_enabled=True,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="10:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )

        with patch("wuyi_seat_bot.web_action_service.SeatWorkflow") as workflow_class:
            workflow_class.return_value.run.return_value = SimpleNamespace(
                success=False,
                message="已找到待签到预约，但当前还不在签到时间窗内：自习室圆形二楼 166 号座位",
            )

            result = app._execute_automation_action(
                plan, "checkin", datetime(2026, 3, 28, 10, 0)
            )

        self.assertEqual(
            result,
            AutomationActionResult(
                message="已找到待签到预约，但当前还不在签到时间窗内：自习室圆形二楼 166 号座位",
                retry_delay_minutes=5,
            ),
        )

    def test_run_automation_reserve_now_updates_each_enabled_reserve_plan(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            state_path = Path(tmp_dir) / "auth-main.json"
            state_path.write_text("{}", encoding="utf-8")
            plan = build_automation_plan(
                account_name="主号",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-28",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=False,
                reserve_time="08:00",
                checkin_time="08:00",
                checkout_time="21:59",
                now=datetime(2026, 3, 28, 8, 0),
            )
            app = object.__new__(SeatWebApp)
            app.automation_scheduler = SimpleNamespace(
                list_plans=lambda: [plan],
                apply_manual_reserve_result=MagicMock(return_value=plan),
            )
            app._get_account_runtime = lambda account_name=None: SimpleNamespace(
                state_path=state_path
            )
            app._execute_manual_reserve_check = MagicMock(
                return_value=AutomationActionResult(
                    message="已补订 1 天：2026-03-28",
                    target_dates=("2026-03-28",),
                    booked_dates=("2026-03-28",),
                    created_dates=("2026-03-28",),
                )
            )

            response = app.run_automation_reserve_now({})

        self.assertEqual(response["checkedCount"], 1)
        self.assertEqual(response["bookedCount"], 1)
        self.assertIn("已补订 1 个账号", response["message"])
        app._execute_manual_reserve_check.assert_called_once()
        app.automation_scheduler.apply_manual_reserve_result.assert_called_once()

    def test_run_automation_reserve_now_skips_plan_without_login_state(self) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )
        app = object.__new__(SeatWebApp)
        app.automation_scheduler = SimpleNamespace(
            list_plans=lambda: [plan],
            apply_manual_reserve_result=MagicMock(return_value=plan),
        )
        app._get_account_runtime = lambda account_name=None: SimpleNamespace(
            state_path=Path("C:/tmp/missing-auth-main.json")
        )
        app._execute_manual_reserve_check = MagicMock()

        response = app.run_automation_reserve_now({})

        self.assertEqual(response["checkedCount"], 1)
        self.assertEqual(response["skippedCount"], 1)
        self.assertEqual(response["results"][0]["message"], "未保存登录态")
        app._execute_manual_reserve_check.assert_not_called()
        app.automation_scheduler.apply_manual_reserve_result.assert_called_once()

    def test_run_automation_reserve_now_reports_empty_plan_set(self) -> None:
        app = object.__new__(SeatWebApp)
        app.automation_scheduler = SimpleNamespace(list_plans=lambda: [])

        response = app.run_automation_reserve_now({})

        self.assertEqual(response["checkedCount"], 0)
        self.assertEqual(response["message"], "当前没有启用自动预约的计划，无需检查")

    def test_prune_orphan_automation_plans_removes_unknown_account_plans_and_logs(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            storage_path = Path(tmp_dir) / "automation_plans.json"
            kept_plan = build_automation_plan(
                account_name="20231121151",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-29",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=False,
                reserve_time="08:00",
                checkin_time="08:00",
                checkout_time="21:59",
                now=datetime(2026, 3, 29, 7, 55),
            )
            orphan_plan = build_automation_plan(
                account_name="20211121101",
                seat_url="https://example.com/seat/a",
                seat_number="77",
                selected_date="2026-03-29",
                room_name="自习室圆形二楼",
                start_hour=13,
                duration_hours=9,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=True,
                reserve_time="13:00",
                checkin_time="08:00",
                checkout_time="21:59",
                now=datetime(2026, 3, 29, 7, 55),
            )
            scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message=f"{action} ok"
                ),
            )
            scheduler.save_plan(kept_plan)
            scheduler.save_plan(orphan_plan)

            app = object.__new__(SeatWebApp)
            app.account_names = ("20231121151",)
            app.automation_scheduler = scheduler

            with self.assertLogs(
                "wuyi-seat-bot.service-worker", level="WARNING"
            ) as captured:
                app._prune_orphan_automation_plans()

            remaining_plan_ids = [plan.plan_id for plan in scheduler.list_plans()]

        self.assertEqual(remaining_plan_ids, [kept_plan.plan_id])
        joined_log = "\n".join(captured.output)
        self.assertIn("已自动清理孤儿自动任务计划", joined_log)
        self.assertIn("20211121101", joined_log)
        self.assertIn(orphan_plan.plan_id, joined_log)

    def test_prune_orphan_automation_plans_skips_when_scheduler_missing(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("20231121151",)

        # 不该抛异常；也不该尝试访问 automation_scheduler 属性
        app._prune_orphan_automation_plans()

    def test_build_automation_target_dates_returns_empty_when_single_day_target_not_in_window(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-04-10",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )

        self.assertEqual(
            build_automation_target_dates(plan, self._build_search_page_payload()),
            (),
        )

    def test_build_automation_reserve_filters_uses_current_hour_to_max_end_for_today(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )

        filters = build_automation_reserve_filters(
            plan,
            self._build_search_page_payload(),
            "2026-03-28",
            datetime(2026, 3, 28, 20, 49),
        )

        self.assertIsNotNone(filters)
        self.assertEqual(filters.start_hour, 20)
        self.assertEqual(filters.duration_hours, 2)

    def test_build_automation_reserve_filters_uses_interface_default_window_for_future_days(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=15,
            duration_hours=7,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=True,
            reserve_time="15:00",
            checkin_time="14:35",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 15, 0),
        )

        filters = build_automation_reserve_filters(
            plan,
            self._build_search_page_payload(),
            "2026-03-29",
            datetime(2026, 3, 28, 15, 0),
        )

        self.assertIsNotNone(filters)
        self.assertEqual(filters.start_hour, 8)
        self.assertEqual(filters.duration_hours, 14)

    def test_build_automation_reserve_filters_keeps_single_day_future_window(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-29",
            start_hour=15,
            duration_hours=7,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="15:00",
            checkin_time="14:35",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 15, 0),
        )

        filters = build_automation_reserve_filters(
            plan,
            self._build_search_page_payload(),
            "2026-03-29",
            datetime(2026, 3, 28, 15, 0),
        )

        self.assertIsNotNone(filters)
        self.assertEqual(filters.start_hour, 15)
        self.assertEqual(filters.duration_hours, 7)

    def test_build_manual_reserve_filters_uses_current_hour_to_max_end_for_today(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=1,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )

        filters = build_manual_reserve_filters(
            plan,
            self._build_search_page_payload(),
            "2026-03-28",
            datetime(2026, 3, 28, 15, 40),
        )

        self.assertIsNotNone(filters)
        self.assertEqual(filters.start_hour, 15)
        self.assertEqual(filters.duration_hours, 7)

    def test_build_manual_reserve_filters_clamps_early_and_late_today_window(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=10,
            duration_hours=2,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )

        early_filters = build_manual_reserve_filters(
            plan,
            self._build_search_page_payload(),
            "2026-03-28",
            datetime(2026, 3, 28, 7, 10),
        )
        last_hour_filters = build_manual_reserve_filters(
            plan,
            self._build_search_page_payload(),
            "2026-03-28",
            datetime(2026, 3, 28, 21, 30),
        )
        expired_filters = build_manual_reserve_filters(
            plan,
            self._build_search_page_payload(),
            "2026-03-28",
            datetime(2026, 3, 28, 22, 10),
        )

        self.assertIsNotNone(early_filters)
        self.assertEqual(early_filters.start_hour, 8)
        self.assertEqual(early_filters.duration_hours, 14)
        self.assertIsNotNone(last_hour_filters)
        self.assertEqual(last_hour_filters.start_hour, 21)
        self.assertEqual(last_hour_filters.duration_hours, 1)
        self.assertIsNone(expired_filters)

    def test_build_manual_reserve_filters_downgrades_duration_to_available_option(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )
        payload = self._build_search_page_payload()
        payload["data"]["range"]["max_duration"] = 3

        filters = build_manual_reserve_filters(
            plan,
            payload,
            "2026-03-28",
            datetime(2026, 3, 28, 15, 40),
        )

        self.assertIsNotNone(filters)
        self.assertEqual(filters.start_hour, 15)
        self.assertEqual(filters.duration_hours, 3)

    def test_execute_manual_reserve_check_tracks_created_dates(self) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=1,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )
        used_filters: list[SearchFilters] = []

        result = execute_manual_reserve_check(
            plan=plan,
            now=datetime(2026, 3, 28, 15, 40),
            search_page_payload=self._build_search_page_payload(),
            booking_list_payload={"content": {"defaultItems": []}},
            reserve_once=lambda filters: used_filters.append(filters) or None,
            wait_reserve_gap=lambda reason: None,
        )

        self.assertEqual(result.created_dates, ("2026-03-28",))
        self.assertEqual(result.booked_dates, ("2026-03-28",))
        self.assertEqual(len(used_filters), 1)
        self.assertEqual(used_filters[0].start_hour, 15)
        self.assertEqual(used_filters[0].duration_hours, 7)

    def test_manual_reserve_response_classifies_and_summarizes_results(self) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-28",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=False,
            reserve_time="08:00",
            checkin_time="08:00",
            checkout_time="21:59",
            now=datetime(2026, 3, 28, 8, 0),
        )
        booked_result = AutomationActionResult(
            message="已补订 1 天：2026-03-28",
            target_dates=("2026-03-28",),
            booked_dates=("2026-03-28",),
            created_dates=("2026-03-28",),
        )
        failed_result = AutomationActionResult(
            message="自动预约本轮没有补订成功",
            target_dates=("2026-03-29",),
            booked_dates=(),
        )
        skipped_result = AutomationActionResult(message="未保存登录态")

        results = [
            build_manual_reserve_result_entry(
                plan,
                classify_manual_reserve_result(
                    booked_result, login_state_ready=True
                ),
                booked_result,
            ),
            build_manual_reserve_result_entry(
                plan,
                classify_manual_reserve_result(
                    failed_result, login_state_ready=True
                ),
                failed_result,
            ),
            build_manual_reserve_result_entry(
                plan,
                classify_manual_reserve_result(
                    skipped_result, login_state_ready=False
                ),
                skipped_result,
            ),
        ]
        response = build_manual_reserve_check_response(results)

        self.assertEqual(response["checkedCount"], 3)
        self.assertEqual(response["bookedCount"], 1)
        self.assertEqual(response["skippedCount"], 1)
        self.assertEqual(response["failedCount"], 1)
        self.assertEqual(response["message"], "检查完成：成功 1，跳过 1，失败 1")

    def test_inspect_task_statuses_marks_missing_login_state(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        app.scheduler = SimpleNamespace(
            list_tasks=lambda: [
                ScheduledTask(
                    task_id="task001",
                    action="checkin",
                    account_name="主号",
                    run_at="2026-03-28T08:00:00",
                    created_at="2026-03-27T23:00:00",
                    summary="主号 · 定时签到",
                    payload={"accountName": "主号"},
                )
            ]
        )
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="主号",
            ),
            state_path=Path("C:/tmp/missing-auth-main.json"),
        )
        app._get_account_runtime = lambda account_name=None: runtime

        result = app.inspect_task_statuses()

        self.assertEqual(result["statuses"][0]["state"], "missing-login")
        self.assertEqual(result["statuses"][0]["pendingTaskCount"], 1)
        self.assertIn("未保存登录态", result["statuses"][0]["summary"])

    def test_inspect_task_statuses_returns_current_booking_summary(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        app.scheduler = SimpleNamespace(list_tasks=lambda: [])
        with tempfile.TemporaryDirectory() as tmp_dir:
            state_path = Path(tmp_dir) / "auth-main.json"
            state_path.write_text("{}", encoding="utf-8")
            runtime = SimpleNamespace(
                config=AppConfig(
                    login_url="https://example.com/login-a",
                    state_file="runtime/auth-main.json",
                    seat_urls=("https://example.com/seat/a",),
                    account_name="主号",
                ),
                state_path=state_path,
            )
            app._get_account_runtime = lambda account_name=None: runtime
            app._load_saved_session = lambda runtime_arg: SimpleNamespace(
                cookie_header="auth=token"
            )

            payload = {
                "content": {
                    "defaultItems": [
                        {
                            "id": 21546358,
                            "roomName": "自习室圆形二楼",
                            "seatNum": "165",
                            "status": "1",
                            "time": "1774656000",
                            "duration": "3600",
                            "nowTime": 1774656200,
                            "limitSignAgo": "900",
                            "limitSignBack": "900",
                            "ibeacons": [],
                        }
                    ]
                }
            }

            with patch(
                "wuyi_seat_bot.web_server.fetch_my_booking_list", return_value=payload
            ):
                result = app.inspect_task_statuses()

        self.assertEqual(result["statuses"][0]["state"], "active")
        self.assertIn("签到成功，使用中", result["statuses"][0]["summary"])
        self.assertEqual(result["statuses"][0]["bookings"][0]["seatNumber"], "165")

    def test_inspect_task_statuses_persists_task_status_cache(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        app.scheduler = SimpleNamespace(list_tasks=lambda: [])
        with tempfile.TemporaryDirectory() as tmp_dir:
            cache_path = Path(tmp_dir) / "runtime" / "task_status_cache.json"
            state_path = Path(tmp_dir) / "auth-main.json"
            state_path.write_text("{}", encoding="utf-8")
            app._task_status_cache_path = cache_path
            app._task_status_cache_lock = threading.Lock()
            runtime = SimpleNamespace(
                config=AppConfig(
                    login_url="https://example.com/login-a",
                    state_file="runtime/auth-main.json",
                    seat_urls=("https://example.com/seat/a",),
                    account_name="主号",
                ),
                state_path=state_path,
            )
            app._get_account_runtime = lambda account_name=None: runtime
            app._load_saved_session = lambda runtime_arg: SimpleNamespace(
                cookie_header="auth=token"
            )

            payload = {
                "content": {
                    "defaultItems": [
                        {
                            "id": 21546358,
                            "roomName": "自习室圆形二楼",
                            "seatNum": "165",
                            "status": "1",
                            "time": "1774656000",
                            "duration": "3600",
                            "nowTime": 1774656200,
                            "limitSignAgo": "900",
                            "limitSignBack": "900",
                            "ibeacons": [],
                        }
                    ]
                }
            }

            with patch(
                "wuyi_seat_bot.web_server.fetch_my_booking_list", return_value=payload
            ):
                result = app.inspect_task_statuses()

            cached = json.loads(cache_path.read_text(encoding="utf-8"))

        self.assertEqual(cached["serverTime"], result["serverTime"])
        self.assertEqual(cached["statuses"], result["statuses"])
        self.assertEqual(cached["statuses"][0]["accountName"], "主号")

    def test_inspect_task_statuses_keeps_all_booking_items_for_each_account(self) -> None:
        app = object.__new__(SeatWebApp)
        app.account_names = ("主号",)
        app.default_account_name = "主号"
        app.scheduler = SimpleNamespace(list_tasks=lambda: [])
        with tempfile.TemporaryDirectory() as tmp_dir:
            state_path = Path(tmp_dir) / "auth-main.json"
            state_path.write_text("{}", encoding="utf-8")
            runtime = SimpleNamespace(
                config=AppConfig(
                    login_url="https://example.com/login-a",
                    state_file="runtime/auth-main.json",
                    seat_urls=("https://example.com/seat/a",),
                    account_name="主号",
                ),
                state_path=state_path,
            )
            app._get_account_runtime = lambda account_name=None: runtime
            app._load_saved_session = lambda runtime_arg: SimpleNamespace(
                cookie_header="auth=token"
            )

            payload = {
                "content": {
                    "defaultItems": [
                        {
                            "id": 21546358,
                            "roomName": "自习室圆形二楼",
                            "seatNum": "165",
                            "status": "1",
                            "time": "1774656000",
                            "duration": "3600",
                            "nowTime": 1774656200,
                            "limitSignAgo": "900",
                            "limitSignBack": "900",
                            "ibeacons": [],
                        },
                        {
                            "id": 21546359,
                            "roomName": "自习室圆形二楼",
                            "seatNum": "166",
                            "status": "0",
                            "time": "1774659600",
                            "duration": "3600",
                            "nowTime": 1774656200,
                            "limitSignAgo": "900",
                            "limitSignBack": "900",
                            "ibeacons": [],
                        },
                        {
                            "id": 21546360,
                            "roomName": "自习室圆形二楼",
                            "seatNum": "167",
                            "status": "8",
                            "time": "1774663200",
                            "duration": "3600",
                            "nowTime": 1774656200,
                            "limitSignAgo": "900",
                            "limitSignBack": "900",
                            "ibeacons": [],
                        },
                        {
                            "id": 21546361,
                            "roomName": "自习室圆形二楼",
                            "seatNum": "168",
                            "status": "5",
                            "time": "1774666800",
                            "duration": "3600",
                            "nowTime": 1774656200,
                            "limitSignAgo": "900",
                            "limitSignBack": "900",
                            "ibeacons": [],
                        },
                    ]
                }
            }

            with patch(
                "wuyi_seat_bot.web_server.fetch_my_booking_list", return_value=payload
            ):
                result = app.inspect_task_statuses()

        bookings = result["statuses"][0]["bookings"]
        self.assertEqual(len(bookings), 4)
        self.assertEqual(
            {item["seatNumber"] for item in bookings},
            {"165", "166", "167", "168"},
        )

    def test_load_saved_session_guides_user_to_refresh_login_in_web_ui(self) -> None:
        app = object.__new__(SeatWebApp)
        runtime = SimpleNamespace(
            config=AppConfig(
                login_url="https://example.com/login-a",
                state_file="runtime/auth-main.json",
                seat_urls=("https://example.com/seat/a",),
                account_name="20231121130",
            ),
            state_path=Path("C:/tmp/runtime/auth-20231121130.json"),
        )

        with self.assertRaisesRegex(ApiRequestError, "刷新认证"):
            app._load_saved_session(runtime)

    def test_request_handler_defines_get_route_table(self) -> None:
        self.assertEqual(
            set(SeatWebRequestHandler.GET_JSON_ROUTES),
            {
                "/api/bootstrap",
                "/api/accounts",
                "/api/settings",
                "/api/tasks",
                "/api/automation-plans",
                "/api/task-status",
                "/api/sync/state",
                "/api/server-sync/settings",
            },
        )
        self.assertIn("/app.js", SeatWebRequestHandler.STATIC_GET_ROUTES)
        self.assertIn("/favicon.ico", SeatWebRequestHandler.NO_CONTENT_GET_ROUTES)

    def test_request_handler_defines_post_route_table_statuses(self) -> None:
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES["/api/tasks"][1],
            HTTPStatus.CREATED,
        )
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES["/api/tasks/batch"][1],
            HTTPStatus.CREATED,
        )
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES["/api/automation-plans"][1],
            HTTPStatus.CREATED,
        )
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES["/api/accounts/checkin-all"][1],
            HTTPStatus.OK,
        )
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES[
                "/api/automation-plans/check-now"
            ][1],
            HTTPStatus.OK,
        )
        self.assertFalse(
            SeatWebRequestHandler.POST_JSON_ROUTES[
                "/api/settings/network/check"
            ][2]
        )
        self.assertNotIn(
            "/api/settings/network/campus-login",
            SeatWebRequestHandler.POST_JSON_ROUTES,
        )
        # spec account-pool-tri-sync 11.13: Manual_Sync_Action 后端入口。
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES["/api/sync/preview"][1],
            HTTPStatus.OK,
        )
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES["/api/sync/apply"][1],
            HTTPStatus.OK,
        )
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES["/api/sync/cancel"][1],
            HTTPStatus.OK,
        )
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES["/api/sync/upload"][1],
            HTTPStatus.OK,
        )
        self.assertEqual(
            SeatWebRequestHandler.POST_JSON_ROUTES[
                "/api/sync/upload-automation-plans"
            ][1],
            HTTPStatus.OK,
        )

    def test_send_json_ignores_aborted_connection(self) -> None:
        handler = object.__new__(SeatWebRequestHandler)
        handler.wfile = _AbortOnWriteStream()
        handler.send_response = lambda status_code: None
        handler.send_header = lambda key, value: None
        handler.end_headers = lambda: None

        handler._send_json({"message": "ok"})

    def test_serve_static_file_ignores_aborted_connection(self) -> None:
        handler = object.__new__(SeatWebRequestHandler)
        handler.wfile = _AbortOnWriteStream()
        handler.send_response = lambda status_code: None
        handler.send_header = lambda key, value: None
        handler.end_headers = lambda: None

        with tempfile.TemporaryDirectory() as tmp_dir:
            file_path = Path(tmp_dir) / "index.html"
            file_path.write_text("<html></html>", encoding="utf-8")

            handler._serve_static_file(file_path, "text/html; charset=utf-8")


class _AbortOnWriteStream(BytesIO):
    def write(self, data: bytes) -> int:
        raise ConnectionAbortedError(10053, "模拟客户端已断开连接")


class _TrackingLock:
    def __init__(self) -> None:
        self.enter_count = 0

    def __enter__(self) -> None:
        self.enter_count += 1

    def __exit__(self, exc_type, exc, tb) -> bool:
        del exc_type, exc, tb
        return False
