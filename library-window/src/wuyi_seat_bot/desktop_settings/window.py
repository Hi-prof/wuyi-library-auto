from __future__ import annotations

import threading
import tkinter as tk
from pathlib import Path
from queue import Empty, Queue
from tkinter import messagebox
from typing import Any, Callable

from wuyi_seat_bot.desktop_settings import ensure_tk_runtime
from wuyi_seat_bot.desktop_settings.page_builders import (
    build_diagnostics_page,
    build_network_page,
    build_runtime_page,
)
from wuyi_seat_bot.desktop_settings.service import DesktopSettingsService
from wuyi_seat_bot.desktop_settings.ui_helpers import (
    FONT_UI,
    PALETTE,
    format_alive,
    set_nav_button_state,
    style_button,
    style_nav_button,
)


class SettingsWindow:
    def __init__(self, *, service: DesktopSettingsService, config_path: Path) -> None:
        self.service = service
        self.config_path = config_path
        ensure_tk_runtime()
        self.root = tk.Tk()
        self.root.title("设置")
        self.root.geometry("860x520")
        self.root.minsize(760, 480)
        self.root.configure(bg=PALETTE["background"])
        self._payload: dict[str, Any] | None = None
        self._current_page = "network"
        self._pages: dict[str, tk.Frame] = {}
        self._nav_buttons: dict[str, tk.Button] = {}
        self._scroll_window_id: int | None = None
        self._background_action: str | None = None
        self._background_results: Queue[tuple[dict[str, Any] | None, Exception | None, bool]] = Queue()
        self.badge_label: tk.Label | None = None

        self.message_var = tk.StringVar(value="准备就绪")
        self.badge_var = tk.StringVar(value="读取中")

        self.network_enabled_var = tk.BooleanVar(value=False)
        self.interval_var = tk.IntVar(value=120)
        self.network_state_var = tk.StringVar(value="--")
        self.network_message_var = tk.StringVar(value="等待检测")
        self.network_updated_at_var = tk.StringVar(value="--")
        self.network_reconnect_var = tk.StringVar(value="--")
        self.network_wifi_var = tk.StringVar(value="--")
        self.stability_var = tk.StringVar(value="未启用")
        self.runtime_vars = {
            "supervisorState": tk.StringVar(value="--"),
            "workerState": tk.StringVar(value="--"),
            "restartCount": tk.StringVar(value="0"),
            "taskSchedulerAlive": tk.StringVar(value="--"),
            "automationSchedulerAlive": tk.StringVar(value="--"),
            "checkinMonitorAlive": tk.StringVar(value="--"),
            "pendingTaskCount": tk.StringVar(value="0"),
            "enabledAutomationPlanCount": tk.StringVar(value="0"),
        }
        self.worker_log_path_var = tk.StringVar(value="--")
        self.supervisor_log_path_var = tk.StringVar(value="--")
        self.network_monitor_log_path_var = tk.StringVar(value="--")
        self.log_directory_path_var = tk.StringVar(value="--")
        self.recent_logs_text: tk.Text

        self._build_ui()
        self._load_initial_payload()
        self.root.after(5000, self._refresh_runtime_loop)

    def run(self) -> int:
        self.root.mainloop()
        return 0

    def close(self) -> None:
        if self.root.winfo_exists():
            self.root.destroy()

    def activate_window(self) -> None:
        self.root.deiconify()
        self.root.lift()
        self.root.attributes("-topmost", True)
        self.root.after(150, lambda: self.root.attributes("-topmost", False))
        self.root.focus_force()

    def run_action(self, action: Callable[[], dict[str, Any]]) -> None:
        try:
            payload = action()
        except Exception as exc:  # noqa: BLE001
            self._show_error(exc)
            return
        self._payload = payload
        self._apply_runtime_payload(payload)

    def run_action_async(
        self,
        action: Callable[[], dict[str, Any]],
        *,
        message: str = "正在执行操作...",
        update_form: bool = False,
    ) -> None:
        self._start_background_action(action, message=message, update_form=update_form)

    def toggle_stability_enhancement(self) -> None:
        enabled = not bool(self._payload and self._payload.get("stabilityEnhancementEnabled"))
        self.run_action(lambda: self.service.set_stability_enhancement(enabled))

    def confirm_clear_logs(self) -> None:
        if not messagebox.askyesno("清空日志", "这会清空当前工作日志、守护日志、网络诊断日志，并删除历史轮转日志。是否继续？"):
            return
        self.run_action(self.service.clear_logs)

    def copy_recent_logs(self) -> None:
        preview = self.recent_logs_text.get("1.0", tk.END).strip()
        if not preview:
            self.message_var.set("最近日志为空")
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(preview)
        self.message_var.set("已复制最近日志")

    def save_settings(self) -> None:
        try:
            payload = self._save_form_settings()
        except Exception as exc:  # noqa: BLE001
            self._show_error(exc)
            return
        self._apply_payload(payload, update_form=True)

    def submit_network_action(self, action_name: str) -> None:
        try:
            saved_payload = self._save_form_settings()
            self._apply_payload(saved_payload, update_form=True)
            payload = getattr(self.service, action_name)()
        except Exception as exc:  # noqa: BLE001
            self._show_error(exc)
            return
        self._apply_payload(payload)

    def submit_network_action_async(self, action_name: str) -> None:
        try:
            saved_payload = self._save_form_settings()
            self._apply_payload(saved_payload, update_form=True)
        except Exception as exc:  # noqa: BLE001
            self._show_error(exc)
            return
        self._start_background_action(
            lambda: getattr(self.service, action_name)(),
            message="正在执行网络操作...",
        )

    def refresh_runtime_payload(self) -> None:
        self._payload = self.service.get_payload()
        self._apply_runtime_payload(self._payload)

    def _start_background_action(
        self,
        action: Callable[[], dict[str, Any]],
        *,
        message: str,
        update_form: bool = False,
    ) -> None:
        if self._background_action is not None:
            self.message_var.set("已有设置操作正在执行，请稍后再试")
            return
        self._background_action = message
        self.message_var.set(message)
        worker = threading.Thread(
            target=self._run_background_action,
            args=(action, update_form),
            daemon=True,
        )
        worker.start()
        self.root.after(100, self._poll_background_action)

    def _run_background_action(
        self,
        action: Callable[[], dict[str, Any]],
        update_form: bool,
    ) -> None:
        try:
            self._background_results.put((action(), None, update_form))
        except Exception as exc:  # noqa: BLE001
            self._background_results.put((None, exc, update_form))

    def _poll_background_action(self) -> None:
        if not self.root.winfo_exists():
            return
        try:
            payload, exc, update_form = self._background_results.get_nowait()
        except Empty:
            if self._background_action is not None:
                self.root.after(100, self._poll_background_action)
            return
        self._background_action = None
        if exc is not None:
            self._show_error(exc)
            return
        if payload is not None:
            self._apply_payload(payload, update_form=update_form)

    def _build_ui(self) -> None:
        self._build_header()
        body = tk.Frame(self.root, bg=PALETTE["background"])
        body.pack(fill="both", expand=True, padx=16, pady=(0, 8))
        self._build_sidebar(body)
        self._build_scrollable_content(body)
        self._build_action_bar()

        self._pages["network"] = build_network_page(self, self.scroll_content)
        self._pages["runtime"] = build_runtime_page(self, self.scroll_content)
        self._pages["diagnostics"] = build_diagnostics_page(self, self.scroll_content)
        self._show_page("network")

    def _build_header(self) -> None:
        header = tk.Frame(
            self.root,
            bg=PALETTE["surface"],
            padx=16,
            pady=12,
            highlightbackground=PALETTE["border"],
            highlightthickness=1,
        )
        header.pack(fill="x", padx=16, pady=(16, 10))
        title_group = tk.Frame(header, bg=header.cget("bg"))
        title_group.pack(side="left", fill="x", expand=True)
        tk.Label(
            title_group,
            text="设置",
            bg=title_group.cget("bg"),
            fg=PALETTE["deep"],
            font=(FONT_UI, 16, "bold"),
        ).pack(anchor="w")
        tk.Label(
            title_group,
            text=f"配置：{self.config_path}",
            bg=title_group.cget("bg"),
            fg=PALETTE["muted"],
            font=(FONT_UI, 9),
        ).pack(anchor="w", pady=(4, 0))
        badge_shell = tk.Frame(
            header,
            bg=PALETTE["success_soft"],
            padx=1,
            pady=1,
            highlightbackground=PALETTE["success"],
            highlightthickness=1,
        )
        badge_shell.pack(side="right", anchor="n")
        self.badge_label = tk.Label(
            badge_shell,
            textvariable=self.badge_var,
            bg=PALETTE["success"],
            fg="#FFFFFF",
            padx=12,
            pady=4,
            font=(FONT_UI, 9, "bold"),
        )
        self.badge_label.pack()

    def _build_sidebar(self, parent: tk.Widget) -> None:
        sidebar = tk.Frame(
            parent,
            bg=PALETTE["surface"],
            width=140,
            padx=8,
            pady=8,
            highlightbackground=PALETTE["border"],
            highlightthickness=1,
        )
        sidebar.pack(side="left", fill="y", padx=(0, 10))
        sidebar.pack_propagate(False)
        nav_items = (
            ("network", "网络"),
            ("runtime", "运行状态"),
            ("diagnostics", "日志"),
        )
        for page_key, title in nav_items:
            button = tk.Button(
                sidebar,
                text=title,
                command=lambda key=page_key: self._show_page(key),
            )
            style_nav_button(button)
            button.pack(fill="x", pady=2)
            self._nav_buttons[page_key] = button

    def _build_scrollable_content(self, parent: tk.Widget) -> None:
        shell = tk.Frame(parent, bg=PALETTE["background"])
        shell.pack(side="left", fill="both", expand=True)
        self.scroll_canvas = tk.Canvas(shell, bg=PALETTE["background"], highlightthickness=0, bd=0)
        scrollbar = tk.Scrollbar(shell, orient="vertical", command=self.scroll_canvas.yview)
        scrollbar.configure(
            bg=PALETTE["surface_alt"],
            activebackground=PALETTE["secondary"],
            troughcolor=PALETTE["background"],
            relief="flat",
            borderwidth=0,
        )
        self.scroll_canvas.configure(yscrollcommand=scrollbar.set)
        self.scroll_canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        self.scroll_content = tk.Frame(self.scroll_canvas, bg=PALETTE["background"])
        self._scroll_window_id = self.scroll_canvas.create_window((0, 0), window=self.scroll_content, anchor="nw")
        self.scroll_content.bind("<Configure>", self._on_scroll_content_configure)
        self.scroll_canvas.bind("<Configure>", self._on_canvas_configure)
        self.scroll_canvas.bind("<MouseWheel>", self._on_mousewheel)
        self.scroll_content.bind("<MouseWheel>", self._on_mousewheel)
        self.root.bind_all("<MouseWheel>", self._on_mousewheel)

    def _build_action_bar(self) -> None:
        bar = tk.Frame(
            self.root,
            bg=PALETTE["surface"],
            padx=16,
            pady=10,
            highlightbackground=PALETTE["border"],
            highlightthickness=1,
        )
        bar.pack(fill="x", padx=16, pady=(0, 16))
        tk.Label(
            bar,
            textvariable=self.message_var,
            bg=bar.cget("bg"),
            fg=PALETTE["muted"],
            justify="left",
            wraplength=560,
            font=(FONT_UI, 9),
        ).pack(side="left", fill="x", expand=True)
        self.secondary_button = style_button(tk.Button(bar), kind="secondary")
        self.secondary_button.pack(side="right", padx=(10, 0))
        self.primary_button = style_button(tk.Button(bar), kind="primary")
        self.primary_button.pack(side="right")

    def _show_page(self, page_key: str) -> None:
        self._current_page = page_key
        for key, page in self._pages.items():
            page.pack_forget()
            set_nav_button_state(self._nav_buttons[key], active=False)
            if key == page_key:
                page.pack(fill="both", expand=True)
                set_nav_button_state(self._nav_buttons[key], active=True)
        self.scroll_canvas.yview_moveto(0)
        self._update_action_bar()

    def _update_action_bar(self) -> None:
        actions = {
            "network": ("保存", self.save_settings),
            "runtime": ("刷新", self.refresh_runtime_payload),
            "diagnostics": ("关闭", self.close),
        }
        primary_text, primary_command = actions[self._current_page]
        self.primary_button.configure(text=primary_text, command=primary_command)
        style_button(self.primary_button, kind="primary" if self._current_page != "diagnostics" else "secondary")
        if self._current_page == "diagnostics":
            self.secondary_button.pack_forget()
            return
        style_button(self.secondary_button, kind="secondary")
        self.secondary_button.configure(text="关闭", command=self.close)
        if not self.secondary_button.winfo_ismapped():
            self.secondary_button.pack(side="right", padx=(10, 0))

    def _load_initial_payload(self) -> None:
        payload = self.service.get_payload()
        self._apply_payload(payload, update_form=True)

    def _refresh_runtime_loop(self) -> None:
        if not self.root.winfo_exists():
            return
        self.refresh_runtime_payload()
        self.root.after(5000, self._refresh_runtime_loop)

    def _apply_settings_form(self, settings: dict[str, Any]) -> None:
        monitoring = settings["networkMonitoring"]
        self.network_enabled_var.set(bool(monitoring["enabled"]))
        self.interval_var.set(int(monitoring["intervalMinutes"]))
        self.preferred_wifi_text.delete("1.0", tk.END)
        self.preferred_wifi_text.insert("1.0", "\n".join(monitoring["preferredWifiNames"]))

    def _apply_runtime_payload(self, payload: dict[str, Any]) -> None:
        supervisor = payload["serviceSnapshot"]["supervisor"]
        worker = payload["serviceSnapshot"]["worker"]
        network_status = payload["networkStatus"]
        diagnostics = payload["diagnostics"]
        worker_running = worker.get("state") == "running"
        self.badge_var.set("运行中" if worker_running else "未运行")
        self._set_badge_state("running" if worker_running else "stopped")
        self.network_state_var.set(str(network_status.get("networkState", "--")))
        self.network_message_var.set(str(network_status.get("message", "--")))
        self.network_updated_at_var.set(str(network_status.get("updatedAt", "--") or "--"))
        self.network_reconnect_var.set(str(network_status.get("reconnectState", "--") or "--"))
        self.network_wifi_var.set(str(network_status.get("wifiName", "--") or "--"))
        self.stability_var.set("已启用" if payload["stabilityEnhancementEnabled"] else "未启用")
        self.runtime_vars["supervisorState"].set(str(supervisor.get("state", "missing")))
        self.runtime_vars["workerState"].set(str(worker.get("state", "missing")))
        self.runtime_vars["restartCount"].set(str(supervisor.get("restartCount", 0) or 0))
        self.runtime_vars["taskSchedulerAlive"].set(format_alive(worker.get("taskSchedulerAlive")))
        self.runtime_vars["automationSchedulerAlive"].set(format_alive(worker.get("automationSchedulerAlive")))
        self.runtime_vars["checkinMonitorAlive"].set(format_alive(worker.get("checkinMonitorAlive")))
        self.runtime_vars["pendingTaskCount"].set(str(worker.get("pendingTaskCount", 0) or 0))
        self.runtime_vars["enabledAutomationPlanCount"].set(str(worker.get("enabledAutomationPlanCount", 0) or 0))
        self.worker_log_path_var.set(diagnostics["workerLogPath"])
        self.supervisor_log_path_var.set(diagnostics["supervisorLogPath"])
        self.network_monitor_log_path_var.set(diagnostics["networkMonitorLogPath"])
        self.log_directory_path_var.set(diagnostics["logDirectoryPath"])
        self._set_recent_logs_preview(str(diagnostics.get("recentLogsPreview", "")))
        if payload.get("message"):
            self.message_var.set(str(payload["message"]))
            return
        self.message_var.set("工作进程运行中" if worker_running else "等待工作进程启动")

    def _apply_payload(self, payload: dict[str, Any], *, update_form: bool = False) -> None:
        self._payload = payload
        if update_form:
            self._apply_settings_form(payload["settings"])
        self._apply_runtime_payload(payload)

    def _save_form_settings(self) -> dict[str, Any]:
        return self.service.save_settings(self._build_settings_payload())

    def _build_settings_payload(self) -> dict[str, Any]:
        return {
            "networkMonitoring": {
                "enabled": self.network_enabled_var.get(),
                "intervalMinutes": self.interval_var.get(),
                "preferredWifiNames": self._collect_wifi_names(),
            },
        }

    def _collect_wifi_names(self) -> list[str]:
        return [line.strip() for line in self.preferred_wifi_text.get("1.0", tk.END).splitlines() if line.strip()]

    def _on_scroll_content_configure(self, _event) -> None:
        self.scroll_canvas.configure(scrollregion=self.scroll_canvas.bbox("all"))

    def _on_canvas_configure(self, event) -> None:
        if self._scroll_window_id is not None:
            self.scroll_canvas.itemconfigure(self._scroll_window_id, width=event.width)

    def _on_mousewheel(self, event) -> str:
        self.scroll_canvas.yview_scroll(int(-event.delta / 120), "units")
        return "break"

    def _show_error(self, exc: Exception) -> None:
        self._set_badge_state("error")
        messagebox.showerror("操作失败", str(exc))
        self.message_var.set(f"操作失败：{exc}")

    def _set_recent_logs_preview(self, preview: str) -> None:
        self.recent_logs_text.configure(state="normal")
        self.recent_logs_text.delete("1.0", tk.END)
        self.recent_logs_text.insert("1.0", preview.strip() or "暂无日志")
        self.recent_logs_text.configure(state="disabled")

    def _set_badge_state(self, state: str) -> None:
        if self.badge_label is None:
            return
        styles = {
            "running": (PALETTE["success_soft"], PALETTE["success"]),
            "stopped": (PALETTE["accent_soft"], PALETTE["accent_dark"]),
            "error": (PALETTE["sunshine_soft"], PALETTE["danger"]),
        }
        shell_bg, label_bg = styles[state]
        self.badge_label.master.configure(bg=shell_bg, highlightbackground=label_bg)
        self.badge_label.configure(bg=label_bg)
