"""``web_sync_service`` 单元测试。

覆盖 spec account-pool-tri-sync 任务 11.13「Manual_Sync_Action UI 入口 +
Sync_Coverage_Confirmation 弹窗」中后端协调器的核心行为：

- :func:`compute_sync_button_state` 三态：``enabled`` / ``disabled_unconfigured`` /
  ``disabled_unreachable``。
- :class:`ManualSyncCoordinator.preview` 在未配置时直接返回 ``unconfigured`` 错误码，
  且不发任何网络请求（Requirement 13.3）。
- :class:`ManualSyncCoordinator.apply` 对空 selection / 全 false selection 视为
  noop（Requirement 13.15 / 13.16），并在 token 过期时返回 ``token_expired``。

不依赖真实 httpx 客户端：通过注入只产生 ``unconfigured`` 路径的 ``config.json``
和 monkeypatch ``ServerSyncClient`` 的方式覆盖关键分支。
"""

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from wuyi_seat_bot.automation_plans import (
    AutomationActionResult,
    LocalAutomationPlanScheduler,
    build_automation_plan,
)
from wuyi_seat_bot.server_sync import (
    ActiveAccountDetail,
    ActiveAccountListItem,
    ConnectivityIndicator,
    load_server_sync_config,
    NetworkError,
    Unauthorized,
)
from wuyi_seat_bot.web_sync_service import (
    ManualSyncCoordinator,
    compute_sync_button_state,
    save_server_sync_settings_response,
    sync_server_managed_automation_plans_to_local,
    upload_local_automation_plans_to_server,
)


def _write_config(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


class ComputeSyncButtonStateTestCase(unittest.TestCase):
    def test_returns_disabled_unconfigured_when_config_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(config_path, {"accounts": []})

            result = compute_sync_button_state(config_path)

            self.assertEqual(result["state"], "disabled_unconfigured")
            self.assertEqual(result["label"], "未配置服务端")
            self.assertEqual(result["failure_reason"], "")

    def test_returns_enabled_when_config_present(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [],
                    "server_sync": {
                        "base_url": "https://server.example.com",
                        "bearer_token": "token-abc",
                        "verify_tls": True,
                        "upload_enabled": False,
                    },
                },
            )

            result = compute_sync_button_state(config_path)

            self.assertEqual(result["state"], "enabled")
            self.assertEqual(result["label"], "服务端已配置")

    def test_indicator_overrides_state_when_provided(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [],
                    "server_sync": {
                        "base_url": "https://server.example.com",
                        "bearer_token": "token-abc",
                    },
                },
            )
            indicator = ConnectivityIndicator(is_configured_fn=lambda: True)
            indicator.record_sync_failure("connect timeout")

            result = compute_sync_button_state(config_path, indicator=indicator)

            self.assertEqual(result["state"], "disabled_unreachable")
            self.assertEqual(result["label"], "服务端不可达")
            self.assertEqual(result["failure_reason"], "connect timeout")


