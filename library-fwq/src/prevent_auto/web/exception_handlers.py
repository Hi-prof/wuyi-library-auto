"""account-pool-tri-sync 异常处理器。

把 :mod:`prevent_auto.account_pool.models` 中定义的业务异常翻译成 design
「Error Handling」表中的 HTTP 响应：

* :class:`AccountNotInActivePool` → ``404 {"detail":"account not found"}``，**字节级
  一致**（Requirement 6.5），用 :class:`Response` 直接写原始字节，不让 FastAPI 重新
  序列化导致键序变动。
* :class:`RevisionConflict` → ``409 {"reason":"revision_conflict","server_revision":N,
  "server_payload":{...}}``。``server_payload`` 在序列化前先经 ``scrub`` 过滤，
  避免 password/token 字段反穿到客户端。
* :class:`PoolCapacityExceeded` → ``422 {"reason":"pool_full"}``。
* :class:`IllegalPoolTransition` → ``422 {"reason":"illegal_transition"}``。
* :class:`MissingLoginCredentials` → ``422 {"reason":"missing_login_credentials"}``。
* :class:`DuplicateStudentId` → ``422 {"reason":"duplicate_student_id"}``。
* :class:`IdleEmpty` → ``422 {"reason":"idle_empty"}``。
* 其它 :class:`AccountPoolError` 落入 ``500 {"reason":"internal_error"}``，避免泄露
  内部异常文本。

同时定义一个 ``HTTPException`` fallback：把 ``HTTPException(status_code, detail=str)``
统一转成 ``{"reason": detail}``（当 ``detail`` 已经是 dict 时直接透传），并在序列化
之前 ``scrub`` 一次。其它路径的 ``HTTPException`` 维持 FastAPI 默认行为。

调用方通过 :func:`register_account_pool_exception_handlers` 在 ``create_app`` 阶段
统一注册。
"""

from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, Response
from starlette.requests import Request

from prevent_auto.account_pool.models import (
    AccountNotInActivePool,
    AccountPoolError,
    DuplicateStudentId,
    IdleEmpty,
    IllegalPoolTransition,
    MissingLoginCredentials,
    PoolCapacityExceeded,
    RevisionConflict,
)
from prevent_auto.logging import scrub


_LOGGER = logging.getLogger(__name__)


#: ``account_not_found`` 的字节级响应；不要修改键序、空白字符或键名。
ACCOUNT_NOT_FOUND_BODY = b'{"detail":"account not found"}'


def _account_not_found_response() -> Response:
    """返回与 design 约定字节级一致的 404 响应。

    使用 :class:`Response`（而非 :class:`JSONResponse`）避免 FastAPI 默认的
    ``json.dumps`` 二次序列化把空白填回去。``media_type`` 显式声明为
    ``application/json``，避免下游代理把它当成 ``text/html``。
    """

    return Response(
        content=ACCOUNT_NOT_FOUND_BODY,
        status_code=404,
        media_type="application/json",
    )


def _scrubbed_json_response(*, status_code: int, payload: dict[str, Any]) -> JSONResponse:
    """把 payload 经 ``scrub`` 处理后序列化。

    ``scrub`` 仅按字段名识别敏感项，遇到 password / token / cookie 等键直接替换。
    返回 :class:`JSONResponse` 让 FastAPI 自动设置 ``application/json`` 头。
    """

    sanitized = scrub(payload)
    return JSONResponse(content=sanitized, status_code=status_code)


def _log_exception_minimally(request: Request, exc: BaseException, *, status_code: int) -> None:
    """在不泄露密码 / token 的前提下记录异常上下文。

    只输出请求路径、HTTP 方法、异常类型与消息片段（截短至 120 字符）。``scrub``
    覆盖不到自由文本异常消息，这里以截断 + 字段化日志的方式做最小披露。
    """

    message = str(exc)
    if len(message) > 120:
        message = message[:117] + "..."
    _LOGGER.warning(
        "account_pool_exception",
        extra={
            "path": request.url.path,
            "method": request.method,
            "exc_type": type(exc).__name__,
            "exc_message": message,
            "status_code": status_code,
        },
    )


# --------------------------- 业务异常处理器 ---------------------------


async def handle_account_not_in_active_pool(
    request: Request, exc: AccountNotInActivePool
) -> Response:
    _log_exception_minimally(request, exc, status_code=404)
    return _account_not_found_response()


async def handle_revision_conflict(
    request: Request, exc: RevisionConflict
) -> JSONResponse:
    _log_exception_minimally(request, exc, status_code=409)
    payload = {
        "reason": "revision_conflict",
        "server_revision": int(exc.server_revision),
        "server_payload": exc.server_payload,
    }
    return _scrubbed_json_response(status_code=409, payload=payload)


async def handle_pool_capacity_exceeded(
    request: Request, exc: PoolCapacityExceeded
) -> JSONResponse:
    _log_exception_minimally(request, exc, status_code=422)
    return _scrubbed_json_response(
        status_code=422,
        payload={"reason": "pool_full", "detail": "号池已满"},
    )


