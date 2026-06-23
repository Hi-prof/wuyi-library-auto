"""prevent_auto 数据库初始化与启动迁移钩子。

本模块负责：

1. 创建 / 打开 SQLite 数据库连接并启用外键约束。
2. ``initialize_database`` 启动钩子：建表、补列、创建号池相关新表与索引。
3. ``_ensure_account_pool_columns``：为 ``account-pool-tri-sync`` 特性扩展
   ``accounts`` 表（三池字段、AES-GCM 密文列、软删除等）；新增
   ``automation_tasks`` / ``pool_audit_log`` / ``client_api_tokens`` 三张新表与
   对应索引；把旧 ``password TEXT`` 内容用 ``ACCOUNT_POOL_SECRET_KEY`` 加密
   迁移到 ``password_cipher / password_nonce / password_tag``；并写一条
   ``audit_action='startup_migration'`` 审计行。

设计要点：

- 整个号池迁移钩子在单个事务（``BEGIN`` / ``COMMIT`` / ``ROLLBACK``）内完成；
  任何步骤失败则回滚并向上抛出，让 FastAPI lifespan 启动失败可见。
- SQLite 不支持 ``ALTER TABLE`` 后追加表级 ``CHECK`` 约束、也不能在不重建表
  的前提下加表级 ``UNIQUE``；为避免与外键引用账号 id 的若干表（``monitor_records``、
  ``rebook_jobs``、``action_logs`` 等）的迁移风险，我们采用 design 中给出的
  「partial UNIQUE INDEX」方案 —— ``UNIQUE (student_id, login_url) WHERE
  deleted_at IS NULL`` 由部分唯一索引落地；表级 CHECK 约束（``pool_status``
  取值集合、``suspended_at`` / ``suspension_expires_at`` 与 ``pool_status`` 的
  互斥蕴含）由服务层在 ``account_pool_service.migrate`` 等写入路径统一兜底，
  并在 design 中显式声明（详见 ``Data Models`` 章节与 tasks.md task 1.2 备注）。
- 容量超限只打 WARN，不抛异常（满足 Requirement 11.4）。
"""

from __future__ import annotations

import json
import logging
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from prevent_auto.account_pool.constants import POOL_CAPACITY
from prevent_auto.account_pool.models import PoolMigrationTrigger, PoolStatus
from prevent_auto.services.account_password_cipher import AccountPasswordCipher
from prevent_auto.settings import PreventAutoSettings, load_settings


logger = logging.getLogger(__name__)


