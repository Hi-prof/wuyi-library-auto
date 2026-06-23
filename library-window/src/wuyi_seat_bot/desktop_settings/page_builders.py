from __future__ import annotations

import tkinter as tk

from wuyi_seat_bot.desktop_settings.ui_helpers import (
    FONT_UI,
    PALETTE,
    add_value_row,
    build_emphasis_checkbutton,
    build_card,
    style_button,
    style_input_field,
)


def _get_window_attr(window: object, name: str, fallback):
    return getattr(window, name, fallback)


def _run_window_action(window: object, action_name: str) -> None:
    service = getattr(window, "service")
    action = getattr(service, action_name)
    run_async = getattr(window, "run_action_async", None)
    if callable(run_async):
        run_async(action)
        return
    window.run_action(action)


def _submit_network_action(window: object, action_name: str) -> None:
    submit_async = getattr(window, "submit_network_action_async", None)
    if callable(submit_async):
        submit_async(action_name)
        return
    submit_action = getattr(window, "submit_network_action", None)
    if callable(submit_action):
        submit_action(action_name)
        return
    _run_window_action(window, action_name)


def _refresh_campus_login_url(window: object) -> None:
    refresh_async = getattr(window, "refresh_campus_login_url_async", None)
    if callable(refresh_async):
        refresh_async()
        return
    window.refresh_campus_login_url()


def build_network_page(window: object, parent: tk.Widget) -> tk.Frame:
    page = tk.Frame(parent, bg=PALETTE["background"])
    _build_page_title(page, "网络")

    columns = tk.Frame(page, bg=PALETTE["background"])
    columns.pack(fill="both", expand=True)
    columns.grid_columnconfigure(0, weight=3)
    columns.grid_columnconfigure(1, weight=2)

    left = tk.Frame(columns, bg=PALETTE["background"])
    left.grid(row=0, column=0, sticky="nsew", padx=(0, 8))

    right = tk.Frame(columns, bg=PALETTE["background"])
    right.grid(row=0, column=1, sticky="nsew", padx=(8, 0))

    _build_monitoring_section(window, left, parent)
    _build_campus_section(window, left, parent)
    _build_network_actions(window, left)
    _build_status_section(window, right)

    return page


def _build_monitoring_section(window: object, parent: tk.Widget, root_parent: tk.Widget) -> None:
    card = build_card(parent, padding=12)
    card.pack(fill="x", pady=(0, 8))
    card.grid_columnconfigure(1, weight=1)

    row = _section_title(card, "巡检策略")

    build_emphasis_checkbutton(
        card,
        text="启用网络巡检",
        variable=window.network_enabled_var,
    ).grid(row=row, column=0, columnspan=2, sticky="ew", pady=(0, 10))
    row += 1

    _field_label(card, row, "检测间隔（分钟）")
    style_input_field(
        tk.Spinbox(card, from_=30, to=720, increment=30, textvariable=window.interval_var),
    ).grid(row=row, column=1, sticky="ew")
    row += 1

    _field_label(card, row, "优先 Wi-Fi")
    window.preferred_wifi_text = style_input_field(
        tk.Text(card, height=3, width=28, font=(FONT_UI, 9)),
        multiline=True,
    )
    window.preferred_wifi_text.grid(row=row, column=1, sticky="nsew", pady=4)
    card.grid_rowconfigure(row, weight=1)


def _build_campus_section(window: object, parent: tk.Widget, root_parent: tk.Widget) -> None:
    card = build_card(parent, padding=12, tone="blue")
    card.pack(fill="x", pady=(0, 8))
    card.grid_columnconfigure(1, weight=1)

    campus_wifi_name_var = _get_window_attr(window, "campus_wifi_name_var", tk.StringVar(master=root_parent, value=""))
    campus_network_enabled_var = _get_window_attr(window, "campus_network_enabled_var", tk.BooleanVar(master=root_parent, value=False))
    campus_login_url_var = _get_window_attr(window, "campus_login_url_var", tk.StringVar(master=root_parent, value=""))
    campus_username_var = _get_window_attr(window, "campus_username_var", tk.StringVar(master=root_parent, value=""))
    campus_password_var = _get_window_attr(window, "campus_password_var", tk.StringVar(master=root_parent, value=""))

    row = _section_title(card, "校园网认证")

    build_emphasis_checkbutton(
        card,
        text="断网时自动认证校园网",
        variable=campus_network_enabled_var,
    ).grid(row=row, column=0, columnspan=2, sticky="ew", pady=(0, 10))
    row += 1

    _field_label(card, row, "校园网 Wi-Fi")
    style_input_field(tk.Entry(card, textvariable=campus_wifi_name_var)).grid(
        row=row, column=1, sticky="ew", pady=4,
    )
    row += 1

    _field_label(card, row, "登录地址")
    login_url_row = tk.Frame(card, bg=card.cget("bg"))
    login_url_row.grid(row=row, column=1, sticky="ew", pady=4)
    login_url_row.grid_columnconfigure(0, weight=1)
    style_input_field(tk.Entry(login_url_row, textvariable=campus_login_url_var)).grid(
        row=0, column=0, sticky="ew",
    )
    style_button(
        tk.Button(login_url_row, text="获取地址", command=lambda: _refresh_campus_login_url(window)),
        kind="secondary",
    ).grid(row=0, column=1, padx=(6, 0))
    row += 1

    _field_label(card, row, "账号")
    style_input_field(tk.Entry(card, textvariable=campus_username_var)).grid(
        row=row, column=1, sticky="ew", pady=4,
    )
    row += 1

    _field_label(card, row, "密码")
    style_input_field(tk.Entry(card, textvariable=campus_password_var, show="*")).grid(
        row=row, column=1, sticky="ew", pady=4,
    )


