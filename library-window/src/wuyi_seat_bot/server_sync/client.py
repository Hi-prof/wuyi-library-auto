"""基于 ``httpx`` 的 Bearer Token 客户端。

服务端 ``library-fwq`` 暴露的 ``/api/v1/...`` 端点统一由本客户端发起。
关键约定（与 design.md「Error Handling」保持一致）：

- 网络层异常（连接失败、DNS 失败、超时）→ ``ServerUnreachable``。
- 5xx 响应 → ``ServerUnreachable``（视作服务端临时不可达）。
- 401 ``unauthorized`` → ``Unauthorized``（凭证问题，调用方暂停同步）。
- 426 ``https_required`` → ``HttpsRequired``（部署配置错误）。
- 429 ``rate_limited`` → ``RateLimited``（携带 ``retry_after``）。
- 其他 4xx → ``ProtocolError``，调用方按业务语义处理（404 / 409 / 422）。

所有异常都派生自 ``ServerSyncError``，便于上层统一捕获。
"""

from __future__ import annotations

import json
import logging
from contextlib import AbstractContextManager
from types import TracebackType
from typing import Any, Mapping

import httpx

from wuyi_seat_bot.server_sync.settings import ServerSyncSettings


logger = logging.getLogger(__name__)


_DEFAULT_HEADERS: dict[str, str] = {
    "Accept": "application/json",
    "User-Agent": "wuyi-seat-bot/server_sync",
}


class ServerSyncError(Exception):
    """server_sync 模块所有异常的基类。"""


class ServerUnreachable(ServerSyncError):
    """服务端不可达。

    覆盖：网络层错误（连接失败、DNS 失败、读取超时、TLS 握手失败）、
    服务端 5xx 响应。

    本异常 **仅** 在 ``client.py`` / ``active_pool_repository.py`` /
    ``automation_task_uploader.py`` / ``blacklist_reporter.py`` 内部网络层
    使用，用于向 sync 流程报告本次服务端调用失败。

    **禁止** 调度器 / UI 层 / 自动任务入口 / 登录刷新 / 座位监控捕获本
    异常用于「拒绝执行 / 跳过本轮 / 置灰本地执行入口」。

    Requirement 12.3、12.7、13.9：服务端不可达 **不** 阻塞本地执行入口；
    本异常仅作为 ``connectivity_indicator`` 三态指示的状态来源之一。
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        body: Any = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.body = body


class NetworkError(ServerUnreachable):
    """传输层错误（连接 / DNS / 超时 / TLS）。"""


class ServerError(ServerUnreachable):
    """服务端 5xx 响应。"""


class ProtocolError(ServerSyncError):
    """协议层错误：4xx 响应（不含 401/426/429）。

    body 通常含 ``reason`` 字段（如 ``account_not_found``、``revision_conflict``、
    ``validation_error``）；调用方根据 ``status_code`` 与 ``body`` 翻译为业务异常。
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int,
        body: Any = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.body = body


class Unauthorized(ProtocolError):
    """401 Unauthorized。"""

    def __init__(self, body: Any = None) -> None:
        super().__init__("Bearer Token 无效或未授权", status_code=401, body=body)


class HttpsRequired(ProtocolError):
    """426 Upgrade Required（服务端要求 HTTPS）。"""

    def __init__(self, body: Any = None) -> None:
        super().__init__("服务端要求 HTTPS 通道", status_code=426, body=body)


class RateLimited(ProtocolError):
    """429 Rate Limited。"""

    def __init__(self, body: Any = None, retry_after: float | None = None) -> None:
        super().__init__("请求被限频", status_code=429, body=body)
        self.retry_after = retry_after


