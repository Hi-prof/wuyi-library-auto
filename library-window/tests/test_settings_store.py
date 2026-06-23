import json
import tempfile
import unittest
from pathlib import Path

from wuyi_seat_bot.settings_store import load_app_settings, save_app_settings


class SettingsStoreTestCase(unittest.TestCase):
    def test_load_app_settings_returns_defaults_when_file_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            settings = load_app_settings(config_path)

        self.assertEqual(
            settings,
            {
                "networkMonitoring": {
                    "enabled": True,
                    "intervalMinutes": 120,
                    "preferredWifiNames": [],
                },
                "campusNetwork": {
                    "enabled": True,
                    "wifiName": "WYU",
                    "loginUrl": "",
                    "username": "",
                    "password": "",
                }
            },
        )

    def test_save_app_settings_normalizes_wifi_names_and_interval(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            saved = save_app_settings(
                config_path,
                {
                    "networkMonitoring": {
                        "enabled": True,
                        "intervalMinutes": 60,
                        "preferredWifiNames": [" Campus ", "", "Dorm", "Campus"],
                    },
                    "campusNetwork": {
                        "enabled": True,
                        "wifiName": " WYU ",
                        "loginUrl": " https://sso.wuyiu.edu.cn/login?service= ",
                        "username": " 20231121150 ",
                        "password": " secret ",
                    }
                },
            )

            persisted_path = Path(tmp_dir) / "runtime" / "app_settings.json"
            persisted = json.loads(persisted_path.read_text(encoding="utf-8"))

        self.assertEqual(saved["networkMonitoring"]["intervalMinutes"], 60)
        self.assertEqual(saved["networkMonitoring"]["preferredWifiNames"], ["Campus", "Dorm"])
        self.assertEqual(saved["campusNetwork"]["wifiName"], "WYU")
        self.assertEqual(saved["campusNetwork"]["loginUrl"], "https://sso.wuyiu.edu.cn/login?service=")
        self.assertEqual(saved["campusNetwork"]["username"], "20231121150")
        self.assertEqual(saved["campusNetwork"]["password"], "secret")
        self.assertEqual(persisted, saved)

    def test_save_app_settings_strips_password_newline(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            saved = save_app_settings(
                config_path,
                {
                    "campusNetwork": {
                        "password": "secret\n",
                    }
                },
            )

        self.assertEqual(saved["campusNetwork"]["password"], "secret")
