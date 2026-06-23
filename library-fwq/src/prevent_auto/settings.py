from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from datetime import datetime, time as dtime
from pathlib import Path


logger = logging.getLogger(__name__)


DEFAULT_AUTH_USERNAME = "xuhuangbin"
DEFAULT_AUTH_PASSWORD = "xuhuangbin"
DEFAULT_SESSION_SECRET = "WuyiAuto!2026#SessionKey7RbTn4YpZs8"
DEFAULT_DAILY_STATUS_REFRESH_TIME = dtime(8, 10)
# 持续滚动预约：默认每 5 小时扫一次活跃账号下所有启用 Automation_Task，
# 按小时模板把今日及未来 N 天还没预约的时段补齐。
DEFAULT_AUTO_RESERVATION_INTERVAL_SECONDS = 5 * 60 * 60
DEFAULT_ROLLING_RESERVATION_DAYS_AHEAD = 3
DEFAULT_ACCOUNT_POOL_TOKEN_PEPPER = "prevent_auto_token_pepper_default"
DEFAULT_ACCOUNT_POOL_REAPER_INTERVAL_SECONDS = 300
DEFAULT_ACCOUNT_POOL_DETAIL_RATE_LIMIT_PER_MINUTE = 6
DEFAULT_ACCOUNT_POOL_AUDIT_RETENTION_DAYS = 90
DEFAULT_ACCOUNT_POOL_HTTPS_REQUIRED = True
LOCAL_ONLY_HOSTS = {"127.0.0.1", "localhost", "::1"}
ACCOUNT_POOL_SECRET_KEY_FILENAME = "account_pool_secret.key"


@dataclass(frozen=True)
class PreventAutoSettings:
    project_root: Path
    package_root: Path
    data_dir: Path
    runtime_dir: Path
    database_path: Path
    host: str
    port: int
    monitor_interval_seconds: int
    rebook_poll_interval_seconds: int
    log_retention_days: int
    auth_username: str = DEFAULT_AUTH_USERNAME
    auth_password: str = DEFAULT_AUTH_PASSWORD
    session_secret: str = DEFAULT_SESSION_SECRET
    daily_status_refresh_time: dtime = field(
        default_factory=lambda: DEFAULT_DAILY_STATUS_REFRESH_TIME
    )
    auto_reservation_interval_seconds: int = (
        DEFAULT_AUTO_RESERVATION_INTERVAL_SECONDS
    )
    rolling_reservation_days_ahead: int = DEFAULT_ROLLING_RESERVATION_DAYS_AHEAD
    account_pool_secret_key: str = ""
    account_pool_token_pepper: str = DEFAULT_ACCOUNT_POOL_TOKEN_PEPPER
    account_pool_reaper_interval_seconds: int = (
        DEFAULT_ACCOUNT_POOL_REAPER_INTERVAL_SECONDS
    )
    account_pool_detail_rate_limit_per_minute: int = (
        DEFAULT_ACCOUNT_POOL_DETAIL_RATE_LIMIT_PER_MINUTE
    )
    account_pool_audit_retention_days: int = (
        DEFAULT_ACCOUNT_POOL_AUDIT_RETENTION_DAYS
    )
    account_pool_https_required: bool = DEFAULT_ACCOUNT_POOL_HTTPS_REQUIRED


def load_settings(base_dir: str | Path | None = None) -> PreventAutoSettings:
    package_root = Path(base_dir or Path(__file__).resolve().parents[2]).resolve()
    project_root = package_root.parent.resolve()
    data_dir = package_root / "data"
    runtime_dir = package_root / "runtime"
    account_pool_secret_key = _resolve_account_pool_secret_key(runtime_dir)
    settings = PreventAutoSettings(
        project_root=project_root,
        package_root=package_root,
        data_dir=data_dir,
        runtime_dir=runtime_dir,
        database_path=data_dir / "prevent_auto.db",
        host=os.environ.get("PREVENT_AUTO_HOST", "127.0.0.1"),
        port=int(os.environ.get("PREVENT_AUTO_PORT", "5000")),
        monitor_interval_seconds=_read_positive_int_env(
            "PREVENT_AUTO_MONITOR_INTERVAL_SECONDS",
            60,
        ),
        rebook_poll_interval_seconds=_read_positive_int_env(
            "PREVENT_AUTO_REBOOK_POLL_INTERVAL_SECONDS",
            15,
        ),
        log_retention_days=_read_positive_int_env(
            "PREVENT_AUTO_LOG_RETENTION_DAYS",
            30,
        ),
        auth_username=os.environ.get("PREVENT_AUTO_AUTH_USERNAME", DEFAULT_AUTH_USERNAME),
        auth_password=os.environ.get("PREVENT_AUTO_AUTH_PASSWORD", DEFAULT_AUTH_PASSWORD),
        session_secret=os.environ.get("PREVENT_AUTO_SESSION_SECRET", DEFAULT_SESSION_SECRET),
        daily_status_refresh_time=_read_time_of_day_env(
            "PREVENT_AUTO_DAILY_STATUS_REFRESH_TIME",
            DEFAULT_DAILY_STATUS_REFRESH_TIME,
        ),
        auto_reservation_interval_seconds=_read_positive_int_env(
            "PREVENT_AUTO_AUTO_RESERVATION_INTERVAL_SECONDS",
            DEFAULT_AUTO_RESERVATION_INTERVAL_SECONDS,
        ),
        rolling_reservation_days_ahead=_read_positive_int_env(
            "PREVENT_AUTO_ROLLING_DAYS_AHEAD",
            DEFAULT_ROLLING_RESERVATION_DAYS_AHEAD,
        ),
        account_pool_secret_key=account_pool_secret_key,
        account_pool_token_pepper=os.environ.get(
            "ACCOUNT_POOL_TOKEN_PEPPER",
            DEFAULT_ACCOUNT_POOL_TOKEN_PEPPER,
        ),
        account_pool_reaper_interval_seconds=_read_positive_int_env(
            "ACCOUNT_POOL_REAPER_INTERVAL_SECONDS",
            DEFAULT_ACCOUNT_POOL_REAPER_INTERVAL_SECONDS,
        ),
        account_pool_detail_rate_limit_per_minute=_read_positive_int_env(
            "ACCOUNT_POOL_DETAIL_RATE_LIMIT_PER_MINUTE",
            DEFAULT_ACCOUNT_POOL_DETAIL_RATE_LIMIT_PER_MINUTE,
        ),
        account_pool_audit_retention_days=_read_positive_int_env(
            "ACCOUNT_POOL_AUDIT_RETENTION_DAYS",
            DEFAULT_ACCOUNT_POOL_AUDIT_RETENTION_DAYS,
        ),
        account_pool_https_required=_read_bool_env(
            "ACCOUNT_POOL_HTTPS_REQUIRED",
            DEFAULT_ACCOUNT_POOL_HTTPS_REQUIRED,
        ),
    )
    _validate_auth_override(settings)
    return settings


