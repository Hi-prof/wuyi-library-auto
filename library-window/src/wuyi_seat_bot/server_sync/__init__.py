# 撤回 spec account-pool-tri-sync 11.4 中的 ConnectivityGate 守卫；本地执行不再依赖服务端可达性
"""服务端同步基础模块。

封装 `library-fwq` 服务端的 HTTP 同步入口：

- ``settings``：服务端地址、Bearer Token、TLS 校验等配置。
- ``client``：基于 ``httpx`` 的 Bearer Token 客户端，统一异常类型。
- ``connectivity``：服务端可达性与时钟对齐状态管理（仅供同步按钮可用性
  指示与未来 Manual_Sync_Action 流程使用，不再用于阻塞本地执行入口）。

服务端不可达时（网络错误、超时、DNS 失败、5xx 响应）由 ``client`` /
``active_pool_repository`` / ``automation_task_uploader`` /
``blacklist_reporter`` 内部按 ``ServerUnreachable`` 抛出，**仅**在 server_sync
模块内部消费；调度器、UI 层、自动任务入口、登录刷新、座位监控不再捕获该异常
用于「拒绝执行 / 跳过本轮」（参见 spec account-pool-tri-sync 任务 11.16 / 11.7）。
"""

from wuyi_seat_bot.server_sync.active_pool_repository import (
    ActiveAccountDetail,
    ActiveAccountListItem,
    ActivePoolRepository,
    AutomationTask,
    CustomWindow,
)
from wuyi_seat_bot.server_sync.automation_task_uploader import (
    AccountNotInActivePool,
    AutomationTaskRevisionConflict,
    AutomationTaskUploadError,
    AutomationTaskUploader,
    AutomationTaskUpsertResult,
    AutomationTaskValidationError,
    ClientKind,
    RevisionConflictResolution,
    UploadDisabledByConfig,
    ValidationFieldError,
    should_upload,
)
from wuyi_seat_bot.server_sync.blacklist_reporter import (
    BlacklistReporter,
    BlacklistReportResult,
)
from wuyi_seat_bot.server_sync.client import (
    HttpsRequired,
    NetworkError,
    ProtocolError,
    RateLimited,
    ServerError,
    ServerSyncClient,
    ServerSyncError,
    ServerUnreachable,
    Unauthorized,
)
from wuyi_seat_bot.server_sync.connectivity_indicator import (
    ConnectivityIndicator,
    ServerConnectivity,
    SyncButtonState,
    parse_server_time,
)
from wuyi_seat_bot.server_sync.settings import (
    DEFAULT_REQUEST_TIMEOUT_SECONDS,
    ServerSyncConfig,
    ServerSyncSettings,
    ensure_server_sync_defaults,
    load_server_sync_config,
    normalize_server_sync_settings,
    save_server_sync_config,
)
from wuyi_seat_bot.server_sync.sync_applier import (
    ApplyResult,
    SyncApplier,
)
from wuyi_seat_bot.server_sync.sync_planner import (
    LocalAccountSummary,
    LocalAutomationTask,
    MANAGED_FIELDS,
    SyncCandidate,
    compute_diff,
)


__all__ = [
    "AccountNotInActivePool",
    "ActiveAccountDetail",
    "ActiveAccountListItem",
    "ActivePoolRepository",
    "ApplyResult",
    "AutomationTask",
    "AutomationTaskRevisionConflict",
    "AutomationTaskUploadError",
    "AutomationTaskUploader",
    "AutomationTaskUpsertResult",
    "AutomationTaskValidationError",
    "BlacklistReporter",
    "BlacklistReportResult",
    "ClientKind",
    "ConnectivityIndicator",
    "CustomWindow",
    "DEFAULT_REQUEST_TIMEOUT_SECONDS",
    "HttpsRequired",
    "LocalAccountSummary",
    "LocalAutomationTask",
    "MANAGED_FIELDS",
    "NetworkError",
    "ProtocolError",
    "RateLimited",
    "RevisionConflictResolution",
    "ServerConnectivity",
    "ServerError",
    "ServerSyncClient",
    "ServerSyncConfig",
    "ServerSyncError",
    "ServerSyncSettings",
    "ServerUnreachable",
    "SyncApplier",
    "SyncButtonState",
    "SyncCandidate",
    "Unauthorized",
    "UploadDisabledByConfig",
    "ValidationFieldError",
    "compute_diff",
    "ensure_server_sync_defaults",
    "load_server_sync_config",
    "normalize_server_sync_settings",
    "save_server_sync_config",
    "should_upload",
]
