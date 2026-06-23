"""``login_status_cache`` 表的 Repository。

本模块为 :class:`prevent_auto.services.account_pool_service.LoginStatusCache`
协议提供 SQLite 持久化实现的底层 CRUD。表结构由
:func:`prevent_auto.database._create_pool_companion_tables` 建好，列定义：

* ``account_id INTEGER PRIMARY KEY``：与 ``accounts.id`` 一对一对应；
  ``FOREIGN KEY ... ON DELETE CASCADE`` 保证账号被硬删除后占位行自动清除。
* ``tracked_at TEXT NOT NULL``：UTC ISO8601 文本（``Z`` 结尾），表示这条占位
  最近一次写入时间；UI 仅渲染「已占位 / 未占位」两态，``tracked_at`` 留作后续
  排查与未来「最近一次刷新登录态」语义升级使用。

设计要点：

* 仅维护 ``account_id`` 占位集合，不存 cookie / session token。生产环境如果
  要扩展为「登录态有效性」，应另起一张存证表 + TTL，本表保持「占位」语义不变。
* 所有方法在内部短连接里执行；单条 ``INSERT OR REPLACE`` / ``DELETE`` 自带原子
  性，事务边界由 SQLite 默认隐式事务承担。``mark`` / ``clear`` 都是幂等操作，
  与现有 :class:`AccountPoolService` 在事务外调用的口径保持一致。
"""

from __future__ import annotations

import sqlite3
from datetime import UTC, datetime
from pathlib import Path

from prevent_auto.database import connect_database


class LoginStatusCacheRepository:
    """``login_status_cache`` 表的 CRUD。

    :class:`prevent_auto.services.account_pool_service.SqliteLoginStatusCache`
    在此之上封装领域语义（``mark_active`` / ``clear`` / ``contains``）。
    Repository 层不直接读取 settings，便于测试时按真实 SQLite 路径注入。
    """

    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    # ------------------------------ 写入 ------------------------------

    def upsert(
        self,
        account_id: int,
        *,
        tracked_at: datetime | None = None,
    ) -> None:
        """把 ``account_id`` 标记为「已占位」。

        重复写入幂等：``INSERT OR REPLACE`` 会刷新 ``tracked_at``。``tracked_at``
        缺省时取 ``datetime.now(UTC)``，调用方一般不需要显式传入。
        """

        timestamp = tracked_at if tracked_at is not None else datetime.now(UTC)
        text = _format_utc(timestamp)
        with connect_database(self.database_path) as conn:
            conn.execute(
                """
                INSERT INTO login_status_cache (account_id, tracked_at)
                VALUES (?, ?)
                ON CONFLICT(account_id) DO UPDATE SET tracked_at = excluded.tracked_at
                """,
                (int(account_id), text),
            )

    def delete(self, account_id: int) -> None:
        """清除 ``account_id`` 的占位行。不存在视为成功。"""

        with connect_database(self.database_path) as conn:
            conn.execute(
                "DELETE FROM login_status_cache WHERE account_id = ?",
                (int(account_id),),
            )

    # ------------------------------ 查询 ------------------------------

    def contains(self, account_id: int) -> bool:
        """判断 ``account_id`` 是否已占位。"""

        with connect_database(self.database_path) as conn:
            row = conn.execute(
                "SELECT 1 FROM login_status_cache WHERE account_id = ? LIMIT 1",
                (int(account_id),),
            ).fetchone()
        return row is not None

    def list_account_ids(self) -> list[int]:
        """返回当前所有占位中的 ``account_id``，按主键升序。

        仅供测试 / 运维排查使用，正常请求路径不要依赖整表扫描。
        """

        with connect_database(self.database_path) as conn:
            rows = conn.execute(
                "SELECT account_id FROM login_status_cache ORDER BY account_id"
            ).fetchall()
        return [int(row["account_id"]) for row in rows]


# ----------------------------- 模块工具 -----------------------------


def _format_utc(value: datetime) -> str:
    """把带时区的 datetime 序列化为 UTC ISO8601 文本（``Z`` 结尾）。"""

    if value.tzinfo is None:
        raise ValueError("datetime 必须带时区，禁止使用 naive datetime")
    aware = value.astimezone(UTC)
    return aware.isoformat().replace("+00:00", "Z")


__all__ = ["LoginStatusCacheRepository"]
