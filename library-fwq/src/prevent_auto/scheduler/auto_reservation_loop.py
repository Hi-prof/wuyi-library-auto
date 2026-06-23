"""持续滚动预约调度器。

每 ``interval_seconds`` 触发一次：

1. 调用 :class:`MonitorLoop` 刷新所有启用账号的预约视图，把
   ``booking_snapshots`` 缓存写到最新；
2. 调用 :meth:`AutoReservationService.run_rolling_once` 把今日及未来 N 天的
   缺失时段补齐。

调度器本身不开线程；由 :func:`prevent_auto.web.runtime.start_background_workers`
里的 ``_worker_loop`` 周期性调用 :meth:`run_due_once`，与
``cleanup_runtime_data`` / ``daily_status_refresher`` 的接入方式保持一致。
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

from prevent_auto.scheduler.monitor_loop import MonitorLoop
from prevent_auto.services.auto_reservation_service import (
    AutoReservationRunResult,
    AutoReservationService,
)


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class AutoReservationLoopTickResult:
    """单次 tick 的汇总，方便测试断言。"""

    ran: bool
    triggered_at: datetime | None = None
    run_result: AutoReservationRunResult | None = None


class AutoReservationLoop:
    """周期性触发持续预约扫描；线程安全的"到点才跑"调度器。"""

    def __init__(
        self,
        *,
        monitor_loop: MonitorLoop,
        auto_reservation_service: AutoReservationService,
        interval_seconds: int,
        rolling_days_ahead: int | None = None,
    ) -> None:
        if interval_seconds <= 0:
            raise ValueError("interval_seconds 必须大于 0")
        if rolling_days_ahead is not None and rolling_days_ahead <= 0:
            raise ValueError("rolling_days_ahead 必须大于 0")
        self.monitor_loop = monitor_loop
        self.auto_reservation_service = auto_reservation_service
        self.interval_seconds = interval_seconds
        self.rolling_days_ahead = rolling_days_ahead
        self._lock = threading.Lock()
        self._last_run_at: datetime | None = None

    @property
    def last_run_at(self) -> datetime | None:
        return self._last_run_at

    def run_due_once(self, current_time: datetime) -> AutoReservationLoopTickResult:
        """到点就跑一次；未到时间直接返回 ``ran=False``。

        ``interval_seconds`` 之前的 tick 仅刷新内部 ``_last_run_at`` 引用计数；
        到点之后会先跑 ``MonitorLoop.run_cycle_once``（异常吞掉，由
        :class:`MonitorLoop` 内部按账号兜底），再触发滚动预约。
        """

        with self._lock:
            if self._last_run_at is not None:
                elapsed = (current_time - self._last_run_at).total_seconds()
                if elapsed < self.interval_seconds:
                    return AutoReservationLoopTickResult(ran=False)
            self._last_run_at = current_time

        try:
            self.monitor_loop.run_cycle_once()
        except Exception:  # noqa: BLE001
            # MonitorLoop 内部按账号 try/except；这里再兜一层，防止单次失败
            # 让滚动预约也跟着跳过本轮。
            logger.exception("AutoReservationLoop: monitor cycle failed")

        try:
            run_result = self.auto_reservation_service.run_rolling_once(
                current_time=current_time,
                days_ahead=self.rolling_days_ahead,
            )
        except Exception:  # noqa: BLE001
            logger.exception("AutoReservationLoop: rolling reservation failed")
            return AutoReservationLoopTickResult(
                ran=True,
                triggered_at=current_time,
                run_result=None,
            )

        return AutoReservationLoopTickResult(
            ran=True,
            triggered_at=current_time,
            run_result=run_result,
        )

    def reset(self) -> None:
        """测试用：清空"上次执行时间"，让下一次 ``run_due_once`` 立刻命中。"""

        with self._lock:
            self._last_run_at = None


__all__ = [
    "AutoReservationLoop",
    "AutoReservationLoopTickResult",
]
