"""accounts.html 三池 Tab 视图的 view-model 装配。

本模块对应 spec ``account-pool-tri-sync`` 的 task 9.1。设计目标：

* 把 :class:`prevent_auto.services.account_pool_service.AccountPoolService` 的
  ``list_by_pool`` 与 :class:`prevent_auto.services.account_service.AccountService`
  的 ``Account.last_status`` 拼成 Tab 专用 view-model，避免模板里再做服务调用与
  时间格式化。
* 三池字段映射严格按 design「Account_Pool_Web_Page UI 设计」表：
  * Active：学号 / 备注 / 启用开关 / 登录态文案（来自 ``Login_Status_Cache`` 与
    既有 ``Account.last_status``）。
  * Suspended：学号 / 备注 / 暂停起始 / 剩余暂停 / 到期。
  * Idle：学号 / 备注 / 进入未启用时间 / 上一次所在池。
* 时间字段一律 UTC 存储，UI 渲染按 Asia/Shanghai (``SHANGHAI_TZ``)。剩余暂停时长
  渲染由 :func:`format_remaining_suspension` 提供：``now <= expires_at`` 返回
  ``HH 时 MM 分``，已过期但尚未被 reaper 回收时返回 ``0 分钟`` 而非负值
  （Requirement 3.6 / design Property 6）。
* 三池 Tab 以同页面切换 + ``?pool=active|suspended|idle`` 区分（Requirement 4-Q1
  默认）；非法 / 缺省时回退到 ``active``。
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, Iterable, Mapping

from wuyi_seat_bot.seat_api import SHANGHAI_TZ

from prevent_auto.account_pool.models import AccountPoolEntry, PoolStatus

if TYPE_CHECKING:  # pragma: no cover - 仅类型导入
    from prevent_auto.services.account_pool_service import (
        AccountPoolService,
        LoginStatusCache,
    )
    from prevent_auto.services.account_service import AccountService


#: 三池 Tab 的字面量集合；按 URL 参数到 :class:`PoolStatus` 的映射。
POOL_TAB_KEYS: tuple[str, ...] = ("active", "suspended", "idle")
DEFAULT_POOL_TAB: str = "active"

#: 池迁移按钮渲染顺序与文案（design「Account_Pool_Web_Page UI 设计」）。
MIGRATION_TARGETS: tuple[tuple[str, str], ...] = (
    ("active", "迁入活跃池"),
    ("suspended", "迁入拉黑号池"),
    ("idle", "迁入未启用池"),
)

#: ``Login_Status_Cache.contains`` 命中 / 未命中时的 UI 文案。
#: 现在 ``LoginStatusCache`` 走 SQLite 持久化（见 :class:`SqliteLoginStatusCache`），
#: 「占位」语义保持不变：账号当前归属 Active_Pool 时即视为「已登录」。
LOGIN_STATUS_CACHE_TRACKED_LABEL = "已登录"
LOGIN_STATUS_CACHE_MISSING_LABEL = "未登录"


@dataclass(frozen=True)
class PoolTab:
    """单个 Tab 的元信息，模板里渲染顶部 Tab 链接用。"""

    key: str
    label: str
    href: str
    active: bool
    count: int


@dataclass(frozen=True)
class PoolRowView:
    """三池列表行的统一 view-model。

    模板按 :attr:`pool` 决定哪些字段可见：

    * ``active`` → 显示 :attr:`login_status_label` / :attr:`last_status_label`。
    * ``suspended`` → 显示 :attr:`suspended_at_label` / :attr:`remaining_label` /
      :attr:`expires_at_label`。
    * ``idle`` → 显示 :attr:`entered_at_label` / :attr:`pool_previous_label`。

    :attr:`migration_buttons` 为该行的「更多」对话框提供三个独立按钮。前端不强制
    阻止非法迁移，由服务端响应决定（design Property 3 / Requirement 4.6）。
    """

    account_id: int
    student_id: str
    display_name: str
    pool: str
    pool_label: str
    enabled: bool
    enabled_label: str
    last_status_label: str
    login_status_label: str
    suspended_at_label: str
    remaining_label: str
    expires_at_label: str
    entered_at_label: str
    pool_previous_label: str
    migration_buttons: tuple[tuple[str, str], ...]


_POOL_LABELS: Mapping[str, str] = {
    PoolStatus.ACTIVE.value: "活跃池",
    PoolStatus.SUSPENDED.value: "拉黑号池",
    PoolStatus.IDLE.value: "未启用池",
}


def normalize_pool_tab(raw: str | None) -> str:
    """把 URL ``?pool=`` 参数规整为 :data:`POOL_TAB_KEYS` 中的字面量。

    缺省 / 非法值返回 :data:`DEFAULT_POOL_TAB`，避免页面报错。
    """

    if not raw:
        return DEFAULT_POOL_TAB
    text = raw.strip().lower()
    if text in POOL_TAB_KEYS:
        return text
    return DEFAULT_POOL_TAB


def format_remaining_suspension(
    expires_at: datetime | None,
    now_utc: datetime,
) -> str:
    """返回剩余暂停时长的中文文案。

    实现满足 design Property 6：``result == max(0, expires_at - now_utc)``，过期
    时返回 ``0 分钟`` 而非负值。``expires_at`` 为 ``None`` 时返回空串，留给模板做
    兜底处理（理论上 suspended 行不会出现 ``None``，由 DB CHECK 约束保证）。
    """

    if expires_at is None:
        return ""
    if expires_at.tzinfo is None:
        raise ValueError("expires_at 必须带时区，禁止 naive datetime")
    if now_utc.tzinfo is None:
        raise ValueError("now_utc 必须带时区，禁止 naive datetime")

    delta = expires_at - now_utc
    if delta <= timedelta(0):
        return "0 分钟"
    total_seconds = int(delta.total_seconds())
    days, remainder = divmod(total_seconds, 86_400)
    hours, remainder = divmod(remainder, 3600)
    minutes = remainder // 60
    parts: list[str] = []
    if days:
        parts.append(f"{days} 天")
    if hours:
        parts.append(f"{hours} 时")
    # 不足 1 分钟也要展示「< 1 分钟」，避免 UI 上出现空字符串。
    if not parts and minutes == 0:
        return "不足 1 分钟"
    parts.append(f"{minutes} 分钟")
    return " ".join(parts)


def format_utc_to_local(value: datetime | None) -> str:
    """把 UTC aware datetime 渲染为 Asia/Shanghai 本地时间字符串。

    ``None`` 与 naive datetime 都返回空串，让模板按「无时间」兜底。日期格式与
    既有 :func:`prevent_auto.web.runtime._format_iso_datetime` 保持一致，避免页面
    多种时间样式。
    """

    if value is None:
        return ""
    if value.tzinfo is None:
        return ""
    return value.astimezone(SHANGHAI_TZ).strftime("%Y年%m月%d日%H时%M分%S秒")


def build_pool_tabs(
    *,
    selected: str,
    counts: Mapping[str, int],
) -> tuple[PoolTab, ...]:
    """按选中状态构造 Tab 列表，用于模板顶部导航渲染。"""

    selected_normalized = normalize_pool_tab(selected)
    return tuple(
        PoolTab(
            key=key,
            label=_POOL_LABELS[key],
            href=f"/accounts?pool={key}",
            active=(selected_normalized == key),
            count=int(counts.get(key, 0)),
        )
        for key in POOL_TAB_KEYS
    )


def _last_status_label(account_service: "AccountService", account_id: int) -> str:
    """从 :class:`AccountService` 取既有的 ``last_status``。

    账号在 ``accounts`` 表存在但 :class:`AccountPoolService` 还没回灌时，可能命中
    ``ValueError``；在这里吞掉异常返回空串，避免单条数据异常影响整页渲染。
    """

    try:
        account = account_service.get_account(account_id)
    except ValueError:
        return ""
    return (account.last_status or "").strip()


def _enabled_label(enabled: bool) -> tuple[bool, str]:
    return enabled, "启用中" if enabled else "已停用"


def _login_status_label(cache: "LoginStatusCache", account_id: int) -> str:
    return (
        LOGIN_STATUS_CACHE_TRACKED_LABEL
        if cache.contains(account_id)
        else LOGIN_STATUS_CACHE_MISSING_LABEL
    )


def _build_active_row(
    entry: AccountPoolEntry,
    *,
    account_service: "AccountService",
    cache: "LoginStatusCache",
) -> PoolRowView:
    enabled, enabled_label = _build_enabled_pair(account_service, entry.account_id)
    return PoolRowView(
        account_id=entry.account_id,
        student_id=entry.student_id,
        display_name=entry.display_name,
        pool=PoolStatus.ACTIVE.value,
        pool_label=_POOL_LABELS[PoolStatus.ACTIVE.value],
        enabled=enabled,
        enabled_label=enabled_label,
        last_status_label=_last_status_label(account_service, entry.account_id) or "尚未检测",
        login_status_label=_login_status_label(cache, entry.account_id),
        suspended_at_label="",
        remaining_label="",
        expires_at_label="",
        entered_at_label="",
        pool_previous_label="",
        migration_buttons=MIGRATION_TARGETS,
    )


def _build_suspended_row(
    entry: AccountPoolEntry,
    *,
    account_service: "AccountService",
    now_utc: datetime,
) -> PoolRowView:
    enabled, enabled_label = _build_enabled_pair(account_service, entry.account_id)
    return PoolRowView(
        account_id=entry.account_id,
        student_id=entry.student_id,
        display_name=entry.display_name,
        pool=PoolStatus.SUSPENDED.value,
        pool_label=_POOL_LABELS[PoolStatus.SUSPENDED.value],
        enabled=enabled,
        enabled_label=enabled_label,
        last_status_label="",
        login_status_label="",
        suspended_at_label=format_utc_to_local(entry.suspended_at),
        remaining_label=format_remaining_suspension(
            entry.suspension_expires_at, now_utc
        ),
        expires_at_label=format_utc_to_local(entry.suspension_expires_at),
        entered_at_label="",
        pool_previous_label="",
        migration_buttons=MIGRATION_TARGETS,
    )


def _build_idle_row(
    entry: AccountPoolEntry,
    *,
    account_service: "AccountService",
) -> PoolRowView:
    enabled, enabled_label = _build_enabled_pair(account_service, entry.account_id)
    pool_previous = (entry.pool_previous or "").strip()
    pool_previous_label = (
        _POOL_LABELS.get(pool_previous, pool_previous) if pool_previous else "—"
    )
    return PoolRowView(
        account_id=entry.account_id,
        student_id=entry.student_id,
        display_name=entry.display_name,
        pool=PoolStatus.IDLE.value,
        pool_label=_POOL_LABELS[PoolStatus.IDLE.value],
        enabled=enabled,
        enabled_label=enabled_label,
        last_status_label="",
        login_status_label="",
        suspended_at_label="",
        remaining_label="",
        expires_at_label="",
        entered_at_label=format_utc_to_local(entry.pool_updated_at),
        pool_previous_label=pool_previous_label,
        migration_buttons=MIGRATION_TARGETS,
    )


def _build_enabled_pair(
    account_service: "AccountService", account_id: int
) -> tuple[bool, str]:
    try:
        account = account_service.get_account(account_id)
    except ValueError:
        return _enabled_label(False)
    return _enabled_label(bool(account.enabled))


def build_pool_rows(
    entries: Iterable[AccountPoolEntry],
    *,
    pool: str,
    account_service: "AccountService",
    pool_service: "AccountPoolService",
    now_utc: datetime,
) -> list[PoolRowView]:
    """按 Tab 把 ``entries`` 转成 :class:`PoolRowView` 列表。

    * ``pool='active'``：注入 ``Login_Status_Cache.contains`` 状态。
    * ``pool='suspended'``：使用 ``now_utc`` 计算剩余时长。
    * ``pool='idle'``：渲染 ``pool_updated_at`` 与 ``pool_previous``。
    """

    cache = pool_service.login_status_cache
    rows: list[PoolRowView] = []
    for entry in entries:
        if pool == PoolStatus.ACTIVE.value:
            rows.append(
                _build_active_row(
                    entry, account_service=account_service, cache=cache
                )
            )
        elif pool == PoolStatus.SUSPENDED.value:
            rows.append(
                _build_suspended_row(
                    entry, account_service=account_service, now_utc=now_utc
                )
            )
        elif pool == PoolStatus.IDLE.value:
            rows.append(
                _build_idle_row(entry, account_service=account_service)
            )
    return rows


def build_pool_view_context(
    *,
    pool: str,
    pool_service: "AccountPoolService",
    account_service: "AccountService",
    now_utc: datetime | None = None,
) -> dict[str, object]:
    """一次性装配三池 Tab + 当前选中池行集 + 总数。

    模板拿到这个 context 后只需要：

    * 渲染 :data:`pool_tabs` 顶部 Tab 区。
    * 按 :data:`pool_rows` 列出当前 Tab 的账号行。
    * 选中 Tab 的 ``empty_label`` 控制空态文案。
    """

    selected = normalize_pool_tab(pool)
    now = now_utc or datetime.now(tz=UTC)
    counts: dict[str, int] = {}
    rows: list[PoolRowView] = []
    for key in POOL_TAB_KEYS:
        status = PoolStatus(key)
        entries = pool_service.list_by_pool(status)
        counts[key] = len(entries)
        if key == selected:
            rows = build_pool_rows(
                entries,
                pool=key,
                account_service=account_service,
                pool_service=pool_service,
                now_utc=now,
            )
    tabs = build_pool_tabs(selected=selected, counts=counts)
    return {
        "selected_pool": selected,
        "pool_tabs": tabs,
        "pool_rows": rows,
        "pool_counts": counts,
        "pool_empty_label": _empty_label(selected),
        "migration_targets": MIGRATION_TARGETS,
    }


def _empty_label(pool: str) -> str:
    if pool == PoolStatus.ACTIVE.value:
        return "活跃池暂无账号"
    if pool == PoolStatus.SUSPENDED.value:
        return "拉黑号池暂无账号"
    return "未启用池暂无账号"


__all__ = [
    "DEFAULT_POOL_TAB",
    "LOGIN_STATUS_CACHE_MISSING_LABEL",
    "LOGIN_STATUS_CACHE_TRACKED_LABEL",
    "MIGRATION_TARGETS",
    "POOL_TAB_KEYS",
    "PoolRowView",
    "PoolTab",
    "build_pool_rows",
    "build_pool_tabs",
    "build_pool_view_context",
    "format_remaining_suspension",
    "format_utc_to_local",
    "normalize_pool_tab",
]
