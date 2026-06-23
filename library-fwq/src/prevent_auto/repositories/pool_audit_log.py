"""pool_audit_log 表的追加与查询 Repository.

本模块实现 spec ``account-pool-tri-sync`` 设计文档「Data Models · pool_audit_log 表」
的最小读写能力，覆盖：

* 通过 :meth:`PoolAuditLogRepository.append` 追加单条审计行；写入前必须对 ``payload``
  调用 :func:`prevent_auto.logging.scrub`，确保 ``payload_json`` 列里永远没有密码 /
  token / cookie 等敏感字段（Requirement 9.2、10.2）。
* 通过 :meth:`PoolAuditLogRepository.query` 按 ``account_id`` / 时间范围 /
  ``audit_action`` 组合筛选，结果按 ``created_at DESC, id DESC`` 排序，对应
  Requirement 10.5 的 Web 查询入口需求。

设计要点：

* 所有时间字段统一以 UTC ISO8601 文本（``Z`` 后缀）读写；入参必须是带时区的
  :class:`datetime.datetime`，否则直接抛 :class:`ValueError`，与
  :mod:`prevent_auto.repositories.account_pool` 的 ``_format_utc`` 风格保持一致。
* 默认每个方法自管理 SQLite 连接；同时接受可选的 ``connection`` 注入，便于上层
  service 在「状态变更 + 审计写入」场景共享同一事务（Requirement 4.8、8.5）。
* 仓库层 **不** 抛业务异常：仅对入参做最小自检（敏感字段过滤、时间格式合法）；
  其它语义校验（例如 ``audit_action='migrate'`` 时是否提供了 ``from_pool`` /
  ``to_pool``）由上层 service 决定，避免本层与领域规则耦合。
"""

from __future__ import annotations

import json
import sqlite3
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from typing import Any

from prevent_auto.account_pool.models import (
    ClientKind,
    PoolMigrationTrigger,
    PoolStatus,
)
from prevent_auto.database import connect_database
from prevent_auto.logging import scrub


class PoolAuditAction(str, Enum):
    """``pool_audit_log.audit_action`` 列的合法取值。

    与 design「Data Models · pool_audit_log 表」一致，覆盖：

    * ``migrate``：池迁移（含手动 / 拉黑 / 到期）。
    * ``bulk_import``：批量导入到 Idle_Pool。
    * ``random_pick``：从 Idle_Pool 随机抽取。
    * ``task_upload`` / ``task_upload_rejected``：Automation_Task 上行同步成功 / 失败。
    * ``reaper_tick``：Pool_Reaper_Job 一次扫描（无论是否实际回收账号都写入）。
    * ``blacklist_report``：客户端上报的拉黑事件审计。
    * ``startup_migration``：服务启动时的既有数据迁移钩子审计。

    继承 ``str`` 是为了 JSON 序列化与 SQL 绑定可以直接拿到字面量字符串，禁止新增新值
    会破坏既有日志检索（如有新增需求请同步更新 design）。
    """

    MIGRATE = "migrate"
    BULK_IMPORT = "bulk_import"
    RANDOM_PICK = "random_pick"
    TASK_UPLOAD = "task_upload"
    TASK_UPLOAD_REJECTED = "task_upload_rejected"
    REAPER_TICK = "reaper_tick"
    BLACKLIST_REPORT = "blacklist_report"
    STARTUP_MIGRATION = "startup_migration"


@dataclass(frozen=True)
class PoolAuditLogEntry:
    """单条 ``pool_audit_log`` 记录的领域对象。

    既用作 :meth:`PoolAuditLogRepository.append` 的入参，也用作
    :meth:`PoolAuditLogRepository.query` 的返回项。

    字段语义：

    * ``audit_action`` / ``trigger_source`` / ``operator`` / ``success``：审计行四元组，
      与 design 表结构对齐，``operator`` 为 web 用户名 / token 标签 / ``"system"``。
    * ``account_id`` / ``task_id`` / ``from_pool`` / ``to_pool`` / ``client_kind``：可空
      字段，按 design 表注释填充（如 ``reaper_tick`` 全部为 ``None``、``migrate`` 必填
      ``from_pool`` 与 ``to_pool``）。
    * ``reason``：失败原因或备注，default 空串。
    * ``payload``：任意 JSON 兼容的 ``dict``，``append`` 会先经过 :func:`scrub` 再
      序列化到 ``payload_json``。
    * ``created_at``：UTC aware datetime。``append`` 入参为 ``None`` 时由 repo 填
      ``datetime.now(UTC)``；查询返回值始终非空。
    * ``id``：主键，新追加时为 ``None``，``append`` 返回值与查询结果都会填好。
    """

    audit_action: PoolAuditAction
    trigger_source: PoolMigrationTrigger
    operator: str
    success: bool
    account_id: int | None = None
    task_id: int | None = None
    from_pool: PoolStatus | None = None
    to_pool: PoolStatus | None = None
    client_kind: ClientKind | None = None
    reason: str = ""
    payload: dict[str, Any] = field(default_factory=dict)
    created_at: datetime | None = None
    id: int | None = None


