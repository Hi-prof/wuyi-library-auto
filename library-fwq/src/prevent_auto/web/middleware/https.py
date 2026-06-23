"""HTTPS 强制中间件。

对应 spec ``account-pool-tri-sync`` 的 task 8.1 与 Requirement 9.3：

* 当 ``ACCOUNT_POOL_HTTPS_REQUIRED`` 为 ``True`` 且 ``request.url.scheme != 'https'``
  且请求来源不是环回地址时，直接返回 ``426 Upgrade Required``。
* 不做 HTTP → HTTPS 的 302 重定向（避免泄露 URL 路径与账号字段）。
* 响应体里**绝不**包含账号、密码、token 字段；只放 ``{"reason":"https_required"}``。
* 仅作用于 ``/api/v1/`` 前缀（客户端同步接口）；管理端 Web 页面 (``/`` / ``/accounts``
  / ``/login``) 与静态资源不强制 HTTPS——它们走的是同一应用，但鉴权域不同（cookie
  会话 vs Bearer Token），统一拒绝会让本机管理面板也访问不通。

中间件使用 Starlette 的 :class:`BaseHTTPMiddleware` 实现，便于复用 FastAPI 的请求
流，并和后续异常处理器串在一起。
"""

from __future__ import annotations

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import JSONResponse, Response


#: design「Components and Interfaces · 鉴权与限频」要求的环回地址集合。
LOOPBACK_HOSTS: frozenset[str] = frozenset({"127.0.0.1", "::1", "localhost"})


def is_loopback_host(host: str | None) -> bool:
    """判断给定 host 是否落在环回地址白名单内。

    输入可能是 ``None`` / 空串（极少见的代理路径）。这里统一返回 ``True``，让
    本机或本地调试更宽容；公网入口部署时 ``request.client.host`` 永远会被填上
    实际 IP，不会误命中。
    """

    if not host:
        return True
    normalized = host.strip().lower()
    if not normalized:
        return True
    if normalized in LOOPBACK_HOSTS:
        return True
    # IPv6 上的 ``::ffff:127.0.0.1`` 等映射也按环回处理
    if normalized.endswith(".0.0.1") and normalized.startswith("::ffff:127"):
        return True
    return False


class HttpsRequiredMiddleware(BaseHTTPMiddleware):
    """强制客户端 API 走 HTTPS。

    ``required`` 参数对应 ``settings.account_pool_https_required``：``False`` 时
    中间件直接放行（开发 / 单元测试场景）。``protected_path_prefix`` 默认 ``/api/v1``，
    与 spec 客户端同步接口一致；调用方可以注入其它前缀以扩展生效范围。
    """

    def __init__(
        self,
        app,
        *,
        required: bool,
        protected_path_prefix: str = "/api/v1",
    ) -> None:
        super().__init__(app)
        self._required = bool(required)
        self._protected_path_prefix = protected_path_prefix

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        if not self._required:
            return await call_next(request)
        if not request.url.path.startswith(self._protected_path_prefix):
            return await call_next(request)
        if request.url.scheme == "https":
            return await call_next(request)
        client = request.client
        host = client.host if client is not None else None
        if is_loopback_host(host):
            return await call_next(request)
        return JSONResponse(
            content={"reason": "https_required"},
            status_code=426,
        )


__all__ = [
    "HttpsRequiredMiddleware",
    "LOOPBACK_HOSTS",
    "is_loopback_host",
]
