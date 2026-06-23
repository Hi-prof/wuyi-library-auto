"""Account_Pool_Service：三池号池领域服务。

本模块对应 spec ``account-pool-tri-sync`` 的 task 4.1，提供：

* 跨池迁移 :meth:`AccountPoolService.migrate`：合法路径校验 + 副作用契约
  （Login_Status_Cache 占位 / 清理、暂停时段填充 / 清空）+ 同事务审计落库。
* 批量导入 Idle_Pool :meth:`AccountPoolService.bulk_import_to_idle`：条目级
  校验（必填、唯一键、容量），逐条返回结果；整体写一条 ``bulk_import`` 审计。
* Random_Pick :meth:`AccountPoolService.random_pick_from_idle`：从 Idle_Pool
  随机抽取一条；空池抛 :class:`IdleEmpty`，命中后只返回非敏感字段、不改池归属。
* 客户端拉黑事件 :meth:`AccountPoolService.mark_blacklisted_by_client`：
  仅对 Active_Pool 生效；非活跃账号一律抛 :class:`AccountNotInActivePool`，
  由 REST 层统一翻译成 ``404 {"detail":"account not found"}``。
* 同步接口 :meth:`AccountPoolService.list_active_for_sync` /
  :meth:`AccountPoolService.get_active_detail`：前者只返回非敏感字段；后者
  仅在 ``pool_status='active' AND deleted_at IS NULL`` 时返回 AES-GCM 解密
  后的明文密码 + 关联 ``automation_tasks``，否则抛 :class:`AccountNotInActivePool`。

设计约束：

* 状态变更 + 审计写入共用同一 SQLite 事务（Requirement 4.8、8.5）。本模块通过
  :func:`prevent_auto.database.connect_database` 取连接，并显式管理 BEGIN /
  COMMIT / ROLLBACK，避免 sqlite3 默认隐式事务把多步操作切成多个事务。
* 时间字段一律 UTC aware：通过 ``clock`` 注入便于测试；落库时序列化为
  ``...Z`` 后缀的 ISO8601 文本。
* 不在错误信息 / 审计 / 日志里夹带明文密码或加密密钥；批量导入的 ``payload_json``
  只包含 ``source_row`` / ``student_id`` / ``status`` / ``reason`` / ``account_id``，
  绝不含 ``password``（design Property 8）。
"""

from __future__ import annotations

import re
import sqlite3
from collections.abc import Callable, Iterable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Protocol

from prevent_auto.account_pool.constants import (
    ACTIVE_POOL_CAPACITY as DEFAULT_ACTIVE_POOL_CAPACITY,
    POOL_CAPACITY as DEFAULT_POOL_CAPACITY,
    SUSPENSION_TTL as DEFAULT_SUSPENSION_TTL,
)
from prevent_auto.account_pool.models import (
    AccountNotInActivePool,
    AccountPoolEntry,
    ActiveAccountDetail,
    ActiveAccountListItem,
    BulkImportItemResult,
    BulkImportItemStatus,
    BulkImportRejectReason,
    BulkImportResult,
    BulkImportRow,
    ClientKind,
    IdleEmpty,
    IllegalPoolTransition,
    MissingLoginCredentials,
    PoolCapacityExceeded,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import connect_database
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.login_status_cache import LoginStatusCacheRepository
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogEntry,
    PoolAuditLogRepository,
)
from prevent_auto.services.account_password_cipher import (
    AccountPasswordCipher,
    EncryptedPassword,
)


# --------------------------- Login_Status_Cache 协议 ---------------------------


class LoginStatusCache(Protocol):
    """Active_Pool 账号登录态缓存的最小接口。

    协议只暴露三个方法：迁入 active 时占位、迁出 active / 暂停 / 删除时清理、
    查询当前是否被占位（仅供测试 / Property 4 断言）。

    内置实现：

    * :class:`InMemoryLoginStatusCache`：仅供测试 / 开发使用，进程重启即清空；
    * :class:`SqliteLoginStatusCache`：生产路径默认实现，把占位集合落到
      ``login_status_cache`` 表，重启可恢复。

    后续若要扩展为「拉取登录态 + 持久化」的真实校验，可在持久化层增列；本协议
    保持不变。
    """

    def mark_active(self, account_id: int) -> None:  # pragma: no cover - 协议方法
        ...

    def clear(self, account_id: int) -> None:  # pragma: no cover - 协议方法
        ...

    def contains(self, account_id: int) -> bool:  # pragma: no cover - 协议方法
        ...


@dataclass
class InMemoryLoginStatusCache:
    """:class:`LoginStatusCache` 的最小内存实现。

    仅保存 ``account_id`` 占位集合，不维护 cookie / session token；服务进程
    重启后清空。生产环境会被替换为「拉取登录态 + 持久化」的真实实现，但接口
    与本类保持一致（design「Components and Interfaces · Account_Pool_Service」）。
    """

    _tracked: set[int] = field(default_factory=set)

    def mark_active(self, account_id: int) -> None:
        self._tracked.add(int(account_id))

    def clear(self, account_id: int) -> None:
        self._tracked.discard(int(account_id))

    def contains(self, account_id: int) -> bool:
        return int(account_id) in self._tracked


