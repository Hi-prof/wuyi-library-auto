from __future__ import annotations

import os
import subprocess
from collections import deque
from pathlib import Path
from typing import Any

from wuyi_seat_bot.network_monitor import (
    NetworkMonitor,
    build_network_monitor_log_path,
    load_network_monitor_status,
)
from wuyi_seat_bot.service_manager import build_service_log_paths, load_service_status
from wuyi_seat_bot.settings_store import load_app_settings, save_app_settings
from wuyi_seat_bot.stability_enhancement import StabilityEnhancementManager

LOG_PREVIEW_LINE_COUNT = 12
EMPTY_LOG_PREVIEW = "暂无日志"


class DesktopSettingsService:
    def __init__(self, config_path: str | Path) -> None:
        self.config_path = Path(config_path).resolve()
        self.network_monitor = NetworkMonitor(self.config_path)
        self.stability_manager = StabilityEnhancementManager(self.config_path)
        self.supervisor_log_path, self.worker_log_path = build_service_log_paths(self.config_path)
        self.network_monitor_log_path = build_network_monitor_log_path(self.config_path)

    def get_payload(
        self,
        *,
        message: str = "",
        network_status: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        settings = load_app_settings(self.config_path)
        resolved_network_status = network_status or load_network_monitor_status(self.config_path)
        supervisor_status, worker_status = load_service_status(self.config_path)
        return {
            "settings": settings,
            "networkStatus": resolved_network_status,
            "serviceSnapshot": self._build_service_snapshot(supervisor_status, worker_status),
            "stabilityEnhancementEnabled": self.stability_manager.is_enabled(),
            "diagnostics": {
                "workerLogPath": str(self.worker_log_path),
                "supervisorLogPath": str(self.supervisor_log_path),
                "networkMonitorLogPath": str(self.network_monitor_log_path),
                "logDirectoryPath": str(self.supervisor_log_path.parent),
                "recentLogsPreview": self._build_recent_logs_preview(),
            },
            "message": message,
        }

    def save_settings(self, payload: dict[str, Any]) -> dict[str, Any]:
        save_app_settings(self.config_path, payload)
        return self.get_payload(message="设置已保存")

    def run_network_check(self) -> dict[str, Any]:
        network_status = self.network_monitor.detect_once()
        return self.get_payload(message="已完成网络检测", network_status=network_status)

    def run_network_reconnect(self) -> dict[str, Any]:
        network_status = self.network_monitor.reconnect_once()
        return self.get_payload(message="已尝试网络重连", network_status=network_status)

    def set_stability_enhancement(self, enabled: bool) -> dict[str, Any]:
        message = self.stability_manager.enable() if enabled else self.stability_manager.disable()
        return self.get_payload(message=message)

    def open_log_target(self, target: str) -> dict[str, Any]:
        target_map = {
            "workerLog": self.worker_log_path,
            "supervisorLog": self.supervisor_log_path,
            "networkMonitorLog": self.network_monitor_log_path,
            "logDirectory": self.supervisor_log_path.parent,
        }
        if target not in target_map:
            raise ValueError("target 仅支持 workerLog、supervisorLog、networkMonitorLog 或 logDirectory")

        target_path = target_map[target]
        if target == "logDirectory":
            target_path.mkdir(parents=True, exist_ok=True)
            os.startfile(str(target_path))
            return self.get_payload(message="已打开日志目录")

        target_path.parent.mkdir(parents=True, exist_ok=True)
        if not target_path.exists():
            target_path.write_text("", encoding="utf-8")
        subprocess.Popen(["notepad.exe", str(target_path)])
        target_label = {
            "workerLog": "工作日志",
            "supervisorLog": "守护日志",
            "networkMonitorLog": "网络诊断",
        }[target]
        return self.get_payload(message=f"已打开{target_label}")

    def clear_logs(self) -> dict[str, Any]:
        log_directory = self.supervisor_log_path.parent
        log_directory.mkdir(parents=True, exist_ok=True)

        cleared_targets = 0
        persistent_log_names = {self.worker_log_path.name, self.supervisor_log_path.name}
        persistent_log_names.add(self.network_monitor_log_path.name)
        for log_path in self._collect_log_targets(log_directory):
            if log_path.name in persistent_log_names:
                log_path.parent.mkdir(parents=True, exist_ok=True)
                log_path.write_text("", encoding="utf-8")
            else:
                log_path.unlink(missing_ok=True)
            cleared_targets += 1

        return self.get_payload(message=f"已清空 {cleared_targets} 个日志文件")

    def _build_service_snapshot(
        self,
        supervisor_status: dict[str, Any] | None,
        worker_status: dict[str, Any] | None,
    ) -> dict[str, Any]:
        return {
            "supervisor": supervisor_status or {"state": "missing"},
            "worker": worker_status or {"state": "missing"},
        }

    def _collect_log_targets(self, log_directory: Path) -> list[Path]:
        targets: list[Path] = [
            self.worker_log_path,
            self.supervisor_log_path,
            self.network_monitor_log_path,
        ]
        for path in sorted(log_directory.glob("*.log*")):
            if path not in targets:
                targets.append(path)
        return targets

    def _build_recent_logs_preview(self) -> str:
        sections = (
            self._build_log_section("工作日志", self.worker_log_path),
            self._build_log_section("守护日志", self.supervisor_log_path),
            self._build_log_section("网络诊断", self.network_monitor_log_path),
        )
        return "\n\n".join(sections)

    def _build_log_section(self, label: str, log_path: Path) -> str:
        lines = self._read_log_tail(log_path)
        preview = "\n".join(lines) if lines else EMPTY_LOG_PREVIEW
        return f"[{label}]\n{preview}"

    def _read_log_tail(self, log_path: Path) -> list[str]:
        if not log_path.exists():
            return []
        with log_path.open("r", encoding="utf-8", errors="replace") as handle:
            return [line.rstrip() for line in deque(handle, maxlen=LOG_PREVIEW_LINE_COUNT) if line.strip()]
