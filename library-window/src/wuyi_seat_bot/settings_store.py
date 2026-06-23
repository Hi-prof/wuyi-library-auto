from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from wuyi_seat_bot.config import resolve_project_path

APP_SETTINGS_RELATIVE_PATH = "runtime/app_settings.json"
DEFAULT_CAMPUS_LOGIN_URL = ""
DEFAULT_APP_SETTINGS = {
    "networkMonitoring": {
        "enabled": True,
        "intervalMinutes": 120,
        "preferredWifiNames": [],
    },
    "campusNetwork": {
        "enabled": True,
        "wifiName": "WYU",
        "loginUrl": DEFAULT_CAMPUS_LOGIN_URL,
        "username": "",
        "password": "",
    }
}
MIN_NETWORK_INTERVAL_MINUTES = 30
MAX_NETWORK_INTERVAL_MINUTES = 720


def load_app_settings(config_path: str | Path) -> dict[str, Any]:
    settings_path = _resolve_app_settings_path(config_path)
    if not settings_path.exists():
        return _clone_default_settings()
    payload = json.loads(settings_path.read_text(encoding="utf-8"))
    return _normalize_app_settings(payload)


def save_app_settings(config_path: str | Path, payload: dict[str, Any]) -> dict[str, Any]:
    normalized = _normalize_app_settings(payload)
    settings_path = _resolve_app_settings_path(config_path)
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(
        json.dumps(normalized, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return normalized


def _resolve_app_settings_path(config_path: str | Path) -> Path:
    return resolve_project_path(config_path, APP_SETTINGS_RELATIVE_PATH)


def _clone_default_settings() -> dict[str, Any]:
    return json.loads(json.dumps(DEFAULT_APP_SETTINGS, ensure_ascii=False))


def _normalize_app_settings(payload: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError("应用设置必须是 JSON 对象")

    defaults = _clone_default_settings()["networkMonitoring"]
    network_payload = payload.get("networkMonitoring")
    if network_payload is None:
        network_payload = {}
    if not isinstance(network_payload, dict):
        raise ValueError("networkMonitoring 必须是对象")

    enabled = bool(network_payload.get("enabled", defaults["enabled"]))
    interval_minutes = _normalize_interval_minutes(
        network_payload.get("intervalMinutes", defaults["intervalMinutes"])
    )
    preferred_wifi_names = _normalize_wifi_names(
        network_payload.get("preferredWifiNames", defaults["preferredWifiNames"])
    )
    campus_defaults = _clone_default_settings()["campusNetwork"]
    campus_payload = payload.get("campusNetwork")
    if campus_payload is None:
        campus_payload = {}
    if not isinstance(campus_payload, dict):
        raise ValueError("campusNetwork 必须是对象")

    campus_enabled = bool(campus_payload.get("enabled", campus_defaults["enabled"]))
    campus_wifi_name = _normalize_text_field(
        campus_payload.get("wifiName", campus_defaults["wifiName"]),
        field_name="wifiName",
        allow_empty=False,
    )
    campus_login_url = _normalize_text_field(
        campus_payload.get("loginUrl", campus_defaults["loginUrl"]),
        field_name="loginUrl",
        allow_empty=True,
    )
    campus_username = _normalize_text_field(
        campus_payload.get("username", campus_defaults["username"]),
        field_name="username",
        allow_empty=True,
    )
    return {
        "networkMonitoring": {
            "enabled": enabled,
            "intervalMinutes": interval_minutes,
            "preferredWifiNames": preferred_wifi_names,
        },
        "campusNetwork": {
            "enabled": campus_enabled,
            "wifiName": campus_wifi_name,
            "loginUrl": campus_login_url,
            "username": campus_username,
            "password": _normalize_password(
                campus_payload.get("password", campus_defaults["password"])
            ),
        }
    }


def _normalize_interval_minutes(value: object) -> int:
    try:
        interval_minutes = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("网络检测间隔必须是整数分钟") from exc
    if not MIN_NETWORK_INTERVAL_MINUTES <= interval_minutes <= MAX_NETWORK_INTERVAL_MINUTES:
        raise ValueError(
            f"网络检测间隔必须在 {MIN_NETWORK_INTERVAL_MINUTES} 到 {MAX_NETWORK_INTERVAL_MINUTES} 分钟之间"
        )
    return interval_minutes


def _normalize_wifi_names(value: object) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ValueError("preferredWifiNames 必须是数组")

    normalized_names: list[str] = []
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, str):
            raise ValueError("Wi-Fi 名称必须是字符串")
        wifi_name = item.strip()
        if not wifi_name or wifi_name in seen:
            continue
        seen.add(wifi_name)
        normalized_names.append(wifi_name)
    return normalized_names


def _normalize_text_field(value: object, *, field_name: str, allow_empty: bool) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise ValueError(f"{field_name} 必须是字符串")
    normalized = value.strip()
    if normalized or allow_empty:
        return normalized
    raise ValueError(f"{field_name} 不能为空")


def _normalize_password(value: object) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise ValueError("password 必须是字符串")
    return value.strip()
