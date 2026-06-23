"""server_sync 模块配置。

仅承载客户端调用 `library-fwq` 服务端所需的最小配置：
服务端基础 URL、Bearer Token、TLS 校验开关、请求超时。

更高层的应用配置（如本地账号、自动任务）由调用方负责，
本模块不读取任何本地账号兜底数据。

本模块同时提供 :class:`ServerSyncConfig`，作为 config.json 中
``server_sync`` 配置段的统一读写入口。该类在字段缺失时自动填充
默认值、不抛异常、不阻塞客户端启动，满足 Local_Only_Mode 需求。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


logger = logging.getLogger(__name__)

DEFAULT_REQUEST_TIMEOUT_SECONDS: float = 10.0
MIN_REQUEST_TIMEOUT_SECONDS: float = 1.0
MAX_REQUEST_TIMEOUT_SECONDS: float = 120.0


# ---------------------------------------------------------------------------
# ServerSyncSettings（既有，供 client.py 使用的严格校验配置）
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ServerSyncSettings:
    """server_sync 客户端配置。

    Attributes:
        server_base_url: 服务端基础 URL，例如 ``https://server.example.com``。
            末尾斜杠会被剥除，避免与请求 path 拼接出双斜杠。
        bearer_token: 服务端签发的 Bearer Token；空字符串视为「未配置」，
            客户端会拒绝构造。
        verify_tls: 是否校验 TLS 证书。生产环境必须为 True；
            仅在本地自签发调试时允许临时关闭。
        request_timeout_seconds: 单次 HTTP 请求总超时（秒）。
    """

    server_base_url: str
    bearer_token: str
    verify_tls: bool = True
    request_timeout_seconds: float = DEFAULT_REQUEST_TIMEOUT_SECONDS

    def with_token(self, bearer_token: str) -> "ServerSyncSettings":
        """返回替换 Bearer Token 后的新配置实例。"""

        return replace(self, bearer_token=_normalize_token(bearer_token))


def normalize_server_sync_settings(
    *,
    server_base_url: str,
    bearer_token: str,
    verify_tls: bool = True,
    request_timeout_seconds: float = DEFAULT_REQUEST_TIMEOUT_SECONDS,
) -> ServerSyncSettings:
    """对外部输入做归一化与最小校验，得到不可变 :class:`ServerSyncSettings`。

    - `server_base_url` 必须是合法的 http/https URL；其余 scheme 会被拒绝。
    - 末尾的 `/` 会被剥除，便于与请求路径拼接。
    - `bearer_token` 会做 ``strip``；空串视为非法。
    - `request_timeout_seconds` 必须落在 [1, 120] 秒区间内。
    """

    base_url = _normalize_base_url(server_base_url)
    token = _normalize_token(bearer_token)
    timeout = _normalize_timeout(request_timeout_seconds)
    return ServerSyncSettings(
        server_base_url=base_url,
        bearer_token=token,
        verify_tls=bool(verify_tls),
        request_timeout_seconds=timeout,
    )


# ---------------------------------------------------------------------------
# ServerSyncConfig（新增，config.json 中 server_sync 段的宽松读写入口）
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ServerSyncConfig:
    """config.json ``server_sync`` 配置段的统一数据结构。

    与 :class:`ServerSyncSettings` 不同，本类 **不** 在字段缺失时抛出异常，
    而是使用安全的默认值（``None`` / ``True`` / ``False``），确保客户端在
    升级期或未配置时也能正常启动并进入 Local_Only_Mode。

    Attributes:
        base_url: 服务端基础 URL，``None`` 表示未配置。
        bearer_token: 服务端签发的 Bearer Token，``None`` 表示未配置。
        verify_tls: 是否校验 TLS 证书，默认 ``True``。
        upload_enabled: 同步上行开关（Automation_Task 上传与拉黑事件上报），
            默认 ``False``。
    """

    base_url: str | None = None
    bearer_token: str | None = None
    verify_tls: bool = True
    upload_enabled: bool = False

    def is_configured(self) -> bool:
        """``base_url`` 与 ``bearer_token`` 均非空时返回 ``True``。"""
        return bool(self.base_url) and bool(self.bearer_token)

    def is_upload_enabled(self) -> bool:
        """已配置且同步上行开关开启时返回 ``True``。"""
        return self.is_configured() and self.upload_enabled

    def is_local_only(self) -> bool:
        """未配置或上行开关关闭时返回 ``True``（进入 Local_Only_Mode）。"""
        return not self.is_configured() or not self.upload_enabled

    def to_server_sync_settings(self) -> ServerSyncSettings | None:
        """尝试转换为严格校验的 :class:`ServerSyncSettings`。

        若当前配置不满足 ``is_configured()``，返回 ``None`` 而非抛异常。
        """
        if not self.is_configured():
            return None
        assert self.base_url is not None
        assert self.bearer_token is not None
        try:
            return normalize_server_sync_settings(
                server_base_url=self.base_url,
                bearer_token=self.bearer_token,
                verify_tls=self.verify_tls,
            )
        except ValueError:
            return None


# ---------------------------------------------------------------------------
# config.json 读写工具函数
# ---------------------------------------------------------------------------

_SERVER_SYNC_KEY = "server_sync"

_SERVER_SYNC_DEFAULTS: dict[str, Any] = {
    "base_url": None,
    "bearer_token": None,
    "verify_tls": True,
    "upload_enabled": False,
}


def load_server_sync_config(config_path: str | Path) -> ServerSyncConfig:
    """从 config.json 中读取 ``server_sync`` 配置段。

    - 文件不存在或解析失败时返回全默认 :class:`ServerSyncConfig`。
    - ``server_sync`` 段缺失时返回全默认值。
    - 单个字段缺失 / 类型不合法时使用默认值补齐，不抛异常。
    - 本函数 **不** 修改 config.json。
    """
    try:
        path = Path(config_path)
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        logger.debug("读取 config.json 失败，使用 server_sync 默认配置: %s", exc)
        return ServerSyncConfig()

    if not isinstance(payload, dict):
        return ServerSyncConfig()

    section = payload.get(_SERVER_SYNC_KEY)
    if not isinstance(section, dict):
        return ServerSyncConfig()

    return _parse_server_sync_section(section)


def ensure_server_sync_defaults(config_path: str | Path) -> ServerSyncConfig:
    """确保 config.json 中存在 ``server_sync`` 段且字段完整。

    - 文件不存在时 **不** 创建新文件（避免覆盖其它初始化流程）。
    - ``server_sync`` 段缺失时插入全默认字段。
    - 已存在的字段保留原值，仅补齐缺失字段。
    - 保留 config.json 中的全部既有字段（accounts、自动任务、窗口配置等）。
    - 写入失败时仅 log warning，不阻塞启动。

    Returns:
        当前读取（含补齐后）的 :class:`ServerSyncConfig`。
    """
    path = Path(config_path)

    if not path.exists():
        logger.debug("config.json 不存在，跳过 server_sync 默认值写入")
        return ServerSyncConfig()

    try:
        raw = path.read_text(encoding="utf-8")
        payload = json.loads(raw)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        logger.warning("读取 config.json 失败，无法补齐 server_sync: %s", exc)
        return ServerSyncConfig()

    if not isinstance(payload, dict):
        logger.warning("config.json 顶层不是对象，无法补齐 server_sync")
        return ServerSyncConfig()

    section = payload.get(_SERVER_SYNC_KEY)
    needs_write = False

    if not isinstance(section, dict):
        section = dict(_SERVER_SYNC_DEFAULTS)
        payload[_SERVER_SYNC_KEY] = section
        needs_write = True
    else:
        for key, default_value in _SERVER_SYNC_DEFAULTS.items():
            if key not in section:
                section[key] = default_value
                needs_write = True

    if needs_write:
        try:
            path.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        except OSError as exc:
            logger.warning("写入 config.json server_sync 默认值失败: %s", exc)

    return _parse_server_sync_section(section)


def save_server_sync_config(
    config_path: str | Path, config: ServerSyncConfig
) -> None:
    """把 :class:`ServerSyncConfig` 写回 config.json 的 ``server_sync`` 段。

    保留 config.json 中其它全部字段不变。
    """
    path = Path(config_path)
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, ValueError):
        payload = {}

    if not isinstance(payload, dict):
        payload = {}

    payload[_SERVER_SYNC_KEY] = {
        "base_url": config.base_url,
        "bearer_token": config.bearer_token,
        "verify_tls": config.verify_tls,
        "upload_enabled": config.upload_enabled,
    }

    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


# ---------------------------------------------------------------------------
# 内部辅助
# ---------------------------------------------------------------------------


def _parse_server_sync_section(section: dict[str, Any]) -> ServerSyncConfig:
    """从 dict 解析出 ServerSyncConfig，字段不合法时用默认值替代。"""
    base_url = _safe_optional_str(section.get("base_url"))
    bearer_token = _safe_optional_str(section.get("bearer_token"))
    verify_tls = _safe_bool(section.get("verify_tls"), default=True)
    upload_enabled = _safe_bool(section.get("upload_enabled"), default=False)
    return ServerSyncConfig(
        base_url=base_url,
        bearer_token=bearer_token,
        verify_tls=verify_tls,
        upload_enabled=upload_enabled,
    )


def _safe_optional_str(value: Any) -> str | None:
    """将值转换为 str | None，无效值返回 None。"""
    if value is None:
        return None
    if not isinstance(value, str):
        return None
    text = value.strip()
    return text if text else None


def _safe_bool(value: Any, *, default: bool) -> bool:
    """将值转换为 bool，无效值返回 default。"""
    if isinstance(value, bool):
        return value
    return default


# ---------------------------------------------------------------------------
# 既有内部辅助（供 normalize_server_sync_settings 使用）
# ---------------------------------------------------------------------------


def _normalize_base_url(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("server_base_url 必须是字符串")
    text = value.strip()
    if not text:
        raise ValueError("server_base_url 不能为空")
    parsed = urlparse(text)
    if parsed.scheme not in ("http", "https"):
        raise ValueError("server_base_url 必须以 http:// 或 https:// 开头")
    if not parsed.netloc:
        raise ValueError("server_base_url 缺少 host")
    # 剥除末尾斜杠，便于拼接 path（例如 base + "/api/v1/..."）。
    while text.endswith("/"):
        text = text[:-1]
    return text


def _normalize_token(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("bearer_token 必须是字符串")
    text = value.strip()
    if not text:
        raise ValueError("bearer_token 不能为空")
    if "\u2026" in text:
        raise ValueError("bearer_token 必须填写签发时显示的完整 Token，不能填写 Token 预览")
    try:
        text.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ValueError("bearer_token 只能包含 ASCII 字符，请填写完整 Token") from exc
    return text


def _normalize_timeout(value: Any) -> float:
    try:
        timeout = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("request_timeout_seconds 必须是数字") from exc
    if not (MIN_REQUEST_TIMEOUT_SECONDS <= timeout <= MAX_REQUEST_TIMEOUT_SECONDS):
        raise ValueError(
            "request_timeout_seconds 必须在 "
            f"[{MIN_REQUEST_TIMEOUT_SECONDS}, {MAX_REQUEST_TIMEOUT_SECONDS}] 秒之间"
        )
    return timeout
