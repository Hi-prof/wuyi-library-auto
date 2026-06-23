"""``automation_tasks`` 表的仓库层。

设计要点（与 design.md「Data Models · automation_tasks」一节对齐）：

* 全部时间字段以 UTC ISO8601 文本读写；落库前由仓库统一格式化，反序列化时强制
  按 UTC aware ``datetime`` 输出，避免上游误把本地时区当成 UTC。
* ``revision`` 由仓库统一自增；调用方仅传入 ``expected_revision``，命中冲突时一律
  抛 :class:`prevent_auto.account_pool.models.RevisionConflict`，并携带服务端最新
  ``revision`` 与一份完整的 ``server_payload``，便于 REST 层翻译为
  ``409 {"reason":"revision_conflict","server_revision":...,"server_payload":{...}}``。
* 软删通过 ``deleted_at`` 列；``list_for_account`` 默认 ``include_deleted=False``，过滤
  ``deleted_at IS NOT NULL`` 的行（与 design 8-Q3 默认值一致）。
* 全部方法接受可选的 ``connection`` 参数。``None`` 时仓库自管理一个 ``with``
  包装的连接、由 sqlite3 默认事务管理器 commit / rollback；非 ``None`` 时不做
  提交，把 audit 写入与持久化合并到 service 层控制的同一事务里（design Flow 2
  「持久化阶段失败回滚审计」要求）。
* 字段语义校验（``mode`` 取值、``room_name`` 长度等）按 design 8-Q4 默认值不在
  仓库层做，由 REST 层 Pydantic 模型兜底；仓库只确保 ``revision`` 与软删的不变量。
"""

from __future__ import annotations

import json
import sqlite3
from collections.abc import Iterable, Iterator
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from prevent_auto.account_pool.models import (
    AutomationTask,
    CustomWindow,
    RevisionConflict,
)
from prevent_auto.database import connect_database


