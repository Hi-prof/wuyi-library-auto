from collections.abc import Callable
import http.client
import tempfile
import unittest
import urllib.parse
import socket
import urllib.error
from pathlib import Path
from subprocess import CompletedProcess, TimeoutExpired
from unittest.mock import patch

from wuyi_seat_bot.network_monitor import (
    NetworkMonitor,
    _build_campus_login_payload,
    _build_campus_login_submit_url,
    _build_wifi_candidates,
    _close_browser_page_targets,
    _decode_windows_command_output,
    _discover_campus_login_url_with_browser,
    _discover_campus_login_url_with_http,
    _extract_campus_login_redirect_target,
    _is_campus_login_entry_url,
    _perform_campus_network_login,
    _run_netsh_wifi_command,
    _terminate_browser_process,
    build_network_monitor_log_path,
    discover_campus_login_url,
    list_connected_interfaces,
    probe_general_connectivity,
)
from wuyi_seat_bot.settings_store import load_app_settings


class NetworkMonitorTestCase(unittest.TestCase):
    def setUp(self) -> None:
        sleep_patcher = patch("wuyi_seat_bot.network_monitor.time.sleep")
        sleep_patcher.start()
        self.addCleanup(sleep_patcher.stop)

    @staticmethod
    def _browser_discovery(login_url: str, *extra_lines: str) -> Callable[[list[str]], str]:
        def discover(discovery_lines: list[str]) -> str:
            discovery_lines.append(f"浏览器探测：http://baidu.com/ -> {login_url}")
            discovery_lines.extend(extra_lines)
            return login_url

        return discover

    @staticmethod
    def _browser_discovery_failure(message: str) -> Callable[[list[str]], str]:
        def discover(discovery_lines: list[str]) -> str:
            discovery_lines.append(message)
            return ""

        return discover

    def test_list_connected_interfaces_falls_back_to_wlan_interfaces_when_summary_is_inaccurate(self) -> None:
        def fake_run_netsh(arguments: list[str]) -> str:
            if arguments == ["interface", "show", "interface"]:
                return (
                    "Admin State    State          Type             Interface Name\n"
                    "-------------------------------------------------------------------------\n"
                    "Disabled       Connecting     Dedicated        WLAN\n"
                    "Disabled       Connecting     Dedicated        FlClash\n"
                )
            if arguments == ["wlan", "show", "interfaces"]:
                return (
                    "There are 2 interfaces on the system:\n\n"
                    "    Name                   : WLAN\n"
                    "    Description            : Qualcomm Adapter\n"
                    "    State                  : connected\n"
                    "    SSID                   : WYU\n\n"
                    "    Name                   : WLAN 4\n"
                    "    Description            : Qualcomm Adapter #4\n"
                    "    State                  : disconnected\n"
                )
            raise AssertionError(f"unexpected netsh arguments: {arguments}")

        with patch(
            "wuyi_seat_bot.network_monitor._run_netsh_command",
            side_effect=fake_run_netsh,
        ):
            result = list_connected_interfaces()

        self.assertEqual(result, ["WLAN"])

    def test_probe_general_connectivity_uses_single_ping_baidu(self) -> None:
        with patch(
            "wuyi_seat_bot.network_monitor.subprocess.run",
            return_value=CompletedProcess(
                args=["ping", "-n", "1", "-w", "2000", "www.baidu.com"],
                returncode=0,
            ),
        ) as run_command, patch(
            "wuyi_seat_bot.network_monitor._probe_url"
        ) as probe_url:
            result = probe_general_connectivity()

        self.assertTrue(result)
        run_command.assert_called_once()
        probe_url.assert_not_called()
        self.assertEqual(
            run_command.call_args.args[0],
            ["ping", "-n", "1", "-w", "2000", "www.baidu.com"],
        )

    def test_probe_general_connectivity_uses_http_fallback_when_ping_fails(self) -> None:
        with patch(
            "wuyi_seat_bot.network_monitor.subprocess.run",
            return_value=CompletedProcess(
                args=["ping", "-n", "1", "-w", "2000", "www.baidu.com"],
                returncode=1,
            ),
        ), patch(
            "wuyi_seat_bot.network_monitor._probe_url",
            return_value=True,
        ) as probe_url:
            result = probe_general_connectivity()

        self.assertTrue(result)
        probe_url.assert_called_once_with("https://www.baidu.com/")

    def test_probe_general_connectivity_returns_false_when_ping_and_http_both_fail(self) -> None:
        with patch(
            "wuyi_seat_bot.network_monitor.subprocess.run",
            return_value=CompletedProcess(
                args=["ping", "-n", "1", "-w", "2000", "www.baidu.com"],
                returncode=1,
            ),
        ), patch(
            "wuyi_seat_bot.network_monitor._probe_url",
            return_value=False,
        ):
            result = probe_general_connectivity()

        self.assertFalse(result)

    def test_probe_general_connectivity_returns_false_when_ping_times_out(self) -> None:
        with patch(
            "wuyi_seat_bot.network_monitor.subprocess.run",
            side_effect=TimeoutExpired(
                cmd=["ping", "-n", "1", "-w", "2000", "www.baidu.com"],
                timeout=5,
            ),
        ), patch(
            "wuyi_seat_bot.network_monitor._probe_url",
            return_value=False,
        ):
            result = probe_general_connectivity()

        self.assertFalse(result)

    def test_detect_once_returns_offline_when_connectivity_probe_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch(
                    "wuyi_seat_bot.network_monitor.list_connected_interfaces",
                    return_value=["Wi-Fi"],
                ),
                patch(
                    "wuyi_seat_bot.network_monitor.probe_general_connectivity",
                    return_value=False,
                ),
            ):
                result = monitor.detect_once()

        self.assertEqual(result["networkState"], "offline")
        self.assertIn("通用网络探测未通过，当前可能未联网", result["message"])

    def test_detect_once_returns_degraded_when_general_https_probe_is_ok(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch("wuyi_seat_bot.network_monitor.list_connected_interfaces", return_value=["Wi-Fi"]),
                patch("wuyi_seat_bot.network_monitor.probe_general_connectivity", return_value=True),
                patch("wuyi_seat_bot.network_monitor.probe_target_connectivity", return_value=False),
            ):
                result = monitor.detect_once()

        self.assertEqual(result["networkState"], "degraded")
        self.assertIn("学校目标站点连通性检测失败", result["message"])

    def test_reconnect_once_prefers_configured_wifi_names(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": ["Campus", "Dorm"],
                        },
                        "campusNetwork": {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                            "username": "20231121150",
                            "password": "secret",
                        }
                    },
                ),
                patch(
                    "wuyi_seat_bot.network_monitor.list_saved_wifi_profiles",
                    return_value=["Dorm", "Campus", "Library"],
                ),
                patch(
                    "wuyi_seat_bot.network_monitor.try_connect_wifi",
                    side_effect=[False, True],
                ) as try_connect,
                patch.object(
                    monitor,
                    "detect_once",
                    side_effect=[
                        {"networkState": "offline", "message": "未联网"},
                        {"networkState": "online", "message": "已恢复联网"},
                    ],
                ),
            ):
                result = monitor.reconnect_once()

        self.assertEqual(try_connect.call_args_list[0].args[0], "Campus")
        self.assertEqual(try_connect.call_args_list[1].args[0], "Dorm")
        self.assertEqual(result["reconnectState"], "reconnected")
        self.assertEqual(result["wifiName"], "Dorm")

    def test_authenticate_campus_network_once_returns_authenticated_when_login_succeeds(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": ["WYU"],
                        },
                        "campusNetwork": {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                            "username": "20231121150",
                            "password": "secret",
                        },
                    },
                ),
                patch.object(
                    monitor,
                    "detect_once",
                    side_effect=[
                        {
                            "networkState": "offline",
                            "message": "未联网",
                            "connectedInterfaces": ["WYU"],
                        },
                        {
                            "networkState": "online",
                            "message": "网络连接正常",
                            "connectedInterfaces": ["WYU"],
                        },
                    ],
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._perform_campus_network_login",
                    return_value=(True, "校园网认证成功"),
                ),
            ):
                result = monitor.authenticate_campus_network_once()

        self.assertEqual(result["networkState"], "online")
        self.assertEqual(result["reconnectState"], "authenticated")
        self.assertIn("校园网认证成功", result["message"])

    def test_authenticate_campus_network_once_skips_when_network_is_already_online(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": ["WYU"],
                        },
                        "campusNetwork": {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                            "username": "20231121150",
                            "password": "secret",
                        },
                    },
                ),
                patch.object(
                    monitor,
                    "detect_once",
                    return_value={
                        "networkState": "online",
                        "message": "网络连接正常",
                        "connectedInterfaces": ["WYU"],
                    },
                ),
                patch("wuyi_seat_bot.network_monitor._perform_campus_network_login") as perform_login,
            ):
                result = monitor.authenticate_campus_network_once()

        perform_login.assert_not_called()
        self.assertEqual(result["networkState"], "online")
        self.assertEqual(result["reconnectState"], "skipped")
        self.assertEqual(result["message"], "当前网络已稳定联网，无需校园网认证")

    def test_authenticate_after_detection_skips_when_network_is_degraded(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with patch("wuyi_seat_bot.network_monitor._perform_campus_network_login") as perform_login:
                result = monitor._authenticate_after_detection(
                    {
                        "networkState": "degraded",
                        "message": "学校目标站点连通性检测失败",
                        "connectedInterfaces": ["WYU"],
                    },
                    campus_network={
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                        "username": "test-user",
                        "password": "test-password",
                    },
                    wifi_name="WYU",
                )

        self.assertIsNone(result)
        perform_login.assert_not_called()

    def test_switch_to_campus_wifi_once_disconnects_then_authenticates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": ["WYU"],
                        },
                        "campusNetwork": {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                            "username": "20231121150",
                            "password": "secret",
                        },
                    },
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._run_netsh_wifi_command",
                    side_effect=[
                        CompletedProcess(args=["netsh", "wlan", "disconnect"], returncode=0, stdout="已断开。"),
                        CompletedProcess(args=["netsh", "wlan", "connect", "name=WYU"], returncode=0, stdout="已成功完成连接请求。"),
                    ],
                ) as run_wifi_command,
                patch.object(
                    monitor,
                    "detect_once",
                    side_effect=[
                        {
                            "networkState": "offline",
                            "message": "通用网络探测未通过，当前可能未联网",
                            "connectedInterfaces": ["WLAN"],
                        },
                        {
                            "networkState": "online",
                            "message": "网络连接正常",
                            "connectedInterfaces": ["WLAN"],
                        },
                    ],
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._perform_campus_network_login",
                    return_value=(True, "校园网认证成功"),
                ) as perform_login,
            ):
                result = monitor.switch_to_campus_wifi_once()

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        self.assertEqual(run_wifi_command.call_count, 2)
        perform_login.assert_called_once()
        self.assertEqual(result["networkState"], "online")
        self.assertEqual(result["reconnectState"], "authenticated")
        self.assertEqual(result["wifiName"], "WYU")
        self.assertIn("切换校园网后认证完成", log_content)
        self.assertIn("断开当前 Wi-Fi：returncode=0", log_content)
        self.assertIn("连接 WYU：returncode=0", log_content)

    def test_switch_to_campus_wifi_once_waits_out_temporary_online_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": ["WYU"],
                        },
                        "campusNetwork": {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "",
                            "username": "20231121150",
                            "password": "secret",
                        },
                    },
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._run_netsh_wifi_command",
                    side_effect=[
                        CompletedProcess(args=["netsh", "wlan", "disconnect"], returncode=0, stdout="已断开。"),
                        CompletedProcess(args=["netsh", "wlan", "connect", "name=WYU"], returncode=0, stdout="已成功完成连接请求。"),
                    ],
                ),
                patch.object(
                    monitor,
                    "detect_once",
                    side_effect=[
                        {
                            "networkState": "online",
                            "message": "短暂联网",
                            "connectedInterfaces": ["WLAN"],
                        },
                        {
                            "networkState": "degraded",
                            "message": "学校目标站点连通性检测失败",
                            "connectedInterfaces": ["WLAN"],
                        },
                        {
                            "networkState": "online",
                            "message": "网络连接正常",
                            "connectedInterfaces": ["WLAN"],
                        },
                    ],
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._perform_campus_network_login",
                    return_value=(True, "校园网认证成功"),
                ) as perform_login,
            ):
                result = monitor.switch_to_campus_wifi_once()

        perform_login.assert_called_once()
        self.assertEqual(result["networkState"], "online")
        self.assertEqual(result["reconnectState"], "authenticated")
        self.assertEqual(result["message"], "校园网认证成功")

    def test_switch_to_campus_wifi_once_returns_failure_when_wifi_name_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": [],
                        },
                        "campusNetwork": {
                            "enabled": True,
                            "wifiName": "",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                            "username": "20231121150",
                            "password": "secret",
                        },
                    },
                ),
                patch.object(
                    monitor,
                    "detect_once",
                    return_value={
                        "networkState": "online",
                        "message": "网络连接正常",
                        "connectedInterfaces": ["WLAN"],
                    },
                ),
                patch("wuyi_seat_bot.network_monitor.disconnect_current_wifi") as disconnect_wifi,
            ):
                result = monitor.switch_to_campus_wifi_once()

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        disconnect_wifi.assert_not_called()
        self.assertEqual(result["reconnectState"], "failed")
        self.assertEqual(result["message"], "请先填写校园网 Wi-Fi 名称")
        self.assertIn("切换校园网未执行", log_content)
        self.assertIn("原因：请先填写校园网 Wi-Fi 名称", log_content)

    def test_switch_to_campus_wifi_once_writes_command_log_when_connect_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": ["WYU"],
                        },
                        "campusNetwork": {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                            "username": "20231121150",
                            "password": "secret",
                        },
                    },
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._run_netsh_wifi_command",
                    side_effect=[
                        CompletedProcess(args=["netsh", "wlan", "disconnect"], returncode=0, stdout="已断开。"),
                        CompletedProcess(args=["netsh", "wlan", "connect", "name=WYU"], returncode=1, stdout="连接失败。"),
                    ],
                ),
                patch.object(
                    monitor,
                    "detect_once",
                    return_value={
                        "networkState": "offline",
                        "message": "通用网络探测未通过，当前可能未联网",
                        "connectedInterfaces": ["WLAN"],
                    },
                ),
            ):
                result = monitor.switch_to_campus_wifi_once()

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        self.assertEqual(result["reconnectState"], "failed")
        self.assertIn("切换校园网失败", log_content)
        self.assertIn("连接 WYU：returncode=1", log_content)
        self.assertIn("检测结果：通用网络探测未通过，当前可能未联网", log_content)

    def test_reconnect_once_does_not_attempt_campus_auth_after_wifi_connects(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": ["WYU"],
                        }
                    },
                ),
                patch(
                    "wuyi_seat_bot.network_monitor.list_saved_wifi_profiles",
                    return_value=["WYU"],
                ),
                patch(
                    "wuyi_seat_bot.network_monitor.try_connect_wifi",
                    return_value=True,
                ),
                patch.object(
                    monitor,
                    "detect_once",
                    side_effect=[
                        {
                            "networkState": "offline",
                            "message": "未联网",
                            "connectedInterfaces": [],
                        },
                        {
                            "networkState": "offline",
                            "message": "Wi-Fi 已连接但未认证",
                            "connectedInterfaces": ["WYU"],
                        },
                    ],
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._perform_campus_network_login",
                    return_value=(True, "校园网认证成功"),
                ) as perform_login,
            ):
                result = monitor.reconnect_once()

        perform_login.assert_not_called()
        self.assertEqual(result["networkState"], "offline")
        self.assertEqual(result["reconnectState"], "failed")
        self.assertEqual(result["wifiName"], "WYU")

    def test_build_campus_login_payload_matches_current_sso_form(self) -> None:
        payload, missing_hidden_fields = _build_campus_login_payload(
            (
                "<html>"
                "<p id='current-login-type'>UsernamePassword</p>"
                "<p id='login-page-flowkey'>flow-token</p>"
                "<p id='login-croypto'>eHlDOI0VRCLcNoLXfgJOyQ==</p>"
                "</html>"
            ),
            "20230000000",
            "fake-password-123",
        )

        self.assertEqual(missing_hidden_fields, [])
        self.assertEqual(
            payload,
            {
                "username": "20230000000",
                "type": "UsernamePassword",
                "_eventId": "submit",
                "geolocation": "",
                "execution": "flow-token",
                "captcha_code": "",
                "croypto": "eHlDOI0VRCLcNoLXfgJOyQ==",
                "password": "2+cl5TynFkle4GTw5GI9oR7Ub1ynPPNxrUPgsOOjg4Y=",
                "captcha_payload": "rrch2EgeW2drD98hkrPJKg==",
            },
        )

    def test_run_netsh_wifi_command_uses_system_encoding(self) -> None:
        with patch(
            "wuyi_seat_bot.network_monitor.locale.getencoding",
            return_value="gbk",
        ), patch(
            "wuyi_seat_bot.network_monitor.subprocess.run",
            return_value=CompletedProcess(args=["netsh", "wlan", "show", "interfaces"], returncode=0, stdout="ok".encode("gbk"), stderr=b""),
        ) as run_command:
            completed = _run_netsh_wifi_command(["wlan", "show", "interfaces"])

        self.assertEqual(completed.stdout, "ok")
        self.assertFalse(run_command.call_args.kwargs["text"])

    def test_decode_windows_command_output_accepts_utf8_output(self) -> None:
        self.assertEqual(_decode_windows_command_output("已成功".encode("utf-8")), "已成功")

    def test_decode_windows_command_output_falls_back_to_system_encoding(self) -> None:
        with patch("wuyi_seat_bot.network_monitor.locale.getencoding", return_value="gbk"):
            self.assertEqual(_decode_windows_command_output("已成功".encode("gbk")), "已成功")

    def test_is_campus_login_entry_url_recognizes_cas_form(self) -> None:
        self.assertTrue(
            _is_campus_login_entry_url(
                "https://sso.wuyiu.edu.cn/login?service=http%3A%2F%2Fcaptive%2Findex.jsp"
            )
        )

    def test_is_campus_login_entry_url_recognizes_authserver_target_param(self) -> None:
        # 学校把 SSO 切到 /authserver/login + TARGET 参数时也应识别
        self.assertTrue(
            _is_campus_login_entry_url(
                "https://sso.wuyiu.edu.cn/authserver/login?TARGET=https%3A%2F%2Fcaptive%2F"
            )
        )

    def test_is_campus_login_entry_url_recognizes_oauth_redirect_param(self) -> None:
        self.assertTrue(
            _is_campus_login_entry_url(
                "https://sso.wuyiu.edu.cn/oauth2/login?redirect_uri=https%3A%2F%2Fcaptive%2F"
            )
        )

    def test_is_campus_login_entry_url_recognizes_path_with_subroute(self) -> None:
        self.assertTrue(
            _is_campus_login_entry_url(
                "https://sso.wuyiu.edu.cn/login/index?service=http%3A%2F%2Fcaptive%2F"
            )
        )

    def test_is_campus_login_entry_url_rejects_login_without_sso_query(self) -> None:
        # 普通网站登录页没有 SSO 跳转参数，避免误识别为校园网入口
        self.assertFalse(
            _is_campus_login_entry_url("https://example.com/login")
        )
        self.assertFalse(
            _is_campus_login_entry_url("https://example.com/login?email=a@b.cn")
        )

    def test_is_campus_login_entry_url_rejects_unrelated_paths(self) -> None:
        self.assertFalse(_is_campus_login_entry_url("https://example.com/portal"))
        self.assertFalse(
            _is_campus_login_entry_url("ftp://sso.example.com/login?service=foo")
        )
        # 路径必须明确含 /login 关键词，避免被相邻字符串误命中
        self.assertFalse(
            _is_campus_login_entry_url("https://sso.example.com/loginsuccess?service=foo")
        )

    def test_build_wifi_candidates_only_contains_preferred_intersect_saved(self) -> None:
        # 用户许可名单（preferred）+ 已保存 profile 取交集；不再无差别拉系统全部已保存 Wi-Fi
        self.assertEqual(
            _build_wifi_candidates(
                ["WYU", "Dorm"],
                ["WYU", "Library", "Dorm"],
            ),
            ["WYU", "Dorm"],
        )

    def test_build_wifi_candidates_skips_unsaved_preferred_names(self) -> None:
        self.assertEqual(
            _build_wifi_candidates(["WYU", "Dorm"], ["Dorm"]),
            ["Dorm"],
        )

    def test_build_wifi_candidates_excludes_extra_saved_profiles(self) -> None:
        # preferred 为空时也不会回落到所有已保存 profile，避免连到无关网络
        self.assertEqual(_build_wifi_candidates([], ["Dorm", "Library"]), [])

    def test_build_wifi_candidates_deduplicates_preserving_order(self) -> None:
        self.assertEqual(
            _build_wifi_candidates(["WYU", "Dorm", "WYU"], ["WYU", "Dorm"]),
            ["WYU", "Dorm"],
        )

    def test_build_campus_login_submit_url_keeps_service_query(self) -> None:
        self.assertEqual(
            _build_campus_login_submit_url(
                "https://sso.wuyiu.edu.cn/login?service=http%3A%2F%2F211.80.243.20%2Feportal%2Findex.jsp%3Fwlanuserip%3Dabc"
            ),
            "https://sso.wuyiu.edu.cn/login?service=http%3A%2F%2F211.80.243.20%2Feportal%2Findex.jsp%3Fwlanuserip%3Dabc",
        )

    def test_discover_campus_login_url_uses_browser_capture_only(self) -> None:
        browser_url = "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fbrowser.example.com"

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch("wuyi_seat_bot.network_monitor._open_url") as open_url,
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery(browser_url),
                ),
            ):
                login_url = discover_campus_login_url(
                    config_path,
                    {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "",
                    },
                )

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        open_url.assert_not_called()
        self.assertEqual(login_url, browser_url)
        self.assertIn("校园网登录地址获取成功", log_content)
        self.assertIn("浏览器探测：http://baidu.com/ ->", log_content)

    def test_discover_campus_login_url_reports_browser_capture_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery_failure("浏览器探测：未找到 Edge 或 Chrome"),
                ),
            ):
                with self.assertRaisesRegex(ValueError, "未识别校园网认证入口"):
                    discover_campus_login_url(
                        config_path,
                        {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com",
                        },
                    )

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        self.assertIn("校园网登录地址获取失败", log_content)
        self.assertIn("浏览器探测：未找到 Edge 或 Chrome", log_content)

    def test_browser_capture_waits_after_launcher_exits(self) -> None:
        browser_url = "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fbrowser.example.com"
        discovery_lines: list[str] = []
        fake_process = _BrowserLauncherProcessStub()
        closed_target_ids: list[str] = []

        with (
            patch("wuyi_seat_bot.network_monitor._find_chromium_browser_path", return_value="msedge.exe"),
            patch("wuyi_seat_bot.network_monitor._find_free_local_port", return_value=9222),
            patch("wuyi_seat_bot.network_monitor.subprocess.Popen", return_value=fake_process),
            patch(
                "wuyi_seat_bot.network_monitor._read_browser_page_targets",
                side_effect=[[], [{"id": "target-1", "url": browser_url}]],
            ),
            patch(
                "wuyi_seat_bot.network_monitor._close_browser_page_targets",
                side_effect=lambda port, target_ids, lines: closed_target_ids.extend(sorted(target_ids)),
            ),
        ):
            login_url = _discover_campus_login_url_with_browser(discovery_lines)

        self.assertEqual(login_url, browser_url)
        self.assertEqual(closed_target_ids, ["target-1"])
        self.assertIn("浏览器探测：浏览器启动器已退出，退出码 0，继续等待页面跳转", discovery_lines)
        self.assertIn(f"浏览器探测：http://baidu.com/ -> {browser_url}", discovery_lines)

    def test_terminate_browser_process_kills_tree_after_launcher_exits(self) -> None:
        fake_process = _BrowserLauncherProcessStubWithPid(pid=12345)
        captured: list[list[str]] = []

        def fake_run(cmd: list[str], **kwargs: object) -> CompletedProcess[bytes]:
            captured.append(list(cmd))
            return CompletedProcess(cmd, 0, b"", b"")

        with patch(
            "wuyi_seat_bot.network_monitor.subprocess.run",
            side_effect=fake_run,
        ):
            _terminate_browser_process(fake_process)

        self.assertEqual(
            captured,
            [["taskkill", "/F", "/T", "/PID", "12345"]],
        )

    def test_terminate_browser_process_kills_tree_when_launcher_alive(self) -> None:
        fake_process = _RunningBrowserLauncherProcessStub(pid=4242)
        captured: list[list[str]] = []

        def fake_run(cmd: list[str], **kwargs: object) -> CompletedProcess[bytes]:
            captured.append(list(cmd))
            return CompletedProcess(cmd, 0, b"", b"")

        with patch(
            "wuyi_seat_bot.network_monitor.subprocess.run",
            side_effect=fake_run,
        ):
            _terminate_browser_process(fake_process)

        self.assertEqual(fake_process.terminate_calls, 1)
        self.assertGreaterEqual(fake_process.wait_calls, 1)
        self.assertEqual(
            captured,
            [["taskkill", "/F", "/T", "/PID", "4242"]],
        )

    def test_close_browser_page_targets_closes_pages_appearing_after_loop(self) -> None:
        # Simulate that the discovery loop never observed any tab IDs (e.g.
        # because the browser was still loading), but extra pages exist when
        # we start cleaning up. They must still be closed so they do not pile
        # up on the next discovery attempt.
        live_target_states: list[list[dict[str, str]]] = [
            [{"id": "target-A", "url": "http://baidu.com/"}],
            [{"id": "target-A", "url": "http://baidu.com/"}, {"id": "target-B", "url": "about:blank"}],
            [{"id": "target-B", "url": "about:blank"}],
            [],
        ]
        closed: list[str] = []

        def fake_read_targets(port: int) -> list[dict[str, str]]:
            if live_target_states:
                return live_target_states.pop(0)
            return []

        def fake_urlopen(url: str, timeout: int = 1) -> object:
            prefix = "http://127.0.0.1:9222/json/close/"
            self.assertTrue(url.startswith(prefix))
            closed.append(url[len(prefix):])

            class _CtxManager:
                def __enter__(self_inner) -> "_CtxManager":
                    return self_inner

                def __exit__(self_inner, *exc: object) -> None:
                    return None

            return _CtxManager()

        discovery_lines: list[str] = []

        with (
            patch(
                "wuyi_seat_bot.network_monitor._read_browser_page_targets",
                side_effect=fake_read_targets,
            ),
            patch(
                "wuyi_seat_bot.network_monitor.urllib.request.urlopen",
                side_effect=fake_urlopen,
            ),
        ):
            _close_browser_page_targets(9222, set(), discovery_lines)

        # Both pages must be closed even though the caller had no observed IDs.
        self.assertEqual(sorted(set(closed)), ["target-A", "target-B"])
        self.assertIn("浏览器探测：已关闭 2 个临时页面", discovery_lines)

    def test_discover_campus_login_url_does_not_fall_back_to_configured_url(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    return_value="",
                ),
                patch("wuyi_seat_bot.network_monitor._open_url") as open_url,
            ):
                with self.assertRaisesRegex(ValueError, "未识别校园网认证入口"):
                    discover_campus_login_url(
                        config_path,
                        {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com",
                        },
                    )

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        open_url.assert_not_called()
        self.assertIn("校园网登录地址获取失败", log_content)
        self.assertIn("提示：未识别校园网认证入口", log_content)

    def test_perform_campus_network_login_posts_current_sso_payload(self) -> None:
        calls: list[tuple[str, bytes | None]] = []
        browser_url = "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com"

        def fake_open_url(
            opener: object,
            url: str,
            *,
            data: bytes | None = None,
        ) -> tuple[str, str]:
            calls.append((url, data))
            if data is None:
                self.assertEqual(url, browser_url)
                return (
                    browser_url,
                    (
                        "<html>"
                        "<p id='current-login-type'>UsernamePassword</p>"
                        "<p id='login-page-flowkey'>flow-token</p>"
                        "<p id='login-croypto'>eHlDOI0VRCLcNoLXfgJOyQ==</p>"
                        "</html>"
                    ),
                )
            return (
                "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com&error=true",
                "<html><body><div role='alert'>账号或密码错误</div></body></html>",
            )

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery(browser_url),
                ),
                patch("wuyi_seat_bot.network_monitor._open_url", side_effect=fake_open_url),
            ):
                success, message = _perform_campus_network_login(
                    config_path,
                    {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fold.example.com",
                        "username": "20230000000",
                        "password": "fake-password-123",
                    },
                )

        self.assertFalse(success)
        self.assertEqual(message, "账号或密码错误")
        self.assertEqual(calls[0][0], browser_url)
        self.assertEqual(calls[1][0], browser_url)
        self.assertIsNotNone(calls[1][1])

        payload = urllib.parse.parse_qs(
            calls[1][1].decode("utf-8"),
            keep_blank_values=True,
        )
        self.assertEqual(payload["username"], ["20230000000"])
        self.assertEqual(payload["type"], ["UsernamePassword"])
        self.assertEqual(payload["_eventId"], ["submit"])
        self.assertEqual(payload["geolocation"], [""])
        self.assertEqual(payload["execution"], ["flow-token"])
        self.assertEqual(payload["captcha_code"], [""])
        self.assertEqual(payload["croypto"], ["eHlDOI0VRCLcNoLXfgJOyQ=="])
        self.assertEqual(payload["password"], ["2+cl5TynFkle4GTw5GI9oR7Ub1ynPPNxrUPgsOOjg4Y="])
        self.assertEqual(payload["captcha_payload"], ["rrch2EgeW2drD98hkrPJKg=="])

    def test_perform_campus_network_login_writes_diagnostic_log_on_failure(self) -> None:
        browser_url = "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com"

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery(browser_url),
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._open_url",
                    side_effect=[
                        (
                            browser_url,
                            (
                                "<html>"
                                "<p id='current-login-type'>UsernamePassword</p>"
                                "<p id='login-page-flowkey'>flow</p>"
                                "<p id='login-croypto'>eHlDOI0VRCLcNoLXfgJOyQ==</p>"
                                "</html>"
                            ),
                        ),
                        (
                            "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com&error=true",
                            "<html><body><div role='alert'>账号或密码错误</div></body></html>",
                        ),
                    ],
                ),
            ):
                success, message = _perform_campus_network_login(
                    config_path,
                    {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com",
                        "username": "20231121150",
                        "password": "secret",
                    },
                )

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        self.assertFalse(success)
        self.assertEqual(message, "账号或密码错误")
        self.assertIn("校园网认证失败", log_content)
        self.assertIn(
            "返回地址：https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com&error=true",
            log_content,
        )
        self.assertIn("登录页状态：已打开", log_content)
        self.assertIn("缺失隐藏字段：无", log_content)
        self.assertIn(f"浏览器探测：http://baidu.com/ -> {browser_url}", log_content)
        self.assertIn("页面文本片段：账号或密码错误", log_content)

    def test_perform_campus_network_login_does_not_fall_back_to_configured_url_when_browser_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery_failure("浏览器探测：未识别校园网登录地址"),
                ),
                patch("wuyi_seat_bot.network_monitor._open_url") as open_url,
            ):
                success, message = _perform_campus_network_login(
                    config_path,
                    {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com",
                        "username": "20230000000",
                        "password": "fake-password-123",
                    },
                )

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        open_url.assert_not_called()
        self.assertFalse(success)
        self.assertEqual(
            message,
            "校园网认证失败：未识别校园网认证入口，请确认已连接 WYU（HTTP 探测和浏览器兜底均失败）",
        )
        self.assertIn("校园网认证异常", log_content)
        self.assertIn("浏览器探测：未识别校园网登录地址", log_content)

    def test_perform_campus_network_login_returns_clear_message_when_dns_lookup_fails(self) -> None:
        browser_url = "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com"

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery(browser_url),
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._open_url",
                    side_effect=urllib.error.URLError(socket.gaierror(11001, "getaddrinfo failed")),
                ),
            ):
                success, message = _perform_campus_network_login(
                    config_path,
                    {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": browser_url,
                        "username": "20231121150",
                        "password": "secret",
                    },
                )

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        self.assertFalse(success)
        self.assertEqual(message, "校园网认证失败：无法解析 sso.wuyiu.edu.cn，请确认已连接 WYU 或 DNS 正常")
        self.assertIn("校园网认证异常", log_content)
        self.assertIn("登录域名：sso.wuyiu.edu.cn", log_content)
        self.assertIn("登录页状态：未打开", log_content)
        self.assertIn("缺失隐藏字段：未检查", log_content)
        self.assertIn("结果：URLError: <urlopen error [Errno 11001] getaddrinfo failed>", log_content)
        self.assertIn(f"浏览器探测：http://baidu.com/ -> {browser_url}", log_content)
        self.assertIn("提示：无法解析 sso.wuyiu.edu.cn，请确认已连接 WYU 或 DNS 正常", log_content)

    def test_perform_campus_network_login_uses_browser_capture_when_http_probe_fails(self) -> None:
        calls: list[tuple[str, bytes | None]] = []
        browser_url = "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fbrowser.example.com"

        def fake_open_url(
            opener: object,
            url: str,
            *,
            data: bytes | None = None,
        ) -> tuple[str, str]:
            calls.append((url, data))
            if url == browser_url and data is None:
                return (
                    browser_url,
                    (
                        "<html>"
                        "<p id='current-login-type'>UsernamePassword</p>"
                        "<p id='login-page-flowkey'>flow-token</p>"
                        "<p id='login-croypto'>eHlDOI0VRCLcNoLXfgJOyQ==</p>"
                        "</html>"
                    ),
                )
            if url == browser_url and data is not None:
                return (
                    "https://sso.wuyiu.edu.cn/success.jsp",
                    "<html><body>您已成功连接校园网!</body></html>",
                )
            return (url, "<html>ok</html>")

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery(browser_url, "浏览器探测：已关闭 1 个临时页面"),
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._open_url",
                    side_effect=fake_open_url,
                ),
            ):
                success, message = _perform_campus_network_login(
                    config_path,
                    {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                        "username": "20230000000",
                        "password": "fake-password-123",
                    },
                )

            settings = load_app_settings(config_path)
            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        self.assertTrue(success)
        self.assertEqual(message, "校园网认证成功")
        self.assertIn((browser_url, None), calls)
        self.assertIn("校园网认证成功", log_content)
        self.assertIn(f"登录地址：{browser_url}", log_content)
        self.assertIn("认证前登录地址已更新", log_content)
        self.assertIn("浏览器探测：已关闭 1 个临时页面", log_content)
        self.assertEqual(settings["campusNetwork"]["loginUrl"], browser_url)

    def test_perform_campus_network_login_treats_remote_disconnect_after_submit_as_submitted(self) -> None:
        browser_url = "https://sso.wuyiu.edu.cn/login?service=https%3A%2F%2Fexample.com"

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery(browser_url),
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._open_url",
                    side_effect=[
                        (
                            browser_url,
                            (
                                "<html>"
                                "<p id='current-login-type'>UsernamePassword</p>"
                                "<p id='login-page-flowkey'>flow</p>"
                                "<p id='login-croypto'>eHlDOI0VRCLcNoLXfgJOyQ==</p>"
                                "</html>"
                            ),
                        ),
                        http.client.RemoteDisconnected("Remote end closed connection without response"),
                    ],
                ),
            ):
                success, message = _perform_campus_network_login(
                    config_path,
                    {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                        "username": "20231121150",
                        "password": "secret",
                    },
                )

            log_content = build_network_monitor_log_path(config_path).read_text(encoding="utf-8")

        self.assertTrue(success)
        self.assertEqual(message, "校园网认证已提交")
        self.assertIn("校园网认证异常", log_content)
        self.assertIn("结果：RemoteDisconnected: Remote end closed connection without response", log_content)
        self.assertIn("提示：sso.wuyiu.edu.cn 提前断开连接，请确认已连接 WYU 后再试", log_content)

    def test_perform_campus_network_login_accepts_success_url_in_http_error(self) -> None:
        browser_url = "https://sso.wuyiu.edu.cn/login?service=http%3A%2F%2F211.80.243.20%2Feportal%2Findex.jsp"
        success_url = "http://211.80.243.20/eportal/success.jsp?userIndex=383464"
        http_error = urllib.error.HTTPError(
            url=success_url,
            code=401,
            msg="Unauthorized",
            hdrs=None,
            fp=None,
        )

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            with (
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_http",
                    return_value="",
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._discover_campus_login_url_with_browser",
                    side_effect=self._browser_discovery(browser_url),
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._open_url",
                    side_effect=[
                        (
                            browser_url,
                            (
                                "<html>"
                                "<p id='current-login-type'>UsernamePassword</p>"
                                "<p id='login-page-flowkey'>flow</p>"
                                "<p id='login-croypto'>eHlDOI0VRCLcNoLXfgJOyQ==</p>"
                                "</html>"
                            ),
                        ),
                        http_error,
                    ],
                ),
            ):
                success, message = _perform_campus_network_login(
                    config_path,
                    {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                        "username": "20231121150",
                        "password": "secret",
                    },
                )

        self.assertTrue(success)
        self.assertEqual(message, "校园网认证成功")

    def test_authenticate_campus_network_once_accepts_submitted_result_without_response(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            monitor = NetworkMonitor(config_path)

            with (
                patch.object(
                    monitor,
                    "load_settings",
                    return_value={
                        "networkMonitoring": {
                            "enabled": True,
                            "intervalMinutes": 120,
                            "preferredWifiNames": ["WYU"],
                        },
                        "campusNetwork": {
                            "enabled": True,
                            "wifiName": "WYU",
                            "loginUrl": "https://sso.wuyiu.edu.cn/login?service=",
                            "username": "20231121150",
                            "password": "secret",
                        },
                    },
                ),
                patch.object(
                    monitor,
                    "detect_once",
                    side_effect=[
                        {
                            "networkState": "offline",
                            "message": "未联网",
                            "connectedInterfaces": ["WYU"],
                        },
                        {
                            "networkState": "online",
                            "message": "网络连接正常",
                            "connectedInterfaces": ["WYU"],
                        },
                    ],
                ),
                patch(
                    "wuyi_seat_bot.network_monitor._perform_campus_network_login",
                    return_value=(True, "校园网认证已提交"),
                ),
            ):
                result = monitor.authenticate_campus_network_once()

        self.assertEqual(result["networkState"], "online")
        self.assertEqual(result["reconnectState"], "authenticated")
        self.assertEqual(result["message"], "校园网认证成功")

    def test_http_probe_returns_sso_url_when_captive_portal_redirects_directly(self) -> None:
        sso_url = (
            "https://sso.wuyiu.edu.cn/login?service=http%3A%2F%2F211.80.243.20"
            "%2Feportal%2Findex.jsp%3Fwlanuserip%3D34fd7a4669f451342424dfe2fac75642"
        )

        def fake_probe(url: str) -> tuple[str, str, int]:
            return sso_url, "<html><body>SSO 页面</body></html>", 200

        discovery_lines: list[str] = []
        with patch(
            "wuyi_seat_bot.network_monitor._http_probe_captive_portal",
            side_effect=fake_probe,
        ):
            login_url = _discover_campus_login_url_with_http(discovery_lines)

        self.assertEqual(login_url, sso_url)
        self.assertTrue(
            any("HTTP 探测：http://www.msftconnecttest.com" in line for line in discovery_lines),
            discovery_lines,
        )

    def test_http_probe_follows_eportal_javascript_redirect_to_sso(self) -> None:
        eportal_url = (
            "http://211.80.243.20/eportal/index.jsp?wlanuserip=34fd7a4669f451342424dfe2fac75642"
        )
        sso_url = (
            "https://sso.wuyiu.edu.cn/login?service="
            "http%3A%2F%2F211.80.243.20%2Feportal%2Findex.jsp%3Fwlanuserip%3D1"
        )

        def fake_probe(url: str) -> tuple[str, str, int]:
            if url == "http://www.msftconnecttest.com/connecttest.txt":
                body = (
                    "<html><script>"
                    f"top.self.location.href='{sso_url}';"
                    "</script></html>"
                )
                return eportal_url, body, 200
            raise AssertionError(f"unexpected probe url: {url}")

        discovery_lines: list[str] = []
        with patch(
            "wuyi_seat_bot.network_monitor._http_probe_captive_portal",
            side_effect=fake_probe,
        ):
            login_url = _discover_campus_login_url_with_http(discovery_lines)

        self.assertEqual(login_url, sso_url)
        self.assertIn(f"HTTP 探测：跳转至 {sso_url}", discovery_lines)

    def test_http_probe_skips_when_msftconnecttest_returns_direct_marker(self) -> None:
        captive_url = (
            "https://sso.wuyiu.edu.cn/login?service=http%3A%2F%2F211.80.243.20%2Feportal%2Findex.jsp"
        )

        def fake_probe(url: str) -> tuple[str, str, int]:
            if url == "http://www.msftconnecttest.com/connecttest.txt":
                return url, "Microsoft Connect Test", 200
            if url == "http://captive.apple.com/":
                return captive_url, "<html>captive</html>", 200
            raise AssertionError(f"unexpected probe url: {url}")

        discovery_lines: list[str] = []
        with patch(
            "wuyi_seat_bot.network_monitor._http_probe_captive_portal",
            side_effect=fake_probe,
        ):
            login_url = _discover_campus_login_url_with_http(discovery_lines)

        self.assertEqual(login_url, captive_url)
        self.assertTrue(
            any("命中直连特征" in line for line in discovery_lines),
            discovery_lines,
        )

    def test_http_probe_returns_empty_when_all_probes_show_direct_internet(self) -> None:
        def fake_probe(url: str) -> tuple[str, str, int]:
            if url == "http://www.msftconnecttest.com/connecttest.txt":
                return url, "Microsoft Connect Test", 200
            if url == "http://captive.apple.com/":
                return url, "<HTML><HEAD><TITLE>Success</TITLE></HEAD></HTML>", 200
            if url == "http://connectivitycheck.gstatic.com/generate_204":
                return url, "", 204
            raise AssertionError(f"unexpected probe url: {url}")

        discovery_lines: list[str] = []
        with patch(
            "wuyi_seat_bot.network_monitor._http_probe_captive_portal",
            side_effect=fake_probe,
        ):
            login_url = _discover_campus_login_url_with_http(discovery_lines)

        self.assertEqual(login_url, "")
        self.assertEqual(
            sum("未被校园网拦截" in line for line in discovery_lines),
            3,
        )

    def test_http_probe_skips_unreachable_hosts_and_continues(self) -> None:
        captive_url = (
            "https://sso.wuyiu.edu.cn/login?service=http%3A%2F%2F211.80.243.20%2Feportal%2Findex.jsp"
        )

        def fake_probe(url: str) -> tuple[str, str, int]:
            if url == "http://www.msftconnecttest.com/connecttest.txt":
                raise urllib.error.URLError(socket.gaierror(11001, "getaddrinfo failed"))
            if url == "http://captive.apple.com/":
                return captive_url, "<html>captive</html>", 200
            raise AssertionError(f"unexpected probe url: {url}")

        discovery_lines: list[str] = []
        with patch(
            "wuyi_seat_bot.network_monitor._http_probe_captive_portal",
            side_effect=fake_probe,
        ):
            login_url = _discover_campus_login_url_with_http(discovery_lines)

        self.assertEqual(login_url, captive_url)
        self.assertTrue(
            any("异常 URLError" in line for line in discovery_lines),
            discovery_lines,
        )

    def test_extract_campus_login_redirect_target_handles_meta_refresh(self) -> None:
        body = (
            "<html><head>"
            "<meta http-equiv=\"refresh\" content=\"0; url=https://sso.wuyiu.edu.cn/login?service=abc\">"
            "</head></html>"
        )
        target = _extract_campus_login_redirect_target("http://211.80.243.20/", body)
        self.assertEqual(target, "https://sso.wuyiu.edu.cn/login?service=abc")

    def test_extract_campus_login_redirect_target_handles_relative_top_location(self) -> None:
        body = (
            "<script>top.self.location.href = '/eportal/index.jsp?wlanuserip=abc';</script>"
        )
        target = _extract_campus_login_redirect_target(
            "http://211.80.243.20/eportal/redirect.jsp?token=1",
            body,
        )
        self.assertEqual(
            target,
            "http://211.80.243.20/eportal/index.jsp?wlanuserip=abc",
        )


class _BrowserLauncherProcessStub:
    returncode = 0

    @staticmethod
    def poll() -> int:
        return 0

    @staticmethod
    def terminate() -> None:
        return None

    @staticmethod
    def wait(timeout: int | None = None) -> int:
        return 0


class _BrowserLauncherProcessStubWithPid(_BrowserLauncherProcessStub):
    def __init__(self, pid: int) -> None:
        self.pid = pid


class _RunningBrowserLauncherProcessStub:
    def __init__(self, pid: int) -> None:
        self.pid = pid
        self.returncode = 0
        self.terminate_calls = 0
        self.wait_calls = 0
        self._poll_results = [None, 0]

    def poll(self) -> int | None:
        if self._poll_results:
            return self._poll_results.pop(0)
        return self.returncode

    def terminate(self) -> None:
        self.terminate_calls += 1

    def wait(self, timeout: int | None = None) -> int:
        self.wait_calls += 1
        return self.returncode
