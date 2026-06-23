"""``active_pool_repository`` 单元测试。

覆盖：

- 接口 A 拉取与缓存维护，连接性标记。
- 接口 B 命中 / 404 / 422 / 网络错误的不同行为。
- 数据类字段解析与密码字段不持久化的约束。
"""

from __future__ import annotations

import unittest
from datetime import datetime, timezone
from typing import Any

import httpx

from wuyi_seat_bot.server_sync import (
    ActiveAccountDetail,
    ActiveAccountListItem,
    ActivePoolRepository,
    HttpsRequired,
    NetworkError,
    ProtocolError,
    RateLimited,
    ServerConnectivity,
    ServerError,
    ServerSyncClient,
    ServerSyncSettings,
    ServerUnreachable,
    Unauthorized,
)


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


def _make_connectivity() -> tuple[ServerConnectivity, list[datetime]]:
    """构造 connectivity 与一个可推进的 mock clock。"""

    state = {"now": datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)}
    history: list[datetime] = []

    def clock() -> datetime:
        history.append(state["now"])
        return state["now"]

    connectivity = ServerConnectivity(reachable_ttl_seconds=300.0, clock=clock)
    connectivity._test_state = state  # type: ignore[attr-defined]
    return connectivity, history


# --------------------------------------------------------------------------- #
# refresh_active_list                                                          #
# --------------------------------------------------------------------------- #


class RefreshActiveListTestCase(unittest.TestCase):
    def test_returns_parsed_items_and_marks_reachable(self) -> None:
        captured: dict[str, str] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["path"] = request.url.path
            captured["authorization"] = request.headers.get("authorization", "")
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "accounts": [
                        {
                            "account_id": 17,
                            "student_id": "20231121130",
                            "display_name": "张三",
                            "pool_status": "active",
                            "updated_at": "2026-04-26T08:25:11Z",
                        },
                        {
                            "account_id": 19,
                            "student_id": "20231121200",
                            "display_name": "",
                            "pool_status": "active",
                            "updated_at": "2026-04-26T08:25:11Z",
                        },
                    ],
                },
            )

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            result = repo.refresh_active_list()

        self.assertEqual(captured["path"], "/api/v1/active-accounts")
        self.assertEqual(captured["authorization"], "Bearer tok-abc")
        self.assertEqual(len(result), 2)
        self.assertIsInstance(result[0], ActiveAccountListItem)
        self.assertEqual(result[0].account_id, 17)
        self.assertEqual(result[0].student_id, "20231121130")
        self.assertEqual(result[0].display_name, "张三")
        self.assertEqual(result[0].pool_status, "active")
        self.assertEqual(
            result[0].updated_at,
            datetime(2026, 4, 26, 8, 25, 11, tzinfo=timezone.utc),
        )
        self.assertTrue(connectivity.is_reachable())
        self.assertEqual(
            connectivity.last_known_server_time(),
            datetime(2026, 4, 26, 8, 30, 0, tzinfo=timezone.utc),
        )

    def test_updates_cache_for_subsequent_reads(self) -> None:
        responses: list[Any] = [
            {
                "server_time": "2026-04-26T08:30:00Z",
                "accounts": [
                    {
                        "account_id": 1,
                        "student_id": "S1",
                        "display_name": "",
                        "pool_status": "active",
                        "updated_at": "2026-04-26T08:25:11Z",
                    }
                ],
            },
            {
                "server_time": "2026-04-26T08:35:00Z",
                "accounts": [
                    {
                        "account_id": 2,
                        "student_id": "S2",
                        "display_name": "",
                        "pool_status": "active",
                        "updated_at": "2026-04-26T08:25:11Z",
                    }
                ],
            },
        ]

        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(200, json_body=responses.pop(0))

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            self.assertEqual(repo.cached_active_list(), [])

            first = repo.refresh_active_list()
            self.assertEqual([item.account_id for item in first], [1])
            self.assertEqual(
                [item.account_id for item in repo.cached_active_list()],
                [1],
            )

            second = repo.refresh_active_list()
            self.assertEqual([item.account_id for item in second], [2])
            self.assertEqual(
                [item.account_id for item in repo.cached_active_list()],
                [2],
            )

    def test_cached_list_returns_copy(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "accounts": [
                        {
                            "account_id": 1,
                            "student_id": "S1",
                            "display_name": "",
                            "pool_status": "active",
                            "updated_at": "2026-04-26T08:25:11Z",
                        }
                    ],
                },
            )

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            repo.refresh_active_list()
            cached = repo.cached_active_list()
            cached.clear()
            # 修改返回的列表不影响内部缓存。
            self.assertEqual(len(repo.cached_active_list()), 1)

    def test_network_error_marks_unreachable_and_keeps_cache(self) -> None:
        # 先成功一次填充缓存，再让下一次失败。
        call_index = {"value": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            call_index["value"] += 1
            if call_index["value"] == 1:
                return _build_response(
                    200,
                    json_body={
                        "server_time": "2026-04-26T08:30:00Z",
                        "accounts": [
                            {
                                "account_id": 1,
                                "student_id": "S1",
                                "display_name": "",
                                "pool_status": "active",
                                "updated_at": "2026-04-26T08:25:11Z",
                            }
                        ],
                    },
                )
            raise httpx.ConnectError("Connection refused", request=request)

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            repo.refresh_active_list()
            self.assertTrue(connectivity.is_reachable())

            with self.assertRaises(NetworkError):
                repo.refresh_active_list()

        self.assertFalse(connectivity.is_reachable())
        # 设计约定：失败时不清空缓存（缓存只在「成功的那一次」整体替换），
        # 但调用方需要靠 connectivity.is_reachable() 判定是否使用。
        self.assertEqual(len(repo.cached_active_list()), 1)

    def test_5xx_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(503, json_body={"reason": "internal_error"})

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(ServerError):
                repo.refresh_active_list()

        self.assertFalse(connectivity.is_reachable())

    def test_401_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(401, json_body={"reason": "unauthorized"})

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(Unauthorized):
                repo.refresh_active_list()

        self.assertFalse(connectivity.is_reachable())

    def test_426_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(426, json_body={"reason": "https_required"})

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(HttpsRequired):
                repo.refresh_active_list()

        self.assertFalse(connectivity.is_reachable())

    def test_429_does_not_mark_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                429,
                json_body={"reason": "rate_limited", "retry_after": 30},
                headers={"retry-after": "30"},
            )

        connectivity, _ = _make_connectivity()
        # 限频不视作不可达 —— 但要避免 connectivity 还是 False（无成功响应过）
        # 所以先 mark 一次成功，再断言限频不会把它推回 False。
        connectivity.mark_reachable(server_time=datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc))
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(RateLimited):
                repo.refresh_active_list()

        self.assertTrue(connectivity.is_reachable())


