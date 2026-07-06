import unittest
from pathlib import Path

from wuyi_seat_bot.desktop_settings import ensure_tk_runtime


ensure_tk_runtime()

import tkinter as tk


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DESKTOP_SETTINGS_DIR = PROJECT_ROOT / "src" / "wuyi_seat_bot" / "desktop_settings"


def _create_tk_root(test_case):
    ensure_tk_runtime()
    try:
        root = tk.Tk()
    except tk.TclError as exc:
        test_case.skipTest(f"当前 Python Tk 运行时不可用：{exc}")
    root.withdraw()
    return root


def _create_tcl_runtime(test_case):
    ensure_tk_runtime()
    try:
        return tk.Tcl()
    except tk.TclError as exc:
        test_case.skipTest(f"当前 Python Tcl 运行时不可用：{exc}")


def _create_settings_window(test_case, window_class, service):
    try:
        return window_class(service=service, config_path=Path("config.json"))
    except tk.TclError as exc:
        test_case.skipTest(f"当前 Python Tk 运行时不可用：{exc}")


class DesktopSettingsUiTestCase(unittest.TestCase):
    def test_window_uses_left_sidebar_and_scrollable_content(self) -> None:
        source = (DESKTOP_SETTINGS_DIR / "window.py").read_text(encoding="utf-8")

        self.assertIn("nav_items =", source)
        self.assertIn("scroll_canvas", source)
        self.assertIn("<MouseWheel>", source)
        self.assertNotIn("build_overview_page", source)

    def test_page_builders_contains_three_detail_pages(self) -> None:
        source = (DESKTOP_SETTINGS_DIR / "page_builders.py").read_text(encoding="utf-8")

        self.assertIn("build_network_page", source)
        self.assertIn("build_runtime_page", source)
        self.assertIn("build_diagnostics_page", source)
        self.assertIn("_build_page_title", source)
        self.assertNotIn("_build_page_header", source)

    def test_window_uses_compact_geometry_and_concise_copy(self) -> None:
        source = (DESKTOP_SETTINGS_DIR / "window.py").read_text(encoding="utf-8")

        self.assertIn('self.root.geometry("860x520")', source)
        self.assertIn('self.root.minsize(760, 480)', source)
        self.assertNotIn("左边选模块，右边专注当前子页面。现在支持鼠标滚轮浏览长内容。", source)
        self.assertNotIn("Maison Control", source)
        self.assertNotIn("模块导航", source)
        self.assertNotIn("建议顺序", source)
        self.assertNotIn("当前模块", source)

    def test_page_builders_use_single_body_card_and_keep_core_controls(self) -> None:
        from wuyi_seat_bot.desktop_settings.page_builders import (
            build_diagnostics_page,
            build_network_page,
            build_runtime_page,
        )

        root = _create_tk_root(self)
        window = _WindowStub(root)
        try:
            network_page = build_network_page(window, root)
            runtime_page = build_runtime_page(window, root)
            diagnostics_page = build_diagnostics_page(window, root)
            root.update_idletasks()

            self.assertEqual(len(network_page.winfo_children()), 2)
            self.assertEqual(len(runtime_page.winfo_children()), 2)
            self.assertEqual(len(diagnostics_page.winfo_children()), 2)

            network_controls = _collect_widget_texts(network_page, tk.Button)
            runtime_controls = _collect_widget_texts(runtime_page, tk.Button)
            diagnostics_controls = _collect_widget_texts(diagnostics_page, tk.Button)

            self.assertIn("检测网络", network_controls)
            self.assertIn("尝试重连", network_controls)
            self.assertNotIn("获取地址", network_controls)
            self.assertNotIn("校园网登录", network_controls)
            self.assertNotIn("切换到校园网", network_controls)
            self.assertIn("切换稳定性", runtime_controls)
            self.assertIn("复制最近日志", diagnostics_controls)
            self.assertIn("网络诊断", diagnostics_controls)
            self.assertNotIn("校园网诊断", diagnostics_controls)
            self.assertIn("清空全部日志", diagnostics_controls)
            self.assertTrue(any(isinstance(widget, tk.Checkbutton) for widget in _walk_widgets(network_page)))
        finally:
            root.destroy()

    def test_network_page_emphasises_selectable_controls(self) -> None:
        from wuyi_seat_bot.desktop_settings.page_builders import build_network_page
        from wuyi_seat_bot.desktop_settings.ui_helpers import PALETTE

        root = _create_tk_root(self)
        window = _WindowStub(root)
        try:
            network_page = build_network_page(window, root)
            root.update_idletasks()

            checkbutton = next(widget for widget in _walk_widgets(network_page) if isinstance(widget, tk.Checkbutton))
            spinbox = next(widget for widget in _walk_widgets(network_page) if isinstance(widget, tk.Spinbox))
            text = next(widget for widget in _walk_widgets(network_page) if isinstance(widget, tk.Text))

            self.assertEqual(checkbutton.cget("relief"), "solid")
            self.assertEqual(str(checkbutton.cget("highlightthickness")), "2")
            self.assertEqual(checkbutton.cget("highlightbackground"), PALETTE["border_strong"])
            window.network_enabled_var.set(True)
            root.update_idletasks()
            self.assertEqual(checkbutton.cget("highlightbackground"), PALETTE["accent"])
            self.assertEqual(checkbutton.cget("bg"), PALETTE["field_bg"])
            self.assertEqual(checkbutton.cget("fg"), PALETTE["text"])
            self.assertEqual(spinbox.cget("relief"), "solid")
            self.assertEqual(str(spinbox.cget("highlightthickness")), "2")
            self.assertEqual(spinbox.cget("bg"), PALETTE["field_bg"])
            self.assertEqual(text.cget("relief"), "solid")
            self.assertEqual(str(text.cget("highlightthickness")), "2")
            self.assertEqual(text.cget("bg"), PALETTE["field_bg"])
        finally:
            root.destroy()

    def test_window_uses_stronger_nav_and_action_button_states(self) -> None:
        from wuyi_seat_bot.desktop_settings.ui_helpers import PALETTE
        from wuyi_seat_bot.desktop_settings.window import SettingsWindow

        window = _create_settings_window(self, SettingsWindow, _WindowServiceStub())
        try:
            window.root.update_idletasks()

            self.assertEqual(window._nav_buttons["network"].cget("bg"), PALETTE["deep"])
            self.assertEqual(window._nav_buttons["network"].cget("fg"), "#FFFFFF")
            self.assertEqual(window._nav_buttons["network"].cget("highlightbackground"), PALETTE["accent"])
            self.assertEqual(window._nav_buttons["runtime"].cget("bg"), PALETTE["surface"])
            self.assertEqual(window.primary_button.cget("bg"), PALETTE["accent"])
            self.assertEqual(window.secondary_button.cget("bg"), PALETTE["secondary"])
            self.assertEqual(window._nav_buttons["runtime"].cget("fg"), PALETTE["text"])

            window._show_page("diagnostics")
            window.root.update_idletasks()

            self.assertEqual(window._nav_buttons["diagnostics"].cget("bg"), PALETTE["deep"])
            self.assertEqual(window.primary_button.cget("bg"), PALETTE["secondary"])
            self.assertFalse(window.secondary_button.winfo_ismapped())
        finally:
            window.close()

    def test_window_applies_recent_logs_preview_and_supports_copy(self) -> None:
        from unittest.mock import patch

        from wuyi_seat_bot.desktop_settings.window import SettingsWindow

        window = _create_settings_window(self, SettingsWindow, _WindowServiceStub())
        try:
            window.root.update_idletasks()

            preview = window.recent_logs_text.get("1.0", tk.END).strip()
            self.assertIn("工作日志", preview)
            self.assertIn("最近一条工作日志", preview)
            self.assertEqual(window.network_monitor_log_path_var.get(), "network-monitor.log")

            with patch.object(window.root, "clipboard_clear") as clipboard_clear, patch.object(
                window.root, "clipboard_append"
            ) as clipboard_append:
                window.copy_recent_logs()

            clipboard_clear.assert_called_once_with()
            clipboard_append.assert_called_once()
            self.assertIn("最近一条工作日志", clipboard_append.call_args.args[0])
            self.assertEqual(window.message_var.get(), "已复制最近日志")
        finally:
            window.close()

    def test_submit_network_action_saves_form_before_reconnect(self) -> None:
        from wuyi_seat_bot.desktop_settings.window import SettingsWindow

        service = _WindowNetworkActionServiceStub()
        runtime = _create_tcl_runtime(self)
        window = SettingsWindow.__new__(SettingsWindow)
        window.service = service
        window.network_enabled_var = tk.BooleanVar(master=runtime, value=False)
        window.interval_var = tk.IntVar(master=runtime, value=120)
        window.preferred_wifi_text = _TextValueStub("图书馆 Wi-Fi\n")
        window.message_var = tk.StringVar(master=runtime, value="")
        window._save_form_settings = SettingsWindow._save_form_settings.__get__(window, SettingsWindow)
        window._build_settings_payload = SettingsWindow._build_settings_payload.__get__(window, SettingsWindow)
        window._collect_wifi_names = SettingsWindow._collect_wifi_names.__get__(window, SettingsWindow)
        window._show_error = lambda exc: self.fail(f"submit_network_action 不应报错：{exc}")
        window._apply_payload = lambda payload, update_form=False: window.message_var.set(str(payload.get("message", "")))

        window.submit_network_action("run_network_reconnect")

        self.assertEqual(
            service.events,
            ["save_settings", "run_network_reconnect"],
        )
        self.assertNotIn("campusNetwork", service.saved_payloads[0])
        self.assertEqual(window.message_var.get(), "已尝试网络重连")

    def test_window_no_longer_exposes_campus_network_actions(self) -> None:
        from wuyi_seat_bot.desktop_settings.window import SettingsWindow

        service = _WindowNetworkActionServiceStub()
        runtime = _create_tcl_runtime(self)
        window = SettingsWindow.__new__(SettingsWindow)
        window.service = service
        window.network_enabled_var = tk.BooleanVar(master=runtime, value=False)
        window.interval_var = tk.IntVar(master=runtime, value=120)
        window.preferred_wifi_text = _TextValueStub("图书馆 Wi-Fi\n")
        window._build_settings_payload = SettingsWindow._build_settings_payload.__get__(window, SettingsWindow)
        window._collect_wifi_names = SettingsWindow._collect_wifi_names.__get__(window, SettingsWindow)

        self.assertFalse(hasattr(window, "refresh_campus_login_url"))
        self.assertFalse(hasattr(window, "refresh_campus_login_url_async"))
        self.assertNotIn("campusNetwork", window._build_settings_payload())


