from __future__ import annotations

import ctypes
import json
import re
import subprocess
from pathlib import Path

from wuyi_seat_bot.config import resolve_project_path

STABILITY_STATE_RELATIVE_PATH = "runtime/stability_enhancement.json"
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
ES_CONTINUOUS = 0x80000000
ES_SYSTEM_REQUIRED = 0x00000001
ES_AWAYMODE_REQUIRED = 0x00000040

POWER_SETTINGS: tuple[tuple[str, bool, str, str, int], ...] = (
    ("sleepTimeoutAc", False, "sub_sleep", "standbyidle", 0),
    ("sleepTimeoutDc", True, "sub_sleep", "standbyidle", 0),
    ("lidActionAc", False, "sub_buttons", "lidaction", 0),
    ("lidActionDc", True, "sub_buttons", "lidaction", 0),
)

POWERCFG_CURRENT_VALUE_PATTERNS = {
    False: (
        re.compile(r"Current\s+AC\s+Power\s+Setting\s+Index\s*[:：]\s*0x([0-9a-fA-F]+)", re.IGNORECASE),
        re.compile(r"当前交流电源设置索引\s*[:：]\s*0x([0-9a-fA-F]+)", re.IGNORECASE),
    ),
    True: (
        re.compile(r"Current\s+DC\s+Power\s+Setting\s+Index\s*[:：]\s*0x([0-9a-fA-F]+)", re.IGNORECASE),
        re.compile(r"当前直流电源设置索引\s*[:：]\s*0x([0-9a-fA-F]+)", re.IGNORECASE),
    ),
}


class StabilityEnhancementManager:
    def __init__(self, config_path: str | Path) -> None:
        self.config_path = Path(config_path).resolve()
        self.state_path = resolve_project_path(self.config_path, STABILITY_STATE_RELATIVE_PATH)

    def is_enabled(self) -> bool:
        return self.state_path.exists()

    def enable(self) -> str:
        if self.is_enabled():
            if not _set_thread_execution_state():
                raise RuntimeError("调用系统防睡眠接口失败")
            return "程序稳定性增强已启用"

        original_settings = self._capture_current_settings()
        _write_state_file(self.state_path, original_settings)
        try:
            if not _set_thread_execution_state():
                raise RuntimeError("调用系统防睡眠接口失败")
            self._apply_target_settings()
        except Exception:
            self._restore_settings(original_settings)
            _clear_thread_execution_state()
            _delete_state_file(self.state_path)
            raise
        return "已启用程序稳定性增强"

    def disable(self) -> str:
        state = _load_state_file(self.state_path)
        if state is None:
            _clear_thread_execution_state()
            return "程序稳定性增强未启用"

        self._restore_settings(state)
        _clear_thread_execution_state()
        _delete_state_file(self.state_path)
        return "已关闭程序稳定性增强"

    def recover_if_needed(self) -> str | None:
        state = _load_state_file(self.state_path)
        if state is None:
            return None

        self._restore_settings(state)
        _clear_thread_execution_state()
        _delete_state_file(self.state_path)
        return "检测到上次未清理的稳定性增强状态，已自动恢复原电源策略"

    def _capture_current_settings(self) -> dict[str, int]:
        return {
            key: _query_power_setting_value(subgroup, setting, on_battery)
            for key, on_battery, subgroup, setting, _ in POWER_SETTINGS
        }

    def _apply_target_settings(self) -> None:
        for _, on_battery, subgroup, setting, target_value in POWER_SETTINGS:
            _apply_power_setting(subgroup, setting, target_value, on_battery)

    def _restore_settings(self, state: dict[str, int]) -> None:
        for key, on_battery, subgroup, setting, _ in POWER_SETTINGS:
            _apply_power_setting(subgroup, setting, int(state[key]), on_battery)


def _query_power_setting_value(subgroup: str, setting: str, on_battery: bool) -> int:
    output = _run_powercfg(["/query", "scheme_current", subgroup, setting])
    for pattern in POWERCFG_CURRENT_VALUE_PATTERNS[on_battery]:
        match = pattern.search(output)
        if match is not None:
            return int(match.group(1), 16)
    raise RuntimeError(f"无法读取当前电源设置值：{subgroup}/{setting}")


def _apply_power_setting(subgroup: str, setting: str, value: int, on_battery: bool) -> None:
    command = "/setdcvalueindex" if on_battery else "/setacvalueindex"
    _run_powercfg([command, "scheme_current", subgroup, setting, str(value)])
    _run_powercfg(["/setactive", "scheme_current"])


def _run_powercfg(arguments: list[str]) -> str:
    completed = subprocess.run(
        ["powercfg", *arguments],
        capture_output=True,
        text=True,
        creationflags=CREATE_NO_WINDOW,
        check=False,
    )
    if completed.returncode == 0:
        return completed.stdout
    error_message = completed.stderr.strip() or completed.stdout.strip() or "powercfg 执行失败"
    raise RuntimeError(error_message)


def _set_thread_execution_state() -> bool:
    return _call_set_thread_execution_state(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED)


def _clear_thread_execution_state() -> bool:
    return _call_set_thread_execution_state(ES_CONTINUOUS)


def _call_set_thread_execution_state(flags: int) -> bool:
    try:
        return bool(ctypes.windll.kernel32.SetThreadExecutionState(flags))
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(f"调用系统防睡眠接口失败：{exc}") from exc


def _load_state_file(path: Path) -> dict[str, int] | None:
    if not path.exists():
        return None
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise RuntimeError("稳定性增强状态文件格式无效")
    return {key: int(payload[key]) for key, *_ in POWER_SETTINGS}


def _write_state_file(path: Path, payload: dict[str, int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _delete_state_file(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        return
