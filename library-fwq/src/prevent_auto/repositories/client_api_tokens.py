"""``client_api_tokens`` 表的 Repository。

本模块负责 spec ``account-pool-tri-sync`` 中「客户端鉴权 token」表的签发、撤销
与按 ``token_hash`` 查询。详见 design「Data Models · client_api_tokens 表」与
「Components and Interfaces · Active_Account_Sync_API · 鉴权与限频」。

设计要点：

* 数据库只保存 ``sha256(token + pepper)`` 的十六进制摘要，**永远不存明文 token**；
  签发动作的明文 token 仅通过 :class:`IssuedClientApiToken` 在内存中一次性返回给
  调用方，调用方负责一次性展示给管理员（参考 design 的「客户端 token 签发管理子
  页面」）。
* ``pepper`` 通过构造器注入（来自 ``settings.account_pool_token_pepper``），repository
  层不直接导入 ``settings`` 模块，便于测试与可替换。
* :py:meth:`ClientApiTokensRepository.find_by_token_hash` 与
  :py:meth:`~ClientApiTokensRepository.find_by_token` 在 token 不存在或已撤销时
  统一返回 ``None``，由上层鉴权中间件翻译成 HTTP ``401``。
* 所有时间字段统一以 UTC aware :class:`~datetime.datetime` 进出，落库时序列化为
  ``...Z`` 结尾的 ISO8601 文本，与 ``repositories.account_pool`` 保持一致。
* 每个方法默认在内部短连接里执行；同时接受可选的 ``connection`` 注入，便于上层
  service 在「签发 token + 写审计」等场景共享同一事务。
"""

from __future__ import annotations

import hashlib
import secrets
import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from prevent_auto.account_pool.models import ClientKind
from prevent_auto.database import connect_database


@dataclass(frozen=True)
class ClientApiToken:
    """``client_api_tokens`` 表的领域对象。

    仅包含可安全展示的字段（不含明文 token）；可由 Web 管理页面、审计日志等任意
    地方持有与序列化。

    ``token_preview`` 是签发瞬间按 ``head…tail`` 格式留下的脱敏预览（前 6 后 4），
    用于管理页面在签发后帮助管理员识别 / 比对手里的明文 token；它本身信息熵远
    低于完整 token，无法用于鉴权。存量记录无明文可推导，``token_preview`` 保持
    空串。
    """

    id: int
    label: str
    client_kind: ClientKind
    token_hash: str
    token_preview: str
    created_at: datetime
    revoked_at: datetime | None


@dataclass(frozen=True)
class IssuedClientApiToken:
    """签发动作返回的「记录 + 明文 token」组合。

    ``raw_token`` 仅在签发瞬间存在，由调用方一次性展示给管理员；不要写入数据库、
    审计日志或长期内存缓存。:class:`ClientApiToken` 部分则可以安全地长期持有。
    """

    record: ClientApiToken
    raw_token: str


def hash_token(token: str, pepper: str) -> str:
    """计算 ``token_hash`` 列的取值。

    与 design「Data Models · client_api_tokens 表」中的约定一致：
    ``sha256(token + pepper)`` 的十六进制小写摘要。``pepper`` 由调用方传入，
    本函数不读取 settings，便于在测试中注入固定值。
    """

    return hashlib.sha256((token + pepper).encode("utf-8")).hexdigest()


# ``token_preview`` 头尾位数；前 6 后 4 是常见的脱敏宽度（GitHub PAT、AWS access
# key 等都按这个量级），既保留可识别性，又远小于完整 token 的暴力枚举空间。
_TOKEN_PREVIEW_HEAD = 6
_TOKEN_PREVIEW_TAIL = 4
_TOKEN_PREVIEW_ELLIPSIS = "…"


def build_token_preview(raw_token: str) -> str:
    """生成 ``token_preview`` 列的取值。

    形如 ``"abcdef…wxyz"``：取前 :data:`_TOKEN_PREVIEW_HEAD` 字符 + 中间省略号 +
    后 :data:`_TOKEN_PREVIEW_TAIL` 字符。当原始 token 长度不足以同时容纳头尾时，
    退化为「全文展示」（实际签发的 token 至少 22 个字符，远超阈值，这只是兜底）。
    """

    head = _TOKEN_PREVIEW_HEAD
    tail = _TOKEN_PREVIEW_TAIL
    if len(raw_token) <= head + tail:
        return raw_token
    return f"{raw_token[:head]}{_TOKEN_PREVIEW_ELLIPSIS}{raw_token[-tail:]}"


def generate_raw_token(num_bytes: int = 32) -> str:
    """生成一段密码学安全的随机原文 token。

    ``num_bytes`` 默认 32，对应约 43 个 base64url 字符；调用方可根据需要调整长度，
    但不应低于 16，以避免暴力枚举。
    """

    if num_bytes < 16:
        raise ValueError("num_bytes 必须 >= 16，否则 token 强度不足")
    return secrets.token_urlsafe(num_bytes)


