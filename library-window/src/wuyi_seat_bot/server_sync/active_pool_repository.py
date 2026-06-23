"""活跃池仓库：拉取活跃池清单与按需取详情。

本模块负责把服务端 ``Active_Account_Sync_API`` 的两阶段同步落地为客户端可用的接口：

- :meth:`ActivePoolRepository.refresh_active_list`
  对应接口 A ``GET /api/v1/active-accounts``，返回不含密码、cookie、自动任务详情
  的清单（Requirement 7.1、7.2、6.3），并把响应缓存到内存供后续读取。
- :meth:`ActivePoolRepository.get_active_account_detail`
  对应接口 B ``GET /api/v1/active-accounts/{id}/detail``，按账号 id 取明文密码
  与 ``automation_tasks``（Requirement 7.3）；账号不在 Active_Pool 时返回 ``None``
  （服务端语义为 404，对应 Requirement 6.5、6.6、7.6）。
- :meth:`ActivePoolRepository.cached_active_list`
  返回最近一次成功 ``refresh_active_list`` 的结果；从未刷新成功时返回空列表，
  避免「服务端不可达时退回本地缓存兜底」（Requirement 12.2、12.7）。

密码与会话相关字段：

- 接口 B 返回的密码字段以 :class:`ActiveAccountDetail` 形式直接返回给调用方，
  仓库 **不** 在内存缓存这个对象，也 **不** 写入磁盘。
- 调用方在单次自动任务执行完毕后建议调用
  :meth:`ActiveAccountDetail.wipe_password` 主动覆写明文，进程退出后自然销毁。
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from datetime import datetime
from typing import Any

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


logger = logging.getLogger(__name__)


_LIST_PATH: str = "/api/v1/active-accounts"
_DETAIL_PATH_TEMPLATE: str = "/api/v1/active-accounts/{account_id}/detail"


# --------------------------------------------------------------------------- #
# Data classes                                                                 #
# --------------------------------------------------------------------------- #


@dataclass(frozen=True, slots=True)
class CustomWindow:
    """自动任务自定义窗口。

    服务端按 ``[{"date": "2026-04-27", "start_hour": 8, "end_hour": 12}]`` 输出，
    本类仅做容器，校验责任在上游服务端。
    """

    date: str
    start_hour: int
    end_hour: int


@dataclass(frozen=True, slots=True)
class AutomationTask:
    """单个自动任务（接口 B 返回字段的客户端镜像）。"""

    task_id: int
    room_name: str
    seat_number: str
    mode: str
    custom_windows: list[CustomWindow]
    enabled: bool
    revision: int
    updated_at: datetime | None


@dataclass(frozen=True, slots=True)
class ActiveAccountListItem:
    """接口 A 单条记录。

    Attributes:
        account_id: 服务端账号自增 ID。
        student_id: 学号。
        display_name: 备注 / 姓名（可能为空字符串）。
        pool_status: 始终为 ``"active"``；保留字段以便服务端未来扩展。
        updated_at: 服务端 ``pool_updated_at``（UTC，可能为 ``None`` 表示缺失）。
    """

    account_id: int
    student_id: str
    display_name: str
    pool_status: str
    updated_at: datetime | None


@dataclass(slots=True)
class ActiveAccountDetail:
    """接口 B 响应的客户端镜像。

    ``password`` 字段以明文形式持有，仅供单次自动任务执行使用；调用方在使用
    完毕后建议调用 :meth:`wipe_password` 主动覆写，避免长生命周期对象继续
    持有明文。本类不做任何磁盘持久化。
    """

    account_id: int
    student_id: str
    display_name: str
    password: str
    revision: int
    automation_tasks: list[AutomationTask]
    server_time: datetime | None = None

    def wipe_password(self) -> None:
        """把 ``password`` 字段覆写为空串。

        Python ``str`` 不可变，旧字符串对象的内存由 CPython 决定回收时机；
        本方法只做「解除引用」，让上层在使用完毕后尽早释放对明文的引用。
        """

        self.password = ""


# --------------------------------------------------------------------------- #
# Repository                                                                   #
# --------------------------------------------------------------------------- #


class ActivePoolRepository:
    """活跃池仓库：维护 Active_Pool 清单缓存 + 按需取详情。

    线程安全：使用 :class:`threading.Lock` 保护内部缓存，便于 UI 线程读取
    `cached_active_list()` 同时由调度线程触发 `refresh_active_list()`。
    """

    def __init__(
        self,
        client: ServerSyncClient,
        connectivity: ServerConnectivity,
    ) -> None:
        self._client = client
        self._connectivity = connectivity
        self._lock = threading.Lock()
        self._cached_list: list[ActiveAccountListItem] = []

    # ------------------------------------------------------------------ #
    # 接口 A：刷新清单                                                     #
    # ------------------------------------------------------------------ #

    def refresh_active_list(self) -> list[ActiveAccountListItem]:
        """调用接口 A 拉取活跃池清单并更新内存缓存。

        - 成功：解析 ``server_time`` 与 ``accounts``，更新缓存，标记 connectivity 可达。
        - 失败：根据异常类型标记 connectivity 不可达，重抛 :class:`ServerSyncError`
          供上层统一处理（Requirement 12.2 / 12.7：客户端不读本地兜底）。
        """

        try:
            response = self._client.get(_LIST_PATH)
        except ServerUnreachable as exc:
            self._connectivity.mark_unreachable(
                _format_failure(exc), status_code=exc.status_code
            )
            raise
        except (Unauthorized, HttpsRequired) as exc:
            # 401 / 426：服务端在响应，但客户端无法继续依赖 Active_Pool。
            # 与 Property 16 一致，把这两类视作「服务端不可达」语义。
            self._connectivity.mark_unreachable(
                _format_failure(exc), status_code=exc.status_code
            )
            raise
        except RateLimited:
            # 限频不视作不可达；调用方按 Retry-After 退避后再次尝试。
            raise
        except ProtocolError as exc:
            # 其他 4xx：服务端在响应但拒绝本次请求；不改 connectivity 不可达标志，
            # 由调用方按 status_code 处理。
            raise

        payload = _decode_json_body(response.json())
        items = _parse_list_payload(payload)
        server_time = parse_server_time(payload.get("server_time"))
        self._connectivity.mark_reachable(server_time=server_time)

        with self._lock:
            self._cached_list = list(items)
        return list(items)

    def cached_active_list(self) -> list[ActiveAccountListItem]:
        """返回最近一次成功刷新的清单。

        从未刷新成功时返回空列表（list copy，调用方修改不会影响内部状态）。
        """

        with self._lock:
            return list(self._cached_list)

    # ------------------------------------------------------------------ #
    # 接口 B：按需取详情                                                   #
    # ------------------------------------------------------------------ #

    def get_active_account_detail(self, account_id: int) -> ActiveAccountDetail | None:
        """调用接口 B 取该账号的密码与自动任务。

        - 命中且账号在 Active_Pool：返回 :class:`ActiveAccountDetail`。
        - HTTP 404 / 服务端响应中 reason='account_not_found'：返回 ``None``。
          服务端在 Requirement 6.5、7.6 下对「不存在 / 已被迁出活跃池 / 软删」
          一律返回相同 404 响应，本方法据此把这一类失败统一翻译为「目前不可用」。
        - 其他 4xx（含 422）：抛 :class:`ProtocolError`。
        - 网络错误 / 5xx / 401 / 426：标记 connectivity 不可达并重抛。
        - 429：直接重抛，由调用方按 Retry-After 退避后再次尝试。

        本方法刻意不缓存返回结果：密码字段只在调用方持有，仓库内部不留副本。
        """

        if not isinstance(account_id, int) or account_id <= 0:
            raise ValueError("account_id 必须是正整数")
        path = _DETAIL_PATH_TEMPLATE.format(account_id=account_id)
        try:
            response = self._client.get(path)
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
            if exc.status_code == 404:
                # 服务端在响应（说明可达），仅说明该账号目前不在 Active_Pool。
                self._connectivity.mark_reachable()
                return None
            # 其他 4xx：服务端响应已收到，不改 connectivity 不可达标志；
            # 让调用方按 status_code 与 body.reason 自行翻译。
            self._connectivity.mark_reachable()
            raise

        payload = _decode_json_body(response.json())
        server_time = parse_server_time(payload.get("server_time"))
        self._connectivity.mark_reachable(server_time=server_time)
        return _parse_detail_payload(payload, server_time=server_time)


# --------------------------------------------------------------------------- #
# Parsing helpers                                                              #
# --------------------------------------------------------------------------- #


def _decode_json_body(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    raise ServerSyncError(f"服务端响应不是 JSON 对象：{type(value).__name__}")


def _parse_list_payload(payload: dict[str, Any]) -> list[ActiveAccountListItem]:
    raw_items = payload.get("accounts") or []
    if not isinstance(raw_items, list):
        raise ServerSyncError("接口 A 响应中的 accounts 字段不是数组")
    parsed: list[ActiveAccountListItem] = []
    for index, raw in enumerate(raw_items):
        if not isinstance(raw, dict):
            logger.warning(
                "接口 A 响应第 %d 条不是对象，已跳过：%r", index, raw
            )
            continue
        try:
            parsed.append(
                ActiveAccountListItem(
                    account_id=_require_int(raw, "account_id"),
                    student_id=_require_str(raw, "student_id"),
                    display_name=_optional_str(raw, "display_name"),
                    pool_status=_optional_str(raw, "pool_status") or "active",
                    updated_at=parse_server_time(raw.get("updated_at")),
                )
            )
        except (KeyError, TypeError, ValueError) as exc:
            logger.warning(
                "接口 A 响应第 %d 条字段非法，已跳过：%s", index, exc
            )
    return parsed


def _parse_detail_payload(
    payload: dict[str, Any],
    *,
    server_time: datetime | None,
) -> ActiveAccountDetail:
    account = payload.get("account")
    if not isinstance(account, dict):
        raise ServerSyncError("接口 B 响应缺少 account 对象")
    raw_tasks = payload.get("automation_tasks") or []
    if not isinstance(raw_tasks, list):
        raise ServerSyncError("接口 B 响应中的 automation_tasks 字段不是数组")

    return ActiveAccountDetail(
        account_id=_require_int(account, "account_id"),
        student_id=_require_str(account, "student_id"),
        display_name=_optional_str(account, "display_name"),
        password=_require_str(account, "password"),
        revision=_optional_int(account, "revision", default=0),
        automation_tasks=[_parse_automation_task(t) for t in raw_tasks if isinstance(t, dict)],
        server_time=server_time,
    )


def _parse_automation_task(raw: dict[str, Any]) -> AutomationTask:
    return AutomationTask(
        task_id=_require_int(raw, "task_id"),
        room_name=_optional_str(raw, "room_name"),
        seat_number=_optional_str(raw, "seat_number"),
        mode=_optional_str(raw, "mode"),
        custom_windows=[
            _parse_custom_window(w)
            for w in (raw.get("custom_windows") or [])
            if isinstance(w, dict)
        ],
        enabled=bool(raw.get("enabled", False)),
        revision=_optional_int(raw, "revision", default=0),
        updated_at=parse_server_time(raw.get("updated_at")),
    )


def _parse_custom_window(raw: dict[str, Any]) -> CustomWindow:
    return CustomWindow(
        date=_optional_str(raw, "date"),
        start_hour=_optional_int(raw, "start_hour", default=0),
        end_hour=_optional_int(raw, "end_hour", default=0),
    )


def _require_int(payload: dict[str, Any], key: str) -> int:
    value = payload.get(key)
    if isinstance(value, bool):
        raise ValueError(f"字段 {key} 不能是 bool")
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().lstrip("-").isdigit():
        return int(value)
    raise ValueError(f"字段 {key} 缺失或不是整数：{value!r}")


def _require_str(payload: dict[str, Any], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str):
        raise ValueError(f"字段 {key} 缺失或不是字符串：{value!r}")
    return value


def _optional_str(payload: dict[str, Any], key: str) -> str:
    value = payload.get(key)
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return str(value)


def _optional_int(payload: dict[str, Any], key: str, *, default: int) -> int:
    value = payload.get(key)
    if value is None:
        return default
    if isinstance(value, bool):
        return default
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().lstrip("-").isdigit():
        return int(value)
    return default


def _format_failure(exc: ServerSyncError) -> str:
    text = str(exc).strip()
    return text or exc.__class__.__name__


__all__ = [
    "ActiveAccountDetail",
    "ActiveAccountListItem",
    "ActivePoolRepository",
    "AutomationTask",
    "CustomWindow",
]
