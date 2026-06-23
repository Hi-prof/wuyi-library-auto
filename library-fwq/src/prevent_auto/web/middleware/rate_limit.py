"""Active_Account_Detail_API 限频依赖。

对应 spec ``account-pool-tri-sync`` 的 task 8.1 与 Requirement 7-Q3：

* 单 ``(token_id, account_id)`` 在 60 秒滑窗内调用 ``Active_Account_Detail_API``
  不超过 ``ACCOUNT_POOL_DETAIL_RATE_LIMIT_PER_MINUTE`` 次（默认 6）。
* 内存 LRU 实现，无外部依赖；服务重启后计数自然清零（与 design「轻量保护，不抗
  DDoS」的取舍一致）。
* 超过限额返回 ``429 {"reason":"rate_limited","retry_after":<int>}``，``retry_after``
  以秒为单位，等于「滑窗内最早一次命中再过多久」。

为了与既有 FastAPI 路由风格一致，限频实现成 **依赖**（``Depends(require_detail_rate_limit)``）：

* 限频对象 :class:`DetailRateLimiter` 由 :func:`install_detail_rate_limiter` 在
  ``create_app`` 阶段挂到 ``app.state``。
* 依赖函数 :func:`require_detail_rate_limit` 复用 ``request.state`` 上由鉴权依赖
  写入的 :class:`ClientApiToken` 记录，从中取 ``token_id``；这样限频依赖天然在
  鉴权依赖之后才会被调用。
"""

from __future__ import annotations

import threading
from collections import OrderedDict, deque
from dataclasses import dataclass
from typing import Callable

from fastapi import HTTPException, Path, Request, status

from prevent_auto.repositories.client_api_tokens import ClientApiToken
from prevent_auto.web.middleware.auth import RESOLVED_TOKEN_REQUEST_ATTR


#: ``app.state`` 上挂载限频器用的属性名。
DETAIL_RATE_LIMIT_STATE_ATTR = "account_pool_detail_rate_limiter"

#: 滑窗长度（秒）。design 默认 60s，不暴露环境变量；调用方需要更小窗口可以
#: 自行构造另一个 :class:`DetailRateLimiter` 实例。
DEFAULT_WINDOW_SECONDS = 60

#: LRU 容量上限——超过则 evict 最旧的 ``(token_id, account_id)`` 计数。
DEFAULT_MAX_TRACKED_KEYS = 4096


@dataclass
class _CounterEntry:
    """单个 ``(token_id, account_id)`` 的滑窗命中记录。

    使用 ``deque`` 存命中时刻的单调时钟值；``deque`` 头部是最早命中。每次访问
    都先清掉过期记录，再检查长度是否越限。
    """

    timestamps: deque[float]