class ClientApiTokensRepository:
    """``client_api_tokens`` 表的 CRUD。

    负责 token 的签发、撤销、列表查询与按 hash / 明文反查；不参与 HTTP 鉴权决策本身，
    决策由调用此 repository 的中间件完成。
    """

    def __init__(self, database_path: str | Path, *, token_pepper: str) -> None:
        if not token_pepper:
            raise ValueError("token_pepper 不能为空")
        self.database_path = Path(database_path)
        self._token_pepper = token_pepper

    @contextmanager
    def _connect(
        self, connection: sqlite3.Connection | None
    ) -> Iterator[sqlite3.Connection]:
        """获取一个 SQLite 连接。

        与 :class:`~prevent_auto.repositories.account_pool.AccountPoolRepository`
        保持相同的语义：注入连接时不接管事务边界，否则在内部短连接中执行。
        """

        if connection is not None:
            yield connection
            return
        with connect_database(self.database_path) as conn:
            yield conn

    # ------------------------------ 签发 / 撤销 ------------------------------

    def issue(
        self,
        *,
        label: str,
        client_kind: ClientKind | str,
        raw_token: str | None = None,
        created_at: datetime | None = None,
        connection: sqlite3.Connection | None = None,
    ) -> IssuedClientApiToken:
        """签发一条新的客户端 token 记录。

        参数语义：

        * ``label``：人类可读的备注，用于管理页面展示与审计 ``operator`` 字段。要求
          非空。
        * ``client_kind``：token 归属的客户端类型；接受 :class:`ClientKind` 或其字面量
          字符串。
        * ``raw_token``：明文 token；缺省时由 :func:`generate_raw_token` 生成。**调用方
          必须自己保管返回值，repository 不再有第二次取出明文的渠道**。
        * ``created_at``：写入的 UTC aware 时间戳；缺省时取 ``datetime.now(UTC)``。

        返回包含数据库记录与明文 token 的组合对象；明文仅用于一次性展示。
        """

        if not label or not label.strip():
            raise ValueError("label 不能为空")
        kind_value = _normalize_client_kind(client_kind)
        token = raw_token if raw_token is not None else generate_raw_token()
        if not token:
            raise ValueError("raw_token 不能为空")
        token_hash = hash_token(token, self._token_pepper)
        token_preview = build_token_preview(token)
        created = created_at if created_at is not None else datetime.now(UTC)
        created_text = _format_utc(created)
        with self._connect(connection) as conn:
            cursor = conn.execute(
                """
                INSERT INTO client_api_tokens (
                    label, client_kind, token_hash, token_preview,
                    created_at, revoked_at
                )
                VALUES (?, ?, ?, ?, ?, NULL)
                """,
                (
                    label.strip(),
                    kind_value,
                    token_hash,
                    token_preview,
                    created_text,
                ),
            )
            row_id = int(cursor.lastrowid)
            row = conn.execute(
                "SELECT * FROM client_api_tokens WHERE id = ?",
                (row_id,),
            ).fetchone()
        if row is None:  # pragma: no cover - 仅为类型收敛
            raise RuntimeError("INSERT 后立即查询不应返回空")
        return IssuedClientApiToken(record=_row_to_token(row), raw_token=token)

    def revoke(
        self,
        token_id: int,
        *,
        revoked_at: datetime | None = None,
        connection: sqlite3.Connection | None = None,
    ) -> ClientApiToken | None:
        """把指定 token 标记为已撤销。

        ``revoked_at`` 缺省时取 ``datetime.now(UTC)``；如果记录不存在或已经被撤销，
        返回 ``None``，由上层决定是 404 还是幂等成功。
        """

        revoked = revoked_at if revoked_at is not None else datetime.now(UTC)
        revoked_text = _format_utc(revoked)
        with self._connect(connection) as conn:
            cursor = conn.execute(
                """
                UPDATE client_api_tokens
                SET revoked_at = ?
                WHERE id = ? AND revoked_at IS NULL
                """,
                (revoked_text, token_id),
            )
            if cursor.rowcount == 0:
                return None
            row = conn.execute(
                "SELECT * FROM client_api_tokens WHERE id = ?",
                (token_id,),
            ).fetchone()
        return _row_to_token(row) if row is not None else None

    # ------------------------------ 查询 ------------------------------

    def list(
        self,
        *,
        include_revoked: bool = False,
        client_kind: ClientKind | str | None = None,
        connection: sqlite3.Connection | None = None,
    ) -> list[ClientApiToken]:
        """列出 token 记录。

        默认仅返回未撤销 token（``include_revoked=False``）；管理页面查看历史时可显式
        传 ``True``。``client_kind`` 用于按客户端类型过滤，缺省返回全部。
        排序按 ``created_at DESC, id DESC``，与「最近签发优先」语义一致。
        """

        clauses: list[str] = []
        params: list[object] = []
        if not include_revoked:
            clauses.append("revoked_at IS NULL")
        if client_kind is not None:
            clauses.append("client_kind = ?")
            params.append(_normalize_client_kind(client_kind))
        query = "SELECT * FROM client_api_tokens"
        if clauses:
            query += " WHERE " + " AND ".join(clauses)
        query += " ORDER BY created_at DESC, id DESC"
        with self._connect(connection) as conn:
            rows = conn.execute(query, params).fetchall()
        return [_row_to_token(row) for row in rows]

    def get_by_id(
        self,
        token_id: int,
        *,
        include_revoked: bool = True,
        connection: sqlite3.Connection | None = None,
    ) -> ClientApiToken | None:
        """按主键查询单条记录。

        默认 ``include_revoked=True``：管理页面查看 / 撤销时仍需要看到已撤销项；
        鉴权路径请使用 :py:meth:`find_by_token_hash`。
        """

        query = "SELECT * FROM client_api_tokens WHERE id = ?"
        params: list[object] = [token_id]
        if not include_revoked:
            query += " AND revoked_at IS NULL"
        with self._connect(connection) as conn:
            row = conn.execute(query, params).fetchone()
        return _row_to_token(row) if row is not None else None

    def delete(
        self,
        token_id: int,
        *,
        connection: sqlite3.Connection | None = None,
    ) -> bool:
        """删除指定 token 记录。

        仅允许删除已撤销的 token；未撤销的 token 不可直接删除（需先撤销）。
        返回 ``True`` 表示删除成功，``False`` 表示记录不存在或未被撤销。
        """

        with self._connect(connection) as conn:
            cursor = conn.execute(
                "DELETE FROM client_api_tokens WHERE id = ? AND revoked_at IS NOT NULL",
                (token_id,),
            )
            return cursor.rowcount > 0

    def delete_all_revoked(
        self,
        *,
        connection: sqlite3.Connection | None = None,
    ) -> int:
        """删除所有已撤销的 token 记录，返回删除的行数。"""

        with self._connect(connection) as conn:
            cursor = conn.execute(
                "DELETE FROM client_api_tokens WHERE revoked_at IS NOT NULL",
            )
            return cursor.rowcount

    def find_by_token_hash(
        self,
        token_hash: str,
        *,
        connection: sqlite3.Connection | None = None,
    ) -> ClientApiToken | None:
        """按 ``token_hash`` 查询有效 token。

        命中且未撤销时返回记录；不存在或已被撤销时返回 ``None``。鉴权中间件应在
        命中 ``None`` 时返回 HTTP ``401``。
        """

        query = (
            "SELECT * FROM client_api_tokens "
            "WHERE token_hash = ? AND revoked_at IS NULL"
        )
        with self._connect(connection) as conn:
            row = conn.execute(query, (token_hash,)).fetchone()
        return _row_to_token(row) if row is not None else None

    def find_by_token(
        self,
        raw_token: str,
        *,
        connection: sqlite3.Connection | None = None,
    ) -> ClientApiToken | None:
        """按明文 token 查询有效记录。

        内部计算 ``sha256(token + pepper)`` 后委托给 :py:meth:`find_by_token_hash`；
        当 ``raw_token`` 为空字符串时直接返回 ``None``，避免对空 token 走一次 SQL。
        """

        if not raw_token:
            return None
        token_hash = hash_token(raw_token, self._token_pepper)
        return self.find_by_token_hash(token_hash, connection=connection)


# ----------------------------- 模块工具 -----------------------------


def _row_to_token(row: sqlite3.Row) -> ClientApiToken:
    """把数据库行映射为领域对象。"""

    keys = row.keys() if hasattr(row, "keys") else []
    preview_value = (
        str(row["token_preview"])
        if "token_preview" in keys and row["token_preview"] is not None
        else ""
    )
    return ClientApiToken(
        id=int(row["id"]),
        label=str(row["label"]),
        client_kind=ClientKind(str(row["client_kind"])),
        token_hash=str(row["token_hash"]),
        token_preview=preview_value,
        created_at=_parse_utc(str(row["created_at"])),
        revoked_at=_parse_optional_utc(row["revoked_at"]),
    )


def _normalize_client_kind(value: ClientKind | str) -> str:
    """把 :class:`ClientKind` / 字面量字符串规范化为数据库列取值。"""

    if isinstance(value, ClientKind):
        return value.value
    return ClientKind(str(value)).value


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
    """可空时间字段的反序列化。"""

    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return _parse_utc(text)
