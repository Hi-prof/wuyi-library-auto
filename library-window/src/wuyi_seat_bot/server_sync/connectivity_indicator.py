# 撤回 spec account-pool-tri-sync 11.4 中的 ConnectivityGate 守卫；本地执行不再依赖服务端可达性
"""同步按钮可用性与连接状态三态指示器。

本模块仅用作：

- 「同步按钮可用性」三态计算（enabled / disabled_unconfigured / disabled_unreachable）。
- 连接状态指示（UI 上同步按钮旁的文案/图标来源）。

**不** 在本模块内执行任何带域名解析或 socket 连接的「主动探测」；
只依赖最近一次 sync 调用（由 ``active_pool_repository`` / ``client.py`` 等网络
层模块执行后回调 ``record_sync_success`` / ``record_sync_failure``）的成功/失败结果
作为状态来源。

设计依据：

- Requirement 12.3：本地执行不依赖服务端可达性。
- Requirement 12.7：Local_Only_Mode 下仅在「同步 / 上传到服务端」入口显示
  「未配置服务端」提示，不置灰本地执行入口。
- Requirement 13.9：Manual_Sync_Action 入口附近显示「服务端可达 / 不可达 / 未配置」
  三态指示；该指示 **不** 被解释为对本地执行流程的拒绝。

线程安全：使用 ``threading.Lock`` 保护内部状态，便于供调度线程与 UI 主线程共享。

关于 ``ServerUnreachable`` 异常：

    该异常定义在 ``client.py`` 中，**仅** 供 ``client.py`` /
    ``active_pool_repository.py`` / ``automation_task_uploader.py`` /
    ``blacklist_reporter.py`` 内部网络层使用。调度器 / UI 层 / 自动任务入口 /
    登录刷新 / 座位监控 **不得** 捕获该异常用于「拒绝执行 / 跳过本轮 /
    置灰本地入口」。
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Literal


# --------------------------------------------------------------------------- #
# 三态类型                                                                      #
# --------------------------------------------------------------------------- #

SyncButtonState = Literal["enabled", "disabled_unconfigured", "disabled_unreachable"]
"""同步按钮可用性三态。

- ``enabled``：Server_Sync_Config 已配置且最近一次同步成功（或从未失败），
  用户可点击 Manual_Sync_Action。
- ``disabled_unconfigured``：Server_Sync_Config 未配置，同步按钮置灰，
  文案「未配置服务端」。
- ``disabled_unreachable``：Server_Sync_Config 已配置但最近一次同步失败
  （网络错误 / 5xx / 401 / 426），同步按钮置灰，文案「服务端不可达」。
"""


# --------------------------------------------------------------------------- #
# 默认常量                                                                      #
# --------------------------------------------------------------------------- #

_DEFAULT_REACHABLE_TTL_SECONDS: float = 300.0
"""成功响应有效期（秒）。

