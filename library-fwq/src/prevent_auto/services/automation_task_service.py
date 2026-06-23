"""Automation_Task_Service：自动任务双向同步领域服务。

本模块对应 spec ``account-pool-tri-sync`` 的 task 5.1，提供：

* :meth:`AutomationTaskService.list_for_active_account`：仅当 ``account_id`` 处于
  Active_Pool 且未软删时返回该账号的非软删 Automation_Task 列表；非活跃 / 不存在
  / 软删账号一律抛 :class:`AccountNotInActivePool`，由 REST 层翻译成
  ``404 {"detail":"account not found"}``，与接口 B 字节级一致以避免泄露状态
  （Requirement 6.5）。
* :meth:`AutomationTaskService.upsert`：以 ``expected_revision`` 做乐观并发的 PUT
  upsert；持久化与「成功」审计落库共用同一事务。
* :meth:`AutomationTaskService.soft_delete`：以 ``expected_revision`` 做乐观并发
  的软删；持久化与「成功」审计落库共用同一事务。
* :meth:`AutomationTaskService.set_enabled_for_active_tasks`：供服务端 Web 批量启停
  当前活跃池里的自动任务，并通过 revision 变化让客户端感知。

设计约束（对齐 Requirements 8.1、8.3、8.4、8.5、8.6、8.7、10.4）：

* 本服务只负责 Automation_Task 的持久化与审计；服务端主动补约由独立自动预约服务读取
  已启用任务后执行，避免把同步写入路径和执行路径混在一起。
* 字段校验仅做非空与类型边界（``room_name`` 非空且长度 ≤ 64、``seat_number`` 非空且
  长度 ≤ 32、``mode ∈ {preferred, manual, random}``、``custom_windows[].start_hour
  / end_hour ∈ [0, 23]`` 且 ``end_hour > start_hour``、``enabled`` 是 ``bool``），
  不做业务校验（自习室是否存在、座位号是否合法）—— 与 design 8-Q4 默认值一致。
* 字段校验失败 / 账号不在活跃池 / ``revision`` 冲突等「持久化前就失败」的路径，
  仅写一条 ``audit_action='task_upload_rejected'`` 审计行（独立短事务，不动
  ``automation_tasks`` 表）；持久化阶段失败（数据库异常等）则回滚审计与数据，不
  允许出现「日志说成功但数据没落库」的中间态（Requirement 8.5）。
* 状态读写复用现有 :class:`AccountPoolRepository` 与
  :class:`AutomationTasksRepository`；审计走 :class:`PoolAuditLogRepository`。
* 通过 :func:`prevent_auto.database.connect_database` 取连接后显式 ``BEGIN`` /
  ``COMMIT`` / ``ROLLBACK``，与 :class:`AccountPoolService` 完全一致的事务管理风格。
"""

from __future__ import annotations

import logging
import sqlite3
from collections.abc import Callable, Iterable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

from prevent_auto.account_pool.models import (
    AccountNotInActivePool,
    AccountPoolError,
    AutomationTask,
    ClientKind,
    CustomWindow,
    PoolMigrationTrigger,
    PoolStatus,
    RevisionConflict,
)
from prevent_auto.database import connect_database
from prevent_auto.models import BookingSnapshot
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.booking_snapshots import BookingSnapshotsRepository
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogEntry,
    PoolAuditLogRepository,
)


# Asia/Shanghai 时区，用于把 ``BookingSnapshot.start_time`` 转换成本地小时段。
# 直接用固定 UTC+8 偏移，避开 Windows 上 ZoneInfo 依赖 ``tzdata`` 模块的问题；
# 与 :data:`wuyi_seat_bot.seat_api.SHANGHAI_TZ` 完全等价。
from datetime import timezone

_SHANGHAI_TZ = timezone(timedelta(hours=8))

# 与 booking_status_service.PROTECTED_BOOKING_STATUSES 一致：未取消、未签退的预约。
# 重新本地定义而非 import，是为了避免 services/automation_task_service 反向依赖
# booking_status_service（自动任务模块本来就和预约状态判定相互独立）。
_PROTECTED_BOOKING_STATUSES: frozenset[str] = frozenset({"0", "1", "2", "8"})


# 合法 mode 取值集合。design「8-Q4 默认」不在数据库层做枚举，由服务层校验。
_LEGAL_MODES: frozenset[str] = frozenset({"preferred", "manual", "random"})

# 字段长度上限（design「Components and Interfaces · 字段校验」）。
_MAX_ROOM_NAME_LENGTH = 64
_MAX_SEAT_NUMBER_LENGTH = 32

# custom_windows 的小时取值范围。
_MIN_HOUR = 0
_MAX_HOUR = 23


# ----------------------------- 入参 / 错误 -----------------------------


