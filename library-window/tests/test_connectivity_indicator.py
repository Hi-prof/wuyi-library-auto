"""ConnectivityIndicator 单元测试。

覆盖：
- 三态计算 ``compute_sync_button_state()`` 的全部分支。
- 状态变更方法 ``record_sync_success()`` / ``record_sync_failure()``。
- TTL 过期后退化为 ``disabled_unreachable``。
- ``is_reachable()`` 便捷方法。
- ``reset()`` 重置。
- 构造参数校验。
"""

from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock

from wuyi_seat_bot.server_sync.connectivity_indicator import (
    ConnectivityIndicator,
    SyncButtonState,
)


class ConnectivityIndicatorTestCase(unittest.TestCase):
    """ConnectivityIndicator 核心行为测试。"""

    def setUp(self) -> None:
        self.now = datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc)

    def _make(
        self,
        *,
        configured: bool = True,
        ttl: float = 300.0,
    ) -> tuple[ConnectivityIndicator, MagicMock]:
        clock = MagicMock(side_effect=lambda: self.now)
        indicator = ConnectivityIndicator(
            is_configured_fn=lambda: configured,
            reachable_ttl_seconds=ttl,
            clock=clock,
        )
        return indicator, clock

    # ------------------------------------------------------------------ #
    # 未配置场景                                                            #
    # ------------------------------------------------------------------ #

    def test_unconfigured_returns_disabled_unconfigured(self) -> None:
        indicator, _ = self._make(configured=False)
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unconfigured"
        )

    def test_unconfigured_ignores_success_history(self) -> None:
        indicator, _ = self._make(configured=False)
        indicator.record_sync_success()
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unconfigured"
        )

    # ------------------------------------------------------------------ #
    # 已配置 - 初始状态                                                     #
    # ------------------------------------------------------------------ #

    def test_initial_state_configured_returns_enabled(self) -> None:
        """首次启动，未调用过 sync → 允许用户尝试。"""
        indicator, _ = self._make(configured=True)
        self.assertEqual(indicator.compute_sync_button_state(), "enabled")

    # ------------------------------------------------------------------ #
    # 已配置 - 成功后                                                       #
    # ------------------------------------------------------------------ #

    def test_after_success_returns_enabled(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_success()
        self.assertEqual(indicator.compute_sync_button_state(), "enabled")

    def test_success_within_ttl_remains_enabled(self) -> None:
        indicator, _ = self._make(configured=True, ttl=60.0)
        indicator.record_sync_success()
        # 推进时钟到 TTL 内
        self.now = self.now + timedelta(seconds=59)
        self.assertEqual(indicator.compute_sync_button_state(), "enabled")

    # ------------------------------------------------------------------ #
    # 已配置 - TTL 过期                                                     #
    # ------------------------------------------------------------------ #

    def test_ttl_expired_returns_disabled_unreachable(self) -> None:
        indicator, _ = self._make(configured=True, ttl=60.0)
        indicator.record_sync_success()
        # 推进时钟超过 TTL
        self.now = self.now + timedelta(seconds=61)
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )

    # ------------------------------------------------------------------ #
    # 已配置 - 失败后                                                       #
    # ------------------------------------------------------------------ #

    def test_after_failure_returns_disabled_unreachable(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_failure("connect timeout")
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )

    def test_failure_reason_stored(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_failure("DNS 解析失败")
        self.assertEqual(indicator.last_failure_reason(), "DNS 解析失败")

    def test_failure_blank_reason_uses_default(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_failure("   ")
        self.assertEqual(indicator.last_failure_reason(), "服务端不可达")

    def test_failure_empty_reason_uses_default(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_failure("")
        self.assertEqual(indicator.last_failure_reason(), "服务端不可达")

    # ------------------------------------------------------------------ #
    # 已配置 - 恢复流程                                                     #
    # ------------------------------------------------------------------ #

    def test_recovery_after_failure(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_failure("network down")
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )
        # 恢复
        indicator.record_sync_success()
        self.assertEqual(indicator.compute_sync_button_state(), "enabled")
        self.assertEqual(indicator.last_failure_reason(), "")

    # ------------------------------------------------------------------ #
    # is_reachable 便捷方法                                                 #
    # ------------------------------------------------------------------ #

    def test_is_reachable_initial_true(self) -> None:
        indicator, _ = self._make(configured=True)
        self.assertTrue(indicator.is_reachable())

    def test_is_reachable_after_success(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_success()
        self.assertTrue(indicator.is_reachable())

    def test_is_reachable_after_failure(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_failure("timeout")
        self.assertFalse(indicator.is_reachable())

    def test_is_reachable_ttl_expired(self) -> None:
        indicator, _ = self._make(configured=True, ttl=60.0)
        indicator.record_sync_success()
        self.now = self.now + timedelta(seconds=61)
        self.assertFalse(indicator.is_reachable())

    # ------------------------------------------------------------------ #
    # reset                                                               #
    # ------------------------------------------------------------------ #

    def test_reset_clears_state(self) -> None:
        indicator, _ = self._make(configured=True)
        indicator.record_sync_failure("error")
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )
        indicator.reset()
        # 恢复到初始状态：已配置但从未调用 → enabled
        self.assertEqual(indicator.compute_sync_button_state(), "enabled")
        self.assertEqual(indicator.last_failure_reason(), "")

    # ------------------------------------------------------------------ #
    # 构造参数校验                                                          #
    # ------------------------------------------------------------------ #

    def test_invalid_ttl_rejected(self) -> None:
        with self.assertRaises(ValueError):
            ConnectivityIndicator(
                is_configured_fn=lambda: True, reachable_ttl_seconds=0
            )
        with self.assertRaises(ValueError):
            ConnectivityIndicator(
                is_configured_fn=lambda: True, reachable_ttl_seconds=-1
            )

    # ------------------------------------------------------------------ #
    # 仅失败无成功记录                                                       #
    # ------------------------------------------------------------------ #

    def test_only_failures_returns_disabled_unreachable(self) -> None:
        """有失败但从未有成功记录 → disabled_unreachable。"""
        indicator, _ = self._make(configured=True)
        indicator.record_sync_failure("500")
        self.assertEqual(
            indicator.compute_sync_button_state(), "disabled_unreachable"
        )


if __name__ == "__main__":
    unittest.main()
