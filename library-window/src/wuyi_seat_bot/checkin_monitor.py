# 撤回 spec account-pool-tri-sync 11.4 中的 ConnectivityGate 守卫；本地执行不再依赖服务端可达性
from __future__ import annotations

import threading
import time
from typing import Callable


class PeriodicAccountCheckinMonitor:
    def __init__(
        self,
        *,
        list_account_names: Callable[[], tuple[str, ...]],
        execute_checkin: Callable[[str], bool],
        poll_interval_seconds: float = 1800.0,
    ) -> None:
        self.list_account_names = list_account_names
        self.execute_checkin = execute_checkin
        self.poll_interval_seconds = poll_interval_seconds
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._signed_in_accounts: set[str] = set()
        self._signed_in_day: int = -1

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._signed_in_accounts.clear()
        self._signed_in_day = -1
        self._thread = threading.Thread(
            target=self._run_loop,
            name="periodic-account-checkin-monitor",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=3)

    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def reset_signed_in(self) -> None:
        """清除已签到账号记录，下一轮会重新检查所有账号。"""
        self._signed_in_accounts.clear()

    def run_cycle_once(self) -> None:
        today = time.localtime().tm_yday
        if today != self._signed_in_day:
            self._signed_in_accounts.clear()
            self._signed_in_day = today

        for account_name in self.list_account_names():
            if account_name in self._signed_in_accounts:
                continue
            try:
                signed_in = self.execute_checkin(account_name)
            except Exception:  # noqa: BLE001
                continue
            if signed_in:
                self._signed_in_accounts.add(account_name)

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            self.run_cycle_once()
            if self._stop_event.wait(self.poll_interval_seconds):
                return
