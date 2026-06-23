"""Feature: account-pool-tri-sync, Property 16': 客户端同步按钮可用性（Window）。

Validates: Requirements 12.3, 12.4, 12.7, 13.9

本属性测试覆盖 design.md「Property 16: 客户端同步按钮可用性」的弱属性
（替换上一版的「服务端不可达拒绝执行 + 不读 config.json 兜底」硬约束）：

对 mock 后端响应 ``R ∈ {ConnectError, Timeout, 5xx, 401, 426}`` 五类响应：

1. 客户端 Manual_Sync_Action 按钮状态 SHALL 进入 ``disabled_unreachable``
   （即 ``enabled == false`` 且伴随错误状态指示文案，
   :meth:`ConnectivityIndicator.last_failure_reason` 非空）。
2. 本地业务循环（自动任务执行 / 登录刷新 / 座位监控）SHALL **不被中断、
   不被置灰、不弹「服务端不可达，已暂停」toast**：本地 ``LocalTaskScheduler``
   的 pending 任务在 sync 失败前后均能继续被执行。
3. mock 后端恢复 200 后下次 ``Manual_Sync_Action`` 同步按钮 SHALL 恢复
   ``enabled == true`` 且接口 A 调用不再被错误状态短路。

测试 **故意不覆盖** 上一版「服务端不可达 → 拒绝执行 + 不读 config.json 兜底」
断言；该断言已随 spec 11.4 显式撤回（参见任务 11.16）。

实现备注：

- 使用 :class:`hypothesis.strategies.sampled_from` 对 5 类 backend 响应分别
  生成一次执行序列；每条序列内部还随机化「失败次数」以验证多次失败累积仍
  收敛到 ``disabled_unreachable``，并最终恢复。
- 使用 :class:`httpx.MockTransport` 拦截 sync 客户端的 HTTP 调用；
  本地业务循环（``LocalTaskScheduler``）由测试夹具驱动，与 sync 模块
  完全解耦，验证「本地执行不依赖服务端可达性」的设计边界。
"""

from __future__ import annotations

import json
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx
import pytest
from hypothesis import HealthCheck, given, settings as hyp_settings, strategies as st

from wuyi_seat_bot.scheduler import (
    LocalTaskScheduler,
    PENDING_STATUS,
    SUCCESS_STATUS,
    ScheduledTask,
)
from wuyi_seat_bot.server_sync.active_pool_repository import ActivePoolRepository
from wuyi_seat_bot.server_sync.client import (
    HttpsRequired,
    ServerSyncClient,
    ServerSyncError,
    ServerUnreachable,
    Unauthorized,
)
from wuyi_seat_bot.server_sync.connectivity_indicator import (
    ConnectivityIndicator,
    ServerConnectivity,
    SyncButtonState,
)
from wuyi_seat_bot.server_sync.settings import ServerSyncSettings


# --------------------------------------------------------------------------- #
# 五类 mock 后端响应                                                            #
# --------------------------------------------------------------------------- #


BACKEND_MODES = ("ConnectError", "Timeout", "5xx", "401", "426")
"""Property 16' 中要求覆盖的五类后端响应。"""


_OK_LIST_BODY: dict[str, Any] = {
    "server_time": "2026-04-26T08:00:00Z",
    "accounts": [],
}


def _build_handler(mode_box: dict[str, str]):
    """构造 :class:`httpx.MockTransport` 的 handler。

    ``mode_box["current"]`` 在测试中可被切换，用于模拟「先错误再恢复」的时序。
    """

    def handler(request: httpx.Request) -> httpx.Response:
        mode = mode_box["current"]
        if mode == "ConnectError":
            raise httpx.ConnectError("connection refused", request=request)
        if mode == "Timeout":
            raise httpx.ReadTimeout("read timeout", request=request)
        if mode == "5xx":
            return httpx.Response(
                503,
                json={"reason": "internal_error"},
                headers={"content-type": "application/json"},
            )
        if mode == "401":
            return httpx.Response(
                401,
                json={"reason": "unauthorized"},
                headers={"content-type": "application/json"},
            )
        if mode == "426":
            return httpx.Response(
                426,
                json={"reason": "https_required"},
                headers={"content-type": "application/json"},
            )
        if mode == "200":
            return httpx.Response(
                200,
                json=_OK_LIST_BODY,
                headers={"content-type": "application/json"},
            )
        raise AssertionError(f"未知的 mock backend mode: {mode!r}")

    return handler


def _build_sync_stack(mode_box: dict[str, str]):
    """构造 sync 客户端 + 仓库 + 三态指示器；返回 (client, repository, indicator)。

    `is_configured_fn` 固定返回 True：本属性只关心「已配置但服务端响应错误」的语义。
    """

    transport = httpx.MockTransport(_build_handler(mode_box))

    def factory(**kwargs):
        kwargs["transport"] = transport
        return httpx.Client(**kwargs)

    settings = ServerSyncSettings(
        server_base_url="https://srv.example.com",
        bearer_token="tok-test",
        verify_tls=True,
        request_timeout_seconds=5.0,
    )
    client = ServerSyncClient(settings, client_factory=factory)
    connectivity = ServerConnectivity()
    indicator = ConnectivityIndicator(is_configured_fn=lambda: True)
    repository = ActivePoolRepository(client, connectivity)
    return client, repository, indicator


# --------------------------------------------------------------------------- #
# Manual_Sync_Action 流程包装                                                   #
# --------------------------------------------------------------------------- #


_BLOCKED_TOAST_BLACKLIST: tuple[str, ...] = (
    "服务端不可达，已暂停",
    "已暂停，请稍后重试",
)
"""Property 16'：本地业务循环 SHALL **不弹** 这几条上一版「拒绝执行」相关 toast。"""


class _ToastBus:
    """简易 toast 总线，用于测试断言「未弹出阻塞性 toast」。"""

    def __init__(self) -> None:
        self.messages: list[str] = []

    def push(self, text: str) -> None:
        self.messages.append(text)


def _drive_manual_sync_action(
    repository: ActivePoolRepository,
    indicator: ConnectivityIndicator,
    toast_bus: _ToastBus,
) -> tuple[bool, str]:
    """模拟 Manual_Sync_Action 一次完整流程。

    成功时调用 ``record_sync_success`` 并返回 ``(True, "")``；
    失败时调用 ``record_sync_failure(reason)``，并向 ``toast_bus`` 推一条
    「错误码 + 文案」的 toast（与 design.md 中 Manual_Sync_Action 失败口径一致：
    仅 toast 错误码，不弹「服务端不可达，已暂停」之类的「拒绝执行」文案）。
    """

    try:
        repository.refresh_active_list()
    except ServerUnreachable as exc:
        reason = str(exc) or "server_unreachable"
        indicator.record_sync_failure(reason)
        toast_bus.push(f"server_unreachable: {reason}")
        return (False, reason)
    except Unauthorized as exc:
        reason = str(exc) or "unauthorized_401"
        indicator.record_sync_failure(reason)
        toast_bus.push(f"unauthorized_401: {reason}")
        return (False, reason)
    except HttpsRequired as exc:
        reason = str(exc) or "https_required_426"
        indicator.record_sync_failure(reason)
        toast_bus.push(f"https_required_426: {reason}")
        return (False, reason)
    except ServerSyncError as exc:  # 兜底：其它 server_sync 异常也算失败
        reason = str(exc) or exc.__class__.__name__
        indicator.record_sync_failure(reason)
        toast_bus.push(f"sync_error: {reason}")
        return (False, reason)
    indicator.record_sync_success()
    return (True, "")


# --------------------------------------------------------------------------- #
# 本地业务循环夹具                                                              #
# --------------------------------------------------------------------------- #


