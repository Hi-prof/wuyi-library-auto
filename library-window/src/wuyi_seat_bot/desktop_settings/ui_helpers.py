from __future__ import annotations

import tkinter as tk

PALETTE = {
    "background": "#F1F5F9",
    "surface": "#FFFFFF",
    "surface_alt": "#F8FAFC",
    "field_bg": "#F8FAFC",
    "field_hover": "#E2E8F0",
    "text": "#0F172A",
    "muted": "#64748B",
    "accent": "#2563EB",
    "accent_dark": "#1D4ED8",
    "accent_soft": "#DBEAFE",
    "secondary": "#E2E8F0",
    "secondary_hover": "#CBD5E1",
    "secondary_active": "#94A3B8",
    "secondary_border": "#CBD5E1",
    "sky_soft": "#EFF6FF",
    "mint_soft": "#ECFDF5",
    "sunshine_soft": "#FEF9C3",
    "deep_soft": "#EEF2FF",
    "border": "#E2E8F0",
    "border_strong": "#CBD5E1",
    "deep": "#1E3A8A",
    "danger": "#DC2626",
    "danger_dark": "#B91C1C",
    "success": "#15803D",
    "success_soft": "#DCFCE7",
}

FONT_UI = "Microsoft YaHei UI"


def build_card(parent: tk.Widget, *, padding: int = 16, tone: str = "default") -> tk.Frame:
    tones = {
        "default": (PALETTE["surface"], PALETTE["border"]),
        "pink": (PALETTE["surface_alt"], PALETTE["border"]),
        "blue": (PALETTE["sky_soft"], PALETTE["border"]),
        "mint": (PALETTE["mint_soft"], PALETTE["border"]),
        "sunshine": (PALETTE["sunshine_soft"], PALETTE["border"]),
        "lavender": (PALETTE["deep_soft"], PALETTE["border"]),
    }
    background, border = tones[tone]
    return tk.Frame(
        parent,
        bg=background,
        padx=padding,
        pady=padding,
        highlightbackground=border,
        highlightthickness=1,
    )


def style_button(button: tk.Button, *, kind: str = "ghost") -> tk.Button:
    base = {
        "font": (FONT_UI, 9, "bold"),
        "padx": 14,
        "pady": 5,
        "relief": "flat",
        "bd": 0,
        "cursor": "hand2",
        "highlightthickness": 1,
    }
    palettes = {
        "primary": {
            "bg": PALETTE["accent"],
            "hover_bg": PALETTE["accent_dark"],
            "active_bg": PALETTE["accent_dark"],
            "fg": "#FFFFFF",
            "border": PALETTE["accent_dark"],
        },
        "secondary": {
            "bg": PALETTE["secondary"],
            "hover_bg": PALETTE["secondary_hover"],
            "active_bg": PALETTE["secondary_active"],
            "fg": PALETTE["text"],
            "border": PALETTE["secondary_border"],
        },
        "ghost": {
            "bg": PALETTE["surface_alt"],
            "hover_bg": PALETTE["field_hover"],
            "active_bg": PALETTE["secondary"],
            "fg": PALETTE["text"],
            "border": PALETTE["border_strong"],
        },
        "danger": {
            "bg": PALETTE["danger"],
            "hover_bg": PALETTE["danger_dark"],
            "active_bg": PALETTE["danger_dark"],
            "fg": "#FFFFFF",
            "border": PALETTE["danger_dark"],
        },
    }
    palette = palettes[kind]
    button._normal_bg = palette["bg"]
    button._hover_bg = palette["hover_bg"]
    button._border_color = palette["border"]
    button._hover_border = palette["border"]
    button.configure(
        bg=palette["bg"],
        activebackground=palette["active_bg"],
        fg=palette["fg"],
        activeforeground=palette["fg"],
        highlightbackground=palette["border"],
        highlightcolor=palette["border"],
        disabledforeground=PALETTE["muted"],
        **base,
    )
    button.bind("<Enter>", lambda _event: _set_button_hover(button, hovered=True))
    button.bind("<Leave>", lambda _event: _set_button_hover(button, hovered=False))
    return button


def style_nav_button(button: tk.Button) -> tk.Button:
    button.configure(
        bd=0,
        relief="flat",
        bg=PALETTE["surface"],
        fg=PALETTE["muted"],
        activeforeground=PALETTE["text"],
        activebackground=PALETTE["field_hover"],
        highlightthickness=0,
        highlightbackground=PALETTE["border"],
        highlightcolor=PALETTE["accent"],
        cursor="hand2",
        font=(FONT_UI, 10),
        anchor="w",
        padx=12,
        pady=8,
    )
    button._nav_active = False
    button.bind("<Enter>", lambda _event: _set_nav_button_hover(button, hovered=True))
    button.bind("<Leave>", lambda _event: _set_nav_button_hover(button, hovered=False))
    set_nav_button_state(button, active=False)
    return button


def set_nav_button_state(button: tk.Button, *, active: bool) -> None:
    button._nav_active = active
    if active:
        button.configure(
            bg=PALETTE["deep"],
            fg="#FFFFFF",
            activebackground=PALETTE["deep"],
            activeforeground="#FFFFFF",
            highlightbackground=PALETTE["accent"],
            highlightcolor=PALETTE["accent"],
            font=(FONT_UI, 10, "bold"),
        )
        return
    button.configure(
        bg=PALETTE["surface"],
        fg=PALETTE["text"],
        activebackground=PALETTE["field_hover"],
        activeforeground=PALETTE["text"],
        highlightbackground=PALETTE["border"],
        highlightcolor=PALETTE["accent"],
        font=(FONT_UI, 10),
    )


