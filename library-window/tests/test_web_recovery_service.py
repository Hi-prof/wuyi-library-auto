from __future__ import annotations

import unittest
from unittest.mock import MagicMock

from wuyi_seat_bot.models import ActionResult, ActionType
from wuyi_seat_bot.web_recovery_service import (
    recover_checkins_after_network_failure,
    recover_network_after_action_failure,
)


def _failed_result(action: ActionType = ActionType.CHECKOUT) -> ActionResult:
    return ActionResult(
        success=False,
        action=action,
        seat_url=None,
        attempts=1,
        message="接口执行异常：urlopen error",
    )


class WebRecoveryServiceTestCase(unittest.TestCase):
    def test_action_failure_reports_degraded_without_reconnect(self) -> None:
        recovery_lock = MagicMock()
        recovery_lock.acquire.return_value = True
        monitor = MagicMock()
        monitor.detect_once.return_value = {
            "networkState": "degraded",
            "message": "学校目标站点连通性检测失败",
        }
        original_result = _failed_result()

        result = recover_network_after_action_failure(
            original_result=original_result,
            recovery_lock=recovery_lock,
            get_network_monitor=lambda: monitor,
        )

        self.assertFalse(result.success)
        self.assertIn(original_result.message, result.message)
        self.assertIn("学校目标站点暂时不通", result.message)
        monitor.reconnect_once.assert_not_called()
        recovery_lock.release.assert_called_once_with()

    def test_action_failure_reconnects_when_offline(self) -> None:
        recovery_lock = MagicMock()
        recovery_lock.acquire.return_value = True
        monitor = MagicMock()
        monitor.detect_once.return_value = {
            "networkState": "offline",
            "message": "通用网络探测未通过",
        }
        monitor.reconnect_once.return_value = {
            "networkState": "online",
            "message": "网络连接正常",
        }

        result = recover_network_after_action_failure(
            original_result=_failed_result(),
            recovery_lock=recovery_lock,
            get_network_monitor=lambda: monitor,
        )

        self.assertFalse(result.success)
        self.assertIn("网络已自动重连", result.message)
        monitor.reconnect_once.assert_called_once_with()
        recovery_lock.release.assert_called_once_with()

    def test_checkin_failure_still_recovers_when_degraded(self) -> None:
        recovery_lock = MagicMock()
        recovery_lock.acquire.return_value = True
        monitor = MagicMock()
        monitor.detect_once.return_value = {
            "networkState": "degraded",
            "message": "学校目标站点连通性检测失败",
        }
        monitor.reconnect_once.return_value = {
            "networkState": "online",
            "message": "网络连接正常",
        }

        result = recover_checkins_after_network_failure(
            trigger_account_name="主号",
            original_result=_failed_result(ActionType.CHECKIN),
            recovery_lock=recovery_lock,
            get_network_monitor=lambda: monitor,
            list_recovery_checkin_accounts=lambda: (),
            run_checkin_once=MagicMock(),
        )

        if result is None:
            self.fail("expected recovery result")
        self.assertIn("网络已恢复", result.message)
        monitor.reconnect_once.assert_called_once_with()
        recovery_lock.release.assert_called_once_with()
