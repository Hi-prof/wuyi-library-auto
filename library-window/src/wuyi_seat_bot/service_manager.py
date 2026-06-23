from __future__ import annotations

import ctypes
import json
import logging
from logging.handlers import RotatingFileHandler
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import webbrowser
from datetime import datetime
from pathlib import Path
from typing import Any

import pystray
from PIL import Image, ImageDraw
from pystray import MenuItem as TrayMenuItem

from wuyi_seat_bot.config import resolve_project_path
from wuyi_seat_bot.network_monitor import NetworkMonitor
from wuyi_seat_bot.stability_enhancement import StabilityEnhancementManager
from wuyi_seat_bot.web_server import SeatWebApp, SeatWebServer

SUPERVISOR_STATUS_RELATIVE_PATH = "runtime/service_supervisor.json"
WORKER_STATUS_RELATIVE_PATH = "runtime/service_worker.json"
LOG_DIRECTORY_RELATIVE_PATH = "runtime/logs"
SUPERVISOR_LOG_NAME = "service-supervisor.log"
WORKER_LOG_NAME = "service-worker.log"
SUPERVISOR_POLL_INTERVAL_SECONDS = 5.0
WORKER_HEARTBEAT_INTERVAL_SECONDS = 15.0
WORKER_STALE_SECONDS = 45.0
FAST_FAILURE_WINDOW_SECONDS = 20.0
MAX_FAST_FAILURES = 3
MAX_RESTART_DELAY_SECONDS = 60.0
MAX_LOG_FILE_BYTES = 2 * 1024 * 1024
LOG_BACKUP_COUNT = 2
MAX_LOG_TRIM_TRIGGER_BYTES = 100 * 1024 * 1024
LOG_TRIM_BYTES = 50 * 1024 * 1024
NETWORK_MONITOR_IDLE_WAIT_SECONDS = 30.0
NETWORK_MONITOR_OFFLINE_RETRY_SECONDS = 90.0
NETWORK_MONITOR_OFFLINE_RETRY_MAX_SECONDS = 30 * 60.0
AUTO_RECONNECT_OFFLINE_THRESHOLD = 1
SW_HIDE = 0
SW_SHOWNORMAL = 1
MESSAGE_BOX_ICON_INFORMATION = 0x40
MESSAGE_BOX_ICON_ERROR = 0x10
MESSAGE_BOX_ICON_WARNING = 0x30
MESSAGE_BOX_YES_NO = 0x04
MESSAGE_BOX_ID_YES = 6
TRAY_LAUNCH_FLAG = "--tray-launch"
ENABLE_STABILITY_ENHANCEMENT_FLAG = "--enable-stability-enhancement"


