import json
import io
import subprocess
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, patch

from wuyi_seat_bot.cli import _find_default_config_path, main
from wuyi_seat_bot.models import ActionType, AppConfig


class CliTestCase(unittest.TestCase):
    def test_package_can_be_imported_from_project_root(self) -> None:
        project_root = Path(__file__).resolve().parents[1]

        result = subprocess.run(
            [
                "python",
                "-c",
                "import wuyi_seat_bot; print(wuyi_seat_bot.__file__)",
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            cwd=project_root,
        )

        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("wuyi_seat_bot", result.stdout)

    def test_frozen_exe_without_arguments_relaunches_detached_tray_process(
        self,
    ) -> None:
        with (
            patch(
                "wuyi_seat_bot.cli._relaunch_detached_tray_process", return_value=0
            ) as relaunch,
            patch(
                "wuyi_seat_bot.cli.sys",
            ) as mocked_sys,
        ):
            mocked_sys.argv = ["wuyi-seat-bot.exe"]
            mocked_sys.frozen = True
            mocked_sys.executable = r"C:\dist\wuyi-seat-bot\wuyi-seat-bot.exe"

            exit_code = main([])

        self.assertEqual(exit_code, 0)
        relaunch.assert_called_once_with(["web", "--tray-launch"])

    def test_web_command_starts_guarded_local_web_server(self) -> None:
        with patch(
            "wuyi_seat_bot.service_manager.start_guarded_web_server", return_value=0
        ) as start_server:
            exit_code = main(
                [
                    "--config",
                    "config.json",
                    "web",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    "9000",
                    "--no-open",
                    "--account",
                    "主号",
                ]
            )

        self.assertEqual(exit_code, 0)
        start_server.assert_called_once_with(
            unittest.mock.ANY,
            host="127.0.0.1",
            port=9000,
            open_browser=False,
            account_name="主号",
            tray_mode=False,
            auto_enable_stability_enhancement=False,
        )

    def test_hidden_tray_launch_flag_starts_guarded_server_in_tray_mode(self) -> None:
        with patch(
            "wuyi_seat_bot.service_manager.start_guarded_web_server", return_value=0
        ) as start_server:
            exit_code = main(["--config", "config.json", "web", "--tray-launch"])

        self.assertEqual(exit_code, 0)
        start_server.assert_called_once_with(
            unittest.mock.ANY,
            host="127.0.0.1",
            port=8765,
            open_browser=True,
            account_name=None,
            tray_mode=True,
            auto_enable_stability_enhancement=False,
        )

    def test_internal_stability_enable_flag_is_forwarded_to_guarded_server(
        self,
    ) -> None:
        with patch(
            "wuyi_seat_bot.service_manager.start_guarded_web_server", return_value=0
        ) as start_server:
            exit_code = main(
                ["--config", "config.json", "web", "--enable-stability-enhancement"]
            )

        self.assertEqual(exit_code, 0)
        start_server.assert_called_once_with(
            unittest.mock.ANY,
            host="127.0.0.1",
            port=8765,
            open_browser=True,
            account_name=None,
            tray_mode=False,
            auto_enable_stability_enhancement=True,
        )

    def test_web_command_can_start_worker_directly_when_guard_disabled(self) -> None:
        with patch(
            "wuyi_seat_bot.web_server.start_web_server", return_value=0
        ) as start_server:
            exit_code = main(
                [
                    "--config",
                    "config.json",
                    "web",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    "9000",
                    "--no-open",
                    "--no-guard",
                    "--account",
                    "主号",
                ]
            )

        self.assertEqual(exit_code, 0)
        start_server.assert_called_once_with(
            unittest.mock.ANY,
            host="127.0.0.1",
            port=9000,
            open_browser=False,
            account_name="主号",
        )

    def test_status_command_prints_service_report(self) -> None:
        with patch(
            "wuyi_seat_bot.service_manager.get_service_status_report",
            return_value=(True, "受守护服务状态：运行中"),
        ):
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = main(["--config", "config.json", "status"])

        self.assertEqual(exit_code, 0)
        self.assertIn("受守护服务状态：运行中", output.getvalue())

    def test_hidden_settings_window_command_starts_native_settings_window(self) -> None:
        captured: dict[str, object] = {}

        fake_package = types.ModuleType("wuyi_seat_bot.desktop_settings")
        fake_app = types.ModuleType("wuyi_seat_bot.desktop_settings.app")

        def fake_run_settings_window(config_path: Path, *, parent_pid: int | None) -> int:
            captured["config_path"] = config_path
            captured["parent_pid"] = parent_pid
            return 0

        fake_app.run_settings_window = fake_run_settings_window

        with patch.dict(
            sys.modules,
            {
                "wuyi_seat_bot.desktop_settings": fake_package,
                "wuyi_seat_bot.desktop_settings.app": fake_app,
            },
        ):
            try:
                exit_code = main(
                    [
                        "--config",
                        "config.json",
                        "_settings-window",
                        "--parent-pid",
                        "4321",
                    ]
                )
            except SystemExit as exc:  # pragma: no cover - 当前失败即说明功能未实现
                self.fail(f"_settings-window 不应触发 SystemExit：{exc.code}")

        self.assertEqual(exit_code, 0)
        self.assertEqual(captured["parent_pid"], 4321)
        self.assertTrue(str(captured["config_path"]).endswith("config.json"))

    def test_find_default_config_path_searches_upward_from_exe_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            project_root = Path(tmp_dir)
            config_path = project_root / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            dist_dir = project_root / "dist" / "wuyi-seat-bot"
            dist_dir.mkdir(parents=True, exist_ok=True)

            with (
                patch("wuyi_seat_bot.cli.Path.cwd", return_value=dist_dir),
                patch(
                    "wuyi_seat_bot.cli._get_runtime_executable_path",
                    return_value=dist_dir / "wuyi-seat-bot.exe",
                ),
            ):
                resolved = _find_default_config_path("config.json")

        self.assertEqual(resolved, config_path.resolve())

    def test_web_command_auto_generates_example_config_when_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            project_root = Path(tmp_dir)
            dist_dir = project_root / "dist" / "wuyi-seat-bot"
            dist_dir.mkdir(parents=True, exist_ok=True)
            config_path = (dist_dir / "config.json").resolve()

            with (
                patch(
                    "wuyi_seat_bot.cli._find_default_config_path",
                    return_value=config_path,
                ),
                patch(
                    "wuyi_seat_bot.web_server.start_web_server",
                    return_value=0,
                ) as start_server,
            ):
                output = io.StringIO()
                with redirect_stdout(output):
                    exit_code = main(["web", "--no-open", "--no-guard"])
                self.assertEqual(exit_code, 0)
                self.assertTrue(config_path.exists())
                payload = json.loads(config_path.read_text(encoding="utf-8"))
                self.assertEqual(payload["default_account"], "2023000001")
                self.assertIn("已自动生成示例配置", output.getvalue())
                start_server.assert_called_once_with(
                    config_path,
                    host="127.0.0.1",
                    port=8765,
                    open_browser=False,
                    account_name=None,
                )

    def test_save_login_uses_selected_account(self) -> None:
        config = AppConfig(
            login_url="https://example.com/login",
            state_file="runtime/auth-main.json",
            seat_urls=("https://example.com/seat/166",),
            account_name="主号",
        )
        automation = MagicMock()
        automation.save_login_state.return_value = "runtime/auth-main.json"

        with (
            patch(
                "wuyi_seat_bot.config.load_config", return_value=config
            ) as load_config_mock,
            patch(
                "wuyi_seat_bot.api_seat_automation.ApiSeatAutomation",
                return_value=automation,
            ),
        ):
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = main(
                    ["--config", "config.json", "save-login", "--account", "主号"]
                )

        self.assertEqual(exit_code, 0)
        load_config_mock.assert_called_once_with(unittest.mock.ANY, account_name="主号")
        self.assertIn("登录态已保存到", output.getvalue())

    def test_inspect_status_prints_each_seat_without_triggering_workflow(self) -> None:
        config = AppConfig(
            login_url="https://example.com/login",
            state_file="runtime/auth.json",
            seat_urls=("https://example.com/seat/166", "https://example.com/seat/168"),
        )
        automation = MagicMock()
        automation.inspect_seat_status.side_effect = [
            "https://example.com/seat/166 -> unavailable: 图书馆未开馆",
            "https://example.com/seat/168 -> available: 可以预约",
        ]

        with (
            patch("wuyi_seat_bot.config.load_config", return_value=config),
            patch(
                "wuyi_seat_bot.api_seat_automation.ApiSeatAutomation",
                return_value=automation,
            ),
            patch("wuyi_seat_bot.workflow.SeatWorkflow") as workflow_class,
        ):
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = main(["--config", "config.json", "inspect-status"])

        self.assertEqual(exit_code, 0)
        self.assertEqual(
            output.getvalue().strip().splitlines(),
            [
                "https://example.com/seat/166 -> unavailable: 图书馆未开馆",
                "https://example.com/seat/168 -> available: 可以预约",
            ],
        )
        workflow_class.assert_not_called()

    def test_checkout_command_runs_checkout_workflow(self) -> None:
        config = AppConfig(
            login_url="https://example.com/login",
            state_file="runtime/auth.json",
            seat_urls=("https://example.com/seat/166",),
            account_name="主号",
        )
        workflow = MagicMock()
        workflow.run.return_value = MagicMock(success=True, message="签退成功")

        with (
            patch("wuyi_seat_bot.config.load_config", return_value=config),
            patch(
                "wuyi_seat_bot.api_seat_automation.ApiSeatAutomation",
                return_value=MagicMock(),
            ),
            patch("wuyi_seat_bot.workflow.SeatWorkflow", return_value=workflow),
        ):
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = main(
                    ["--config", "config.json", "checkout", "--account", "主号"]
                )

        self.assertEqual(exit_code, 0)
        workflow.run.assert_called_once_with(ActionType.CHECKOUT)
        self.assertIn("签退成功", output.getvalue())
