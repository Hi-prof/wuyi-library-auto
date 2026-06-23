"""account-pool-tri-sync task 8.1 中间件 / 异常处理器单元测试。

覆盖：

* 鉴权依赖：缺头 / 错格式 / 命中 / 未撤销 / 已撤销 token 的 401 与放行。
* HTTPS 中间件：``required=True`` 时非 HTTPS 请求返回 426；本机环回放行；
  开关关闭时不影响请求。
* 限频依赖：``(token_id, account_id)`` 60 秒滑窗内第 N+1 次请求返回 429
  并带 ``Retry-After`` 头部。
* 异常处理器：``account_not_found`` 字节级一致；``revision_conflict`` 经
  ``scrub`` 之后不带密码；``pool_full`` / ``illegal_transition`` /
  ``missing_login_credentials`` / ``duplicate_student_id`` / ``idle_empty``
  各自落入 422 + 正确 ``reason``；``HTTPException`` fallback。

测试不读取真实 ``ACCOUNT_POOL_SECRET_KEY``，全部使用 in-memory FastAPI 装配，
路由层用 ``/api/v1/_test/...`` 假端点暴露依赖。
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException
from fastapi.testclient import TestClient

from prevent_auto.account_pool.models import (
    AccountNotInActivePool,
    ClientKind,
    DuplicateStudentId,
    IdleEmpty,
    IllegalPoolTransition,
    MissingLoginCredentials,
    PoolCapacityExceeded,
    RevisionConflict,
)
from prevent_auto.database import initialize_database
from prevent_auto.repositories.client_api_tokens import ClientApiTokensRepository
from prevent_auto.web.exception_handlers import (
    ACCOUNT_NOT_FOUND_BODY,
    register_account_pool_exception_handlers,
)
from prevent_auto.web.middleware import (
    DetailRateLimiter,
    HttpsRequiredMiddleware,
    install_auth_dependency_state,
    install_detail_rate_limiter,
    is_loopback_host,
    require_bearer_token,
    require_detail_rate_limit,
)


def _run_asgi_request(
    app,
    *,
    method: str,
    path: str,
    scheme: str = "http",
    client_host: str = "127.0.0.1",
    client_port: int = 51234,
) -> tuple[int, bytes]:
    """直接调用 ASGI 应用，返回 ``(status_code, body_bytes)``。

    Starlette 的 :class:`TestClient` 不允许显式覆盖 ``scope["client"]`` /
    ``scope["scheme"]``，对 HTTPS 中间件单元测试不够用；这里手搓一个最小 ASGI
    runner，便于注入「来自公网 IP 的明文请求」与「HTTPS 请求」两类场景。
    """

    import asyncio

    captured: dict[str, object] = {"body": b""}

    async def _receive() -> dict:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def _send(message: dict) -> None:
        if message["type"] == "http.response.start":
            captured["status"] = int(message["status"])
        elif message["type"] == "http.response.body":
            chunk = bytes(message.get("body", b""))
            captured["body"] = bytes(captured["body"]) + chunk

    scope = {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "method": method,
        "scheme": scheme,
        "path": path,
        "raw_path": path.encode("utf-8"),
        "query_string": b"",
        "root_path": "",
        "headers": [(b"host", client_host.encode("utf-8"))],
        "client": (client_host, client_port),
        "server": (client_host, 80 if scheme == "http" else 443),
        "extensions": {},
        "app": app,
    }

    asyncio.new_event_loop().run_until_complete(app(scope, _receive, _send))
    return int(captured["status"]), bytes(captured["body"])


def _build_app(
    *,
    database_path: Path,
    https_required: bool,
    rate_limit: int = 6,
    clock=None,
) -> tuple[FastAPI, ClientApiTokensRepository, DetailRateLimiter]:
    """构造一个最小测试 app，挂载本 task 8.1 引入的中间件 / 异常处理器。

    暴露三个 ``/api/v1/_test/...`` 假端点用于触发依赖：

    * ``GET /api/v1/_test/auth``：仅鉴权放行后回 ``{"ok": true, "label": <label>}``。
    * ``GET /api/v1/_test/detail/{account_id}``：鉴权 + 限频依赖联动。
    * ``GET /api/v1/_test/raise/{kind}``：手动抛业务异常，验证异常处理器映射。
    """

    app = FastAPI()
    app.add_middleware(HttpsRequiredMiddleware, required=https_required)
    repository = ClientApiTokensRepository(database_path, token_pepper="unit-pepper")
    install_auth_dependency_state(app, tokens_repository=repository)
    limiter = DetailRateLimiter(limit_per_minute=rate_limit, clock=clock)
    install_detail_rate_limiter(app, limiter=limiter)
    register_account_pool_exception_handlers(app)

    @app.get("/api/v1/_test/auth")
    def _auth_route(token=Depends(require_bearer_token)) -> dict:
        return {"ok": True, "label": token.label}

    @app.get("/api/v1/_test/detail/{account_id}")
    def _detail_route(
        account_id: int,
        token=Depends(require_bearer_token),
        _=Depends(require_detail_rate_limit),
    ) -> dict:
        return {"ok": True, "account_id": account_id, "label": token.label}

    @app.get("/api/v1/_test/raise/{kind}")
    def _raise_route(kind: str) -> dict:  # pragma: no cover - 仅在异常路径生效
        if kind == "account_not_found":
            raise AccountNotInActivePool("not active")
        if kind == "revision_conflict":
            raise RevisionConflict(
                server_revision=42,
                server_payload={
                    "task_id": 7,
                    "password": "P@ssw0rd",  # 应被 scrub
                    "room_name": "三层东区",
                },
            )
        if kind == "pool_full":
            raise PoolCapacityExceeded("capacity reached")
        if kind == "illegal_transition":
            raise IllegalPoolTransition("idle->suspended forbidden")
        if kind == "missing_login_credentials":
            raise MissingLoginCredentials("missing")
        if kind == "duplicate_student_id":
            raise DuplicateStudentId("duplicate")
        if kind == "idle_empty":
            raise IdleEmpty("empty")
        if kind == "http_with_dict":
            raise HTTPException(status_code=418, detail={"reason": "teapot"})
        if kind == "http_with_string":
            raise HTTPException(status_code=418, detail="i_am_a_teapot")
        return {"ok": False}

    return app, repository, limiter


class HttpsLoopbackHelperTestCase(unittest.TestCase):
    def test_loopback_hosts_recognized(self) -> None:
        for host in ("127.0.0.1", "::1", "localhost", "LOCALHOST"):
            with self.subTest(host=host):
                self.assertTrue(is_loopback_host(host))

    def test_public_hosts_rejected(self) -> None:
        for host in ("203.0.113.10", "example.com"):
            with self.subTest(host=host):
                self.assertFalse(is_loopback_host(host))

    def test_empty_or_none_treated_as_loopback(self) -> None:
        self.assertTrue(is_loopback_host(None))
        self.assertTrue(is_loopback_host(""))
        self.assertTrue(is_loopback_host("   "))


class AuthDependencyTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        initialize_database(self.database_path)
        self.app, self.repo, self.limiter = _build_app(
            database_path=self.database_path,
            https_required=False,
        )
        self.client = TestClient(self.app)

    def _issue_token(self, *, label: str = "test-window") -> str:
        issued = self.repo.issue(label=label, client_kind=ClientKind.WINDOW)
        return issued.raw_token

    def test_missing_authorization_returns_401(self) -> None:
        response = self.client.get("/api/v1/_test/auth")

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"reason": "unauthorized"})

    def test_malformed_authorization_returns_401(self) -> None:
        for header in ("", "Token abc", "Bearer", "bearer  "):
            with self.subTest(header=header):
                response = self.client.get(
                    "/api/v1/_test/auth",
                    headers={"Authorization": header},
                )
                self.assertEqual(response.status_code, 401)
                self.assertEqual(response.json(), {"reason": "unauthorized"})

    def test_unknown_token_returns_401(self) -> None:
        response = self.client.get(
            "/api/v1/_test/auth",
            headers={"Authorization": "Bearer not-a-real-token"},
        )

        self.assertEqual(response.status_code, 401)

    def test_revoked_token_returns_401(self) -> None:
        raw = self._issue_token()
        record = self.repo.find_by_token(raw)
        assert record is not None
        self.repo.revoke(record.id)

        response = self.client.get(
            "/api/v1/_test/auth",
            headers={"Authorization": f"Bearer {raw}"},
        )

        self.assertEqual(response.status_code, 401)

    def test_valid_token_passes(self) -> None:
        raw = self._issue_token(label="my-window")

        response = self.client.get(
            "/api/v1/_test/auth",
            headers={"Authorization": f"Bearer {raw}"},
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"ok": True, "label": "my-window"})


class HttpsRequiredMiddlewareTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        initialize_database(self.database_path)

    def _client_for(self, *, https_required: bool) -> TestClient:
        app, _, _ = _build_app(
            database_path=self.database_path, https_required=https_required
        )
        return TestClient(app)

    def test_disabled_passes_through(self) -> None:
        client = self._client_for(https_required=False)
        # 未带 token，但 HTTPS 中间件不阻拦 → 走到鉴权依赖返回 401。
        response = client.get(
            "/api/v1/_test/auth",
            headers={"X-Forwarded-For": "203.0.113.5"},
        )
        self.assertEqual(response.status_code, 401)

    def test_loopback_request_bypasses_https_check(self) -> None:
        app, _, _ = _build_app(
            database_path=self.database_path, https_required=True
        )
        # 使用 ASGI runner 显式注入环回 client.host
        status, body = _run_asgi_request(
            app,
            method="GET",
            path="/api/v1/_test/auth",
            scheme="http",
            client_host="127.0.0.1",
        )
        # 环回放行 → 走到鉴权依赖（无 token）→ 401
        self.assertEqual(status, 401)
        self.assertNotIn(b"https_required", body)

    def test_non_loopback_http_returns_426(self) -> None:
        app, _, _ = _build_app(
            database_path=self.database_path, https_required=True
        )
        status, body = _run_asgi_request(
            app,
            method="GET",
            path="/api/v1/_test/auth",
            scheme="http",
            client_host="203.0.113.5",
        )
        self.assertEqual(status, 426)
        self.assertEqual(body, b'{"reason":"https_required"}')

    def test_non_api_path_not_blocked_when_http(self) -> None:
        app, _, _ = _build_app(
            database_path=self.database_path, https_required=True
        )

        @app.get("/health")
        def health() -> dict:
            return {"ok": True}

        status, _ = _run_asgi_request(
            app,
            method="GET",
            path="/health",
            scheme="http",
            client_host="203.0.113.5",
        )
        # /health 不在 /api/v1 前缀下，HTTPS 中间件应放行
        self.assertEqual(status, 200)

    def test_https_scheme_request_passes_even_from_public_ip(self) -> None:
        app, _, _ = _build_app(
            database_path=self.database_path, https_required=True
        )
        status, body = _run_asgi_request(
            app,
            method="GET",
            path="/api/v1/_test/auth",
            scheme="https",
            client_host="203.0.113.5",
        )
        # 走 HTTPS 即使是公网 IP，也应进入鉴权依赖（无 token → 401）
        self.assertEqual(status, 401)
        self.assertNotIn(b"https_required", body)


class DetailRateLimiterTestCase(unittest.TestCase):
    """直接对 :class:`DetailRateLimiter` 做窗口语义测试，时钟可控。"""

    def test_allows_up_to_limit_then_429s(self) -> None:
        clock_value = [1000.0]
        limiter = DetailRateLimiter(
            limit_per_minute=3, clock=lambda: clock_value[0]
        )

        for _ in range(3):
            self.assertEqual(limiter.acquire(token_id=1, account_id=42), 0)
        retry = limiter.acquire(token_id=1, account_id=42)
        self.assertGreater(retry, 0)

    def test_window_slides(self) -> None:
        clock_value = [0.0]
        limiter = DetailRateLimiter(
            limit_per_minute=2, clock=lambda: clock_value[0]
        )

        self.assertEqual(limiter.acquire(token_id=1, account_id=10), 0)
        clock_value[0] = 30.0
        self.assertEqual(limiter.acquire(token_id=1, account_id=10), 0)
        # 第 3 次仍在 60s 窗口内，应被拒绝
        retry = limiter.acquire(token_id=1, account_id=10)
        self.assertGreater(retry, 0)
        # 推进到 61s，最早一条命中过期，第 3 次再发应该放行
        clock_value[0] = 61.0
        self.assertEqual(limiter.acquire(token_id=1, account_id=10), 0)

    def test_isolated_per_token_account_pair(self) -> None:
        clock_value = [0.0]
        limiter = DetailRateLimiter(
            limit_per_minute=1, clock=lambda: clock_value[0]
        )

        self.assertEqual(limiter.acquire(token_id=1, account_id=10), 0)
        # 同 token 不同 account：独立计数，应放行
        self.assertEqual(limiter.acquire(token_id=1, account_id=20), 0)
        # 不同 token 同 account：独立计数，应放行
        self.assertEqual(limiter.acquire(token_id=2, account_id=10), 0)
        # 同 (token, account) 重复：应被拒
        self.assertGreater(limiter.acquire(token_id=1, account_id=10), 0)


class DetailRateLimitDependencyTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        initialize_database(self.database_path)
        self.clock_value = [1000.0]
        self.app, self.repo, self.limiter = _build_app(
            database_path=self.database_path,
            https_required=False,
            rate_limit=2,
            clock=lambda: self.clock_value[0],
        )
        self.client = TestClient(self.app)
        issued = self.repo.issue(label="rate", client_kind=ClientKind.WINDOW)
        self.token = issued.raw_token

    def test_returns_429_after_limit(self) -> None:
        headers = {"Authorization": f"Bearer {self.token}"}

        for _ in range(2):
            response = self.client.get(
                "/api/v1/_test/detail/17", headers=headers
            )
            self.assertEqual(response.status_code, 200)

        response = self.client.get("/api/v1/_test/detail/17", headers=headers)
        self.assertEqual(response.status_code, 429)
        body = response.json()
        self.assertEqual(body["reason"], "rate_limited")
        self.assertGreaterEqual(body["retry_after"], 1)
        self.assertIn("retry-after", {key.lower() for key in response.headers})

    def test_separate_account_id_has_own_quota(self) -> None:
        headers = {"Authorization": f"Bearer {self.token}"}

        for _ in range(2):
            self.assertEqual(
                self.client.get("/api/v1/_test/detail/17", headers=headers).status_code,
                200,
            )

        # 不同 account_id 应该有独立配额
        self.assertEqual(
            self.client.get("/api/v1/_test/detail/18", headers=headers).status_code,
            200,
        )


class ExceptionHandlerTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        initialize_database(self.database_path)
        self.app, _, _ = _build_app(
            database_path=self.database_path, https_required=False
        )
        self.client = TestClient(self.app)

    def test_account_not_found_is_byte_exact(self) -> None:
        response = self.client.get("/api/v1/_test/raise/account_not_found")

        self.assertEqual(response.status_code, 404)
        # 字节级一致：键序、键名、空白字符都不能动
        self.assertEqual(response.content, ACCOUNT_NOT_FOUND_BODY)
        self.assertEqual(
            response.headers["content-type"].lower(), "application/json"
        )

    def test_revision_conflict_includes_server_payload_scrubbed(self) -> None:
        response = self.client.get("/api/v1/_test/raise/revision_conflict")

        self.assertEqual(response.status_code, 409)
        body = response.json()
        self.assertEqual(body["reason"], "revision_conflict")
        self.assertEqual(body["server_revision"], 42)
        self.assertEqual(body["server_payload"]["task_id"], 7)
        self.assertEqual(body["server_payload"]["room_name"], "三层东区")
        # 密码字段应被 scrub 替换
        self.assertNotIn("P@ssw0rd", response.text)
        self.assertEqual(
            body["server_payload"]["password"], "***SCRUBBED***"
        )

    def test_pool_full_returns_422(self) -> None:
        response = self.client.get("/api/v1/_test/raise/pool_full")

        self.assertEqual(response.status_code, 422)
        body = response.json()
        self.assertEqual(body["reason"], "pool_full")
        self.assertEqual(body["detail"], "号池已满")

    def test_illegal_transition_returns_422(self) -> None:
        response = self.client.get("/api/v1/_test/raise/illegal_transition")

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json(), {"reason": "illegal_transition"})

    def test_missing_login_credentials_returns_422(self) -> None:
        response = self.client.get(
            "/api/v1/_test/raise/missing_login_credentials"
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(
            response.json(), {"reason": "missing_login_credentials"}
        )

    def test_duplicate_student_id_returns_422(self) -> None:
        response = self.client.get("/api/v1/_test/raise/duplicate_student_id")

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json(), {"reason": "duplicate_student_id"})

    def test_idle_empty_returns_422(self) -> None:
        response = self.client.get("/api/v1/_test/raise/idle_empty")

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json(), {"reason": "idle_empty"})

    def test_http_exception_with_dict_passes_through(self) -> None:
        response = self.client.get("/api/v1/_test/raise/http_with_dict")

        self.assertEqual(response.status_code, 418)
        self.assertEqual(response.json(), {"reason": "teapot"})

    def test_http_exception_with_string_normalized_to_reason(self) -> None:
        response = self.client.get("/api/v1/_test/raise/http_with_string")

        self.assertEqual(response.status_code, 418)
        self.assertEqual(response.json(), {"reason": "i_am_a_teapot"})


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
