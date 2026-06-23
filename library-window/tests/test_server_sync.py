"""server_sync 基础模块单元测试。

覆盖：

- ``ServerSyncSettings`` 归一化与边界校验。
- ``ServerSyncClient`` 在 200 / 4xx / 5xx / 网络错误下的异常翻译。
- ``ServerConnectivity`` 的可达性判定、TTL 过期、时钟对齐。
- ``ServerSyncConfig`` config.json 段读写、默认值、Local_Only_Mode 判定。
"""

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

import httpx

from wuyi_seat_bot.server_sync import (
    HttpsRequired,
    NetworkError,
    ProtocolError,
    RateLimited,
    ServerConnectivity,
    ServerError,
    ServerSyncClient,
    ServerSyncConfig,
    ServerSyncSettings,
    ServerUnreachable,
    Unauthorized,
    ensure_server_sync_defaults,
    load_server_sync_config,
    normalize_server_sync_settings,
    save_server_sync_config,
)
from wuyi_seat_bot.server_sync.connectivity_indicator import parse_server_time


def _build_response(
    status_code: int,
    *,
    json_body: Any = None,
    text: str | None = None,
    headers: dict[str, str] | None = None,
) -> httpx.Response:
    base_headers = {"content-type": "application/json"} if json_body is not None else {}
    if headers:
        base_headers.update(headers)
    if json_body is not None:
        return httpx.Response(status_code, json=json_body, headers=base_headers)
    if text is not None:
        return httpx.Response(status_code, text=text, headers=base_headers)
    return httpx.Response(status_code, headers=base_headers)


def _build_client_with_handler(
    handler,
    *,
    bearer_token: str = "tok-abc",
    base_url: str = "https://srv.example.com",
) -> ServerSyncClient:
    """构造 ``ServerSyncClient``，其底层 transport 由 ``handler`` 提供。

    通过自定义 ``client_factory`` 注入 ``httpx.MockTransport``，
    避免真实网络调用、且不打补丁全局对象。
    """

    transport = httpx.MockTransport(handler)

    def factory(**kwargs):
        kwargs["transport"] = transport
        # MockTransport 不需要真实连接，但保留 verify/headers 等参数。
        return httpx.Client(**kwargs)

    settings = ServerSyncSettings(
        server_base_url=base_url,
        bearer_token=bearer_token,
        verify_tls=True,
        request_timeout_seconds=5.0,
    )
    return ServerSyncClient(settings, client_factory=factory)


# --------------------------------------------------------------------------- #
# Settings                                                                     #
# --------------------------------------------------------------------------- #


class ServerSyncSettingsTestCase(unittest.TestCase):
    def test_normalize_strips_trailing_slash(self) -> None:
        settings = normalize_server_sync_settings(
            server_base_url="https://srv.example.com/",
            bearer_token="abc",
        )
        self.assertEqual(settings.server_base_url, "https://srv.example.com")
        self.assertEqual(settings.bearer_token, "abc")
        self.assertTrue(settings.verify_tls)

    def test_normalize_strips_multiple_trailing_slashes(self) -> None:
        settings = normalize_server_sync_settings(
            server_base_url="https://srv.example.com///",
            bearer_token="abc",
        )
        self.assertEqual(settings.server_base_url, "https://srv.example.com")

    def test_normalize_rejects_non_http_scheme(self) -> None:
        with self.assertRaises(ValueError):
            normalize_server_sync_settings(
                server_base_url="ftp://srv.example.com",
                bearer_token="abc",
            )

    def test_normalize_rejects_blank_token(self) -> None:
        with self.assertRaises(ValueError):
            normalize_server_sync_settings(
                server_base_url="https://srv.example.com",
                bearer_token="   ",
            )

    def test_normalize_rejects_blank_url(self) -> None:
        with self.assertRaises(ValueError):
            normalize_server_sync_settings(
                server_base_url="",
                bearer_token="abc",
            )

    def test_normalize_rejects_url_without_host(self) -> None:
        with self.assertRaises(ValueError):
            normalize_server_sync_settings(
                server_base_url="https://",
                bearer_token="abc",
            )

    def test_normalize_rejects_out_of_range_timeout(self) -> None:
        with self.assertRaises(ValueError):
            normalize_server_sync_settings(
                server_base_url="https://srv.example.com",
                bearer_token="abc",
                request_timeout_seconds=0.0,
            )
        with self.assertRaises(ValueError):
            normalize_server_sync_settings(
                server_base_url="https://srv.example.com",
                bearer_token="abc",
                request_timeout_seconds=999.0,
            )

    def test_with_token_replaces_token_only(self) -> None:
        settings = normalize_server_sync_settings(
            server_base_url="https://srv.example.com",
            bearer_token="abc",
        )
        rotated = settings.with_token("xyz")
        self.assertEqual(rotated.bearer_token, "xyz")
        self.assertEqual(rotated.server_base_url, settings.server_base_url)
        self.assertNotEqual(id(rotated), id(settings))


