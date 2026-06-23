"""Bearer Token 鉴权依赖。

对应 spec ``account-pool-tri-sync`` 的 task 8.1：

* 解析请求头 ``Authorization: Bearer <token>``。
* 用 ``sha256(token + pepper)`` 计算 hash，按 ``client_api_tokens`` 表查询；
  命中且未撤销才放行。
* 任何未通过的请求统一返回 ``401 {"reason":"unauthorized"}``，绝不在响应或
  日志里回显 token 原文。

实现策略：以 FastAPI **依赖**（``Depends(require_bearer_token)``）的形式落地，
而不是全局 ``BaseHTTPMiddleware``。原因：

1. 现有 Web UI 复用同一 FastAPI 实例，cookie 鉴权与 Bearer Token 鉴权并存，
   全局中间件会让 ``/login`` / 静态资源等路径被错误拒绝；
2. 路由级依赖的作用域更精确，便于 ``/api/v1/...`` 路由组单独打补丁；
3. 每条受保护路由的鉴权要求在路由本身可见，可读性更好。

调用方应通过 :func:`install_auth_dependency_state` 把 ``ClientApiTokensRepository``
实例注入到 ``app.state.account_pool_auth``，然后在路由声明里使用
``token = Depends(require_bearer_token)`` 取用解析后的 ``ClientApiToken``。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fastapi import HTTPException, Request, status

from prevent_auto.repositories.client_api_tokens import (
    ClientApiToken,
    ClientApiTokensRepository,
)


#: ``app.state`` 上挂载鉴权状态用的属性名。
AUTH_DEPENDENCY_STATE_ATTR = "account_pool_auth"

#: ``request.state`` 上回写已解析的 token 记录，便于下游限频依赖复用。
RESOLVED_TOKEN_REQUEST_ATTR = "account_pool_token"

_BEARER_PATTERN = re.compile(r"^Bearer\s+(?P<token>[^\s]+)\s*$")


@dataclass(frozen=True)
class AuthDependencyState:
    """挂在 ``app.state`` 上的鉴权状态。

    只持有 :class:`ClientApiTokensRepository`，不把 ``settings`` 整个塞进来，
    避免依赖耦合。``token_pepper`` 由 repository 在构造时持有。
    """

    tokens_repository: ClientApiTokensRepository


def install_auth_dependency_state(
    app, *, tokens_repository: ClientApiTokensRepository
) -> AuthDependencyState:
    """把鉴权状态挂到 ``app.state`` 上，供 :func:`require_bearer_token` 取用。

    ``app`` 是 FastAPI 应用实例；``tokens_repository`` 必须由调用方按
    ``settings.account_pool_token_pepper`` 构造好。返回挂载结果，便于测试断言。
    """

    state = AuthDependencyState(tokens_repository=tokens_repository)
    setattr(app.state, AUTH_DEPENDENCY_STATE_ATTR, state)
    return state


def _extract_bearer_token(authorization_header: str | None) -> str | None:
    """解析 ``Authorization: Bearer <token>`` 头部。

    多余的空白被包容（前后空格 / 大小写已由 FastAPI 规整），但格式不正确（缺少
    ``Bearer`` 前缀、token 段含空白等）一律返回 ``None``。
    """

    if authorization_header is None:
        return None
    text = authorization_header.strip()
    if not text:
        return None
    match = _BEARER_PATTERN.match(text)
    if match is None:
        return None
    token = match.group("token")
    if not token:
        return None
    return token


def require_bearer_token(request: Request) -> ClientApiToken:
    """FastAPI 依赖：解析并校验 Bearer Token，返回 :class:`ClientApiToken`。

    校验失败统一抛 ``HTTPException(401, {"reason":"unauthorized"})``，由后续异常
    处理器序列化为 ``{"reason":"unauthorized"}``；这里不直接返回 ``Response``，
    便于 FastAPI 的依赖体系做组合。

    成功时把解析后的记录回写到 ``request.state.account_pool_token``，
    :func:`require_detail_rate_limit` 等下游依赖可直接读取，不必重新查 DB。
    """

    state = getattr(request.app.state, AUTH_DEPENDENCY_STATE_ATTR, None)
    if state is None:  # pragma: no cover - 启动期失误才会触发
        raise RuntimeError(
            "未注册 account_pool_auth 状态：请在 create_app 中调用 "
            "install_auth_dependency_state",
        )
    token = _extract_bearer_token(request.headers.get("Authorization"))
    if token is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"reason": "unauthorized"},
        )
    record = state.tokens_repository.find_by_token(token)
    if record is None or record.revoked_at is not None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"reason": "unauthorized"},
        )
    setattr(request.state, RESOLVED_TOKEN_REQUEST_ATTR, record)
    return record


__all__ = [
    "AUTH_DEPENDENCY_STATE_ATTR",
    "AuthDependencyState",
    "RESOLVED_TOKEN_REQUEST_ATTR",
    "install_auth_dependency_state",
    "require_bearer_token",
]
