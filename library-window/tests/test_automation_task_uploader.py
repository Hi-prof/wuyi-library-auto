"""``automation_task_uploader`` 单元测试。

覆盖：

- PUT 上传 happy path 与请求体 / 路径 / Bearer Token 正确性。
- DELETE 软删 happy path 与 ``revision`` query 参数。
- 404 → ``AccountNotInActivePool``，并标记 connectivity 仍可达。
- 409 → ``AutomationTaskRevisionConflict``，且 ``resolution.keep_local`` 触发新的
  PUT、``resolution.accept_server`` 不触发额外 HTTP。
- 400 / 422 → ``AutomationTaskValidationError``（含 errors 与 reason）。
- 5xx / 401 / 426 / 网络错误 → ``ServerUnreachable`` 家族，connectivity 标记不可达。
- 429 不把 connectivity 推回不可达。
- 入参合法性校验（账号 id / task id / revision）。
- ``ServerSyncConfig`` 双开关守卫：未配置 / 同步上行未启用时直接抛
  ``UploadDisabledByConfig``，不发起任何 HTTP；双开关全开时正常上行。
- ``should_upload(config)`` 顶层 helper 返回值与双开关语义一致。

拉黑事件上报职责已在 spec ``account-pool-tri-sync`` 任务 11.8 中拆出到独立模块
:mod:`wuyi_seat_bot.server_sync.blacklist_reporter`，对应测试见
``tests/test_blacklist_reporter.py``。
"""

from __future__ import annotations

import unittest
from datetime import datetime, timezone
from typing import Any
from unittest.mock import MagicMock

import httpx

from wuyi_seat_bot.server_sync import (
    AccountNotInActivePool,
    AutomationTaskRevisionConflict,
    AutomationTaskUploader,
    AutomationTaskValidationError,
    HttpsRequired,
    NetworkError,
    ProtocolError,
    RateLimited,
    ServerConnectivity,
    ServerError,
    ServerSyncClient,
    ServerSyncConfig,
    ServerSyncSettings,
    Unauthorized,
    UploadDisabledByConfig,
    should_upload,
)


# --------------------------------------------------------------------------- #
# Test fixtures                                                                #
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


def _build_client(handler) -> ServerSyncClient:
    transport = httpx.MockTransport(handler)

    def factory(**kwargs):
        kwargs["transport"] = transport
        return httpx.Client(**kwargs)

    settings = ServerSyncSettings(
        server_base_url="https://srv.example.com",
        bearer_token="tok-abc",
        verify_tls=True,
        request_timeout_seconds=5.0,
    )
    return ServerSyncClient(settings, client_factory=factory)


def _make_connectivity() -> ServerConnectivity:
    fixed_now = datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)
    clock = MagicMock(side_effect=lambda: fixed_now)
    return ServerConnectivity(reachable_ttl_seconds=300.0, clock=clock)


def _ok_task_payload(
    *,
    task_id: int = 901,
    revision: int = 8,
    deleted_at: str | None = None,
) -> dict[str, Any]:
    """构造与服务端响应字段一致的 ``task`` 字段。"""

    return {
        "task_id": task_id,
        "account_id": 17,
        "room_name": "三层东区",
        "seat_number": "A12",
        "mode": "preferred",
        "custom_windows": [
            {"date": "2026-04-27", "start_hour": 8, "end_hour": 12}
        ],
        "enabled": True,
        "revision": revision,
        "updated_at": "2026-04-26T07:00:00Z",
        "deleted_at": deleted_at,
    }


def _payload_for_upsert() -> dict[str, Any]:
    return {
        "room_name": "三层东区",
        "seat_number": "A12",
        "mode": "preferred",
        "custom_windows": [
            {"date": "2026-04-27", "start_hour": 8, "end_hour": 12}
        ],
        "enabled": True,
    }


# --------------------------------------------------------------------------- #
# upsert                                                                       #
# --------------------------------------------------------------------------- #