class DetailRateLimiter:
    """``(token_id, account_id)`` 维度的 60 秒滑窗限频器。

    ``limit_per_minute`` 由 ``settings.account_pool_detail_rate_limit_per_minute``
    注入；``clock`` 默认使用 :func:`time.monotonic`，可在测试中替换为可控时钟。

    线程安全：所有可变状态在 ``threading.Lock`` 下访问。FastAPI / uvicorn 默认
    单进程，依赖在线程池里跑，加锁开销可忽略。
    """

    def __init__(
        self,
        *,
        limit_per_minute: int,
        clock: Callable[[], float] | None = None,
        window_seconds: int = DEFAULT_WINDOW_SECONDS,
        max_tracked_keys: int = DEFAULT_MAX_TRACKED_KEYS,
    ) -> None:
        if limit_per_minute <= 0:
            raise ValueError("limit_per_minute 必须为正整数")
        if window_seconds <= 0:
            raise ValueError("window_seconds 必须为正整数")
        if max_tracked_keys <= 0:
            raise ValueError("max_tracked_keys 必须为正整数")
        self._limit = int(limit_per_minute)
        self._window = float(window_seconds)
        self._max_tracked_keys = int(max_tracked_keys)
        self._clock = clock if clock is not None else _default_clock
        self._lock = threading.Lock()
        self._counters: OrderedDict[tuple[int, int], _CounterEntry] = OrderedDict()

    @property
    def limit_per_minute(self) -> int:
        """返回当前生效的每分钟上限，方便测试断言。"""

        return self._limit

    def acquire(self, token_id: int, account_id: int) -> int:
        """记录一次命中；返回 0 表示放行，正整数表示需要等待的秒数。

        当返回正整数时，调用方负责把它转换成 HTTP ``429`` 响应。``retry_after``
        以秒向上取整：滑窗中最早一次命中再过多久会失效，就让客户端等多久。
        """

        now = self._clock()
        cutoff = now - self._window
        key = (int(token_id), int(account_id))
        with self._lock:
            entry = self._counters.get(key)
            if entry is None:
                entry = _CounterEntry(timestamps=deque())
                self._counters[key] = entry
            timestamps = entry.timestamps
            while timestamps and timestamps[0] <= cutoff:
                timestamps.popleft()
            if len(timestamps) >= self._limit:
                earliest = timestamps[0]
                retry_after = max(1, int(earliest + self._window - now + 0.999))
                # 仍然把当前 key 移到 LRU 末尾，避免被立刻 evict
                self._counters.move_to_end(key)
                return retry_after
            timestamps.append(now)
            self._counters.move_to_end(key)
            self._evict_excess_locked()
            return 0

    def _evict_excess_locked(self) -> None:
        """LRU 上限溢出时，丢掉最旧的若干条计数。"""

        while len(self._counters) > self._max_tracked_keys:
            self._counters.popitem(last=False)


def _default_clock() -> float:
    import time as _time

    return _time.monotonic()


def install_detail_rate_limiter(
    app,
    *,
    limiter: DetailRateLimiter,
) -> DetailRateLimiter:
    """把限频器挂到 ``app.state``，返回挂载结果。

    与 :func:`install_auth_dependency_state` 对称，便于 ``create_app`` 在装配阶段
    集中注册中间件状态。
    """

    setattr(app.state, DETAIL_RATE_LIMIT_STATE_ATTR, limiter)
    return limiter


def require_detail_rate_limit(
    request: Request,
    account_id: int = Path(..., description="目标账号 ID（路径参数）"),
) -> None:
    """FastAPI 依赖：按 ``(token_id, account_id)`` 维度限频。

    依赖在路由处理函数前执行，命中限额时抛 ``HTTPException(429, ...)``。
    依赖必须排在 :func:`require_bearer_token` 之后，由它把已解析的
    :class:`ClientApiToken` 写到 ``request.state``。
    """

    token = getattr(request.state, RESOLVED_TOKEN_REQUEST_ATTR, None)
    if not isinstance(token, ClientApiToken):  # pragma: no cover - 启动期失误
        raise RuntimeError(
            "限频依赖必须排在 require_bearer_token 之后，"
            "未发现 request.state.account_pool_token",
        )
    limiter = getattr(request.app.state, DETAIL_RATE_LIMIT_STATE_ATTR, None)
    if not isinstance(limiter, DetailRateLimiter):  # pragma: no cover - 启动期失误
        raise RuntimeError(
            "未注册 account_pool_detail_rate_limiter：请在 create_app 中调用 "
            "install_detail_rate_limiter",
        )
    retry_after = limiter.acquire(token.id, int(account_id))
    if retry_after > 0:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={"reason": "rate_limited", "retry_after": retry_after},
            headers={"Retry-After": str(retry_after)},
        )


__all__ = [
    "DEFAULT_MAX_TRACKED_KEYS",
    "DEFAULT_WINDOW_SECONDS",
    "DETAIL_RATE_LIMIT_STATE_ATTR",
    "DetailRateLimiter",
    "install_detail_rate_limiter",
    "require_detail_rate_limit",
]