class GuardedWebService:
    def __init__(
        self,
        config_path: str | Path,
        *,
        host: str = "127.0.0.1",
        port: int = 8765,
        open_browser: bool = True,
        account_name: str | None = None,
        tray_mode: bool = False,
        auto_enable_stability_enhancement: bool = False,
    ) -> None:
        self.config_path = Path(config_path).resolve()
        self.host = host
        self.port = port
        self.open_browser = open_browser
        self.account_name = account_name
        self.tray_mode = tray_mode
        self.auto_enable_stability_enhancement = auto_enable_stability_enhancement
        self.supervisor_status_path, self.worker_status_path = build_service_status_paths(self.config_path)
        self.supervisor_log_path, self.worker_log_path = build_service_log_paths(self.config_path)
        self.browser_url = _build_browser_url(host, port)
        self.stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._network_thread: threading.Thread | None = None
        self._tray_icon: pystray.Icon | None = None
        self._child_process: subprocess.Popen[str] | None = None
        self._exit_code: int | None = None
        self._restart_count = 0
        self._restart_requested = False
        self._fast_failure_count = 0
        self._browser_opened = False
        self._logger = _configure_process_logger(self.supervisor_log_path, "service-supervisor")
        self.stability_manager = StabilityEnhancementManager(self.config_path)
        self.network_monitor = NetworkMonitor(self.config_path)

    def run_forever(self) -> int:
        _delete_status_file(self.worker_status_path)
        self._write_supervisor_status(state="starting", message="守护进程准备启动工作进程")
        self._recover_stability_enhancement_if_needed()
        if self.auto_enable_stability_enhancement:
            self._enable_stability_enhancement_after_relaunch()
        self._emit("info", f"受守护本地选座界面已准备启动：{self.browser_url}")
        self._emit("info", "当前模式会持续检测工作进程心跳，异常退出后会自动重启。")
        if self.tray_mode:
            self._emit("info", "系统托盘已启用，可在托盘菜单里查看状态、日志或退出。")
        else:
            self._emit("info", "可另开一个终端执行 status 命令查看运行状态。")
            self._emit("info", "按 Ctrl+C 可停止服务。")

        try:
            self._ensure_network_monitor_thread()
            while not self.stop_event.is_set():
                child_started_at = _now_iso()
                child = _spawn_worker_process(
                    self.config_path,
                    host=self.host,
                    port=self.port,
                    account_name=self.account_name,
                    hide_window=self.tray_mode,
                )
                self._child_process = child
                self._write_supervisor_status(
                    state="running",
                    message="工作进程运行中",
                    worker_pid=child.pid,
                    worker_started_at=child_started_at,
                )
                self._logger.info("工作进程已启动，PID=%s", child.pid)

                worker_became_ready = False
                loop_started_at = time.monotonic()
                worker_status: dict[str, Any] | None = None
                while not self.stop_event.is_set():
                    worker_status = _read_status_file(self.worker_status_path)
                    if worker_status and worker_status.get("state") == "running":
                        worker_became_ready = True
                        self._maybe_open_browser()
                    if child.poll() is not None:
                        break
                    if _is_worker_status_stale(worker_status):
                        self._emit("warning", "检测到工作进程心跳超时，正在自动拉起新的工作进程。")
                        self._write_supervisor_status(
                            state="backoff",
                            message="工作进程心跳超时，守护进程准备重启",
                            worker_pid=child.pid,
                        )
                        _stop_process(child)
                        break
                    self.stop_event.wait(SUPERVISOR_POLL_INTERVAL_SECONDS)

                if self.stop_event.is_set():
                    break

                exit_code = child.poll()
                runtime_seconds = time.monotonic() - loop_started_at
                self._child_process = None
                self._restart_count += 1
                if worker_became_ready or runtime_seconds >= FAST_FAILURE_WINDOW_SECONDS:
                    self._fast_failure_count = 0
                else:
                    self._fast_failure_count += 1

                last_message = _read_text(worker_status, "message") or f"工作进程已退出，退出码：{exit_code}"
                if self._fast_failure_count >= MAX_FAST_FAILURES:
                    self._write_supervisor_status(
                        state="error",
                        message=f"工作进程连续启动失败：{last_message}",
                        last_exit_code=exit_code,
                        last_exit_at=_now_iso(),
                    )
                    self._emit("error", "工作进程连续启动失败，已停止自动重启，请先查看日志。")
                    self._notify_tray("服务启动失败，请查看日志。")
                    self._exit_code = 1
                    return 1

                delay_seconds = min(2 ** min(self._restart_count, 5), int(MAX_RESTART_DELAY_SECONDS))
                self._write_supervisor_status(
                    state="backoff",
                    message=f"{last_message}；{delay_seconds} 秒后自动重启",
                    last_exit_code=exit_code,
                    last_exit_at=_now_iso(),
                )
                self._emit("warning", f"工作进程已退出，{delay_seconds} 秒后自动重启。最近原因：{last_message}")
                if self.stop_event.wait(delay_seconds):
                    break

            self._stop_child_process()
            self._write_supervisor_status(state="stopped", message="服务已停止")
            self._emit("info", "受守护本地选座界面已停止。")
            self._exit_code = 0
            return 0
        except KeyboardInterrupt:
            self.stop()
            self._exit_code = 0
            return 0
        except Exception:  # noqa: BLE001
            self._logger.exception("守护进程运行时出现未处理异常")
            self._write_supervisor_status(state="error", message="守护进程异常退出，请查看守护日志")
            self._notify_tray("守护进程异常退出，请查看守护日志。")
            self._exit_code = 1
            raise

    def run_with_tray(self) -> int:
        _hide_console_window()
        self._tray_icon = _build_tray_icon(self)

        def setup(icon: pystray.Icon) -> None:
            # pystray 传入自定义 setup 后，不会再自动把图标设为可见，这里必须显式显示。
            icon.visible = True
            self._thread = threading.Thread(target=self._run_service_thread, name="service-supervisor", daemon=False)
            self._thread.start()

        self._logger.info("系统托盘主循环即将启动")
        self._tray_icon.run(setup=setup)
        self._logger.info("系统托盘主循环已退出")
        if self._thread is not None:
            self._thread.join()
        if self._restart_requested:
            if _relaunch_detached_process(self._build_restart_command(), hide_window=True):
                return 0
            self._logger.error("托盘重启失败")
            _show_message_box("重启失败，请从日志里查看详情。", "重启失败", error=True)
            return 1
        return self._exit_code if self._exit_code is not None else 0

    def stop(self) -> None:
        self.stop_event.set()
        self._stop_child_process()
        self._disable_stability_enhancement_for_shutdown()
        self._write_supervisor_status(state="stopped", message="服务已停止")
        self._logger.info("服务已停止")

    def request_exit_from_tray(self, icon: pystray.Icon, item: object) -> None:
        del item
        self.stop()
        icon.stop()

    def request_restart_from_tray(self, icon: pystray.Icon, item: object) -> None:
        del item
        self._restart_requested = True
        self.stop()
        icon.stop()

    def open_browser_page(self, icon: pystray.Icon | None = None, item: object | None = None) -> None:
        del icon, item
        try:
            webbrowser.open(self.browser_url)
            self._logger.info("已从托盘打开浏览器：%s", self.browser_url)
        except Exception:  # noqa: BLE001
            self._logger.exception("从托盘打开浏览器失败")

    def open_settings_page(
        self,
        icon: pystray.Icon | None = None,
        item: object | None = None,
    ) -> None:
        del icon, item
        try:
            _spawn_settings_window_process(self.config_path, parent_pid=os.getpid())
            self._logger.info("已从托盘打开原生设置窗口")
        except Exception:  # noqa: BLE001
            self._logger.exception("从托盘打开原生设置窗口失败")
            self._notify_tray("设置窗口启动失败，请查看守护日志。")

    def show_status_dialog(self, icon: pystray.Icon | None = None, item: object | None = None) -> None:
        del icon, item
        _, report = get_service_status_report(self.config_path)
        _show_message_box(report, "服务状态", error=False)

    def open_worker_log(self, icon: pystray.Icon | None = None, item: object | None = None) -> None:
        del icon, item
        _open_path_in_notepad(self.worker_log_path)

    def open_supervisor_log(self, icon: pystray.Icon | None = None, item: object | None = None) -> None:
        del icon, item
        _open_path_in_notepad(self.supervisor_log_path)

    def open_log_directory(self, icon: pystray.Icon | None = None, item: object | None = None) -> None:
        del icon, item
        _open_directory(self.supervisor_log_path.parent)

    def enable_stability_enhancement(self, icon: pystray.Icon | None = None, item: object | None = None) -> None:
        del item
        if self.stability_manager.is_enabled():
            self._notify_tray("程序稳定性增强已启用")
            return
        if not _is_user_admin():
            self._handle_non_admin_stability_request(icon)
            return
        self._run_enable_stability_enhancement(notify=True)

    def disable_stability_enhancement(self, icon: pystray.Icon | None = None, item: object | None = None) -> None:
        del icon, item
        self._run_disable_stability_enhancement(notify=True)

    def _run_service_thread(self) -> None:
        try:
            exit_code = self.run_forever()
            self._exit_code = exit_code
            if exit_code != 0:
                self._notify_tray("服务已停止，请通过托盘查看日志。")
        except Exception as exc:  # noqa: BLE001
            self._exit_code = 1
            self._notify_tray(f"服务异常退出：{exc}")
        finally:
            if self._tray_icon is not None:
                try:
                    self._tray_icon.stop()
                except Exception:  # noqa: BLE001
                    self._logger.exception("停止托盘图标时失败")

    def _maybe_open_browser(self) -> None:
        if not self.open_browser or self._browser_opened:
            return
        try:
            webbrowser.open(self.browser_url)
            self._browser_opened = True
        except Exception:  # noqa: BLE001
            self._logger.exception("自动打开浏览器失败")

    def _emit(self, level: str, message: str) -> None:
        getattr(self._logger, level)(message)
        if not self.tray_mode:
            print(message)

    def _build_restart_command(self) -> list[str]:
        config_arg = str(self.config_path)
        if getattr(sys, "frozen", False):
            command = [sys.executable, "--config", config_arg, "web", "--host", self.host, "--port", str(self.port)]
        else:
            command = [
                sys.executable,
                "-m",
                "wuyi_seat_bot.cli",
                "--config",
                config_arg,
                "web",
                "--host",
                self.host,
                "--port",
                str(self.port),
            ]
        if not self.open_browser:
            command.append("--no-open")
        if self.account_name:
            command.extend(["--account", self.account_name])
        if self.tray_mode:
            command.append(TRAY_LAUNCH_FLAG)
        return command

    def _ensure_network_monitor_thread(self) -> None:
        if self._network_thread is not None and self._network_thread.is_alive():
            return
        self._network_thread = threading.Thread(
            target=self._run_network_monitor_thread,
            name="network-monitor",
            daemon=True,
        )
        self._network_thread.start()

    def _notify_tray(self, message: str) -> None:
        if self._tray_icon is None:
            return
        try:
            self._tray_icon.notify(message, "武夷学院自习室自动预约")
        except Exception:  # noqa: BLE001
            self._logger.exception("发送托盘通知失败")

    def _run_network_monitor_thread(self) -> None:
        next_run_at = 0.0
        consecutive_offline_count = 0
        while not self.stop_event.is_set():
            try:
                settings = self.network_monitor.load_settings()
                monitoring_settings = settings["networkMonitoring"]
                if not monitoring_settings["enabled"]:
                    next_run_at = 0.0
                    consecutive_offline_count = 0
                    self.stop_event.wait(NETWORK_MONITOR_IDLE_WAIT_SECONDS)
                    continue

                now = time.monotonic()
                if now < next_run_at:
                    self.stop_event.wait(
                        min(NETWORK_MONITOR_IDLE_WAIT_SECONDS, next_run_at - now)
                    )
                    continue

                detect_result = self.network_monitor.detect_once()
                self._logger.info("网络巡检结果：%s", detect_result["message"])
                detected_state = detect_result["networkState"]
                consecutive_offline_count = _update_offline_count(
                    detected_state, consecutive_offline_count
                )
                effective_state = detected_state
                if _should_attempt_auto_reconnect(
                    detected_state, consecutive_offline_count
                ):
                    reconnect_result = self.network_monitor.reconnect_once()
                    self._logger.warning("自动重连结果：%s", reconnect_result["message"])
                    effective_state = reconnect_result.get("networkState", detected_state)
                    if effective_state == "online":
                        consecutive_offline_count = 0
                next_run_at = time.monotonic() + _compute_next_run_delay_seconds(
                    effective_state,
                    consecutive_offline_count,
                    monitoring_settings["intervalMinutes"],
                )
            except Exception:  # noqa: BLE001
                self._logger.exception("后台网络巡检线程执行失败")
                self.stop_event.wait(NETWORK_MONITOR_IDLE_WAIT_SECONDS)

    def _recover_stability_enhancement_if_needed(self) -> None:
        try:
            message = self.stability_manager.recover_if_needed()
        except Exception:  # noqa: BLE001
            self._logger.exception("自动恢复稳定性增强状态失败")
            return
        if message:
            self._logger.info(message)

    def _enable_stability_enhancement_after_relaunch(self) -> None:
        self._run_enable_stability_enhancement(notify=False)

    def _disable_stability_enhancement_for_shutdown(self) -> None:
        if not self.stability_manager.is_enabled():
            return
        try:
            message = self.stability_manager.disable()
        except Exception:  # noqa: BLE001
            self._logger.exception("退出时恢复稳定性增强状态失败")
            return
        self._logger.info(message)

    def _run_enable_stability_enhancement(self, *, notify: bool) -> bool:
        try:
            message = self.stability_manager.enable()
        except Exception as exc:  # noqa: BLE001
            failure_message = f"启用程序稳定性增强失败：{exc}"
            self._logger.exception(failure_message)
            _show_message_box(failure_message, "稳定性增强", error=True)
            return False
        self._logger.info(message)
        if notify:
            self._notify_tray(message)
        return True

    def _run_disable_stability_enhancement(self, *, notify: bool) -> bool:
        try:
            message = self.stability_manager.disable()
        except Exception as exc:  # noqa: BLE001
            failure_message = f"关闭程序稳定性增强失败：{exc}"
            self._logger.exception(failure_message)
            _show_message_box(failure_message, "稳定性增强", error=True)
            return False
        self._logger.info(message)
        if notify:
            self._notify_tray(message)
        return True

    def _handle_non_admin_stability_request(self, icon: pystray.Icon | None) -> None:
        should_relaunch = _ask_yes_no_message_box(
            "程序稳定性增强需要管理员权限。是否立即以管理员身份重新启动程序并继续启用？",
            "需要管理员权限",
        )
        if not should_relaunch:
            return
        relaunch_argv = self._build_relaunch_argv(enable_stability_enhancement=True)
        if not _relaunch_process_as_admin(relaunch_argv):
            _show_message_box("以管理员重新启动失败，请检查 UAC 提示或系统策略。", "启动失败", error=True)
            return
        self.stop()
        if icon is not None:
            icon.stop()

    def _build_relaunch_argv(self, *, enable_stability_enhancement: bool) -> list[str]:
        command = [
            "--config",
            str(self.config_path),
            "web",
            "--host",
            self.host,
            "--port",
            str(self.port),
        ]
        if not self.open_browser:
            command.append("--no-open")
        if self.account_name:
            command.extend(["--account", self.account_name])
        if self.tray_mode:
            command.append(TRAY_LAUNCH_FLAG)
        if enable_stability_enhancement:
            command.append(ENABLE_STABILITY_ENHANCEMENT_FLAG)
        if getattr(sys, "frozen", False):
            return command
        return ["-m", "wuyi_seat_bot.cli", *command]

    def _stop_child_process(self) -> None:
        if self._child_process is None:
            return
        _stop_process(self._child_process)
        self._child_process = None

    def _write_supervisor_status(self, *, state: str, message: str, **extra: Any) -> None:
        payload = {
            "pid": os.getpid(),
            "state": state,
            "host": self.host,
            "port": self.port,
            "browserUrl": self.browser_url,
            "accountName": self.account_name,
            "restartCount": self._restart_count,
            "updatedAt": _now_iso(),
            "message": message,
            "supervisorLogPath": str(self.supervisor_log_path),
            "workerLogPath": str(self.worker_log_path),
            **extra,
        }
        _write_status_file(self.supervisor_status_path, payload)