class SqliteLoginStatusCache:
    """:class:`LoginStatusCache` 的 SQLite 持久化实现。

    占位集合落到 ``login_status_cache`` 表（``account_id PRIMARY KEY`` +
    ``tracked_at``）。表 DDL 由 :func:`prevent_auto.database._create_pool_companion_tables`
    建好；启动钩子里会把现有 Active_Pool 账号一次性回填，以衔接早期使用
    :class:`InMemoryLoginStatusCache` 时未持久化的占位状态。

    ``mark_active`` / ``clear`` 都委托给
    :class:`prevent_auto.repositories.login_status_cache.LoginStatusCacheRepository`，
    每次开短连接、单语句执行；与现有 :class:`AccountPoolService` 在事务外调用
    缓存的口径一致，不与正在持有写事务的迁移连接抢锁。

    生产环境注入此实现即可获得「服务重启后号池管理页登录态列保持一致」的效果。
    后续若要扩展为「最近一次刷新登录态时间 + TTL」语义，可在表上增列；接口
    保持不变。
    """

    def __init__(
        self,
        repository: LoginStatusCacheRepository,
        *,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._repository = repository
        self._clock: Callable[[], datetime] = clock or (lambda: datetime.now(tz=UTC))

    def mark_active(self, account_id: int) -> None:
        self._repository.upsert(int(account_id), tracked_at=self._clock())

    def clear(self, account_id: int) -> None:
        self._repository.delete(int(account_id))

    def contains(self, account_id: int) -> bool:
        return self._repository.contains(int(account_id))


@dataclass(frozen=True)
class ActiveAccountUploadItemResult:
    """单条 Windows 上行账号的处理结果，不包含密码字段。"""

    source_row: int
    student_id: str
    status: BulkImportItemStatus
    account_id: int | None
    action: str | None
    reason: BulkImportRejectReason | None


@dataclass(frozen=True)
class ActiveAccountUploadResult:
    """Windows 客户端批量上行到 Active_Pool 的汇总结果。"""

    total: int
    created_count: int
    updated_count: int
    rejected_count: int
    items: tuple[ActiveAccountUploadItemResult, ...] = field(default_factory=tuple)


# ----------------------------- 合法迁移矩阵 -----------------------------


# `from_pool -> 合法 to_pool 集合`。同池迁移视为非法（迁移操作必须改变状态）；
# `idle -> suspended` 直达被显式拒绝（Requirement 2.7）。
_LEGAL_TRANSITIONS: dict[PoolStatus, frozenset[PoolStatus]] = {
    PoolStatus.ACTIVE: frozenset({PoolStatus.SUSPENDED, PoolStatus.IDLE}),
    PoolStatus.SUSPENDED: frozenset({PoolStatus.ACTIVE, PoolStatus.IDLE}),
    PoolStatus.IDLE: frozenset({PoolStatus.ACTIVE}),
}


def _is_legal_transition(from_pool: PoolStatus, to_pool: PoolStatus) -> bool:
    return to_pool in _LEGAL_TRANSITIONS.get(from_pool, frozenset())


# ----------------------------- 服务实现 -----------------------------


class AccountPoolService:
    """三池号池的领域服务。

    构造参数：

    * ``database_path``：SQLite 数据库文件路径，与其它 repository / service 一致。
    * ``cipher``：AES-GCM 密码加解密器；用于批量导入加密入库与详情接口解密回明文。
    * ``login_status_cache``：可选的 :class:`LoginStatusCache` 实例。缺省使用
      :class:`InMemoryLoginStatusCache`，便于本地开发 / 测试。
    * ``clock``：可注入的 ``Callable[[], datetime]``，必须返回带时区的 UTC 时间。
      默认 ``lambda: datetime.now(tz=UTC)``。
    * ``pool_capacity`` / ``active_pool_capacity`` / ``suspension_ttl``：服务级
      可覆盖的常量注入点（Requirement 1-Q1 / 2-Q2 / 3-Q2 默认值见
      :mod:`prevent_auto.account_pool.constants`）；**本服务不直接读 settings 模块**，
      由 web 层 / 测试在装配时把对应值传进来。
    """

    def __init__(
        self,
        database_path: str | Path,
        *,
        cipher: AccountPasswordCipher,
        login_status_cache: LoginStatusCache | None = None,
        clock: Callable[[], datetime] | None = None,
        pool_capacity: int = DEFAULT_POOL_CAPACITY,
        active_pool_capacity: int | None = DEFAULT_ACTIVE_POOL_CAPACITY,
        suspension_ttl: timedelta = DEFAULT_SUSPENSION_TTL,
    ) -> None:
        if pool_capacity <= 0:
            raise ValueError("pool_capacity 必须大于 0")
        if active_pool_capacity is not None and active_pool_capacity <= 0:
            raise ValueError("active_pool_capacity 必须大于 0 或为 None")
        if suspension_ttl <= timedelta(0):
            raise ValueError("suspension_ttl 必须为正时间增量")
        self.database_path = Path(database_path)
        self._cipher = cipher
        self._login_status_cache: LoginStatusCache = (
            login_status_cache if login_status_cache is not None else InMemoryLoginStatusCache()
        )
        self._clock: Callable[[], datetime] = clock or (lambda: datetime.now(tz=UTC))
        self.pool_capacity = pool_capacity
        self.active_pool_capacity = active_pool_capacity
        self.suspension_ttl = suspension_ttl

        self._account_pool_repo = AccountPoolRepository(self.database_path)
        self._automation_tasks_repo = AutomationTasksRepository(self.database_path)
        self._audit_repo = PoolAuditLogRepository(self.database_path)

    # ---------- 只读快捷入口 ----------

    @property
    def login_status_cache(self) -> LoginStatusCache:
        """暴露当前 ``LoginStatusCache``，方便测试做断言（Property 4）。"""

        return self._login_status_cache

    def list_by_pool(
        self,
        pool: PoolStatus,
        *,
        include_deleted: bool = False,
    ) -> list[AccountPoolEntry]:
        """按池状态返回账号列表，仅作三池视图便利方法。"""

        return self._account_pool_repo.list_by_pool(
            pool, include_deleted=include_deleted
        )

    # ---------- 跨池迁移 ----------

    def migrate(
        self,
        account_id: int,
        target_pool: PoolStatus,
        *,
        operator: str,
        trigger_source: PoolMigrationTrigger,
    ) -> AccountPoolEntry:
        """把 ``account_id`` 从当前池迁移到 ``target_pool``。

        副作用契约（design Property 4 / Requirement 2.2/2.3/2.4/6.1）：

        * 迁入 ``active``：检查必填登录字段（``student_id`` / ``login_url`` /
          已加密密码三列均非空），不全则抛 :class:`MissingLoginCredentials`；
          写状态后调用 ``login_status_cache.mark_active``。
        * 迁入 ``suspended``：写 ``suspended_at = now_utc`` 与
          ``suspension_expires_at = now_utc + suspension_ttl``，调用
          ``login_status_cache.clear``。
        * 迁入 ``idle``：清空 ``suspended_at`` / ``suspension_expires_at``，调用
          ``login_status_cache.clear``。

        校验顺序（与审计行的关系，对应 design Property 3）：

        1. 账号不存在 / 已软删 → :class:`AccountNotInActivePool`，**不写审计**
           （REST 层会按 404 翻译；不写审计避免大量 404 探测拉爆审计表）。
        2. 路径不在合法矩阵内 / 同池迁移 → :class:`IllegalPoolTransition`，写
           一条 ``audit_action='migrate', success=0`` 审计行（独立事务）。
        3. ``ACTIVE_POOL_CAPACITY`` 已满 → :class:`PoolCapacityExceeded`，写
           一条 ``success=0`` 审计行（``reason='pool_full_active'``）。
        4. 迁入 active 但缺少登录字段 → :class:`MissingLoginCredentials`，写
           一条 ``success=0`` 审计行。
        5. 通过校验：状态变更与成功审计行在 **同一事务** 内提交（Requirement 4.8）。
        """

        if not isinstance(target_pool, PoolStatus):
            raise TypeError("target_pool 必须是 PoolStatus 枚举值")
        if not isinstance(trigger_source, PoolMigrationTrigger):
            raise TypeError("trigger_source 必须是 PoolMigrationTrigger 枚举值")

        # 阶段 1：只读校验。失败时把失败审计写到独立短连接（不参与下面的事务），
        # 避免后续 ROLLBACK 把失败审计也卷走。
        entry = self._account_pool_repo.get_by_id(account_id)
        if entry is None:
            raise AccountNotInActivePool(
                f"账号不存在或已软删：account_id={account_id}"
            )
        from_pool = entry.pool_status

        if not _is_legal_transition(from_pool, target_pool):
            self._append_migrate_audit_failure(
                account_id=account_id,
                from_pool=from_pool,
                to_pool=target_pool,
                trigger_source=trigger_source,
                operator=operator,
                reason="illegal_transition",
            )
            raise IllegalPoolTransition(
                f"非法迁移路径 {from_pool.value} -> {target_pool.value}"
            )

        if (
            target_pool is PoolStatus.ACTIVE
            and self.active_pool_capacity is not None
        ):
            active_count = self._account_pool_repo.count_active()
            if active_count >= self.active_pool_capacity:
                self._append_migrate_audit_failure(
                    account_id=account_id,
                    from_pool=from_pool,
                    to_pool=target_pool,
                    trigger_source=trigger_source,
                    operator=operator,
                    reason="pool_full_active",
                )
                raise PoolCapacityExceeded("Active_Pool 单池容量已满")

        if target_pool is PoolStatus.ACTIVE:
            with connect_database(self.database_path) as conn:
                row = conn.execute(
                    "SELECT student_id, login_url, password_cipher "
                    "FROM accounts WHERE id = ?",
                    (account_id,),
                ).fetchone()
            if row is None or _missing_login_credentials(row):
                self._append_migrate_audit_failure(
                    account_id=account_id,
                    from_pool=from_pool,
                    to_pool=target_pool,
                    trigger_source=trigger_source,
                    operator=operator,
                    reason="missing_login_credentials",
                )
                raise MissingLoginCredentials(
                    f"账号缺少登录所需字段：account_id={account_id}"
                )

        # 阶段 2：状态变更 + 成功审计同事务提交。
        now = self._now_utc()
        suspended_at: datetime | None = None
        suspension_expires_at: datetime | None = None
        if target_pool is PoolStatus.SUSPENDED:
            suspended_at = now
            suspension_expires_at = now + self.suspension_ttl

        with self._begin_transaction() as conn:
            updated = self._account_pool_repo.update_pool_status(
                account_id,
                pool_status=target_pool,
                pool_updated_at=now,
                pool_previous=from_pool,
                suspended_at=suspended_at,
                suspension_expires_at=suspension_expires_at,
                connection=conn,
            )
            if updated is None:
                # 阶段 1 已校验存在，这里仅作并发删除兜底。
                raise AccountNotInActivePool(
                    f"账号迁移时被并发删除：account_id={account_id}"
                )

            self._append_migrate_audit(
                conn,
                account_id=account_id,
                from_pool=from_pool,
                to_pool=target_pool,
                trigger_source=trigger_source,
                operator=operator,
                success=True,
                reason="",
            )

        # 副作用：登录态缓存（事务外，幂等）。
        if target_pool is PoolStatus.ACTIVE:
            self._login_status_cache.mark_active(account_id)
        else:
            self._login_status_cache.clear(account_id)

        return updated

    # ---------- 批量导入到 Idle_Pool ----------

    def bulk_import_to_idle(
        self,
        rows: Iterable[BulkImportRow],
        *,
        operator: str,
    ) -> BulkImportResult:
        """把若干 :class:`BulkImportRow` 写入 Idle_Pool。

        校验顺序（条目级）：

        1. ``student_id`` / ``password`` 必填；空白即按 ``VALIDATION_ERROR`` 拒绝。
        2. 容量余量：以本批已经成功的行数 + 数据库现有行数计算，超过
           ``pool_capacity`` 即按 ``POOL_FULL`` 拒绝。
        3. 唯一键 ``(student_id, login_url)``：先在批内查重，再在数据库已有未软删
           行中查重；命中即按 ``DUPLICATE_STUDENT_ID`` 拒绝。
        4. 通过校验则加密密码后插入；插入仍因数据库约束失败（如 ``accounts.name``
           UNIQUE 冲突）回退到 ``VALIDATION_ERROR``。

        全过程在单事务内完成，并写一条 ``audit_action='bulk_import'`` 审计行；
        ``payload_json`` 只包含条目级元信息（``source_row``、``student_id``、
        ``status``、``reason``、``account_id``），绝不含明文 / 密文密码。
        """

        rows_list = list(rows)
        items: list[BulkImportItemResult] = []
        success_count = 0
        failure_count = 0

        with self._begin_transaction() as conn:
            existing_total = self._account_pool_repo.count_total(connection=conn)
            seen_keys: set[tuple[str, str]] = set()

            for row in rows_list:
                source_row = int(row.source_row)
                raw_student_id = (row.student_id or "").strip()
                password = row.password or ""
                if not raw_student_id or not password:
                    items.append(
                        BulkImportItemResult(
                            source_row=source_row,
                            student_id=row.student_id,
                            status=BulkImportItemStatus.REJECTED,
                            account_id=None,
                            reason=BulkImportRejectReason.VALIDATION_ERROR,
                        )
                    )
                    failure_count += 1
                    continue

                login_url = (row.login_url or "").strip()
                key = (raw_student_id, login_url)

                if existing_total + success_count >= self.pool_capacity:
                    items.append(
                        BulkImportItemResult(
                            source_row=source_row,
                            student_id=raw_student_id,
                            status=BulkImportItemStatus.REJECTED,
                            account_id=None,
                            reason=BulkImportRejectReason.POOL_FULL,
                        )
                    )
                    failure_count += 1
                    continue

                if key in seen_keys or self._account_pool_repo.find_by_unique_key(
                    student_id=raw_student_id,
                    login_url=login_url,
                    connection=conn,
                ) is not None:
                    items.append(
                        BulkImportItemResult(
                            source_row=source_row,
                            student_id=raw_student_id,
                            status=BulkImportItemStatus.REJECTED,
                            account_id=None,
                            reason=BulkImportRejectReason.DUPLICATE_STUDENT_ID,
                        )
                    )
                    failure_count += 1
                    continue

                try:
                    new_id = self._insert_idle_account(
                        conn,
                        row=row,
                        student_id=raw_student_id,
                        login_url=login_url,
                        password=password,
                    )
                except sqlite3.IntegrityError:
                    items.append(
                        BulkImportItemResult(
                            source_row=source_row,
                            student_id=raw_student_id,
                            status=BulkImportItemStatus.REJECTED,
                            account_id=None,
                            reason=BulkImportRejectReason.VALIDATION_ERROR,
                        )
                    )
                    failure_count += 1
                    continue

                seen_keys.add(key)
                success_count += 1
                items.append(
                    BulkImportItemResult(
                        source_row=source_row,
                        student_id=raw_student_id,
                        status=BulkImportItemStatus.OK,
                        account_id=new_id,
                        reason=None,
                    )
                )

            self._audit_repo.append(
                PoolAuditLogEntry(
                    audit_action=PoolAuditAction.BULK_IMPORT,
                    trigger_source=PoolMigrationTrigger.IMPORT,
                    operator=operator,
                    success=failure_count == 0,
                    payload={
                        "total": len(rows_list),
                        "success_count": success_count,
                        "failure_count": failure_count,
                        "items": [
                            {
                                "source_row": item.source_row,
                                "student_id": item.student_id,
                                "status": item.status.value,
                                "account_id": item.account_id,
                                "reason": item.reason.value
                                if item.reason is not None
                                else None,
                            }
                            for item in items
                        ],
                    },
                ),
                connection=conn,
            )

        return BulkImportResult(
            total=len(rows_list),
            success_count=success_count,
            failure_count=failure_count,
            items=tuple(items),
        )

    # ---------- Windows 客户端账号上行 ----------

    def upload_to_active_pool(
        self,
        rows: Iterable[BulkImportRow],
        *,
        operator: str,
        client_kind: ClientKind = ClientKind.WINDOW,
    ) -> ActiveAccountUploadResult:
        """把 Windows 客户端账号写入 Active_Pool。

        语义是 upsert：按 ``student_id`` 定位既有未软删账号，命中则更新密码 /
        备注 / 登录入口并迁入 Active_Pool；未命中则直接创建 Active_Pool 账号。
        本方法不会删除服务端已有账号，也不会把密码写入审计 payload。
        """

        if not isinstance(client_kind, ClientKind):
            raise TypeError("client_kind 必须是 ClientKind 枚举值")

        rows_list = list(rows)
        items: list[ActiveAccountUploadItemResult] = []
        created_count = 0
        updated_count = 0
        rejected_count = 0
        activated_account_ids: list[int] = []

        with self._begin_transaction() as conn:
            existing_total = self._account_pool_repo.count_total(connection=conn)
            active_count = self._account_pool_repo.count_active(connection=conn)
            seen_student_ids: set[str] = set()

            for row in rows_list:
                source_row = int(row.source_row)
                student_id = (row.student_id or "").strip()
                password = row.password or ""
                login_url = (row.login_url or "").strip()

                if not student_id or not password:
                    items.append(
                        ActiveAccountUploadItemResult(
                            source_row=source_row,
                            student_id=student_id or row.student_id,
                            status=BulkImportItemStatus.REJECTED,
                            account_id=None,
                            action=None,
                            reason=BulkImportRejectReason.VALIDATION_ERROR,
                        )
                    )
                    rejected_count += 1
                    continue

                if student_id in seen_student_ids:
                    items.append(
                        ActiveAccountUploadItemResult(
                            source_row=source_row,
                            student_id=student_id,
                            status=BulkImportItemStatus.REJECTED,
                            account_id=None,
                            action=None,
                            reason=BulkImportRejectReason.DUPLICATE_STUDENT_ID,
                        )
                    )
                    rejected_count += 1
                    continue
                seen_student_ids.add(student_id)

                existing = self._find_account_by_student_id(
                    conn, student_id=student_id
                )
                needs_active_slot = (
                    existing is None or existing.pool_status is not PoolStatus.ACTIVE
                )
                if (
                    needs_active_slot
                    and self.active_pool_capacity is not None
                    and active_count >= self.active_pool_capacity
                ):
                    items.append(
                        ActiveAccountUploadItemResult(
                            source_row=source_row,
                            student_id=student_id,
                            status=BulkImportItemStatus.REJECTED,
                            account_id=None,
                            action=None,
                            reason=BulkImportRejectReason.POOL_FULL,
                        )
                    )
                    rejected_count += 1
                    continue

                if existing is None:
                    if existing_total >= self.pool_capacity:
                        items.append(
                            ActiveAccountUploadItemResult(
                                source_row=source_row,
                                student_id=student_id,
                                status=BulkImportItemStatus.REJECTED,
                                account_id=None,
                                action=None,
                                reason=BulkImportRejectReason.POOL_FULL,
                            )
                        )
                        rejected_count += 1
                        continue
                    account_id = self._insert_uploaded_active_account(
                        conn,
                        row=row,
                        student_id=student_id,
                        login_url=login_url,
                        password=password,
                    )
                    existing_total += 1
                    active_count += 1
                    created_count += 1
                    action = "created"
                else:
                    conflict = self._find_unique_key_conflict(
                        conn,
                        student_id=student_id,
                        login_url=login_url,
                        exclude_account_id=existing.account_id,
                    )
                    if conflict is not None:
                        items.append(
                            ActiveAccountUploadItemResult(
                                source_row=source_row,
                                student_id=student_id,
                                status=BulkImportItemStatus.REJECTED,
                                account_id=None,
                                action=None,
                                reason=BulkImportRejectReason.DUPLICATE_STUDENT_ID,
                            )
                        )
                        rejected_count += 1
                        continue
                    self._update_uploaded_active_account(
                        conn,
                        account_id=existing.account_id,
                        row=row,
                        student_id=student_id,
                        login_url=login_url,
                        password=password,
                        previous_pool=existing.pool_status,
                    )
                    if existing.pool_status is not PoolStatus.ACTIVE:
                        active_count += 1
                    account_id = existing.account_id
                    updated_count += 1
                    action = "updated"

                activated_account_ids.append(account_id)
                items.append(
                    ActiveAccountUploadItemResult(
                        source_row=source_row,
                        student_id=student_id,
                        status=BulkImportItemStatus.OK,
                        account_id=account_id,
                        action=action,
                        reason=None,
                    )
                )

            self._audit_repo.append(
                PoolAuditLogEntry(
                    audit_action=PoolAuditAction.BULK_IMPORT,
                    trigger_source=PoolMigrationTrigger.IMPORT,
                    operator=operator,
                    success=rejected_count == 0,
                    client_kind=client_kind,
                    payload={
                        "mode": "window_upload_to_active",
                        "total": len(rows_list),
                        "created_count": created_count,
                        "updated_count": updated_count,
                        "rejected_count": rejected_count,
                        "items": [
                            {
                                "source_row": item.source_row,
                                "student_id": item.student_id,
                                "status": item.status.value,
                                "account_id": item.account_id,
                                "action": item.action,
                                "reason": item.reason.value
                                if item.reason is not None
                                else None,
                            }
                            for item in items
                        ],
                    },
                ),
                connection=conn,
            )

        for account_id in activated_account_ids:
            self._login_status_cache.mark_active(account_id)

        return ActiveAccountUploadResult(
            total=len(rows_list),
            created_count=created_count,
            updated_count=updated_count,
            rejected_count=rejected_count,
            items=tuple(items),
        )

    # ---------- Random_Pick ----------

    def random_pick_from_idle(self, *, operator: str) -> AccountPoolEntry:
        """从 Idle_Pool 随机抽取一条；空池抛 :class:`IdleEmpty`。

        * 命中后 **不** 改池归属（Requirement 5.7、design Property 9）；返回的
          :class:`AccountPoolEntry` 仅用于 UI 展示非敏感字段。
        * 命中即写一条 ``audit_action='random_pick'`` 审计；空池不写审计，避免空
          点击拉爆审计表。
        """

        with self._begin_transaction() as conn:
            row = conn.execute(
                """
                SELECT * FROM accounts
                WHERE pool_status = ? AND deleted_at IS NULL
                ORDER BY RANDOM()
                LIMIT 1
                """,
                (PoolStatus.IDLE.value,),
            ).fetchone()
            if row is None:
                raise IdleEmpty("未启用池为空")
            entry = self._account_pool_repo.get_by_id(int(row["id"]), connection=conn)
            assert entry is not None  # 上一步刚命中

            self._audit_repo.append(
                PoolAuditLogEntry(
                    audit_action=PoolAuditAction.RANDOM_PICK,
                    trigger_source=PoolMigrationTrigger.RANDOM_PICK,
                    operator=operator,
                    success=True,
                    account_id=entry.account_id,
                ),
                connection=conn,
            )

        return entry

    # ---------- 客户端拉黑事件 ----------

    def mark_blacklisted_by_client(
        self,
        account_id: int,
        *,
        client_kind: ClientKind,
        evidence: str,
    ) -> AccountPoolEntry:
        """处理客户端上报的拉黑事件，把 Active_Pool 账号迁入 Suspended_Pool。

        非活跃账号一律抛 :class:`AccountNotInActivePool`（含不存在 / 软删 / 已在
        其它池），由 REST 层统一翻译成 ``404 {"detail":"account not found"}``，
        以避免泄露真实状态（Requirement 6.5）。
        """

        if not isinstance(client_kind, ClientKind):
            raise TypeError("client_kind 必须是 ClientKind 枚举值")

        with self._begin_transaction() as conn:
            entry = self._account_pool_repo.get_by_id(account_id, connection=conn)
            if entry is None or entry.pool_status is not PoolStatus.ACTIVE:
                raise AccountNotInActivePool(
                    f"账号不在活跃池：account_id={account_id}"
                )

            now = self._now_utc()
            now_iso = _format_utc(now)
            conn.execute(
                """
                UPDATE accounts
                SET last_blacklist_evidence = ?, updated_at = ?
                WHERE id = ?
                """,
                (evidence, now_iso, account_id),
            )

            updated = self._account_pool_repo.update_pool_status(
                account_id,
                pool_status=PoolStatus.SUSPENDED,
                pool_updated_at=now,
                pool_previous=PoolStatus.ACTIVE,
                suspended_at=now,
                suspension_expires_at=now + self.suspension_ttl,
                connection=conn,
            )
            if updated is None:  # pragma: no cover - 上一步刚校验存在
                raise AccountNotInActivePool(
                    f"账号在拉黑事件处理时被并发删除：account_id={account_id}"
                )

            self._audit_repo.append(
                PoolAuditLogEntry(
                    audit_action=PoolAuditAction.MIGRATE,
                    account_id=account_id,
                    from_pool=PoolStatus.ACTIVE,
                    to_pool=PoolStatus.SUSPENDED,
                    trigger_source=PoolMigrationTrigger.BLACKLIST,
                    operator=f"client:{client_kind.value}",
                    client_kind=client_kind,
                    success=True,
                    reason="blacklist",
                    payload={"evidence": evidence},
                ),
                connection=conn,
            )

        # 副作用：登录态缓存（事务外，幂等）。
        # 持久化实现 :class:`SqliteLoginStatusCache` 会另开 SQLite 连接，
        # 必须在外层写事务结束后再调用，避免「database is locked」。
        self._login_status_cache.clear(account_id)

        return updated

    # ---------- 同步接口 ----------

    def list_active_for_sync(self) -> list[ActiveAccountListItem]:
        """返回 Active_Pool 全量清单，仅暴露非敏感字段（Requirement 7.1、7.2）。"""

        entries = self._account_pool_repo.list_by_pool(PoolStatus.ACTIVE)
        return [
            ActiveAccountListItem(
                account_id=entry.account_id,
                student_id=entry.student_id,
                display_name=entry.display_name,
                pool_status=PoolStatus.ACTIVE,
                updated_at=entry.pool_updated_at,
            )
            for entry in entries
        ]

    def get_active_detail(self, account_id: int) -> ActiveAccountDetail:
        """返回 Active_Pool 账号详情；非活跃 / 软删一律抛 ``AccountNotInActivePool``。

        * 仅在 ``pool_status='active' AND deleted_at IS NULL`` 时命中。
        * 命中后用 AES-GCM 解密 ``password_cipher / nonce / tag`` 三列得到明文密码；
          关联 ``automation_tasks`` 用 :class:`AutomationTasksRepository`，默认
          过滤已软删任务（design 8-Q3）。
        """

        with connect_database(self.database_path) as conn:
            row = conn.execute(
                """
                SELECT id, student_id, display_name, revision,
                       password_cipher, password_nonce, password_tag
                FROM accounts
                WHERE id = ? AND pool_status = ? AND deleted_at IS NULL
                """,
                (account_id, PoolStatus.ACTIVE.value),
            ).fetchone()
            if row is None:
                raise AccountNotInActivePool(
                    f"账号不在活跃池：account_id={account_id}"
                )

            password = self._cipher.decrypt(
                EncryptedPassword(
                    cipher=bytes(row["password_cipher"] or b""),
                    nonce=bytes(row["password_nonce"] or b""),
                    tag=bytes(row["password_tag"] or b""),
                )
            )
            tasks = self._automation_tasks_repo.list_for_account(
                account_id, connection=conn
            )

        return ActiveAccountDetail(
            account_id=int(row["id"]),
            student_id=str(row["student_id"]),
            display_name=str(row["display_name"] or ""),
            password=password,
            revision=int(row["revision"] or 0),
            automation_tasks=tuple(tasks),
        )

    def get_login_password(self, account_id: int) -> str:
        """读取账号登录密码，兼容旧明文列与号池 AES-GCM 密文列。"""

        with connect_database(self.database_path) as conn:
            row = conn.execute(
                """
                SELECT password, password_cipher, password_nonce, password_tag
                FROM accounts
                WHERE id = ? AND deleted_at IS NULL
                """,
                (account_id,),
            ).fetchone()
        if row is None:
            raise ValueError(f"未找到账号：{account_id}")

        password = str(row["password"] or "")
        if password:
            return password
        return self._cipher.decrypt(
            EncryptedPassword(
                cipher=bytes(row["password_cipher"] or b""),
                nonce=bytes(row["password_nonce"] or b""),
                tag=bytes(row["password_tag"] or b""),
            )
        )

    # ---------- 私有工具 ----------

    @contextmanager
    def _begin_transaction(self) -> Iterator[sqlite3.Connection]:
        """在 ``connect_database`` 之上手动控制事务边界。

        sqlite3 默认 ``isolation_level`` 会在执行 DDL 前自动提交；本服务的多步
        写入需要原子性（状态 + 审计），所以把 ``isolation_level=None`` 后显式
        ``BEGIN`` / ``COMMIT`` / ``ROLLBACK``，与 :func:`prevent_auto.database._ensure_account_pool_columns`
        采用相同模式。
        """

        with connect_database(self.database_path) as conn:
            # 退出 with 时 sqlite3 仍会做隐式 commit；为防止本事务被夹在两个
            # 隐式事务中，先把外层挂起的隐式事务清空。
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

    def _append_migrate_audit(
        self,
        conn: sqlite3.Connection,
        *,
        account_id: int,
        from_pool: PoolStatus,
        to_pool: PoolStatus,
        trigger_source: PoolMigrationTrigger,
        operator: str,
        success: bool,
        reason: str,
        payload: dict[str, Any] | None = None,
    ) -> None:
        """统一 ``audit_action='migrate'`` 审计写入，避免散落多处的字段拼装。"""

        merged_payload: dict[str, Any] = {
            "from": from_pool.value,
            "to": to_pool.value,
        }
        if payload:
            merged_payload.update(payload)
        self._audit_repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.MIGRATE,
                account_id=account_id,
                from_pool=from_pool,
                to_pool=to_pool,
                trigger_source=trigger_source,
                operator=operator,
                success=success,
                reason=reason,
                payload=merged_payload,
            ),
            connection=conn,
        )

    def _append_migrate_audit_failure(
        self,
        *,
        account_id: int,
        from_pool: PoolStatus,
        to_pool: PoolStatus,
        trigger_source: PoolMigrationTrigger,
        operator: str,
        reason: str,
    ) -> None:
        """对失败迁移单独写一条审计行（独立短连接，与可能的事务回滚解耦）。

        Property 3 要求合法 / 非法 / 容量越界 / 缺登录字段失败时各自落一条
        ``audit_action='migrate', success=0`` 审计行；如果把它放在主事务里，
        紧跟着的异常会触发 ROLLBACK，把审计行也卷走。这里使用 repository 的
        默认连接（每次方法独立 commit），保证失败审计稳定落库。
        """

        self._audit_repo.append(
            PoolAuditLogEntry(
                audit_action=PoolAuditAction.MIGRATE,
                account_id=account_id,
                from_pool=from_pool,
                to_pool=to_pool,
                trigger_source=trigger_source,
                operator=operator,
                success=False,
                reason=reason,
                payload={"from": from_pool.value, "to": to_pool.value},
            )
        )

    def _find_account_by_student_id(
        self,
        conn: sqlite3.Connection,
        *,
        student_id: str,
    ) -> AccountPoolEntry | None:
        row = conn.execute(
            """
            SELECT id FROM accounts
            WHERE student_id = ? AND deleted_at IS NULL
            ORDER BY id ASC
            LIMIT 1
            """,
            (student_id,),
        ).fetchone()
        if row is None:
            return None
        return self._account_pool_repo.get_by_id(int(row["id"]), connection=conn)

    @staticmethod
    def _find_unique_key_conflict(
        conn: sqlite3.Connection,
        *,
        student_id: str,
        login_url: str,
        exclude_account_id: int,
    ) -> int | None:
        row = conn.execute(
            """
            SELECT id FROM accounts
            WHERE student_id = ?
              AND login_url = ?
              AND id != ?
              AND deleted_at IS NULL
            ORDER BY id ASC
            LIMIT 1
            """,
            (student_id, login_url, exclude_account_id),
        ).fetchone()
        return int(row["id"]) if row is not None else None

    def _insert_uploaded_active_account(
        self,
        conn: sqlite3.Connection,
        *,
        row: BulkImportRow,
        student_id: str,
        login_url: str,
        password: str,
    ) -> int:
        encrypted = self._cipher.encrypt(password)
        now_iso = _format_utc(self._now_utc())
        display_name = (row.display_name or "").strip()
        base_name = display_name or student_id
        account_name = self._allocate_account_name(conn, base_name)
        state_file = _build_state_file(student_id)

        cursor = conn.execute(
            """
            INSERT INTO accounts (
                name, student_id, password, login_url, seat_url,
                rebook_enabled, rebook_trigger_minutes, state_file,
                account_status, enabled,
                last_check_at, last_status, created_at, updated_at,
                pool_status, pool_updated_at, pool_previous,
                suspended_at, suspension_expires_at,
                display_name,
                password_cipher, password_nonce, password_tag,
                revision, deleted_at, last_blacklist_evidence
            )
            VALUES (?, ?, '', ?, '',
                    0, 5, ?,
                    'unknown', 1,
                    NULL, '', ?, ?,
                    ?, ?, '',
                    NULL, NULL,
                    ?,
                    ?, ?, ?,
                    0, NULL, '')
            """,
            (
                account_name,
                student_id,
                login_url,
                state_file,
                now_iso,
                now_iso,
                PoolStatus.ACTIVE.value,
                now_iso,
                display_name,
                encrypted.cipher,
                encrypted.nonce,
                encrypted.tag,
            ),
        )
        return int(cursor.lastrowid)

    def _update_uploaded_active_account(
        self,
        conn: sqlite3.Connection,
        *,
        account_id: int,
        row: BulkImportRow,
        student_id: str,
        login_url: str,
        password: str,
        previous_pool: PoolStatus,
    ) -> None:
        encrypted = self._cipher.encrypt(password)
        now_iso = _format_utc(self._now_utc())
        display_name = (row.display_name or "").strip()
        pool_previous = (
            previous_pool.value
            if previous_pool is not PoolStatus.ACTIVE
            else PoolStatus.ACTIVE.value
        )
        conn.execute(
            """
            UPDATE accounts
            SET student_id = ?,
                login_url = ?,
                display_name = ?,
                password = '',
                password_cipher = ?,
                password_nonce = ?,
                password_tag = ?,
                pool_status = ?,
                pool_updated_at = ?,
                pool_previous = ?,
                suspended_at = NULL,
                suspension_expires_at = NULL,
                last_blacklist_evidence = '',
                updated_at = ?,
                revision = revision + 1
            WHERE id = ? AND deleted_at IS NULL
            """,
            (
                student_id,
                login_url,
                display_name,
                encrypted.cipher,
                encrypted.nonce,
                encrypted.tag,
                PoolStatus.ACTIVE.value,
                now_iso,
                pool_previous,
                now_iso,
                account_id,
            ),
        )

    def _insert_idle_account(
        self,
        conn: sqlite3.Connection,
        *,
        row: BulkImportRow,
        student_id: str,
        login_url: str,
        password: str,
    ) -> int:
        """把单条批量导入数据写入 ``accounts`` 表，返回新行主键。

        * 加密密码后写入 ``password_cipher / nonce / tag`` 三列；旧 ``password``
          列保留为空字符串（与启动迁移钩子的口径一致，便于回滚 1 个版本）。
        * ``name`` 列尝试用 ``display_name`` / ``student_id`` 作为 base，遇
          UNIQUE 冲突时追加数字后缀寻找空位，最多尝试 1000 次后抛错让外层
          落入 ``VALIDATION_ERROR``。
        * ``state_file`` 由 ``student_id`` slug 化生成，与
          :class:`prevent_auto.repositories.accounts.AccountsRepository._build_state_file`
          相同口径，避免新旧路径冲突。
        """

        encrypted = self._cipher.encrypt(password)
        now_iso = _format_utc(self._now_utc())
        display_name = (row.display_name or "").strip()
        base_name = display_name or student_id
        account_name = self._allocate_account_name(conn, base_name)
        state_file = _build_state_file(student_id)

        cursor = conn.execute(
            """
            INSERT INTO accounts (
                name, student_id, password, login_url, seat_url,
                rebook_enabled, rebook_trigger_minutes, state_file,
                account_status, enabled,
                last_check_at, last_status, created_at, updated_at,
                pool_status, pool_updated_at, pool_previous,
                suspended_at, suspension_expires_at,
                display_name,
                password_cipher, password_nonce, password_tag,
                revision, deleted_at, last_blacklist_evidence
            )
            VALUES (?, ?, '', ?, '',
                    0, 5, ?,
                    'unknown', 1,
                    NULL, '', ?, ?,
                    ?, ?, '',
                    NULL, NULL,
                    ?,
                    ?, ?, ?,
                    0, NULL, '')
            """,
            (
                account_name,
                student_id,
                login_url,
                state_file,
                now_iso,
                now_iso,
                PoolStatus.IDLE.value,
                now_iso,
                display_name,
                encrypted.cipher,
                encrypted.nonce,
                encrypted.tag,
            ),
        )
        return int(cursor.lastrowid)

    @staticmethod
    def _allocate_account_name(conn: sqlite3.Connection, base: str) -> str:
        """为新账号选一个不与既有 ``name UNIQUE`` 列冲突的字符串。

        ``base`` 为空白时回退到 ``account``，再叠加数字后缀。SQLite 把空字符串
        视为有效值并参与 UNIQUE，因此空 ``name`` 也需要走分配流程。
        """

        candidate = base.strip() or "account"
        for suffix in range(1000):
            attempt = candidate if suffix == 0 else f"{candidate}-{suffix}"
            row = conn.execute(
                "SELECT 1 FROM accounts WHERE name = ?",
                (attempt,),
            ).fetchone()
            if row is None:
                return attempt
        raise sqlite3.IntegrityError(
            f"账号 name 冲突无法解决，base={candidate!r}"
        )


