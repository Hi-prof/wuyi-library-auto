import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class DesktopSettingsServiceTestCase(unittest.TestCase):
    def _load_service_class(self):
        try:
            from wuyi_seat_bot.desktop_settings.service import DesktopSettingsService
        except ModuleNotFoundError as exc:  # pragma: no cover - 当前失败即说明功能未实现
            self.fail(f"桌面设置服务模块缺失：{exc}")
        return DesktopSettingsService

    def test_get_payload_returns_settings_runtime_and_diagnostics(self) -> None:
        desktop_settings_service = self._load_service_class()

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            payload = desktop_settings_service(config_path).get_payload()

        self.assertEqual(payload["settings"]["networkMonitoring"]["intervalMinutes"], 120)
        self.assertEqual(payload["networkStatus"]["networkState"], "unknown")
        self.assertEqual(payload["serviceSnapshot"]["supervisor"]["state"], "missing")
        self.assertFalse(payload["stabilityEnhancementEnabled"])
        self.assertTrue(payload["diagnostics"]["logDirectoryPath"].endswith("runtime\\logs"))
        self.assertTrue(payload["diagnostics"]["networkMonitorLogPath"].endswith("network-monitor.log"))

    def test_save_settings_normalizes_wifi_names(self) -> None:
        desktop_settings_service = self._load_service_class()

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            payload = desktop_settings_service(config_path).save_settings(
                {
                    "networkMonitoring": {
                        "enabled": False,
                        "intervalMinutes": 60,
                        "preferredWifiNames": [" 图书馆WiFi ", "图书馆WiFi", "宿舍热点"],
                    }
                }
            )

        self.assertFalse(payload["settings"]["networkMonitoring"]["enabled"])
        self.assertEqual(payload["settings"]["networkMonitoring"]["preferredWifiNames"], ["图书馆WiFi", "宿舍热点"])

    def test_run_network_check_refreshes_network_status(self) -> None:
        desktop_settings_service = self._load_service_class()

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            service = desktop_settings_service(config_path)

            with patch(
                "wuyi_seat_bot.desktop_settings.service.NetworkMonitor.detect_once",
                return_value={
                    "networkState": "online",
                    "message": "网络连接正常",
                    "connectedInterfaces": ["WLAN"],
                    "reconnectState": "",
                    "wifiName": "",
                    "updatedAt": "2026-04-07T20:30:00",
                },
            ):
                payload = service.run_network_check()

        self.assertEqual(payload["networkStatus"]["networkState"], "online")
        self.assertEqual(payload["networkStatus"]["message"], "网络连接正常")

    def test_service_no_longer_exposes_campus_network_actions(self) -> None:
        desktop_settings_service = self._load_service_class()

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            service = desktop_settings_service(config_path)

        self.assertFalse(hasattr(service, "run_campus_network_login"))
        self.assertFalse(hasattr(service, "refresh_campus_login_url"))
        self.assertFalse(hasattr(service, "run_switch_to_campus_wifi"))

    def test_get_payload_reads_existing_service_status_files(self) -> None:
        desktop_settings_service = self._load_service_class()

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            runtime_dir = Path(tmp_dir) / "runtime"
            runtime_dir.mkdir(parents=True, exist_ok=True)
            (runtime_dir / "service_supervisor.json").write_text(
                json.dumps({"state": "running", "restartCount": 1}, ensure_ascii=False),
                encoding="utf-8",
            )
            (runtime_dir / "service_worker.json").write_text(
                json.dumps({"state": "running", "pendingTaskCount": 2}, ensure_ascii=False),
                encoding="utf-8",
            )

            payload = desktop_settings_service(config_path).get_payload()

        self.assertEqual(payload["serviceSnapshot"]["supervisor"]["state"], "running")
        self.assertEqual(payload["serviceSnapshot"]["worker"]["pendingTaskCount"], 2)

    def test_get_payload_includes_recent_logs_preview(self) -> None:
        desktop_settings_service = self._load_service_class()

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            runtime_logs = Path(tmp_dir) / "runtime" / "logs"
            runtime_logs.mkdir(parents=True, exist_ok=True)
            (runtime_logs / "service-worker.log").write_text(
                "worker line 1\nworker line 2\nworker line 3\n",
                encoding="utf-8",
            )
            (runtime_logs / "service-supervisor.log").write_text(
                "supervisor line 1\nsupervisor line 2\n",
                encoding="utf-8",
            )
            (runtime_logs / "network-monitor.log").write_text(
                "campus line 1\ncampus line 2\n",
                encoding="utf-8",
            )

            payload = desktop_settings_service(config_path).get_payload()

        preview = payload["diagnostics"]["recentLogsPreview"]
        self.assertIn("工作日志", preview)
        self.assertIn("worker line 3", preview)
        self.assertIn("守护日志", preview)
        self.assertIn("supervisor line 2", preview)
        self.assertIn("网络诊断", preview)
        self.assertNotIn("校园网诊断", preview)
        self.assertIn("campus line 2", preview)

    def test_clear_logs_truncates_current_logs_and_removes_backups(self) -> None:
        desktop_settings_service = self._load_service_class()

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            runtime_logs = Path(tmp_dir) / "runtime" / "logs"
            runtime_logs.mkdir(parents=True, exist_ok=True)
            worker_log = runtime_logs / "service-worker.log"
            supervisor_log = runtime_logs / "service-supervisor.log"
            network_monitor_log = runtime_logs / "network-monitor.log"
            worker_backup = runtime_logs / "service-worker.log.1"
            stray_log = runtime_logs / "extra.log"
            worker_log.write_text("worker-data", encoding="utf-8")
            supervisor_log.write_text("supervisor-data", encoding="utf-8")
            network_monitor_log.write_text("network-data", encoding="utf-8")
            worker_backup.write_text("backup-data", encoding="utf-8")
            stray_log.write_text("extra-data", encoding="utf-8")

            payload = desktop_settings_service(config_path).clear_logs()

            self.assertEqual(worker_log.read_text(encoding="utf-8"), "")
            self.assertEqual(supervisor_log.read_text(encoding="utf-8"), "")
            self.assertEqual(network_monitor_log.read_text(encoding="utf-8"), "")
            self.assertFalse(worker_backup.exists())
            self.assertFalse(stray_log.exists())
            self.assertIn("已清空", payload["message"])
            self.assertIn("暂无日志", payload["diagnostics"]["recentLogsPreview"])

    def test_open_log_target_supports_network_monitor_log(self) -> None:
        desktop_settings_service = self._load_service_class()

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            network_monitor_log = Path(tmp_dir) / "runtime" / "logs" / "network-monitor.log"
            service = desktop_settings_service(config_path)

            with patch("wuyi_seat_bot.desktop_settings.service.subprocess.Popen") as popen:
                payload = service.open_log_target("networkMonitorLog")

            self.assertTrue(network_monitor_log.exists())
            popen.assert_called_once()
            command = popen.call_args.args[0]
            self.assertEqual(command[0], "notepad.exe")
            self.assertEqual(Path(command[1]).resolve(), network_monitor_log.resolve())
            self.assertEqual(payload["message"], "已打开网络诊断")

    def test_diagnostics_page_contains_clear_logs_button(self) -> None:
        source = (Path(__file__).resolve().parents[1] / "src" / "wuyi_seat_bot" / "desktop_settings" / "page_builders.py").read_text(
            encoding="utf-8"
        )

        self.assertIn("清空全部日志", source)
        self.assertIn("复制最近日志", source)
        self.assertIn("网络诊断", source)
        self.assertNotIn("校园网诊断", source)