class UpsertTestCase(unittest.TestCase):
    def test_put_sends_revision_and_returns_task(self) -> None:
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["method"] = request.method
            captured["path"] = request.url.path
            captured["authorization"] = request.headers.get("authorization", "")
            captured["body"] = request.content.decode("utf-8")
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "task": _ok_task_payload(revision=8),
                },
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            result = uploader.upsert(
                17, 901, _payload_for_upsert(), expected_revision=7
            )

        self.assertEqual(captured["method"], "PUT")
        self.assertEqual(
            captured["path"],
            "/api/v1/active-accounts/17/automation-tasks/901",
        )
        self.assertEqual(captured["authorization"], "Bearer tok-abc")
        self.assertIn('"revision":7', captured["body"])
        self.assertIn("三层东区", captured["body"])
        self.assertEqual(result.task["task_id"], 901)
        self.assertEqual(result.task["revision"], 8)
        self.assertEqual(
            result.server_time,
            datetime(2026, 4, 26, 8, 30, 0, tzinfo=timezone.utc),
        )
        self.assertTrue(connectivity.is_reachable())

    def test_caller_payload_not_mutated(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "task": _ok_task_payload(),
                },
            )

        connectivity = _make_connectivity()
        payload = _payload_for_upsert()
        original = dict(payload)
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            uploader.upsert(17, 901, payload, expected_revision=7)

        self.assertEqual(payload, original)
        self.assertNotIn("revision", payload)

    def test_invalid_account_id_raises(self) -> None:
        connectivity = _make_connectivity()

        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(200, json_body={})

        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(ValueError):
                uploader.upsert(0, 1, _payload_for_upsert(), expected_revision=0)
            with self.assertRaises(ValueError):
                uploader.upsert(-1, 1, _payload_for_upsert(), expected_revision=0)
            with self.assertRaises(ValueError):
                uploader.upsert(1, 0, _payload_for_upsert(), expected_revision=0)
            with self.assertRaises(ValueError):
                uploader.upsert(1, 1, _payload_for_upsert(), expected_revision=-1)
            with self.assertRaises(ValueError):
                uploader.upsert(
                    1, 1, _payload_for_upsert(), expected_revision=True  # type: ignore[arg-type]
                )

    def test_404_raises_account_not_in_active_pool(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(404, json_body={"detail": "account not found"})

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(AccountNotInActivePool) as ctx:
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertEqual(ctx.exception.account_id, 17)
        # 服务端在响应，不应被标记为不可达。
        self.assertTrue(connectivity.is_reachable())

    def test_409_raises_revision_conflict_with_resolution(self) -> None:
        call_count = {"value": 0}
        captured_paths: list[str] = []
        captured_bodies: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            call_count["value"] += 1
            captured_paths.append(request.url.path)
            captured_bodies.append(request.content.decode("utf-8"))
            if call_count["value"] == 1:
                return _build_response(
                    409,
                    json_body={
                        "reason": "revision_conflict",
                        "server_revision": 11,
                        "server_payload": {
                            "task_id": 901,
                            "room_name": "服务端版本",
                            "revision": 11,
                        },
                    },
                )
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:31:00Z",
                    "task": _ok_task_payload(revision=12),
                },
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(AutomationTaskRevisionConflict) as ctx:
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

            conflict = ctx.exception
            self.assertEqual(conflict.account_id, 17)
            self.assertEqual(conflict.task_id, 901)
            self.assertEqual(conflict.server_revision, 11)
            self.assertEqual(conflict.server_payload["room_name"], "服务端版本")

            # accept_server() 不触发额外 HTTP。
            accepted = conflict.resolution.accept_server()
            self.assertEqual(accepted["revision"], 11)
            self.assertEqual(call_count["value"], 1)

            # keep_local() 触发新的 PUT，使用 server_revision。
            result = conflict.resolution.keep_local()
            self.assertEqual(call_count["value"], 2)
            self.assertEqual(result.task["revision"], 12)

        # 第二次 PUT 体内 revision 应该是 server_revision=11。
        self.assertIn('"revision":11', captured_bodies[1])

    def test_400_validation_error_propagates_field_errors(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                400,
                json_body={
                    "reason": "validation_error",
                    "errors": [
                        {"field": "room_name", "message": "must be non-empty"},
                        {"field": "mode", "message": "must be one of preferred/manual/random"},
                    ],
                },
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(AutomationTaskValidationError) as ctx:
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        exc = ctx.exception
        self.assertEqual(exc.status_code, 400)
        self.assertEqual(exc.reason, "validation_error")
        self.assertEqual(len(exc.errors), 2)
        self.assertEqual(exc.errors[0].field, "room_name")
        self.assertEqual(exc.errors[1].field, "mode")
        self.assertTrue(connectivity.is_reachable())

    def test_422_pool_full_translates_to_validation_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                422,
                json_body={"reason": "pool_full", "detail": "号池已满"},
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(AutomationTaskValidationError) as ctx:
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertEqual(ctx.exception.status_code, 422)
        self.assertEqual(ctx.exception.reason, "pool_full")
        self.assertEqual(ctx.exception.errors, [])

    def test_5xx_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(503, json_body={"reason": "internal_error"})

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(ServerError):
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertFalse(connectivity.is_reachable())

    def test_401_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(401, json_body={"reason": "unauthorized"})

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(Unauthorized):
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertFalse(connectivity.is_reachable())

    def test_426_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(426, json_body={"reason": "https_required"})

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(HttpsRequired):
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertFalse(connectivity.is_reachable())

    def test_network_error_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("Connection refused", request=request)

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(NetworkError):
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertFalse(connectivity.is_reachable())

    def test_429_does_not_affect_connectivity(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                429,
                json_body={"reason": "rate_limited", "retry_after": 30},
                headers={"retry-after": "30"},
            )

        connectivity = _make_connectivity()
        connectivity.mark_reachable(
            server_time=datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)
        )
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(RateLimited):
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertTrue(connectivity.is_reachable())