class ServerSyncClient(AbstractContextManager["ServerSyncClient"]):
    """基于 ``httpx.Client`` 的 Bearer Token 同步客户端。

    本客户端是**同步**的，与现有 ``wuyi_seat_bot`` 模块（urllib / 同步调度）保持一致。

    生命周期：

    - 优先以上下文管理器使用：``with ServerSyncClient(settings) as cli: ...``。
    - 也可通过 ``cli.close()`` 显式释放底层连接池。
    """

    def __init__(
        self,
        settings: ServerSyncSettings,
        *,
        client_factory: "type[httpx.Client] | None" = None,
    ) -> None:
        self._settings = settings
        factory = client_factory or httpx.Client
        # 注：headers 由 httpx 在每次请求合并；这里只放静态字段，
        # Authorization 通过单独逻辑写入，避免将 token 拼进 repr/日志。
        self._client = factory(
            base_url=settings.server_base_url,
            timeout=httpx.Timeout(settings.request_timeout_seconds),
            verify=settings.verify_tls,
            headers=dict(_DEFAULT_HEADERS),
        )

    @property
    def settings(self) -> ServerSyncSettings:
        return self._settings

    def __enter__(self) -> "ServerSyncClient":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def close(self) -> None:
        self._client.close()

    # ------------------------------------------------------------------ #
    # HTTP 动作                                                            #
    # ------------------------------------------------------------------ #

    def get(
        self,
        path: str,
        *,
        params: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        return self.request("GET", path, params=params, headers=headers)

    def post(
        self,
        path: str,
        *,
        json_body: Any = None,
        params: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        return self.request(
            "POST", path, json_body=json_body, params=params, headers=headers
        )

    def put(
        self,
        path: str,
        *,
        json_body: Any = None,
        params: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        return self.request(
            "PUT", path, json_body=json_body, params=params, headers=headers
        )

    def delete(
        self,
        path: str,
        *,
        params: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        return self.request("DELETE", path, params=params, headers=headers)

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any = None,
        params: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        """统一的请求入口，把网络异常 / 5xx / 4xx 翻译为本模块异常。

        返回 :class:`httpx.Response`，调用方可读取 ``json()``、``headers``、
        ``content`` 等字段；对 2xx 不做强制 JSON 解析，避免把空体响应解释为错误。
        """

        merged_headers = dict(headers or {})
        merged_headers["Authorization"] = f"Bearer {self._settings.bearer_token}"
        try:
            response = self._client.request(
                method,
                path,
                params=params,
                json=json_body,
                headers=merged_headers,
            )
        except httpx.TimeoutException as exc:
            raise NetworkError(
                f"调用服务端 {method} {path} 超时"
            ) from exc
        except httpx.TransportError as exc:
            # ConnectError / ReadError / WriteError / ProtocolError / ProxyError 等。
            raise NetworkError(
                f"调用服务端 {method} {path} 网络错误：{exc}"
            ) from exc
        except httpx.HTTPError as exc:
            # 兜底：覆盖 InvalidURL、UnsupportedProtocol 等其余 httpx 异常。
            raise NetworkError(
                f"调用服务端 {method} {path} 失败：{exc}"
            ) from exc

        status = response.status_code
        if status >= 500:
            body = _safe_parse_body(response)
            raise ServerError(
                f"服务端 {method} {path} 返回 {status}",
                status_code=status,
                body=body,
            )
        if status == 401:
            raise Unauthorized(_safe_parse_body(response))
        if status == 426:
            raise HttpsRequired(_safe_parse_body(response))
        if status == 429:
            raise RateLimited(
                _safe_parse_body(response),
                retry_after=_parse_retry_after(response),
            )
        if 400 <= status < 500:
            raise ProtocolError(
                f"服务端 {method} {path} 返回 {status}",
                status_code=status,
                body=_safe_parse_body(response),
            )
        return response


def _safe_parse_body(response: httpx.Response) -> Any:
    """容错解析响应体。

    服务端约定大多数错误响应是 JSON；若解析失败则回退到原始文本，
    保证调用方拿到的 ``body`` 字段类型可读、可记录。
    """

    content_type = response.headers.get("content-type", "")
    text = response.text
    if "application/json" in content_type or text.lstrip().startswith(("{", "[")):
        try:
            return response.json()
        except json.JSONDecodeError:
            return text
    return text


def _parse_retry_after(response: httpx.Response) -> float | None:
    raw = response.headers.get("retry-after")
    if raw is None:
        return None
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None