class ServerSyncSettingsResponseTestCase(unittest.TestCase):
    def test_save_accepts_unvalidated_server_url(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(config_path, {"accounts": []})

            result = save_server_sync_settings_response(
                config_path=config_path,
                payload={
                    "base_url": "not a valid server url",
                    "bearer_token": "tok",
                    "verify_tls": True,
                    "upload_enabled": True,
                },
            )

            self.assertEqual(result["config"]["base_url"], "not a valid server url")
            self.assertEqual(result["message"], "已保存服务端配置（已开启上行）")
            saved = load_server_sync_config(config_path)
            self.assertEqual(saved.base_url, "not a valid server url")
            self.assertEqual(saved.bearer_token, "tok")
            self.assertTrue(saved.upload_enabled)
            self.assertIsNone(saved.to_server_sync_settings())

    def test_save_allows_empty_bearer_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(config_path, {"accounts": []})

            result = save_server_sync_settings_response(
                config_path=config_path,
                payload={
                    "base_url": "https://server.example.com",
                    "bearer_token": "",
                    "verify_tls": True,
                    "upload_enabled": True,
                },
            )

            self.assertEqual(result["config"]["base_url"], "https://server.example.com")
            self.assertEqual(result["config"]["bearer_token"], "")
            self.assertFalse(result["config"]["is_configured"])
            self.assertEqual(
                result["message"],
                "已保存服务端配置（Bearer Token 为空，暂未启用上行）",
            )
            saved = load_server_sync_config(config_path)
            self.assertEqual(saved.base_url, "https://server.example.com")
            self.assertIsNone(saved.bearer_token)
            self.assertTrue(saved.upload_enabled)
            self.assertFalse(saved.is_configured())
            self.assertIsNone(saved.to_server_sync_settings())


class ManualSyncCoordinatorPreviewTestCase(unittest.TestCase):
    def test_preview_returns_unconfigured_without_network_call(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(config_path, {"accounts": []})

            coordinator = ManualSyncCoordinator(config_path)
            with patch(
                "wuyi_seat_bot.web_sync_service.ServerSyncClient"
            ) as mock_client:
                result = coordinator.preview()

            self.assertEqual(result["ok"], False)
            self.assertEqual(result["error_code"], "unconfigured")
            self.assertEqual(result["message"], "未配置服务端")
            mock_client.assert_not_called()

    def test_preview_returns_server_unreachable_when_network_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [],
                    "server_sync": {
                        "base_url": "https://server.example.com",
                        "bearer_token": "token-abc",
                    },
                },
            )

            class _StubRepository:
                def __init__(self, *args, **kwargs) -> None:
                    pass

                def refresh_active_list(self):
                    raise NetworkError("connect refused")

                def get_active_account_detail(self, _):
                    raise AssertionError("不应该走到详情接口")

            coordinator = ManualSyncCoordinator(config_path)
            with patch(
                "wuyi_seat_bot.web_sync_service.ServerSyncClient"
            ) as mock_client_cls, patch(
                "wuyi_seat_bot.web_sync_service.ActivePoolRepository",
                _StubRepository,
            ):
                mock_client_cls.return_value.__enter__.return_value = (
                    mock_client_cls.return_value
                )
                result = coordinator.preview()

            self.assertEqual(result["ok"], False)
            self.assertEqual(result["error_code"], "server_unreachable")

    def test_preview_returns_unauthorized_when_token_invalid(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [],
                    "server_sync": {
                        "base_url": "https://server.example.com",
                        "bearer_token": "token-abc",
                    },
                },
            )

            class _StubRepository:
                def __init__(self, *args, **kwargs) -> None:
                    pass

                def refresh_active_list(self):
                    raise Unauthorized()

                def get_active_account_detail(self, _):
                    raise AssertionError("不应该走到详情接口")

            coordinator = ManualSyncCoordinator(config_path)
            with patch(
                "wuyi_seat_bot.web_sync_service.ServerSyncClient"
            ) as mock_client_cls, patch(
                "wuyi_seat_bot.web_sync_service.ActivePoolRepository",
                _StubRepository,
            ):
                mock_client_cls.return_value.__enter__.return_value = (
                    mock_client_cls.return_value
                )
                result = coordinator.preview()

            self.assertEqual(result["ok"], False)
            self.assertEqual(result["error_code"], "unauthorized_401")


class ManualSyncCoordinatorPreviewSuccessTestCase(unittest.TestCase):
    def test_preview_diff_includes_add_candidate_for_new_account(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [],
                    "server_sync": {
                        "base_url": "https://server.example.com",
                        "bearer_token": "token-abc",
                    },
                },
            )

            server_detail = ActiveAccountDetail(
                account_id=17,
                student_id="20231121200",
                display_name="Alice",
                password="secret",
                revision=1,
                automation_tasks=[],
            )

            class _StubRepository:
                def __init__(self, *args, **kwargs) -> None:
                    pass

                def refresh_active_list(self):
                    return [
                        ActiveAccountListItem(
                            account_id=17,
                            student_id="20231121200",
                            display_name="Alice",
                            pool_status="active",
                            updated_at=None,
                        )
                    ]

                def get_active_account_detail(self, account_id: int):
                    self_outer = server_detail
                    return self_outer if account_id == 17 else None

            coordinator = ManualSyncCoordinator(config_path)
            with patch(
                "wuyi_seat_bot.web_sync_service.ServerSyncClient"
            ) as mock_client_cls, patch(
                "wuyi_seat_bot.web_sync_service.ActivePoolRepository",
                _StubRepository,
            ):
                mock_client_cls.return_value.__enter__.return_value = (
                    mock_client_cls.return_value
                )
                preview = coordinator.preview()

            self.assertTrue(preview["ok"], preview)
            self.assertEqual(preview["summary"], {"add": 1, "replace": 0, "remove": 0})
            self.assertEqual(len(preview["candidates"]), 1)
            candidate = preview["candidates"][0]
            self.assertEqual(candidate["kind"], "add")
            self.assertEqual(candidate["student_id"], "20231121200")
            self.assertTrue(candidate["default_checked"])
            self.assertEqual(
                candidate["server_summary"]["password_masked"], "********"
            )
            self.assertNotIn("password", candidate["server_summary"])
            self.assertIsNone(candidate["local_summary"])

            # token 由 preview 派发后供 apply 复用
            self.assertTrue(preview["token"])

            # apply 全 false → noop，不写入 config.json
            apply_result = coordinator.apply(
                preview["token"], {"20231121200": False}
            )
            self.assertTrue(apply_result["ok"])
            self.assertTrue(apply_result["noop"])
            self.assertEqual(
                apply_result["message"],
                "未选择任何账号，本次同步未对本地数据做任何更改",
            )