def style_input_field(widget: tk.Widget, *, multiline: bool = False) -> tk.Widget:
    widget.configure(
        bg=PALETTE["field_bg"],
        fg=PALETTE["text"],
        relief="solid",
        bd=1,
        highlightthickness=2,
        highlightbackground=PALETTE["border_strong"],
        highlightcolor=PALETTE["accent"],
        insertbackground=PALETTE["text"],
        font=(FONT_UI, 9),
    )
    _safe_configure(
        widget,
        selectbackground=PALETTE["accent"],
        selectforeground="#FFFFFF",
    )
    if multiline:
        widget.configure(padx=8, pady=8)
    else:
        _safe_configure(
            widget,
            buttonbackground=PALETTE["surface_alt"],
            readonlybackground=PALETTE["field_bg"],
        )
    widget.bind("<FocusIn>", lambda _event: _set_input_focus(widget, True))
    widget.bind("<FocusOut>", lambda _event: _set_input_focus(widget, False))
    _set_input_focus(widget, False)
    return widget


def build_emphasis_checkbutton(parent: tk.Widget, *, text: str, variable: tk.BooleanVar) -> tk.Checkbutton:
    button = tk.Checkbutton(
        parent,
        text=text,
        variable=variable,
        anchor="w",
        justify="left",
        bg=PALETTE["surface"],
        fg=PALETTE["text"],
        selectcolor=PALETTE["accent_soft"],
        activeforeground=PALETTE["text"],
        activebackground=PALETTE["surface"],
        relief="solid",
        bd=1,
        highlightthickness=2,
        highlightbackground=PALETTE["border_strong"],
        cursor="hand2",
        font=(FONT_UI, 9, "bold"),
        indicatoron=False,
        padx=10,
        pady=6,
    )

    def sync_state(*_args: object) -> None:
        enabled = bool(variable.get())
        button.configure(
            bg=PALETTE["field_bg"],
            activebackground=PALETTE["field_bg"],
            highlightbackground=PALETTE["accent"] if enabled else PALETTE["border_strong"],
            fg=PALETTE["text"],
        )

    variable.trace_add("write", sync_state)
    sync_state()
    return button


def make_clickable(widget: tk.Widget, callback) -> None:
    widget.bind("<Button-1>", lambda _event: callback())
    for child in widget.winfo_children():
        make_clickable(child, callback)


def set_hover_state(widget: tk.Widget, *, normal_bg: str, hover_bg: str) -> None:
    def on_enter(_event) -> None:
        _apply_bg(widget, hover_bg)

    def on_leave(_event) -> None:
        _apply_bg(widget, normal_bg)

    widget.bind("<Enter>", on_enter)
    widget.bind("<Leave>", on_leave)
    for child in widget.winfo_children():
        child.bind("<Enter>", on_enter)
        child.bind("<Leave>", on_leave)


def add_value_row(parent: tk.Widget, *, row: int, label: str, value_var: tk.StringVar) -> None:
    parent.grid_columnconfigure(1, weight=1)
    tk.Label(
        parent,
        text=label,
        bg=parent.cget("bg"),
        fg=PALETTE["muted"],
        font=(FONT_UI, 9),
    ).grid(row=row, column=0, sticky="nw", padx=(0, 16), pady=4)
    tk.Label(
        parent,
        textvariable=value_var,
        bg=PALETTE["field_bg"],
        fg=PALETTE["text"],
        justify="left",
        wraplength=320,
        font=(FONT_UI, 9),
        padx=10,
        pady=6,
        relief="flat",
        bd=0,
        highlightthickness=1,
        highlightbackground=PALETTE["border"],
        anchor="w",
    ).grid(row=row, column=1, sticky="ew", pady=4)


def format_alive(value: object) -> str:
    return "正常" if bool(value) else "未运行"


def _set_input_focus(widget: tk.Widget, focused: bool) -> None:
    widget.configure(
        bg=PALETTE["surface"] if focused else PALETTE["field_bg"],
        highlightbackground=PALETTE["accent"] if focused else PALETTE["border_strong"],
        highlightcolor=PALETTE["accent"],
    )


def _set_button_hover(button: tk.Button, *, hovered: bool) -> None:
    if str(button.cget("state")) == "disabled":
        return
    button.configure(
        bg=button._hover_bg if hovered else button._normal_bg,
        highlightbackground=button._hover_border if hovered else button._border_color,
    )


def _set_nav_button_hover(button: tk.Button, *, hovered: bool) -> None:
    if getattr(button, "_nav_active", False):
        return
    button.configure(
        bg=PALETTE["field_hover"] if hovered else PALETTE["surface"],
        fg=PALETTE["text"] if hovered else PALETTE["muted"],
    )


def _apply_bg(widget: tk.Widget, color: str) -> None:
    try:
        widget.configure(bg=color)
    except tk.TclError:
        return
    for child in widget.winfo_children():
        try:
            child.configure(bg=color)
        except tk.TclError:
            continue


def _safe_configure(widget: tk.Widget, **kwargs: object) -> None:
    for key, value in kwargs.items():
        try:
            widget.configure(**{key: value})
        except tk.TclError:
            continue