def connect_database(database_path: str | Path) -> sqlite3.Connection:
    connection = sqlite3.connect(Path(database_path), check_same_thread=False)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def initialize_database(
    database_path: str | Path,
    *,
    settings: PreventAutoSettings | None = None,
) -> None:
    """建库、补列、并执行号池启动迁移。

    ``settings`` 由调用方注入（见 ``web/app.py``）；若不传则按 ``load_settings()``
    取，便于既有的单元测试沿用。``ACCOUNT_POOL_SECRET_KEY`` 缺失时走非加密路径
    （仅在测试环境合法），生产 / 启动阶段会因 settings 加载顺序在更早处暴露。
    """

    path = Path(database_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    restrict_private_path(path.parent, directory=True)
    resolved_settings = settings if settings is not None else _load_settings_safely()
    with connect_database(path) as connection:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                student_id TEXT NOT NULL,
                password TEXT NOT NULL,
                login_url TEXT NOT NULL,
                seat_url TEXT NOT NULL,
                rebook_enabled INTEGER NOT NULL DEFAULT 0,
                rebook_trigger_minutes INTEGER NOT NULL DEFAULT 5,
                last_detected_room_name TEXT NOT NULL DEFAULT '',
                last_detected_seat_number TEXT NOT NULL DEFAULT '',
                last_detected_booking_start_at TEXT,
                last_detected_booking_status TEXT NOT NULL DEFAULT '',
                state_file TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                account_status TEXT NOT NULL DEFAULT 'unknown',
                last_check_at TEXT,
                last_status TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS monitor_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                booking_id TEXT NOT NULL,
                booking_status TEXT NOT NULL,
                booking_start_at TEXT,
                detected_at TEXT NOT NULL,
                decision TEXT NOT NULL,
                detail TEXT NOT NULL,
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS rebook_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                source_booking_id TEXT NOT NULL,
                target_date TEXT NOT NULL,
                target_start_hour INTEGER NOT NULL,
                target_end_hour INTEGER NOT NULL,
                room_name TEXT NOT NULL,
                seat_number TEXT NOT NULL,
                seat_id TEXT NOT NULL DEFAULT '',
                run_at TEXT NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS action_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                action_type TEXT NOT NULL,
                success INTEGER NOT NULL,
                message TEXT NOT NULL,
                payload_json TEXT NOT NULL DEFAULT '{}',
                created_at TEXT NOT NULL,
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            );

            -- 号池管理页活跃池表格的预约视图缓存：每次状态检测把 fetch_bookings
            -- 的全量结果按账号整体替换写入；UI 读这张表，避免每次打开页面再去
            -- 实时调用学校接口造成的卡顿。daily_status_refresher（每天 8:10）
            -- 与「刷新预约位置」按钮触发的 MonitorLoop 周期会刷新这里的内容。
            CREATE TABLE IF NOT EXISTS booking_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                booking_id TEXT NOT NULL,
                room_name TEXT NOT NULL,
                seat_number TEXT NOT NULL,
                status TEXT NOT NULL,
                start_time INTEGER NOT NULL,
                duration_seconds INTEGER NOT NULL,
                checkin_deadline_at INTEGER,
                refreshed_at TEXT NOT NULL,
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_booking_snapshots_account
            ON booking_snapshots(account_id);

            CREATE TABLE IF NOT EXISTS account_login_states (
                account_id INTEGER PRIMARY KEY,
                state_file TEXT NOT NULL,
                state_json TEXT NOT NULL,
                refreshed_at TEXT NOT NULL,
                FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_account_login_states_refreshed_at
            ON account_login_states(refreshed_at);

            CREATE TABLE IF NOT EXISTS app_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_monitor_records_account_detected_at
            ON monitor_records(account_id, detected_at DESC);

            CREATE INDEX IF NOT EXISTS idx_monitor_records_detected_at
            ON monitor_records(detected_at);

            CREATE INDEX IF NOT EXISTS idx_action_logs_account_created_at
            ON action_logs(account_id, created_at DESC);

            CREATE INDEX IF NOT EXISTS idx_action_logs_created_at
            ON action_logs(created_at);

            CREATE INDEX IF NOT EXISTS idx_rebook_jobs_status_run_at
            ON rebook_jobs(status, run_at);

            CREATE INDEX IF NOT EXISTS idx_rebook_jobs_created_at
            ON rebook_jobs(created_at);
            """
        )
        _ensure_account_columns(connection)
        _ensure_account_pool_columns(connection, resolved_settings)
    restrict_private_path(path, directory=False)


def restrict_private_path(path: str | Path, *, directory: bool) -> None:
    if os.name != "posix":
        return
    path = Path(path)
    path.chmod(0o700 if directory else 0o600)


def _load_settings_safely() -> PreventAutoSettings | None:
    """尝试加载 settings，捕获鉴权覆盖校验异常以便单元测试沿用旧入口。

    单元测试习惯直接 ``initialize_database(path)``，此时进程环境可能没有设置
    ``ACCOUNT_POOL_SECRET_KEY`` 也没有覆盖鉴权变量；这种场景下号池迁移退化为
    「不做密码加密」（仅 ALTER TABLE 与新表 DDL）。生产路径由 ``web/app.py``
    显式注入 settings，确保密钥存在。
    """

    try:
        return load_settings()
    except ValueError:
        return None


def _ensure_account_columns(connection: sqlite3.Connection) -> None:
    existing_columns = {
        row["name"] for row in connection.execute("PRAGMA table_info(accounts)").fetchall()
    }
    statements: list[str] = []
    if "rebook_enabled" not in existing_columns:
        statements.append("ALTER TABLE accounts ADD COLUMN rebook_enabled INTEGER")
    if "rebook_trigger_minutes" not in existing_columns:
        statements.append("ALTER TABLE accounts ADD COLUMN rebook_trigger_minutes INTEGER")
    if "last_detected_room_name" not in existing_columns:
        statements.append(
            "ALTER TABLE accounts ADD COLUMN last_detected_room_name TEXT NOT NULL DEFAULT ''"
        )
    if "last_detected_seat_number" not in existing_columns:
        statements.append(
            "ALTER TABLE accounts ADD COLUMN last_detected_seat_number TEXT NOT NULL DEFAULT ''"
        )
    if "last_detected_booking_start_at" not in existing_columns:
        statements.append(
            "ALTER TABLE accounts ADD COLUMN last_detected_booking_start_at TEXT"
        )
    if "last_detected_booking_status" not in existing_columns:
        statements.append(
            "ALTER TABLE accounts ADD COLUMN last_detected_booking_status TEXT NOT NULL DEFAULT ''"
        )
    if "account_status" not in existing_columns:
        statements.append(
            "ALTER TABLE accounts ADD COLUMN account_status TEXT NOT NULL DEFAULT 'unknown'"
        )
    if "preferred_room_name" in existing_columns:
        statements.append("ALTER TABLE accounts DROP COLUMN preferred_room_name")
    if "preferred_seat_number" in existing_columns:
        statements.append("ALTER TABLE accounts DROP COLUMN preferred_seat_number")
    for statement in statements:
        connection.execute(statement)
    connection.execute(
        """
        UPDATE accounts
        SET rebook_enabled = CASE
                WHEN rebook_enabled IS NULL THEN 0
                ELSE rebook_enabled
            END,
            rebook_trigger_minutes = COALESCE(rebook_trigger_minutes, 5),
            account_status = COALESCE(NULLIF(TRIM(account_status), ''), 'unknown')
        """
    )


# --------------------------- 号池迁移钩子 ---------------------------


_ACCOUNT_POOL_NEW_COLUMNS: tuple[tuple[str, str], ...] = (
    # 列名, ALTER TABLE 后半句（含类型、默认值）
    ("pool_status", "TEXT NOT NULL DEFAULT ''"),
    ("pool_updated_at", "TEXT NOT NULL DEFAULT ''"),
    ("pool_previous", "TEXT NOT NULL DEFAULT ''"),
    ("suspended_at", "TEXT"),
    ("suspension_expires_at", "TEXT"),
    ("display_name", "TEXT NOT NULL DEFAULT ''"),
    ("password_cipher", "BLOB NOT NULL DEFAULT X''"),
    ("password_nonce", "BLOB NOT NULL DEFAULT X''"),
    ("password_tag", "BLOB NOT NULL DEFAULT X''"),
    ("revision", "INTEGER NOT NULL DEFAULT 0"),
    ("deleted_at", "TEXT"),
    ("last_blacklist_evidence", "TEXT NOT NULL DEFAULT ''"),
)


def _ensure_account_pool_columns(
    connection: sqlite3.Connection,
    settings: PreventAutoSettings | None,
) -> None:
    """为 ``account-pool-tri-sync`` 特性扩展 ``accounts`` 表与新建配套表。

    钩子在单事务内完成：补列、新表、索引、密码迁移、容量 WARN、审计落库。
    任何步骤抛错都会触发 ``ROLLBACK`` 并向上抛出，让启动失败可见。
    """

    cipher = _maybe_build_cipher(settings)
    now_utc = _utc_now_iso()

    # 切换为「显式事务」：sqlite3 默认隐式事务在 DDL 前会自动 commit；这里手动
    # BEGIN 后所有 DDL + DML 都在同一事务里，失败可以一次性 ROLLBACK。
    # 切换前先把上游 ``_ensure_account_columns`` 留下的隐式事务（如有）显式 commit，
    # 避免 ``BEGIN`` 失败 ("cannot start a transaction within a transaction")。
    connection.commit()
    previous_isolation = connection.isolation_level
    connection.isolation_level = None
    connection.execute("BEGIN")
    try:
        _alter_accounts_for_pool(connection)
        _create_pool_indexes(connection)
        _create_pool_companion_tables(connection)
        _backfill_pool_defaults(connection, now_utc=now_utc)
        _backfill_login_status_cache_for_active(connection, now_utc=now_utc)
        migrated_password_count = _migrate_password_to_cipher(
            connection,
            cipher=cipher,
            now_utc=now_utc,
        )
        capacity_total, capacity_exceeded = _check_pool_capacity(connection)
        if capacity_exceeded:
            logger.warning(
                "account_pool_startup_capacity_exceeded total=%d capacity=%d "
                "pool_capacity_exceeded=%s",
                capacity_total,
                POOL_CAPACITY,
                True,
            )
        _append_startup_migration_audit(
            connection,
            now_utc=now_utc,
            capacity_total=capacity_total,
            capacity_exceeded=capacity_exceeded,
            migrated_password_count=migrated_password_count,
            cipher_available=cipher is not None,
        )
        connection.execute("COMMIT")
    except Exception:
        connection.execute("ROLLBACK")
        raise
    finally:
        connection.isolation_level = previous_isolation


def _maybe_build_cipher(
    settings: PreventAutoSettings | None,
) -> AccountPasswordCipher | None:
    if settings is None or not settings.account_pool_secret_key:
        return None
    return AccountPasswordCipher(settings.account_pool_secret_key)


def _alter_accounts_for_pool(connection: sqlite3.Connection) -> None:
    existing_columns = {
        row["name"]
        for row in connection.execute("PRAGMA table_info(accounts)").fetchall()
    }
    for column_name, column_def in _ACCOUNT_POOL_NEW_COLUMNS:
        if column_name in existing_columns:
            continue
        connection.execute(
            f"ALTER TABLE accounts ADD COLUMN {column_name} {column_def}"
        )


def _create_pool_indexes(connection: sqlite3.Connection) -> None:
    """创建号池相关索引。

    包括：

    - ``UNIQUE (student_id, login_url) WHERE deleted_at IS NULL`` —— 替代设计文档中
      原本写的「表级 UNIQUE」，由部分唯一索引落地，等价于过滤掉软删除行后的唯一键。
    - ``idx_accounts_pool_status_pool_updated_at`` —— 三池列表查询主索引。
    - ``idx_accounts_pool_status_suspension_expires_at`` —— Reaper Job 扫描索引。
    """

    connection.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS uniq_accounts_student_login_active
        ON accounts(student_id, login_url)
        WHERE deleted_at IS NULL
        """
    )
    connection.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_accounts_pool_status_pool_updated_at
        ON accounts(pool_status, pool_updated_at)
        """
    )
    connection.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_accounts_pool_status_suspension_expires_at
        ON accounts(pool_status, suspension_expires_at)
        """
    )


