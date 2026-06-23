"""Automation_Task_Sync_API 路由（spec ``account-pool-tri-sync`` task 8.3）。

本模块对接服务层 :class:`prevent_auto.services.automation_task_service.AutomationTaskService`，
落地三个 REST 端点：

* ``GET  /api/v1/active-accounts/{account_id}/automation-tasks``：下行获取。默认
  ``include_deleted=false`` 过滤软删；账号不在 Active_Pool 一律 ``404``（与接口 B
  字节级一致，由现有 :class:`AccountNotInActivePool` 异常处理器统一返回）。
* ``PUT  /api/v1/active-accounts/{account_id}/automation-tasks/{task_id}``：上行
  upsert。请求体走 :class:`AutomationTaskUpsertModel`（Pydantic v2）做字段非空与
  类型边界校验；业务校验由 service 层完成。``revision`` 冲突翻译成
  ``409 {"reason":"revision_conflict","server_revision":...,"server_payload":{...}}``
  （由 :func:`prevent_auto.web.exception_handlers.handle_revision_conflict` 处理）。
* ``DELETE /api/v1/active-accounts/{account_id}/automation-tasks/{task_id}``：上行
  软删，``revision`` 通过 query parameter ``?revision=N`` 传入。

字段校验与审计：

* ``AutomationTaskUpsertModel`` 仅做结构性校验（类型、长度、字面量、整数范围），
  service 层 :class:`AutomationTaskValidationError` 与 Pydantic 自身的
  ``ValidationError`` 都被翻译成
  ``400 {"reason":"validation_error","errors":[{"field":...,"message":...}]}``。
* ``client_kind`` 从 :class:`ClientApiToken` 上取（已经是 :class:`ClientKind`），
  连同 ``operator = token.label`` 一起进入 ``pool_audit_log``，对应 task 8.3
  「把 client_kind 取值（window / android）写入审计」的要求。

服务装配：

* :func:`register` 负责把路由挂上 FastAPI 应用，并在 ``app.state`` 上挂载
  :class:`AutomationTaskService` 实例。优先复用 ``app.state.automation_task_service``
  注入项；缺失时以 ``app.state.settings.database_path`` 为根，按既有 repository 构造
  默认实例（默认时钟 ``datetime.now(tz=UTC)``）。同时注册局部
  :class:`AutomationTaskValidationError` 异常处理器，避免依赖
  :func:`register_account_pool_exception_handlers` 二次落地。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, Depends, FastAPI, HTTPException, Path as PathParam, Query, Request, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.requests import Request as StarletteRequest

from prevent_auto.account_pool.models import (
    AutomationTask,
    ClientKind,
    CustomWindow,
)
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.booking_snapshots import BookingSnapshotsRepository
from prevent_auto.repositories.client_api_tokens import ClientApiToken
from prevent_auto.repositories.pool_audit_log import PoolAuditLogRepository
from prevent_auto.services.automation_task_service import (
    AutomationTaskService,
    AutomationTaskUpsertPayload,
    AutomationTaskValidationError,
)
from prevent_auto.web.middleware.auth import require_bearer_token


#: ``app.state`` 上挂载 :class:`AutomationTaskService` 用的属性名。允许调用方
#: 在 :func:`register` 之前自行注入，便于测试与 8.2 共享同一实例。
AUTOMATION_TASK_SERVICE_STATE_ATTR = "automation_task_service"

#: 路由公共前缀；与 design「Automation_Task_Sync_API」严格一致。
ROUTER_PREFIX = "/api/v1/active-accounts"


# ----------------------------- 请求体模型 -----------------------------


class CustomWindowModel(BaseModel):
    """``custom_windows[]`` 单条的 Pydantic 模型。

    仅做结构性校验，业务校验（``end_hour > start_hour`` 等）由 service 层完成；
    保留 :class:`int` 边界 ``[0, 23]`` 让 Pydantic 在数据格式错误时立刻拒绝，
    避免无效请求穿透到事务里再被回滚。
    """

    model_config = ConfigDict(extra="forbid")

    date: str = Field(..., min_length=1, max_length=32)
    start_hour: int = Field(..., ge=0, le=23)
    end_hour: int = Field(..., ge=0, le=23)


class AutomationTaskUpsertModel(BaseModel):
    """``PUT .../automation-tasks/{task_id}`` 请求体的 Pydantic 模型。

    与 design「Automation_Task_Sync_API · 上行 PUT」规定的请求体严格一致：

    * ``room_name`` / ``seat_number``：非空字符串，长度上限 64 / 32。
    * ``mode``：``preferred`` / ``manual`` / ``random`` 字面量。
    * ``custom_windows``：自定义生效时段列表；元素结构由 :class:`CustomWindowModel`
      校验。
    * ``enabled``：严格 ``bool``。
    * ``revision``：客户端持有的服务端版本号。``0`` 表示新建路径；其他取值由 service
      层与数据库行真实 ``revision`` 对比。

    禁止 ``extra`` 字段，避免客户端无意泄漏密码 / token 字段穿透到审计。
    """

    model_config = ConfigDict(extra="forbid", strict=True)

    room_name: str = Field(..., min_length=1, max_length=64)
    seat_number: str = Field(..., min_length=1, max_length=32)
    mode: Literal["preferred", "manual", "random"]
    custom_windows: list[CustomWindowModel] = Field(default_factory=list)
    enabled: bool
    revision: int = Field(..., ge=0)


# ----------------------------- 工具函数 -----------------------------


def _now_utc_iso() -> str:
    """生成与 design 一致的 UTC ISO8601 文本（``Z`` 结尾）。"""

    return datetime.now(tz=UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _format_utc(value: datetime) -> str:
    """把 datetime 序列化为 ``...Z`` UTC ISO8601 文本。"""

    if value.tzinfo is None:
        # service / repository 都强制 UTC aware，这里只是兜底防御
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _custom_window_to_dict(window: CustomWindow) -> dict[str, Any]:
    return {
        "date": window.date,
        "start_hour": window.start_hour,
        "end_hour": window.end_hour,
    }


def _task_to_dict(task: AutomationTask) -> dict[str, Any]:
    """把 :class:`AutomationTask` 序列化为 REST 响应载荷。

    与 design「接口 B · automation_tasks」字段保持一致；额外携带 ``deleted_at``
    便于 DELETE 响应回带软删时刻。``deleted_at`` 为 ``None`` 时直接落 ``null``。
    """

    return {
        "task_id": task.task_id,
        "account_id": task.account_id,
        "room_name": task.room_name,
        "seat_number": task.seat_number,
        "mode": task.mode,
        "custom_windows": [_custom_window_to_dict(w) for w in task.custom_windows],
        "enabled": task.enabled,
        "revision": task.revision,
        "updated_at": _format_utc(task.updated_at),
        "deleted_at": (
            _format_utc(task.deleted_at) if task.deleted_at is not None else None
        ),
    }


def _payload_from_model(model: AutomationTaskUpsertModel) -> AutomationTaskUpsertPayload:
    """把 Pydantic 模型转成 service 层 dataclass。"""

    return AutomationTaskUpsertPayload(
        room_name=model.room_name,
        seat_number=model.seat_number,
        mode=model.mode,
        custom_windows=tuple(
            CustomWindow(
                date=window.date,
                start_hour=window.start_hour,
                end_hour=window.end_hour,
            )
            for window in model.custom_windows
        ),
        enabled=model.enabled,
    )


def _validation_errors_from_pydantic(exc: ValidationError) -> list[dict[str, str]]:
    """把 Pydantic 错误列表归一化为 ``[{field, message}]`` 形态。"""

    items: list[dict[str, str]] = []
    for error in exc.errors():
        loc = error.get("loc", ())
        # 跳过 "body" 这一层（FastAPI 在路由级注入），让客户端看到真实字段路径
        normalized_loc = [
            str(part) for part in loc if not (isinstance(part, str) and part == "body")
        ]
        field_path = ".".join(normalized_loc) if normalized_loc else "body"
        items.append({"field": field_path, "message": str(error.get("msg", "invalid"))})
    return items


def _validation_errors_from_service(
    exc: AutomationTaskValidationError,
) -> list[dict[str, str]]:
    """把 service 层 :class:`FieldError` 转成 REST 响应里的错误数组。"""

    return [{"field": err.field, "message": err.message} for err in exc.errors]


def _raise_validation_error(errors: list[dict[str, str]]) -> None:
    """统一抛出 ``400 {"reason":"validation_error","errors":...}``。

    通过 :class:`HTTPException` + 既有 :func:`handle_http_exception_for_account_pool`
    完成序列化，避免再额外注册一份 handler。
    """

    raise HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail={"reason": "validation_error", "errors": errors},
    )


# ----------------------------- 服务依赖 -----------------------------


def _get_automation_task_service(request: Request) -> AutomationTaskService:
    """从 ``app.state`` 取已装配好的 :class:`AutomationTaskService` 实例。"""

    service = getattr(request.app.state, AUTOMATION_TASK_SERVICE_STATE_ATTR, None)
    if not isinstance(service, AutomationTaskService):  # pragma: no cover - 启动期失误
        raise RuntimeError(
            "未注册 automation_task_service：请在 create_app 中调用 "
            "prevent_auto.web.api.automation_task.register",
        )
    return service


# ----------------------------- 路由 -----------------------------


router = APIRouter(prefix=ROUTER_PREFIX, tags=["automation-task-sync"])


@router.get(
    "/{account_id}/automation-tasks",
    name="list_automation_tasks",
)
def list_automation_tasks(
    request: Request,
    account_id: int = PathParam(..., ge=1, description="目标账号 ID（路径参数）"),
    token: ClientApiToken = Depends(require_bearer_token),
    service: AutomationTaskService = Depends(_get_automation_task_service),
) -> JSONResponse:
    """下行：列出活跃账号的非软删 Automation_Task。

    与 design「Automation_Task_Sync_API · 下行」一致；账号不在 Active_Pool 由
    service 层抛 :class:`AccountNotInActivePool`，全局异常处理器统一翻译成
    ``404 {"detail":"account not found"}``，与接口 B 字节级一致。
    """

    tasks = service.list_for_active_account(account_id)
    payload = {
        "server_time": _now_utc_iso(),
        "automation_tasks": [_task_to_dict(task) for task in tasks],
    }
    return JSONResponse(content=payload, status_code=status.HTTP_200_OK)


@router.put(
    "/{account_id}/automation-tasks/{task_id}",
    name="upsert_automation_task",
)
async def upsert_automation_task(
    request: Request,
    account_id: int = PathParam(..., ge=1),
    task_id: int = PathParam(..., ge=1),
    token: ClientApiToken = Depends(require_bearer_token),
    service: AutomationTaskService = Depends(_get_automation_task_service),
) -> JSONResponse:
    """上行 upsert：以 ``revision`` 做乐观并发的 PUT。

    我们不依赖 FastAPI 自带的请求体解析，统一手动 ``model_validate``，方便把
    Pydantic 错误映射到设计约定的 ``400 {"reason":"validation_error","errors":...}``
    （Requirement 8.4）。
    """

    raw_body = await request.body()
    if not raw_body:
        _raise_validation_error([{"field": "body", "message": "must be provided"}])
    try:
        data = json.loads(raw_body)
    except json.JSONDecodeError:
        _raise_validation_error([{"field": "body", "message": "must be valid json"}])
    if not isinstance(data, dict):
        _raise_validation_error([{"field": "body", "message": "must be json object"}])

    try:
        model = AutomationTaskUpsertModel.model_validate(data)
    except ValidationError as exc:
        _raise_validation_error(_validation_errors_from_pydantic(exc))

    payload = _payload_from_model(model)
    expected_revision = model.revision
    task = service.upsert(
        account_id,
        task_id,
        payload,
        expected_revision=expected_revision,
        operator=token.label,
        client_kind=token.client_kind,
    )
    return JSONResponse(
        content={
            "server_time": _now_utc_iso(),
            "task": _task_to_dict(task),
        },
        status_code=status.HTTP_200_OK,
    )


@router.delete(
    "/{account_id}/automation-tasks/{task_id}",
    name="delete_automation_task",
)
def delete_automation_task(
    request: Request,
    account_id: int = PathParam(..., ge=1),
    task_id: int = PathParam(..., ge=1),
    revision: int = Query(..., ge=0, description="客户端持有的 revision，乐观并发用"),
    token: ClientApiToken = Depends(require_bearer_token),
    service: AutomationTaskService = Depends(_get_automation_task_service),
) -> JSONResponse:
    """上行 DELETE：以 ``revision`` 做乐观并发的软删。

    与 design「Automation_Task_Sync_API · 上行删除」一致；冲突 / 不在活跃池的错误
    路径全部走全局异常处理器。响应回带 ``deleted_at`` 与新的 ``revision``，便于客户端
    更新本地缓存。
    """

    task = service.soft_delete(
        account_id,
        task_id,
        expected_revision=revision,
        operator=token.label,
        client_kind=token.client_kind,
    )
    return JSONResponse(
        content={
            "server_time": _now_utc_iso(),
            "task": _task_to_dict(task),
        },
        status_code=status.HTTP_200_OK,
    )


# ----------------------------- 异常处理器 -----------------------------


async def handle_automation_task_validation_error(
    request: StarletteRequest, exc: AutomationTaskValidationError
) -> JSONResponse:
    """把 service 层 :class:`AutomationTaskValidationError` 翻译成 ``400``。

    与 design「错误响应统一约定」一致：返回
    ``{"reason":"validation_error","errors":[{"field":...,"message":...}]}``。
    """

    return JSONResponse(
        status_code=status.HTTP_400_BAD_REQUEST,
        content={
            "reason": "validation_error",
            "errors": _validation_errors_from_service(exc),
        },
    )


# ----------------------------- 注册入口 -----------------------------


@dataclass(frozen=True)
class _ServiceFactoryDeps:
    database_path: Path


def _build_default_service(app: FastAPI) -> AutomationTaskService:
    """从 ``app.state.settings`` 构造默认 :class:`AutomationTaskService`。

    与 :class:`prevent_auto.services.account_pool_service.AccountPoolService` 一样，
    自管理 SQLite 连接的事务边界；时钟使用 ``datetime.now(tz=UTC)``。
    """

    settings = getattr(app.state, "settings", None)
    if settings is None:  # pragma: no cover - 启动期失误
        raise RuntimeError(
            "无法构造 AutomationTaskService：app.state.settings 未设置",
        )
    database_path = Path(settings.database_path)
    return AutomationTaskService(
        database_path,
        automation_tasks_repo=AutomationTasksRepository(database_path),
        account_pool_repo=AccountPoolRepository(database_path),
        audit_repo=PoolAuditLogRepository(database_path),
        booking_snapshots_repo=BookingSnapshotsRepository(database_path),
    )


def register(
    app: FastAPI,
    *,
    automation_task_service: AutomationTaskService | None = None,
) -> AutomationTaskService:
    """把路由 + 服务挂上 FastAPI 应用并返回最终生效的服务实例。

    服务装配优先级：

    1. 调用方显式传入的 ``automation_task_service``。
    2. ``app.state.automation_task_service`` 已经被其它模块（例如 8.2 共享的服务初始
       化路径）注入。
    3. 调用 :func:`_build_default_service` 按 ``app.state.settings`` 构造默认实例。

    本函数同时：

    * 注册 :class:`AutomationTaskValidationError` 异常处理器（局部，不污染其它
      service）。
    * 把路由 :data:`router` 挂到应用上。
    """

    if automation_task_service is not None:
        service = automation_task_service
    else:
        existing = getattr(app.state, AUTOMATION_TASK_SERVICE_STATE_ATTR, None)
        service = (
            existing
            if isinstance(existing, AutomationTaskService)
            else _build_default_service(app)
        )
    setattr(app.state, AUTOMATION_TASK_SERVICE_STATE_ATTR, service)
    app.add_exception_handler(
        AutomationTaskValidationError, handle_automation_task_validation_error
    )
    app.include_router(router)
    return service


__all__ = [
    "AUTOMATION_TASK_SERVICE_STATE_ATTR",
    "AutomationTaskUpsertModel",
    "CustomWindowModel",
    "ROUTER_PREFIX",
    "handle_automation_task_validation_error",
    "register",
    "router",
]
