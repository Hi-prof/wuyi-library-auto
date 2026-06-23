"""accounts 表的号池视图 Repository。

本模块封装 spec ``account-pool-tri-sync`` 在 ``accounts`` 表上扩展出的「三池字段」
读写：``pool_status`` / ``pool_updated_at`` / ``pool_previous`` / ``suspended_at`` /
``suspension_expires_at`` / ``display_name`` / ``revision`` / ``deleted_at`` 等。

设计要点：

* 所有时间字段统一以 UTC ISO8601 文本读写；入参必须是带时区的
  :class:`datetime.datetime`，否则抛 :class:`ValueError`。
* 默认按 ``deleted_at IS NULL`` 过滤软删除；调用方需要观测软删行（例如启动期迁移
  与一致性核对）时，可显式传 ``include_deleted=True``。
* 默认每个方法自管理 SQLite 连接，沿用 repository 包内的现有风格；同时接受可选的
  ``connection`` 注入，便于上层 service 在「状态变更 + 审计写入」场景共享同一事务
  （Requirement 4.8、8.5）。
* 仓库层 **不** 抛业务异常：找不到行返回 ``None``、乐观并发不命中返回 ``None``，由
  上层 service 翻译成 ``AccountNotInActivePool`` / ``RevisionConflict``。
"""

from __future__ import annotations

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from prevent_auto.account_pool.models import AccountPoolEntry, PoolStatus
from prevent_auto.database import connect_database