# ----------------------------- 模块级工具 -----------------------------


def _format_utc(value: datetime) -> str:
    """把 UTC aware datetime 序列化为 ``...Z`` 后缀的 ISO8601 文本。

    与 :mod:`prevent_auto.repositories.account_pool` 的同名函数保持完全一致；
    服务层不复用 repository 的私有函数以避免跨模块隐式依赖。
    """

    if value.tzinfo is None:
        raise ValueError("datetime 必须带时区，禁止使用 naive datetime")
    aware = value.astimezone(UTC).replace(microsecond=0)
    return aware.isoformat().replace("+00:00", "Z")


def _build_state_file(student_id: str) -> str:
    """复用 :class:`AccountsRepository` 的 state_file 命名口径。

    任何新建账号都需要一个登录态缓存文件名；这里按 ``student_id`` 的 slug 形式
    生成，保证与 web 端老路径写出的文件名一致，避免登录态被切成两份。
    """

    slug = re.sub(r"[^0-9A-Za-z_-]+", "-", student_id.strip()).strip("-").lower()
    slug = slug or "account"
    return f"runtime/auth-{slug}.json"


def _missing_login_credentials(row: sqlite3.Row) -> bool:
    """判定 ``row`` 是否缺少登录所需字段。

    要求 ``student_id`` 与 ``login_url`` 非空、``password_cipher`` 长度大于 0。
    """

    student_id = (row["student_id"] or "").strip()
    login_url = (row["login_url"] or "").strip()
    cipher_blob = row["password_cipher"]
    cipher_len = len(cipher_blob) if cipher_blob is not None else 0
    return not student_id or not login_url or cipher_len == 0


__all__ = [
    "ActiveAccountUploadItemResult",
    "ActiveAccountUploadResult",
    "AccountPoolService",
    "InMemoryLoginStatusCache",
    "LoginStatusCache",
    "SqliteLoginStatusCache",
]