class ManualSyncCoordinatorApplyTestCase(unittest.TestCase):
    def test_apply_returns_token_expired_for_unknown_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(config_path, {"accounts": []})
            coordinator = ManualSyncCoordinator(config_path)

            result = coordinator.apply("does-not-exist", {"sid": True})

            self.assertEqual(result["ok"], False)
            self.assertEqual(result["error_code"], "token_expired")

    def test_apply_with_empty_selection_is_noop(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(config_path, {"accounts": []})
            coordinator = ManualSyncCoordinator(config_path)

            # 直接塞一个临时 session，模拟 preview 已完成。
            from wuyi_seat_bot.web_sync_service import _PreviewSession
            import time

            with coordinator._lock:  # type: ignore[attr-defined]
                coordinator._sessions["tk"] = _PreviewSession(  # type: ignore[attr-defined]
                    token="tk",
                    candidates=[],
                    created_at=time.monotonic(),
                )

            result = coordinator.apply("tk", {})

            self.assertTrue(result["ok"])
            self.assertTrue(result["noop"])
            self.assertEqual(result["added"], 0)
            self.assertEqual(result["replaced"], 0)
            self.assertEqual(result["removed"], 0)


class AutomationPlanUploadTestCase(unittest.TestCase):
    def test_upload_returns_error_when_upload_disabled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "default_account": "主号",
                    "accounts": [
                        {
                            "name": "主号",
                            "student_id": "20231121130",
                            "password": "secret",
                            "login_url": "https://wuyiu.example.com/login",
                            "state_file": "runtime/auth-main.json",
                            "seat_urls": ["https://wuyiu.example.com/seat/a"],
                        }
                    ],
                    "server_sync": {
                        "base_url": "https://server.example.com",
                        "bearer_token": "token-abc",
                        "upload_enabled": False,
                    },
                },
            )
            plan = build_automation_plan(
                account_name="主号",
                seat_url="https://wuyiu.example.com/seat/a",
                room_name="三楼自习室",
                seat_number="88",
                selected_date="2026-05-20",
                start_hour=8,
                duration_hours=1,
                reserve_enabled=True,
                checkin_enabled=True,
                checkout_enabled=True,
                continuous_reserve=True,
                reserve_time="08:00",
                checkin_time="07:35",
                checkout_time="21:59",
            )

            with patch(
                "wuyi_seat_bot.web_sync_service.ServerSyncClient"
            ) as mock_client_cls:
                result = upload_local_automation_plans_to_server(
                    config_path=config_path, plans=[plan]
                )

            self.assertEqual(result["ok"], False)
            self.assertEqual(result["error_code"], "upload_disabled")
            mock_client_cls.assert_not_called()

    def test_upload_associates_plan_with_active_account_and_upserts_seat(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "default_account": "主号",
                    "accounts": [
                        {
                            "name": "主号",
                            "student_id": "20231121130",
                            "password": "secret",
                            "login_url": "https://wuyiu.example.com/login",
                            "state_file": "runtime/auth-main.json",
                            "seat_urls": ["https://wuyiu.example.com/seat/a"],
                        }
                    ],
                    "server_sync": {
                        "base_url": "https://server.example.com",
                        "bearer_token": "token-abc",
                        "upload_enabled": True,
                    },
                },
            )
            plan = build_automation_plan(
                account_name="主号",
                seat_url="https://wuyiu.example.com/seat/a",
                room_id="room-a",
                room_name="三楼自习室",
                seat_number="88",
                selected_date="2026-05-20",
                start_hour=8,
                duration_hours=1,
                reserve_enabled=True,
                checkin_enabled=True,
                checkout_enabled=True,
                continuous_reserve=True,
                reserve_time="08:00",
                checkin_time="07:35",
                checkout_time="21:59",
            )

            class _StubRepository:
                def __init__(self, *args, **kwargs) -> None:
                    pass

                def refresh_active_list(self):
                    return [
                        ActiveAccountListItem(
                            account_id=17,
                            student_id="20231121130",
                            display_name="主号",
                            pool_status="active",
                            updated_at=None,
                        )
                    ]

                def get_active_account_detail(self, account_id: int):
                    return ActiveAccountDetail(
                        account_id=account_id,
                        student_id="20231121130",
                        display_name="主号",
                        password="secret",
                        revision=1,
                        automation_tasks=[],
                    )

            class _StubUploader:
                calls: list[tuple[int, int, dict, int]] = []

                def __init__(self, *args, **kwargs) -> None:
                    pass

                def upsert(
                    self,
                    account_id: int,
                    task_id: int,
                    payload: dict,
                    *,
                    expected_revision: int,
                ):
                    self.calls.append(
                        (account_id, task_id, payload, expected_revision)
                    )
                    return SimpleNamespace(
                        task={"task_id": task_id, "revision": 1},
                        server_time=datetime(2026, 5, 20, 8, 0, 0),
                    )

            with patch(
                "wuyi_seat_bot.web_sync_service.ServerSyncClient"
            ) as mock_client_cls, patch(
                "wuyi_seat_bot.web_sync_service.ActivePoolRepository",
                _StubRepository,
            ), patch(
                "wuyi_seat_bot.web_sync_service.AutomationTaskUploader",
                _StubUploader,
            ):
                mock_client_cls.return_value.__enter__.return_value = (
                    mock_client_cls.return_value
                )
                result = upload_local_automation_plans_to_server(
                    config_path=config_path, plans=[plan]
                )

            self.assertTrue(result["ok"], result)
            self.assertEqual(result["created"], 1)
            self.assertEqual(result["updated"], 0)
            self.assertEqual(result["rejected"], 0)
            self.assertEqual(len(_StubUploader.calls), 1)
            account_id, _task_id, payload, expected_revision = _StubUploader.calls[0]
            self.assertEqual(account_id, 17)
            self.assertEqual(expected_revision, 0)
            self.assertEqual(
                payload,
                {
                    "room_name": "三楼自习室",
                    "seat_number": "88",
                    "mode": "manual",
                    "custom_windows": [],
                    "enabled": True,
                },
            )