@dataclass(frozen=True)
class PoolAuditLogQuery:
    """:meth:`PoolAuditLogRepository.query` 的过滤条件容器。

    所有字段为 ``None`` 表示该条件不参与过滤。语义：

    * ``account_id``：精确匹配；``None`` 时不过滤；如需匹配「整体记录」（``account_id IS NULL``，
      例如 ``reaper_tick``），可显式传 ``IS_NULL`` 哨兵——目前 service 层暂未需要，
      因此本版本不支持「过滤 NULL」语义。
    * ``audit_action``：单个枚举或枚举序列；序列为空时 :meth:`query` 直接返回空列表，
      避免生成 ``... IN ()`` 这种语法错误。
    * ``created_after`` / ``created_before``：UTC aware datetime；``created_after`` 取
      ``>=``、``created_before`` 取 ``<=``，便于 UI 上闭区间筛选。
    * ``limit``：返回条数上限；``None`` 表示不限制（仍按 DESC 排序）。
    """

    account_id: int | None = None
    audit_action: PoolAuditAction | Sequence[PoolAuditAction] | None = None
    created_after: datetime | None = None
    created_before: datetime | None = None
    limit: int | None = None


class PoolAuditLogRepository:
    """``pool_audit_log`` 表的追加与查询能力。

    与其它 repository 类相同，构造仅持有 ``database_path``；连接生命周期由方法局部
    控制，需要事务共享时由 service 层注入 ``connection``。
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

    # ------------------------------ 写入 ------------------------------

    def append(
        self,
        entry: PoolAuditLogEntry,
        *,
        connection: sqlite3.Connection | None = None,
    ) -> PoolAuditLogEntry:
        """追加一条审计行。

        行为约定：

        * ``payload_json`` 写入前对 ``entry.payload`` 调用 :func:`scrub`，把 password /
          token / cookie 等敏感字段替换为占位符；调用方拿到的返回值同样使用脱敏后的
          ``payload``，避免后续日志再次序列化时复活原文。
        * ``entry.created_at`` 为 ``None`` 时填 ``datetime.now(UTC)``；非空时必须带时区，
          否则抛 :class:`ValueError`。
        * 返回值为新构造的 :class:`PoolAuditLogEntry`，``id`` 与 ``created_at`` 字段填好。
        """

        created_at = entry.created_at or datetime.now(UTC)
        if created_at.tzinfo is None:
            raise ValueError(
                "created_at 必须带时区，禁止使用 naive datetime"
            )
        created_at = created_at.astimezone(UTC)

        scrubbed_payload = scrub(entry.payload)
        if not isinstance(scrubbed_payload, dict):
            # scrub 对非 Mapping 输入按原样返回；为了保证 payload_json 是 JSON 对象，
            # 强制把非 dict 包装到 ``{"value": ...}``。
            scrubbed_payload = {"value": scrubbed_payload}
        payload_json = json.dumps(
            scrubbed_payload, ensure_ascii=False, sort_keys=True
        )

        params = (
            entry.audit_action.value,
            entry.account_id,
            entry.task_id,
            entry.from_pool.value if entry.from_pool is not None else None,
            entry.to_pool.value if entry.to_pool is not None else None,
            entry.trigger_source.value,
            entry.operator,
            entry.client_kind.value if entry.client_kind is not None else None,
            1 if entry.success else 0,
            entry.reason,
            payload_json,
            _format_utc(created_at),
        )
        with self._connect(connection) as conn:
            cursor = conn.execute(
                """
                INSERT INTO pool_audit_log (
                    audit_action, account_id, task_id, from_pool, to_pool,
                    trigger_source, operator, client_kind, success, reason,
                    payload_json, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                params,
            )
            row_id = int(cursor.lastrowid)

        return replace(
            entry,
            id=row_id,
            created_at=created_at,
            payload=scrubbed_payload,
        )

    # ------------------------------ 查询 ------------------------------

    def query(
        self,
        filters: PoolAuditLogQuery | None = None,
        *,
        connection: sqlite3.Connection | None = None,
    ) -> list[PoolAuditLogEntry]:
        """按筛选条件返回审计行，按 ``created_at DESC, id DESC`` 排序。

        ``filters`` 为 ``None`` 时返回全部记录（仍按 DESC 排序）；``filters`` 中的
        ``audit_action`` 同时支持单个枚举与枚举序列：序列形式翻译成 ``... IN (...)``；
        空序列直接返回 ``[]``，避免生成 ``... IN ()`` 这种 SQLite 语法错误。
        """

        filters = filters or PoolAuditLogQuery()
        clauses: list[str] = []
        params: list[object] = []

        if filters.account_id is not None:
            clauses.append("account_id = ?")
            params.append(int(filters.account_id))

        if filters.audit_action is not None:
            actions = _normalize_actions(filters.audit_action)
            if not actions:
                return []
            placeholders = ",".join(["?"] * len(actions))
            clauses.append(f"audit_action IN ({placeholders})")
            params.extend(action.value for action in actions)

        if filters.created_after is not None:
            clauses.append("created_at >= ?")
            params.append(_format_utc(filters.created_after))

        if filters.created_before is not None:
            clauses.append("created_at <= ?")
            params.append(_format_utc(filters.created_before))

        sql = "SELECT * FROM pool_audit_log"
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        sql += " ORDER BY created_at DESC, id DESC"
        if filters.limit is not None:
            limit = int(filters.limit)
            if limit < 0:
                raise ValueError("limit 必须非负整数")
            sql += " LIMIT ?"
            params.append(limit)

        with self._connect(connection) as conn:
            rows = conn.execute(sql, params).fetchall()
        return [_row_to_entry(row) for row in rows]


