"""拉黑事件上报模块。

独立承担 ``POST /api/v1/active-accounts/{account_id}/blacklist-events`` 调用，
受 ``Server_Sync_Config`` 双开关约束（已配置 + ``upload_enabled=true``）。

### 调用条件（Requirement 15.1 / 15.2）

- 当 ``Server_Sync_Config`` 未配置（``base_url`` / ``bearer_token`` 任一缺失）：
  跳过本次上报，``BlacklistReportResult.skipped=True``、
  ``error_kind="config_missing"``，**不** 发起任何 HTTP 请求。
- 当 ``Server_Sync_Config`` 已配置但 ``upload_enabled=false``：
  跳过，``error_kind="upload_disabled"``，**不** 发起任何 HTTP 请求。
- 仅当 ``Server_Sync_Config.is_upload_enabled()`` 为 ``True`` 时才会触达网络层。

### 失败处理（Requirement 15.3）

- 网络错误 / 401 / 426 / 5xx / 限频 / 4xx：捕获并翻译为
  ``BlacklistReportResult.error_kind`` 字段，``delivered=False``。
- 调用方据 ``error_kind`` 与 ``message`` 做 UI / 日志提示；**不** 据此回滚
  本地拉黑标记，**不** 阻塞本地执行流程。
- 本模块不维护失败重试队列；调用方按需在用户下次显式触发时再次调用。

### 与 ``automation_task_uploader`` 的关系

- 在 spec ``account-pool-tri-sync`` 任务 11.8 中从 ``automation_task_uploader.py``
  拆出，独立承担拉黑事件上报职责，原 ``AutomationTaskUploader.report_blacklist_event``
  随之删除。
- 错误响应翻译与 uploader 一致：``404 → not_in_active_pool``、
  ``400 / 422 → validation_error``、``409`` → ``protocol_error``（未来可扩展）。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable, Literal

from wuyi_seat_bot.server_sync.client import (
    HttpsRequired,
    NetworkError,
    ProtocolError,
    RateLimited,
    ServerError,
    ServerSyncClient,
    ServerSyncError,
    ServerUnreachable,
    Unauthorized,
)
from wuyi_seat_bot.server_sync.connectivity_indicator import ConnectivityIndicator
from wuyi_seat_bot.server_sync.settings import (
    ServerSyncConfig,
    ServerSyncSettings,
)


logger = logging.getLogger(__name__)


_BLACKLIST_PATH_TEMPLATE: str = "/api/v1/active-accounts/{account_id}/blacklist-events"

ClientKind = Literal["window", "android"]
"""客户端类型字面量，对齐服务端 ``client_kind`` 字段。"""


# --------------------------------------------------------------------------- #
# Result                                                                       #
# --------------------------------------------------------------------------- #


# 跳过类（双开关关闭，本地未触发 HTTP）。
_SKIP_CONFIG_MISSING: str = "config_missing"
_SKIP_UPLOAD_DISABLED: str = "upload_disabled"

# 错误类（已发起 HTTP 但失败）。
_ERROR_SERVER_UNREACHABLE: str = "server_unreachable"
_ERROR_UNAUTHORIZED: str = "unauthorized"
_ERROR_HTTPS_REQUIRED: str = "https_required"
_ERROR_RATE_LIMITED: str = "rate_limited"
_ERROR_NOT_IN_ACTIVE_POOL: str = "not_in_active_pool"
_ERROR_VALIDATION: str = "validation_error"
_ERROR_PROTOCOL: str = "protocol_error"
_ERROR_UNKNOWN: str = "unknown"


@dataclass(frozen=True)
class BlacklistReportResult:
    """单次拉黑事件上报的结果。

    Attributes:
        delivered: 服务端返回 2xx 时为 ``True``；否则为 ``False``。
        skipped: 因双开关关闭未发起 HTTP 请求时为 ``True``。
        error_kind: 失败 / 跳过原因的字符串标签；成功时为空字符串。
            可选取值：``config_missing`` / ``upload_disabled`` /
            ``server_unreachable`` / ``unauthorized`` / ``https_required`` /
            ``rate_limited`` / ``not_in_active_pool`` / ``validation_error`` /
            ``protocol_error`` / ``unknown``。
        message: 给 UI / 日志使用的简短文案；成功时空串。
        status_code: 服务端响应状态码（仅 HTTP 失败时非 ``None``）。
        server_response: 服务端响应字典（仅 ``delivered=True`` 时非 ``None``）。
        validation_errors: ``error_kind='validation_error'`` 时携带的字段错误列表。
    """

    delivered: bool = False
    skipped: bool = False
    error_kind: str = ""
    message: str = ""
    status_code: int | None = None
    server_response: dict[str, Any] | None = None
    validation_errors: list[dict[str, str]] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """``delivered`` 的别名，便于调用方写 ``if result.ok``。"""

        return self.delivered


# --------------------------------------------------------------------------- #
# Reporter                                                                     #
# --------------------------------------------------------------------------- #


class BlacklistReporter:
    """拉黑事件上报。

    本类把双开关守卫与 HTTP 错误翻译都封装在内，确保：

    1. 双开关任一关闭时 **零** 网络请求（含 DNS / TCP / TLS 握手）。
    2. 任何失败都不抛出异常给调用方，而是以 :class:`BlacklistReportResult` 返回；
       这样调用方无需 ``try/except``，循环 / 调度器流程不会被异常打断
       （Requirement 15.3 「失败仅 UI / 日志提示，不阻塞本地执行」）。
    3. 客户端在没有持久化 :class:`ServerSyncClient` 的情况下也能用：
       构造时只接受「读取最新配置」的回调；HTTP client 在判定双开关开启后
       通过 ``client_factory`` 临时构造，调用结束后自动关闭。

    Args:
        config_provider: 返回最新 :class:`ServerSyncConfig` 的回调；
            每次 ``report()`` 都会重新读取，确保用户在 UI 上修改配置后
            下一次上报立刻生效。
        client_factory: 根据 :class:`ServerSyncSettings` 构造
            :class:`ServerSyncClient` 的工厂；测试时用于注入 mock transport。
            默认调用方为 ``ServerSyncClient(settings)``。
        connectivity: 可选的 :class:`ConnectivityIndicator`；本对象会在调用
            成功 / 失败后调用 ``record_sync_success()`` / ``record_sync_failure()``，
            用于驱动同步按钮三态指示（Requirement 13.9）。``None`` 表示不上报状态。
        clock: 当前 UTC 时间的回调；测试时可注入固定时钟。
    """

    def __init__(
        self,
        config_provider: Callable[[], ServerSyncConfig],
        *,
        client_factory: Callable[[ServerSyncSettings], ServerSyncClient] | None = None,
        connectivity: ConnectivityIndicator | None = None,
        clock: Callable[[], datetime] = lambda: datetime.now(tz=timezone.utc),
    ) -> None:
        self._config_provider = config_provider
        self._client_factory = client_factory or _default_client_factory
        self._connectivity = connectivity
        self._clock = clock

    # ------------------------------------------------------------------ #
    # Public API                                                          #
    # ------------------------------------------------------------------ #

    def report(
        self,
        account_id: int,
        *,
        evidence: str,
        client_kind: ClientKind = "window",
        client_observed_at: datetime | None = None,
    ) -> BlacklistReportResult:
        """上报一次拉黑事件。

        Args:
            account_id: 服务端账号 id（正整数）。
            evidence: 拉黑证据，非空字符串（去空白后非空）。
            client_kind: 客户端类型，``"window"`` 或 ``"android"``。
            client_observed_at: 客户端观察到的时间（UTC）；省略时取
                ``self._clock()``。

        Returns:
            :class:`BlacklistReportResult`。本方法 **不抛异常**：
            入参非法的情况也会被翻译为 ``error_kind='validation_error'`` 的结果，
            便于调用方在循环 / 调度器流程中不被异常打断。
        """

        # ---- 入参校验：失败也走 result 而非 raise，保持流程不被打断 ---- #
        if not isinstance(account_id, int) or isinstance(account_id, bool) or account_id <= 0:
            return BlacklistReportResult(
                delivered=False,
                error_kind=_ERROR_VALIDATION,
                message="account_id 必须是正整数",
                validation_errors=[
                    {"field": "account_id", "message": "must be a positive integer"}
                ],
            )
        if not isinstance(evidence, str) or not evidence.strip():
            return BlacklistReportResult(
                delivered=False,
                error_kind=_ERROR_VALIDATION,
                message="evidence 必须是非空字符串",
                validation_errors=[
                    {"field": "evidence", "message": "must be non-empty string"}
                ],
            )
        if client_kind not in ("window", "android"):
            return BlacklistReportResult(
                delivered=False,
                error_kind=_ERROR_VALIDATION,
                message="client_kind 必须是 'window' 或 'android'",
                validation_errors=[
                    {"field": "client_kind", "message": "must be 'window' or 'android'"}
                ],
            )

        # ---- 双开关守卫：任一关闭 → 不发任何网络请求 ---- #
        config = self._config_provider()
        if not isinstance(config, ServerSyncConfig):  # 防御调用方误传
            logger.warning(
                "BlacklistReporter 收到非 ServerSyncConfig 的配置：%r", type(config)
            )
            return BlacklistReportResult(
                delivered=False,
                skipped=True,
                error_kind=_SKIP_CONFIG_MISSING,
                message="未配置服务端，已跳过拉黑事件上报",
            )
        if not config.is_configured():
            return BlacklistReportResult(
                delivered=False,
                skipped=True,
                error_kind=_SKIP_CONFIG_MISSING,
                message="未配置服务端，已跳过拉黑事件上报",
            )
        if not config.upload_enabled:
            return BlacklistReportResult(
                delivered=False,
                skipped=True,
                error_kind=_SKIP_UPLOAD_DISABLED,
                message="同步上行开关已关闭，已跳过拉黑事件上报",
            )

        # ---- 构造请求体 ---- #
        observed_at = client_observed_at or self._clock()
        body = {
            "evidence": evidence,
            "client_kind": client_kind,
            "client_observed_at": _format_utc(observed_at),
        }
        path = _BLACKLIST_PATH_TEMPLATE.format(account_id=account_id)

        # ---- 双开关已开 → 调用网络层；不抛异常给调用方 ---- #
        settings = config.to_server_sync_settings()
        if settings is None:  # 理论上 is_configured() 已校验过；防御
            return BlacklistReportResult(
                delivered=False,
                skipped=True,
                error_kind=_SKIP_CONFIG_MISSING,
                message="服务端配置非法，已跳过拉黑事件上报",
            )

        try:
            with self._client_factory(settings) as client:
                response = client.post(path, json_body=body)
        except ServerUnreachable as exc:
            self._record_failure(_format_failure(exc))
            return BlacklistReportResult(
                delivered=False,
                error_kind=(
                    _ERROR_HTTPS_REQUIRED
                    if isinstance(exc, HttpsRequired)
                    else _ERROR_UNAUTHORIZED
                    if isinstance(exc, Unauthorized)
                    else _ERROR_SERVER_UNREACHABLE
                ),
                message=_format_failure(exc),
                status_code=exc.status_code,
            )
        except (Unauthorized, HttpsRequired) as exc:
            # Unauthorized / HttpsRequired 在 client.py 中本身已是 ProtocolError 子类，
            # 但为了与 uploader 保持一致，单独翻译为可读 error_kind。
            self._record_failure(_format_failure(exc))
            kind = (
                _ERROR_HTTPS_REQUIRED
                if isinstance(exc, HttpsRequired)
                else _ERROR_UNAUTHORIZED
            )
            return BlacklistReportResult(
                delivered=False,
                error_kind=kind,
                message=_format_failure(exc),
                status_code=exc.status_code,
            )
        except RateLimited as exc:
            # 429 不视作不可达；不更新 connectivity 状态。
            return BlacklistReportResult(
                delivered=False,
                error_kind=_ERROR_RATE_LIMITED,
                message=_format_failure(exc),
                status_code=exc.status_code,
            )
        except ProtocolError as exc:
            return self._translate_protocol_error(exc)
        except ServerSyncError as exc:
            # 兜底：未预期的 server_sync 异常。
            logger.exception("拉黑事件上报失败：未预期异常")
            return BlacklistReportResult(
                delivered=False,
                error_kind=_ERROR_UNKNOWN,
                message=_format_failure(exc),
            )

        # ---- 解析响应 ---- #
        try:
            payload = response.json()
        except Exception as exc:  # noqa: BLE001 - httpx 多种异常
            logger.warning("拉黑事件上报响应不是 JSON：%s", exc)
            payload = None

        if not isinstance(payload, dict):
            logger.warning(
                "拉黑事件上报响应不是 JSON 对象，已忽略响应体：%r", type(payload)
            )
            payload = {}

        self._record_success()
        return BlacklistReportResult(
            delivered=True,
            error_kind="",
            message="",
            status_code=response.status_code,
            server_response=dict(payload),
        )

    # ------------------------------------------------------------------ #
    # Internal helpers                                                    #
    # ------------------------------------------------------------------ #

    def _translate_protocol_error(
        self, exc: ProtocolError
    ) -> BlacklistReportResult:
        """把 4xx 协议错误翻译为 :class:`BlacklistReportResult`。

        404 → ``not_in_active_pool``；400/422 → ``validation_error``；
        其它 4xx → ``protocol_error``。所有这些都说明服务端在响应，所以
        connectivity 标记为成功（``record_sync_success``）。
        """

        body = exc.body if isinstance(exc.body, dict) else {}
        if exc.status_code == 404:
            self._record_success()
            return BlacklistReportResult(
                delivered=False,
                error_kind=_ERROR_NOT_IN_ACTIVE_POOL,
                message="账号不在活跃池，已跳过本次拉黑事件",
                status_code=404,
            )
        if exc.status_code in (400, 422):
            self._record_success()
            reason = str(body.get("reason") or "")
            errors_raw = body.get("errors")
            errors: list[dict[str, str]] = []
            if isinstance(errors_raw, list):
                for item in errors_raw:
                    if isinstance(item, dict):
                        field_name = str(item.get("field", ""))
                        message = str(item.get("message", ""))
                        if field_name or message:
                            errors.append(
                                {"field": field_name, "message": message}
                            )
            display_message = (
                f"服务端拒绝请求：{reason}" if reason else "服务端拒绝请求"
            )
            return BlacklistReportResult(
                delivered=False,
                error_kind=_ERROR_VALIDATION,
                message=display_message,
                status_code=exc.status_code,
                validation_errors=errors,
            )
        # 其它 4xx：服务端在响应，但客户端无法判断业务语义；记录可达。
        self._record_success()
        return BlacklistReportResult(
            delivered=False,
            error_kind=_ERROR_PROTOCOL,
            message=_format_failure(exc),
            status_code=exc.status_code,
        )

    def _record_success(self) -> None:
        if self._connectivity is not None:
            self._connectivity.record_sync_success()

    def _record_failure(self, reason: str) -> None:
        if self._connectivity is not None:
            self._connectivity.record_sync_failure(reason)


# --------------------------------------------------------------------------- #
# Module-level helpers                                                         #
# --------------------------------------------------------------------------- #


def _default_client_factory(settings: ServerSyncSettings) -> ServerSyncClient:
    return ServerSyncClient(settings)


def _format_utc(value: datetime) -> str:
    """把 datetime 序列化为 ``...Z`` UTC ISO8601 文本，与服务端一致。"""

    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _format_failure(exc: BaseException) -> str:
    text = str(exc).strip()
    return text or exc.__class__.__name__


__all__ = [
    "BlacklistReporter",
    "BlacklistReportResult",
    "ClientKind",
]
