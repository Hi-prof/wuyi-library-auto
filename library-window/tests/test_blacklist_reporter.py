"""``blacklist_reporter`` 单元测试。

覆盖：

- 双开关守卫：未配置 / 上行关闭 / 完全关闭 → 零网络请求。
- happy path：`POST /api/v1/active-accounts/{id}/blacklist-events` 请求体字段、
  路径与 Bearer Token 正确性。
- 入参非法 → ``BlacklistReportResult.error_kind == 'validation_error'``，不发请求。
- 网络错误 / 5xx / 401 / 426 → ``error_kind`` 翻译并通过 ConnectivityIndicator
  上报 sync 失败。
- 404 → ``error_kind == 'not_in_active_pool'``，且不被视为不可达。
- 400 / 422 → ``error_kind == 'validation_error'``，携带字段错误。
- 429 → ``error_kind == 'rate_limited'``，不影响 connectivity 状态。
- 默认 client_observed_at 使用注入的 clock。
"""

from __future__ import annotations

import unittest
from datetime import datetime, timezone
from typing import Any
from unittest.mock import MagicMock

import httpx

from wuyi_seat_bot.server_sync import (
    BlacklistReporter,
    BlacklistReportResult,
    ConnectivityIndicator,
    ServerSyncClient,
    ServerSyncConfig,
    ServerSyncSettings,
)


# --------------------------------------------------------------------------- #
# Helpers                                                                      #
# --------------------------------------------------------------------------- #


def _build_response(
    status_code: int,
    *,
    json_body: Any = None,
    headers: dict[str, str] | None = None,
) -> httpx.Response:
    base_headers = {"content-type": "application/json"} if json_body is not None else {}
    if headers:
        base_headers.update(headers)
    if json_body is not None:
        return httpx.Response(status_code, json=json_body, headers=base_headers)
    return httpx.Response(status_code, headers=base_headers)


def _make_indicator(*, configured: bool = True) -> ConnectivityIndicator:
    fixed_now = datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)
    return ConnectivityIndicator(
        is_configured_fn=lambda: configured,
        reachable_ttl_seconds=300.0,
        clock=lambda: fixed_now,
    )


def _make_factory(handler):
    """构造一个 ``client_factory``，返回的 :class:`ServerSyncClient` 用 mock transport。"""

    def factory(settings: ServerSyncSettings) -> ServerSyncClient:
        transport = httpx.MockTransport(handler)

        def client_factory(**kwargs):
            kwargs["transport"] = transport
            return httpx.Client(**kwargs)

        return ServerSyncClient(settings, client_factory=client_factory)

    return factory


def _ok_config() -> ServerSyncConfig:
    return ServerSyncConfig(
        base_url="https://srv.example.com",
        bearer_token="tok-abc",
        verify_tls=True,
        upload_enabled=True,
    )


# --------------------------------------------------------------------------- #
# 双开关守卫                                                                    #
# --------------------------------------------------------------------------- #


