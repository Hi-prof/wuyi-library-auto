from __future__ import annotations

import base64
import binascii
import html
import http.client
import json
import locale
import os
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from wuyi_seat_bot.config import resolve_project_path
from wuyi_seat_bot.settings_store import load_app_settings, save_app_settings
from wuyi_seat_bot.version import __version__

NETWORK_MONITOR_STATUS_RELATIVE_PATH = "runtime/network_monitor_status.json"
NETWORK_MONITOR_LOG_RELATIVE_PATH = "runtime/logs/network-monitor.log"
GENERAL_CONNECTIVITY_PING_HOST = "www.baidu.com"
GENERAL_CONNECTIVITY_HTTP_URL = "https://www.baidu.com/"
TARGET_CONNECTIVITY_URL = "https://wuyiu.huitu.zhishulib.com/"
CONNECTIVITY_TIMEOUT_SECONDS = 5
GENERAL_CONNECTIVITY_PING_REQUEST_COUNT = 1
GENERAL_CONNECTIVITY_PING_WAIT_MILLISECONDS = 2000
NETWORK_MONITOR_LOG_TEXT_LIMIT = 240
RECONNECT_RETRY_WAIT_SECONDS = 5
AUTH_RETRY_WAIT_SECONDS = 3
WIFI_DISCONNECT_WAIT_SECONDS = 1
CAMPUS_LOGIN_DISCOVERY_RETRY_ATTEMPTS = 6
CAMPUS_LOGIN_DISCOVERY_RETRY_WAIT_SECONDS = 3
CAMPUS_LOGIN_BROWSER_DISCOVERY_TIMEOUT_SECONDS = 45
CAMPUS_LOGIN_BROWSER_DISCOVERY_POLL_SECONDS = 1
CAMPUS_LOGIN_BROWSER_DISCOVERY_URL = "http://baidu.com/"
CAMPUS_LOGIN_BROWSER_SHUTDOWN_TIMEOUT_SECONDS = 5
CAMPUS_LOGIN_BROWSER_SHUTDOWN_POLL_SECONDS = 0.2
CAMPUS_LOGIN_BROWSER_TASKKILL_TIMEOUT_SECONDS = 5
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
SUCCESS_PAGE_MARKER = "success.jsp"
SUCCESS_TEXT_MARKER = "您已成功连接校园网!"
CAMPUS_LOGIN_REQUIRED_FIELD_IDS = (
    "login-page-flowkey",
    "login-croypto",
)
CAMPUS_LOGIN_DEFAULT_TYPE = "UsernamePassword"
CAMPUS_LOGIN_DEFAULT_CAPTCHA_PAYLOAD = "{}"
CAMPUS_LOGIN_REQUEST_EXCEPTIONS = (
    TimeoutError,
    urllib.error.URLError,
    urllib.error.HTTPError,
    http.client.RemoteDisconnected,
)
CAMPUS_LOGIN_HANDLED_EXCEPTIONS = CAMPUS_LOGIN_REQUEST_EXCEPTIONS + (ValueError,)
CAMPUS_LOGIN_SUBMITTED_MESSAGE = "校园网认证已提交"
CAMPUS_LOGIN_HTTP_PROBE_URLS = (
    "http://www.msftconnecttest.com/connecttest.txt",
    "http://captive.apple.com/",
    "http://connectivitycheck.gstatic.com/generate_204",
)
CAMPUS_LOGIN_HTTP_PROBE_DIRECT_MARKERS = {
    "http://www.msftconnecttest.com/connecttest.txt": "Microsoft Connect Test",
    "http://captive.apple.com/": "<TITLE>Success</TITLE>",
}
CAMPUS_LOGIN_HTTP_REDIRECT_FOLLOW_LIMIT = 5
CAMPUS_LOGIN_ENTRY_PATH_HINTS = ("/login", "/cas/login", "/authserver/login")
CAMPUS_LOGIN_ENTRY_QUERY_KEYS = (
    "service",
    "ticket",
    "TARGET",
    "returnUrl",
    "return_url",
    "redirect_uri",
    "continue",
    "SAMLRequest",
)
CAMPUS_LOGIN_HTTP_REDIRECT_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(
        r"""(?:top|self|window|parent)(?:\.[a-z]+)*\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""",
        re.IGNORECASE,
    ),
    re.compile(
        r"""(?<![.\w])location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""",
        re.IGNORECASE,
    ),
    re.compile(
        r"""location\.replace\(\s*['"]([^'"]+)['"]\s*\)""",
        re.IGNORECASE,
    ),
    re.compile(
        r"""<meta\s+http-equiv=["']?refresh["']?[^>]*content=["'][^"']*url=([^"'>\s]+)""",
        re.IGNORECASE,
    ),
)