# --------------------------------------------------------------------------- #
# get_active_account_detail                                                    #
# --------------------------------------------------------------------------- #


class GetActiveAccountDetailTestCase(unittest.TestCase):
    def test_returns_detail_with_password_and_tasks(self) -> None:
        captured: dict[str, str] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["path"] = request.url.path
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "account": {
                        "account_id": 17,
                        "student_id": "20231121130",
                        "display_name": "张三",
                        "password": "Pa$$w0rd",
                        "revision": 42,
                    },
                    "automation_tasks": [
                        {
                            "task_id": 901,
                            "room_name": "三层东区",
                            "seat_number": "A12",
                            "mode": "preferred",
                            "custom_windows": [
                                {
                                    "date": "2026-04-27",
                                    "start_hour": 8,
                                    "end_hour": 12,
                                }
                            ],
                            "enabled": True,
                            "revision": 7,
                            "updated_at": "2026-04-26T07:00:00Z",
                        }
                    ],
                },
            )

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            detail = repo.get_active_account_detail(17)

        self.assertEqual(captured["path"], "/api/v1/active-accounts/17/detail")
        self.assertIsNotNone(detail)
        assert detail is not None  # for mypy / pylance
        self.assertIsInstance(detail, ActiveAccountDetail)
        self.assertEqual(detail.account_id, 17)
        self.assertEqual(detail.student_id, "20231121130")
        self.assertEqual(detail.password, "Pa$$w0rd")
        self.assertEqual(detail.revision, 42)
        self.assertEqual(len(detail.automation_tasks), 1)
        task = detail.automation_tasks[0]
        self.assertEqual(task.task_id, 901)
        self.assertEqual(task.room_name, "三层东区")
        self.assertEqual(task.mode, "preferred")
        self.assertTrue(task.enabled)
        self.assertEqual(len(task.custom_windows), 1)
        self.assertEqual(task.custom_windows[0].date, "2026-04-27")
        self.assertEqual(task.custom_windows[0].start_hour, 8)
        self.assertEqual(task.custom_windows[0].end_hour, 12)
        self.assertEqual(
            detail.server_time,
            datetime(2026, 4, 26, 8, 30, 0, tzinfo=timezone.utc),
        )
        self.assertTrue(connectivity.is_reachable())

    def test_404_returns_none_and_keeps_reachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(404, json_body={"detail": "account not found"})

        connectivity, _ = _make_connectivity()
        # 先标记可达，断言 404 不会导致 connectivity 回退。
        connectivity.mark_reachable(
            server_time=datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)
        )
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            result = repo.get_active_account_detail(999)

        self.assertIsNone(result)
        self.assertTrue(connectivity.is_reachable())

    def test_422_raises_protocol_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                422,
                json_body={"reason": "validation_error", "errors": []},
            )

        connectivity, _ = _make_connectivity()
        connectivity.mark_reachable(
            server_time=datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)
        )
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(ProtocolError) as ctx:
                repo.get_active_account_detail(17)

        self.assertEqual(ctx.exception.status_code, 422)
        # 422 是协议层错误，不应改变 connectivity 状态。
        self.assertTrue(connectivity.is_reachable())

    def test_400_raises_protocol_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                400,
                json_body={"reason": "validation_error"},
            )

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(ProtocolError) as ctx:
                repo.get_active_account_detail(17)

        self.assertEqual(ctx.exception.status_code, 400)

    def test_network_error_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("Connection refused", request=request)

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(NetworkError):
                repo.get_active_account_detail(17)

        self.assertFalse(connectivity.is_reachable())

    def test_5xx_marks_unreachable(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(500, json_body={"reason": "internal_error"})

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(ServerError):
                repo.get_active_account_detail(17)

        self.assertFalse(connectivity.is_reachable())

    def test_invalid_account_id_rejected(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(200, json_body={})

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(ValueError):
                repo.get_active_account_detail(0)
            with self.assertRaises(ValueError):
                repo.get_active_account_detail(-1)
            with self.assertRaises(ValueError):
                repo.get_active_account_detail("17")  # type: ignore[arg-type]


# --------------------------------------------------------------------------- #
# Detail not persisted                                                         #
# --------------------------------------------------------------------------- #


class DetailNotPersistedTestCase(unittest.TestCase):
    """详情（含密码）必须不被仓库持久化（Requirement 12.2 / 7.3）。"""

    def test_detail_not_cached_internally(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "account": {
                        "account_id": 17,
                        "student_id": "20231121130",
                        "display_name": "",
                        "password": "Pa$$w0rd",
                        "revision": 1,
                    },
                    "automation_tasks": [],
                },
            )

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            detail = repo.get_active_account_detail(17)
            assert detail is not None

            # 仓库内部状态不应留下密码。
            for field_value in vars(repo).values():
                if isinstance(field_value, str):
                    self.assertNotIn("Pa$$w0rd", field_value)
                elif isinstance(field_value, list):
                    for item in field_value:
                        self.assertNotIsInstance(item, ActiveAccountDetail)

    def test_wipe_password_clears_plaintext(self) -> None:
        detail = ActiveAccountDetail(
            account_id=17,
            student_id="S1",
            display_name="",
            password="Pa$$w0rd",
            revision=1,
            automation_tasks=[],
        )
        self.assertEqual(detail.password, "Pa$$w0rd")
        detail.wipe_password()
        self.assertEqual(detail.password, "")


# --------------------------------------------------------------------------- #
# Defensive parsing                                                            #
# --------------------------------------------------------------------------- #


class DefensiveParsingTestCase(unittest.TestCase):
    def test_skip_invalid_list_items(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                200,
                json_body={
                    "server_time": "2026-04-26T08:30:00Z",
                    "accounts": [
                        {
                            "account_id": 1,
                            "student_id": "S1",
                            "display_name": "",
                            "pool_status": "active",
                            "updated_at": "2026-04-26T08:25:11Z",
                        },
                        # 缺 student_id：被跳过。
                        {"account_id": 2, "pool_status": "active"},
                        # 不是对象：被跳过。
                        "garbage",
                    ],
                },
            )

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            items = repo.refresh_active_list()

        self.assertEqual([item.account_id for item in items], [1])

    def test_missing_accounts_field_returns_empty(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                200,
                json_body={"server_time": "2026-04-26T08:30:00Z"},
            )

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            items = repo.refresh_active_list()

        self.assertEqual(items, [])

    def test_detail_without_account_field_raises(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return _build_response(
                200,
                json_body={"server_time": "2026-04-26T08:30:00Z"},
            )

        connectivity, _ = _make_connectivity()
        with _build_client(handler) as client:
            repo = ActivePoolRepository(client, connectivity)
            with self.assertRaises(Exception):
                repo.get_active_account_detail(17)


if __name__ == "__main__":  # pragma: no cover - manual runner
    unittest.main()