# ----------------------------- 模块工具 -----------------------------


def _normalize_actions(
    value: PoolAuditAction | Sequence[PoolAuditAction],
) -> tuple[PoolAuditAction, ...]:
    """把 ``audit_action`` 过滤值统一成元组。"""

    if isinstance(value, PoolAuditAction):
        return (value,)
    return tuple(value)


def _row_to_entry(row: sqlite3.Row) -> PoolAuditLogEntry:
    """把数据库行映射为领域对象。

    ``row`` 必须来自 ``pool_audit_log`` 表的 ``SELECT *``。``payload_json`` 列是合法
    JSON 对象时直接反序列化；解析失败或非对象时回退为 ``{"value": <raw>}``，避免抛
    异常打断查询路径（既然写入侧已经控制了序列化，理论上不会触发该回退）。
    """

    payload_text = str(row["payload_json"] or "{}")
    try:
        payload_obj = json.loads(payload_text)
    except json.JSONDecodeError:
        payload_obj = {"value": payload_text}
    if not isinstance(payload_obj, dict):
        payload_obj = {"value": payload_obj}

    from_pool_raw = row["from_pool"]
    to_pool_raw = row["to_pool"]
    client_kind_raw = row["client_kind"]
    account_id_raw = row["account_id"]
    task_id_raw = row["task_id"]

    return PoolAuditLogEntry(
        id=int(row["id"]),
        audit_action=PoolAuditAction(str(row["audit_action"])),
        account_id=int(account_id_raw) if account_id_raw is not None else None,
        task_id=int(task_id_raw) if task_id_raw is not None else None,
        from_pool=PoolStatus(str(from_pool_raw)) if from_pool_raw else None,
        to_pool=PoolStatus(str(to_pool_raw)) if to_pool_raw else None,
        trigger_source=PoolMigrationTrigger(str(row["trigger_source"])),
        operator=str(row["operator"]),
        client_kind=ClientKind(str(client_kind_raw)) if client_kind_raw else None,
        success=bool(int(row["success"])),
        reason=str(row["reason"] or ""),
        payload=payload_obj,
        created_at=_parse_utc(str(row["created_at"])),
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


__all__ = [
    "PoolAuditAction",
    "PoolAuditLogEntry",
    "PoolAuditLogQuery",
    "PoolAuditLogRepository",
]