def _create_pool_companion_tables(connection: sqlite3.Connection) -> None:
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS automation_tasks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL,
            room_name TEXT NOT NULL,
            seat_number TEXT NOT NULL,
            mode TEXT NOT NULL,
            custom_windows_json TEXT NOT NULL DEFAULT '[]',
            enabled INTEGER NOT NULL DEFAULT 1,
            revision INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            deleted_at TEXT,
            FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
        )
        """
    )
    connection.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_automation_tasks_account_id
        ON automation_tasks(account_id, deleted_at)
        """
    )

    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS pool_audit_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            audit_action TEXT NOT NULL,
            account_id INTEGER,
            task_id INTEGER,
            from_pool TEXT,
            to_pool TEXT,
            trigger_source TEXT NOT NULL,
            operator TEXT NOT NULL,
            client_kind TEXT,
            success INTEGER NOT NULL,
            reason TEXT NOT NULL DEFAULT '',
            payload_json TEXT NOT NULL DEFAULT '{}',
            created_at TEXT NOT NULL
        )
        """
    )
    connection.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_pool_audit_log_account_created
        ON pool_audit_log(account_id, created_at DESC)
        """
    )
    connection.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_pool_audit_log_action_created
        ON pool_audit_log(audit_action, created_at DESC)
        """
    )
    connection.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_pool_audit_log_created_at
        ON pool_audit_log(created_at)
        """
    )

    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS login_status_cache (
            account_id INTEGER PRIMARY KEY,
            tracked_at TEXT NOT NULL,
            FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
        )
        """
    )

    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS client_api_tokens (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            label TEXT NOT NULL,
            client_kind TEXT NOT NULL,
            token_hash TEXT NOT NULL UNIQUE,
            token_preview TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL,
            revoked_at TEXT
        )
        """
    )
    # 旧库不会再次执行上面的 CREATE TABLE，因此显式 ALTER 把 ``token_preview`` 列
    # 补齐；存量行无法恢复明文，``token_preview`` 保持空串，由前端展示为占位符。
    existing_token_columns = {
        row["name"]
        for row in connection.execute(
            "PRAGMA table_info(client_api_tokens)"
        ).fetchall()
    }
    if "token_preview" not in existing_token_columns:
        connection.execute(
            "ALTER TABLE client_api_tokens "
            "ADD COLUMN token_preview TEXT NOT NULL DEFAULT ''"
        )
    connection.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_client_api_tokens_hash
        ON client_api_tokens(token_hash)
        """
    )


def _backfill_pool_defaults(
    connection: sqlite3.Connection,
    *,
    now_utc: str,
) -> None:
    """对所有 ``pool_status IS NULL OR pool_status=''`` 的存量账号补默认值。

    Requirement 11.1：现存账号默认全部映射为 ``active``，``pool_updated_at``
    设为当前 UTC 时间，``pool_previous`` 为空字符串。
    """

    connection.execute(
        """
        UPDATE accounts
        SET pool_status = ?,
            pool_updated_at = ?,
            pool_previous = '',
            suspended_at = NULL,
            suspension_expires_at = NULL
        WHERE pool_status IS NULL OR pool_status = ''
        """,
        (PoolStatus.ACTIVE.value, now_utc),
    )


def _backfill_login_status_cache_for_active(
    connection: sqlite3.Connection,
    *,
    now_utc: str,
) -> None:
    """启动时把当前 Active_Pool 账号补回 ``login_status_cache``。

    既有数据库可能保留着 ``pool_status='active'`` 的账号，但由于早期使用的是
    :class:`InMemoryLoginStatusCache`，重启后占位集合会清空。这里在持久化表落地
    后用 ``INSERT OR IGNORE`` 把这些账号一次性补回，保证 UI「登录态缓存」列与
    「账号当前所在池」语义一致。

    后续运行期由 :class:`AccountPoolService.migrate` /
    :meth:`AccountPoolService.mark_blacklisted_by_client` 自行维护占位写入与
    清理；本启动钩子只负责回填首次部署时的初值。
    """

    connection.execute(
        """
        INSERT OR IGNORE INTO login_status_cache (account_id, tracked_at)
        SELECT id, ?
        FROM accounts
        WHERE pool_status = ? AND deleted_at IS NULL
        """,
        (now_utc, PoolStatus.ACTIVE.value),
    )


def _migrate_password_to_cipher(
    connection: sqlite3.Connection,
    *,
    cipher: AccountPasswordCipher | None,
    now_utc: str,
) -> int:
    """把存量明文密码用 AES-GCM 加密后写入 ``password_cipher / nonce / tag``。

    迁移条件：``length(password_cipher) = 0``（即新加列后的 ``X''`` 默认值），
    且 ``password`` 列存在内容。迁移完毕后旧 ``password`` 列保留为空串便于一次性
    回滚（design「Data Models」备注）。

    无密钥时不强行加密，直接跳过；该路径仅在测试环境（不传 settings）出现，
    生产 / web 路径会显式注入 settings 并要求密钥存在。
    """

    if cipher is None:
        return 0

    rows = connection.execute(
        """
        SELECT id, password
        FROM accounts
        WHERE length(password_cipher) = 0
        """
    ).fetchall()

    migrated = 0
    for row in rows:
        plaintext = row["password"] or ""
        if plaintext == "":
            # 没有原文可迁，仍写入空密文 / nonce / tag，让后续读取走「未设密码」分支。
            continue
        encrypted = cipher.encrypt(plaintext)
        connection.execute(
            """
            UPDATE accounts
            SET password_cipher = ?,
                password_nonce = ?,
                password_tag = ?,
                password = '',
                updated_at = ?
            WHERE id = ?
            """,
            (
                encrypted.cipher,
                encrypted.nonce,
                encrypted.tag,
                now_utc,
                int(row["id"]),
            ),
        )
        migrated += 1
    return migrated


def _check_pool_capacity(connection: sqlite3.Connection) -> tuple[int, bool]:
    total = int(
        connection.execute(
            "SELECT COUNT(*) FROM accounts WHERE deleted_at IS NULL"
        ).fetchone()[0]
    )
    return total, total > POOL_CAPACITY


def _append_startup_migration_audit(
    connection: sqlite3.Connection,
    *,
    now_utc: str,
    capacity_total: int,
    capacity_exceeded: bool,
    migrated_password_count: int,
    cipher_available: bool,
) -> None:
    payload = {
        "capacity_total": capacity_total,
        "pool_capacity": POOL_CAPACITY,
        "pool_capacity_exceeded": capacity_exceeded,
        "migrated_password_count": migrated_password_count,
        "cipher_available": cipher_available,
    }
    connection.execute(
        """
        INSERT INTO pool_audit_log (
            audit_action, account_id, task_id,
            from_pool, to_pool, trigger_source,
            operator, client_kind, success, reason,
            payload_json, created_at
        )
        VALUES (
            'startup_migration', NULL, NULL,
            NULL, NULL, ?,
            'system', 'system', 1, '',
            ?, ?
        )
        """,
        (
            PoolMigrationTrigger.SYSTEM.value,
            json.dumps(payload, ensure_ascii=False, sort_keys=True),
            now_utc,
        ),
    )


def _utc_now_iso() -> str:
    return (
        datetime.now(tz=timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )
