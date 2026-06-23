from __future__ import annotations

import os
import subprocess
from http import HTTPStatus
from pathlib import Path
from typing import Any

from wuyi_seat_bot.config import resolve_project_path
from wuyi_seat_bot.network_monitor import load_network_monitor_status
from wuyi_seat_bot.settings_store import load_app_settings, save_app_settings
from wuyi_seat_bot.stability_enhancement import StabilityEnhancementManager
from wuyi_seat_bot.web_errors import ApiRequestError

LOG_DIRECTORY_RELATIVE_PATH = "runtime/logs"
SUPERVISOR_LOG_NAME = "service-supervisor.log"
WORKER_LOG_NAME = "service-worker.log"


def build_settings_log_paths(config_path: str | Path) -> tuple[Path, Path]:
    log_directory = resolve_project_path(config_path, LOG_DIRECTORY_RELATIVE_PATH)
    return log_directory / SUPERVISOR_LOG_NAME, log_directory / WORKER_LOG_NAME


def open_log_target(target: str, path: Path) -> None:
    if target == "logDirectory":
        path.mkdir(parents=True, exist_ok=True)
        os.startfile(str(path))
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text("", encoding="utf-8")
    subprocess.Popen(["notepad.exe", str(path)])


def build_settings_payload(
    *,
    config_path: str | Path,
    service_snapshot: dict[str, Any],
    settings: dict[str, Any] | None = None,
    network_status: dict[str, Any] | None = None,
    message: str = "",
    stability_enabled: bool | None = None,
) -> dict[str, Any]:
    resolved_settings = settings or load_app_settings(config_path)
    resolved_network_status = network_status or load_network_monitor_status(config_path)
    stability_manager = StabilityEnhancementManager(config_path)
    if stability_enabled is None:
        stability_enabled = stability_manager.is_enabled()
    supervisor_log_path, worker_log_path = build_settings_log_paths(config_path)
    return {
        "settings": resolved_settings,
        "networkStatus": resolved_network_status,
        "serviceSnapshot": service_snapshot,
        "stabilityEnhancementEnabled": stability_enabled,
        "diagnostics": {
            "workerLogPath": str(worker_log_path),
            "supervisorLogPath": str(supervisor_log_path),
            "logDirectoryPath": str(supervisor_log_path.parent),
        },
        "message": message,
    }


def get_settings_response(
    *, config_path: str | Path, service_snapshot: dict[str, Any]
) -> dict[str, Any]:
    return build_settings_payload(
        config_path=config_path,
        service_snapshot=service_snapshot,
    )


def save_settings_response(
    *,
    config_path: str | Path,
    payload: dict[str, Any],
    service_snapshot: dict[str, Any],
) -> dict[str, Any]:
    saved_settings = save_app_settings(config_path, payload)
    return build_settings_payload(
        config_path=config_path,
        service_snapshot=service_snapshot,
        settings=saved_settings,
    )


def build_network_settings_response(
    *,
    config_path: str | Path,
    network_status: dict[str, Any],
    service_snapshot: dict[str, Any],
) -> dict[str, Any]:
    return build_settings_payload(
        config_path=config_path,
        service_snapshot=service_snapshot,
        network_status=network_status,
    )


def update_stability_enhancement_response(
    *,
    config_path: str | Path,
    payload: dict[str, Any],
    service_snapshot: dict[str, Any],
) -> dict[str, Any]:
    enabled = bool(payload.get("enabled"))
    manager = StabilityEnhancementManager(config_path)
    try:
        message = manager.enable() if enabled else manager.disable()
    except Exception as exc:  # noqa: BLE001
        raise ApiRequestError(str(exc), HTTPStatus.BAD_REQUEST) from exc
    return build_settings_payload(
        config_path=config_path,
        service_snapshot=service_snapshot,
        message=message,
        stability_enabled=manager.is_enabled(),
    )


def open_diagnostics_target_response(
    *,
    config_path: str | Path,
    payload: dict[str, Any],
    service_snapshot: dict[str, Any],
) -> dict[str, Any]:
    target = str(payload.get("target", "")).strip()
    supervisor_log_path, worker_log_path = build_settings_log_paths(config_path)
    target_map = {
        "workerLog": worker_log_path,
        "supervisorLog": supervisor_log_path,
        "logDirectory": supervisor_log_path.parent,
    }
    if target not in target_map:
        raise ApiRequestError("target 仅支持 workerLog、supervisorLog 或 logDirectory")
    open_log_target(target, target_map[target])
    target_label = {
        "workerLog": "工作日志",
        "supervisorLog": "守护日志",
        "logDirectory": "日志目录",
    }[target]
    return build_settings_payload(
        config_path=config_path,
        service_snapshot=service_snapshot,
        message=f"已打开{target_label}",
    )
