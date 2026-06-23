from __future__ import annotations

import ctypes
import hashlib
import os
import queue
import socket
import threading
from pathlib import Path

from wuyi_seat_bot.desktop_settings.service import DesktopSettingsService
from wuyi_seat_bot.desktop_settings.window import SettingsWindow

LOCAL_HOST = "127.0.0.1"
ACTIVATE_MESSAGE = b"activate"
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
STILL_ACTIVE = 259


def run_settings_window(config_path: str | Path, *, parent_pid: int | None = None) -> int:
    resolved_config_path = Path(config_path).resolve()
    port = _build_single_instance_port(resolved_config_path)

    if _notify_existing_instance(port):
        return 0

    activation_queue: queue.SimpleQueue[str] = queue.SimpleQueue()
    listener = _ActivationListener(port, activation_queue)
    listener.start()

    window = SettingsWindow(
        service=DesktopSettingsService(resolved_config_path),
        config_path=resolved_config_path,
    )

    def poll_external_events() -> None:
        try:
            while True:
                activation_queue.get_nowait()
                window.activate_window()
        except queue.Empty:
            pass

        if parent_pid is not None and not _is_process_running(parent_pid):
            window.close()
            return
        window.root.after(500, poll_external_events)

    window.root.after(500, poll_external_events)
    try:
        return window.run()
    finally:
        listener.stop()


def _build_single_instance_port(config_path: Path) -> int:
    digest = hashlib.sha1(str(config_path).encode("utf-8")).hexdigest()[:8]
    return 45000 + int(digest, 16) % 15000


def _notify_existing_instance(port: int) -> bool:
    try:
        with socket.create_connection((LOCAL_HOST, port), timeout=0.2) as connection:
            connection.sendall(ACTIVATE_MESSAGE)
    except OSError:
        return False
    return True


def _is_process_running(process_id: int) -> bool:
    if process_id <= 0:
        return False
    if os.name == "nt":
        return _is_windows_process_running(process_id)
    try:
        os.kill(process_id, 0)
    except OSError:
        return False
    return True


def _is_windows_process_running(process_id: int) -> bool:
    kernel32 = ctypes.windll.kernel32
    process_handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, process_id)
    if not process_handle:
        return False

    try:
        exit_code = ctypes.c_ulong()
        if not kernel32.GetExitCodeProcess(process_handle, ctypes.byref(exit_code)):
            return False
        return int(exit_code.value) == STILL_ACTIVE
    finally:
        kernel32.CloseHandle(process_handle)


class _ActivationListener:
    def __init__(self, port: int, activation_queue: queue.SimpleQueue[str]) -> None:
        self.port = port
        self.activation_queue = activation_queue
        self._stop_event = threading.Event()
        self._thread = threading.Thread(target=self._run, name="settings-activation-listener", daemon=True)
        self._server_socket: socket.socket | None = None

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._server_socket is not None:
            try:
                self._server_socket.close()
            except OSError:
                return
        self._thread.join(timeout=1)

    def _run(self) -> None:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server_socket:
                self._server_socket = server_socket
                server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                server_socket.bind((LOCAL_HOST, self.port))
                server_socket.listen()
                server_socket.settimeout(0.5)
                while not self._stop_event.is_set():
                    try:
                        connection, _ = server_socket.accept()
                    except TimeoutError:
                        continue
                    except OSError:
                        break
                    with connection:
                        try:
                            payload = connection.recv(64)
                        except OSError:
                            continue
                        if payload.startswith(ACTIVATE_MESSAGE):
                            self.activation_queue.put("activate")
        except OSError:
            return