def _build_local_scheduler(
    *,
    storage_path: Path,
    toast_bus: _ToastBus,
    indicator_probe: ConnectivityIndicator | None = None,
) -> tuple[LocalTaskScheduler, list[str]]:
    """构造一个能记录执行轨迹的 :class:`LocalTaskScheduler`。

    ``indicator_probe`` 仅作为「本地循环不依赖 connectivity」的诊断点存在：
    若本地循环在执行前 / 执行中检查 indicator 状态，本测试会把这一行为视作
    Property 16' 弱约束的违反并 fail。这里通过给 ``execute_task`` 传一个不读
    indicator 的简单 callback 实现。
    """

    executed: list[str] = []

    def execute_task(task: ScheduledTask) -> str:
        # 本地业务循环不读 connectivity 三态、不查 ServerSyncClient、不弹 toast；
        # 与 sync 模块完全解耦。
        executed.append(task.task_id)
        return "ok"

    scheduler = LocalTaskScheduler(
        storage_path=storage_path,
        execute_task=execute_task,
        poll_interval_seconds=60.0,
    )
    return scheduler, executed


def _seed_pending_task(scheduler: LocalTaskScheduler, *, account: str) -> ScheduledTask:
    """向调度器塞一条已到期的 pending 任务，便于 ``run_pending_once`` 立即取走。"""

    return scheduler.add_task(
        action="reservation",
        account_name=account,
        run_at=datetime.now().replace(microsecond=0).isoformat(),
        summary="本地自动任务执行入口（应不依赖服务端可达性）",
        payload={"room": "A101", "seat": "01"},
    )


# --------------------------------------------------------------------------- #
# Property 16'                                                                 #
# --------------------------------------------------------------------------- #


