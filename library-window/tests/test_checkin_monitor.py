import threading
import unittest

from wuyi_seat_bot.checkin_monitor import PeriodicAccountCheckinMonitor


class PeriodicAccountCheckinMonitorTestCase(unittest.TestCase):
    def test_run_cycle_checks_each_account(self) -> None:
        calls: list[str] = []

        def execute_checkin(account_name: str) -> bool:
            calls.append(account_name)
            return False

        monitor = PeriodicAccountCheckinMonitor(
            list_account_names=lambda: ("主号", "室友"),
            execute_checkin=execute_checkin,
        )

        monitor.run_cycle_once()

        self.assertEqual(calls, ["主号", "室友"])

    def test_run_cycle_continues_after_single_account_failure(self) -> None:
        calls: list[str] = []

        def execute_checkin(account_name: str) -> bool:
            calls.append(account_name)
            if account_name == "主号":
                raise RuntimeError("蓝牙扫描失败")
            return False

        monitor = PeriodicAccountCheckinMonitor(
            list_account_names=lambda: ("主号", "室友"),
            execute_checkin=execute_checkin,
        )

        monitor.run_cycle_once()

        self.assertEqual(calls, ["主号", "室友"])

    def test_start_runs_first_cycle_immediately(self) -> None:
        calls: list[str] = []
        first_run_event = threading.Event()

        def execute_checkin(account_name: str) -> bool:
            calls.append(account_name)
            first_run_event.set()
            return False

        monitor = PeriodicAccountCheckinMonitor(
            list_account_names=lambda: ("主号",),
            execute_checkin=execute_checkin,
            poll_interval_seconds=3600,
        )
        self.addCleanup(monitor.stop)

        monitor.start()

        self.assertTrue(first_run_event.wait(timeout=1))
        self.assertEqual(calls, ["主号"])

    def test_signed_in_account_skipped_on_next_cycle(self) -> None:
        calls: list[str] = []

        def execute_checkin(account_name: str) -> bool:
            calls.append(account_name)
            return account_name == "主号"

        monitor = PeriodicAccountCheckinMonitor(
            list_account_names=lambda: ("主号", "室友"),
            execute_checkin=execute_checkin,
        )

        monitor.run_cycle_once()
        self.assertEqual(calls, ["主号", "室友"])

        calls.clear()
        monitor.run_cycle_once()
        self.assertEqual(calls, ["室友"])

    def test_signed_in_resets_on_new_day(self) -> None:
        calls: list[str] = []

        def execute_checkin(account_name: str) -> bool:
            calls.append(account_name)
            return True

        monitor = PeriodicAccountCheckinMonitor(
            list_account_names=lambda: ("主号",),
            execute_checkin=execute_checkin,
        )

        monitor.run_cycle_once()
        self.assertEqual(calls, ["主号"])

        calls.clear()
        monitor.run_cycle_once()
        self.assertEqual(calls, [])

        # 模拟跨天
        monitor._signed_in_day = -1
        calls.clear()
        monitor.run_cycle_once()
        self.assertEqual(calls, ["主号"])