# --------------------------------------------------------------------------- #
# Client - happy path & error translation                                      #
# --------------------------------------------------------------------------- #


class ServerSyncClientTestCase(unittest.TestCase):
    def test_get_attaches_bearer_token(self) -> None:
        captured: dict[str, str] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["authorization"] = request.headers.get("authorization", "")
            captured["accept"] = request.headers.get("accept", "")
            return _build_response(200, json_body={"ok": True})

        with _build_client_with_handler(handler, bearer_token="tok-xyz") as client:
            response = client.get("/api/v1/active-accounts")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(captured["authorization"], "Bearer tok-xyz")
        self.assertEqual(captured["accept"], "application/json")

    def test_post_sends_json_body(self) -> None:
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["method"] = request.method
            captured["body"] = request.content
            captured["content_type"] = request.headers.get("content-type", "")
            return _build_response(200, json_body={"ok": True})

        with _build_client_with_handler(handler) as client:
            client.post(
                "/api/v1/active-accounts/17/blacklist-events",
                json_body={"evidence": "人机验证失败"},
            )

        self.assertEqual(captured["method"], "POST")
        self.assertIn(b"evidence", captured["body"])
        self.assertIn("application/json", captured["content_type"])

    def test_put_and_delete_dispatch_methods(self) -> None:
        seen: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            seen.append(request.method)
            return _build_response(200, json_body={"ok": True})

        with _build_client_with_handler(handler) as client:
            client.put("/x", json_body={"a": 1})
            client.delete("/x")

        self.assertEqual(seen, ["PUT", "DELETE"])

    def test_5xx_raises_server_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(503, json_body={"reason": "internal_error"})

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(ServerError) as ctx:
                client.get("/x")

        self.assertIsInstance(ctx.exception, ServerUnreachable)
        self.assertEqual(ctx.exception.status_code, 503)
        self.assertEqual(ctx.exception.body, {"reason": "internal_error"})

    def test_401_raises_unauthorized(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(401, json_body={"reason": "unauthorized"})

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(Unauthorized) as ctx:
                client.get("/x")

        self.assertEqual(ctx.exception.status_code, 401)
        self.assertEqual(ctx.exception.body, {"reason": "unauthorized"})

    def test_426_raises_https_required(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(426, json_body={"reason": "https_required"})

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(HttpsRequired) as ctx:
                client.get("/x")

        self.assertEqual(ctx.exception.status_code, 426)

    def test_429_raises_rate_limited_with_retry_after(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                429,
                json_body={"reason": "rate_limited", "retry_after": 30},
                headers={"retry-after": "30"},
            )

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(RateLimited) as ctx:
                client.get("/x")

        self.assertEqual(ctx.exception.status_code, 429)
        self.assertEqual(ctx.exception.retry_after, 30.0)

    def test_404_raises_protocol_error_only(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(404, json_body={"detail": "account not found"})

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(ProtocolError) as ctx:
                client.get("/api/v1/active-accounts/999/detail")

        # 404 不是 ServerUnreachable —— 调用方据 status_code 自己处理。
        self.assertNotIsInstance(ctx.exception, ServerUnreachable)
        self.assertEqual(ctx.exception.status_code, 404)
        self.assertEqual(ctx.exception.body, {"detail": "account not found"})

    def test_409_raises_protocol_error_with_payload(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                409,
                json_body={
                    "reason": "revision_conflict",
                    "server_revision": 7,
                    "server_payload": {"room_name": "三层东区"},
                },
            )

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(ProtocolError) as ctx:
                client.put("/x", json_body={"revision": 6})

        self.assertEqual(ctx.exception.status_code, 409)
        self.assertEqual(ctx.exception.body["reason"], "revision_conflict")

    def test_connect_error_raises_network_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("Connection refused", request=request)

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(NetworkError) as ctx:
                client.get("/x")

        self.assertIsInstance(ctx.exception, ServerUnreachable)

    def test_timeout_raises_network_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("read timeout", request=request)

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(NetworkError):
                client.get("/x")

    def test_close_releases_underlying_client(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(200, json_body={"ok": True})

        client = _build_client_with_handler(handler)
        client.close()
        # 关闭后再发请求应该抛错（httpx 的标准行为）。
        with self.assertRaises(RuntimeError):
            client.get("/x")

    def test_non_json_4xx_keeps_text_body(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                400,
                text="bad request raw",
                headers={"content-type": "text/plain"},
            )

        with _build_client_with_handler(handler) as client:
            with self.assertRaises(ProtocolError) as ctx:
                client.get("/x")

        self.assertEqual(ctx.exception.body, "bad request raw")


# --------------------------------------------------------------------------- #
# Connectivity                                                                 #
# --------------------------------------------------------------------------- #


class ServerConnectivityTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.now = datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)

    def _make(self, *, ttl: float = 300.0) -> tuple[ServerConnectivity, MagicMock]:
        clock = MagicMock(side_effect=lambda: self.now)
        connectivity = ServerConnectivity(reachable_ttl_seconds=ttl, clock=clock)
        return connectivity, clock

    def test_initial_state_not_reachable(self) -> None:
        connectivity, _ = self._make()
        self.assertFalse(connectivity.is_reachable())
        snapshot = connectivity.snapshot()
        self.assertFalse(snapshot.reachable)
        self.assertIsNone(snapshot.last_success_at)
        self.assertIsNone(snapshot.last_server_time)

    def test_mark_reachable_updates_state_and_clock_skew(self) -> None:
        connectivity, _ = self._make()
        server_time = self.now + timedelta(seconds=2)
        connectivity.mark_reachable(server_time=server_time)

        self.assertTrue(connectivity.is_reachable())
        self.assertEqual(connectivity.last_known_server_time(), server_time)
        self.assertAlmostEqual(connectivity.clock_skew_seconds(), 2.0)
        snapshot = connectivity.snapshot()
        self.assertTrue(snapshot.reachable)
        self.assertEqual(snapshot.last_server_time, server_time)

    def test_mark_unreachable_overrides_recent_success(self) -> None:
        connectivity, _ = self._make()
        connectivity.mark_reachable(server_time=self.now)
        self.assertTrue(connectivity.is_reachable())

        connectivity.mark_unreachable("connect timeout", status_code=None)
        self.assertFalse(connectivity.is_reachable())
        snapshot = connectivity.snapshot()
        self.assertFalse(snapshot.reachable)
        self.assertEqual(snapshot.last_failure_reason, "connect timeout")

    def test_mark_unreachable_uses_default_message_for_blank(self) -> None:
        connectivity, _ = self._make()
        connectivity.mark_unreachable("   ")
        snapshot = connectivity.snapshot()
        self.assertEqual(snapshot.last_failure_reason, "服务端不可达")

    def test_ttl_expires_makes_state_unreachable(self) -> None:
        connectivity, clock = self._make(ttl=60.0)
        connectivity.mark_reachable(server_time=self.now)
        self.assertTrue(connectivity.is_reachable())

        # 推进时钟超过 TTL。
        self.now = self.now + timedelta(seconds=61)
        self.assertFalse(connectivity.is_reachable())

    def test_recovering_after_failure(self) -> None:
        connectivity, _ = self._make()
        connectivity.mark_unreachable("network down")
        self.assertFalse(connectivity.is_reachable())

        # 服务端恢复后再 mark 一次成功。
        connectivity.mark_reachable(server_time=self.now)
        self.assertTrue(connectivity.is_reachable())
        snapshot = connectivity.snapshot()
        self.assertEqual(snapshot.last_failure_reason, "")

    def test_estimated_server_time_uses_skew(self) -> None:
        connectivity, _ = self._make()
        server_time = self.now + timedelta(seconds=10)
        connectivity.mark_reachable(server_time=server_time)
        estimated = connectivity.estimated_server_time()
        assert estimated is not None
        self.assertAlmostEqual(
            (estimated - self.now).total_seconds(), 10.0, places=3
        )

    def test_estimated_server_time_none_when_never_aligned(self) -> None:
        connectivity, _ = self._make()
        connectivity.mark_reachable(server_time=None)
        self.assertIsNone(connectivity.estimated_server_time())

    def test_invalid_ttl_rejected(self) -> None:
        with self.assertRaises(ValueError):
            ServerConnectivity(reachable_ttl_seconds=0)
        with self.assertRaises(ValueError):
            ServerConnectivity(reachable_ttl_seconds=-1)


class ParseServerTimeTestCase(unittest.TestCase):
    def test_parse_z_suffix(self) -> None:
        parsed = parse_server_time("2026-04-26T08:30:00Z")
        self.assertEqual(parsed, datetime(2026, 4, 26, 8, 30, 0, tzinfo=timezone.utc))

    def test_parse_offset(self) -> None:
        parsed = parse_server_time("2026-04-26T16:30:00+08:00")
        self.assertEqual(parsed, datetime(2026, 4, 26, 8, 30, 0, tzinfo=timezone.utc))

    def test_parse_naive_assumes_utc(self) -> None:
        parsed = parse_server_time("2026-04-26T08:30:00")
        self.assertEqual(parsed, datetime(2026, 4, 26, 8, 30, 0, tzinfo=timezone.utc))

    def test_parse_returns_none_for_invalid(self) -> None:
        self.assertIsNone(parse_server_time(""))
        self.assertIsNone(parse_server_time(None))
        self.assertIsNone(parse_server_time("not-a-date"))
        self.assertIsNone(parse_server_time(12345))

    def test_parse_existing_datetime(self) -> None:
        original = datetime(2026, 4, 26, 8, 30, 0, tzinfo=timezone.utc)
        parsed = parse_server_time(original)
        self.assertEqual(parsed, original)


# --------------------------------------------------------------------------- #
# ServerSyncConfig - 默认值与状态判定                                           #
# --------------------------------------------------------------------------- #


class ServerSyncConfigStateTestCase(unittest.TestCase):
    def test_default_is_local_only(self) -> None:
        config = ServerSyncConfig()
        self.assertIsNone(config.base_url)
        self.assertIsNone(config.bearer_token)
        self.assertTrue(config.verify_tls)
        self.assertFalse(config.upload_enabled)
        self.assertFalse(config.is_configured())
        self.assertFalse(config.is_upload_enabled())
        self.assertTrue(config.is_local_only())

    def test_is_configured_requires_both_fields(self) -> None:
        only_url = ServerSyncConfig(base_url="https://srv.example.com")
        only_token = ServerSyncConfig(bearer_token="tok")
        full = ServerSyncConfig(
            base_url="https://srv.example.com", bearer_token="tok"
        )
        self.assertFalse(only_url.is_configured())
        self.assertFalse(only_token.is_configured())
        self.assertTrue(full.is_configured())

    def test_is_upload_enabled_requires_configured_and_flag(self) -> None:
        configured_no_upload = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token="tok",
            upload_enabled=False,
        )
        configured_with_upload = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token="tok",
            upload_enabled=True,
        )
        not_configured_with_upload = ServerSyncConfig(upload_enabled=True)

        self.assertFalse(configured_no_upload.is_upload_enabled())
        self.assertTrue(configured_with_upload.is_upload_enabled())
        self.assertFalse(not_configured_with_upload.is_upload_enabled())

    def test_is_local_only_when_upload_off(self) -> None:
        # 已配置但上行关闭 → Local_Only_Mode 仍生效（上行通道关闭即视为本地模式）。
        config = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token="tok",
            upload_enabled=False,
        )
        self.assertTrue(config.is_configured())
        self.assertTrue(config.is_local_only())

    def test_is_local_only_false_when_fully_enabled(self) -> None:
        config = ServerSyncConfig(
            base_url="https://srv.example.com",
            bearer_token="tok",
            upload_enabled=True,
        )
        self.assertFalse(config.is_local_only())

    def test_to_server_sync_settings_returns_none_when_unconfigured(self) -> None:
        self.assertIsNone(ServerSyncConfig().to_server_sync_settings())

    def test_to_server_sync_settings_returns_normalized(self) -> None:
        config = ServerSyncConfig(
            base_url="https://srv.example.com/",
            bearer_token="  tok  ",
            verify_tls=False,
        )
        settings = config.to_server_sync_settings()
        assert settings is not None
        self.assertEqual(settings.server_base_url, "https://srv.example.com")
        self.assertEqual(settings.bearer_token, "tok")
        self.assertFalse(settings.verify_tls)