# --------------------------------------------------------------------------- #
# delete                                                                       #
# --------------------------------------------------------------------------- #


class DeleteTestCase(unittest.TestCase):
    def test_delete_sends_revision_query(self) -> None:
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["method"] = request.method
            captured["path"] = request.url.path
            captured["query"] = dict(request.url.params)
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:35:00Z",
                    "task": _ok_task_payload(
                        revision=9, deleted_at="2026-04-26T08:35:00Z"
                    ),
                },
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            result = uploader.delete(17, 901, revision=8)

        self.assertEqual(captured["method"], "DELETE")
        self.assertEqual(
            captured["path"],
            "/api/v1/active-accounts/17/automation-tasks/901",
        )
        self.assertEqual(captured["query"], {"revision": "8"})
        self.assertEqual(result.task["revision"], 9)
        self.assertEqual(result.task["deleted_at"], "2026-04-26T08:35:00Z")
        self.assertTrue(connectivity.is_reachable())

    def test_delete_404_translates_to_account_not_in_active_pool(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(404, json_body={"detail": "account not found"})

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(AccountNotInActivePool):
                uploader.delete(17, 901, revision=8)

        self.assertTrue(connectivity.is_reachable())

    def test_delete_409_provides_resolution_without_keep_local(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                409,
                json_body={
                    "reason": "revision_conflict",
                    "server_revision": 12,
                    "server_payload": {"task_id": 901, "deleted_at": None},
                },
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(AutomationTaskRevisionConflict) as ctx:
                uploader.delete(17, 901, revision=8)

            conflict = ctx.exception
            self.assertEqual(conflict.server_revision, 12)
            # DELETE 路径没有 last_body，keep_local() 应该拒绝。
            with self.assertRaises(RuntimeError):
                conflict.resolution.keep_local()
            # accept_server() 仍然可用。
            self.assertEqual(
                conflict.resolution.accept_server(),
                {"task_id": 901, "deleted_at": None},
            )

    def test_invalid_revision_raises(self) -> None:
        connectivity = _make_connectivity()

        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(200, json_body={})

        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(ValueError):
                uploader.delete(1, 1, revision=-1)


# --------------------------------------------------------------------------- #
# Defensive parsing                                                            #
# --------------------------------------------------------------------------- #


class DefensiveParsingTestCase(unittest.TestCase):
    def test_response_without_task_field_raises(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                200, json_body={"server_time": "2026-04-26T08:30:00Z"}
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            with self.assertRaises(Exception):
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

    def test_409_without_revision_conflict_reason_falls_through(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                409, json_body={"reason": "something_else"}
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            # 没有 reason='revision_conflict'，应该作为普通 ProtocolError 抛出。
            with self.assertRaises(ProtocolError) as ctx:
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertEqual(ctx.exception.status_code, 409)


# --------------------------------------------------------------------------- #
# ServerSyncConfig 双开关守卫（spec 任务 11.9）                                  #
# --------------------------------------------------------------------------- #


def _make_handler_recording_calls(call_log: list[str]) -> Any:
    """构造一个会记录调用次数的 mock handler；任何被调用都会失败该断言。"""

    def handler(request: httpx.Request) -> httpx.Response:
        call_log.append(f"{request.method} {request.url.path}")
        return _build_response(
            200,
            json_body={
                "server_time": "2026-04-26T08:30:00Z",
                "task": _ok_task_payload(),
            },
        )

    return handler


class UploadGateTestCase(unittest.TestCase):
    """``AutomationTaskUploader`` 在 ``ServerSyncConfig`` 双开关下的守卫行为。"""

    def test_upsert_raises_when_config_not_configured(self) -> None:
        call_log: list[str] = []
        handler = _make_handler_recording_calls(call_log)
        connectivity = _make_connectivity()

        config = ServerSyncConfig(
            base_url=None, bearer_token=None, upload_enabled=True
        )
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(
                client, connectivity, config_provider=lambda: config
            )
            with self.assertRaises(UploadDisabledByConfig) as ctx:
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        # 不发起任何 HTTP 请求；connectivity 状态保持初始值。
        self.assertEqual(call_log, [])
        self.assertFalse(connectivity.is_reachable())
        # 异常文案区分原因。
        self.assertIn("未配置", ctx.exception.reason)

    def test_upsert_raises_when_upload_disabled(self) -> None:
        call_log: list[str] = []
        handler = _make_handler_recording_calls(call_log)
        connectivity = _make_connectivity()
        # 已经被标记为可达；守卫拒绝时不应改写该状态。
        connectivity.mark_reachable(
            server_time=datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)
        )

        config = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token="tok-abc",
            upload_enabled=False,
        )
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(
                client, connectivity, config_provider=lambda: config
            )
            with self.assertRaises(UploadDisabledByConfig) as ctx:
                uploader.upsert(
                    17, 901, _payload_for_upsert(), expected_revision=7
                )

        self.assertEqual(call_log, [])
        self.assertTrue(connectivity.is_reachable())
        self.assertIn("同步上行未启用", ctx.exception.reason)

    def test_delete_raises_when_upload_disabled(self) -> None:
        call_log: list[str] = []
        handler = _make_handler_recording_calls(call_log)
        connectivity = _make_connectivity()

        config = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token="tok-abc",
            upload_enabled=False,
        )
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(
                client, connectivity, config_provider=lambda: config
            )
            with self.assertRaises(UploadDisabledByConfig):
                uploader.delete(17, 901, revision=8)

        self.assertEqual(call_log, [])

    def test_upsert_proceeds_when_both_switches_enabled(self) -> None:
        captured_paths: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            captured_paths.append(request.url.path)
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "task": _ok_task_payload(revision=8),
                },
            )

        connectivity = _make_connectivity()
        config = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token="tok-abc",
            upload_enabled=True,
        )
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(
                client, connectivity, config_provider=lambda: config
            )
            result = uploader.upsert(
                17, 901, _payload_for_upsert(), expected_revision=7
            )

        self.assertEqual(
            captured_paths,
            ["/api/v1/active-accounts/17/automation-tasks/901"],
        )
        self.assertEqual(result.task["revision"], 8)
        self.assertTrue(connectivity.is_reachable())

    def test_legacy_constructor_without_config_provider_skips_gate(self) -> None:
        """没有传入 ``config_provider`` 时保留旧行为，便于既有调用方迁移。"""

        captured_paths: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            captured_paths.append(request.url.path)
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "task": _ok_task_payload(),
                },
            )

        connectivity = _make_connectivity()
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(client, connectivity)
            uploader.upsert(17, 901, _payload_for_upsert(), expected_revision=7)

        self.assertEqual(len(captured_paths), 1)

    def test_input_validation_errors_before_gate_check(self) -> None:
        """``ValueError`` 与 ``UploadDisabledByConfig`` 都不发起 HTTP；
        非法入参应优先抛 ``ValueError`` 以便单元测试定位错误。"""

        call_log: list[str] = []
        handler = _make_handler_recording_calls(call_log)
        connectivity = _make_connectivity()

        config = ServerSyncConfig(
            base_url=None, bearer_token=None, upload_enabled=False
        )
        with _build_client(handler) as client:
            uploader = AutomationTaskUploader(
                client, connectivity, config_provider=lambda: config
            )
            with self.assertRaises(ValueError):
                uploader.upsert(0, 1, _payload_for_upsert(), expected_revision=0)

        self.assertEqual(call_log, [])

    def test_should_upload_helper_matches_double_switch(self) -> None:
        """顶层 :func:`should_upload` 与 ``ServerSyncConfig.is_upload_enabled`` 保持一致。"""

        self.assertFalse(should_upload(None))
        self.assertFalse(
            should_upload(
                ServerSyncConfig(
                    base_url=None, bearer_token=None, upload_enabled=False
                )
            )
        )
        self.assertFalse(
            should_upload(
                ServerSyncConfig(
                    base_url="https://srv", bearer_token="t", upload_enabled=False
                )
            )
        )
        self.assertFalse(
            should_upload(
                ServerSyncConfig(
                    base_url=None, bearer_token="t", upload_enabled=True
                )
            )
        )
        self.assertTrue(
            should_upload(
                ServerSyncConfig(
                    base_url="https://srv", bearer_token="t", upload_enabled=True
                )
            )
        )


if __name__ == "__main__":  # pragma: no cover - manual runner
    unittest.main()