def start_guarded_web_server(
    config_path: str | Path,
    *,
    host: str = "127.0.0.1",
    port: int = 8765,
    open_browser: bool = True,
    account_name: str | None = None,
    tray_mode: bool = False,
    auto_enable_stability_enhancement: bool = False,
) -> int:
    service = GuardedWebService(
        config_path,
        host=host,
        port=port,
        open_browser=open_browser,
        account_name=account_name,
        tray_mode=tray_mode,
        auto_enable_stability_enhancement=auto_enable_stability_enhancement,
    )
    if tray_mode:
        return service.run_with_tray()
    return service.run_forever()


def run_web_worker(
    config_path: str | Path,
    *,
    host: str = "127.0.0.1",
    port: int = 8765,
    account_name: str | None = None,
) -> int:
    config_path = Path(config_path).resolve()
    _, worker_status_path = build_service_status_paths(config_path)
    _, worker_log_path = build_service_log_paths(config_path)
    logger = _configure_process_logger(worker_log_path, "service-worker")
    app: SeatWebApp | None = None
    server: SeatWebServer | None = None
    stop_event = threading.Event()
    heartbeat_thread: threading.Thread | None = None
    browser_url = _build_browser_url(host, port)
    worker_started_at = _now_iso()

    try:
        _write_status_file(
            worker_status_path,
            {
                "pid": os.getpid(),
                "state": "starting",
                "host": host,
                "port": port,
                "browserUrl": browser_url,
                "accountName": account_name,
                "startedAt": worker_started_at,
                "updatedAt": _now_iso(),
                "message": "工作进程正在启动",
                "workerLogPath": str(worker_log_path),
            },
        )
        logger.info("工作进程正在启动")
        app = SeatWebApp(config_path, account_name=account_name)
        server = SeatWebServer((host, port), app)
        actual_host, actual_port = server.server_address
        browser_url = _build_browser_url(actual_host, actual_port)
        _write_worker_status(
            worker_status_path,
            state="running",
            host=actual_host,
            port=actual_port,
            browser_url=browser_url,
            started_at=worker_started_at,
            app=app,
            worker_log_path=worker_log_path,
            message="服务运行中",
        )
        logger.info("工作进程启动成功，访问地址：%s", browser_url)
        heartbeat_thread = threading.Thread(
            target=_worker_heartbeat_loop,
            name="service-worker-heartbeat",
            args=(worker_status_path, app, stop_event, worker_started_at, actual_host, actual_port, browser_url, worker_log_path),
            daemon=True,
        )
        heartbeat_thread.start()
        server.serve_forever(poll_interval=0.5)
        _write_worker_status(
            worker_status_path,
            state="stopped",
            host=actual_host,
            port=actual_port,
            browser_url=browser_url,
            started_at=worker_started_at,
            app=app,
            worker_log_path=worker_log_path,
            message="服务已停止",
        )
        logger.info("工作进程已停止")
        return 0
    except KeyboardInterrupt:
        if app is not None:
            _write_worker_status(
                worker_status_path,
                state="stopped",
                host=host,
                port=port,
                browser_url=browser_url,
                started_at=worker_started_at,
                app=app,
                worker_log_path=worker_log_path,
                message="服务已停止",
            )
        logger.info("工作进程被手动停止")
        return 0
    except Exception as exc:  # noqa: BLE001
        logger.exception("工作进程出现未处理异常")
        _write_status_file(
            worker_status_path,
            {
                "pid": os.getpid(),
                "state": "error",
                "host": host,
                "port": port,
                "browserUrl": browser_url,
                "accountName": account_name,
                "startedAt": worker_started_at,
                "updatedAt": _now_iso(),
                "message": f"{type(exc).__name__}: {exc}",
                "workerLogPath": str(worker_log_path),
            },
        )
        raise
    finally:
        stop_event.set()
        if server is not None:
            server.server_close()
        if heartbeat_thread is not None:
            heartbeat_thread.join(timeout=3)
        if app is not None:
            app.close()