def _build_network_actions(window: object, parent: tk.Widget) -> None:
    card = build_card(parent, padding=12, tone="default")
    card.pack(fill="x")

    row = _section_title(card, "快捷操作")
    action_row = tk.Frame(card, bg=card.cget("bg"))
    action_row.grid(row=row, column=0, columnspan=2, sticky="w")

    style_button(
        tk.Button(action_row, text="检测网络", command=lambda: _run_window_action(window, "run_network_check")),
        kind="ghost",
    ).pack(side="left", padx=(0, 6))
    style_button(
        tk.Button(action_row, text="尝试重连", command=lambda: _submit_network_action(window, "run_network_reconnect")),
        kind="primary",
    ).pack(side="left", padx=(0, 6))
    style_button(
        tk.Button(action_row, text="校园网登录", command=lambda: _submit_network_action(window, "run_campus_network_login")),
        kind="secondary",
    ).pack(side="left", padx=(0, 6))
    style_button(
        tk.Button(action_row, text="切换到校园网", command=lambda: _submit_network_action(window, "run_switch_to_campus_wifi")),
        kind="secondary",
    ).pack(side="left")


def _build_status_section(window: object, parent: tk.Widget) -> None:
    card = build_card(parent, padding=12, tone="lavender")
    card.pack(fill="x")

    row = _section_title(card, "实时状态")
    add_value_row(card, row=row, label="当前状态", value_var=window.network_state_var)
    add_value_row(card, row=row + 1, label="结果说明", value_var=window.network_message_var)
    add_value_row(card, row=row + 2, label="最近检测", value_var=window.network_updated_at_var)
    add_value_row(card, row=row + 3, label="最近重连", value_var=window.network_reconnect_var)
    add_value_row(card, row=row + 4, label="命中 Wi-Fi", value_var=window.network_wifi_var)


def build_runtime_page(window: object, parent: tk.Widget) -> tk.Frame:
    page = tk.Frame(parent, bg=PALETTE["background"])
    _build_page_title(page, "运行状态")

    body_card = build_card(page, padding=12, tone="mint")
    body_card.pack(fill="x")

    stability_card = tk.Frame(body_card, bg=body_card.cget("bg"))
    stability_card.pack(fill="x", pady=(0, 8))
    stability_card.grid_columnconfigure(1, weight=1)
    top_row = _section_title(stability_card, "稳定性增强")
    add_value_row(stability_card, row=top_row, label="当前状态", value_var=window.stability_var)
    style_button(
        tk.Button(stability_card, text="切换稳定性", command=window.toggle_stability_enhancement),
        kind="primary",
    ).grid(row=top_row + 1, column=0, columnspan=2, sticky="w", pady=(10, 0))

    status_card = tk.Frame(body_card, bg=body_card.cget("bg"))
    status_card.pack(fill="x")
    status_card.grid_columnconfigure(1, weight=1)
    bottom_row = _section_title(status_card, "服务状态")
    runtime_items = (
        ("守护状态", window.runtime_vars["supervisorState"]),
        ("工作进程", window.runtime_vars["workerState"]),
        ("自动重启次数", window.runtime_vars["restartCount"]),
        ("任务调度线程", window.runtime_vars["taskSchedulerAlive"]),
        ("自动计划线程", window.runtime_vars["automationSchedulerAlive"]),
        ("签到巡检线程", window.runtime_vars["checkinMonitorAlive"]),
        ("待执行任务数", window.runtime_vars["pendingTaskCount"]),
        ("启用中的自动计划", window.runtime_vars["enabledAutomationPlanCount"]),
    )
    for row_index, (label, value_var) in enumerate(runtime_items, start=bottom_row):
        add_value_row(status_card, row=row_index, label=label, value_var=value_var)
    return page


