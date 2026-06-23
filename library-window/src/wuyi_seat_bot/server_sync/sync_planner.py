"""Manual_Sync_Action 差异规划器。

本模块只承担「按 ``student_id`` 对比服务端活跃池快照与本地账号集合的受管字段
差异」这一项纯函数职责，与 design.md「数据流 4：Manual_Sync_Action 全流程」
中给出的 diff 算法等价。

设计依据：

- Requirement 13.5 / 13.10：Manual_Sync_Action 在拉取接口 A / 接口 B 后，弹
  Sync_Coverage_Confirmation 时按账号 (``student_id``) 行级展示「新增 / 替换 /
  移除」候选条目。
- Requirement 13.14：差异计算结果 ``SHALL`` 不因 Sync_Selection 的勾选变化而
  改变；Sync_Selection 仅作用于「应用差异」阶段。本模块只做差异计算，不维
  护任何 Sync_Selection 状态。
- Requirement 13.17 (旧 13-Q1 默认)：Manual_Sync_Action 是「按学号 diff」而非
  「全量覆盖」；本模块严格按 ``student_id`` 做集合比对，不做模糊匹配。

调用约束：

- 本模块 **不** 修改 Local_Account_Store；只产出 :class:`SyncCandidate`
  列表，由 ``sync_applier``（任务 11.11）按用户勾选状态执行覆盖。
- 本模块 **不** 依赖运行时网络状态；输入 ``server_snapshot`` 与 ``local_store``
  均由调用方在外层准备好。

受管字段集合（Requirement 13.6 / 13.13、design「Sync_Coverage_Confirmation
数据结构与字段口径」）::

    {student_id, password, display_name, automation_tasks, custom_windows}

其中 ``custom_windows`` 是 ``automation_tasks[i].custom_windows`` 的递归项；
本模块在比较 ``automation_tasks`` 时一并对每个任务的 ``custom_windows``
做内容比较，不再单独建模顶层 ``custom_windows`` 字段。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Literal

from wuyi_seat_bot.server_sync.active_pool_repository import (
    ActiveAccountDetail,
    AutomationTask,
    CustomWindow,
)


# --------------------------------------------------------------------------- #
# 受管字段常量                                                                  #
# --------------------------------------------------------------------------- #

MANAGED_FIELDS: frozenset[str] = frozenset(
    {
        "student_id",
        "password",
        "display_name",
        "automation_tasks",
        "custom_windows",
    }
)
"""Manual_Sync_Action 覆盖时受服务端管理的字段集合。