class ServerManagedAutomationPlanSyncTestCase(unittest.TestCase):
    def test_sync_creates_local_plan_bound_to_matching_account(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            config_path = root / "config.json"
            _write_config(
                config_path,
                {
                    "default_account": "主号",
                    "accounts": [
                        {
                            "name": "主号",
                            "student_id": "20231121130",
                            "password": "secret",
                            "login_url": "https://wuyiu.example.com/login",
                            "state_file": "runtime/auth-main.json",
                            "seat_urls": ["https://wuyiu.example.com/seat/a"],
                            "is_server_managed": True,
                            "server_managed_automation_tasks": [
                                {
                                    "task_id": 23,
                                    "room_name": "三楼自习室",
                                    "seat_number": "88",
                                    "mode": "manual",
                                    "custom_windows": [],
                                    "enabled": True,
                                }
                            ],
                        }
                    ],
                },
            )
            scheduler = LocalAutomationPlanScheduler(
                root / "runtime" / "automation_plans.json",
                lambda *_: AutomationActionResult(message="ok"),
            )

            result = sync_server_managed_automation_plans_to_local(
                config_path=config_path,
                automation_scheduler=scheduler,
            )

            self.assertTrue(result["ok"], result)
            self.assertEqual(result["created"], 1)
            plans = scheduler.list_plans()
            self.assertEqual(len(plans), 1)
            plan = plans[0]
            self.assertEqual(plan.plan_id, "server-managed:20231121130:23")
            self.assertEqual(plan.account_name, "主号")
            self.assertEqual(plan.seat_url, "https://wuyiu.example.com/seat/a")
            self.assertEqual(plan.room_name, "三楼自习室")
            self.assertEqual(plan.seat_number, "88")
            self.assertEqual(plan.room_id, "")
            self.assertTrue(plan.enabled)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
