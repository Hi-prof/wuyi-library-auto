"""prevent_auto 内部日志辅助工具。

目前只提供 :func:`scrub`，用于在写审计日志、应用日志、错误响应序列化前过滤
掉密码 / 鉴权 token 等敏感字段。``account-pool-tri-sync`` 设计文档要求所有
``pool_audit_log.payload_json`` 与外发响应都必须经过 ``scrub`` 处理。
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from typing import Any


SCRUBBED_PLACEHOLDER = "***SCRUBBED***"

#: 默认敏感字段名集合。命中（大小写不敏感）即直接替换为占位符。
DEFAULT_SENSITIVE_FIELDS: frozenset[str] = frozenset(
    {
        "password",
        "passwd",
        "pwd",
        "password_cipher",
        "password_nonce",
        "password_tag",
        "authorization",
        "auth_token",
        "access_token",
        "session_token",
        "session_secret",
        "cookie",
        "set-cookie",
        "secret",
        "token",
        "token_hash",
        "api_key",
    }
)


def scrub(
    payload: Any,
    *,
    sensitive_fields: Iterable[str] | None = None,
) -> Any:
    """递归扫描 ``payload``，把敏感字段值替换为占位符。

    - 命中 :data:`DEFAULT_SENSITIVE_FIELDS`（或用户传入的集合）的字段名按大小写
      不敏感比较，直接替换为 :data:`SCRUBBED_PLACEHOLDER`，不递归进入其值。
    - ``Mapping`` 与 ``list`` / ``tuple`` / ``set`` 容器递归处理；其它类型按原样返回。
    - 返回新对象（``Mapping`` 始终返回 ``dict``，``list`` 返回 ``list``，``tuple`` 返
      回 ``tuple``，``set`` 返回 ``set``），不修改入参。
    """

    fields_lower = {field.lower() for field in (sensitive_fields or DEFAULT_SENSITIVE_FIELDS)}
    return _scrub_internal(payload, fields_lower)


def _scrub_internal(value: Any, sensitive_lower: set[str]) -> Any:
    if isinstance(value, Mapping):
        scrubbed: dict[Any, Any] = {}
        for key, item in value.items():
            if isinstance(key, str) and key.lower() in sensitive_lower:
                scrubbed[key] = SCRUBBED_PLACEHOLDER
            else:
                scrubbed[key] = _scrub_internal(item, sensitive_lower)
        return scrubbed
    if isinstance(value, list):
        return [_scrub_internal(item, sensitive_lower) for item in value]
    if isinstance(value, tuple):
        return tuple(_scrub_internal(item, sensitive_lower) for item in value)
    if isinstance(value, set):
        return {_scrub_internal(item, sensitive_lower) for item in value}
    return value


__all__ = ["DEFAULT_SENSITIVE_FIELDS", "SCRUBBED_PLACEHOLDER", "scrub"]