用于文档与外部模块（``sync_applier`` / UI）共享口径，本模块在对比时按字段
名逐一展开比较；``custom_windows`` 通过 ``automation_tasks`` 内部嵌套递归
覆盖，不再单独建模顶层字段。
"""


# --------------------------------------------------------------------------- #
# Data classes                                                                #
# --------------------------------------------------------------------------- #


@dataclass(frozen=True, slots=True)
class LocalAutomationTask:
    """sync_planner 视角下的本地自动任务快照。

    与服务端 :class:`AutomationTask` 字段一致，但不携带 ``updated_at`` 与
    ``revision`` 等服务端元数据，便于做受管字段差异对比。
    """

    task_id: int
    room_name: str
    seat_number: str
    mode: str
    custom_windows: tuple[CustomWindow, ...]
    enabled: bool


@dataclass(frozen=True, slots=True)
class LocalAccountSummary:
    """sync_planner 视角下的本地账号快照。

    Attributes:
        account_name: 本地配置中的账号名；用于把同步结果落回对应账号。
        student_id: 学号；与服务端清单的 ``student_id`` 字段对齐。
        password: 本地保存的密码（明文）。
        display_name: 备注 / 姓名。
        automation_tasks: 本地自动任务集合，顺序无关（比较时会规范化排序）。
        is_server_managed: 是否被「受服务端管理活跃账号」标记。仅当为
            ``True`` 且服务端清单不再包含该 ``student_id`` 时，diff 算法
            才产出 ``kind == "remove"`` 的候选条目（Requirement 13.18）。
    """

    student_id: str
    password: str
    display_name: str
    automation_tasks: tuple[LocalAutomationTask, ...]
    is_server_managed: bool
    account_name: str = ""


@dataclass(frozen=True, slots=True)
class SyncCandidate:
    """Sync_Coverage_Confirmation 弹窗中的单条候选。

    Attributes:
        kind: 同步动作类型；``add`` / ``replace`` / ``remove``。
        student_id: 候选行对应的学号。
        server_payload: 服务端原始 ``ActiveAccountDetail``（含密码、自动任务
            等完整信息）。``add`` / ``replace`` 时必填，``remove`` 时为
            ``None``。
        local_summary: 本地受管字段快照。``replace`` / ``remove`` 时必填，
            ``add`` 时为 ``None``。
        default_checked: 弹窗首次打开时该行的默认勾选状态。
            ``add`` / ``replace`` 默认 ``True``；``remove`` 默认 ``False``
            （Requirement 13.11）。
    """

    kind: Literal["add", "replace", "remove"]
    student_id: str
    server_payload: ActiveAccountDetail | None
    local_summary: LocalAccountSummary | None
    default_checked: bool


# --------------------------------------------------------------------------- #
# 公共 API                                                                     #
# --------------------------------------------------------------------------- #


def compute_diff(
    server_snapshot: Iterable[ActiveAccountDetail],
    local_store: Iterable[LocalAccountSummary],
) -> list[SyncCandidate]:
    """按 ``student_id`` 计算服务端活跃池快照与本地账号集合的受管字段差异。

    与 design.md「数据流 4：Manual_Sync_Action 全流程」中 diff 算法等价：

    - **新增**：服务端有该 ``student_id``，本地无。
    - **替换**：两端 ``student_id`` 匹配，但任一受管字段（学号 / 密码 /
      备注 / 自动任务 / 自定义窗口）不一致。
    - **移除**：本地标记 ``is_server_managed=True`` 但服务端清单已无对应
      ``student_id``。

    Args:
        server_snapshot: 接口 A / 接口 B 返回的活跃池账号详情集合。可空。
        local_store: 本地账号集合的受管字段快照。可空。

    Returns:
        候选条目列表，按 (kind, student_id) 升序稳定排序，方便 UI 展示
        与单元测试断言。``add`` / ``replace`` 默认 ``default_checked=True``；
        ``remove`` 默认 ``default_checked=False``。

    Raises:
        ValueError: 若 ``server_snapshot`` 中存在重复 ``student_id``，或
            ``local_store`` 中存在重复 ``student_id``。同一学号在同一端只
            应出现一次（Requirement 1-Q3 唯一键约束）。
    """

    server_by_sid = _index_by_student_id(
        server_snapshot,
        get_sid=lambda detail: detail.student_id,
        side="server_snapshot",
    )
    local_by_sid = _index_by_student_id(
        local_store,
        get_sid=lambda summary: summary.student_id,
        side="local_store",
    )

    candidates: list[SyncCandidate] = []

    # 新增：服务端有、本地无
    for sid, srv in server_by_sid.items():
        if sid not in local_by_sid:
            candidates.append(
                SyncCandidate(
                    kind="add",
                    student_id=sid,
                    server_payload=srv,
                    local_summary=None,
                    default_checked=True,
                )
            )

    # 替换：两端 student_id 匹配，但任一受管字段不一致
    for sid, srv in server_by_sid.items():
        if sid in local_by_sid:
            local_summary = local_by_sid[sid]
            if not _managed_fields_equal(srv, local_summary):
                candidates.append(
                    SyncCandidate(
                        kind="replace",
                        student_id=sid,
                        server_payload=srv,
                        local_summary=local_summary,
                        default_checked=True,
                    )
                )

    # 移除：本地标记「受服务端管理」但服务端清单已无对应 student_id
    for sid, local_summary in local_by_sid.items():
        if local_summary.is_server_managed and sid not in server_by_sid:
            candidates.append(
                SyncCandidate(
                    kind="remove",
                    student_id=sid,
                    server_payload=None,
                    local_summary=local_summary,
                    default_checked=False,
                )
            )

    # 稳定排序，便于 UI 展示与测试断言。
    _KIND_ORDER = {"add": 0, "replace": 1, "remove": 2}
    candidates.sort(key=lambda c: (_KIND_ORDER[c.kind], c.student_id))
    return candidates


# --------------------------------------------------------------------------- #
# 内部辅助                                                                      #
# --------------------------------------------------------------------------- #


def _index_by_student_id(items, *, get_sid, side: str):
    """按 ``student_id`` 建立索引，重复学号抛 ValueError。"""

    indexed: dict[str, object] = {}
    for item in items:
        sid = get_sid(item)
        if not isinstance(sid, str) or not sid:
            raise ValueError(f"{side} 条目的 student_id 必须是非空字符串")
        if sid in indexed:
            raise ValueError(f"{side} 中存在重复 student_id：{sid!r}")
        indexed[sid] = item
    return indexed  # type: ignore[return-value]


def _managed_fields_equal(
    server: ActiveAccountDetail, local: LocalAccountSummary
) -> bool:
    """比较受管字段集合是否完全一致。

    顺序无关地比较 ``automation_tasks``，并对每个任务的 ``custom_windows``
    做内容比较（忽略服务端附加的 ``updated_at`` / ``revision`` 元数据）。
    """

    if server.student_id != local.student_id:
        return False
    if server.password != local.password:
        return False
    if server.display_name != local.display_name:
        return False
    if _normalize_server_tasks(server.automation_tasks) != _normalize_local_tasks(
        local.automation_tasks
    ):
        return False
    return True


def _normalize_server_tasks(
    tasks: Iterable[AutomationTask],
) -> tuple[LocalAutomationTask, ...]:
    """把服务端 :class:`AutomationTask` 序列归一化为受管字段元组并稳定排序。"""

    normalized = [
        LocalAutomationTask(
            task_id=t.task_id,
            room_name=t.room_name,
            seat_number=t.seat_number,
            mode=t.mode,
            custom_windows=_normalize_custom_windows(t.custom_windows),
            enabled=t.enabled,
        )
        for t in tasks
    ]
    return tuple(sorted(normalized, key=_task_sort_key))


def _normalize_local_tasks(
    tasks: Iterable[LocalAutomationTask],
) -> tuple[LocalAutomationTask, ...]:
    """把本地 :class:`LocalAutomationTask` 序列做稳定排序，并归一化窗口序列。"""

    normalized = [
        LocalAutomationTask(
            task_id=t.task_id,
            room_name=t.room_name,
            seat_number=t.seat_number,
            mode=t.mode,
            custom_windows=_normalize_custom_windows(t.custom_windows),
            enabled=t.enabled,
        )
        for t in tasks
    ]
    return tuple(sorted(normalized, key=_task_sort_key))


def _normalize_custom_windows(
    windows: Iterable[CustomWindow],
) -> tuple[CustomWindow, ...]:
    """对自定义窗口序列做稳定排序，便于顺序无关比较。"""

    return tuple(
        sorted(
            windows,
            key=lambda w: (w.date, w.start_hour, w.end_hour),
        )
    )


def _task_sort_key(task: LocalAutomationTask) -> tuple:
    """自动任务排序键。

    优先按 ``task_id`` 排序；当多个任务共用同一 ``task_id``（理论上不应发生，
    但本模块不主动校验）时，按其它字段做次级排序，保证排序稳定。
    """

    return (
        task.task_id,
        task.room_name,
        task.seat_number,
        task.mode,
        task.enabled,
        task.custom_windows,
    )


__all__ = [
    "LocalAccountSummary",
    "LocalAutomationTask",
    "MANAGED_FIELDS",
    "SyncCandidate",
    "compute_diff",
]
