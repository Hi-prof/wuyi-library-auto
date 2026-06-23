"""Active_Account_Sync_API 路由（spec ``account-pool-tri-sync`` task 8.2）。

本模块对接服务层
:class:`prevent_auto.services.account_pool_service.AccountPoolService`，落地三个
REST 端点：

* ``GET  /api/v1/active-accounts``（接口 A）：返回 Active_Pool 全量清单。响应
  仅含 ``account_id`` / ``student_id`` / ``display_name`` / ``pool_status='active'`` /
  ``updated_at`` 与顶层 ``server_time``，**不包含** 密码、cookie、session token、
  ``automation_tasks``（Requirement 7.1、7.2）。
* ``GET  /api/v1/active-accounts/{account_id}/detail``（接口 B）：返回单账号
  AES-GCM 解密后的明文密码、``revision`` 与该账号绑定的全部非软删
  ``automation_tasks``。非活跃 / 不存在 / 软删一律由 service 层抛
  :class:`AccountNotInActivePool`，全局异常处理器统一翻译为字节级一致的
  ``404 {"detail":"account not found"}``（Requirement 6.5、7.6）。
* ``POST /api/v1/active-accounts``：Windows 客户端账号上行。请求体只接收账号
  基础字段，由服务层加密密码并 upsert 到 Active_Pool。
* ``POST /api/v1/active-accounts/{account_id}/blacklist-events``：客户端拉黑事件
  上报。请求体三字段：``evidence`` / ``client_kind`` / ``client_observed_at``；
  对非 Active_Pool 账号同样返回 ``404``，不泄露真实状态（Requirement 2.5、
  7.4、12.4）。

鉴权 / 限频走与本应用其它客户端 API 一致的依赖：

* 全部端点使用 :func:`prevent_auto.web.middleware.auth.require_bearer_token`
  鉴权；缺失 / 撤销 / 错误格式统一返回 ``401 {"reason":"unauthorized"}``。
* 接口 B 在鉴权之上叠加
  :func:`prevent_auto.web.middleware.rate_limit.require_detail_rate_limit`，
  按 ``(token_id, account_id)`` 滑窗 60 秒、限额由
  ``settings.account_pool_detail_rate_limit_per_minute`` 注入。

服务装配：

* :func:`register` 把路由挂上 FastAPI 应用，并保证 ``app.state`` 上挂载了一份
  :class:`AccountPoolService` 实例。优先复用 ``app.state.account_pool_service``
  / ``app.state.services.account_pool_service``；都没有时按
  ``app.state.settings`` 构造默认实例。
* 不在本模块注册 :class:`AccountNotInActivePool` 等业务异常处理器——它们由
  :func:`prevent_auto.web.exception_handlers.register_account_pool_exception_handlers`
  在 ``create_app`` 阶段集中注册，避免重复落地。
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from fastapi import (
    APIRouter,
    Body,
    Depends,
    FastAPI,
    HTTPException,
    Path as PathParam,
    Request,
    status,
)
from fastapi.responses import JSONResponse

from prevent_auto.account_pool.models import (
    ActiveAccountDetail,
    ActiveAccountListItem,
    AutomationTask,
    BulkImportRow,
    ClientKind,
    CustomWindow,
)
from prevent_auto.repositories.client_api_tokens import ClientApiToken
from prevent_auto.repositories.login_status_cache import LoginStatusCacheRepository
from prevent_auto.services.account_password_cipher import AccountPasswordCipher
from prevent_auto.services.account_pool_service import (
    AccountPoolService,
    SqliteLoginStatusCache,
)
from prevent_auto.web.middleware.auth import require_bearer_token
from prevent_auto.web.middleware.rate_limit import require_detail_rate_limit


#: ``app.state`` 上挂载 :class:`AccountPoolService` 用的属性名。允许调用方
#: 在 :func:`register` 之前自行注入，便于测试与运行时共用同一实例。
ACCOUNT_POOL_SERVICE_STATE_ATTR = "account_pool_service"

#: 路由公共前缀；与 design「Active_Account_Sync_API」严格一致。
ROUTER_PREFIX = "/api/v1/active-accounts"


# ----------------------------- 工具函数 -----------------------------


def _now_utc_iso() -> str:
    """生成与 design 一致的 UTC ISO8601 文本（``Z`` 结尾）。

    与 :mod:`prevent_auto.web.api.automation_task` 使用的格式一致；秒以下精度
    截掉，避免因微秒差异让响应体在不同 Python 解释器下出现非语义性差别。
    """

    return (
        datetime.now(tz=UTC)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def _format_utc(value: datetime) -> str:
    """把 datetime 序列化为 ``...Z`` UTC ISO8601 文本。

    与 :func:`prevent_auto.repositories.account_pool._format_utc` 同口径；
    路由层重新实现一份避免跨模块引用 repository 的私有函数。
    """

    if value.tzinfo is None:
        # service / repository 都强制 UTC aware；这里只是兜底防御
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _list_item_to_dict(item: ActiveAccountListItem) -> dict[str, Any]:
    """把 :class:`ActiveAccountListItem` 序列化为接口 A 的单条响应。

    字段集严格限定为 design「接口 A」约定的 5 列，便于上线后通过响应字段名做
    grep 反向回溯审计；不含密码 / cookie / token / automation_tasks。
    """

    return {
        "account_id": item.account_id,
        "student_id": item.student_id,
        "display_name": item.display_name,
        "pool_status": item.pool_status.value,
        "updated_at": _format_utc(item.updated_at),
    }


def _custom_window_to_dict(window: CustomWindow) -> dict[str, Any]:
    return {
        "date": window.date,
        "start_hour": window.start_hour,
        "end_hour": window.end_hour,
    }


def _automation_task_to_dict(task: AutomationTask) -> dict[str, Any]:
    """把 :class:`AutomationTask` 序列化为接口 B 内嵌的单条任务。

    字段集与 design「接口 B · automation_tasks」严格一致：``task_id`` / ``room_name`` /
    ``seat_number`` / ``mode`` / ``custom_windows`` / ``enabled`` / ``revision`` /
    ``updated_at``。``deleted_at`` / ``account_id`` / ``created_at`` 等内部字段不暴露。
    """

    return {
        "task_id": task.task_id,
        "room_name": task.room_name,
        "seat_number": task.seat_number,
        "mode": task.mode,
        "custom_windows": [_custom_window_to_dict(w) for w in task.custom_windows],
        "enabled": task.enabled,
        "revision": task.revision,
        "updated_at": _format_utc(task.updated_at),
    }


def _detail_to_dict(detail: ActiveAccountDetail) -> dict[str, Any]:
    """把 :class:`ActiveAccountDetail` 序列化为接口 B 的 ``account`` 子对象。

    严格遵循 design 字段：``account_id`` / ``student_id`` / ``display_name`` /
    ``password`` / ``revision``。``automation_tasks`` 不在此处展开，由顶层路由响应
    单独序列化以便和 design「接口 B」JSON 结构一一对应。
    """

    return {
        "account_id": detail.account_id,
        "student_id": detail.student_id,
        "display_name": detail.display_name,
        "password": detail.password,
        "revision": detail.revision,
    }


def _optional_text(value: object) -> str:
    return value.strip() if isinstance(value, str) else ""


def _parse_upload_rows(body: dict[str, Any]) -> list[BulkImportRow]:
    accounts = body.get("accounts")
    if not isinstance(accounts, list):
        _raise_validation_error(
            [{"field": "accounts", "message": "must be array"}]
        )

    rows: list[BulkImportRow] = []
    errors: list[dict[str, str]] = []
    for index, raw in enumerate(accounts, start=1):
        if not isinstance(raw, dict):
            errors.append(
                {
                    "field": f"accounts[{index - 1}]",
                    "message": "must be object",
                }
            )
            continue
        rows.append(
            BulkImportRow(
                source_row=index,
                student_id=_optional_text(raw.get("student_id")),
                password=_optional_text(raw.get("password")),
                display_name=_optional_text(raw.get("display_name")),
                login_url=_optional_text(raw.get("login_url")),
            )
        )
    if errors:
        _raise_validation_error(errors)
    return rows


def _upload_result_to_dict(result: Any) -> dict[str, Any]:
    return {
        "server_time": _now_utc_iso(),
        "total": result.total,
        "created": result.created_count,
        "updated": result.updated_count,
        "rejected": result.rejected_count,
        "items": [
            {
                "source_row": item.source_row,
                "student_id": item.student_id,
                "status": item.status.value,
                "account_id": item.account_id,
                "action": item.action,
                "reason": item.reason.value if item.reason is not None else None,
            }
            for item in result.items
        ],
    }


def _parse_client_kind_for_blacklist(value: object) -> ClientKind:
    """把请求体里 ``client_kind`` 字符串收敛到 ``window`` / ``android``。

    其它取值（``web`` / ``system`` / 未知字符串）一律按字段校验失败处理，避免
    服务端审计被伪造来源；与 design「客户端拉黑事件上报」字面量一致。
    """

    if not isinstance(value, str):
        _raise_validation_error(
            [{"field": "client_kind", "message": "must be string"}]
        )
    text = value.strip()
    if text == ClientKind.WINDOW.value:
        return ClientKind.WINDOW
    if text == ClientKind.ANDROID.value:
        return ClientKind.ANDROID
    _raise_validation_error(
        [
            {
                "field": "client_kind",
                "message": "must be 'window' or 'android'",
            }
        ]
    )
    raise AssertionError("unreachable")  # pragma: no cover


def _raise_validation_error(errors: list[dict[str, str]]) -> None:
    """统一抛出 ``400 {"reason":"validation_error","errors":...}``。

    通过 :class:`HTTPException` + 既有 :func:`handle_http_exception_for_account_pool`
    完成序列化，避免再额外注册一份 handler。与
    :mod:`prevent_auto.web.api.automation_task` 同款。
    """

    raise HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail={"reason": "validation_error", "errors": errors},
    )


# ----------------------------- 服务依赖 -----------------------------


def _get_account_pool_service(request: Request) -> AccountPoolService:
    """从 ``app.state`` 取已装配好的 :class:`AccountPoolService` 实例。"""

    service = getattr(request.app.state, ACCOUNT_POOL_SERVICE_STATE_ATTR, None)
    if not isinstance(service, AccountPoolService):  # pragma: no cover - 启动期失误
        raise RuntimeError(
            "未注册 account_pool_service：请在 create_app 中调用 "
            "prevent_auto.web.api.active_account.register",
        )
    return service


# ----------------------------- 路由 -----------------------------


router = APIRouter(prefix=ROUTER_PREFIX, tags=["active-account-sync"])


@router.get(
    "",
    name="list_active_accounts",
)
def list_active_accounts(
    request: Request,
    token: ClientApiToken = Depends(require_bearer_token),
    service: AccountPoolService = Depends(_get_account_pool_service),
) -> JSONResponse:
    """接口 A：列出 Active_Pool 全部账号的非敏感字段。

    响应顶层带 ``server_time`` 便于客户端做时钟对齐（Requirement 7.7），单条
    记录不含密码 / cookie / session token / automation_tasks（Requirement 7.2）。
    Suspended_Pool 与 Idle_Pool 中的账号永远不会出现在此清单
    （Requirement 6.3）。
    """

    items = service.list_active_for_sync()
    payload = {
        "server_time": _now_utc_iso(),
        "accounts": [_list_item_to_dict(item) for item in items],
    }
    return JSONResponse(content=payload, status_code=status.HTTP_200_OK)


@router.post(
    "",
    name="upload_active_accounts",
)
def upload_active_accounts(
    request: Request,
    body: dict[str, Any] = Body(default_factory=dict),
    token: ClientApiToken = Depends(require_bearer_token),
    service: AccountPoolService = Depends(_get_account_pool_service),
) -> JSONResponse:
    """Windows 客户端账号上行：upsert 到 Active_Pool。

    请求体形如 ``{"accounts": [{"student_id": "...", "password": "..."}]}``。
    服务端只在响应和审计中保留脱敏结果，不回显密码。
    """

    if not isinstance(body, dict):
        _raise_validation_error([{"field": "body", "message": "must be json object"}])

    rows = _parse_upload_rows(body)
    result = service.upload_to_active_pool(
        rows,
        operator=f"{token.client_kind.value}:{token.label}",
        client_kind=token.client_kind,
    )
    return JSONResponse(
        content=_upload_result_to_dict(result),
        status_code=status.HTTP_200_OK,
    )


@router.get(
    "/{account_id}/detail",
    name="get_active_account_detail",
)
def get_active_account_detail(
    request: Request,
    account_id: int = PathParam(..., ge=1, description="目标账号 ID（路径参数）"),
    token: ClientApiToken = Depends(require_bearer_token),
    _rate_limit: None = Depends(require_detail_rate_limit),
    service: AccountPoolService = Depends(_get_account_pool_service),
) -> JSONResponse:
    """接口 B：返回 Active_Pool 账号的解密密码 + 全部 Automation_Task。

    服务层在 ``pool_status='active' AND deleted_at IS NULL`` 时返回
    :class:`ActiveAccountDetail`，否则抛 :class:`AccountNotInActivePool`，由
    :func:`prevent_auto.web.exception_handlers.handle_account_not_in_active_pool`
    翻译为字节级一致的 ``404 {"detail":"account not found"}``，确保不泄露真实状态
    （Requirement 6.5、7.6、9.4）。
    """

    detail = service.get_active_detail(account_id)
    payload = {
        "server_time": _now_utc_iso(),
        "account": _detail_to_dict(detail),
        "automation_tasks": [
            _automation_task_to_dict(task) for task in detail.automation_tasks
        ],
    }
    return JSONResponse(content=payload, status_code=status.HTTP_200_OK)


@router.post(
    "/{account_id}/blacklist-events",
    name="report_blacklist_event",
)
def report_blacklist_event(
    request: Request,
    account_id: int = PathParam(..., ge=1),
    body: dict[str, Any] = Body(default_factory=dict),
    token: ClientApiToken = Depends(require_bearer_token),
    service: AccountPoolService = Depends(_get_account_pool_service),
) -> JSONResponse:
    """客户端拉黑事件上报：把账号从 Active_Pool 迁入 Suspended_Pool。

    请求体（与 design「客户端拉黑事件上报」一致）::

        {
          "evidence": "人机验证失败 5 次",
          "client_kind": "window",
          "client_observed_at": "2026-04-26T08:30:00Z"
        }

    校验顺序：

    1. ``evidence``：非空字符串。
    2. ``client_kind``：字面量 ``window`` / ``android`` 之一。
    3. ``client_observed_at``：非空字符串（仅做存在性校验，时间格式由客户端自己
       保证；服务端只把它写入审计 payload，不参与状态判定）。

    任一字段缺失 / 类型错误一律 ``400 {"reason":"validation_error","errors":[...]}``；
    账号不在 Active_Pool 时 service 抛 :class:`AccountNotInActivePool`，全局
    异常处理器返回字节级一致的 404，避免泄露真实状态（Requirement 6.5、12.4）。
    """

    if not isinstance(body, dict):
        _raise_validation_error([{"field": "body", "message": "must be json object"}])

    errors: list[dict[str, str]] = []
    evidence = body.get("evidence")
    if not isinstance(evidence, str) or not evidence.strip():
        errors.append({"field": "evidence", "message": "must be non-empty string"})
    observed_at = body.get("client_observed_at")
    if not isinstance(observed_at, str) or not observed_at.strip():
        errors.append(
            {"field": "client_observed_at", "message": "must be non-empty string"}
        )
    if errors:
        _raise_validation_error(errors)

    client_kind = _parse_client_kind_for_blacklist(body.get("client_kind"))

    entry = service.mark_blacklisted_by_client(
        account_id,
        client_kind=client_kind,
        evidence=evidence,
    )
    payload = {
        "server_time": _now_utc_iso(),
        "account_id": entry.account_id,
        "pool_status": entry.pool_status.value,
        "suspended_at": (
            _format_utc(entry.suspended_at)
            if entry.suspended_at is not None
            else None
        ),
        "suspension_expires_at": (
            _format_utc(entry.suspension_expires_at)
            if entry.suspension_expires_at is not None
            else None
        ),
    }
    return JSONResponse(content=payload, status_code=status.HTTP_200_OK)


# ----------------------------- 注册入口 -----------------------------


def _build_default_service(app: FastAPI) -> AccountPoolService:
    """从 ``app.state.settings`` 构造默认 :class:`AccountPoolService`。

    与 :func:`prevent_auto.web.runtime.build_account_pool_service` 同口径——
    ``ACCOUNT_POOL_SECRET_KEY`` 缺失时无法构造（AES-GCM 必须有密钥），抛
    :class:`RuntimeError` 让启动阶段直接失败，避免线上跑一半才发现密码无法解密。
    """

    settings = getattr(app.state, "settings", None)
    if settings is None:  # pragma: no cover - 启动期失误
        raise RuntimeError(
            "无法构造 AccountPoolService：app.state.settings 未设置",
        )
    if not settings.account_pool_secret_key:
        raise RuntimeError(
            "无法构造 AccountPoolService：ACCOUNT_POOL_SECRET_KEY 未配置",
        )
    cipher = AccountPasswordCipher(settings.account_pool_secret_key)
    login_status_cache = SqliteLoginStatusCache(
        LoginStatusCacheRepository(settings.database_path),
    )
    return AccountPoolService(
        Path(settings.database_path),
        cipher=cipher,
        login_status_cache=login_status_cache,
    )


def register(
    app: FastAPI,
    *,
    account_pool_service: AccountPoolService | None = None,
) -> AccountPoolService | None:
    """把路由 + 服务挂上 FastAPI 应用并返回最终生效的服务实例。

    服务装配优先级：

    1. 调用方显式传入的 ``account_pool_service``。
    2. ``app.state.account_pool_service`` 已经被其它模块（例如 8.3 共享路径）注入。
    3. ``app.state.services.account_pool_service``：``create_app`` 在 ``build_services``
       阶段已经构造好的实例（密钥存在时）。
    4. 调用 :func:`_build_default_service` 按 ``app.state.settings`` 重新构造一份
       （仅在 ``ACCOUNT_POOL_SECRET_KEY`` 已配置时成立）。

    四个来源都拿不到时——典型场景是单元测试 / 开发环境没有配
    ``ACCOUNT_POOL_SECRET_KEY``——本函数 **直接跳过路由挂载并返回 ``None``**，
    与 :func:`prevent_auto.web.runtime.start_pool_reaper_async` 在缺密钥时的退化
    口径保持一致。生产环境会在更早期校验密钥存在，不会走到这条退化路径。

    本函数只挂路由 + 注入服务，不再注册业务异常处理器；
    :class:`AccountNotInActivePool` 等映射由
    :func:`prevent_auto.web.exception_handlers.register_account_pool_exception_handlers`
    集中负责。
    """

    if account_pool_service is not None:
        service: AccountPoolService | None = account_pool_service
    else:
        existing = getattr(app.state, ACCOUNT_POOL_SERVICE_STATE_ATTR, None)
        if isinstance(existing, AccountPoolService):
            service = existing
        else:
            service = _service_from_app_services(app)
            if service is None:
                service = _try_build_default_service(app)
    if service is None:
        return None
    setattr(app.state, ACCOUNT_POOL_SERVICE_STATE_ATTR, service)
    app.include_router(router)
    return service


def _try_build_default_service(app: FastAPI) -> AccountPoolService | None:
    """:func:`_build_default_service` 的安静版：缺密钥时返回 ``None``。"""

    settings = getattr(app.state, "settings", None)
    if settings is None or not getattr(settings, "account_pool_secret_key", ""):
        return None
    return _build_default_service(app)


def _service_from_app_services(app: FastAPI) -> AccountPoolService | None:
    """从 ``app.state.services`` 取已经构造好的 :class:`AccountPoolService`。

    ``services`` 容器是 :class:`prevent_auto.web.runtime.AppServices`；
    ``account_pool_service`` 字段在 ``ACCOUNT_POOL_SECRET_KEY`` 缺失时为
    ``None``，由调用方决定是否兜底。
    """

    services = getattr(app.state, "services", None)
    candidate = getattr(services, "account_pool_service", None)
    return candidate if isinstance(candidate, AccountPoolService) else None


__all__ = [
    "ACCOUNT_POOL_SERVICE_STATE_ATTR",
    "ROUTER_PREFIX",
    "register",
    "router",
]