@dataclass(frozen=True)
class AutomationTaskUpsertPayload:
    """``AutomationTaskService.upsert`` 的入参 DTO。

    REST 层（task 8.3）会从 Pydantic 模型构造此对象后传入服务层；服务层再做非空与
    类型边界校验。``frozen=True`` 防止在校验后被原地修改。

    字段语义：

    * ``room_name`` / ``seat_number``：自习室与座位标识，长度上限分别为 64 / 32。
    * ``mode``：执行模式，只接受 ``preferred`` / ``manual`` / ``random`` 三个字面量。
    * ``custom_windows``：自定义生效时段元组；每条 ``CustomWindow`` 的 ``start_hour``
      与 ``end_hour`` 必须在 ``[0, 23]`` 且 ``end_hour > start_hour``。
    * ``enabled``：是否启用，必须严格是 ``bool``（``isinstance(x, bool)`` True）。
    """

    room_name: str
    seat_number: str
    mode: str
    custom_windows: tuple[CustomWindow, ...]
    enabled: bool


@dataclass(frozen=True)
class FieldError:
    """单字段校验失败信息。

    REST 层会按 ``[{"field": ..., "message": ...}, ...]`` 的格式回显给客户端，便于
    Compose / Web UI 直接绑定到字段。
    """

    field: str
    message: str


class AutomationTaskValidationError(AccountPoolError):
    """``upsert`` 字段校验失败。

    携带逐字段的失败原因；REST 层映射为
    ``400 {"reason":"validation_error","errors":[{"field":...,"message":...}]}``。
    """

    def __init__(self, errors: Iterable[FieldError]) -> None:
        self.errors: list[FieldError] = list(errors)
        super().__init__(
            f"automation task validation failed: {len(self.errors)} field(s)"
        )


@dataclass(frozen=True)
class AutomationTaskBulkEnabledResult:
    """服务端 Web 批量启停自动任务的汇总结果。"""

    matched_count: int
    changed_count: int
    unchanged_count: int


@dataclass(frozen=True)
class TaskBootstrapItem:
    """单账号「按当前预约生成任务」的处理结果。"""

    account_id: int
    student_id: str
    created_count: int
    updated_count: int
    skipped_count: int
    skip_reasons: tuple[str, ...]
    error: str = ""


@dataclass(frozen=True)
class TaskBootstrapResult:
    """批量「按当前预约生成任务」的整体汇总。"""

    total_accounts: int
    success_accounts: int
    failed_accounts: int
    skipped_accounts: int
    created_count: int
    updated_count: int
    items: tuple[TaskBootstrapItem, ...]

    def to_notice(self) -> str:
        return (
            f"按预约生成任务：共 {self.total_accounts} 个账号，"
            f"新建 {self.created_count} 条 / 合并 {self.updated_count} 条；"
            f"成功 {self.success_accounts} 个，"
            f"跳过 {self.skipped_accounts} 个，失败 {self.failed_accounts} 个"
        )


# ----------------------------- 服务实现 -----------------------------