超过这个时长仍没有新的成功响应时，状态退化为「不可达」。
与 Pool_Reaper_Job 的 5 分钟周期一致。
"""


def _utc_now() -> datetime:
    return datetime.now(tz=timezone.utc)


# --------------------------------------------------------------------------- #
# 内部状态                                                                      #
# --------------------------------------------------------------------------- #

@dataclass
class _IndicatorState:
    last_success_at: datetime | None = None
    last_failure_at: datetime | None = None
    last_failure_reason: str = ""
    explicitly_unreachable: bool = False
    _lock: threading.Lock = field(default_factory=threading.Lock)


# --------------------------------------------------------------------------- #
# ConnectivityIndicator                                                         #
# --------------------------------------------------------------------------- #

class ConnectivityIndicator:
    """同步按钮可用性与连接状态三态指示器。

    本类 **不** 发起任何网络请求。状态完全由外部调用方（sync 流程中的
    网络层）通过 ``record_sync_success()`` / ``record_sync_failure()`` 注入。

    使用方式：

    .. code-block:: python

        indicator = ConnectivityIndicator(
            is_configured_fn=lambda: server_sync_config.is_configured(),
        )

        # 在 sync 网络调用成功后：
        indicator.record_sync_success()

        # 在 sync 网络调用失败后：
        indicator.record_sync_failure("connect timeout")

        # UI 读取同步按钮状态：
        state = indicator.compute_sync_button_state()
        # -> "enabled" | "disabled_unconfigured" | "disabled_unreachable"
    """

    def __init__(
        self,
        *,
        is_configured_fn: Callable[[], bool],
        reachable_ttl_seconds: float = _DEFAULT_REACHABLE_TTL_SECONDS,
        clock: Callable[[], datetime] = _utc_now,
    ) -> None:
        """
        Args:
            is_configured_fn: 返回 Server_Sync_Config 是否已配置的回调。
                当 ``base_url`` 与 ``bearer_token`` 均非空时返回 True。
                本类不持有配置引用，只在需要时调用此函数。
            reachable_ttl_seconds: 成功响应有效期。超过此时长未收到新成功
                响应则视为不可达。必须为正数。
            clock: UTC 时钟注入点，便于测试。
        """
        if reachable_ttl_seconds <= 0:
            raise ValueError("reachable_ttl_seconds 必须为正数")
        self._is_configured_fn = is_configured_fn
        self._ttl = timedelta(seconds=reachable_ttl_seconds)
        self._clock = clock
        self._state = _IndicatorState()

    # ------------------------------------------------------------------ #
    # 状态变更（由网络层在 sync 调用后调用）                                       #
    # ------------------------------------------------------------------ #

    def record_sync_success(self) -> None:
        """记录一次 sync 调用成功。

        由 ``active_pool_repository`` / ``client.py`` 在接口 A / B 返回
        200 后调用。不会触发任何网络请求。
        """
        now = self._clock()
        with self._state._lock:
            self._state.last_success_at = now
            self._state.explicitly_unreachable = False
            self._state.last_failure_reason = ""

    def record_sync_failure(self, reason: str = "") -> None:
        """记录一次 sync 调用失败。

        由网络层在遇到 ``ServerUnreachable`` / ``NetworkError`` /
        ``ServerError`` / ``Unauthorized`` / ``HttpsRequired`` 等异常后调用。
        不会触发任何网络请求。

        Args:
            reason: 失败的简短原因（用于 UI toast 与日志）。
        """
        now = self._clock()
        text = (reason or "").strip() or "服务端不可达"
        with self._state._lock:
            self._state.last_failure_at = now
            self._state.last_failure_reason = text
            self._state.explicitly_unreachable = True

    # ------------------------------------------------------------------ #
    # 三态计算                                                              #
    # ------------------------------------------------------------------ #

    def compute_sync_button_state(self) -> SyncButtonState:
        """计算同步按钮可用性三态。

        判定逻辑：

        1. 若 Server_Sync_Config 未配置 → ``disabled_unconfigured``。
        2. 若 Server_Sync_Config 已配置，但最近一次 sync 显式失败
           或从未成功且已超 TTL → ``disabled_unreachable``。
        3. 若 Server_Sync_Config 已配置且最近一次 sync 成功（在 TTL 内）
           → ``enabled``。
        4. 若 Server_Sync_Config 已配置但从未调用过 sync（首次启动状态）
           → ``enabled``（允许用户首次点击尝试）。

        Returns:
            同步按钮状态三态字面量。
        """
        if not self._is_configured_fn():
            return "disabled_unconfigured"

        now = self._clock()
        with self._state._lock:
            # 显式标记为不可达
            if self._state.explicitly_unreachable:
                return "disabled_unreachable"
            # 从未有过成功记录：首次启动允许用户尝试
            if self._state.last_success_at is None:
                # 若也从未有过失败记录，则处于初始化状态，允许用户点击
                if self._state.last_failure_at is None:
                    return "enabled"
                # 有失败但无成功 → 不可达
                return "disabled_unreachable"
            # 有成功记录但已超 TTL → 不可达
            if (now - self._state.last_success_at) > self._ttl:
                return "disabled_unreachable"
            # 有效期内的成功记录 → 可用
            return "enabled"

    # ------------------------------------------------------------------ #
    # 辅助查询                                                              #
    # ------------------------------------------------------------------ #

    def is_reachable(self) -> bool:
        """返回当前是否视作服务端可达。

        便捷方法，等价于 ``compute_sync_button_state() == "enabled"`` 但
        不检查 is_configured（假定已配置时才调用此方法）。

        **仅供** 同步按钮可用性指示使用，**不得** 用于阻塞本地执行入口。
        """
        now = self._clock()
        with self._state._lock:
            if self._state.explicitly_unreachable:
                return False
            last = self._state.last_success_at
            if last is None:
                # 从未成功但也从未失败 → 视为「尚可」(首次启动)
                return self._state.last_failure_at is None
            return (now - last) <= self._ttl

    def last_failure_reason(self) -> str:
        """返回最近一次失败的简短原因（可为空串）。

        用于 UI 在 ``disabled_unreachable`` 状态下显示的错误提示文案。
        """
        with self._state._lock:
            return self._state.last_failure_reason

    def reset(self) -> None:
        """重置内部状态。

        用于配置变更后（如用户修改了 base_url / bearer_token）清除旧状态，
        让下一次 sync 调用重新决定可达性。
        """
        with self._state._lock:
            self._state.last_success_at = None
            self._state.last_failure_at = None
            self._state.last_failure_reason = ""
            self._state.explicitly_unreachable = False


# --------------------------------------------------------------------------- #
# ServerConnectivity（可达性追踪 + 时钟对齐）                                       #
# --------------------------------------------------------------------------- #


_DEFAULT_REACHABLE_TTL_SECONDS_LEGACY: float = 300.0
"""``ServerConnectivity`` 的默认「成功响应有效期」：5 分钟。

