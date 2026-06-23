"""Web 中间件子包：account-pool-tri-sync 客户端 API 的鉴权 / HTTPS / 限频。

本子包对应 spec ``account-pool-tri-sync`` 的 task 8.1。三个模块分别承担：

* :mod:`prevent_auto.web.middleware.auth` —— Bearer Token 鉴权依赖；按
  ``sha256(token + pepper)`` 在 ``client_api_tokens`` 表里找未撤销记录，命中
  失败返回 ``401``（与 design「Error Handling」一致）。
* :mod:`prevent_auto.web.middleware.https` —— ``BaseHTTPMiddleware`` 实现，
  非 HTTPS 且非环回 IP 时返回 ``426 Upgrade Required``。
* :mod:`prevent_auto.web.middleware.rate_limit` —— 内存 LRU 滑窗限频依赖，
  专用于 ``Active_Account_Detail_API``，按 ``(token_id, account_id)`` 计数。
"""

from prevent_auto.web.middleware.auth import (
    AUTH_DEPENDENCY_STATE_ATTR,
    AuthDependencyState,
    install_auth_dependency_state,
    require_bearer_token,
)
from prevent_auto.web.middleware.https import (
    HttpsRequiredMiddleware,
    is_loopback_host,
)
from prevent_auto.web.middleware.rate_limit import (
    DETAIL_RATE_LIMIT_STATE_ATTR,
    DetailRateLimiter,
    install_detail_rate_limiter,
    require_detail_rate_limit,
)

__all__ = [
    "AUTH_DEPENDENCY_STATE_ATTR",
    "AuthDependencyState",
    "DETAIL_RATE_LIMIT_STATE_ATTR",
    "DetailRateLimiter",
    "HttpsRequiredMiddleware",
    "install_auth_dependency_state",
    "install_detail_rate_limiter",
    "is_loopback_host",
    "require_bearer_token",
    "require_detail_rate_limit",
]