class DualSwitchGuardTestCase(unittest.TestCase):
    def test_skips_when_config_missing_base_url(self) -> None:
        call_count = {"value": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            call_count["value"] += 1
            return _build_response(200, json_body={})

        config = ServerSyncConfig(base_url=None, bearer_token="tok", upload_enabled=True)
        reporter = BlacklistReporter(
            config_provider=lambda: config,
            client_factory=_make_factory(handler),
        )
        result = reporter.report(17, evidence="evidence")

        self.assertIsInstance(result, BlacklistReportResult)
        self.assertFalse(result.delivered)
        self.assertTrue(result.skipped)
        self.assertEqual(result.error_kind, "config_missing")
        self.assertEqual(call_count["value"], 0)

    def test_skips_when_config_missing_bearer_token(self) -> None:
        call_count = {"value": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            call_count["value"] += 1
            return _build_response(200, json_body={})

        config = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token=None,
            upload_enabled=True,
        )
        reporter = BlacklistReporter(
            config_provider=lambda: config,
            client_factory=_make_factory(handler),
        )
        result = reporter.report(17, evidence="evidence")

        self.assertTrue(result.skipped)
        self.assertEqual(result.error_kind, "config_missing")
        self.assertEqual(call_count["value"], 0)

    def test_skips_when_upload_disabled(self) -> None:
        call_count = {"value": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            call_count["value"] += 1
            return _build_response(200, json_body={})

        config = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token="tok",
            upload_enabled=False,
        )
        reporter = BlacklistReporter(
            config_provider=lambda: config,
            client_factory=_make_factory(handler),
        )
        result = reporter.report(17, evidence="evidence")

        self.assertTrue(result.skipped)
        self.assertEqual(result.error_kind, "upload_disabled")
        self.assertEqual(call_count["value"], 0)

    def test_skips_when_completely_unconfigured(self) -> None:
        call_count = {"value": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            call_count["value"] += 1
            return _build_response(200, json_body={})

        reporter = BlacklistReporter(
            config_provider=lambda: ServerSyncConfig(),
            client_factory=_make_factory(handler),
        )
        result = reporter.report(17, evidence="evidence")

        self.assertTrue(result.skipped)
        self.assertEqual(result.error_kind, "config_missing")
        self.assertEqual(call_count["value"], 0)

    def test_indicator_not_touched_when_skipped(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(200, json_body={})

        indicator = _make_indicator()
        success_spy = MagicMock(wraps=indicator.record_sync_success)
        failure_spy = MagicMock(wraps=indicator.record_sync_failure)
        indicator.record_sync_success = success_spy  # type: ignore[method-assign]
        indicator.record_sync_failure = failure_spy  # type: ignore[method-assign]

        reporter = BlacklistReporter(
            config_provider=lambda: ServerSyncConfig(),
            client_factory=_make_factory(handler),
            connectivity=indicator,
        )
        reporter.report(17, evidence="evidence")

        success_spy.assert_not_called()
        failure_spy.assert_not_called()


# --------------------------------------------------------------------------- #
# 入参校验                                                                      #
# --------------------------------------------------------------------------- #


class InputValidationTestCase(unittest.TestCase):
    def test_zero_account_id_rejected_without_network_call(self) -> None:
        call_count = {"value": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            call_count["value"] += 1
            return _build_response(200, json_body={})

        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
        )
        result = reporter.report(0, evidence="evidence")

        self.assertEqual(result.error_kind, "validation_error")
        self.assertEqual(call_count["value"], 0)
        self.assertEqual(
            result.validation_errors[0]["field"], "account_id"
        )

    def test_negative_account_id_rejected(self) -> None:
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(
                lambda request: _build_response(200, json_body={})
            ),
        )
        result = reporter.report(-5, evidence="evidence")
        self.assertEqual(result.error_kind, "validation_error")

    def test_blank_evidence_rejected(self) -> None:
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(
                lambda request: _build_response(200, json_body={})
            ),
        )
        result = reporter.report(17, evidence="   ")
        self.assertEqual(result.error_kind, "validation_error")
        self.assertEqual(result.validation_errors[0]["field"], "evidence")

    def test_invalid_client_kind_rejected(self) -> None:
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(
                lambda request: _build_response(200, json_body={})
            ),
        )
        result = reporter.report(
            17, evidence="evidence", client_kind="web"  # type: ignore[arg-type]
        )
        self.assertEqual(result.error_kind, "validation_error")
        self.assertEqual(result.validation_errors[0]["field"], "client_kind")


# --------------------------------------------------------------------------- #
# Happy path                                                                   #
# --------------------------------------------------------------------------- #


class HappyPathTestCase(unittest.TestCase):
    def test_post_sends_expected_body_and_path(self) -> None:
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["method"] = request.method
            captured["path"] = request.url.path
            captured["authorization"] = request.headers.get("authorization", "")
            captured["body"] = request.content.decode("utf-8")
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:40:00Z",
                    "account_id": 17,
                    "pool_status": "suspended",
                    "suspended_at": "2026-04-26T08:40:00Z",
                    "suspension_expires_at": "2026-05-03T08:40:00Z",
                },
            )

        indicator = _make_indicator()
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
            connectivity=indicator,
        )
        result = reporter.report(
            17,
            evidence="人机验证失败 5 次",
            client_kind="window",
            client_observed_at=datetime(
                2026, 4, 26, 8, 39, 30, tzinfo=timezone.utc
            ),
        )

        self.assertTrue(result.delivered)
        self.assertTrue(result.ok)
        self.assertFalse(result.skipped)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(captured["method"], "POST")
        self.assertEqual(
            captured["path"], "/api/v1/active-accounts/17/blacklist-events"
        )
        self.assertEqual(captured["authorization"], "Bearer tok-abc")
        self.assertIn('"evidence":"人机验证失败 5 次"', captured["body"])
        self.assertIn('"client_kind":"window"', captured["body"])
        self.assertIn(
            '"client_observed_at":"2026-04-26T08:39:30Z"', captured["body"]
        )
        assert result.server_response is not None
        self.assertEqual(result.server_response["pool_status"], "suspended")
        self.assertEqual(
            indicator.compute_sync_button_state(), "enabled"
        )

    def test_default_observed_at_uses_clock(self) -> None:
        captured_body: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured_body["body"] = request.content.decode("utf-8")
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:40:00Z",
                    "account_id": 17,
                    "pool_status": "suspended",
                },
            )

        fixed_now = datetime(2026, 4, 26, 9, 0, 0, tzinfo=timezone.utc)
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
            clock=lambda: fixed_now,
        )
        result = reporter.report(17, evidence="evidence")

        self.assertTrue(result.delivered)
        self.assertIn(
            '"client_observed_at":"2026-04-26T09:00:00Z"', captured_body["body"]
        )

    def test_android_client_kind_accepted(self) -> None:
        captured_body: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured_body["body"] = request.content.decode("utf-8")
            return _build_response(
                200,
                json_body={"server_time": "2026-04-26T08:40:00Z"},
            )

        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
        )
        result = reporter.report(17, evidence="evidence", client_kind="android")
        self.assertTrue(result.delivered)
        self.assertIn('"client_kind":"android"', captured_body["body"])