与 :data:`_DEFAULT_REACHABLE_TTL_SECONDS` 取值一致；保留独立别名以便 server_sync
内部模块可以分别调整 ``ConnectivityIndicator`` 与 ``ServerConnectivity`` 的 TTL。
"""


@dataclass(frozen=True)
class ConnectivitySnapshot:
    """一次状态快照，便于 UI / 日志读取。

    Attributes:
        reachable: 当前是否视作可达。
        last_success_at: 最近一次成功响应的本地时间（UTC）。
        last_failure_at: 最近一次失败时间（UTC）。
        last_failure_reason: 最近一次失败的简短原因（用于 toast 与日志）。
        last_server_time: 服务端最近一次响应中携带的 ``server_time``。
        clock_skew_seconds: 服务端 - 本地的时钟差（秒）；正数表示服务端更晚。
    """

    reachable: bool
    last_success_at: datetime | None
    last_failure_at: datetime | None
    last_failure_reason: str
    last_server_time: datetime | None
    clock_skew_seconds: float | None


@dataclass
class _ServerConnectivityState:
    last_success_at: datetime | None = None
    last_failure_at: datetime | None = None
    last_failure_reason: str = ""
    last_server_time: datetime | None = None
    clock_skew_seconds: float | None = None
    explicitly_unreachable: bool = False
    last_status_code: int | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock)


class ServerConnectivity:
    """跟踪服务端可达性与服务端时钟。

    仅用作「同步按钮可用性 + 连接状态指示」的状态来源（Manual_Sync_Action 与
    server_sync 上行模块按需读取），调度器 / UI 层 / 自动任务入口 / 登录刷新 /
    座位监控均 **不** 据此暂停或拒绝本地任务（Requirement 12.3 / 12.4 / 12.6 /
    12.7 / 13.9）。

    使用方式：

    .. code-block:: python

        connectivity = ServerConnectivity()
        try:
            response = client.get("/api/v1/active-accounts")
            connectivity.mark_reachable(server_time=parse_server_time(response))
        except ServerUnreachable as exc:
            connectivity.mark_unreachable(str(exc))
            raise

        # 仅供 UI 同步按钮 / 状态指示读取，**不** 用于阻塞本地执行入口。
        if not connectivity.is_reachable():
            ...
    """

    def __init__(
        self,
        *,
        reachable_ttl_seconds: float = _DEFAULT_REACHABLE_TTL_SECONDS_LEGACY,
        clock: Callable[[], datetime] = _utc_now,
    ) -> None:
        if reachable_ttl_seconds <= 0:
            raise ValueError("reachable_ttl_seconds 必须为正数")
        self._ttl = timedelta(seconds=reachable_ttl_seconds)
        self._clock = clock
        self._state = _ServerConnectivityState()

    # ------------------------------------------------------------------ #
    # 状态变更                                                              #
    # ------------------------------------------------------------------ #

    def mark_reachable(self, *, server_time: datetime | None = None) -> None:
        """记录一次成功响应。

        Args:
            server_time: 服务端响应中携带的时间戳，若提供则用于计算时钟差。
        """

        now = self._clock()
        skew: float | None = None
        normalized_server_time: datetime | None = None
        if server_time is not None:
            normalized_server_time = _ensure_utc(server_time)
            skew = (normalized_server_time - now).total_seconds()
        with self._state._lock:
            self._state.last_success_at = now
            self._state.explicitly_unreachable = False
            self._state.last_failure_reason = ""
            self._state.last_status_code = None
            if normalized_server_time is not None:
                self._state.last_server_time = normalized_server_time
                self._state.clock_skew_seconds = skew

    def mark_unreachable(
        self,
        reason: str,
        *,
        status_code: int | None = None,
    ) -> None:
        """记录一次失败响应（网络错误 / 超时 / 5xx / 鉴权失败等）。

        ``status_code`` 仅做记录用，``is_reachable()`` 直接看 explicit 标志。
        """

        now = self._clock()
        text = (reason or "").strip() or "服务端不可达"
        with self._state._lock:
            self._state.last_failure_at = now
            self._state.last_failure_reason = text
            self._state.explicitly_unreachable = True
            self._state.last_status_code = status_code

    # ------------------------------------------------------------------ #
    # 状态查询                                                              #
    # ------------------------------------------------------------------ #

    def is_reachable(self) -> bool:
        """返回当前是否视作服务端可达。

        判定规则：

        1. 若最近一次操作显式标记为不可达 → False。
        2. 若从未记录过成功响应 → False。
        3. 若最近一次成功响应距今超过 ``reachable_ttl_seconds`` → False。
        4. 否则 → True。
        """

        now = self._clock()
        with self._state._lock:
            if self._state.explicitly_unreachable:
                return False
            last = self._state.last_success_at
            if last is None:
                return False
            return (now - last) <= self._ttl

    def last_known_server_time(self) -> datetime | None:
        """返回服务端最近一次响应携带的 ``server_time``（UTC，可为 None）。"""

        with self._state._lock:
            return self._state.last_server_time

    def clock_skew_seconds(self) -> float | None:
        """返回服务端 - 本地的时钟差（秒）；尚未对齐时为 None。"""

        with self._state._lock:
            return self._state.clock_skew_seconds

    def estimated_server_time(self) -> datetime | None:
        """基于最近一次时钟差估算「现在的服务端时间」。

        若从未对齐过 ``server_time`` 则返回 None；
        调用方据此判断是否使用客户端本地时间作为兜底。
        """

        with self._state._lock:
            skew = self._state.clock_skew_seconds
        if skew is None:
            return None
        return self._clock() + timedelta(seconds=skew)

    def snapshot(self) -> ConnectivitySnapshot:
        """返回当前状态的不可变快照。"""

        now = self._clock()
        with self._state._lock:
            reachable = (
                not self._state.explicitly_unreachable
                and self._state.last_success_at is not None
                and (now - self._state.last_success_at) <= self._ttl
            )
            return ConnectivitySnapshot(
                reachable=reachable,
                last_success_at=self._state.last_success_at,
                last_failure_at=self._state.last_failure_at,
                last_failure_reason=self._state.last_failure_reason,
                last_server_time=self._state.last_server_time,
                clock_skew_seconds=self._state.clock_skew_seconds,
            )


def parse_server_time(value: Any) -> datetime | None:
    """把服务端响应的 ``server_time`` 字段解析为 UTC ``datetime``。

    服务端按 ISO 8601 输出（design.md「Active_Account_Sync_API」），
    形如 ``"2026-04-26T08:30:00Z"`` 或 ``"2026-04-26T08:30:00+00:00"``；
    无法解析时返回 ``None``，由调用方决定是否记录警告。
    """

    if value is None:
        return None
    if isinstance(value, datetime):
        return _ensure_utc(value)
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    # ``fromisoformat`` 在 3.11+ 支持 ``Z`` 后缀；为兼容 3.10 这里手动替换。
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return _ensure_utc(parsed)


def _ensure_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


__all__ = [
    "ConnectivityIndicator",
    "ConnectivitySnapshot",
    "ServerConnectivity",
    "SyncButtonState",
    "parse_server_time",
]