async def handle_illegal_pool_transition(
    request: Request, exc: IllegalPoolTransition
) -> JSONResponse:
    _log_exception_minimally(request, exc, status_code=422)
    return _scrubbed_json_response(
        status_code=422,
        payload={"reason": "illegal_transition"},
    )


async def handle_missing_login_credentials(
    request: Request, exc: MissingLoginCredentials
) -> JSONResponse:
    _log_exception_minimally(request, exc, status_code=422)
    return _scrubbed_json_response(
        status_code=422,
        payload={"reason": "missing_login_credentials"},
    )


async def handle_duplicate_student_id(
    request: Request, exc: DuplicateStudentId
) -> JSONResponse:
    _log_exception_minimally(request, exc, status_code=422)
    return _scrubbed_json_response(
        status_code=422,
        payload={"reason": "duplicate_student_id"},
    )


async def handle_idle_empty(request: Request, exc: IdleEmpty) -> JSONResponse:
    _log_exception_minimally(request, exc, status_code=422)
    return _scrubbed_json_response(
        status_code=422,
        payload={"reason": "idle_empty"},
    )


async def handle_account_pool_error(
    request: Request, exc: AccountPoolError
) -> JSONResponse:
    """:class:`AccountPoolError` fallback：吞掉异常细节，只回 ``500``。

    具体子类已经被前面的 handler 拦截，走到这里说明上层抛了一个未声明子类的
    错误。我们不把异常 message 透出客户端，避免数据库错误 / 实现细节回流。
    """

    _log_exception_minimally(request, exc, status_code=500)
    return _scrubbed_json_response(
        status_code=500,
        payload={"reason": "internal_error"},
    )


# --------------------------- HTTPException 改写 ---------------------------


async def handle_http_exception_for_account_pool(
    request: Request, exc: HTTPException
) -> Response:
    """对 ``/api/v1/`` 前缀路径上的 :class:`HTTPException` 做格式归一化。

    auth / rate-limit 依赖里手动抛的 ``HTTPException`` 会带 ``detail={"reason":...}``，
    这里直接把 detail 当响应体；当 detail 是字符串时降级成 ``{"reason": detail}``。
    其它非 ``/api/v1/`` 路径走 FastAPI 默认行为，避免破坏 Web 管理页 ``401`` /
    ``403`` 的既有契约（``{"detail":"未登录"}``）。
    """

    if not request.url.path.startswith("/api/v1/"):
        # 让 FastAPI 自带 handler 处理：直接抛回去
        return JSONResponse(
            status_code=exc.status_code,
            content={"detail": exc.detail},
            headers=getattr(exc, "headers", None) or {},
        )

    detail = exc.detail
    if isinstance(detail, dict):
        payload: dict[str, Any] = detail
    elif isinstance(detail, str):
        payload = {"reason": detail}
    else:
        payload = {"reason": "http_exception"}
    headers = getattr(exc, "headers", None) or {}
    sanitized = scrub(payload)
    body = json.dumps(sanitized, ensure_ascii=False).encode("utf-8")
    return Response(
        content=body,
        status_code=exc.status_code,
        media_type="application/json",
        headers=headers,
    )


# --------------------------- 注册入口 ---------------------------


def register_account_pool_exception_handlers(app: FastAPI) -> None:
    """在 FastAPI app 上注册全部账号池业务异常处理器。

    顺序无关——FastAPI 会按异常类层级最具体的 handler 优先匹配。注册后，
    任何路由抛出对应异常都会落到设计约定的 HTTP 响应。
    """

    app.add_exception_handler(AccountNotInActivePool, handle_account_not_in_active_pool)
    app.add_exception_handler(RevisionConflict, handle_revision_conflict)
    app.add_exception_handler(PoolCapacityExceeded, handle_pool_capacity_exceeded)
    app.add_exception_handler(IllegalPoolTransition, handle_illegal_pool_transition)
    app.add_exception_handler(MissingLoginCredentials, handle_missing_login_credentials)
    app.add_exception_handler(DuplicateStudentId, handle_duplicate_student_id)
    app.add_exception_handler(IdleEmpty, handle_idle_empty)
    app.add_exception_handler(AccountPoolError, handle_account_pool_error)
    app.add_exception_handler(HTTPException, handle_http_exception_for_account_pool)


__all__ = [
    "ACCOUNT_NOT_FOUND_BODY",
    "handle_account_not_in_active_pool",
    "handle_account_pool_error",
    "handle_duplicate_student_id",
    "handle_http_exception_for_account_pool",
    "handle_idle_empty",
    "handle_illegal_pool_transition",
    "handle_missing_login_credentials",
    "handle_pool_capacity_exceeded",
    "handle_revision_conflict",
    "register_account_pool_exception_handlers",
]
