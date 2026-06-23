import types
import unittest
from unittest.mock import patch


class DesktopSettingsAppTestCase(unittest.TestCase):
    def test_is_process_running_uses_windows_process_api(self) -> None:
        from wuyi_seat_bot.desktop_settings import app

        fake_handle = object()
        fake_kernel32 = types.SimpleNamespace(
            OpenProcess=lambda *_args: fake_handle,
            GetExitCodeProcess=lambda handle, exit_code: self._set_exit_code(handle, exit_code, 259),
            CloseHandle=lambda handle: self.assertIs(handle, fake_handle),
        )

        with patch("wuyi_seat_bot.desktop_settings.app.os.name", "nt"), patch(
            "wuyi_seat_bot.desktop_settings.app.ctypes.windll",
            types.SimpleNamespace(kernel32=fake_kernel32),
            create=True,
        ):
            self.assertTrue(app._is_process_running(1234))

    def test_is_process_running_returns_false_for_terminated_windows_process(self) -> None:
        from wuyi_seat_bot.desktop_settings import app

        fake_handle = object()
        fake_kernel32 = types.SimpleNamespace(
            OpenProcess=lambda *_args: fake_handle,
            GetExitCodeProcess=lambda handle, exit_code: self._set_exit_code(handle, exit_code, 0),
            CloseHandle=lambda _handle: None,
        )

        with patch("wuyi_seat_bot.desktop_settings.app.os.name", "nt"), patch(
            "wuyi_seat_bot.desktop_settings.app.ctypes.windll",
            types.SimpleNamespace(kernel32=fake_kernel32),
            create=True,
        ):
            self.assertFalse(app._is_process_running(1234))

    @staticmethod
    def _set_exit_code(handle, exit_code, value: int) -> bool:
        del handle
        exit_code._obj.value = value
        return True