# --------------------------------------------------------------------------- #
# 错误响应翻译                                                                  #
# --------------------------------------------------------------------------- #


class ErrorTranslationTestCase(unittest.TestCase):
    def test_404_translates_to_not_in_active_pool(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(404, json_body={"detail": "account not found"})

        indicator = _make_indicator()
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
            connectivity=indicator,
        )
        result = reporter.report(17, evidence="evidence")

        self.assertFalse(result.delivered)
        self.assertEqual(result.error_kind, "not_in_active_pool")
        self.assertEqual(result.status_code, 404)
        # 服务端在响应，不应被标记为不可达。
        self.assertEqual(
            indicator.compute_sync_button_state(), "enabled"
        )

    def test_400_translates_to_validation_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                400,
                json_body={
                    "reason": "validation_error",
                    "errors": [
                        {"field": "evidence", "message": "must be non-empty string"},
                    ],
                },
            )

        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
        )
        result = reporter.report(17, evidence="x")

        self.assertEqual(result.error_kind, "validation_error")
        self.assertEqual(result.status_code, 400)
        self.assertEqual(len(result.validation_errors), 1)
        self.assertEqual(result.validation_errors[0]["field"], "evidence")

    def test_422_pool_full_translates_to_validation_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                422, json_body={"reason": "pool_full", "detail": "号池已满"}
            )

        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
        )
        result = reporter.report(17, evidence="evidence")

        self.assertEqual(result.error_kind, "validation_error")
        self.assertEqual(result.status_code, 422)

    def test_5xx_translates_to_server_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(503, json_body={"reason": "internal_error"})

        indicator = _make_indicator()
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
            connectivity=indicator,
        )
        result = reporter.report(17, evidence="evidence")

        self.assertEqual(result.error_kind, "server_unreachable")
        self.assertEqual(result.status_code, 503)
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )

    def test_401_translates_to_unauthorized(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(401, json_body={"reason": "unauthorized"})

        indicator = _make_indicator()
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
            connectivity=indicator,
        )
        result = reporter.report(17, evidence="evidence")

        self.assertEqual(result.error_kind, "unauthorized")
        self.assertEqual(result.status_code, 401)
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )

    def test_426_translates_to_https_required(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(426, json_body={"reason": "https_required"})

        indicator = _make_indicator()
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
            connectivity=indicator,
        )
        result = reporter.report(17, evidence="evidence")

        self.assertEqual(result.error_kind, "https_required")
        self.assertEqual(result.status_code, 426)
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )

    def test_429_does_not_affect_connectivity(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                429,
                json_body={"reason": "rate_limited", "retry_after": 30},
                headers={"retry-after": "30"},
            )

        indicator = _make_indicator()
        indicator.record_sync_success()
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
            connectivity=indicator,
        )
        result = reporter.report(17, evidence="evidence")

        self.assertEqual(result.error_kind, "rate_limited")
        self.assertEqual(result.status_code, 429)
        # 429 不视作不可达，已有的成功状态保持不变。
        self.assertEqual(
            indicator.compute_sync_button_state(), "enabled"
        )

    def test_network_error_translates_to_server_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("Connection refused", request=request)

        indicator = _make_indicator()
        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
            connectivity=indicator,
        )
        result = reporter.report(17, evidence="evidence")

        self.assertEqual(result.error_kind, "server_unreachable")
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )

    def test_other_4xx_translates_to_protocol_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(409, json_body={"reason": "something_else"})

        reporter = BlacklistReporter(
            config_provider=_ok_config,
            client_factory=_make_factory(handler),
        )
        result = reporter.report(17, evidence="evidence")

        self.assertEqual(result.error_kind, "protocol_error")
        self.assertEqual(result.status_code, 409)


# --------------------------------------------------------------------------- #
# Config provider 动态读取                                                      #
# --------------------------------------------------------------------------- #


class DynamicConfigTestCase(unittest.TestCase):
    def test_config_changes_take_effect_on_next_call(self) -> None:
        call_count = {"value": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            call_count["value"] += 1
            return _build_response(200, json_body={})

        config_holder = {"current": ServerSyncConfig()}

        reporter = BlacklistReporter(
            config_provider=lambda: config_holder["current"],
            client_factory=_make_factory(handler),
        )

        # 初始未配置 → 跳过。
        first = reporter.report(17, evidence="e1")
        self.assertTrue(first.skipped)
        self.assertEqual(call_count["value"], 0)

        # 用户在 UI 上完成配置后再触发 → 走网络。
        config_holder["current"] = _ok_config()
        second = reporter.report(17, evidence="e2")
        self.assertTrue(second.delivered)
        self.assertEqual(call_count["value"], 1)


if __name__ == "__main__":  # pragma: no cover - manual runner
    unittest.main()