def build_diagnostics_page(window: object, parent: tk.Widget) -> tk.Frame:
    page = tk.Frame(parent, bg=PALETTE["background"])
    _build_page_title(page, "日志")

    body_card = build_card(page, padding=12, tone="blue")
    body_card.pack(fill="both", expand=True)

    location_card = tk.Frame(body_card, bg=body_card.cget("bg"))
    location_card.pack(fill="x", pady=(0, 8))
    location_card.grid_columnconfigure(1, weight=1)
    location_row = _section_title(location_card, "日志位置")
    add_value_row(location_card, row=location_row, label="工作日志", value_var=window.worker_log_path_var)
    add_value_row(location_card, row=location_row + 1, label="守护日志", value_var=window.supervisor_log_path_var)
    add_value_row(location_card, row=location_row + 2, label="校园网诊断", value_var=window.network_monitor_log_path_var)
    add_value_row(location_card, row=location_row + 3, label="日志目录", value_var=window.log_directory_path_var)

    button_row = tk.Frame(location_card, bg=location_card.cget("bg"))
    button_row.grid(row=location_row + 4, column=0, columnspan=2, sticky="w", pady=(8, 0))
    style_button(tk.Button(button_row, text="工作日志", command=lambda: window.run_action(lambda: window.service.open_log_target("workerLog"))), kind="ghost").pack(side="left", padx=(0, 6))
    style_button(tk.Button(button_row, text="守护日志", command=lambda: window.run_action(lambda: window.service.open_log_target("supervisorLog"))), kind="ghost").pack(side="left", padx=(0, 6))
    style_button(
        tk.Button(button_row, text="校园网诊断", command=lambda: window.run_action(lambda: window.service.open_log_target("networkMonitorLog"))),
        kind="ghost",
    ).pack(side="left", padx=(0, 6))
    style_button(
        tk.Button(button_row, text="日志目录", command=lambda: window.run_action(lambda: window.service.open_log_target("logDirectory"))),
        kind="primary",
    ).pack(side="left")

    preview_card = tk.Frame(body_card, bg=body_card.cget("bg"))
    preview_card.pack(fill="both", expand=True, pady=(0, 8))
    preview_card.grid_columnconfigure(0, weight=1)
    preview_card.grid_rowconfigure(2, weight=1)
    preview_row = _section_title(preview_card, "最近日志")
    style_button(
        tk.Button(preview_card, text="复制最近日志", command=window.copy_recent_logs),
        kind="secondary",
    ).grid(row=preview_row, column=0, sticky="w", pady=(0, 8))
    window.recent_logs_text = style_input_field(
        tk.Text(preview_card, height=9, wrap="word", font=(FONT_UI, 8)),
        multiline=True,
    )
    window.recent_logs_text.grid(row=preview_row + 1, column=0, columnspan=2, sticky="nsew")
    window.recent_logs_text.configure(state="disabled", cursor="arrow")

    danger_card = tk.Frame(body_card, bg=body_card.cget("bg"))
    danger_card.pack(fill="x")
    danger_card.grid_columnconfigure(0, weight=1)
    danger_row = _section_title(danger_card, "清理")
    style_button(tk.Button(danger_card, text="清空全部日志", command=window.confirm_clear_logs), kind="danger").grid(
        row=danger_row,
        column=0,
        sticky="w",
    )
    return page


def _build_page_title(parent: tk.Widget, title: str) -> None:
    header = tk.Frame(parent, bg=PALETTE["background"])
    header.pack(fill="x", pady=(0, 14))
    tk.Label(
        header,
        text=title,
        bg=PALETTE["background"],
        fg=PALETTE["deep"],
        font=(FONT_UI, 15, "bold"),
    ).pack(anchor="w")


def _section_title(parent: tk.Widget, title: str, *, row: int = 0) -> int:
    tk.Label(parent, text=title, bg=parent.cget("bg"), fg=PALETTE["text"], font=(FONT_UI, 10, "bold")).grid(
        row=row,
        column=0,
        columnspan=2,
        sticky="w",
        pady=(0, 8),
    )
    return row + 1


def _field_label(parent: tk.Widget, row: int, text: str) -> None:
    tk.Label(parent, text=text, bg=parent.cget("bg"), fg=PALETTE["muted"], font=(FONT_UI, 9)).grid(
        row=row,
        column=0,
        sticky="nw",
        padx=(0, 16),
        pady=5,
    )
