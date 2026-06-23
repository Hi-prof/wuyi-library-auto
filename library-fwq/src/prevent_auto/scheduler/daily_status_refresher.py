from __future__ import annotations

import threading
import time as time_module
from datetime import date, datetime, time as dtime
from typing import Callable

from wuyi_seat_bot.seat_api import SHANGHAI_TZ


class DailyStatusRefresher:
    """每天到达指定时间后，对所有启用账号执行一次状态检测。

    通过 ``run_due_refresh_once`` 触发，由后台 worker 周期性调用。
    使用内存中的 ``_last_run_date`` 防止同一天重复刷新。
    """

    def __init__(
        self,
        *,
        account_service,
        monitor_loop,
        auto_reservation_service=None,
        refresh_time: dtime,
        batch_size: int = 3,
        batch_delay_seconds: int = 3,
        sleep_func: Callable[[float], None] = time_module.sleep,
    ) -> None:
        if batch_size <= 0:
            raise ValueError("batch_size 必须大于 0")
        if batch_delay_seconds < 0:
            raise ValueError("batch_delay_seconds 不能为负数")
        self.account_service = account_service
        self.monitor_loop = monitor_loop
        self.auto_reservation_service = auto_reservation_service
        self.refresh_time = refresh_time
        self.batch_size = batch_size
        self.batch_delay_seconds = batch_delay_seconds
        self._sleep = sleep_func
        self._last_run_date: date | None = None
        self._lock = threading.Lock()

    @property
    def last_run_date(self) -> date | None:
        return self._last_run_date

    def run_due_refresh_once(self, current_time: datetime) -> bool:
        localized = _localize(current_time)
        today = localized.date()
        with self._lock:
            if self._last_run_date == today:
                return False
            if localized.time() < self.refresh_time:
                return False
            self._last_run_date = today
        self._refresh_all_enabled_accounts(localized)
        return True

    def _refresh_all_enabled_accounts(self, current_time: datetime) -> None:
        accounts = self.account_service.list_enabled_accounts()
        for index, account in enumerate(accounts):
            if index > 0 and index % self.batch_size == 0:
                self._sleep(self.batch_delay_seconds)
            try:
                self.monitor_loop.run_account_once(account.id)
                if self.auto_reservation_service is not None:
                    self.auto_reservation_service.run_account_once(
                        account.id,
                        current_time=current_time,
                    )
            except Exception:  # noqa: BLE001
                # MonitorLoop 内部已经处理单账号异常，这里再兜底，
                # 避免单个账号异常打断剩余账号的刷新。
                continue


def _localize(current_time: datetime) -> datetime:
    if current_time.tzinfo is None:
        return current_time.replace(tzinfo=SHANGHAI_TZ)
    return current_time.astimezone(SHANGHAI_TZ)