class NetworkMonitor:
    def __init__(self, config_path: str | Path) -> None:
        self.config_path = Path(config_path).resolve()
        self.status_path = resolve_project_path(
            self.config_path,
            NETWORK_MONITOR_STATUS_RELATIVE_PATH,
        )

    def load_settings(self) -> dict[str, Any]:
        return load_app_settings(self.config_path)

    def load_status(self) -> dict[str, Any]:
        return load_network_monitor_status(self.config_path)

    def detect_once(self) -> dict[str, Any]:
        connected_interfaces = list_connected_interfaces()
        if not connected_interfaces:
            return self._save_status(
                {
                    "networkState": "offline",
                    "message": "未检测到已连接网络接口",
                    "connectedInterfaces": [],
                }
            )

        if not probe_general_connectivity():
            return self._save_status(
                {
                    "networkState": "offline",
                    "message": "通用网络探测未通过，当前可能未联网",
                    "connectedInterfaces": connected_interfaces,
                }
            )

        if not probe_target_connectivity():
            return self._save_status(
                {
                    "networkState": "degraded",
                    "message": "学校目标站点连通性检测失败",
                    "connectedInterfaces": connected_interfaces,
                }
            )

        return self._save_status(
            {
                "networkState": "online",
                "message": "网络连接正常",
                "connectedInterfaces": connected_interfaces,
            }
        )

    def reconnect_once(self) -> dict[str, Any]:
        current_status = self.detect_once()
        if current_status["networkState"] == "online":
            return self._save_status(
                {
                    **current_status,
                    "reconnectState": "skipped",
                    "message": "当前网络已正常，无需重连",
                }
            )

        settings = self.load_settings()
        preferred_wifi_names = settings["networkMonitoring"]["preferredWifiNames"]
        candidates = _build_wifi_candidates(
            preferred_wifi_names,
            list_saved_wifi_profiles(),
        )
        if not candidates:
            return self._save_status(
                {
                    "networkState": "offline",
                    "message": "未找到已保存的 Wi-Fi 配置",
                    "reconnectState": "failed",
                    "wifiName": "",
                }
            )

        for wifi_name in candidates:
            if not try_connect_wifi(wifi_name):
                continue
            time.sleep(RECONNECT_RETRY_WAIT_SECONDS)
            detection = self.detect_once()
            if detection["networkState"] == "online":
                return self._save_status(
                    {
                        **detection,
                        "reconnectState": "reconnected",
                        "wifiName": wifi_name,
                        "message": f"已通过 {wifi_name} 恢复联网",
                    }
                )

        return self._save_status(
            {
                "networkState": "offline",
                "message": "已尝试连接所有已保存 Wi-Fi，但联网复检仍未通过",
                "reconnectState": "failed",
                "wifiName": candidates[-1],
            }
        )

    def authenticate_campus_network_once(self) -> dict[str, Any]:
        settings = self.load_settings()
        campus_network = settings["campusNetwork"]
        current_status = self._detect_stable_campus_status()
        if current_status["networkState"] == "online":
            return self._save_status(
                {
                    **current_status,
                    "reconnectState": "skipped",
                    "wifiName": str(campus_network.get("wifiName", "")).strip(),
                    "message": "当前网络已稳定联网，无需校园网认证",
                }
            )
        success, message = _perform_campus_network_login(self.config_path, campus_network)
        if not success:
            return self._save_status(
                {
                    **current_status,
                    "reconnectState": "failed",
                    "wifiName": str(campus_network.get("wifiName", "")).strip(),
                    "message": message,
                }
            )

        time.sleep(AUTH_RETRY_WAIT_SECONDS)
        detection = self.detect_once()
        reconnect_state = "authenticated" if detection["networkState"] == "online" else "failed"
        final_message = _resolve_campus_auth_result_message(
            message,
            reconnect_state=reconnect_state,
            failed_message="校园网认证已提交，但联网复检仍未通过",
        )
        return self._save_status(
            {
                **detection,
                "reconnectState": reconnect_state,
                "wifiName": str(campus_network.get("wifiName", "")).strip(),
                "message": final_message,
            }
        )

    def switch_to_campus_wifi_once(self) -> dict[str, Any]:
        settings = self.load_settings()
        campus_network = settings["campusNetwork"]
        wifi_name = str(campus_network.get("wifiName", "")).strip()
        if not wifi_name:
            current_status = self.detect_once()
            _append_network_monitor_log(
                self.config_path,
                title="切换校园网未执行",
                detail_lines=[
                    "目标 Wi-Fi：(空)",
                    "原因：请先填写校园网 Wi-Fi 名称",
                ],
            )
            return self._save_status(
                {
                    **current_status,
                    "reconnectState": "failed",
                    "wifiName": "",
                    "message": "请先填写校园网 Wi-Fi 名称",
                }
            )

        disconnect_completed = _run_netsh_wifi_command(["wlan", "disconnect"])
        time.sleep(WIFI_DISCONNECT_WAIT_SECONDS)
        connect_completed = _run_netsh_wifi_command(["wlan", "connect", f"name={wifi_name}"])
        if connect_completed.returncode != 0:
            detection = self.detect_once()
            _append_network_monitor_log(
                self.config_path,
                title="切换校园网失败",
                detail_lines=[
                    f"目标 Wi-Fi：{wifi_name}",
                    _build_wifi_command_result_line("断开当前 Wi-Fi", disconnect_completed),
                    _build_wifi_command_result_line(f"连接 {wifi_name}", connect_completed),
                    f"检测结果：{detection.get('message', '') or '(空)'}",
                ],
            )
            return self._save_status(
                {
                    **detection,
                    "reconnectState": "failed",
                    "wifiName": wifi_name,
                    "message": f"切换到 {wifi_name} 失败，请确认该 Wi-Fi 已保存到系统",
                }
            )

        time.sleep(RECONNECT_RETRY_WAIT_SECONDS)
        detection = self._detect_stable_campus_status()
        if detection["networkState"] == "online":
            _append_network_monitor_log(
                self.config_path,
                title="切换校园网成功",
                detail_lines=[
                    f"目标 Wi-Fi：{wifi_name}",
                    _build_wifi_command_result_line("断开当前 Wi-Fi", disconnect_completed),
                    _build_wifi_command_result_line(f"连接 {wifi_name}", connect_completed),
                    f"检测结果：{detection.get('message', '') or '(空)'}",
                ],
            )
            return self._save_status(
                {
                    **detection,
                    "reconnectState": "reconnected",
                    "wifiName": wifi_name,
                    "message": f"已切换到 {wifi_name} 并恢复联网",
                }
            )

        success, message = _perform_campus_network_login(self.config_path, campus_network)
        if not success:
            _append_network_monitor_log(
                self.config_path,
                title="切换校园网后认证失败",
                detail_lines=[
                    f"目标 Wi-Fi：{wifi_name}",
                    _build_wifi_command_result_line("断开当前 Wi-Fi", disconnect_completed),
                    _build_wifi_command_result_line(f"连接 {wifi_name}", connect_completed),
                    f"检测结果：{detection.get('message', '') or '(空)'}",
                    f"认证结果：{message}",
                ],
            )
            return self._save_status(
                {
                    **detection,
                    "reconnectState": "failed",
                    "wifiName": wifi_name,
                    "message": message,
                }
            )

        time.sleep(AUTH_RETRY_WAIT_SECONDS)
        verified_detection = self.detect_once()
        reconnect_state = "authenticated" if verified_detection["networkState"] == "online" else "failed"
        final_message = _resolve_campus_auth_result_message(
            message,
            reconnect_state=reconnect_state,
            failed_message=f"已切换到 {wifi_name} 并提交认证，但联网复检仍未通过",
        )
        _append_network_monitor_log(
            self.config_path,
            title="切换校园网后认证完成",
            detail_lines=[
                f"目标 Wi-Fi：{wifi_name}",
                _build_wifi_command_result_line("断开当前 Wi-Fi", disconnect_completed),
                _build_wifi_command_result_line(f"连接 {wifi_name}", connect_completed),
                f"认证结果：{message}",
                f"复检结果：{verified_detection.get('message', '') or '(空)'}",
            ],
        )
        return self._save_status(
            {
                **verified_detection,
                "reconnectState": reconnect_state,
                "wifiName": wifi_name,
                "message": final_message,
            }
        )

    def _detect_stable_campus_status(self) -> dict[str, Any]:
        detection = self.detect_once()
        if detection["networkState"] != "online":
            return detection

        for _ in range(CAMPUS_LOGIN_DISCOVERY_RETRY_ATTEMPTS - 1):
            time.sleep(CAMPUS_LOGIN_DISCOVERY_RETRY_WAIT_SECONDS)
            detection = self.detect_once()
            if detection["networkState"] != "online":
                return detection
        return detection

    def _authenticate_after_detection(
        self,
        detection: dict[str, Any],
        *,
        campus_network: dict[str, Any],
        wifi_name: str,
    ) -> dict[str, Any] | None:
        if str(detection.get("networkState", "")).strip() != "offline":
            return None
        connected_interfaces = [str(item).strip() for item in detection.get("connectedInterfaces", []) if str(item).strip()]
        if not connected_interfaces:
            return None
        success, message = _perform_campus_network_login(self.config_path, campus_network)
        if not success:
            return None
        time.sleep(AUTH_RETRY_WAIT_SECONDS)
        verified_detection = self.detect_once()
        if verified_detection["networkState"] != "online":
            return None
        resolved_wifi_name = wifi_name or str(campus_network.get("wifiName", "")).strip() or connected_interfaces[0]
        return self._save_status(
            {
                **verified_detection,
                "reconnectState": "authenticated",
                "wifiName": resolved_wifi_name,
                "message": f"已通过 {resolved_wifi_name} 完成校园网认证",
            }
        )

    def _save_status(self, payload: dict[str, Any]) -> dict[str, Any]:
        normalized = {
            "networkState": str(payload.get("networkState", "")).strip() or "unknown",
            "message": str(payload.get("message", "")).strip(),
            "connectedInterfaces": list(payload.get("connectedInterfaces", [])),
            "reconnectState": str(payload.get("reconnectState", "")).strip(),
            "wifiName": str(payload.get("wifiName", "")).strip(),
            "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        }
        self.status_path.parent.mkdir(parents=True, exist_ok=True)
        self.status_path.write_text(
            json.dumps(normalized, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return normalized


def load_network_monitor_status(config_path: str | Path) -> dict[str, Any]:
    status_path = resolve_project_path(config_path, NETWORK_MONITOR_STATUS_RELATIVE_PATH)
    if not status_path.exists():
        return {
            "networkState": "unknown",
            "message": "尚未执行网络检测",
            "connectedInterfaces": [],
            "reconnectState": "",
            "wifiName": "",
            "updatedAt": "",
        }
    payload = json.loads(status_path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("网络巡检状态文件格式无效")
    return payload


def list_connected_interfaces() -> list[str]:
    interfaces: list[str] = []
    seen: set[str] = set()
    for interface_name in [
        *_list_connected_interfaces_from_summary(),
        *_list_connected_wifi_interfaces(),
    ]:
        if interface_name in seen:
            continue
        seen.add(interface_name)
        interfaces.append(interface_name)
    return interfaces


def _list_connected_interfaces_from_summary() -> list[str]:
    output = _run_netsh_command(["interface", "show", "interface"])
    interfaces: list[str] = []
    for line in output.splitlines():
        stripped_line = line.strip()
        if not stripped_line:
            continue
        if "Connected" in stripped_line or "已连接" in stripped_line:
            parts = stripped_line.split()
            if len(parts) >= 4:
                interfaces.append(" ".join(parts[3:]))
    return interfaces


def _list_connected_wifi_interfaces() -> list[str]:
    output = _run_netsh_command(["wlan", "show", "interfaces"])
    interfaces: list[str] = []
    current_name = ""
    current_state = ""
    for line in output.splitlines():
        stripped_line = line.strip()
        if not stripped_line:
            _append_connected_wifi_interface(
                interfaces,
                current_name=current_name,
                current_state=current_state,
            )
            current_name = ""
            current_state = ""
            continue

        separator = ":" if ":" in line else "：" if "：" in line else ""
        if not separator:
            continue
        key, value = [part.strip() for part in line.split(separator, 1)]
        normalized_key = key.lower()
        if normalized_key in {"name", "名称"}:
            if current_name or current_state:
                _append_connected_wifi_interface(
                    interfaces,
                    current_name=current_name,
                    current_state=current_state,
                )
                current_state = ""
            current_name = value
            continue
        if normalized_key in {"state", "状态"}:
            current_state = value
    _append_connected_wifi_interface(
        interfaces,
        current_name=current_name,
        current_state=current_state,
    )
    return interfaces


def _append_connected_wifi_interface(
    interfaces: list[str],
    *,
    current_name: str,
    current_state: str,
) -> None:
    normalized_state = current_state.strip().lower()
    if not current_name or normalized_state not in {"connected", "已连接"}:
        return
    interfaces.append(current_name.strip())


def list_saved_wifi_profiles() -> list[str]:
    output = _run_netsh_command(["wlan", "show", "profiles"])
    profiles: list[str] = []
    for line in output.splitlines():
        separator = ":" if ":" in line else "：" if "：" in line else ""
        if not separator:
            continue
        left, right = line.split(separator, 1)
        if "Profile" not in left and "配置文件" not in left:
            continue
        profile_name = right.strip()
        if profile_name:
            profiles.append(profile_name)
    return profiles


def try_connect_wifi(wifi_name: str) -> bool:
    completed = _run_netsh_wifi_command(["wlan", "connect", f"name={wifi_name}"])
    return completed.returncode == 0


def disconnect_current_wifi() -> bool:
    completed = _run_netsh_wifi_command(["wlan", "disconnect"])
    return completed.returncode == 0


def _run_netsh_wifi_command(arguments: list[str]) -> subprocess.CompletedProcess[str]:
    return _run_windows_command(["netsh", *arguments])


def probe_general_connectivity() -> bool:
    if _probe_general_connectivity_by_ping():
        return True
    return _probe_url(GENERAL_CONNECTIVITY_HTTP_URL)


def _probe_general_connectivity_by_ping() -> bool:
    try:
        completed = subprocess.run(
            [
                "ping",
                "-n",
                str(GENERAL_CONNECTIVITY_PING_REQUEST_COUNT),
                "-w",
                str(GENERAL_CONNECTIVITY_PING_WAIT_MILLISECONDS),
                GENERAL_CONNECTIVITY_PING_HOST,
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            creationflags=CREATE_NO_WINDOW,
            check=False,
            timeout=CONNECTIVITY_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return False
    return completed.returncode == 0


def probe_target_connectivity() -> bool:
    return _probe_url(TARGET_CONNECTIVITY_URL)


def _probe_url(url: str) -> bool:
    request = urllib.request.Request(url, headers={"User-Agent": f"wuyi-seat-bot/{__version__}"})
    try:
        with urllib.request.urlopen(request, timeout=CONNECTIVITY_TIMEOUT_SECONDS) as response:
            return int(getattr(response, "status", 200)) < 500
    except (TimeoutError, urllib.error.URLError, urllib.error.HTTPError):
        return False

def _run_netsh_command(arguments: list[str]) -> str:
    completed = _run_windows_command(["netsh", *arguments])
    if completed.returncode == 0:
        return _normalize_command_output(completed.stdout)
    return _normalize_command_output(completed.stderr or completed.stdout)


def _run_windows_command(arguments: list[str]) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        arguments,
        capture_output=True,
        text=False,
        creationflags=CREATE_NO_WINDOW,
        check=False,
    )
    return subprocess.CompletedProcess(
        completed.args,
        completed.returncode,
        stdout=_decode_windows_command_output(completed.stdout),
        stderr=_decode_windows_command_output(completed.stderr),
    )


def _decode_windows_command_output(output: bytes | str | None) -> str:
    if output is None:
        return ""
    if isinstance(output, str):
        return output
    if not output:
        return ""
    for encoding in ("utf-8", _get_windows_command_encoding()):
        try:
            return output.decode(encoding)
        except UnicodeDecodeError:
            continue
    return output.decode(_get_windows_command_encoding(), errors="replace")


def _get_windows_command_encoding() -> str:
    get_encoding = getattr(locale, "getencoding", None)
    if callable(get_encoding):
        return get_encoding() or "utf-8"
    return locale.getpreferredencoding(False) or "utf-8"


def _build_wifi_candidates(
    preferred_wifi_names: list[str],
    saved_profiles: list[str],
) -> list[str]:
    saved_profile_set = set(saved_profiles)
    candidates: list[str] = []
    seen: set[str] = set()
    for wifi_name in preferred_wifi_names:
        if wifi_name in seen or wifi_name not in saved_profile_set:
            continue
        seen.add(wifi_name)
        candidates.append(wifi_name)
    return candidates


def _build_wifi_preferences(preferred_wifi_names: list[str], campus_wifi_name: str) -> list[str]:
    if not campus_wifi_name:
        return preferred_wifi_names
    return [campus_wifi_name, *preferred_wifi_names]


def build_network_monitor_log_path(config_path: str | Path) -> Path:
    return resolve_project_path(config_path, NETWORK_MONITOR_LOG_RELATIVE_PATH)


def discover_campus_login_url(
    config_path: str | Path,
    campus_network: dict[str, Any],
) -> str:
    campus_wifi_name = str(campus_network.get("wifiName", "")).strip() or "校园网"
    discovery_lines: list[str] = []
    login_url = _discover_campus_login_url(discovery_lines)
    if not login_url:
        detail_message = _build_browser_campus_login_url_discovery_message(campus_wifi_name)
        _append_network_monitor_log(
            config_path,
            title="校园网登录地址获取失败",
            detail_lines=[
                f"目标 Wi-Fi：{campus_wifi_name}",
                *discovery_lines,
                f"提示：{detail_message}",
            ],
        )
        raise ValueError(detail_message)

    _append_network_monitor_log(
        config_path,
        title="校园网登录地址获取成功",
        detail_lines=[
            f"目标 Wi-Fi：{campus_wifi_name}",
            f"登录地址：{login_url}",
            *discovery_lines,
        ],
    )
    return login_url


def _perform_campus_network_login(
    config_path: str | Path,
    campus_network: dict[str, Any],
) -> tuple[bool, str]:
    if not bool(campus_network.get("enabled", True)):
        return False, "校园网自动认证未启用"

    login_url = str(campus_network.get("loginUrl", "")).strip()
    username = str(campus_network.get("username", "")).strip()
    if (
        not username
        or not isinstance(campus_network.get("password", ""), str)
        or not campus_network.get("password", "")
    ):
        _append_network_monitor_log(
            config_path,
            title="校园网认证未执行",
            detail_lines=[
                f"登录地址：{login_url or '(空)'}",
                f"账号：{_mask_sensitive_text(username)}",
                "原因：请先填写校园网账号和密码",
            ],
        )
        return False, "请先填写校园网账号和密码"

    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor())
    login_page_url = login_url
    login_page_html = ""
    login_page_loaded = False
    response_url = ""
    response_html = ""
    payload_keys: list[str] = []
    missing_hidden_fields: list[str] = []
    discovery_lines: list[str] = []
    try:
        login_page_url, login_page_html = _resolve_campus_login_page_before_authentication(
            opener,
            config_path,
            campus_network,
            discovery_lines,
        )
        if _is_campus_login_entry_url(login_page_url):
            login_url = login_page_url
        login_page_loaded = True
        payload, missing_hidden_fields = _build_campus_login_payload(
            login_page_html,
            username,
            campus_network["password"],
        )
        if missing_hidden_fields:
            raise ValueError(f"登录页缺少必要字段：{', '.join(missing_hidden_fields)}")
        payload_keys = sorted(key for key in payload if key != "password")
        submit_url = _build_campus_login_submit_url(login_page_url)
        response_url, response_html = _open_url(
            opener,
            submit_url,
            data=urllib.parse.urlencode(payload).encode("utf-8"),
        )
    except CAMPUS_LOGIN_HANDLED_EXCEPTIONS as exc:
        raw_error_message = f"{type(exc).__name__}: {exc}"
        detail_message = _build_campus_login_exception_message(login_url, campus_network, exc)
        _append_network_monitor_log(
            config_path,
            title="校园网认证异常",
            detail_lines=_build_campus_network_diagnostic_lines(
                login_url=login_url,
                username=username,
                login_page_url=login_page_url,
                login_page_loaded=login_page_loaded,
                response_url=response_url,
                payload_keys=payload_keys,
                missing_hidden_fields=missing_hidden_fields,
                discovery_lines=discovery_lines,
                page_html=response_html or login_page_html,
                result_message=raw_error_message,
                detail_message=detail_message,
            ),
        )
        if _is_campus_login_success(_extract_exception_url(exc), ""):
            return True, "校园网认证成功"
        if _should_trust_campus_login_submission(
            exc,
            login_page_loaded=login_page_loaded,
            payload_keys=payload_keys,
        ):
            return True, CAMPUS_LOGIN_SUBMITTED_MESSAGE
        return False, f"校园网认证失败：{detail_message}"

    if _is_campus_login_success(response_url, response_html):
        _append_network_monitor_log(
            config_path,
            title="校园网认证成功",
            detail_lines=_build_campus_network_diagnostic_lines(
                login_url=login_url,
                username=username,
                login_page_url=login_page_url,
                login_page_loaded=login_page_loaded,
                response_url=response_url,
                payload_keys=payload_keys,
                missing_hidden_fields=missing_hidden_fields,
                discovery_lines=discovery_lines,
                page_html=response_html,
                result_message="校园网认证成功",
            ),
        )
        return True, "校园网认证成功"

    error_message = _extract_login_error_message(response_html)
    _append_network_monitor_log(
        config_path,
        title="校园网认证失败",
        detail_lines=_build_campus_network_diagnostic_lines(
            login_url=login_url,
            username=username,
            login_page_url=login_page_url,
            login_page_loaded=login_page_loaded,
            response_url=response_url,
            payload_keys=payload_keys,
            missing_hidden_fields=missing_hidden_fields,
            discovery_lines=discovery_lines,
            page_html=response_html,
            result_message=error_message,
        ),
    )
    return False, error_message


def _resolve_campus_login_page_before_authentication(
    opener: urllib.request.OpenerDirector,
    config_path: str | Path,
    campus_network: dict[str, Any],
    discovery_lines: list[str],
) -> tuple[str, str]:
    login_page_url = _discover_campus_login_url(discovery_lines)
    if not login_page_url:
        campus_wifi_name = str(campus_network.get("wifiName", "")).strip() or "校园网"
        raise ValueError(_build_browser_campus_login_url_discovery_message(campus_wifi_name))
    _save_campus_login_url_if_changed(
        config_path,
        campus_network,
        login_page_url,
        discovery_lines,
    )
    return _open_url(opener, login_page_url)


def _open_url(
    opener: urllib.request.OpenerDirector,
    url: str,
    *,
    data: bytes | None = None,
) -> tuple[str, str]:
    request = urllib.request.Request(
        url,
        data=data,
        headers={
            "User-Agent": f"wuyi-seat-bot/{__version__}",
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        },
    )
    with opener.open(request, timeout=CONNECTIVITY_TIMEOUT_SECONDS) as response:
        final_url = getattr(response, "url", url)
        body = response.read().decode("utf-8", errors="replace")
    return str(final_url), body


def _discover_campus_login_url(discovery_lines: list[str]) -> str:
    http_url = _discover_campus_login_url_with_http(discovery_lines)
    if http_url:
        return http_url
    return _discover_campus_login_url_with_browser(discovery_lines)


def _discover_campus_login_url_with_http(discovery_lines: list[str]) -> str:
    last_seen_url = ""
    for probe_url in CAMPUS_LOGIN_HTTP_PROBE_URLS:
        try:
            final_url, body, status = _http_probe_captive_portal(probe_url)
        except CAMPUS_LOGIN_REQUEST_EXCEPTIONS as exc:
            discovery_lines.append(
                f"HTTP 探测：{probe_url} -> 异常 {type(exc).__name__}: {exc}"
            )
            continue
        last_seen_url = final_url or probe_url
        if _is_campus_login_entry_url(final_url):
            discovery_lines.append(f"HTTP 探测：{probe_url} -> {final_url}")
            return final_url
        if _looks_like_direct_internet_response(probe_url, final_url, body, status):
            discovery_lines.append(
                f"HTTP 探测：{probe_url} -> 命中直连特征，未被校园网拦截"
            )
            continue
        candidate_url = _follow_campus_login_redirect_chain(
            final_url,
            body,
            discovery_lines,
        )
        if candidate_url:
            return candidate_url
        discovery_lines.append(
            f"HTTP 探测：{probe_url} -> {final_url or '(空)'}（未识别校园网入口）"
        )
    if last_seen_url:
        discovery_lines.append(
            f"HTTP 探测：最终落点 {last_seen_url}，未识别校园网入口"
        )
    return ""


def _http_probe_captive_portal(url: str) -> tuple[str, str, int]:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": f"wuyi-seat-bot/{__version__}"},
    )
    with urllib.request.urlopen(request, timeout=CONNECTIVITY_TIMEOUT_SECONDS) as response:
        final_url = str(getattr(response, "url", url))
        status_value = (
            getattr(response, "status", None)
            or getattr(response, "code", None)
            or 200
        )
        body = response.read().decode("utf-8", errors="replace")
    return final_url, body, int(status_value)


def _looks_like_direct_internet_response(
    probe_url: str,
    final_url: str,
    body: str,
    status: int,
) -> bool:
    if status == 204 and not body.strip():
        return True
    marker = CAMPUS_LOGIN_HTTP_PROBE_DIRECT_MARKERS.get(probe_url, "")
    if not marker or marker.lower() not in body.lower():
        return False
    return _extract_url_host(final_url) == _extract_url_host(probe_url)


def _follow_campus_login_redirect_chain(
    start_url: str,
    start_body: str,
    discovery_lines: list[str],
) -> str:
    visited: set[str] = {start_url}
    current_url = start_url
    current_body = start_body
    for _ in range(CAMPUS_LOGIN_HTTP_REDIRECT_FOLLOW_LIMIT):
        next_url = _extract_campus_login_redirect_target(current_url, current_body)
        if not next_url or next_url in visited:
            return ""
        visited.add(next_url)
        if _is_campus_login_entry_url(next_url):
            discovery_lines.append(f"HTTP 探测：跳转至 {next_url}")
            return next_url
        try:
            current_url, current_body, _ = _http_probe_captive_portal(next_url)
        except CAMPUS_LOGIN_REQUEST_EXCEPTIONS as exc:
            discovery_lines.append(
                f"HTTP 探测：{next_url} -> 异常 {type(exc).__name__}: {exc}"
            )
            return ""
        if _is_campus_login_entry_url(current_url):
            discovery_lines.append(f"HTTP 探测：跳转至 {current_url}")
            return current_url
    return ""


def _extract_campus_login_redirect_target(base_url: str, body: str) -> str:
    if not body:
        return ""
    for pattern in CAMPUS_LOGIN_HTTP_REDIRECT_PATTERNS:
        match = pattern.search(body)
        if not match:
            continue
        target = html.unescape(match.group(1)).strip().strip("\"'")
        if not target:
            continue
        return urllib.parse.urljoin(base_url, target)
    return ""


def _discover_campus_login_url_with_browser(discovery_lines: list[str]) -> str:
    browser_path = _find_chromium_browser_path()
    if not browser_path:
        discovery_lines.append("浏览器探测：未找到 Edge 或 Chrome")
        return ""

    port = _find_free_local_port()
    last_observed_url = ""
    observed_target_ids: set[str] = set()
    process: subprocess.Popen[bytes] | None = None
    with tempfile.TemporaryDirectory(
        prefix="wuyi-campus-login-",
        ignore_cleanup_errors=True,
    ) as user_data_dir:
        try:
            process = subprocess.Popen(
                [
                    browser_path,
                    f"--remote-debugging-port={port}",
                    f"--user-data-dir={user_data_dir}",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--new-window",
                    CAMPUS_LOGIN_BROWSER_DISCOVERY_URL,
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=CREATE_NO_WINDOW,
            )
            deadline = time.monotonic() + CAMPUS_LOGIN_BROWSER_DISCOVERY_TIMEOUT_SECONDS
            launcher_exit_logged = False
            while time.monotonic() < deadline:
                if process.poll() is not None and not launcher_exit_logged:
                    discovery_lines.append(
                        f"浏览器探测：浏览器启动器已退出，退出码 {process.returncode}，继续等待页面跳转"
                    )
                    launcher_exit_logged = True
                for target in _read_browser_page_targets(port):
                    target_id = str(target.get("id", "")).strip()
                    if target_id:
                        observed_target_ids.add(target_id)
                    current_url = str(target.get("url", "")).strip()
                    if current_url:
                        last_observed_url = current_url
                    if _is_campus_login_entry_url(current_url):
                        discovery_lines.append(
                            f"浏览器探测：{CAMPUS_LOGIN_BROWSER_DISCOVERY_URL} -> {current_url}"
                        )
                        return current_url
                time.sleep(CAMPUS_LOGIN_BROWSER_DISCOVERY_POLL_SECONDS)
        except OSError as exc:
            discovery_lines.append(f"浏览器探测：启动失败 {type(exc).__name__}: {exc}")
            return ""
        finally:
            _close_browser_page_targets(port, observed_target_ids, discovery_lines)
            _terminate_browser_process(process)

    discovery_lines.append(
        "浏览器探测："
        f"{CAMPUS_LOGIN_BROWSER_DISCOVERY_URL} -> {last_observed_url or '(空)'}，"
        "未识别校园网登录地址"
    )
    return ""


def _save_campus_login_url_if_changed(
    config_path: str | Path,
    campus_network: dict[str, Any],
    login_url: str,
    discovery_lines: list[str],
) -> None:
    configured_login_url = str(campus_network.get("loginUrl", "")).strip()
    if configured_login_url == login_url:
        return

    try:
        settings = load_app_settings(config_path)
        save_app_settings(
            config_path,
            {
                **settings,
                "campusNetwork": {
                    **settings["campusNetwork"],
                    **campus_network,
                    "loginUrl": login_url,
                },
            },
        )
    except Exception as exc:  # noqa: BLE001
        discovery_lines.append(
            f"认证前登录地址保存失败：{type(exc).__name__}: {exc}"
        )
        return

    discovery_lines.append(f"认证前登录地址已更新：{login_url}")


def _find_chromium_browser_path() -> str:
    candidate_paths = [
        _build_env_path("ProgramFiles", "Microsoft", "Edge", "Application", "msedge.exe"),
        _build_env_path("ProgramFiles(x86)", "Microsoft", "Edge", "Application", "msedge.exe"),
        _build_env_path("LocalAppData", "Microsoft", "Edge", "Application", "msedge.exe"),
        _build_env_path("ProgramFiles", "Google", "Chrome", "Application", "chrome.exe"),
        _build_env_path("ProgramFiles(x86)", "Google", "Chrome", "Application", "chrome.exe"),
        _build_env_path("LocalAppData", "Google", "Chrome", "Application", "chrome.exe"),
    ]
    for candidate_path in candidate_paths:
        if candidate_path and candidate_path.exists():
            return str(candidate_path)

    for command_name in ("msedge.exe", "chrome.exe"):
        executable_path = shutil.which(command_name)
        if executable_path:
            return executable_path
    return ""


def _build_env_path(env_name: str, *parts: str) -> Path | None:
    env_value = os.environ.get(env_name, "").strip()
    if not env_value:
        return None
    return Path(env_value).joinpath(*parts)


def _find_free_local_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _read_browser_page_targets(port: int) -> list[dict[str, str]]:
    try:
        with urllib.request.urlopen(
            f"http://127.0.0.1:{port}/json/list",
            timeout=1,
        ) as response:
            payload = json.loads(response.read().decode("utf-8", errors="replace"))
    except (OSError, TimeoutError, urllib.error.URLError, json.JSONDecodeError):
        return []
    if not isinstance(payload, list):
        return []
    targets: list[dict[str, str]] = []
    for item in payload:
        if not isinstance(item, dict):
            continue
        current_url = str(item.get("url", "")).strip()
        target_id = str(item.get("id", "")).strip()
        if current_url or target_id:
            targets.append({"id": target_id, "url": current_url})
    return targets


def _close_browser_page_targets(
    port: int,
    target_ids: set[str],
    discovery_lines: list[str],
) -> None:
    closed_target_ids: set[str] = set()
    deadline = time.monotonic() + CAMPUS_LOGIN_BROWSER_SHUTDOWN_TIMEOUT_SECONDS
    while True:
        pending_ids: set[str] = {
            target_id
            for target_id in target_ids
            if target_id and target_id not in closed_target_ids
        }
        for target in _read_browser_page_targets(port):
            target_id = str(target.get("id", "")).strip()
            if target_id and target_id not in closed_target_ids:
                pending_ids.add(target_id)
        if not pending_ids:
            break
        for target_id in sorted(pending_ids):
            try:
                close_url = (
                    f"http://127.0.0.1:{port}/json/close/"
                    f"{urllib.parse.quote(target_id, safe='')}"
                )
                with urllib.request.urlopen(close_url, timeout=1):
                    closed_target_ids.add(target_id)
            except (OSError, TimeoutError, urllib.error.URLError):
                # The browser side may have already gone away; consider this
                # target closed so we do not retry forever on a stuck handle.
                closed_target_ids.add(target_id)
        if time.monotonic() >= deadline:
            break
        time.sleep(CAMPUS_LOGIN_BROWSER_SHUTDOWN_POLL_SECONDS)
    if closed_target_ids:
        discovery_lines.append(
            f"浏览器探测：已关闭 {len(closed_target_ids)} 个临时页面"
        )


def _terminate_browser_process(process: subprocess.Popen[bytes] | None) -> None:
    if process is None:
        return
    pid = getattr(process, "pid", None)
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
    # Edge/Chrome's launcher often exits immediately after spawning the actual
    # browser worker process, leaving the worker (and any pages it opened)
    # alive. Killing only the launcher therefore leaks browser windows on every
    # discovery retry. Force-kill the entire process tree so that orphaned
    # browser instances do not pile up and freeze the machine.
    if pid:
        _kill_browser_process_tree(int(pid))


def _kill_browser_process_tree(pid: int) -> None:
    if pid <= 0:
        return
    try:
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(pid)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=CREATE_NO_WINDOW,
            check=False,
            timeout=CAMPUS_LOGIN_BROWSER_TASKKILL_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired):
        return


def _build_campus_login_payload(
    login_page_html: str,
    username: str,
    password: str,
) -> tuple[dict[str, str], list[str]]:
    hidden_fields = {
        "login-page-flowkey": _extract_element_value(login_page_html, "login-page-flowkey"),
        "login-croypto": _extract_element_value(login_page_html, "login-croypto"),
    }
    missing_hidden_fields = [
        field_name for field_name in CAMPUS_LOGIN_REQUIRED_FIELD_IDS if not hidden_fields[field_name]
    ]
    crypto_key = hidden_fields["login-croypto"]
    if missing_hidden_fields:
        return {}, missing_hidden_fields
    payload = {
        "username": username,
        "type": _extract_element_value(login_page_html, "current-login-type")
        or CAMPUS_LOGIN_DEFAULT_TYPE,
        "_eventId": "submit",
        "geolocation": "",
        "execution": hidden_fields["login-page-flowkey"],
        "captcha_code": "",
        "croypto": crypto_key,
        "password": _encrypt_campus_login_value(crypto_key, password),
        "captcha_payload": _encrypt_campus_login_value(
            crypto_key,
            CAMPUS_LOGIN_DEFAULT_CAPTCHA_PAYLOAD,
        ),
    }
    return payload, missing_hidden_fields


def _build_campus_login_submit_url(login_page_url: str) -> str:
    return urllib.parse.urldefrag(login_page_url).url


def _is_campus_login_entry_url(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return False
    path = parsed.path or ""
    if not _has_campus_login_path_hint(path):
        return False
    query_params = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    return any(
        any(value.strip() for value in query_params.get(key, []))
        for key in CAMPUS_LOGIN_ENTRY_QUERY_KEYS
    )


def _has_campus_login_path_hint(path: str) -> bool:
    for hint in CAMPUS_LOGIN_ENTRY_PATH_HINTS:
        if path.endswith(hint) or f"{hint}/" in path:
            return True
    return False


def _encrypt_campus_login_value(crypto_key: str, value: str) -> str:
    try:
        key_bytes = base64.b64decode(crypto_key, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("登录页加密参数无效") from exc
    if len(key_bytes) not in (16, 24, 32):
        raise ValueError("登录页加密参数长度无效")

    padder = padding.PKCS7(algorithms.AES.block_size).padder()
    padded_value = padder.update(value.encode("utf-8")) + padder.finalize()
    encryptor = Cipher(algorithms.AES(key_bytes), modes.ECB()).encryptor()
    encrypted_value = encryptor.update(padded_value) + encryptor.finalize()
    return base64.b64encode(encrypted_value).decode("ascii")


def _extract_element_value(login_page_html: str, element_id: str) -> str:
    patterns = (
        rf'id=["\']{re.escape(element_id)}["\'][^>]*value=["\']([^"\']+)["\']',
        rf'id=["\']{re.escape(element_id)}["\'][^>]*>(.*?)<',
    )
    for pattern in patterns:
        match = re.search(pattern, login_page_html, flags=re.IGNORECASE | re.DOTALL)
        if not match:
            continue
        return html.unescape(match.group(1)).strip()
    return ""


def _is_campus_login_success(response_url: str, response_html: str) -> bool:
    normalized_html = response_html.strip()
    return SUCCESS_PAGE_MARKER in response_url or SUCCESS_TEXT_MARKER in normalized_html


def _extract_login_error_message(response_html: str) -> str:
    for pattern in (
        r'role=["\']alert["\'][^>]*>(.*?)<',
        r'class=["\'][^"\']*(?:ant-form-explain|ant-message|error)[^"\']*["\'][^>]*>(.*?)<',
    ):
        match = re.search(pattern, response_html, flags=re.IGNORECASE | re.DOTALL)
        if not match:
            continue
        message = html.unescape(match.group(1)).strip()
        if message:
            return message
    return "校园网认证失败，请检查账号密码或登录页状态"


def _build_campus_login_exception_message(
    login_url: str,
    campus_network: dict[str, Any],
    exc: Exception,
) -> str:
    login_host = _extract_url_host(login_url)
    campus_wifi_name = str(campus_network.get("wifiName", "")).strip() or "校园网"
    if _is_dns_resolution_error(exc):
        if login_host:
            return f"无法解析 {login_host}，请确认已连接 {campus_wifi_name} 或 DNS 正常"
        return f"无法解析登录地址，请确认已连接 {campus_wifi_name} 或 DNS 正常"
    if _is_timeout_error(exc):
        if login_host:
            return f"访问 {login_host} 超时，请稍后重试"
        return "访问登录地址超时，请稍后重试"
    if _is_remote_disconnected_error(exc):
        if login_host:
            return f"{login_host} 提前断开连接，请确认已连接 {campus_wifi_name} 后再试"
        return f"认证页提前断开连接，请确认已连接 {campus_wifi_name} 后再试"
    return str(exc).strip() or type(exc).__name__


def _build_browser_campus_login_url_discovery_message(campus_wifi_name: str) -> str:
    return (
        f"未识别校园网认证入口，请确认已连接 {campus_wifi_name}（HTTP 探测和浏览器兜底均失败）"
    )


def _should_trust_campus_login_submission(
    exc: Exception,
    *,
    login_page_loaded: bool,
    payload_keys: list[str],
) -> bool:
    return _is_remote_disconnected_error(exc) and login_page_loaded and bool(payload_keys)


def _resolve_campus_auth_result_message(
    message: str,
    *,
    reconnect_state: str,
    failed_message: str,
) -> str:
    if reconnect_state == "failed":
        return failed_message
    if message == CAMPUS_LOGIN_SUBMITTED_MESSAGE:
        return "校园网认证成功"
    return message


def _build_campus_network_diagnostic_lines(
    *,
    login_url: str,
    username: str,
    login_page_url: str,
    login_page_loaded: bool,
    response_url: str,
    payload_keys: list[str],
    missing_hidden_fields: list[str],
    discovery_lines: list[str],
    page_html: str,
    result_message: str,
    detail_message: str = "",
) -> list[str]:
    lines = [
        f"登录地址：{login_url or '(空)'}",
        f"登录域名：{_extract_url_host(login_url) or '(空)'}",
        f"账号：{_mask_sensitive_text(username)}",
        f"登录页地址：{login_page_url or '(空)'}",
        f"登录页状态：{'已打开' if login_page_loaded else '未打开'}",
        f"返回地址：{response_url or '(空)'}",
        f"提交字段：{', '.join(payload_keys) if payload_keys else '(未生成表单)'}",
        "缺失隐藏字段：" + _format_missing_hidden_fields(
            login_page_loaded=login_page_loaded,
            missing_hidden_fields=missing_hidden_fields,
        ),
        f"结果：{result_message}",
    ]
    lines.extend(discovery_lines)
    if detail_message and detail_message != result_message:
        lines.append(f"提示：{detail_message}")
    snippet = _build_visible_text_snippet(page_html)
    if snippet:
        lines.append(f"页面文本片段：{snippet}")
    return lines


def _append_network_monitor_log(
    config_path: str | Path,
    *,
    title: str,
    detail_lines: list[str],
) -> None:
    log_path = build_network_monitor_log_path(config_path)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"{time.strftime('%Y-%m-%d %H:%M:%S')} [{title}]", *detail_lines, ""]
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def _build_wifi_command_result_line(label: str, completed: subprocess.CompletedProcess[str]) -> str:
    output = (completed.stdout or completed.stderr or "").strip()
    if not output:
        output = "(空)"
    output = re.sub(r"\s+", " ", output)
    if len(output) > NETWORK_MONITOR_LOG_TEXT_LIMIT:
        output = f"{output[:NETWORK_MONITOR_LOG_TEXT_LIMIT]}..."
    return f"{label}：returncode={completed.returncode}，输出={output}"


def _normalize_command_output(output: str | None) -> str:
    if isinstance(output, str):
        return output
    return ""


def _mask_sensitive_text(value: str) -> str:
    normalized = value.strip()
    if not normalized:
        return "(空)"
    if len(normalized) <= 4:
        return "*" * len(normalized)
    return f"{normalized[:3]}{'*' * (len(normalized) - 5)}{normalized[-2:]}"


def _build_visible_text_snippet(page_html: str) -> str:
    normalized = re.sub(
        r"<(?:script|style)\b[^>]*>.*?</(?:script|style)>",
        " ",
        page_html,
        flags=re.IGNORECASE | re.DOTALL,
    )
    normalized = re.sub(r"<[^>]+>", " ", normalized)
    normalized = html.unescape(normalized)
    normalized = re.sub(r"\s+", " ", normalized).strip()
    if len(normalized) <= NETWORK_MONITOR_LOG_TEXT_LIMIT:
        return normalized
    return f"{normalized[:NETWORK_MONITOR_LOG_TEXT_LIMIT]}..."


def _format_missing_hidden_fields(*, login_page_loaded: bool, missing_hidden_fields: list[str]) -> str:
    if not login_page_loaded:
        return "未检查"
    if not missing_hidden_fields:
        return "无"
    return ", ".join(missing_hidden_fields)


def _extract_url_host(url: str) -> str:
    return urllib.parse.urlparse(url).hostname or ""


def _is_dns_resolution_error(exc: Exception) -> bool:
    if not isinstance(exc, urllib.error.URLError):
        return False
    reason = exc.reason
    if isinstance(reason, socket.gaierror):
        return True
    return "getaddrinfo failed" in str(reason).lower()


def _is_timeout_error(exc: Exception) -> bool:
    if isinstance(exc, TimeoutError):
        return True
    if not isinstance(exc, urllib.error.URLError):
        return False
    reason = exc.reason
    return isinstance(reason, TimeoutError)


def _is_remote_disconnected_error(exc: Exception) -> bool:
    return isinstance(exc, http.client.RemoteDisconnected)


def _extract_exception_url(exc: Exception) -> str:
    return str(getattr(exc, "url", "") or getattr(exc, "filename", "") or "")
