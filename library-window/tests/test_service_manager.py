import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import MagicMock, patch

from wuyi_seat_bot.service_manager import (
    GuardedWebService,
    LOG_TRIM_BYTES,
    MAX_LOG_TRIM_TRIGGER_BYTES,
    NETWORK_MONITOR_OFFLINE_RETRY_MAX_SECONDS,
    NETWORK_MONITOR_OFFLINE_RETRY_SECONDS,
    StabilityEnhancementManager,
    _compute_next_run_delay_seconds,
    _should_attempt_auto_reconnect,
    _update_offline_count,
    _build_tray_icon,
    _configure_process_logger,
    build_service_log_paths,
    build_service_status_paths,
    get_service_status_report,
)


class ServiceManagerTestCase(unittest.TestCase):
    def test_auto_reconnect_triggers_on_first_offline_detection(self) -> None:
        self.assertTrue(_should_attempt_auto_reconnect("offline", 1))
        self.assertTrue(_should_attempt_auto_reconnect("offline", 3))
        self.assertFalse(_should_attempt_auto_reconnect("offline", 0))
        self.assertFalse(_should_attempt_auto_reconnect("degraded", 3))
        self.assertFalse(_should_attempt_auto_reconnect("online", 5))

    def test_update_offline_count_only_resets_when_online(self) -> None:
        self.assertEqual(_update_offline_count("offline", 0), 1)
        self.assertEqual(_update_offline_count("offline", 2), 3)
        # degraded 必须保留计数，避免"被踢下线后误判为 degraded"重置重连节奏
        self.assertEqual(_update_offline_count("degraded", 2), 2)
        self.assertEqual(_update_offline_count("unknown", 4), 4)
        self.assertEqual(_update_offline_count("online", 5), 0)

    def test_compute_next_run_delay_uses_short_offline_backoff(self) -> None:
        interval_minutes = 120
        # 在线/未脱困状态走用户配置的常规巡检间隔
        self.assertEqual(
            _compute_next_run_delay_seconds("online", 0, interval_minutes),
            interval_minutes * 60,
        )
        self.assertEqual(
            _compute_next_run_delay_seconds("degraded", 5, interval_minutes),
            interval_minutes * 60,
        )
        # offline 时不再受 intervalMinutes 拖累，首次重试就在短退避区间
        first_delay = _compute_next_run_delay_seconds("offline", 1, interval_minutes)
        self.assertEqual(first_delay, NETWORK_MONITOR_OFFLINE_RETRY_SECONDS)
        # 指数退避
        self.assertEqual(
            _compute_next_run_delay_seconds("offline", 2, interval_minutes),
            NETWORK_MONITOR_OFFLINE_RETRY_SECONDS * 2,
        )
        self.assertEqual(
            _compute_next_run_delay_seconds("offline", 3, interval_minutes),
            NETWORK_MONITOR_OFFLINE_RETRY_SECONDS * 4,
        )

    def test_compute_next_run_delay_caps_offline_backoff(self) -> None:
        # 退避会被全局上限封顶，避免无限增大
        delay = _compute_next_run_delay_seconds("offline", 50, 720)
        self.assertEqual(delay, NETWORK_MONITOR_OFFLINE_RETRY_MAX_SECONDS)

    def test_compute_next_run_delay_respects_user_interval_lower_bound(self) -> None:
        # 用户把 intervalMinutes 调到最小值 30 时，offline 退避也不应超过该值
        # （除非已经低于 OFFLINE_RETRY 起步值——起步值始终保证最小重试节奏）
        delay = _compute_next_run_delay_seconds("offline", 50, 30)
        self.assertEqual(delay, max(30 * 60, NETWORK_MONITOR_OFFLINE_RETRY_SECONDS))

    def test_build_tray_icon_contains_show_ui_settings_restart_and_exit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            service = GuardedWebService(config_path, tray_mode=True)

            try:
                with patch("wuyi_seat_bot.service_manager.pystray.Icon") as icon_class:
                    _build_tray_icon(service)
            finally:
                for handler in list(service._logger.handlers):
                    handler.close()
                    service._logger.removeHandler(handler)

        menu = icon_class.call_args.args[3]
        labels = [item.text for item in menu.items]
        self.assertEqual(labels, ["显示界面", "设置", "重启程序", "退出程序"])

    def test_open_settings_page_spawns_native_settings_process(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            service = GuardedWebService(config_path, tray_mode=True)

            try:
                with patch(
                    "wuyi_seat_bot.service_manager._spawn_settings_window_process"
                ) as spawn_process:
                    service.open_settings_page()
            finally:
                for handler in list(service._logger.handlers):
                    handler.close()
                    service._logger.removeHandler(handler)

        spawn_process.assert_called_once_with(service.config_path, parent_pid=os.getpid())

    def test_request_restart_from_tray_marks_restart_and_stops_service(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            service = GuardedWebService(config_path, tray_mode=True)
            icon = MagicMock()

            try:
                with patch.object(service, "stop") as stop:
                    service.request_restart_from_tray(icon, None)
            finally:
                for handler in list(service._logger.handlers):
                    handler.close()
                    service._logger.removeHandler(handler)

        self.assertTrue(getattr(service, "_restart_requested", False))
        stop.assert_called_once()
        icon.stop.assert_called_once()

    def test_run_with_tray_makes_icon_visible_before_starting_service_thread(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            class FakeIcon:
                def __init__(self) -> None:
                    self.visible = False

                def run(self, setup=None) -> None:
                    if setup is not None:
                        setup(self)

                def stop(self) -> None:
                    return

                def notify(self, message: str, title: str) -> None:
                    del message, title

            service = GuardedWebService(config_path, tray_mode=True)
            fake_icon = FakeIcon()

            with patch("wuyi_seat_bot.service_manager._hide_console_window"), patch(
                "wuyi_seat_bot.service_manager._build_tray_icon",
                return_value=fake_icon,
            ), patch.object(service, "run_forever", return_value=0):
                exit_code = service.run_with_tray()
            for handler in list(service._logger.handlers):
                handler.close()
                service._logger.removeHandler(handler)

        self.assertEqual(exit_code, 0)
        self.assertTrue(fake_icon.visible)

    def test_enable_stability_enhancement_relaunches_as_admin_when_user_confirms(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            service = GuardedWebService(config_path, tray_mode=True)
            icon = MagicMock()

            try:
                with patch("wuyi_seat_bot.service_manager._is_user_admin", return_value=False), patch(
                    "wuyi_seat_bot.service_manager._ask_yes_no_message_box",
                    return_value=True,
                ), patch(
                    "wuyi_seat_bot.service_manager._relaunch_process_as_admin",
                    return_value=True,
                ) as relaunch, patch.object(service, "stop") as stop:
                    service.enable_stability_enhancement(icon, None)
            finally:
                for handler in list(service._logger.handlers):
                    handler.close()
                    service._logger.removeHandler(handler)

        relaunch_args = relaunch.call_args.args[0]
        self.assertIn("--enable-stability-enhancement", relaunch_args)
        stop.assert_called_once()
        icon.stop.assert_called_once()

    def test_run_forever_auto_enables_stability_enhancement_when_requested(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            service = GuardedWebService(config_path, auto_enable_stability_enhancement=True)

            try:
                with patch.object(service, "_recover_stability_enhancement_if_needed"), patch.object(
                    service,
                    "_enable_stability_enhancement_after_relaunch",
                ) as auto_enable, patch.object(service, "_write_supervisor_status"), patch.object(
                    service,
                    "_emit",
                ), patch.object(service.stop_event, "is_set", return_value=True), patch.object(
                    service,
                    "_stop_child_process",
                ):
                    exit_code = service.run_forever()
            finally:
                for handler in list(service._logger.handlers):
                    handler.close()
                    service._logger.removeHandler(handler)

        self.assertEqual(exit_code, 0)
        auto_enable.assert_called_once()

    def test_build_service_log_paths_places_logs_under_runtime_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            supervisor_log_path, worker_log_path = build_service_log_paths(config_path)

        self.assertEqual(supervisor_log_path.name, "service-supervisor.log")
        self.assertEqual(worker_log_path.name, "service-worker.log")
        self.assertIn("runtime", str(supervisor_log_path))

    def test_configure_process_logger_trims_oversized_log_file_before_reuse(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            log_path = Path(tmp_dir) / "service-worker.log"
            head = b"A" * LOG_TRIM_BYTES
            tail = b"B" * (MAX_LOG_TRIM_TRIGGER_BYTES - LOG_TRIM_BYTES + 128)
            log_path.write_bytes(head + tail)

            logger = _configure_process_logger(log_path, "trim-test")
            try:
                trimmed_content = log_path.read_bytes()
            finally:
                for handler in list(logger.handlers):
                    handler.close()
                    logger.removeHandler(handler)

        self.assertEqual(trimmed_content, tail)

    def test_get_service_status_report_returns_missing_message_without_status_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            healthy, report = get_service_status_report(config_path)

        self.assertFalse(healthy)
        self.assertIn("未检测到受守护的服务状态文件", report)

    def test_get_service_status_report_reads_healthy_supervisor_and_worker_status(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            supervisor_path, worker_path = build_service_status_paths(config_path)
            now_text = datetime.now().replace(microsecond=0).isoformat()
            supervisor_path.parent.mkdir(parents=True, exist_ok=True)
            supervisor_path.write_text(
                json.dumps(
                    {
                        "pid": 1001,
                        "state": "running",
                        "browserUrl": "http://127.0.0.1:8765/",
                        "restartCount": 2,
                        "message": "工作进程运行中",
                        "updatedAt": now_text,
                        "supervisorLogPath": str(supervisor_path.parent / "logs" / "service-supervisor.log"),
                        "workerLogPath": str(supervisor_path.parent / "logs" / "service-worker.log"),
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )
            worker_path.write_text(
                json.dumps(
                    {
                        "pid": 1002,
                        "state": "running",
                        "browserUrl": "http://127.0.0.1:8765/",
                        "updatedAt": now_text,
                        "message": "服务运行中",
                        "taskSchedulerAlive": True,
                        "automationSchedulerAlive": True,
                        "checkinMonitorAlive": True,
                        "taskSchedulerRestartCount": 0,
                        "automationSchedulerRestartCount": 1,
                        "checkinMonitorRestartCount": 0,
                        "pendingTaskCount": 1,
                        "enabledAutomationPlanCount": 2,
                        "workerLogPath": str(worker_path.parent / "logs" / "service-worker.log"),
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )

            healthy, report = get_service_status_report(config_path)

        self.assertTrue(healthy)
        self.assertIn("受守护服务状态：运行中", report)
        self.assertIn("自动重启次数：2", report)
        self.assertIn("启用中的自动计划：2", report)
        self.assertIn("签到巡检线程：正常", report)
        self.assertIn("工作日志：", report)
        self.assertIn("守护日志：", report)

    def test_get_service_status_report_marks_stale_worker_as_unhealthy(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            _, worker_path = build_service_status_paths(config_path)
            worker_path.parent.mkdir(parents=True, exist_ok=True)
            stale_time = (datetime.now() - timedelta(seconds=120)).replace(microsecond=0).isoformat()
            worker_path.write_text(
                json.dumps(
                    {
                        "pid": 1002,
                        "state": "running",
                        "browserUrl": "http://127.0.0.1:8765/",
                        "updatedAt": stale_time,
                        "message": "服务运行中",
                        "taskSchedulerAlive": True,
                        "automationSchedulerAlive": True,
                        "checkinMonitorAlive": False,
                        "taskSchedulerRestartCount": 0,
                        "automationSchedulerRestartCount": 0,
                        "checkinMonitorRestartCount": 0,
                        "pendingTaskCount": 0,
                        "enabledAutomationPlanCount": 0,
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )

            healthy, report = get_service_status_report(config_path)

        self.assertFalse(healthy)
        self.assertIn("受守护服务状态：异常", report)

    def test_stability_manager_enable_saves_original_power_settings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            manager = StabilityEnhancementManager(config_path)

            with patch(
                "wuyi_seat_bot.stability_enhancement._query_power_setting_value",
                side_effect=[30, 15, 1, 1],
            ), patch("wuyi_seat_bot.stability_enhancement._apply_power_setting") as apply_setting, patch(
                "wuyi_seat_bot.stability_enhancement._set_thread_execution_state",
                return_value=True,
            ):
                message = manager.enable()

            saved_state = json.loads(manager.state_path.read_text(encoding="utf-8"))

        self.assertIn("已启用", message)
        self.assertEqual(
            saved_state,
            {
                "sleepTimeoutAc": 30,
                "sleepTimeoutDc": 15,
                "lidActionAc": 1,
                "lidActionDc": 1,
            },
        )
        self.assertEqual(apply_setting.call_count, 4)

    def test_stability_manager_disable_restores_saved_power_settings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            manager = StabilityEnhancementManager(config_path)
            manager.state_path.parent.mkdir(parents=True, exist_ok=True)
            manager.state_path.write_text(
                json.dumps(
                    {
                        "sleepTimeoutAc": 30,
                        "sleepTimeoutDc": 15,
                        "lidActionAc": 1,
                        "lidActionDc": 1,
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )

            with patch("wuyi_seat_bot.stability_enhancement._apply_power_setting") as apply_setting, patch(
                "wuyi_seat_bot.stability_enhancement._clear_thread_execution_state",
                return_value=True,
            ):
                message = manager.disable()

        self.assertIn("已关闭", message)
        self.assertFalse(manager.state_path.exists())
        self.assertEqual(apply_setting.call_count, 4)