class _ServiceStub:
    @staticmethod
    def run_network_check() -> dict[str, str]:
        return {}

    @staticmethod
    def run_network_reconnect() -> dict[str, str]:
        return {}

    @staticmethod
    def open_log_target(_target: str) -> dict[str, str]:
        return {}


class _WindowServiceStub:
    @staticmethod
    def get_payload() -> dict[str, object]:
        return _build_window_payload()


class _WindowNetworkActionServiceStub:
    def __init__(self) -> None:
        self.saved_payloads: list[dict[str, object]] = []
        self.events: list[str] = []

    def get_payload(self) -> dict[str, object]:
        return _build_window_payload()

    def save_settings(self, payload: dict[str, object]) -> dict[str, object]:
        self.events.append("save_settings")
        self.saved_payloads.append(payload)
        return _build_window_payload(
            settings=payload,
            message="设置已保存",
        )

    def run_network_reconnect(self) -> dict[str, object]:
        self.events.append("run_network_reconnect")
        settings = self.saved_payloads[-1]
        return _build_window_payload(
            settings=settings,
            message="已尝试网络重连",
            network_status={
                "networkState": "online",
                "message": "已恢复联网",
                "updatedAt": "2026-04-23T22:10:00",
                "reconnectState": "reconnected",
                "wifiName": "图书馆 Wi-Fi",
            },
        )