class AutomationTasksRepository:
    """``automation_tasks`` 表的 CRUD 入口。

    所有写入方法 (`upsert_with_revision` / `soft_delete`) 都按 ``expected_revision``
    做乐观并发控制，调用方只需要把客户端 ``If-Match`` 风格的 revision 直接传入。
    """

    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    # ---------- 读 ----------

    def list_for_account(
        self,
        account_id: int,
        *,
        include_deleted: bool = False,
        connection: sqlite3.Connection | None = None,
    ) -> list[AutomationTask]:
        """按 ``account_id`` 列出 ``AutomationTask``。

        默认 ``include_deleted=False`` 时不返回 ``deleted_at IS NOT NULL`` 的行；
        结果按 ``id ASC`` 稳定排序，方便客户端做幂等比对与冲突解决 UI 渲染。
        """
        sql = "SELECT * FROM automation_tasks WHERE account_id = ?"
        params: tuple[object, ...] = (account_id,)
        if not include_deleted:
            sql += " AND deleted_at IS NULL"
        sql += " ORDER BY id ASC"
        with self._use_connection(connection) as conn:
            rows = conn.execute(sql, params).fetchall()
        return [_row_to_task(row) for row in rows]

    def get(
        self,
        account_id: int,
        task_id: int,
        *,
        include_deleted: bool = False,
        connection: sqlite3.Connection | None = None,
    ) -> AutomationTask | None:
        """按 ``(account_id, task_id)`` 取单行；命中返回 ``AutomationTask``，否则 ``None``。

        当 service 层需要做 ``revision`` 校验时应传 ``include_deleted=True``，避免
        「软删行用同一 revision 重复 PUT 应该 409」的语义被错误降级（design Property 13）。
        """
        sql = "SELECT * FROM automation_tasks WHERE account_id = ? AND id = ?"
        params: tuple[object, ...] = (account_id, task_id)
        if not include_deleted:
            sql += " AND deleted_at IS NULL"
        with self._use_connection(connection) as conn:
            row = conn.execute(sql, params).fetchone()
        return _row_to_task(row) if row is not None else None

    # ---------- 写 ----------

    def upsert_with_revision(
        self,
        *,
        account_id: int,
        task_id: int | None,
        room_name: str,
        seat_number: str,
        mode: str,
        custom_windows: Iterable[CustomWindow],
        enabled: bool,
        expected_revision: int,
        connection: sqlite3.Connection | None = None,
    ) -> AutomationTask:
        """以乐观并发语义新增或更新一行。

        路径：

        * ``task_id is None``：服务端 ``AUTOINCREMENT`` 分配 ``id``。``expected_revision``
          必须为 ``0``，否则抛 :class:`RevisionConflict(0, {})`。新行 ``revision`` 落 ``1``。
        * ``task_id is not None``：

          - 行存在且 ``revision == expected_revision``：``UPDATE``，``revision += 1``、
            ``updated_at = now_utc``、``deleted_at = NULL``（允许在同一 revision 下「恢复」
            一条软删行）。
          - 行存在但 ``revision != expected_revision``：抛 :class:`RevisionConflict`，
            ``server_revision`` 与 ``server_payload`` 取该行真实值。
          - 行不存在且 ``expected_revision == 0``：``INSERT`` 显式 ``id=task_id``，``revision=1``，
            匹配 ``PUT .../automation-tasks/{task_id}`` 的 upsert 路径。
          - 行不存在但 ``expected_revision != 0``：抛 :class:`RevisionConflict(0, {})`。

        仓库不做业务字段校验（design 8-Q4 默认值），但所有时间字段以 UTC ISO8601
        写入，``custom_windows`` 通过 :func:`_serialize_custom_windows` 序列化。
        """
        custom_windows_json = _serialize_custom_windows(custom_windows)
        now_utc = _now_utc_iso()
        with self._use_connection(connection) as conn:
            if task_id is None:
                if expected_revision != 0:
                    raise RevisionConflict(server_revision=0, server_payload={})
                cursor = conn.execute(
                    """
                    INSERT INTO automation_tasks (
                        account_id, room_name, seat_number, mode,
                        custom_windows_json, enabled, revision,
                        created_at, updated_at, deleted_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """,
                    (
                        account_id,
                        room_name,
                        seat_number,
                        mode,
                        custom_windows_json,
                        1 if enabled else 0,
                        1,
                        now_utc,
                        now_utc,
                    ),
                )
                new_id = int(cursor.lastrowid)
                row = conn.execute(
                    "SELECT * FROM automation_tasks WHERE id = ?",
                    (new_id,),
                ).fetchone()
            else:
                row = conn.execute(
                    "SELECT * FROM automation_tasks WHERE account_id = ? AND id = ?",
                    (account_id, task_id),
                ).fetchone()
                if row is None:
                    if expected_revision != 0:
                        raise RevisionConflict(
                            server_revision=0, server_payload={}
                        )
                    conn.execute(
                        """
                        INSERT INTO automation_tasks (
                            id, account_id, room_name, seat_number, mode,
                            custom_windows_json, enabled, revision,
                            created_at, updated_at, deleted_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                        (
                            task_id,
                            account_id,
                            room_name,
                            seat_number,
                            mode,
                            custom_windows_json,
                            1 if enabled else 0,
                            1,
                            now_utc,
                            now_utc,
                        ),
                    )
                else:
                    current_revision = int(row["revision"])
                    if current_revision != expected_revision:
                        raise RevisionConflict(
                            server_revision=current_revision,
                            server_payload=_row_to_payload(row),
                        )
                    new_revision = current_revision + 1
                    conn.execute(
                        """
                        UPDATE automation_tasks
                        SET room_name = ?, seat_number = ?, mode = ?,
                            custom_windows_json = ?, enabled = ?,
                            revision = ?, updated_at = ?, deleted_at = NULL
                        WHERE account_id = ? AND id = ?
                        """,
                        (
                            room_name,
                            seat_number,
                            mode,
                            custom_windows_json,
                            1 if enabled else 0,
                            new_revision,
                            now_utc,
                            account_id,
                            task_id,
                        ),
                    )
                row = conn.execute(
                    "SELECT * FROM automation_tasks WHERE id = ?",
                    (task_id,),
                ).fetchone()
        assert row is not None  # 上一步刚 INSERT/UPDATE，理论必命中
        return _row_to_task(row)

    def soft_delete(
        self,
        *,
        account_id: int,
        task_id: int,
        expected_revision: int,
        connection: sqlite3.Connection | None = None,
    ) -> AutomationTask:
        """以乐观并发语义软删除一行。

        * 行不存在：抛 :class:`RevisionConflict(0, {})`。
        * ``revision != expected_revision``：抛 :class:`RevisionConflict`，
          ``server_revision`` 与 ``server_payload`` 取真实值（含已被设置的 ``deleted_at``）。
        * 否则 ``UPDATE``：``deleted_at = now_utc``、``revision += 1``、``updated_at = now_utc``。

        允许对已软删行再次 ``soft_delete`` 当且仅当 ``expected_revision`` 命中，使
        ``revision`` 保持严格单调（与 design Property 13「同一 revision 不可复用」一致）。
        """
        now_utc = _now_utc_iso()
        with self._use_connection(connection) as conn:
            row = conn.execute(
                "SELECT * FROM automation_tasks WHERE account_id = ? AND id = ?",
                (account_id, task_id),
            ).fetchone()
            if row is None:
                raise RevisionConflict(server_revision=0, server_payload={})
            current_revision = int(row["revision"])
            if current_revision != expected_revision:
                raise RevisionConflict(
                    server_revision=current_revision,
                    server_payload=_row_to_payload(row),
                )
            new_revision = current_revision + 1
            conn.execute(
                """
                UPDATE automation_tasks
                SET deleted_at = ?, revision = ?, updated_at = ?
                WHERE account_id = ? AND id = ?
                """,
                (now_utc, new_revision, now_utc, account_id, task_id),
            )
            row = conn.execute(
                "SELECT * FROM automation_tasks WHERE id = ?",
                (task_id,),
            ).fetchone()
        assert row is not None
        return _row_to_task(row)

    # ---------- 工具 ----------

    @contextmanager
    def _use_connection(
        self, connection: sqlite3.Connection | None
    ) -> Iterator[sqlite3.Connection]:
        """复用既有连接（由调用方控制提交），或自管理一个 ``with`` 包装的连接。

        ``connection is None`` 时使用 :func:`connect_database` 上下文，由 sqlite3
        默认事务管理器在退出时 commit / rollback；``connection is not None`` 时
        不做提交，让 service 层把多张表的写入合并到同一事务。
        """
        if connection is not None:
            yield connection
            return
        with connect_database(self.database_path) as conn:
            yield conn


# ---------- 序列化 / 反序列化 ----------


def _serialize_custom_windows(windows: Iterable[CustomWindow]) -> str:
    """把 ``CustomWindow`` 序列化为 ``custom_windows_json`` 列文本。

    顺序保留，便于客户端按相同顺序渲染；不做任何业务校验（如时段重叠 / 跨日），
    与 design 8-Q4 默认值一致。
    """
    payload = [
        {
            "date": window.date,
            "start_hour": window.start_hour,
            "end_hour": window.end_hour,
        }
        for window in windows
    ]
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def _deserialize_custom_windows(text: str | None) -> tuple[CustomWindow, ...]:
    if not text:
        return ()
    raw = json.loads(text)
    return tuple(
        CustomWindow(
            date=str(item.get("date", "")),
            start_hour=int(item.get("start_hour", 0)),
            end_hour=int(item.get("end_hour", 0)),
        )
        for item in raw
    )


def _parse_utc_iso(value: str | None) -> datetime | None:
    """解析 UTC ISO8601 文本为 UTC aware ``datetime``；空值返回 ``None``。

    兼容 ``2026-04-26T08:30:00Z`` / ``2026-04-26T08:30:00+00:00`` 两种写法；
    历史遗留数据若缺失时区信息（迁移阶段可能存在），按 UTC 解释而不是本地时区。
    """
    if value is None or value == "":
        return None
    text = value
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _now_utc_iso() -> str:
    """生成 ``YYYY-MM-DDTHH:MM:SS+00:00`` 格式的 UTC ISO8601 文本。

    截掉微秒，避免不同 SQLite 版本下文本比对受 µs 影响；服务端入库精度足够。
    """
    return datetime.now(tz=timezone.utc).replace(microsecond=0).isoformat()


def _row_to_task(row: sqlite3.Row) -> AutomationTask:
    created_at = _parse_utc_iso(row["created_at"])
    updated_at = _parse_utc_iso(row["updated_at"])
    assert created_at is not None and updated_at is not None
    return AutomationTask(
        task_id=int(row["id"]),
        account_id=int(row["account_id"]),
        room_name=str(row["room_name"]),
        seat_number=str(row["seat_number"]),
        mode=str(row["mode"]),
        custom_windows=_deserialize_custom_windows(row["custom_windows_json"]),
        enabled=bool(int(row["enabled"])),
        revision=int(row["revision"]),
        created_at=created_at,
        updated_at=updated_at,
        deleted_at=_parse_utc_iso(row["deleted_at"]),
    )


def _row_to_payload(row: sqlite3.Row) -> dict:
    """把行转成 :attr:`RevisionConflict.server_payload` 用的载荷字典。

    格式与 REST 上行 PUT 请求体保持一致（外加只读字段 ``task_id`` / ``revision`` /
    ``updated_at`` / ``deleted_at``），便于客户端做冲突解决 UI。
    """
    raw_windows = json.loads(row["custom_windows_json"] or "[]") or []
    return {
        "task_id": int(row["id"]),
        "account_id": int(row["account_id"]),
        "room_name": str(row["room_name"]),
        "seat_number": str(row["seat_number"]),
        "mode": str(row["mode"]),
        "custom_windows": [
            {
                "date": str(item.get("date", "")),
                "start_hour": int(item.get("start_hour", 0)),
                "end_hour": int(item.get("end_hour", 0)),
            }
            for item in raw_windows
        ],
        "enabled": bool(int(row["enabled"])),
        "revision": int(row["revision"]),
        "updated_at": str(row["updated_at"]),
        "deleted_at": row["deleted_at"],
    }
