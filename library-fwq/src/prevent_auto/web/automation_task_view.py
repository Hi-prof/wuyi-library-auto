"""自动任务总览视图的 view-model 装配。

服务端把 ``automation_tasks`` 表的存量数据和最近一次上传来源拼成一个面向运维的
只读总览表，挂在 ``/automation-tasks`` 路由上。设计目标：

* 该页面是 Window 和 Android 两端把任务推上来后的「单一真相视图」，运维可以借此
  看到每个活跃账号上有哪些任务、最近谁推过来、当前 revision 是什么；服务端自动
  预约服务也读取这里的启用任务做补约。
* 「下线（软删）」按钮调用 :class:`AutomationTaskService.soft_delete`，``client_kind``
  填 :class:`ClientKind.WEB`，让客户端下一次同步时通过 revision/状态变化感知到
  服务端的强制下线，与既有 Automation_Task_Sync_API 协议保持一致。
* 不提供创建 / 编辑入口：任务内容仍由 Window / Android 两端各自负责上传；服务端
  只提供批量启停、删除和按启用任务补约。

筛选模型尽量复用 ``/logs?audit=pool`` 风格：所有过滤条件走 GET query，状态自带
反显，便于书签 / 分享。
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Iterable, Mapping

from prevent_auto.account_pool.models import (
    AutomationTask,
    ClientKind,
    CustomWindow,
    PoolStatus,
)
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogEntry,
    PoolAuditLogQuery,
    PoolAuditLogRepository,
)
from prevent_auto.web.account_pool_view import format_utc_to_local


#: 任务执行模式 → 中文文案；与 ``AutomationTaskUpsertModel.mode`` 字面量集合一一对应。
#: 新增模式时必须同步追加，否则页面会回退到原始字面量展示。
MODE_LABELS: Mapping[str, str] = {
    "preferred": "首选座位",
    "manual": "手动指定",
    "random": "随机分配",
}


#: 上传来源 → 中文文案，与 :class:`ClientKind` 严格对齐。``WEB`` / ``SYSTEM`` 仅在
#: 服务端内部触发的写入审计中出现（例如本页面的「下线」按钮 / Reaper Job 写回）。
CLIENT_KIND_LABELS: Mapping[str, str] = {
    ClientKind.WINDOW.value: "Windows 客户端",
    ClientKind.ANDROID.value: "Android 客户端",
    ClientKind.WEB.value: "服务端 Web",
    ClientKind.SYSTEM.value: "系统",
}


#: 「最近上传来源」筛选下拉框的可选值；只暴露真正会上行的端，其余 ClientKind 留作
#: 内部使用，不在 UI 上提供过滤入口（避免误导）。
UPLOADER_FILTER_CHOICES: tuple[tuple[str, str], ...] = (
    (ClientKind.WINDOW.value, CLIENT_KIND_LABELS[ClientKind.WINDOW.value]),
    (ClientKind.ANDROID.value, CLIENT_KIND_LABELS[ClientKind.ANDROID.value]),
    (ClientKind.WEB.value, CLIENT_KIND_LABELS[ClientKind.WEB.value]),
)


#: 启用状态筛选下拉框的可选值；空串表示不过滤。
ENABLED_FILTER_CHOICES: tuple[tuple[str, str], ...] = (
    ("enabled", "仅启用中"),
    ("disabled", "仅已停用"),
)


#: 取最近多少条 ``task_upload`` 审计行用于建「最近上传端」索引。当前活跃池规模
#: 远小于 500，足够覆盖每个 task 都至少命中一次。如果未来活跃池扩张，请把这里
#: 改成按 ``task_id`` 分组的 SQL，避免漏命中。
_UPLOADER_INDEX_LIMIT = 500


@dataclass(frozen=True)
class AutomationTaskRow:
    """单条自动任务在页面表格里的统一 view-model。

    所有时间字段都已经按 :func:`format_utc_to_local` 渲染成 ``Asia/Shanghai`` 文案，
    模板里直接吐字符串即可。``custom_windows`` 与 ``custom_windows_label`` 同时给出，
    后者用于表格紧凑展示，前者留给「详情」弹窗渲染逐条时段。
    """

    account_id: int
    student_id: str
    display_name: str
    task_id: int
    room_name: str
    seat_number: str
    mode: str
    mode_label: str
    custom_windows: tuple[CustomWindow, ...]
    custom_windows_label: str
    enabled: bool
    enabled_label: str
    revision: int
    updated_at_label: str
    last_uploader_kind: str
    last_uploader_label: str
    last_uploaded_at_label: str


@dataclass(frozen=True)
class AutomationTaskSummary:
    """页面顶部统计卡的聚合数。

    Attributes:
        total: ``automation_tasks`` 表里属于活跃账号且未软删的总条数。
        enabled_count: 其中 ``enabled=True`` 的条数。
        account_count: 涉及多少个独立活跃账号（去重后的 account_id 数）。
        upload_success_24h: 最近 24 小时内 ``task_upload`` 审计行数。
        upload_rejected_24h: 最近 24 小时内 ``task_upload_rejected`` 审计行数。
    """

    total: int
    enabled_count: int
    account_count: int
    upload_success_24h: int
    upload_rejected_24h: int


def _format_custom_windows(windows: Iterable[CustomWindow]) -> str:
    """把 ``custom_windows`` 列表压成 ``YYYY-MM-DD HH:MM-HH:MM`` 串联文案。

    多于 1 条用 ``；`` 分隔；空列表返回 ``—``，与表格其他空字段视觉一致。
    """

    items: list[str] = []
    for window in windows:
        items.append(
            f"{window.date} {window.start_hour:02d}:00-{window.end_hour:02d}:00"
        )
    return "；".join(items) if items else "—"


def _build_uploader_index(
    audit_repo: PoolAuditLogRepository,
) -> dict[tuple[int, int], tuple[str, datetime | None]]:
    """按 ``(account_id, task_id)`` 取最近一条 ``task_upload`` 的来源端 + 时间。

    审计行已经按 ``created_at DESC, id DESC`` 排序，遇到同 task_id 的后续行就跳过，
    保证每个 task 拿到的是最新一次的上传记录。返回 dict 的 value：

    * ``[0]``：``ClientKind.value``，``""`` 表示没有 ``client_kind`` 字段（理论上
      不会出现，但写入路径若改动也不会让索引爆）。
    * ``[1]``：UTC aware datetime，``None`` 表示审计行没填 ``created_at``（极少
      见，仅历史数据迁移时可能出现）。
    """

    entries = audit_repo.query(
        PoolAuditLogQuery(
            audit_action=PoolAuditAction.TASK_UPLOAD,
            limit=_UPLOADER_INDEX_LIMIT,
        )
    )
    index: dict[tuple[int, int], tuple[str, datetime | None]] = {}
    for entry in entries:
        if entry.account_id is None or entry.task_id is None:
            continue
        key = (entry.account_id, entry.task_id)
        if key in index:
            continue
        kind_value = entry.client_kind.value if entry.client_kind else ""
        index[key] = (kind_value, entry.created_at)
    return index


def _count_recent_audit(
    audit_repo: PoolAuditLogRepository,
    *,
    action: PoolAuditAction,
    cutoff: datetime,
) -> int:
    """统计 ``cutoff`` 之后某个 ``audit_action`` 的审计行数。

    实现上直接借用 ``query`` 加 ``created_after`` 拉一段后取 ``len``；活跃池审计量
    本身有限，不需要单独的 ``count`` SQL。
    """

    return len(
        audit_repo.query(
            PoolAuditLogQuery(audit_action=action, created_after=cutoff)
        )
    )


def _build_row(
    *,
    account_id: int,
    student_id: str,
    display_name: str,
    task: AutomationTask,
    uploader: tuple[str, datetime | None] | None,
) -> AutomationTaskRow:
    uploader_kind, uploader_time = uploader if uploader is not None else ("", None)
    return AutomationTaskRow(
        account_id=account_id,
        student_id=student_id,
        display_name=display_name,
        task_id=task.task_id,
        room_name=task.room_name,
        seat_number=task.seat_number,
        mode=task.mode,
        mode_label=MODE_LABELS.get(task.mode, task.mode),
        custom_windows=task.custom_windows,
        custom_windows_label=_format_custom_windows(task.custom_windows),
        enabled=task.enabled,
        enabled_label="启用中" if task.enabled else "已停用",
        revision=task.revision,
        updated_at_label=format_utc_to_local(task.updated_at),
        last_uploader_kind=uploader_kind,
        last_uploader_label=(
            CLIENT_KIND_LABELS.get(uploader_kind, uploader_kind)
            if uploader_kind
            else "—"
        ),
        last_uploaded_at_label=format_utc_to_local(uploader_time) or "—",
    )


def build_automation_task_view_context(
    *,
    account_pool_repo: AccountPoolRepository,
    automation_tasks_repo: AutomationTasksRepository,
    audit_repo: PoolAuditLogRepository,
    filter_account_id: str = "",
    filter_mode: str = "",
    filter_enabled: str = "",
    filter_uploader: str = "",
    now_utc: datetime | None = None,
) -> dict[str, object]:
    """一次性装配 ``/automation-tasks`` 页面用的所有 context。

    总数 / 启用数等统计是 **不受筛选影响** 的「全局态」，即顶部统计卡始终展示当前
    活跃池里的总体规模；筛选只控制下方表格行。这样既能让运维快速看到全局，又能
    通过 query 参数过滤定位单账号 / 单端的数据。

    返回 ``automation_task_filter_error`` 用来回显「账号 ID 不是整数」之类的输入
    错误；模板按既有 ``/logs?audit=pool`` 的同款风格直接展示一个 ``form-error``
    提示行。
    """

    now = now_utc or datetime.now(tz=UTC)

    filter_error = ""
    account_id_filter: int | None = None
    if filter_account_id.strip():
        try:
            account_id_filter = int(filter_account_id.strip())
            if account_id_filter <= 0:
                raise ValueError("账号 ID 必须是正整数")
        except ValueError:
            filter_error = "账号 ID 必须是正整数"
            account_id_filter = None

    mode_filter = filter_mode.strip().lower()
    if mode_filter and mode_filter not in MODE_LABELS:
        # 非法 mode 值忽略，不要把空表格甩给用户
        mode_filter = ""

    enabled_filter = filter_enabled.strip().lower()
    valid_enabled_filters = {value for value, _ in ENABLED_FILTER_CHOICES}
    if enabled_filter and enabled_filter not in valid_enabled_filters:
        enabled_filter = ""

    uploader_filter = filter_uploader.strip().lower()
    valid_uploader_values = {value for value, _ in UPLOADER_FILTER_CHOICES}
    if uploader_filter and uploader_filter not in valid_uploader_values:
        uploader_filter = ""

    entries = account_pool_repo.list_by_pool(PoolStatus.ACTIVE)
    uploader_index = _build_uploader_index(audit_repo)

    rows: list[AutomationTaskRow] = []
    affected_accounts: set[int] = set()
    enabled_count = 0
    total = 0

    for entry in entries:
        tasks = automation_tasks_repo.list_for_account(entry.account_id)
        for task in tasks:
            total += 1
            if task.enabled:
                enabled_count += 1
            affected_accounts.add(entry.account_id)

            if (
                account_id_filter is not None
                and entry.account_id != account_id_filter
            ):
                continue
            if mode_filter and task.mode != mode_filter:
                continue
            if enabled_filter == "enabled" and not task.enabled:
                continue
            if enabled_filter == "disabled" and task.enabled:
                continue

            uploader = uploader_index.get((entry.account_id, task.task_id))
            uploader_kind = uploader[0] if uploader else ""
            if uploader_filter and uploader_kind != uploader_filter:
                continue

            rows.append(
                _build_row(
                    account_id=entry.account_id,
                    student_id=entry.student_id,
                    display_name=entry.display_name,
                    task=task,
                    uploader=uploader,
                )
            )

    cutoff = now - timedelta(hours=24)
    summary = AutomationTaskSummary(
        total=total,
        enabled_count=enabled_count,
        account_count=len(affected_accounts),
        upload_success_24h=_count_recent_audit(
            audit_repo, action=PoolAuditAction.TASK_UPLOAD, cutoff=cutoff
        ),
        upload_rejected_24h=_count_recent_audit(
            audit_repo,
            action=PoolAuditAction.TASK_UPLOAD_REJECTED,
            cutoff=cutoff,
        ),
    )

    recent_audit_entries = audit_repo.query(
        PoolAuditLogQuery(
            audit_action=(
                PoolAuditAction.TASK_UPLOAD,
                PoolAuditAction.TASK_UPLOAD_REJECTED,
            ),
            limit=50,
        )
    )

    return {
        "automation_task_summary": summary,
        "automation_task_rows": rows,
        "automation_task_filter_error": filter_error,
        "automation_task_filter_account_id": filter_account_id.strip(),
        "automation_task_filter_mode": mode_filter,
        "automation_task_filter_enabled": enabled_filter,
        "automation_task_filter_uploader": uploader_filter,
        "automation_task_recent_audit_entries": recent_audit_entries,
        "automation_task_mode_options": tuple(MODE_LABELS.items()),
        "automation_task_uploader_options": UPLOADER_FILTER_CHOICES,
        "automation_task_enabled_options": ENABLED_FILTER_CHOICES,
    }


def build_recent_audit_row(entry: PoolAuditLogEntry) -> dict[str, object]:
    """把审计行映射为模板渲染用的扁平字典。

    与 :func:`prevent_auto.web.app._build_pool_audit_log_row` 对齐字段名（``summary`` /
    ``audit_action`` / ``trigger_source`` 等），保留单独实现是因为 view 模块不应该
    反向依赖 ``app.py``。
    """

    summary_parts: list[str] = []
    if entry.account_id is not None:
        summary_parts.append(f"账号 #{entry.account_id}")
    if entry.task_id is not None:
        summary_parts.append(f"任务 #{entry.task_id}")
    if entry.client_kind is not None:
        summary_parts.append(
            f"来源：{CLIENT_KIND_LABELS.get(entry.client_kind.value, entry.client_kind.value)}"
        )
    if entry.reason:
        summary_parts.append(f"原因：{entry.reason}")
    return {
        "id": entry.id,
        "created_at": (
            entry.created_at.isoformat() if entry.created_at is not None else None
        ),
        "audit_action": entry.audit_action.value,
        "audit_action_label": (
            "上传成功"
            if entry.audit_action is PoolAuditAction.TASK_UPLOAD
            else "上传被拒"
        ),
        "operator": entry.operator,
        "success": entry.success,
        "summary": " · ".join(summary_parts) if summary_parts else "",
    }


__all__ = [
    "AutomationTaskRow",
    "AutomationTaskSummary",
    "CLIENT_KIND_LABELS",
    "ENABLED_FILTER_CHOICES",
    "MODE_LABELS",
    "UPLOADER_FILTER_CHOICES",
    "build_automation_task_view_context",
    "build_recent_audit_row",
]