def get_service_status_report(config_path: str | Path) -> tuple[bool, str]:
    supervisor_status, worker_status = load_service_status(config_path)
    if supervisor_status is None and worker_status is None:
        return False, "未检测到受守护的服务状态文件，请先运行 web 命令。"

    worker_alive = worker_status is not None and worker_status.get("state") == "running" and not _is_worker_status_stale(
        worker_status,
    )
    task_scheduler_alive = bool(worker_status and worker_status.get("taskSchedulerAlive"))
    automation_scheduler_alive = bool(worker_status and worker_status.get("automationSchedulerAlive"))
    checkin_monitor_alive = bool(worker_status and worker_status.get("checkinMonitorAlive"))
    healthy = worker_alive and task_scheduler_alive and automation_scheduler_alive and checkin_monitor_alive

    state_label = "运行中" if healthy else "异常"
    browser_url = _read_text(worker_status, "browserUrl") or _read_text(supervisor_status, "browserUrl")
    lines = [f"受守护服务状态：{state_label}"]
    if browser_url:
        lines.append(f"访问地址：{browser_url}")

    supervisor_pid = _read_text(supervisor_status, "pid")
    if supervisor_pid:
        lines.append(f"守护进程 PID：{supervisor_pid}")
    worker_pid = _read_text(worker_status, "pid")
    if worker_pid:
        lines.append(f"工作进程 PID：{worker_pid}")

    last_heartbeat = _read_text(worker_status, "updatedAt")
    if last_heartbeat:
        lines.append(f"最近心跳：{last_heartbeat}")

    restart_count = _read_text(supervisor_status, "restartCount")
    if restart_count:
        lines.append(f"自动重启次数：{restart_count}")

    if worker_status is not None:
        lines.append(
            "任务调度线程："
            f"{'正常' if task_scheduler_alive else '异常'}"
            f"（自愈 {int(worker_status.get('taskSchedulerRestartCount', 0) or 0)} 次）",
        )
        lines.append(
            "自动计划线程："
            f"{'正常' if automation_scheduler_alive else '异常'}"
            f"（自愈 {int(worker_status.get('automationSchedulerRestartCount', 0) or 0)} 次）",
        )
        lines.append(
            "签到巡检线程："
            f"{'正常' if checkin_monitor_alive else '异常'}"
            f"（自愈 {int(worker_status.get('checkinMonitorRestartCount', 0) or 0)} 次）",
        )
        lines.append(f"待执行定时任务：{int(worker_status.get('pendingTaskCount', 0) or 0)}")
        lines.append(f"启用中的自动计划：{int(worker_status.get('enabledAutomationPlanCount', 0) or 0)}")

    supervisor_log_path = _read_text(supervisor_status, "supervisorLogPath")
    worker_log_path = _read_text(worker_status, "workerLogPath") or _read_text(supervisor_status, "workerLogPath")
    if worker_log_path:
        lines.append(f"工作日志：{worker_log_path}")
    if supervisor_log_path:
        lines.append(f"守护日志：{supervisor_log_path}")

    message = _read_text(worker_status, "message") or _read_text(supervisor_status, "message")
    if message:
        lines.append(f"最近消息：{message}")
    return healthy, "\n".join(lines)