class AutomationTaskService:
    """自动任务的服务层，负责字段校验、活跃池校验、乐观并发与同事务审计。

    构造参数：

    * ``database_path``：SQLite 数据库文件路径，用于服务自管理事务。
    * ``automation_tasks_repo`` / ``account_pool_repo`` / ``audit_repo``：三类
      仓库，由调用方装配；测试可以注入指向同一 ``database_path`` 的真实仓库或
      mock 实现。
    * ``clock``：可选的 ``Callable[[], datetime]``，仅用于在审计行上写带时区的
      ``created_at``；缺省 ``lambda: datetime.now(tz=UTC)``。
    """

    def __init__(
        self,
        database_path: str | Path,
        *,
        automation_tasks_repo: AutomationTasksRepository,
        account_pool_repo: AccountPoolRepository,
        audit_repo: PoolAuditLogRepository,
        booking_snapshots_repo: BookingSnapshotsRepository | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self.database_path = Path(database_path)
        self._automation_tasks_repo = automation_tasks_repo
        self._account_pool_repo = account_pool_repo
        self._audit_repo = audit_repo
        self._booking_snapshots_repo = booking_snapshots_repo
        self._clock: Callable[[], datetime] = clock or (lambda: datetime.now(tz=UTC))

    # ------------------------------ 读 ------------------------------

    def list_for_active_account(self, account_id: int) -> list[AutomationTask]:
        """列出 ``account_id`` 在 Active_Pool 时的非软删 Automation_Task。

        非活跃 / 不存在 / 软删账号一律抛 :class:`AccountNotInActivePool`，由 REST
        层统一翻译成 ``404 {"detail":"account not found"}``。读路径不写审计（与
        Requirement 10 只覆盖写路径一致），避免 404 探测拉爆审计表。
        """

        with connect_database(self.database_path) as conn:
            self._ensure_active_or_raise(account_id, connection=conn)
            return self._automation_tasks_repo.list_for_account(
                account_id, connection=conn
            )

    # ------------------------------ 写 ------------------------------

    def upsert(
        self,
        account_id: int,
        task_id: int | None,
        payload: AutomationTaskUpsertPayload,
        expected_revision: int,
        *,
        operator: str,
        client_kind: ClientKind,
    ) -> AutomationTask:
        """以乐观并发与同事务审计 PUT 一条 Automation_Task。

        参数语义：

        * ``task_id``：``None`` 表示新建（由 ``AUTOINCREMENT`` 分配），``int`` 表示
          按既定 ID upsert。``int`` 路径下 ``expected_revision == 0`` 表示客户端
          认为目标行尚不存在；其他取值表示客户端持有的服务端版本。
        * ``payload``：通过 :class:`AutomationTaskUpsertPayload` 传入；服务层做
          非空与类型边界校验，校验失败抛 :class:`AutomationTaskValidationError`。
        * ``operator`` / ``client_kind``：审计行的「谁」与「什么端」字段；通常由
          REST 层把 token 标签与 ``Authorization`` 解析结果填入。

        校验顺序（与审计行的关系）：

        1. 字段校验失败 → :class:`AutomationTaskValidationError`，独立写一条
           ``audit_action='task_upload_rejected'`` 审计行（独立事务），不动
           ``automation_tasks`` 表。
        2. 账号不在活跃池 → :class:`AccountNotInActivePool`，独立写一条
           ``audit_action='task_upload_rejected'`` 审计行。
        3. ``expected_revision`` 与服务端不一致 → :class:`RevisionConflict`，
           独立写一条 ``audit_action='task_upload_rejected'`` 审计行，``payload``
           含 ``expected_revision`` 与 ``server_revision``。
        4. 上述都通过：在单事务内完成「写 ``automation_tasks`` + 写
           ``task_upload`` 审计」。如果数据库写入因系统原因失败（磁盘 / 锁等），
           ``COMMIT`` 失败，整个事务回滚，审计与数据一起消失。
        """

        # 阶段 1：字段校验（无 DB）
        try:
            self._validate_upsert_payload(payload)
        except AutomationTaskValidationError as exc:
            self._append_rejection_audit(
                account_id=account_id,
                task_id=task_id,
                operator=operator,
                client_kind=client_kind,
                reason="validation_error",
                payload={
                    "errors": [
                        {"field": err.field, "message": err.message}
                        for err in exc.errors
                    ],
                },
            )
            raise

        # 阶段 2：活跃池校验 + 乐观并发 upsert + 成功审计，全部在单事务内完成
        try:
            with self._begin_transaction() as conn:
                self._ensure_active_or_raise(account_id, connection=conn)
                task = self._automation_tasks_repo.upsert_with_revision(
                    account_id=account_id,
                    task_id=task_id,
                    room_name=payload.room_name,
                    seat_number=payload.seat_number,
                    mode=payload.mode,
                    custom_windows=payload.custom_windows,
                    enabled=payload.enabled,
                    expected_revision=expected_revision,
                    connection=conn,
                )
                change_type = "create" if expected_revision == 0 else "update"
                self._audit_repo.append(
                    self._build_success_entry(
                        account_id=account_id,
                        task_id=task.task_id,
                        operator=operator,
                        client_kind=client_kind,
                        change_type=change_type,
                        revision=task.revision,
                        extra={"enabled": task.enabled},
                    ),
                    connection=conn,
                )
        except AccountNotInActivePool:
            # 事务已经回滚（``_begin_transaction`` 的 except 分支会 ROLLBACK）；这里
            # 在新事务里追加 rejection 审计，再把异常抛回 REST 层。
            self._append_rejection_audit(
                account_id=account_id,
                task_id=task_id,
                operator=operator,
                client_kind=client_kind,
                reason="account_not_active",
                payload={},
            )
            raise
        except RevisionConflict as exc:
            self._append_rejection_audit(
                account_id=account_id,
                task_id=task_id,
                operator=operator,
                client_kind=client_kind,
                reason="revision_conflict",
                payload={
                    "expected_revision": expected_revision,
                    "server_revision": exc.server_revision,
                },
            )
            raise

        return task

    def soft_delete(
        self,
        account_id: int,
        task_id: int,
        expected_revision: int,
        *,
        operator: str,
        client_kind: ClientKind,
    ) -> AutomationTask:
        """以乐观并发与同事务审计软删一条 Automation_Task。

        校验顺序与 :meth:`upsert` 一致，仅省略字段校验环节：

        1. 账号不在活跃池 → :class:`AccountNotInActivePool` + ``task_upload_rejected``。
        2. ``expected_revision`` 与服务端不一致（含目标行不存在）→
           :class:`RevisionConflict` + ``task_upload_rejected``。
        3. 通过：在单事务内 ``UPDATE deleted_at`` 并写 ``task_upload`` 审计
           （``change_type='delete'``）。
        """

        try:
            with self._begin_transaction() as conn:
                self._ensure_active_or_raise(account_id, connection=conn)
                task = self._automation_tasks_repo.soft_delete(
                    account_id=account_id,
                    task_id=task_id,
                    expected_revision=expected_revision,
                    connection=conn,
                )
                self._audit_repo.append(
                    self._build_success_entry(
                        account_id=account_id,
                        task_id=task.task_id,
                        operator=operator,
                        client_kind=client_kind,
                        change_type="delete",
                        revision=task.revision,
                        extra={},
                    ),
                    connection=conn,
                )
        except AccountNotInActivePool:
            self._append_rejection_audit(
                account_id=account_id,
                task_id=task_id,
                operator=operator,
                client_kind=client_kind,
                reason="account_not_active",
                payload={},
            )
            raise
        except RevisionConflict as exc:
            self._append_rejection_audit(
                account_id=account_id,
                task_id=task_id,
                operator=operator,
                client_kind=client_kind,
                reason="revision_conflict",
                payload={
                    "expected_revision": expected_revision,
                    "server_revision": exc.server_revision,
                },
            )
            raise

        return task

    def set_enabled_for_active_tasks(
        self,
        *,
        enabled: bool,
        account_id: int | None = None,
        operator: str,
        client_kind: ClientKind,
    ) -> AutomationTaskBulkEnabledResult:
        """批量打开或关闭活跃池内的自动任务。

        ``account_id`` 为空时处理全部 Active_Pool 账号；不为空时只处理该账号。每条
        发生变化的任务都复用 ``upsert_with_revision`` 写回原字段，仅切换 ``enabled``，
        并写 ``task_upload`` 审计，保证客户端下一次同步能看到 revision 变化。
        """

        if not isinstance(enabled, bool):
            raise TypeError("enabled 必须是 bool")

        matched_count = 0
        changed_count = 0
        unchanged_count = 0
        change_type = "enable" if enabled else "disable"

        logger.info(
            "automation_task.bulk_enabled.start enabled=%s account_id=%s operator=%s client_kind=%s",
            enabled,
            account_id,
            operator,
            client_kind.value if hasattr(client_kind, "value") else client_kind,
        )

        with self._begin_transaction() as conn:
            if account_id is None:
                active_entries = self._account_pool_repo.list_by_pool(
                    PoolStatus.ACTIVE,
                    connection=conn,
                )
                target_account_ids = [entry.account_id for entry in active_entries]
                logger.info(
                    "automation_task.bulk_enabled.scope=all_active active_account_count=%d account_ids=%s",
                    len(target_account_ids),
                    target_account_ids,
                )
            else:
                self._ensure_active_or_raise(account_id, connection=conn)
                target_account_ids = [account_id]
                logger.info(
                    "automation_task.bulk_enabled.scope=single account_id=%s",
                    account_id,
                )

            for target_account_id in target_account_ids:
                tasks = self._automation_tasks_repo.list_for_account(
                    target_account_id,
                    connection=conn,
                )
                logger.info(
                    "automation_task.bulk_enabled.account account_id=%s task_count=%d task_ids=%s",
                    target_account_id,
                    len(tasks),
                    [task.task_id for task in tasks],
                )
                matched_count += len(tasks)
                for task in tasks:
                    if task.enabled is enabled:
                        unchanged_count += 1
                        logger.debug(
                            "automation_task.bulk_enabled.skip_unchanged account_id=%s task_id=%s enabled=%s",
                            target_account_id,
                            task.task_id,
                            task.enabled,
                        )
                        continue
                    updated = self._automation_tasks_repo.upsert_with_revision(
                        account_id=target_account_id,
                        task_id=task.task_id,
                        room_name=task.room_name,
                        seat_number=task.seat_number,
                        mode=task.mode,
                        custom_windows=task.custom_windows,
                        enabled=enabled,
                        expected_revision=task.revision,
                        connection=conn,
                    )
                    self._audit_repo.append(
                        self._build_success_entry(
                            account_id=target_account_id,
                            task_id=updated.task_id,
                            operator=operator,
                            client_kind=client_kind,
                            change_type=change_type,
                            revision=updated.revision,
                            extra={"enabled": updated.enabled},
                        ),
                        connection=conn,
                    )
                    changed_count += 1
                    logger.info(
                        "automation_task.bulk_enabled.changed account_id=%s task_id=%s revision=%d enabled=%s",
                        target_account_id,
                        updated.task_id,
                        updated.revision,
                        updated.enabled,
                    )

        logger.info(
            "automation_task.bulk_enabled.done matched=%d changed=%d unchanged=%d",
            matched_count,
            changed_count,
            unchanged_count,
        )

        return AutomationTaskBulkEnabledResult(
            matched_count=matched_count,
            changed_count=changed_count,
            unchanged_count=unchanged_count,
        )

    def bootstrap_tasks_from_bookings(
        self,
        account_ids: Iterable[int],
        *,
        bookings_provider: Callable[[int], Iterable[BookingSnapshot]] | None = None,
        operator: str,
        client_kind: ClientKind,
        skip_accounts_with_existing_tasks: bool = True,
    ) -> TaskBootstrapResult:
        """根据账号当前预约批量创建或合并 Automation_Task。

        每条 ``BookingSnapshot``（仅 ``status`` 在 ``_PROTECTED_BOOKING_STATUSES``
        里的活动预约）按 ``(room_name, seat_number)`` 维度生成或合并任务：

        * 同一账号下已有 ``(room_name, seat_number)`` 任务：把预约推出的
          ``(start_hour, end_hour)`` 时段合并进 ``custom_windows``；时段已经存在的
          跳过。``mode`` / ``enabled`` 保留任务原值。
        * 没有同 ``(room_name, seat_number)`` 任务：新建一条，``mode='preferred'``、
          ``enabled=True``、``custom_windows`` 仅含本次推出的时段，``date`` 写预约
          当天日期（滚动模式不读 date 但字段必填）。
        * 跨午夜的预约直接跳过，记入 ``skip_reasons``；spec 不接受 ``end_hour > 23``。

        ``skip_accounts_with_existing_tasks=True`` 时（默认值），当账号已经有任意
        非软删任务就整体跳过该账号——配合 UI 的「自动排除已有自动预约的账号」
        勾选框。调用方可以传 ``False`` 来强制为所有账号都生成。

        ``bookings_provider`` 用于在调用方已经有预约数据时直接传入；缺省走构造时
        注入的 ``booking_snapshots_repo``。两者都没有时抛 :class:`RuntimeError`，
        提示调用方装配链路有问题。
        """

        if bookings_provider is None and self._booking_snapshots_repo is None:
            raise RuntimeError(
                "未配置 booking_snapshots_repo，无法 bootstrap：请改用 bookings_provider 注入"
            )

        ids = [int(account_id) for account_id in account_ids]
        ids = list(dict.fromkeys(ids))  # 去重保持顺序

        logger.info(
            "automation_task.bootstrap.start account_count=%d account_ids=%s "
            "skip_accounts_with_existing_tasks=%s operator=%s client_kind=%s",
            len(ids),
            ids,
            skip_accounts_with_existing_tasks,
            operator,
            client_kind.value if hasattr(client_kind, "value") else client_kind,
        )

        # 一次性把所有账号的快照拉出来（仓库里已有的批量读接口），避免 N 次 SQL。
        snapshots_by_account: dict[int, list[BookingSnapshot]] = {}
        if bookings_provider is not None:
            for account_id in ids:
                snapshots_by_account[account_id] = list(bookings_provider(account_id))
        else:
            assert self._booking_snapshots_repo is not None
            snapshots_by_account = self._booking_snapshots_repo.list_by_account_ids(ids)

        items: list[TaskBootstrapItem] = []
        success_accounts = 0
        skipped_accounts = 0
        failed_accounts = 0
        total_created = 0
        total_updated = 0

        for account_id in ids:
            try:
                entry = self._account_pool_repo.get_by_id(account_id)
            except Exception as exc:  # noqa: BLE001
                items.append(
                    TaskBootstrapItem(
                        account_id=account_id,
                        student_id="",
                        created_count=0,
                        updated_count=0,
                        skipped_count=0,
                        skip_reasons=(),
                        error=f"读取账号失败：{exc}",
                    )
                )
                failed_accounts += 1
                continue

            if entry is None or entry.pool_status is not PoolStatus.ACTIVE:
                items.append(
                    TaskBootstrapItem(
                        account_id=account_id,
                        student_id="" if entry is None else entry.student_id,
                        created_count=0,
                        updated_count=0,
                        skipped_count=0,
                        skip_reasons=("账号不在活跃池",),
                    )
                )
                skipped_accounts += 1
                continue

            existing_tasks = self._automation_tasks_repo.list_for_account(account_id)
            if skip_accounts_with_existing_tasks and existing_tasks:
                items.append(
                    TaskBootstrapItem(
                        account_id=account_id,
                        student_id=entry.student_id,
                        created_count=0,
                        updated_count=0,
                        skipped_count=0,
                        skip_reasons=("账号已有自动任务，按设置跳过",),
                    )
                )
                skipped_accounts += 1
                continue

            bookings = snapshots_by_account.get(account_id, [])
            active_bookings = [
                snapshot
                for snapshot in bookings
                if snapshot.status in _PROTECTED_BOOKING_STATUSES
            ]
            if not active_bookings:
                items.append(
                    TaskBootstrapItem(
                        account_id=account_id,
                        student_id=entry.student_id,
                        created_count=0,
                        updated_count=0,
                        skipped_count=0,
                        skip_reasons=("当前没有可用作模板的预约",),
                    )
                )
                skipped_accounts += 1
                continue

            try:
                created, updated, skip_reasons = self._bootstrap_for_account(
                    account_id=account_id,
                    bookings=active_bookings,
                    existing_tasks=existing_tasks,
                    operator=operator,
                    client_kind=client_kind,
                )
            except AccountPoolError as exc:
                items.append(
                    TaskBootstrapItem(
                        account_id=account_id,
                        student_id=entry.student_id,
                        created_count=0,
                        updated_count=0,
                        skipped_count=0,
                        skip_reasons=(),
                        error=str(exc) or exc.__class__.__name__,
                    )
                )
                failed_accounts += 1
                continue

            if created == 0 and updated == 0:
                if not skip_reasons:
                    skip_reasons = ("预约对应的时段已经全部覆盖",)
                items.append(
                    TaskBootstrapItem(
                        account_id=account_id,
                        student_id=entry.student_id,
                        created_count=0,
                        updated_count=0,
                        skipped_count=len(skip_reasons),
                        skip_reasons=tuple(skip_reasons),
                    )
                )
                skipped_accounts += 1
                continue

            items.append(
                TaskBootstrapItem(
                    account_id=account_id,
                    student_id=entry.student_id,
                    created_count=created,
                    updated_count=updated,
                    skipped_count=len(skip_reasons),
                    skip_reasons=tuple(skip_reasons),
                )
            )
            success_accounts += 1
            total_created += created
            total_updated += updated

        logger.info(
            "automation_task.bootstrap.done total=%d success=%d failed=%d "
            "skipped=%d created=%d updated=%d",
            len(ids),
            success_accounts,
            failed_accounts,
            skipped_accounts,
            total_created,
            total_updated,
        )

        return TaskBootstrapResult(
            total_accounts=len(ids),
            success_accounts=success_accounts,
            failed_accounts=failed_accounts,
            skipped_accounts=skipped_accounts,
            created_count=total_created,
            updated_count=total_updated,
            items=tuple(items),
        )

    def _bootstrap_for_account(
        self,
        *,
        account_id: int,
        bookings: list[BookingSnapshot],
        existing_tasks: list[AutomationTask],
        operator: str,
        client_kind: ClientKind,
    ) -> tuple[int, int, list[str]]:
        """对单个账号按预约创建 / 合并任务，返回 ``(created, updated, skip_reasons)``。

        每个 ``(room_name, seat_number)`` 走一次 ``upsert_with_revision``：

        * 已存在的任务：用合并后的 ``custom_windows`` 调一次 PUT，``revision``
          自然 +1；这条逻辑包括复活之前软删的任务。
        * 不存在的任务：``task_id=None`` + ``expected_revision=0`` 让仓库分配新 ID。

        预约对应的时段如果已经在任务的 ``custom_windows`` 中存在（按
        ``(start_hour, end_hour)`` 比较），不会触发 PUT；返回值里 ``updated`` 为 0、
        ``skip_reasons`` 累加描述。
        """

        # 把预约按 (room, seat) 分桶，每桶维护出现过的所有 (start_hour, end_hour) 模板。
        booking_buckets: dict[
            tuple[str, str], list[tuple[CustomWindow, BookingSnapshot]]
        ] = {}
        skip_reasons: list[str] = []

        for snapshot in bookings:
            window = self._booking_to_window(snapshot)
            if window is None:
                skip_reasons.append(
                    f"跨日预约 {snapshot.room_name} {snapshot.seat_number} 已跳过"
                )
                continue
            key = (snapshot.room_name.strip(), snapshot.seat_number.strip())
            booking_buckets.setdefault(key, []).append((window, snapshot))

        existing_by_key: dict[tuple[str, str], AutomationTask] = {
            (task.room_name.strip(), task.seat_number.strip()): task
            for task in existing_tasks
        }

        created = 0
        updated = 0

        for (room_name, seat_number), entries in booking_buckets.items():
            new_windows: list[CustomWindow] = []
            seen_hours: set[tuple[int, int]] = set()
            for window, _snapshot in entries:
                hour_key = (window.start_hour, window.end_hour)
                if hour_key in seen_hours:
                    continue
                seen_hours.add(hour_key)
                new_windows.append(window)

            existing_task = existing_by_key.get((room_name, seat_number))
            if existing_task is None:
                payload = AutomationTaskUpsertPayload(
                    room_name=room_name,
                    seat_number=seat_number,
                    mode="preferred",
                    custom_windows=tuple(new_windows),
                    enabled=True,
                )
                self.upsert(
                    account_id=account_id,
                    task_id=None,
                    payload=payload,
                    expected_revision=0,
                    operator=operator,
                    client_kind=client_kind,
                )
                created += 1
                continue

            # 合并到已有任务：保留任务原 mode / enabled，把新时段并到 custom_windows。
            existing_hours = {
                (window.start_hour, window.end_hour)
                for window in existing_task.custom_windows
            }
            additions = [
                window
                for window in new_windows
                if (window.start_hour, window.end_hour) not in existing_hours
            ]
            if not additions:
                skip_reasons.append(
                    f"{room_name} {seat_number} 时段已存在，跳过"
                )
                continue

            merged_windows = tuple(list(existing_task.custom_windows) + additions)
            payload = AutomationTaskUpsertPayload(
                room_name=existing_task.room_name,
                seat_number=existing_task.seat_number,
                mode=existing_task.mode,
                custom_windows=merged_windows,
                enabled=existing_task.enabled,
            )
            self.upsert(
                account_id=account_id,
                task_id=existing_task.task_id,
                payload=payload,
                expected_revision=existing_task.revision,
                operator=operator,
                client_kind=client_kind,
            )
            updated += 1

        return created, updated, skip_reasons

    @staticmethod
    def _booking_to_window(snapshot: BookingSnapshot) -> CustomWindow | None:
        """把一条 ``BookingSnapshot`` 转成 ``CustomWindow``；跨午夜返回 ``None``。"""

        start_at = datetime.fromtimestamp(snapshot.start_time, tz=_SHANGHAI_TZ)
        duration = max(int(snapshot.duration_seconds), 0)
        if duration <= 0:
            return None
        end_at = start_at + timedelta(seconds=duration)
        # 跨日预约：按 spec ``end_hour <= 23`` 限制，直接跳过。号池和滚动预约都
        # 按整点小时模板抢座，跨日的非常少见，没必要在此特意拆段处理。
        if end_at.date() != start_at.date():
            return None
        if end_at.hour == 0 and end_at != start_at:
            # 24:00 边界：不允许写 end_hour=24
            return None
        return CustomWindow(
            date=start_at.date().isoformat(),
            start_hour=start_at.hour,
            end_hour=end_at.hour,
        )

    # ------------------------------ 私有：校验 ------------------------------

    def _validate_upsert_payload(
        self, payload: AutomationTaskUpsertPayload
    ) -> None:
        """字段非空与类型边界校验。

        校验规则与 design「Components and Interfaces · 字段校验」严格一致；不做
        任何业务语义校验（自习室 / 座位号是否真实存在）。命中任何一条则收集到
        ``errors`` 列表里，最终一次性抛 :class:`AutomationTaskValidationError`，
        让 REST 层在一次响应里把所有问题告诉客户端。
        """

        if not isinstance(payload, AutomationTaskUpsertPayload):
            raise AutomationTaskValidationError(
                [FieldError(field="payload", message="must be AutomationTaskUpsertPayload")]
            )

        errors: list[FieldError] = []

        # room_name
        if not isinstance(payload.room_name, str):
            errors.append(
                FieldError(field="room_name", message="must be string")
            )
        elif not payload.room_name.strip():
            errors.append(
                FieldError(field="room_name", message="must be non-empty")
            )
        elif len(payload.room_name) > _MAX_ROOM_NAME_LENGTH:
            errors.append(
                FieldError(
                    field="room_name",
                    message=f"length must be <= {_MAX_ROOM_NAME_LENGTH}",
                )
            )

        # seat_number
        if not isinstance(payload.seat_number, str):
            errors.append(
                FieldError(field="seat_number", message="must be string")
            )
        elif not payload.seat_number.strip():
            errors.append(
                FieldError(field="seat_number", message="must be non-empty")
            )
        elif len(payload.seat_number) > _MAX_SEAT_NUMBER_LENGTH:
            errors.append(
                FieldError(
                    field="seat_number",
                    message=f"length must be <= {_MAX_SEAT_NUMBER_LENGTH}",
                )
            )

        # mode
        if not isinstance(payload.mode, str):
            errors.append(FieldError(field="mode", message="must be string"))
        elif payload.mode not in _LEGAL_MODES:
            errors.append(
                FieldError(
                    field="mode",
                    message=f"must be one of {sorted(_LEGAL_MODES)}",
                )
            )

        # enabled：必须严格是 bool（避免 ``1`` / ``0`` 被静默接受）
        if not isinstance(payload.enabled, bool):
            errors.append(FieldError(field="enabled", message="must be bool"))

        # custom_windows
        if not isinstance(payload.custom_windows, tuple):
            errors.append(
                FieldError(
                    field="custom_windows",
                    message="must be tuple of CustomWindow",
                )
            )
        else:
            for index, window in enumerate(payload.custom_windows):
                self._validate_custom_window(window, index, errors)

        if errors:
            raise AutomationTaskValidationError(errors)

    @staticmethod
    def _validate_custom_window(
        window: object,
        index: int,
        errors: list[FieldError],
    ) -> None:
        if not isinstance(window, CustomWindow):
            errors.append(
                FieldError(
                    field=f"custom_windows[{index}]",
                    message="must be CustomWindow",
                )
            )
            return

        # date：服务端只检查类型与非空；具体格式（YYYY-MM-DD）由 design 8-Q4 默认
        # 不做业务校验。
        if not isinstance(window.date, str) or not window.date.strip():
            errors.append(
                FieldError(
                    field=f"custom_windows[{index}].date",
                    message="must be non-empty string",
                )
            )

        start_hour = window.start_hour
        end_hour = window.end_hour
        # bool 是 int 的子类，必须先排除以免 ``True`` 被当作合法小时通过。
        if isinstance(start_hour, bool) or not isinstance(start_hour, int):
            errors.append(
                FieldError(
                    field=f"custom_windows[{index}].start_hour",
                    message="must be int",
                )
            )
            return
        if isinstance(end_hour, bool) or not isinstance(end_hour, int):
            errors.append(
                FieldError(
                    field=f"custom_windows[{index}].end_hour",
                    message="must be int",
                )
            )
            return

        if not (_MIN_HOUR <= start_hour <= _MAX_HOUR):
            errors.append(
                FieldError(
                    field=f"custom_windows[{index}].start_hour",
                    message=f"must be in [{_MIN_HOUR}, {_MAX_HOUR}]",
                )
            )
        if not (_MIN_HOUR <= end_hour <= _MAX_HOUR):
            errors.append(
                FieldError(
                    field=f"custom_windows[{index}].end_hour",
                    message=f"must be in [{_MIN_HOUR}, {_MAX_HOUR}]",
                )
            )
        if (
            _MIN_HOUR <= start_hour <= _MAX_HOUR
            and _MIN_HOUR <= end_hour <= _MAX_HOUR
            and end_hour <= start_hour
        ):
            errors.append(
                FieldError(
                    field=f"custom_windows[{index}].end_hour",
                    message="must be greater than start_hour",
                )
            )

    # ------------------------------ 私有：活跃池校验 ------------------------------

    def _ensure_active_or_raise(
        self,
        account_id: int,
        *,
        connection: sqlite3.Connection,
    ) -> None:
        """若 ``account_id`` 不在 Active_Pool（含不存在 / 软删 / 暂停 / 未启用），抛
        :class:`AccountNotInActivePool`，由 REST 层翻译成 ``404``。
        """

        entry = self._account_pool_repo.get_by_id(
            account_id, connection=connection
        )
        if entry is None or entry.pool_status is not PoolStatus.ACTIVE:
            raise AccountNotInActivePool(
                f"账号不在活跃池：account_id={account_id}"
            )

    # ------------------------------ 私有：审计构造 ------------------------------

    def _append_rejection_audit(
        self,
        *,
        account_id: int,
        task_id: int | None,
        operator: str,
        client_kind: ClientKind,
        reason: str,
        payload: dict[str, Any],
    ) -> None:
        """把一条 ``task_upload_rejected`` 审计行写入独立短事务。

        与「持久化前就失败」语义对应：调用方在抛业务异常之前调用此方法，确保即使
        ``automation_tasks`` 表没有任何写入，审计仍能落地。
        """

        self._audit_repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.TASK_UPLOAD_REJECTED,
                trigger_source=PoolMigrationTrigger.MANUAL,
                operator=operator,
                success=False,
                account_id=account_id,
                task_id=task_id,
                client_kind=client_kind,
                reason=reason,
                payload=payload,
                created_at=self._now_utc(),
            )
        )

    def _build_success_entry(
        self,
        *,
        account_id: int,
        task_id: int,
        operator: str,
        client_kind: ClientKind,
        change_type: str,
        revision: int,
        extra: dict[str, Any],
    ) -> PoolAuditLogEntry:
        """构造一条 ``task_upload`` 成功审计行；调用方负责在事务内 append。"""

        merged_payload: dict[str, Any] = {
            "change_type": change_type,
            "revision": revision,
        }
        if extra:
            merged_payload.update(extra)
        return PoolAuditLogEntry(
            audit_action=PoolAuditAction.TASK_UPLOAD,
            trigger_source=PoolMigrationTrigger.MANUAL,
            operator=operator,
            success=True,
            account_id=account_id,
            task_id=task_id,
            client_kind=client_kind,
            payload=merged_payload,
            created_at=self._now_utc(),
        )

    # ------------------------------ 私有：事务 / 时钟 ------------------------------

    @contextmanager
    def _begin_transaction(self) -> Iterator[sqlite3.Connection]:
        """在 ``connect_database`` 之上手动控制事务边界。

        与 :class:`prevent_auto.services.account_pool_service.AccountPoolService`
        采用相同模式：先把外层挂起的隐式事务清空，再 ``BEGIN`` / ``COMMIT`` /
        ``ROLLBACK`` 显式控制；确保「数据写入 + 成功审计」两步原子提交，任一失败
        都回滚整段事务（Requirement 8.5）。
        """

        with connect_database(self.database_path) as conn:
            conn.commit()
            previous_isolation = conn.isolation_level
            conn.isolation_level = None
            conn.execute("BEGIN")
            try:
                yield conn
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.isolation_level = previous_isolation

    def _now_utc(self) -> datetime:
        """读取注入的 ``clock`` 并强制规范化到 UTC aware datetime。"""

        value = self._clock()
        if not isinstance(value, datetime):
            raise TypeError("clock 返回值必须是 datetime")
        if value.tzinfo is None:
            raise ValueError("clock 返回值必须带时区，禁止 naive datetime")
        return value.astimezone(UTC)


__all__ = [
    "AutomationTaskBulkEnabledResult",
    "AutomationTaskService",
    "AutomationTaskUpsertPayload",
    "AutomationTaskValidationError",
    "FieldError",
    "TaskBootstrapItem",
    "TaskBootstrapResult",
]