def _validate_auth_override(settings: PreventAutoSettings) -> None:
    if settings.host in LOCAL_ONLY_HOSTS:
        return
    if settings.auth_password == DEFAULT_AUTH_PASSWORD:
        raise ValueError("对外监听时必须设置 PREVENT_AUTO_AUTH_PASSWORD 覆盖默认密码")
    if settings.session_secret == DEFAULT_SESSION_SECRET:
        raise ValueError("对外监听时必须设置 PREVENT_AUTO_SESSION_SECRET 覆盖默认会话密钥")


def _resolve_account_pool_secret_key(runtime_dir: Path) -> str:
    """读取或自动生成 ACCOUNT_POOL_SECRET_KEY。

    优先级：环境变量 ``ACCOUNT_POOL_SECRET_KEY`` > ``runtime/account_pool_secret.key``
    文件 > 自动生成并写入文件。

    密钥仅用于移动端 / Windows 客户端拉取账号密码时的 AES-GCM 加解密；web 端
    自身不要求用户感知，因此服务端进程启动时若缺失就静默生成一份持久化密钥，
    避免管理员每次手动操作。生成的文件会被 ``.gitignore`` 覆盖，不会泄漏到仓库。
    """

    env_value = (os.environ.get("ACCOUNT_POOL_SECRET_KEY") or "").strip()
    if env_value:
        return env_value

    key_file = runtime_dir / ACCOUNT_POOL_SECRET_KEY_FILENAME
    if key_file.exists():
        text = key_file.read_text(encoding="utf-8").strip()
        if text:
            return text

    # 延迟导入，避免 cryptography 依赖链在 settings 模块启动期被强行初始化。
    from prevent_auto.services.account_password_cipher import generate_secret_key

    generated = generate_secret_key()
    runtime_dir.mkdir(parents=True, exist_ok=True)
    key_file.write_text(generated, encoding="utf-8")
    try:
        os.chmod(key_file, 0o600)
    except OSError:
        # Windows / 部分 FS 不支持精细权限，忽略即可。
        pass
    logger.info(
        "已自动生成 ACCOUNT_POOL_SECRET_KEY 并写入 %s（仅本机使用，请勿提交到版本库）",
        key_file,
    )
    return generated


def _read_positive_int_env(name: str, default: int) -> int:
    raw_value = os.environ.get(name)
    if raw_value is None:
        return default
    value = int(raw_value)
    if value <= 0:
        raise ValueError(f"{name} 必须大于 0")
    return value


def _read_time_of_day_env(name: str, default: dtime) -> dtime:
    raw_value = os.environ.get(name)
    if raw_value is None:
        return default
    text = raw_value.strip()
    if not text:
        return default
    try:
        return datetime.strptime(text, "%H:%M").time()
    except ValueError as exc:
        raise ValueError(
            f"{name} 必须是 HH:MM 格式（24 小时制），当前值：{raw_value!r}"
        ) from exc


def _read_bool_env(name: str, default: bool) -> bool:
    raw_value = os.environ.get(name)
    if raw_value is None:
        return default
    text = raw_value.strip().lower()
    if text in {"1", "true", "yes", "on"}:
        return True
    if text in {"0", "false", "no", "off"}:
        return False
    raise ValueError(
        f"{name} 必须是布尔值（true/false/1/0/yes/no/on/off），当前值：{raw_value!r}"
    )