class _WindowStub:
    def __init__(self, root: tk.Tk) -> None:
        self.service = _ServiceStub()
        self.network_enabled_var = tk.BooleanVar(master=root, value=False)
        self.interval_var = tk.IntVar(master=root, value=120)
        self.network_state_var = tk.StringVar(master=root, value="offline")
        self.network_message_var = tk.StringVar(master=root, value="未检测到已连接网络接口")
        self.network_updated_at_var = tk.StringVar(master=root, value="2026-04-07T21:38:11")
        self.network_reconnect_var = tk.StringVar(master=root, value="--")
        self.network_wifi_var = tk.StringVar(master=root, value="--")
        self.stability_var = tk.StringVar(master=root, value="未启用")
        self.runtime_vars = {
            "supervisorState": tk.StringVar(master=root, value="running"),
            "workerState": tk.StringVar(master=root, value="running"),
            "restartCount": tk.StringVar(master=root, value="0"),
            "taskSchedulerAlive": tk.StringVar(master=root, value="正常"),
            "automationSchedulerAlive": tk.StringVar(master=root, value="正常"),
            "checkinMonitorAlive": tk.StringVar(master=root, value="正常"),
            "pendingTaskCount": tk.StringVar(master=root, value="0"),
            "enabledAutomationPlanCount": tk.StringVar(master=root, value="0"),
        }
        self.worker_log_path_var = tk.StringVar(master=root, value="worker.log")
        self.supervisor_log_path_var = tk.StringVar(master=root, value="supervisor.log")
        self.network_monitor_log_path_var = tk.StringVar(master=root, value="network-monitor.log")
        self.log_directory_path_var = tk.StringVar(master=root, value="runtime\\logs")
        self.recent_logs_text = tk.Text(master=root)

    @staticmethod
    def run_action(_action) -> None:
        return None

    @staticmethod
    def toggle_stability_enhancement() -> None:
        return None

    @staticmethod
    def confirm_clear_logs() -> None:
        return None

    @staticmethod
    def copy_recent_logs() -> None:
        return None