# --------------------------------------------------------------------------- #
# ServerSyncConfig - config.json 读写                                           #
# --------------------------------------------------------------------------- #


class LoadServerSyncConfigTestCase(unittest.TestCase):
    def _write(self, payload: dict[str, Any]) -> Path:
        config_path = self._tmp_path / "config.json"
        config_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return config_path

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self._tmp_path = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_load_returns_default_when_file_missing(self) -> None:
        config = load_server_sync_config(self._tmp_path / "missing.json")
        self.assertEqual(config, ServerSyncConfig())

    def test_load_returns_default_when_section_missing(self) -> None:
        path = self._write({"accounts": []})
        config = load_server_sync_config(path)
        self.assertEqual(config, ServerSyncConfig())

    def test_load_parses_full_section(self) -> None:
        path = self._write(
            {
                "server_sync": {
                    "base_url": "https://srv.example.com",
                    "bearer_token": "tok",
                    "verify_tls": False,
                    "upload_enabled": True,
                }
            }
        )
        config = load_server_sync_config(path)
        self.assertEqual(config.base_url, "https://srv.example.com")
        self.assertEqual(config.bearer_token, "tok")
        self.assertFalse(config.verify_tls)
        self.assertTrue(config.upload_enabled)

    def test_load_uses_defaults_for_invalid_field_types(self) -> None:
        path = self._write(
            {
                "server_sync": {
                    "base_url": 123,  # 非字符串 → None
                    "bearer_token": "  ",  # 空白 → None
                    "verify_tls": "yes",  # 非 bool → 默认 True
                    "upload_enabled": 1,  # 非 bool → 默认 False
                }
            }
        )
        config = load_server_sync_config(path)
        self.assertIsNone(config.base_url)
        self.assertIsNone(config.bearer_token)
        self.assertTrue(config.verify_tls)
        self.assertFalse(config.upload_enabled)

    def test_load_returns_default_when_payload_not_dict(self) -> None:
        path = self._tmp_path / "config.json"
        path.write_text(json.dumps([1, 2, 3]), encoding="utf-8")
        self.assertEqual(load_server_sync_config(path), ServerSyncConfig())

    def test_load_returns_default_when_section_not_dict(self) -> None:
        path = self._write({"server_sync": "not-a-dict"})
        self.assertEqual(load_server_sync_config(path), ServerSyncConfig())

    def test_load_returns_default_when_json_invalid(self) -> None:
        path = self._tmp_path / "config.json"
        path.write_text("{not valid json", encoding="utf-8")
        self.assertEqual(load_server_sync_config(path), ServerSyncConfig())


class EnsureServerSyncDefaultsTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self._tmp_path = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_skips_when_file_does_not_exist(self) -> None:
        config_path = self._tmp_path / "config.json"
        result = ensure_server_sync_defaults(config_path)
        self.assertFalse(config_path.exists())
        self.assertEqual(result, ServerSyncConfig())

    def test_inserts_defaults_when_section_missing(self) -> None:
        config_path = self._tmp_path / "config.json"
        config_path.write_text(
            json.dumps(
                {
                    "default_account": "2023000001",
                    "accounts": [{"name": "2023000001"}],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        result = ensure_server_sync_defaults(config_path)

        payload = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertEqual(
            payload["server_sync"],
            {
                "base_url": None,
                "bearer_token": None,
                "verify_tls": True,
                "upload_enabled": False,
            },
        )
        # 既有字段不被删除 / 重写。
        self.assertEqual(payload["default_account"], "2023000001")
        self.assertEqual(payload["accounts"], [{"name": "2023000001"}])
        self.assertEqual(result, ServerSyncConfig())

    def test_preserves_existing_section_values(self) -> None:
        config_path = self._tmp_path / "config.json"
        config_path.write_text(
            json.dumps(
                {
                    "accounts": [],
                    "server_sync": {
                        "base_url": "https://srv.example.com",
                        "bearer_token": "tok",
                        "verify_tls": False,
                        "upload_enabled": True,
                    },
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        result = ensure_server_sync_defaults(config_path)

        payload = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertEqual(
            payload["server_sync"],
            {
                "base_url": "https://srv.example.com",
                "bearer_token": "tok",
                "verify_tls": False,
                "upload_enabled": True,
            },
        )
        self.assertEqual(result.base_url, "https://srv.example.com")
        self.assertEqual(result.bearer_token, "tok")
        self.assertFalse(result.verify_tls)
        self.assertTrue(result.upload_enabled)

    def test_fills_only_missing_fields(self) -> None:
        config_path = self._tmp_path / "config.json"
        config_path.write_text(
            json.dumps(
                {
                    "accounts": [],
                    "server_sync": {
                        "base_url": "https://srv.example.com",
                    },
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        ensure_server_sync_defaults(config_path)

        payload = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertEqual(
            payload["server_sync"],
            {
                "base_url": "https://srv.example.com",
                "bearer_token": None,
                "verify_tls": True,
                "upload_enabled": False,
            },
        )

    def test_does_not_rewrite_when_all_fields_present(self) -> None:
        config_path = self._tmp_path / "config.json"
        original = {
            "accounts": [{"name": "a"}],
            "server_sync": {
                "base_url": "https://srv.example.com",
                "bearer_token": "tok",
                "verify_tls": True,
                "upload_enabled": False,
            },
        }
        config_path.write_text(
            json.dumps(original, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        original_text = config_path.read_text(encoding="utf-8")
        original_mtime = config_path.stat().st_mtime_ns

        ensure_server_sync_defaults(config_path)

        # 字节级一致：所有字段已存在时不重写文件。
        self.assertEqual(config_path.read_text(encoding="utf-8"), original_text)
        self.assertEqual(config_path.stat().st_mtime_ns, original_mtime)

    def test_handles_invalid_json_without_raising(self) -> None:
        config_path = self._tmp_path / "config.json"
        config_path.write_text("{not valid", encoding="utf-8")
        # 不应抛异常；返回默认 config。
        result = ensure_server_sync_defaults(config_path)
        self.assertEqual(result, ServerSyncConfig())

    def test_handles_non_object_payload(self) -> None:
        config_path = self._tmp_path / "config.json"
        config_path.write_text("[1, 2, 3]", encoding="utf-8")
        result = ensure_server_sync_defaults(config_path)
        self.assertEqual(result, ServerSyncConfig())


class SaveServerSyncConfigTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self._tmp_path = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_save_creates_file_when_missing(self) -> None:
        config_path = self._tmp_path / "config.json"
        save_server_sync_config(
            config_path,
            ServerSyncConfig(
                base_url="https://srv.example.com",
                bearer_token="tok",
                verify_tls=False,
                upload_enabled=True,
            ),
        )

        payload = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertEqual(
            payload["server_sync"],
            {
                "base_url": "https://srv.example.com",
                "bearer_token": "tok",
                "verify_tls": False,
                "upload_enabled": True,
            },
        )

    def test_save_preserves_other_fields(self) -> None:
        config_path = self._tmp_path / "config.json"
        config_path.write_text(
            json.dumps(
                {
                    "default_account": "2023000001",
                    "accounts": [{"name": "2023000001"}],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        save_server_sync_config(
            config_path,
            ServerSyncConfig(base_url="https://srv.example.com", bearer_token="tok"),
        )

        payload = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertEqual(payload["default_account"], "2023000001")
        self.assertEqual(payload["accounts"], [{"name": "2023000001"}])
        self.assertEqual(payload["server_sync"]["base_url"], "https://srv.example.com")
        self.assertEqual(payload["server_sync"]["bearer_token"], "tok")


if __name__ == "__main__":  # pragma: no cover - manual runner
    unittest.main()