def load_service_status(config_path: str | Path) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    supervisor_status_path, worker_status_path = build_service_status_paths(config_path)
    return _read_status_file(supervisor_status_path), _read_status_file(worker_status_path)


def build_service_status_paths(config_path: str | Path) -> tuple[Path, Path]:
    config_path = Path(config_path).resolve()
    return (
        resolve_project_path(config_path, SUPERVISOR_STATUS_RELATIVE_PATH),
        resolve_project_path(config_path, WORKER_STATUS_RELATIVE_PATH),
    )


def build_service_log_paths(config_path: str | Path) -> tuple[Path, Path]:
    config_path = Path(config_path).resolve()
    log_directory = resolve_project_path(config_path, LOG_DIRECTORY_RELATIVE_PATH)
    return log_directory / SUPERVISOR_LOG_NAME, log_directory / WORKER_LOG_NAME


def _worker_heartbeat_loop(
    worker_status_path: Path,
    app: SeatWebApp,
    stop_event: threading.Event,
    started_at: str,
    host: str,
    port: int,
    browser_url: str,
    worker_log_path: Path,
) -> None:
    while not stop_event.is_set():
        app.ensure_background_services()
        _write_worker_status(
            worker_status_path,
            state="running",
            host=host,
            port=port,
            browser_url=browser_url,
            started_at=started_at,
            app=app,
            worker_log_path=worker_log_path,
            message="服务运行中",
        )
        stop_event.wait(WORKER_HEARTBEAT_INTERVAL_SECONDS)


def _write_worker_status(
    status_path: Path,
    *,
    state: str,
    host: str,
    port: int,
    browser_url: str,
    started_at: str,
    app: SeatWebApp,
    worker_log_path: Path,
    message: str,
) -> None:
    snapshot = app.get_service_snapshot()
    payload = {
        "pid": os.getpid(),
        "state": state,
        "host": host,
        "port": port,
        "browserUrl": browser_url,
        "startedAt": started_at,
        "updatedAt": _now_iso(),
        "message": message,
        "workerLogPath": str(worker_log_path),
        **snapshot,
    }
    _write_status_file(status_path, payload)


def _spawn_worker_process(
    config_path: Path,
    *,
    host: str,
    port: int,
    account_name: str | None,
    hide_window: bool,
) -> subprocess.Popen[str]:
    command = _build_worker_command(config_path, host=host, port=port, account_name=account_name)
    creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    if hide_window:
        creationflags |= getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return subprocess.Popen(command, creationflags=creationflags)


def _build_worker_command(
    config_path: Path,
    *,
    host: str,
    port: int,
    account_name: str | None,
) -> list[str]:
    config_arg = str(config_path)
    if getattr(sys, "frozen", False):
        command = [sys.executable, "--config", config_arg, "_web-worker", "--host", host, "--port", str(port)]
    else:
        command = [
            sys.executable,
            "-m",
            "wuyi_seat_bot.cli",
            "--config",
            config_arg,
            "_web-worker",
            "--host",
            host,
            "--port",
            str(port),
        ]
    if account_name:
        command.extend(["--account", account_name])
    return command


def _stop_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "nt" and hasattr(signal, "CTRL_BREAK_EVENT"):
            process.send_signal(signal.CTRL_BREAK_EVENT)
        else:
            process.terminate()
        process.wait(timeout=10)
    except Exception:  # noqa: BLE001
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)


def _read_status_file(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return payload if isinstance(payload, dict) else None


def _write_status_file(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_suffix(path.suffix + ".tmp")
    temp_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    temp_path.replace(path)


def _delete_status_file(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        return


def _is_worker_status_stale(worker_status: dict[str, Any] | None) -> bool:
    if not worker_status:
        return False
    updated_at = _read_text(worker_status, "updatedAt")
    if not updated_at:
        return False
    try:
        updated_at_time = datetime.fromisoformat(updated_at)
    except ValueError:
        return False
    return (datetime.now() - updated_at_time).total_seconds() > WORKER_STALE_SECONDS


def _build_browser_url(host: str, port: int) -> str:
    browser_host = "127.0.0.1" if host in {"0.0.0.0", ""} else host
    return f"http://{browser_host}:{port}/"


def _read_text(payload: dict[str, Any] | None, key: str) -> str | None:
    if payload is None:
        return None
    value = payload.get(key)
    if value in {None, ""}:
        return None
    return str(value)


def _configure_process_logger(log_path: Path, logger_name: str) -> logging.Logger:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    _trim_log_file_if_oversized(log_path)
    logger = logging.getLogger(f"wuyi-seat-bot.{logger_name}")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    for handler in list(logger.handlers):
        handler.close()
        logger.removeHandler(handler)
    handler = RotatingFileHandler(
        log_path,
        maxBytes=MAX_LOG_FILE_BYTES,
        backupCount=LOG_BACKUP_COUNT,
        encoding="utf-8",
    )
    handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
    logger.addHandler(handler)
    return logger


def _trim_log_file_if_oversized(log_path: Path) -> None:
    try:
        if not log_path.exists():
            return
        file_size = log_path.stat().st_size
        if file_size <= MAX_LOG_TRIM_TRIGGER_BYTES:
            return

        temp_path = log_path.with_suffix(log_path.suffix + ".trim")
        with log_path.open("rb") as source, temp_path.open("wb") as target:
            source.seek(min(LOG_TRIM_BYTES, file_size))
            shutil.copyfileobj(source, target)
        temp_path.replace(log_path)
    except OSError:
        return


def _open_path_in_notepad(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text("", encoding="utf-8")
    subprocess.Popen(["notepad.exe", str(path)])


def _open_directory(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    os.startfile(str(path))


def _hide_console_window() -> None:
    try:
        kernel32 = ctypes.windll.kernel32
        user32 = ctypes.windll.user32
        console_window = kernel32.GetConsoleWindow()
        if console_window:
            user32.ShowWindow(console_window, SW_HIDE)
    except Exception:  # noqa: BLE001
        return


def _show_message_box(message: str, title: str, *, error: bool) -> None:
    try:
        user32 = ctypes.windll.user32
        style = MESSAGE_BOX_ICON_ERROR if error else MESSAGE_BOX_ICON_INFORMATION
        user32.MessageBoxW(None, message, title, style)
    except Exception:  # noqa: BLE001
        return


def _ask_yes_no_message_box(message: str, title: str) -> bool:
    try:
        user32 = ctypes.windll.user32
        style = MESSAGE_BOX_ICON_WARNING | MESSAGE_BOX_YES_NO
        return user32.MessageBoxW(None, message, title, style) == MESSAGE_BOX_ID_YES
    except Exception:  # noqa: BLE001
        return False


def _is_user_admin() -> bool:
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:  # noqa: BLE001
        return False


def _relaunch_process_as_admin(argv: list[str]) -> bool:
    try:
        executable = sys.executable
        working_directory = str(Path(executable).resolve().parent)
        parameters = subprocess.list2cmdline(argv)
        result = ctypes.windll.shell32.ShellExecuteW(
            None,
            "runas",
            executable,
            parameters,
            working_directory,
            SW_SHOWNORMAL,
        )
        return result > 32
    except Exception:  # noqa: BLE001
        return False


def _build_tray_icon(service: GuardedWebService) -> pystray.Icon:
    menu = pystray.Menu(
        TrayMenuItem("显示界面", service.open_browser_page, default=True),
        TrayMenuItem("设置", service.open_settings_page),
        TrayMenuItem("重启程序", service.request_restart_from_tray),
        TrayMenuItem("退出程序", service.request_exit_from_tray),
    )
    return pystray.Icon(
        "wuyi-seat-bot",
        _build_tray_icon_image(),
        "武夷学院自习室自动预约",
        menu,
    )


def _spawn_settings_window_process(
    config_path: Path,
    *,
    parent_pid: int,
) -> subprocess.Popen[str]:
    command = _build_settings_window_command(config_path, parent_pid=parent_pid)
    return subprocess.Popen(
        command,
        creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) | getattr(subprocess, "CREATE_NO_WINDOW", 0),
        startupinfo=_build_hidden_startupinfo(),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def _build_settings_window_command(config_path: Path, *, parent_pid: int) -> list[str]:
    config_arg = str(config_path)
    parent_pid_text = str(parent_pid)
    if getattr(sys, "frozen", False):
        return [sys.executable, "--config", config_arg, "_settings-window", "--parent-pid", parent_pid_text]
    return [
        sys.executable,
        "-m",
        "wuyi_seat_bot.cli",
        "--config",
        config_arg,
        "_settings-window",
        "--parent-pid",
        parent_pid_text,
    ]


def _relaunch_detached_process(command: list[str], *, hide_window: bool) -> bool:
    creationflags = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    if hide_window:
        creationflags |= getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        subprocess.Popen(
            command,
            creationflags=creationflags,
            startupinfo=_build_hidden_startupinfo() if hide_window else None,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:  # noqa: BLE001
        return False
    return True


def _build_hidden_startupinfo() -> subprocess.STARTUPINFO | None:
    if not hasattr(subprocess, "STARTUPINFO"):
        return None
    startupinfo = subprocess.STARTUPINFO()
    startupinfo.dwFlags |= getattr(subprocess, "STARTF_USESHOWWINDOW", 0)
    startupinfo.wShowWindow = 0
    return startupinfo


def _should_attempt_auto_reconnect(network_state: str, consecutive_offline_count: int) -> bool:
    return network_state == "offline" and consecutive_offline_count >= AUTO_RECONNECT_OFFLINE_THRESHOLD


def _update_offline_count(network_state: str, current_count: int) -> int:
    if network_state == "offline":
        return current_count + 1
    if network_state == "online":
        return 0
    return current_count


def _compute_next_run_delay_seconds(
    network_state: str,
    consecutive_offline_count: int,
    interval_minutes: int,
) -> float:
    interval_seconds = float(max(0, interval_minutes) * 60)
    if network_state != "offline" or consecutive_offline_count <= 0:
        return interval_seconds
    exponent = max(0, consecutive_offline_count - 1)
    backoff_seconds = NETWORK_MONITOR_OFFLINE_RETRY_SECONDS * (2 ** exponent)
    cap_seconds = min(NETWORK_MONITOR_OFFLINE_RETRY_MAX_SECONDS, interval_seconds) \
        if interval_seconds > 0 else NETWORK_MONITOR_OFFLINE_RETRY_MAX_SECONDS
    upper_bound = max(NETWORK_MONITOR_OFFLINE_RETRY_SECONDS, cap_seconds)
    return min(backoff_seconds, upper_bound)


def _build_tray_icon_image() -> Image.Image:
    image = Image.new("RGBA", (64, 64), (22, 78, 99, 255))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((8, 8, 56, 56), radius=12, fill=(17, 94, 89, 255))
    draw.rectangle((18, 18, 46, 26), fill=(240, 253, 250, 255))
    draw.rectangle((18, 30, 46, 38), fill=(204, 251, 241, 255))
    draw.rectangle((18, 42, 38, 48), fill=(153, 246, 228, 255))
    draw.line((44, 42, 50, 48), fill=(251, 191, 36, 255), width=4)
    draw.line((50, 42, 44, 48), fill=(251, 191, 36, 255), width=4)
    return image


def _now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat()