class AccountPoolRepository:
    """围绕 accounts 表三池字段的 CRUD。

    与 :class:`prevent_auto.repositories.accounts.AccountsRepository` 的职责互补：
    后者负责账号基础属性、登录字段与重约相关字段，本 repo 专注于号池语义字段。
    两者共用同一张 ``accounts`` 表与同一份 ``database_path``。
    """

    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    @contextmanager
    def _connect(
        self, connection: sqlite3.Connection | None
    ) -> Iterator[sqlite3.Connection]:
        """获取一个 SQLite 连接。

        若调用方注入了 ``connection``，本方法直接复用、不负责 commit / close（让上层
        统一控制事务边界）；否则按现有 repository 风格在方法内开启短连接，``with``
        退出时由 sqlite3 模块负责事务提交。
        """

        if connection is not None:
            yield connection
            return
        with connect_database(self.database_path) as conn:
            yield conn

    # ------------------------------ 查询 ------------------------------

    def list_by_pool(
        self,
        pool_status: PoolStatus,
        *,
        include_deleted: bool = False,
        connection: sqlite3.Connection | None = None,
    ) -> list[AccountPoolEntry]:
        """按池状态分页返回账号列表。

        排序按 ``pool_updated_at DESC, id ASC``，与 design「Account_Pool_Web_Page UI
        设计」一致：列表展示「最近变更优先」。
        """

        query = "SELECT * FROM accounts WHERE pool_status = ?"
        params: list[object] = [pool_status.value]
        if not include_deleted:
            query += " AND deleted_at IS NULL"
        query += " ORDER BY pool_updated_at DESC, id ASC"
        with self._connect(connection) as conn:
            rows = conn.execute(query, params).fetchall()
        return [_row_to_entry(row) for row in rows]

    def get_by_id(
        self,
        account_id: int,
        *,
        include_deleted: bool = False,
        connection: sqlite3.Connection | None = None,
    ) -> AccountPoolEntry | None:
        """按主键查询单个账号。

        ``include_deleted=False`` 时把软删账号视为不存在，与 design「Active_Pool 过滤
        与隐私」要求保持一致：迁出 / 软删账号一律按 404 处理。
        """

        query = "SELECT * FROM accounts WHERE id = ?"
        params: list[object] = [account_id]
        if not include_deleted:
            query += " AND deleted_at IS NULL"
        with self._connect(connection) as conn:
            row = conn.execute(query, params).fetchone()
        return _row_to_entry(row) if row is not None else None

    def find_by_unique_key(
        self,
        *,
        student_id: str,
        login_url: str,
        connection: sqlite3.Connection | None = None,
    ) -> AccountPoolEntry | None:
        """按 ``(student_id, login_url)`` 唯一键查询。

        与数据库部分唯一索引 ``UNIQUE(student_id, login_url) WHERE deleted_at IS NULL``
        语义保持一致：永远过滤软删行。批量导入与新建账号在写库前先用本方法判重，命中
        即按 ``DUPLICATE_STUDENT_ID`` 拒绝。
        """

        query = (
            "SELECT * FROM accounts "
            "WHERE student_id = ? AND login_url = ? AND deleted_at IS NULL"
        )
        with self._connect(connection) as conn:
            row = conn.execute(query, (student_id, login_url)).fetchone()
        return _row_to_entry(row) if row is not None else None

    def count_total(
        self,
        *,
        include_deleted: bool = False,
        connection: sqlite3.Connection | None = None,
    ) -> int:
        """统计号池总规模。

        与 ``POOL_CAPACITY = 100`` 校验配合：service 层在创建 / 批量导入 / 迁移前调用
        本方法判定是否还有余量。``include_deleted=False`` 时只统计未软删行，与
        Requirement 1.2 的语义一致。
        """

        query = "SELECT COUNT(*) AS cnt FROM accounts"
        if not include_deleted:
            query += " WHERE deleted_at IS NULL"
        with self._connect(connection) as conn:
            row = conn.execute(query).fetchone()
        return int(row["cnt"]) if row is not None else 0

    def count_active(
        self, *, connection: sqlite3.Connection | None = None
    ) -> int:
        """统计 Active_Pool 规模。

        用于校验可选的 ``ACTIVE_POOL_CAPACITY``；当 service 未显式注入单池上限时，仅
        作为监控指标使用，本身不参与拒绝逻辑（Requirement 2-Q2 默认值）。
        """

        query = (
            "SELECT COUNT(*) AS cnt FROM accounts "
            "WHERE pool_status = ? AND deleted_at IS NULL"
        )
        with self._connect(connection) as conn:
            row = conn.execute(query, (PoolStatus.ACTIVE.value,)).fetchone()
        return int(row["cnt"]) if row is not None else 0

    # ------------------------------ 写入 ------------------------------

    def update_pool_status(
        self,
        account_id: int,
        *,
        pool_status: PoolStatus,
        pool_updated_at: datetime,
        pool_previous: PoolStatus | str,
        suspended_at: datetime | None,
        suspension_expires_at: datetime | None,
        expected_revision: int | None = None,
        connection: sqlite3.Connection | None = None,
    ) -> AccountPoolEntry | None:
        """更新账号的池状态字段并自增 ``revision``。

        参数语义：

        * ``pool_status`` / ``pool_updated_at`` / ``pool_previous``：迁移落地的目标池、
          落地时间（UTC aware）、迁出前的池（首次入池时可传空串）。
        * ``suspended_at`` / ``suspension_expires_at``：仅在 ``pool_status`` 为
          ``SUSPENDED`` 时有意义，且必须同时为非空；否则传 ``None`` 表示清空。
          为防止违反数据库 ``CHECK`` 约束，本方法对入参组合做对称性校验。
        * ``expected_revision``：可选乐观并发版本号。``None`` 表示无并发约束；非空时
          仅当数据库当前 ``revision == expected_revision`` 才更新，并把 ``revision``
          自增 1，否则不更新。

        返回更新后的 :class:`AccountPoolEntry`；若行不存在 / 已软删 / 乐观并发不命中，
        返回 ``None``，由 service 层翻译成 ``AccountNotInActivePool`` 或
        ``RevisionConflict``。
        """

        _validate_suspension_invariant(
            pool_status, suspended_at, suspension_expires_at
        )
        previous_value = (
            pool_previous.value if isinstance(pool_previous, PoolStatus) else pool_previous
        )
        # 在 SQLite 的一条 UPDATE 中，右侧表达式引用的是旧值，所以这里把 UTC 文本
        # 显式绑定两次，分别写入 pool_updated_at 与 updated_at，确保两者同源。
        pool_updated_at_text = _format_utc(pool_updated_at)
        params: list[object] = [
            pool_status.value,
            pool_updated_at_text,
            previous_value,
            _format_utc(suspended_at) if suspended_at is not None else None,
            _format_utc(suspension_expires_at)
            if suspension_expires_at is not None
            else None,
            pool_updated_at_text,
            account_id,
        ]
        query = (
            "UPDATE accounts "
            "SET pool_status = ?, "
            "    pool_updated_at = ?, "
            "    pool_previous = ?, "
            "    suspended_at = ?, "
            "    suspension_expires_at = ?, "
            "    revision = revision + 1, "
            "    updated_at = ? "
            "WHERE id = ? AND deleted_at IS NULL"
        )
        if expected_revision is not None:
            query += " AND revision = ?"
            params.append(expected_revision)

        with self._connect(connection) as conn:
            cursor = conn.execute(query, params)
            if cursor.rowcount == 0:
                return None
            return self.get_by_id(account_id, connection=conn)


