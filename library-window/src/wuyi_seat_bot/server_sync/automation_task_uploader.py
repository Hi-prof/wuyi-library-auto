"""自动任务上行模块。

封装 ``Automation_Task_Sync_API`` 的客户端调用逻辑，把服务端的业务错误码翻译成
Window_Client 自身的业务异常，便于上层 UI / 调度层根据异常类型走不同的恢复路径：

- ``PUT  /api/v1/active-accounts/{account_id}/automation-tasks/{task_id}``：上行 upsert。
- ``DELETE /api/v1/active-accounts/{account_id}/automation-tasks/{task_id}``：上行软删。

拉黑事件上报（``POST /api/v1/active-accounts/{account_id}/blacklist-events``）已在
spec ``account-pool-tri-sync`` 任务 11.8 中拆出到独立模块
:mod:`wuyi_seat_bot.server_sync.blacklist_reporter`，本模块不再承担该职责。

上行触发条件（spec ``account-pool-tri-sync`` 任务 11.9，Requirement 14.2 / 14.3 / 14.5）：

- 上行 PUT/DELETE 的触发条件不再是「服务端可达」，而是「:class:`ServerSyncConfig` 已配置
  **且** ``upload_enabled=True``」双开关。任一开关关闭即进入 Local_Only_Mode，本模块
  **不会** 发起任何 HTTP 调用。
- 调用方（ViewModel / 任务编辑面板）应先用 :func:`should_upload` 守卫一道，再调用本类，
  确保 Local_Only_Mode 下零网络请求。
- 作为防御层，``AutomationTaskUploader`` 在收到 ``config_provider`` 时会在每次
  PUT/DELETE 前自检；若双开关关闭，直接抛 :class:`UploadDisabledByConfig`，**不**
  发起任何 HTTP 调用，**不** 修改 ``connectivity`` 状态。
- 失败时调用方仅在 UI 提示，**不** 回滚 Local_Account_Store 中已经发生的本地编辑
  （Requirement 14.5）。

错误翻译（与服务端 ``prevent_auto.web.exception_handlers`` 与
``prevent_auto.web.api.automation_task`` 严格对齐，design.md「Automation_Task_Sync_API」
错误响应表）：

- ``409 {"reason":"revision_conflict","server_revision":N,"server_payload":{...}}`` →
  :class:`AutomationTaskRevisionConflict`，并构造 :class:`RevisionConflictResolution`
  暴露给调用方做「保留本地 / 接受服务端」的二选一回调。
- ``404 {"detail":"account not found"}`` → :class:`AccountNotInActivePool`，触发上层
  主动刷新本地 Active_Pool 清单（Requirement 6.5、6.6、12.4）。
- ``400 {"reason":"validation_error","errors":[{"field","message"}]}`` →
  :class:`AutomationTaskValidationError`，调用方据此 toast 字段错误（Requirement 8.4）。
  ``422`` 的业务规则错误（如 ``pool_full`` / ``illegal_transition``）也走同一类异常，
  ``reason`` 字段保留给上层判断。

服务端不可达（网络错误 / 超时 / 5xx / 401 / 426）继续向上抛 :class:`ServerUnreachable`
家族，由调用方按 connectivity 状态处理；本模块同时把这些失败转告 ``ServerConnectivity``，
保持与 ``ActivePoolRepository`` 一致的口径（Requirement 12.2、12.7）。

冲突解决回调约定（``RevisionConflictResolution``）：

- ``keep_local()``：客户端选择「保留本地」，重新 PUT 一次，把 ``revision`` 升到
  ``server_revision``（即基于服务端最新版本号但保留本地 payload）。
- ``accept_server()``：客户端选择「接受服务端」，仅返回 ``server_payload``，由 UI 直接
  覆盖本地缓存，不再发起 PUT。

任何方法都不会持久化 ``payload``、``evidence`` 等敏感字段，调用方负责自身的 UI / Worker
状态机。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Literal, Mapping, MutableMapping

from wuyi_seat_bot.server_sync.client import (
    HttpsRequired,
    ProtocolError,
    RateLimited,
    ServerSyncClient,
    ServerSyncError,
    ServerUnreachable,
    Unauthorized,
)
from wuyi_seat_bot.server_sync.connectivity_indicator import (
    ServerConnectivity,
    parse_server_time,
)
from wuyi_seat_bot.server_sync.settings import ServerSyncConfig


logger = logging.getLogger(__name__)


_UPSERT_PATH_TEMPLATE: str = "/api/v1/active-accounts/{account_id}/automation-tasks/{task_id}"

ClientKind = Literal["window", "android"]


# --------------------------------------------------------------------------- #
# Exceptions                                                                   #
# --------------------------------------------------------------------------- #


class AutomationTaskUploadError(ServerSyncError):
    """``automation_task_uploader`` 模块所有业务异常的基类。

    继承自 :class:`ServerSyncError` 便于上层 ``except`` 一次捕获 server_sync 全部异常。
    """


class UploadDisabledByConfig(AutomationTaskUploadError):
    """上行被 :class:`ServerSyncConfig` 双开关阻断（spec 任务 11.9）。

    仅在 :class:`AutomationTaskUploader` 持有 ``config_provider`` 且当前配置不满足
    ``is_upload_enabled()``（即未配置 ``base_url`` / ``bearer_token``，或
    ``upload_enabled=False``）时，由 ``upsert`` / ``delete`` 在发起 HTTP 调用 **之前**
    抛出。

    抛出此异常时：

    - 不会发起任何 HTTP 调用，``connectivity`` 状态不被修改。
    - Local_Account_Store 中已经发生的本地编辑由调用方保留，不应回滚。
    - 调用方应在 UI 上提示「未配置服务端」或「同步上行未开启」，并继续走本地执行。

    本异常不应被翻译为 ``ServerUnreachable``——它是配置驱动的本地拒绝，与服务端是否可达
    无关（Requirement 14.2 / 14.3 / 14.5）。
    """

    def __init__(self, reason: str = "服务端同步上行未启用") -> None:
        super().__init__(reason)
        self.reason = reason


class AccountNotInActivePool(AutomationTaskUploadError):
    """目标账号不在 Active_Pool（404）。

    服务端在 Requirement 6.5、7.6 下对「不存在 / 已迁出活跃池 / 软删」一律返回字节级一致
    的 404。客户端据此触发 UI 主动刷新本地 Active_Pool 清单。
    """

    def __init__(self, account_id: int) -> None:
        super().__init__(f"账号 {account_id} 不在活跃池")
        self.account_id = account_id


@dataclass(frozen=True)
class ValidationFieldError:
    """单个字段错误。

    与服务端 ``handle_automation_task_validation_error`` 响应中
    ``errors[*]`` 字段一一对应。
    """

    field: str
    message: str


class AutomationTaskValidationError(AutomationTaskUploadError):
    """服务端拒绝请求体（400 / 422）。

    - 400：服务端字段非空 / 类型 / 长度校验失败（``reason='validation_error'``）。
    - 422：服务端业务规则错误（``reason ∈ {pool_full, illegal_transition,
      missing_login_credentials, duplicate_student_id, idle_empty}``）。

    调用方按 :attr:`reason` 与 :attr:`errors` 做不同 toast；Requirement 8.4 默认
    「直接展示字段错误」即可。
    """

    def __init__(
        self,
        *,
        status_code: int,
        reason: str,
        errors: list[ValidationFieldError] | None = None,
        body: Any = None,
    ) -> None:
        message = f"服务端拒绝请求：{reason}" if reason else "服务端拒绝请求"
        super().__init__(message)
        self.status_code = status_code
        self.reason = reason
        self.errors: list[ValidationFieldError] = list(errors or [])
        self.body = body


class AutomationTaskRevisionConflict(AutomationTaskUploadError):
    """乐观并发冲突（409）。

    服务端响应：``{"reason":"revision_conflict","server_revision":N,"server_payload":{...}}``。
    :attr:`resolution` 是一个 :class:`RevisionConflictResolution` 对象，调用方据此
    在 UI 上让用户在「保留本地」与「接受服务端」之间二选一。
    """

    def __init__(
        self,
        *,
        account_id: int,
        task_id: int,
        server_revision: int,
        server_payload: dict,
        resolution: "RevisionConflictResolution",
    ) -> None:
        super().__init__(
            f"自动任务 {task_id} 在账号 {account_id} 上发生 revision 冲突，"
            f"server_revision={server_revision}"
        )
        self.account_id = account_id
        self.task_id = task_id
        self.server_revision = server_revision
        self.server_payload = dict(server_payload)
        self.resolution = resolution


# --------------------------------------------------------------------------- #
# Conflict resolution helper                                                   #
# --------------------------------------------------------------------------- #


@dataclass
class AutomationTaskUpsertResult:
    """成功 PUT/DELETE 的回包。

    Attributes:
        task: 服务端返回的 ``task`` 对象（与 design 一致：含 ``revision``、
            ``updated_at``、``deleted_at`` 等）。``DELETE`` 后 ``task.deleted_at``
            非空。
        server_time: 顶层 ``server_time``（UTC，可能为 ``None``）。
    """

    task: dict[str, Any]
    server_time: datetime | None


@dataclass
class RevisionConflictResolution:
    """409 冲突解决回调。

    设计目的：让 ``AutomationTaskRevisionConflict`` 的捕获方在不二次构造 client / payload
    的情况下，直接调用 ``keep_local()`` 或 ``accept_server()`` 进行下一步。

    - :meth:`keep_local`：以 ``server_revision`` 重新发起 PUT；语义为「我承认服务端的
      版本号，但保留本地 payload 字段值，强制覆盖」。
    - :meth:`accept_server`：放弃本地变更，返回服务端载荷；UI 应据此覆盖本地缓存。
      该路径不再发起任何 HTTP 调用。

    本对象由 :class:`AutomationTaskUploader` 在抛出 409 异常时构造；调用方拿到的
    `keep_local` / `accept_server` 是绑定到当前 uploader / payload / 路径的闭包。
    """

    server_revision: int
    server_payload: dict[str, Any]
    _keep_local_callable: Callable[[], AutomationTaskUpsertResult]
    _accept_server_callable: Callable[[], dict[str, Any]]

    def keep_local(self) -> AutomationTaskUpsertResult:
        """以 ``server_revision`` 重新 PUT 一次，保留本地 payload。"""

        return self._keep_local_callable()

    def accept_server(self) -> dict[str, Any]:
        """放弃本地变更，返回服务端 payload；不再触发任何 HTTP 调用。"""

        return self._accept_server_callable()


# --------------------------------------------------------------------------- #
# Uploader                                                                     #
# --------------------------------------------------------------------------- #


class AutomationTaskUploader:
    """自动任务上行（PUT / DELETE）。

    本类只做协议翻译与异常映射；它 **不** 维护任何业务状态（例如「队列」「重试」），
    上层调度器（Window 端的自动任务编辑入口、调度器）负责具体的工作流。

    拉黑事件上报已在 spec 任务 11.8 中拆出到 :mod:`wuyi_seat_bot.server_sync.blacklist_reporter`，
    本类不再承担该职责。

    上行触发条件（spec 任务 11.9，Requirement 14.2 / 14.3 / 14.5）：当 ``config_provider``
    被注入时，每次 :meth:`upsert` / :meth:`delete` 调用都会在发起 HTTP 之前自检
    ``config_provider().is_upload_enabled()``；若返回 ``False`` 直接抛
    :class:`UploadDisabledByConfig`，不发起网络请求、不修改 ``connectivity`` 状态。
    ``config_provider=None`` 时不做守卫，主要供既有单元测试与已经在调用前自行守卫的
    工作流使用。

    线程安全：本类无可变状态，可在多线程间共享；底层 :class:`ServerSyncClient` 已
    使用 ``httpx.Client`` 的内部锁，普通使用足够安全。
    """

    def __init__(
        self,
        client: ServerSyncClient,
        connectivity: ServerConnectivity,
        *,
        clock: Callable[[], datetime] = lambda: datetime.now(tz=timezone.utc),
        config_provider: Callable[[], ServerSyncConfig] | None = None,
    ) -> None:
        self._client = client
        self._connectivity = connectivity
        self._clock = clock
        self._config_provider = config_provider

    # ------------------------------------------------------------------ #
    # Upload gate                                                         #
    # ------------------------------------------------------------------ #

    def _ensure_upload_enabled(self) -> None:
        """守卫：若 ``config_provider`` 报告双开关未开启则拒绝。

        ``config_provider=None`` 时不做检查（保留旧行为，由调用方自行守卫）。
        """

        if self._config_provider is None:
            return
        try:
            config = self._config_provider()
        except Exception as exc:  # pragma: no cover - 防御性
            # 读配置失败一律视为「未配置」，进入 Local_Only_Mode；不外泄底层异常。
            logger.debug("读取 ServerSyncConfig 失败，按 Local_Only_Mode 拒绝上行: %s", exc)
            raise UploadDisabledByConfig() from exc
        if not isinstance(config, ServerSyncConfig):
            # 防御：调用方传错类型，按未配置处理。
            raise UploadDisabledByConfig()
        if not config.is_upload_enabled():
            if not config.is_configured():
                raise UploadDisabledByConfig("未配置服务端")
            raise UploadDisabledByConfig("同步上行未启用")

    # ------------------------------------------------------------------ #
    # PUT upsert                                                          #
    # ------------------------------------------------------------------ #

    def upsert(
        self,
        account_id: int,
        task_id: int,
        payload: Mapping[str, Any],
        *,
        expected_revision: int,
    ) -> AutomationTaskUpsertResult:
        """PUT 上传单条自动任务。

        Args:
            account_id: 服务端账号 id（正整数）。
            task_id: 服务端任务 id（正整数；新建时使用客户端约定的占位值）。
            payload: 任务字段，例如 ``room_name`` / ``seat_number`` / ``mode`` /
                ``custom_windows`` / ``enabled``。本对象不会修改外部传入的 payload。
            expected_revision: 客户端持有的 ``revision``。

        Returns:
            :class:`AutomationTaskUpsertResult`，含服务端最新 ``task`` 与 ``server_time``。

        Raises:
            ValueError: ``account_id`` / ``task_id`` / ``expected_revision`` 不合法。
            UploadDisabledByConfig: ``ServerSyncConfig`` 双开关未全部开启
                （``base_url`` / ``bearer_token`` 缺失或 ``upload_enabled=False``）。
            AccountNotInActivePool: 服务端 404。
            AutomationTaskValidationError: 服务端 400 / 422。
            AutomationTaskRevisionConflict: 服务端 409。
            ServerUnreachable: 服务端不可达（网络错误 / 超时 / 5xx / 401 / 426）。
            RateLimited: 命中限频。
        """

        _require_positive_int(account_id, "account_id")
        _require_positive_int(task_id, "task_id")
        _require_non_negative_int(expected_revision, "expected_revision")
        self._ensure_upload_enabled()

        body = _build_upsert_body(payload, expected_revision)
        return self._do_upsert(account_id, task_id, body)

    def _do_upsert(
        self,
        account_id: int,
        task_id: int,
        body: dict[str, Any],
    ) -> AutomationTaskUpsertResult:
        path = _UPSERT_PATH_TEMPLATE.format(
            account_id=account_id, task_id=task_id
        )
        try:
            response = self._client.put(path, json_body=body)
        except ServerUnreachable as exc:
            self._connectivity.mark_unreachable(
                _format_failure(exc), status_code=exc.status_code
            )
            raise
        except (Unauthorized, HttpsRequired) as exc:
            self._connectivity.mark_unreachable(
                _format_failure(exc), status_code=exc.status_code
            )
            raise
        except RateLimited:
            raise
        except ProtocolError as exc:
            self._translate_protocol_error(
                exc, account_id=account_id, task_id=task_id, last_body=body
            )
            raise  # pragma: no cover - _translate 总是 raise

        return self._parse_upsert_response(response.json())

    # ------------------------------------------------------------------ #
    # DELETE                                                              #
    # ------------------------------------------------------------------ #

    def delete(
        self,
        account_id: int,
        task_id: int,
        *,
        revision: int,
    ) -> AutomationTaskUpsertResult:
        """DELETE 软删除单条自动任务。

        与服务端约定一致，``revision`` 通过 query parameter 传入；服务端会写入
        新的 ``deleted_at`` 并把 ``revision`` 加 1，返回的 ``task`` 即更新后版本。

        异常映射与 :meth:`upsert` 一致；同样受 ``ServerSyncConfig`` 双开关守卫，
        双开关未全部开启时抛 :class:`UploadDisabledByConfig`，不发起任何 HTTP 调用。
        """

        _require_positive_int(account_id, "account_id")
        _require_positive_int(task_id, "task_id")
        _require_non_negative_int(revision, "revision")
        self._ensure_upload_enabled()

        path = _UPSERT_PATH_TEMPLATE.format(
            account_id=account_id, task_id=task_id
        )
        try:
            response = self._client.delete(path, params={"revision": revision})
        except ServerUnreachable as exc:
            self._connectivity.mark_unreachable(
                _format_failure(exc), status_code=exc.status_code
            )
            raise
        except (Unauthorized, HttpsRequired) as exc:
            self._connectivity.mark_unreachable(
                _format_failure(exc), status_code=exc.status_code
            )
            raise
        except RateLimited:
            raise
        except ProtocolError as exc:
            self._translate_protocol_error(
                exc,
                account_id=account_id,
                task_id=task_id,
                last_body=None,
            )
            raise  # pragma: no cover

        return self._parse_upsert_response(response.json())

    # ------------------------------------------------------------------ #
    # Helpers                                                             #
    # ------------------------------------------------------------------ #

    def _parse_upsert_response(self, payload: Any) -> AutomationTaskUpsertResult:
        if not isinstance(payload, dict):
            raise ServerSyncError(
                "自动任务上行响应不是 JSON 对象：" f"{type(payload).__name__}"
            )
        task = payload.get("task")
        if not isinstance(task, dict):
            raise ServerSyncError("自动任务上行响应缺少 task 字段")
        server_time = parse_server_time(payload.get("server_time"))
        self._connectivity.mark_reachable(server_time=server_time)
        return AutomationTaskUpsertResult(task=dict(task), server_time=server_time)

    def _translate_protocol_error(
        self,
        exc: ProtocolError,
        *,
        account_id: int,
        task_id: int | None,
        last_body: dict[str, Any] | None,
    ) -> None:
        """把 4xx 协议错误翻译成本模块业务异常。

        非可达性失败一律先把 ``connectivity`` 标记为可达（说明服务端在响应），再抛出。
        """

        status_code = exc.status_code
        body = exc.body if isinstance(exc.body, dict) else {}
        # 服务端在响应，说明可达；不要继续把 connectivity 拖到不可达。
        self._connectivity.mark_reachable()

        if status_code == 404:
            raise AccountNotInActivePool(account_id) from exc

        if status_code == 409 and body.get("reason") == "revision_conflict":
            server_revision = _coerce_int(body.get("server_revision"))
            server_payload_raw = body.get("server_payload")
            server_payload: dict[str, Any] = (
                dict(server_payload_raw)
                if isinstance(server_payload_raw, dict)
                else {}
            )
            resolution = self._build_revision_conflict_resolution(
                account_id=account_id,
                task_id=task_id,
                server_revision=server_revision,
                server_payload=server_payload,
                last_body=last_body,
            )
            raise AutomationTaskRevisionConflict(
                account_id=account_id,
                task_id=task_id if task_id is not None else 0,
                server_revision=server_revision,
                server_payload=server_payload,
                resolution=resolution,
            ) from exc

        if status_code in (400, 422):
            reason = str(body.get("reason") or "")
            errors_raw = body.get("errors")
            errors: list[ValidationFieldError] = []
            if isinstance(errors_raw, list):
                for item in errors_raw:
                    if not isinstance(item, dict):
                        continue
                    field_name = str(item.get("field", ""))
                    message = str(item.get("message", ""))
                    if field_name or message:
                        errors.append(
                            ValidationFieldError(
                                field=field_name, message=message
                            )
                        )
            raise AutomationTaskValidationError(
                status_code=status_code,
                reason=reason,
                errors=errors,
                body=exc.body,
            ) from exc

        # 其它 4xx：按原始 ProtocolError 抛出，保留 status_code / body。
        raise exc

    def _build_revision_conflict_resolution(
        self,
        *,
        account_id: int,
        task_id: int | None,
        server_revision: int,
        server_payload: dict[str, Any],
        last_body: dict[str, Any] | None,
    ) -> RevisionConflictResolution:
        """构造冲突解决回调。

        如果 ``last_body`` 为空（DELETE 路径），``keep_local`` 会拒绝调用——该路径没有
        「保留本地 payload」的语义。
        """

        def _keep_local() -> AutomationTaskUpsertResult:
            if task_id is None or last_body is None:
                raise RuntimeError(
                    "当前请求没有可保留的本地 payload，不能调用 keep_local()"
                )
            new_body = dict(last_body)
            new_body["revision"] = server_revision
            return self._do_upsert(account_id, task_id, new_body)

        def _accept_server() -> dict[str, Any]:
            return dict(server_payload)

        return RevisionConflictResolution(
            server_revision=server_revision,
            server_payload=server_payload,
            _keep_local_callable=_keep_local,
            _accept_server_callable=_accept_server,
        )


# --------------------------------------------------------------------------- #
# Validation helpers                                                           #
# --------------------------------------------------------------------------- #


def _require_positive_int(value: Any, name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{name} 必须是正整数")


def _require_non_negative_int(value: Any, name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} 必须是非负整数")


def _coerce_int(value: Any) -> int:
    if isinstance(value, bool):
        return 0
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().lstrip("-").isdigit():
        return int(value)
    return 0


def _build_upsert_body(
    payload: Mapping[str, Any], expected_revision: int
) -> dict[str, Any]:
    """把调用方传入的 ``payload`` 与 ``expected_revision`` 合并成 PUT 请求体。

    若 payload 自带 ``revision`` 字段，会被覆盖为 ``expected_revision`` 以保证语义一致。
    服务端 ``AutomationTaskUpsertModel`` 禁止 ``extra`` 字段，本函数不做白名单过滤——
    调用方应保证只传入 design 约定字段。
    """

    if not isinstance(payload, Mapping):
        raise ValueError("payload 必须是字典")
    body: MutableMapping[str, Any] = dict(payload)
    body["revision"] = expected_revision
    return dict(body)


def _format_failure(exc: ServerSyncError) -> str:
    text = str(exc).strip()
    return text or exc.__class__.__name__


# --------------------------------------------------------------------------- #
# Public helpers                                                               #
# --------------------------------------------------------------------------- #


def should_upload(config: ServerSyncConfig | None) -> bool:
    """判断是否应当向服务端上行自动任务变更。

    ViewModel / 任务编辑面板在保存任务、删除任务等入口处应先调用本函数；
    返回 ``False`` 时直接走纯本地路径，**不要** 构造或调用 :class:`AutomationTaskUploader`，
    保证 Local_Only_Mode 下零网络请求（Requirement 12.6 / 14.2）。

    Args:
        config: 当前 :class:`ServerSyncConfig`；``None`` 视为未配置。

    Returns:
        ``True`` 当且仅当 ``config`` 已配置 ``base_url`` / ``bearer_token`` 且
        ``upload_enabled=True``（Requirement 14.3 双开关）。
    """

    if config is None:
        return False
    return config.is_upload_enabled()


__all__ = [
    "AccountNotInActivePool",
    "AutomationTaskRevisionConflict",
    "AutomationTaskUploadError",
    "AutomationTaskUploader",
    "AutomationTaskUpsertResult",
    "AutomationTaskValidationError",
    "ClientKind",
    "RevisionConflictResolution",
    "UploadDisabledByConfig",
    "ValidationFieldError",
    "should_upload",
]
