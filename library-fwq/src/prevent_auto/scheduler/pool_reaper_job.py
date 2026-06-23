"""Pool_Reaper_Job：Suspended_Pool 7 天到期自动回收。

本模块对应 spec ``account-pool-tri-sync`` 的 task 6.1，提供：

* :class:`PoolReaperJob`：每 :data:`INTERVAL_SECONDS` 秒扫描一次 ``accounts`` 表，
  把所有 ``pool_status='suspended' AND suspension_expires_at <= now_utc AND
  deleted_at IS NULL`` 的账号迁移到 Idle_Pool。每条迁移由
  :class:`AccountPoolService.migrate` 完成（内部已写一条 ``audit_action='migrate'``
  审计与清理 Login_Status_Cache）。
* 每次 tick 末尾，无论本次是否处理到账号都向 ``pool_audit_log`` 追加一条
  ``audit_action='reaper_tick'`` 审计行；扫描阶段抛 DB 异常时，写一条
  ``success=0`` 的 ``reaper_tick`` 行，让下一次 tick 自然重试。
* :data:`INTERVAL_SECONDS = 300`、:data:`MAX_DRIFT_SECONDS = 300`；调用方在 lifespan
  装配时按 ``ACCOUNT_POOL_REAPER_INTERVAL_SECONDS`` 注入 ``interval_seconds`` 即可。

设计要点（与 design 数据流 3、Requirement 2.6 / 3.3 / 3.4 / 3.5 对齐）：

* ``clock`` 注入便于测试；默认 ``lambda: datetime.now(tz=UTC)``。
* ``run_forever`` 在 ``stop_event`` 触发后立即退出，不再触发新一轮 tick；启动后
  立即执行首次 tick，自然处理服务停机期间累积的到期项。
* SQLite 是阻塞 I/O，``_tick`` 把同步实现委托给 ``asyncio.to_thread``，避免堵塞
  事件循环。
* 单条迁移失败（例如并发删除导致 ``AccountNotInActivePool``）只记录警告，不打断
  本轮其它账号的处理；至少把 ``reaper_tick`` 审计行落库以保证审计完备。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from prevent_auto.account_pool.models import (
    AccountNotInActivePool,
    AccountPoolError,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogEntry,
    PoolAuditLogRepository,
)
from prevent_auto.services.account_pool_service import AccountPoolService


logger = logging.getLogger(__name__)


INTERVAL_SECONDS = 300
"""默认扫描周期（秒）。对应 Requirement 3-Q3 默认值「每 5 分钟扫描一次」。"""

MAX_DRIFT_SECONDS = 300
"""单次 tick 允许的最大漂移（秒）。Requirement 3-Q3：到期账号最迟在到期后 5
分钟内被回收，因此扫描周期与最大漂移取同值。"""


@dataclass(frozen=True)
class ReaperTickResult:
    """单次 tick 的执行结果。

    既用于单元测试断言、也方便 lifespan 层把 ``processed_count`` /
    ``failed_account_ids`` 暴露为运维指标。``error`` 非空表示扫描阶段抛了 DB
    级异常，本轮没能完成 SELECT；``success`` 为 ``False`` 时仍会向审计表追加
    一条 ``reaper_tick`` 行，便于下一轮自然重试。
    """

    now_utc: datetime
    processed_count: int = 0
    failed_account_ids: tuple[int, ...] = field(default_factory=tuple)
    error: str = ""

    @property
    def success(self) -> bool:
        return not self.error and not self.failed_account_ids


class PoolReaperJob:
    """Suspended_Pool 到期回收的后台任务。

    构造参数（全部 keyword-only，与 :class:`prevent_auto.scheduler.daily_status_refresher.DailyStatusRefresher`
    保持一致）：

    * ``account_pool_repo`` / ``audit_repo``：直接操作的两个 repository；
      ``account_pool_repo`` 用于扫描到期 suspended 账号，``audit_repo`` 用于追加
      ``reaper_tick`` 审计行。
    * ``account_pool_service``：复用 :class:`AccountPoolService.migrate` 完成单条
      迁移（同事务写入 ``migrate`` 审计与清理 Login_Status_Cache）。
    * ``clock``：可注入的 ``Callable[[], datetime]``，必须返回带时区的 UTC 时间。
      默认 ``lambda: datetime.now(tz=UTC)``。
    * ``interval_seconds``：可选扫描周期；缺省取 :data:`INTERVAL_SECONDS`。lifespan
      层按 ``ACCOUNT_POOL_REAPER_INTERVAL_SECONDS`` 注入即可。
    """

    INTERVAL_SECONDS = INTERVAL_SECONDS
    MAX_DRIFT_SECONDS = MAX_DRIFT_SECONDS

    def __init__(
        self,
        *,
        account_pool_repo: AccountPoolRepository,
        audit_repo: PoolAuditLogRepository,
        account_pool_service: AccountPoolService,
        clock: Callable[[], datetime] | None = None,
        interval_seconds: int | None = None,
    ) -> None:
        self._account_pool_repo = account_pool_repo
        self._audit_repo = audit_repo
        self._service = account_pool_service
        self._clock: Callable[[], datetime] = clock or (
            lambda: datetime.now(tz=UTC)
        )
        if interval_seconds is None:
            self.interval_seconds = INTERVAL_SECONDS
        else:
            if interval_seconds <= 0:
                raise ValueError("interval_seconds 必须大于 0")
            self.interval_seconds = int(interval_seconds)

    # --------------------------- 调度循环 ---------------------------

    async def run_forever(self, stop_event: asyncio.Event) -> None:
        """循环执行 :meth:`_tick`，直至 ``stop_event`` 被设置。

        启动后立即触发首次 tick（Requirement 3.5：服务恢复后的首次扫描自然
        补处理停机期间累积的到期项）。两次 tick 之间用 ``stop_event.wait()``
        + 超时阻塞，便于上层在 shutdown 时立即唤醒并退出。
        """

        while not stop_event.is_set():
            try:
                await self._tick()
            except Exception:  # noqa: BLE001
                # `_tick` 内部已经把扫描 / 迁移异常翻译成 success=0 的审计行；
                # 这里再兜底一次，防止极端情况（例如审计写入也异常）打挂整个
                # 后台循环。日志保留堆栈，便于运维排查。
                logger.exception("PoolReaperJob tick 出现未捕获异常")

            if stop_event.is_set():
                break
            try:
                await asyncio.wait_for(
                    stop_event.wait(), timeout=self.interval_seconds
                )
            except asyncio.TimeoutError:
                continue

    async def _tick(self) -> ReaperTickResult:
        """单次 tick 的异步入口。

        SQLite 是阻塞 I/O，把同步实现委托给 ``asyncio.to_thread`` 避免堵塞
        事件循环；测试可以直接调用 :meth:`_tick_sync` 拿到结构化结果。
        """

        return await asyncio.to_thread(self._tick_sync)

    # --------------------------- 同步实现 ---------------------------

    def _tick_sync(self) -> ReaperTickResult:
        """单次扫描 + 迁移的同步实现，返回结构化结果。

        步骤：

        1. 用注入的 ``clock`` 取一次 ``now_utc``；本 tick 全程沿用同一时间。
        2. 扫描 ``pool_status='suspended' AND suspension_expires_at <= now_utc
           AND deleted_at IS NULL`` 的账号。扫描失败 → 写一条 ``success=0`` 的
           ``reaper_tick`` 审计行后返回。
        3. 逐条调用 ``service.migrate(... PoolStatus.IDLE, trigger_source=EXPIRE)``。
           ``AccountNotInActivePool`` 视为「已被并发迁移 / 软删」，跳过；其它
           业务异常 / 未知异常计入 ``failed_account_ids``，不打断剩余账号。
        4. 末尾追加一条 ``audit_action='reaper_tick'`` 审计行；本轮全部成功
           ``success=1``，存在失败计为 ``success=0``。
        """

        now_utc = self._now_utc()
        processed_count = 0
        failed_account_ids: list[int] = []

        try:
            expired_ids = self._scan_expired_account_ids(now_utc)
        except Exception as exc:  # noqa: BLE001
            logger.exception("PoolReaperJob 扫描数据库失败")
            self._append_tick_audit(
                now_utc=now_utc,
                processed_count=0,
                failed_account_ids=(),
                success=False,
                reason=type(exc).__name__,
            )
            return ReaperTickResult(
                now_utc=now_utc,
                processed_count=0,
                failed_account_ids=(),
                error=type(exc).__name__,
            )

        for account_id in expired_ids:
            try:
                self._service.migrate(
                    account_id,
                    PoolStatus.IDLE,
                    operator="system",
                    trigger_source=PoolMigrationTrigger.EXPIRE,
                )
                processed_count += 1
            except AccountNotInActivePool:
                # 账号已被并发迁移 / 软删，无需重试；不计入 failed_account_ids。
                logger.debug(
                    "PoolReaperJob 跳过已被并发迁移的账号 account_id=%s",
                    account_id,
                )
                continue
            except AccountPoolError as exc:
                logger.warning(
                    "PoolReaperJob 迁移失败 account_id=%s reason=%s",
                    account_id,
                    type(exc).__name__,
                )
                failed_account_ids.append(account_id)
            except Exception:  # noqa: BLE001
                logger.exception(
                    "PoolReaperJob 迁移异常 account_id=%s", account_id
                )
                failed_account_ids.append(account_id)

        success = not failed_account_ids
        self._append_tick_audit(
            now_utc=now_utc,
            processed_count=processed_count,
            failed_account_ids=tuple(failed_account_ids),
            success=success,
            reason="" if success else "partial_failure",
        )
        return ReaperTickResult(
            now_utc=now_utc,
            processed_count=processed_count,
            failed_account_ids=tuple(failed_account_ids),
        )

    # --------------------------- 私有工具 ---------------------------

    def _scan_expired_account_ids(self, now_utc: datetime) -> list[int]:
        """返回所有满足回收条件的 ``account_id`` 列表。

        通过 :meth:`AccountPoolRepository.list_by_pool` 拿到全部 suspended 账号，
        再按 ``suspension_expires_at <= now_utc`` 过滤；规模受 Pool_Capacity = 100
        上限约束，不会出现性能问题，因此无需新增 repository 方法。``include_deleted``
        默认 ``False``，与 task 描述「``deleted_at IS NULL``」一致。
        """

        suspended = self._account_pool_repo.list_by_pool(PoolStatus.SUSPENDED)
        ids: list[int] = []
        for entry in suspended:
            expires_at = entry.suspension_expires_at
            if expires_at is None:
                # 数据完整性兜底：suspended 状态下两字段必同时非空，但本扫描
                # 仍以 ``suspension_expires_at IS NULL`` 视为不可回收。
                continue
            if expires_at <= now_utc:
                ids.append(entry.account_id)
        return ids

    def _append_tick_audit(
        self,
        *,
        now_utc: datetime,
        processed_count: int,
        failed_account_ids: tuple[int, ...],
        success: bool,
        reason: str,
    ) -> None:
        """统一封装 ``reaper_tick`` 审计行的字段拼装与写入异常兜底。

        审计写入失败（例如数据库本身就不可用）时只记录日志，不再抛异常打断
        循环；下一轮 tick 会自然重试，与 Requirement 3.5「补处理累积到期项」
        语义一致。
        """

        payload: dict[str, Any] = {
            "now_utc": _format_utc(now_utc),
            "processed_count": processed_count,
            "failed_account_ids": list(failed_account_ids),
        }
        try:
            self._audit_repo.append(
                PoolAuditLogEntry(
                    audit_action=PoolAuditAction.REAPER_TICK,
                    trigger_source=PoolMigrationTrigger.SYSTEM,
                    operator="system",
                    success=success,
                    reason=reason,
                    payload=payload,
                    created_at=now_utc,
                )
            )
        except Exception:  # noqa: BLE001
            logger.exception("PoolReaperJob 写入 reaper_tick 审计失败")

    def _now_utc(self) -> datetime:
        """读取注入的 ``clock`` 并强制规范化到 UTC aware datetime。

        与 :class:`AccountPoolService._now_utc` 同口径：禁止 naive datetime，
        非 datetime 入参直接抛 ``TypeError``，避免 silent 误用。
        """

        value = self._clock()
        if not isinstance(value, datetime):
            raise TypeError("clock 返回值必须是 datetime")
        if value.tzinfo is None:
            raise ValueError("clock 返回值必须带时区，禁止 naive datetime")
        return value.astimezone(UTC)


def _format_utc(value: datetime) -> str:
    """把 UTC aware datetime 序列化为 ``...Z`` 后缀的 ISO8601 文本。

    与 :mod:`prevent_auto.repositories.pool_audit_log` 的同名工具保持一致；本
    模块不复用 repository 私有函数，避免跨模块隐式依赖。``microsecond`` 截断到 0
    便于审计文本检索。
    """

    if value.tzinfo is None:
        raise ValueError("datetime 必须带时区，禁止使用 naive datetime")
    aware = value.astimezone(UTC).replace(microsecond=0)
    return aware.isoformat().replace("+00:00", "Z")


__all__ = [
    "INTERVAL_SECONDS",
    "MAX_DRIFT_SECONDS",
    "PoolReaperJob",
    "ReaperTickResult",
]