# ----------------------------- 模块工具 -----------------------------


def _row_to_entry(row: sqlite3.Row) -> AccountPoolEntry:
    """把数据库行映射为领域对象。

    `row` 必须来自 ``accounts`` 表的 ``SELECT *``，包含 task 1.2 添加的全部新列。
    """

    return AccountPoolEntry(
        account_id=int(row["id"]),
        student_id=str(row["student_id"]),
        display_name=str(row["display_name"] or ""),
        pool_status=PoolStatus(str(row["pool_status"])),
        pool_updated_at=_parse_utc(str(row["pool_updated_at"])),
        pool_previous=str(row["pool_previous"] or ""),
        suspended_at=_parse_optional_utc(row["suspended_at"]),
        suspension_expires_at=_parse_optional_utc(row["suspension_expires_at"]),
        revision=int(row["revision"] or 0),
    )


def _validate_suspension_invariant(
    pool_status: PoolStatus,
    suspended_at: datetime | None,
    suspension_expires_at: datetime | None,
) -> None:
    """校验 suspension 字段与池状态的对称约束。

    与数据库 ``CHECK ((pool_status='suspended') = (suspended_at IS NOT NULL AND
    suspension_expires_at IS NOT NULL))`` 一致：``suspended`` 状态下两字段必须同时存在；
    其它状态下两字段必须同时为空。提前在 Python 层拦截可以给出更可读的错误信息。
    """

    if pool_status is PoolStatus.SUSPENDED:
        if suspended_at is None or suspension_expires_at is None:
            raise ValueError(
                "迁入 suspended 时必须同时提供 suspended_at 与 suspension_expires_at"
            )
    else:
        if suspended_at is not None or suspension_expires_at is not None:
            raise ValueError(
                f"迁入 {pool_status.value} 时必须把 suspended_at 与 "
                "suspension_expires_at 清空"
            )


def _format_utc(value: datetime) -> str:
    """把带时区的 datetime 序列化为 UTC ISO8601 文本（``Z`` 结尾）。"""

    if value.tzinfo is None:
        raise ValueError("datetime 必须带时区，禁止使用 naive datetime")
    aware = value.astimezone(UTC)
    return aware.isoformat().replace("+00:00", "Z")


def _parse_utc(text: str) -> datetime:
    """解析 UTC ISO8601 文本（兼容 ``Z`` 与 ``+00:00`` 后缀）。"""

    raw = text.strip()
    if raw.endswith("Z"):
        raw = raw[:-1] + "+00:00"
    parsed = datetime.fromisoformat(raw)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def _parse_optional_utc(value: object) -> datetime | None:
    """可空时间字段的反序列化。

    SQLite ``NULL`` 在 sqlite3 中读出为 Python ``None``；空串视同未设置。
    """

    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return _parse_utc(text)