class _TextValueStub:
    def __init__(self, value: str) -> None:
        self._value = value

    def get(self, _start: str, _end: str) -> str:
        return self._value

    def delete(self, _start: str, _end: str) -> None:
        self._value = ""

    def insert(self, _index: str, value: str) -> None:
        self._value = value


def _build_window_payload(
    *,
    settings: dict[str, object] | None = None,
    message: str = "",
    network_status: dict[str, object] | None = None,
) -> dict[str, object]:
    resolved_settings = settings or {
        "networkMonitoring": {
            "enabled": False,
            "intervalMinutes": 120,
            "preferredWifiNames": ["图书馆 Wi-Fi"],
        },
    }
    resolved_network_status = network_status or {
        "networkState": "online",
        "message": "网络连接正常",
        "updatedAt": "2026-04-07T23:23:30",
        "reconnectState": "--",
        "wifiName": "图书馆 Wi-Fi",
    }
    return {
        "settings": resolved_settings,
        "networkStatus": resolved_network_status,
        "serviceSnapshot": {
            "supervisor": {"state": "running", "restartCount": 0},
            "worker": {
                "state": "running",
                "taskSchedulerAlive": True,
                "automationSchedulerAlive": True,
                "checkinMonitorAlive": True,
                "pendingTaskCount": 0,
                "enabledAutomationPlanCount": 0,
            },
        },
        "stabilityEnhancementEnabled": False,
        "diagnostics": {
            "workerLogPath": "worker.log",
            "supervisorLogPath": "supervisor.log",
            "networkMonitorLogPath": "network-monitor.log",
            "logDirectoryPath": "runtime\\logs",
            "recentLogsPreview": "[工作日志]\n最近一条工作日志\n\n[守护日志]\n最近一条守护日志\n\n[网络诊断]\n最近一条网络诊断日志",
        },
        "message": message,
    }


def _collect_widget_texts(root: tk.Widget, widget_type: type[tk.Widget]) -> list[str]:
    texts: list[str] = []
    for widget in _walk_widgets(root):
        if isinstance(widget, widget_type):
            texts.append(widget.cget("text"))
    return texts


def _walk_widgets(root: tk.Widget) -> list[tk.Widget]:
    widgets: list[tk.Widget] = []
    for child in root.winfo_children():
        widgets.append(child)
        widgets.extend(_walk_widgets(child))
    return widgets