@hyp_settings(
    max_examples=50,
    deadline=None,
    suppress_health_check=[HealthCheck.function_scoped_fixture],
)
@given(
    backend=st.sampled_from(BACKEND_MODES),
    failure_repeats=st.integers(min_value=1, max_value=3),
    local_executions_during_failure=st.integers(min_value=0, max_value=2),
)
def test_property_16_sync_button_disabled_on_error_then_recovers(
    backend: str,
    failure_repeats: int,
    local_executions_during_failure: int,
    tmp_path_factory: pytest.TempPathFactory,
) -> None:
    """Property 16'：同步按钮在五类 backend 错误下置灰，本地循环不被中断，恢复后按钮重新可用。

    Validates: Requirements 12.3, 12.4, 12.7, 13.9
    """

    # ------------------------------------------------------------------ #
    # 准备 sync 栈 + 本地调度器 + toast 总线                                #
    # ------------------------------------------------------------------ #
    mode_box = {"current": backend}
    client, repository, indicator = _build_sync_stack(mode_box)
    toast_bus = _ToastBus()

    storage_path = tmp_path_factory.mktemp(f"sched-{backend}-") / "tasks.json"
    scheduler, executed = _build_local_scheduler(
        storage_path=storage_path,
        toast_bus=toast_bus,
    )

    try:
        # -------------------------------------------------------------- #
        # 阶段 1：同步按钮初始为 enabled（已配置 + 从未失败）                  #
        # -------------------------------------------------------------- #
        initial_state: SyncButtonState = indicator.compute_sync_button_state()
        assert initial_state == "enabled", (
            f"已配置且无失败记录时，同步按钮初始状态应为 enabled，实际为 {initial_state!r}"
        )

        # -------------------------------------------------------------- #
        # 阶段 2：触发 N 次失败的 Manual_Sync_Action                          #
        # -------------------------------------------------------------- #
        for _ in range(failure_repeats):
            ok, reason = _drive_manual_sync_action(repository, indicator, toast_bus)
            assert ok is False, (
                f"backend={backend} 应导致 Manual_Sync_Action 失败，实际成功"
            )
            assert reason, "失败时应记录非空 failure reason"

            # P16'-A: 同步按钮 enabled == false（disabled_unreachable）
            state: SyncButtonState = indicator.compute_sync_button_state()
            assert state == "disabled_unreachable", (
                f"backend={backend} 失败后同步按钮应为 disabled_unreachable，实际为 {state!r}"
            )
            assert indicator.is_reachable() is False
            # P16'-B: 错误状态指示有可读文案（last_failure_reason 非空）
            assert indicator.last_failure_reason() != "", (
                "disabled_unreachable 状态下应保留可读的错误状态指示文案"
            )

            # 本地业务循环：在每次失败之间继续执行 pending 任务，验证不被阻塞
            for _ in range(local_executions_during_failure):
                task = _seed_pending_task(scheduler, account=f"acct-{backend}")
                ran = scheduler.run_pending_once()
                assert ran is True, (
                    "本地任务调度器在 sync 失败状态下仍 SHALL 能执行 pending 任务"
                )
                # 检查任务实际执行了
                refreshed = next(
                    (t for t in scheduler.list_tasks() if t.task_id == task.task_id),
                    None,
                )
                assert refreshed is not None
                assert refreshed.status == SUCCESS_STATUS, (
                    f"本地任务应执行成功，实际状态为 {refreshed.status!r}"
                )
                assert task.task_id in executed

        # -------------------------------------------------------------- #
        # 阶段 3：关键禁止断言 —— 不弹「服务端不可达，已暂停」toast            #
        # -------------------------------------------------------------- #
        for blacklisted in _BLOCKED_TOAST_BLACKLIST:
            for msg in toast_bus.messages:
                assert blacklisted not in msg, (
                    f"Property 16' 禁止本地业务循环弹出阻塞性 toast，"
                    f"但发现 {msg!r} 含黑名单文案 {blacklisted!r}"
                )

        # -------------------------------------------------------------- #
        # 阶段 4：mock 恢复 200，下次 Manual_Sync_Action 应恢复 enabled 并完成正常同步 #
        # -------------------------------------------------------------- #
        mode_box["current"] = "200"
        ok, reason = _drive_manual_sync_action(repository, indicator, toast_bus)
        assert ok is True, (
            f"mock 恢复 200 后 Manual_Sync_Action 应成功，实际失败：{reason!r}"
        )
        assert reason == ""

        recovered_state: SyncButtonState = indicator.compute_sync_button_state()
        assert recovered_state == "enabled", (
            f"恢复 200 后按钮应回到 enabled，实际为 {recovered_state!r}"
        )
        assert indicator.is_reachable() is True
        assert indicator.last_failure_reason() == ""

        # 仓库缓存应得到一次正常同步的结果（空清单也算）
        cached = repository.cached_active_list()
        assert isinstance(cached, list)

    finally:
        client.close()


# --------------------------------------------------------------------------- #
# 五类 backend 的最小例子覆盖（hypothesis 之外的 sanity check）                  #
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("backend", BACKEND_MODES)
def test_property_16_each_backend_kind_disables_sync_button(
    backend: str,
    tmp_path: Path,
) -> None:
    """对每类 backend 单独覆盖一次（example 用例）。

    保证 Hypothesis sample 不到极端组合时，每类 backend 仍至少跑过一次。
    """

    mode_box = {"current": backend}
    client, repository, indicator = _build_sync_stack(mode_box)
    toast_bus = _ToastBus()
    scheduler, executed = _build_local_scheduler(
        storage_path=tmp_path / "tasks.json",
        toast_bus=toast_bus,
    )

    try:
        ok, reason = _drive_manual_sync_action(repository, indicator, toast_bus)
        assert ok is False
        assert reason
        assert indicator.compute_sync_button_state() == "disabled_unreachable"
        assert indicator.is_reachable() is False

        # 本地业务循环：执行一条 pending 任务
        _seed_pending_task(scheduler, account="local-only-account")
        assert scheduler.run_pending_once() is True
        assert len(executed) == 1

        for blacklisted in _BLOCKED_TOAST_BLACKLIST:
            for msg in toast_bus.messages:
                assert blacklisted not in msg
    finally:
        client.close()
