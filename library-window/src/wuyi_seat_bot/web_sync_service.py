"""Manual_Sync_Action Web 入口服务。

封装 spec account-pool-tri-sync 任务 11.13 中「从服务端同步活跃池」按钮所
需要的后端能力：

- :func:`compute_sync_button_state`：返回同步按钮的三态
  (``enabled`` / ``disabled_unconfigured`` / ``disabled_unreachable``) 与
  对应文案，供 UI 渲染按钮可用性指示（Requirement 13.9 / 13.3）。
- :func:`preview_manual_sync`：调用接口 A → 接口 B → ``sync_planner.compute_diff``，
  返回 Sync_Coverage_Confirmation 弹窗所需的候选条目；服务端不可达 / 401 /
  426 / 5xx 时仅返回 ``error_code``，不产出候选条目，也不修改 Local_Account_Store
  （Requirement 13.4 / 13.5 / 13.8）。
- :func:`apply_manual_sync`：根据用户在弹窗内给出的 ``selection`` 调
  :class:`SyncApplier`；``selection`` 全空时视为无操作（Requirement 13.6 / 13.13 /
  13.15 / 13.16）。

所有方法 **都不修改** Local_Account_Store 直到 :func:`apply_manual_sync` 真正被
调用；任何错误路径都不写入 ``config.json``。

模块刻意不持有长期网络客户端：每次 ``preview`` 调用临时构造
:class:`ServerSyncClient` 与 :class:`ActivePoolRepository`，调用结束后立即关闭
连接池。这样可以避免 UI 长时间持有 token / 连接，与既有 ``web_settings_service``
的「按请求新建连接」风格一致。
"""

from __future__ import annotations

import hashlib
import json
import logging
import threading
import time
import uuid
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path
from typing import Any

from wuyi_seat_bot.automation_plans import (
    AutomationPlan,
    LocalAutomationPlanScheduler,
    build_automation_plan,
)
from wuyi_seat_bot.config import load_config_bundle
from wuyi_seat_bot.server_sync import (
    ActiveAccountDetail,
    ActivePoolRepository,
    AutomationTask,
    AutomationTaskRevisionConflict,
    AutomationTaskUploadError,
    AutomationTaskValidationError,
    AutomationTaskUploader,
    ConnectivityIndicator,
    CustomWindow,
    HttpsRequired,
    LocalAccountSummary,
    LocalAutomationTask,
    NetworkError,
    ProtocolError,
    RateLimited,
    ServerConnectivity,
    ServerError,
    ServerSyncClient,
    ServerSyncConfig,
    ServerUnreachable,
    SyncApplier,
    SyncCandidate,
    Unauthorized,
    compute_diff,
    load_server_sync_config,
    normalize_server_sync_settings,
    save_server_sync_config,
)
from wuyi_seat_bot.web_errors import ApiRequestError


logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------- #
# Public types                                                                 #
# --------------------------------------------------------------------------- #


_TOKEN_TTL_SECONDS: float = 600.0
"""Sync_Coverage_Confirmation 候选条目缓存有效期（秒）。

弹窗在用户面前停留时间一般很短；这里给 10 分钟，足以覆盖用户阅读、勾选、
确认的全部过程。超时后 ``apply`` 会返回 ``token_expired`` 错误，由 UI 引导用户重新
点击同步按钮重算 diff。
"""

_SERVER_MANAGED_PLAN_PREFIX: str = "server-managed:"
_DEFAULT_IMPORTED_START_HOUR: int = 8
_DEFAULT_IMPORTED_DURATION_HOURS: int = 1
_DEFAULT_IMPORTED_RESERVE_TIME: str = "08:00"
_DEFAULT_IMPORTED_CHECKIN_TIME: str = "07:35"
_DEFAULT_IMPORTED_CHECKOUT_TIME: str = "21:59"
_DEFAULT_IMPORTED_RESERVE_CHECK_INTERVAL_MINUTES: int = 30


@dataclass(slots=True)
class _PreviewSession:
    """Sync_Coverage_Confirmation 候选条目的临时持仓。

    候选条目里含有 :class:`ActiveAccountDetail`（含明文密码），所以本对象
    **不** 序列化到磁盘、**不** 通过 HTTP 暴露给前端；前端只拿到 ``token`` +
    候选条目的脱敏摘要。
    """

    token: str
    candidates: list[SyncCandidate]
    created_at: float


# --------------------------------------------------------------------------- #
# 同步按钮三态                                                                 #
# --------------------------------------------------------------------------- #


def compute_sync_button_state(
    config_path: str | Path,
    *,
    indicator: ConnectivityIndicator | None = None,
) -> dict[str, Any]:
    """计算同步按钮三态。

    Args:
        config_path: ``config.json`` 路径，用于读取 ``server_sync`` 段。
        indicator: 可选注入；不传时按 ``ServerSyncConfig.is_configured()`` 直接
            返回 ``enabled`` / ``disabled_unconfigured``，不做「最近一次失败 →
            disabled_unreachable」推断。

    Returns:
        含 ``state``（三态字面量）、``label``（按钮 chip 文案）、``failure_reason``
        （仅在不可达时为非空）三字段的 dict。
    """

    config = load_server_sync_config(config_path)
    if not config.is_configured():
        return {
            "state": "disabled_unconfigured",
            "label": "未配置服务端",
            "failure_reason": "",
            "upload_enabled": False,
        }

    if indicator is None:
        return {
            "state": "enabled",
            "label": "服务端已配置",
            "failure_reason": "",
            "upload_enabled": config.upload_enabled,
        }

    state = indicator.compute_sync_button_state()
    if state == "enabled":
        return {
            "state": state,
            "label": "服务端已配置",
            "failure_reason": "",
            "upload_enabled": config.upload_enabled,
        }
    if state == "disabled_unreachable":
        return {
            "state": state,
            "label": "服务端不可达",
            "failure_reason": indicator.last_failure_reason(),
            "upload_enabled": config.upload_enabled,
        }
    return {
        "state": state,
        "label": "未配置服务端",
        "failure_reason": "",
        "upload_enabled": False,
    }


# --------------------------------------------------------------------------- #
# Server_Sync_Config 读写（账号管理页直接编辑）                                  #
# --------------------------------------------------------------------------- #


def _serialize_server_sync_config(config: ServerSyncConfig) -> dict[str, Any]:
    """把 :class:`ServerSyncConfig` 序列化为前端可渲染的 dict。

    bearer_token 不做掩码：账号管理页的「服务端配置」入口本身是受信操作，
    用户需要看到当前 token 才能判断是否需要更新；UI 侧用 ``type="password"``
    输入框遮挡显示。
    """

    return {
        "base_url": config.base_url or "",
        "bearer_token": config.bearer_token or "",
        "verify_tls": bool(config.verify_tls),
        "upload_enabled": bool(config.upload_enabled),
        "is_configured": config.is_configured(),
    }


def get_server_sync_settings_response(*, config_path: str | Path) -> dict[str, Any]:
    """读取当前 ``server_sync`` 配置，供账号管理页「服务端配置」弹窗渲染。"""

    config = load_server_sync_config(config_path)
    return {"config": _serialize_server_sync_config(config)}


def save_server_sync_settings_response(
    *,
    config_path: str | Path,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """保存账号管理页「服务端配置」弹窗提交的 ``server_sync`` 配置。

    - ``base_url`` 与 ``bearer_token`` 同时为空时视为「清除配置」，回到未配置态；
      其它字段也会被重置为默认值。
    - ``bearer_token`` 可留空单独保存，此时仅保留 ``base_url``，仍视为未配置态。
    - 仅当 ``base_url`` 为空而 ``bearer_token`` 非空时才按 :class:`ApiRequestError`
      抛出 400。
    - 保存时不校验 URL 格式或连通性；真正同步 / 上传时仍会走严格校验。
    """

    if not isinstance(payload, dict):
        raise ApiRequestError("请求体必须是 JSON 对象")

    base_url_raw = payload.get("base_url", "")
    bearer_token_raw = payload.get("bearer_token", "")
    if not isinstance(base_url_raw, str):
        raise ApiRequestError("base_url 必须是字符串")
    if not isinstance(bearer_token_raw, str):
        raise ApiRequestError("bearer_token 必须是字符串")

    base_url = base_url_raw.strip()
    bearer_token = bearer_token_raw.strip()
    verify_tls = _coerce_bool(payload.get("verify_tls"), default=True)
    upload_enabled = _coerce_bool(payload.get("upload_enabled"), default=False)

    if not base_url and not bearer_token:
        # 清除配置：把 server_sync 段重置为默认值，按未配置态展示。
        cleared = ServerSyncConfig()
        save_server_sync_config(config_path, cleared)
        return {
            "config": _serialize_server_sync_config(cleared),
            "message": "已清除服务端配置",
        }

    if not base_url:
        raise ApiRequestError("base_url 不能为空")
    if not bearer_token:
        saved = ServerSyncConfig(
            base_url=base_url,
            bearer_token=None,
            verify_tls=verify_tls,
            upload_enabled=upload_enabled,
        )
        save_server_sync_config(config_path, saved)
        if upload_enabled:
            message = "已保存服务端配置（Bearer Token 为空，暂未启用上行）"
        else:
            message = "已保存服务端配置（Bearer Token 为空）"
        return {
            "config": _serialize_server_sync_config(saved),
            "message": message,
        }

    if upload_enabled and not verify_tls:
        # 显式提示：上行打开但关闭 TLS 校验，仅本地调试可用；不阻塞保存，
        # 但通过 message 让用户感知到风险。
        message = "已保存服务端配置（已开启上行；当前关闭了 TLS 校验，仅建议本地调试时使用）"
    elif upload_enabled:
        message = "已保存服务端配置（已开启上行）"
    else:
        message = "已保存服务端配置"

    saved = ServerSyncConfig(
        base_url=base_url,
        bearer_token=bearer_token,
        verify_tls=verify_tls,
        upload_enabled=upload_enabled,
    )
    save_server_sync_config(config_path, saved)
    return {
        "config": _serialize_server_sync_config(saved),
        "message": message,
    }


def _coerce_bool(value: Any, *, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return default
    if isinstance(value, str):
        text = value.strip().lower()
        if text in {"true", "1", "yes", "on"}:
            return True
        if text in {"false", "0", "no", "off", ""}:
            return False
    if isinstance(value, (int, float)):
        return bool(value)
    return default


# --------------------------------------------------------------------------- #
# Windows -> Server 账号上行                                                   #
# --------------------------------------------------------------------------- #


def upload_local_accounts_to_server(*, config_path: str | Path) -> dict[str, Any]:
    """把本地账号基础配置上传到服务端 Active_Pool。"""

    config_file = Path(config_path)
    config = load_server_sync_config(config_file)
    if not config.is_configured():
        return {
            "ok": False,
            "error_code": "unconfigured",
            "message": "未配置服务端",
        }
    if not config.upload_enabled:
        return {
            "ok": False,
            "error_code": "upload_disabled",
            "message": "未启用同步上行，请先在服务端配置中勾选上行开关",
        }

    settings = config.to_server_sync_settings()
    if settings is None:
        return {
            "ok": False,
            "error_code": "invalid_config",
            "message": "服务端配置无效",
        }

    accounts = _load_local_upload_accounts(config_file)
    if not accounts:
        return {
            "ok": True,
            "noop": True,
            "total": 0,
            "created": 0,
            "updated": 0,
            "rejected": 0,
            "message": "没有可上传的本地账号",
        }

    try:
        with ServerSyncClient(settings) as client:
            response = client.post(
                "/api/v1/active-accounts",
                json_body={"accounts": accounts},
            )
    except ServerUnreachable as exc:
        return _failure_from_exception(exc)
    except (Unauthorized, HttpsRequired) as exc:
        return _failure_from_protocol_exception(exc)
    except RateLimited as exc:
        return {
            "ok": False,
            "error_code": "rate_limited",
            "message": "服务端限频，请稍后再试",
            "retry_after": exc.retry_after,
        }
    except ProtocolError as exc:
        return {
            "ok": False,
            "error_code": "protocol_error",
            "message": exc.message,
            "status_code": exc.status_code,
        }
    except Exception as exc:  # noqa: BLE001
        logger.exception("Windows 账号上传失败")
        return {
            "ok": False,
            "error_code": "internal_error",
            "message": f"上传失败：{exc}",
        }

    try:
        payload = response.json()
    except ValueError:
        return {
            "ok": False,
            "error_code": "protocol_error",
            "message": "服务端响应不是 JSON",
        }
    if not isinstance(payload, dict):
        return {
            "ok": False,
            "error_code": "protocol_error",
            "message": "服务端响应格式异常",
        }

    created = int(payload.get("created") or 0)
    updated = int(payload.get("updated") or 0)
    rejected = int(payload.get("rejected") or 0)
    total = int(payload.get("total") or len(accounts))
    message = f"上传完成：新增 {created}、更新 {updated}、拒绝 {rejected}"
    return {
        "ok": True,
        "total": total,
        "created": created,
        "updated": updated,
        "rejected": rejected,
        "items": payload.get("items") if isinstance(payload.get("items"), list) else [],
        "message": message,
    }


# --------------------------------------------------------------------------- #
# Manual_Sync_Action 主流程                                                    #
# --------------------------------------------------------------------------- #


class ManualSyncCoordinator:
    """协调 Manual_Sync_Action 的两个阶段：``preview`` 与 ``apply``。

    UI 侧调用流程：

    1. ``preview()`` 返回候选条目摘要 + 临时 ``token``；服务端在内存里持有原
       :class:`SyncCandidate` 列表（含 :class:`ActiveAccountDetail` 中的明文密码）。
    2. 用户在 Sync_Coverage_Confirmation 弹窗里勾选 ``selection``。
    3. ``apply(token, selection)`` 用 ``token`` 查回原候选列表，调
       :class:`SyncApplier`。
    4. 弹窗关闭后服务端按 TTL 自然清理 ``token``；调用 ``cancel(token)``
       可立即清理。

    线程安全：使用 :class:`threading.Lock` 保护内部 ``_sessions`` dict。
    """

    def __init__(self, config_path: str | Path) -> None:
        self._config_path = Path(config_path)
        self._lock = threading.Lock()
        self._sessions: dict[str, _PreviewSession] = {}

    # ------------------------------------------------------------------ #
    # Stage 1：preview                                                    #
    # ------------------------------------------------------------------ #

    def preview(self) -> dict[str, Any]:
        """执行接口 A → 接口 B → ``compute_diff`` 全流程。

        Returns:
            成功时返回::

                {
                    "ok": True,
                    "token": "<uuid>",
                    "state": "enabled",
                    "candidates": [
                        {
                            "kind": "add",
                            "student_id": "20231121130",
                            "default_checked": True,
                            "server_summary": {...},
                            "local_summary": None,
                        },
                        ...
                    ],
                    "summary": {"add": 1, "replace": 0, "remove": 0},
                }

            失败时返回::

                {
                    "ok": False,
                    "error_code": "unconfigured" | "invalid_config" |
                                  "server_unreachable" |
                                  "unauthorized_401" | "https_required_426" |
                                  "server_5xx" | "rate_limited" |
                                  "protocol_error" | "internal_error",
                    "message": "<UI 友好文案>",
                }
        """

        config = load_server_sync_config(self._config_path)
        if not config.is_configured():
            return {
                "ok": False,
                "error_code": "unconfigured",
                "message": "未配置服务端",
            }

        try:
            settings = normalize_server_sync_settings(
                server_base_url=config.base_url or "",
                bearer_token=config.bearer_token or "",
                verify_tls=config.verify_tls,
            )
        except ValueError as exc:
            return {
                "ok": False,
                "error_code": "invalid_config",
                "message": f"服务端配置无效：{exc}",
            }

        connectivity = ServerConnectivity()
        try:
            with ServerSyncClient(settings) as client:
                repository = ActivePoolRepository(client, connectivity)
                items = repository.refresh_active_list()
                details: list[ActiveAccountDetail] = []
                for item in items:
                    detail = repository.get_active_account_detail(item.account_id)
                    if detail is not None:
                        details.append(detail)
        except ServerUnreachable as exc:
            return _failure_from_exception(exc)
        except (Unauthorized, HttpsRequired) as exc:
            return _failure_from_protocol_exception(exc)
        except RateLimited as exc:
            return {
                "ok": False,
                "error_code": "rate_limited",
                "message": "服务端限频，请稍后再试",
                "retry_after": exc.retry_after,
            }
        except ProtocolError as exc:
            return {
                "ok": False,
                "error_code": "protocol_error",
                "message": exc.message,
                "status_code": exc.status_code,
            }
        except Exception as exc:  # noqa: BLE001
            logger.exception("Manual_Sync_Action 预览失败")
            return {
                "ok": False,
                "error_code": "internal_error",
                "message": f"同步失败：{exc}",
            }

        local_summaries = _load_local_summaries(self._config_path)
        try:
            candidates = compute_diff(details, local_summaries)
        except ValueError as exc:
            logger.exception("compute_diff 输入非法")
            return {
                "ok": False,
                "error_code": "internal_error",
                "message": f"差异计算失败：{exc}",
            }

        token = uuid.uuid4().hex
        with self._lock:
            self._gc_locked()
            self._sessions[token] = _PreviewSession(
                token=token,
                candidates=list(candidates),
                created_at=time.monotonic(),
            )

        return {
            "ok": True,
            "token": token,
            "state": "enabled",
            "candidates": [_serialize_candidate(c) for c in candidates],
            "summary": _summarize_kinds(candidates),
        }

    # ------------------------------------------------------------------ #
    # Stage 2：apply                                                       #
    # ------------------------------------------------------------------ #

    def apply(self, token: str, selection: dict[str, bool]) -> dict[str, Any]:
        """根据用户勾选执行 :class:`SyncApplier.apply`。

        Args:
            token: ``preview`` 返回的临时 token。
            selection: ``student_id -> bool`` 映射。仅对 ``selection[sid] is True``
                的候选执行写入；其它条目保持 Local_Account_Store 中所有字段
                完全一致。

        Returns:
            成功时返回::

                {
                    "ok": True,
                    "added": 1,
                    "replaced": 0,
                    "removed": 0,
                    "total": 1,
                    "noop": False,
                    "message": "同步成功：新增 1、替换 0、移除 0",
                }

            ``selection`` 为空 dict 或全部 False 时返回::

                {
                    "ok": True,
                    "added": 0,
                    "replaced": 0,
                    "removed": 0,
                    "total": 0,
                    "noop": True,
                    "message": "未选择任何账号，本次同步未对本地数据做任何更改",
                }

            失败时返回::

                {
                    "ok": False,
                    "error_code": "token_expired" | "internal_error",
                    "message": "<UI 友好文案>",
                }
        """

        with self._lock:
            self._gc_locked()
            session = self._sessions.pop(token, None)
        if session is None:
            return {
                "ok": False,
                "error_code": "token_expired",
                "message": "弹窗会话已过期，请重新点击同步按钮",
            }

        normalized = _normalize_selection(selection)
        if not any(value is True for value in normalized.values()):
            return {
                "ok": True,
                "added": 0,
                "replaced": 0,
                "removed": 0,
                "total": 0,
                "noop": True,
                "message": "未选择任何账号，本次同步未对本地数据做任何更改",
            }

        applier = SyncApplier(self._config_path)
        try:
            result = applier.apply(session.candidates, normalized)
        except (OSError, ValueError) as exc:
            logger.exception("Manual_Sync_Action 应用失败")
            return {
                "ok": False,
                "error_code": "internal_error",
                "message": f"同步失败：{exc}",
            }

        return {
            "ok": True,
            "added": result.added,
            "replaced": result.replaced,
            "removed": result.removed,
            "total": result.total,
            "noop": False,
            "message": (
                f"同步成功：新增 {result.added}、替换 {result.replaced}、"
                f"移除 {result.removed}"
            ),
        }

    def cancel(self, token: str) -> dict[str, Any]:
        """主动丢弃一次 preview 会话（用户点击「取消」时调用）。"""

        with self._lock:
            self._sessions.pop(token, None)
            self._gc_locked()
        return {"ok": True}

    # ------------------------------------------------------------------ #
    # 内部辅助                                                              #
    # ------------------------------------------------------------------ #

    def _gc_locked(self) -> None:
        now = time.monotonic()
        expired = [
            token
            for token, session in self._sessions.items()
            if (now - session.created_at) > _TOKEN_TTL_SECONDS
        ]
        for token in expired:
            self._sessions.pop(token, None)


# --------------------------------------------------------------------------- #
# Helpers                                                                       #
# --------------------------------------------------------------------------- #


def _failure_from_exception(exc: ServerUnreachable) -> dict[str, Any]:
    """把 :class:`ServerUnreachable` 派生异常映射为 UI 错误码。"""

    if isinstance(exc, ServerError):
        return {
            "ok": False,
            "error_code": "server_5xx",
            "message": f"服务端错误（{exc.status_code or '5xx'}）",
        }
    # NetworkError / 其它派生
    if isinstance(exc, NetworkError):
        return {
            "ok": False,
            "error_code": "server_unreachable",
            "message": "服务端不可达",
        }
    return {
        "ok": False,
        "error_code": "server_unreachable",
        "message": exc.message or "服务端不可达",
    }


def _failure_from_protocol_exception(exc: ProtocolError) -> dict[str, Any]:
    """把 :class:`Unauthorized` / :class:`HttpsRequired` 翻译为 UI 错误码。"""

    if isinstance(exc, Unauthorized):
        return {
            "ok": False,
            "error_code": "unauthorized_401",
            "message": "服务端鉴权失败（401）",
        }
    if isinstance(exc, HttpsRequired):
        return {
            "ok": False,
            "error_code": "https_required_426",
            "message": "服务端要求 HTTPS（426）",
        }
    return {
        "ok": False,
        "error_code": "protocol_error",
        "message": exc.message,
        "status_code": exc.status_code,
    }


def _load_local_upload_accounts(config_path: Path) -> list[dict[str, str]]:
    """读取本地账号上行所需字段；不读取登录态文件。"""

    if not config_path.exists():
        return []
    try:
        text = config_path.read_text(encoding="utf-8")
    except OSError as exc:
        logger.warning("读取 %s 失败：%s", config_path, exc)
        return []
    if not text.strip():
        return []
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        logger.warning("config.json 解析失败：%s", exc)
        return []
    if not isinstance(payload, dict):
        return []

    raw_accounts = payload.get("accounts")
    if isinstance(raw_accounts, list):
        accounts = [item for item in raw_accounts if isinstance(item, dict)]
    else:
        accounts = [payload]

    root_login_url = payload.get("login_url")
    default_login_url = root_login_url if isinstance(root_login_url, str) else ""
    upload_accounts: list[dict[str, str]] = []
    for account in accounts:
        student_id = _text_field(account.get("student_id"))
        password = _text_field(account.get("password"))
        if not student_id or not password:
            continue
        display_name = (
            _text_field(account.get("display_name"))
            or _text_field(account.get("name"))
            or student_id
        )
        login_url = _text_field(account.get("login_url")) or default_login_url
        upload_accounts.append(
            {
                "student_id": student_id,
                "password": password,
                "display_name": display_name,
                "login_url": login_url,
            }
        )
    return upload_accounts


def _text_field(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


def _load_local_summaries(config_path: Path) -> list[LocalAccountSummary]:
    """从 ``config.json`` 中提取受管字段的本地快照。

    - 文件不存在 / 解析失败 / 顶层不是 JSON 对象 → 返回空列表（视作本地无账号）。
    - 跳过 ``student_id`` 为空字符串的账号；diff 阶段不参与。
    - ``is_server_managed`` 字段在 sync_applier 写入时维护，缺省 False；
      只有 ``True`` 的账号才会进入「remove」候选（Requirement 13.18）。
    - 重复 ``student_id``：保留最后一条；compute_diff 会自然抛出 ValueError，
      由调用方按 ``internal_error`` 报告。
    """

    if not config_path.exists():
        return []
    try:
        text = config_path.read_text(encoding="utf-8")
    except OSError as exc:
        logger.warning("读取 %s 失败：%s", config_path, exc)
        return []
    if not text.strip():
        return []
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        logger.warning("config.json 解析失败：%s", exc)
        return []
    if not isinstance(payload, dict):
        return []
    accounts = payload.get("accounts")
    if not isinstance(accounts, list):
        return []

    summaries: list[LocalAccountSummary] = []
    for account in accounts:
        if not isinstance(account, dict):
            continue
        account_name = _text_field(account.get("name")) or _text_field(
            account.get("account_name")
        )
        student_id = account.get("student_id")
        if not isinstance(student_id, str) or not student_id:
            continue
        password = account.get("password") or ""
        display_name = account.get("display_name") or ""
        is_server_managed = bool(account.get("is_server_managed", False))
        tasks = _parse_local_tasks(
            account.get("server_managed_automation_tasks", [])
        )
        summaries.append(
            LocalAccountSummary(
                account_name=account_name,
                student_id=student_id,
                password=str(password),
                display_name=str(display_name),
                automation_tasks=tasks,
                is_server_managed=is_server_managed,
            )
        )
    return summaries


def _parse_local_tasks(value: Any) -> tuple[LocalAutomationTask, ...]:
    """把 ``server_managed_automation_tasks`` 字段解析为 LocalAutomationTask 元组。

    字段不存在 / 类型不合法时按空元组处理；单条任务字段缺失时跳过该任务。
    """

    if not isinstance(value, list):
        return ()
    tasks: list[LocalAutomationTask] = []
    for raw in value:
        if not isinstance(raw, dict):
            continue
        try:
            task_id = int(raw.get("task_id"))
        except (TypeError, ValueError):
            continue
        windows = _parse_local_windows(raw.get("custom_windows", []))
        tasks.append(
            LocalAutomationTask(
                task_id=task_id,
                room_name=str(raw.get("room_name") or ""),
                seat_number=str(raw.get("seat_number") or ""),
                mode=str(raw.get("mode") or ""),
                custom_windows=windows,
                enabled=bool(raw.get("enabled", False)),
            )
        )
    return tuple(tasks)


def _parse_local_windows(value: Any) -> tuple[CustomWindow, ...]:
    if not isinstance(value, list):
        return ()
    windows: list[CustomWindow] = []
    for raw in value:
        if not isinstance(raw, dict):
            continue
        try:
            start_hour = int(raw.get("start_hour", 0))
            end_hour = int(raw.get("end_hour", 0))
        except (TypeError, ValueError):
            continue
        windows.append(
            CustomWindow(
                date=str(raw.get("date") or ""),
                start_hour=start_hour,
                end_hour=end_hour,
            )
        )
    return tuple(windows)


def upload_local_automation_plans_to_server(
    *, config_path: str | Path, plans: list[AutomationPlan]
) -> dict[str, Any]:
    """把本地 ``automation_plans.json`` 中的计划上传到服务端。"""

    config_file = Path(config_path)
    config = load_server_sync_config(config_file)
    if not config.is_configured():
        return {
            "ok": False,
            "error_code": "unconfigured",
            "message": "未配置服务端",
        }
    if not config.upload_enabled:
        return {
            "ok": False,
            "error_code": "upload_disabled",
            "message": "未启用同步上行，请先在服务端配置中勾选上行开关",
        }

    settings = config.to_server_sync_settings()
    if settings is None:
        return {
            "ok": False,
            "error_code": "invalid_config",
            "message": "服务端配置无效",
        }
    if not plans:
        return {
            "ok": True,
            "noop": True,
            "total": 0,
            "created": 0,
            "updated": 0,
            "rejected": 0,
            "message": "没有可上传的本地自动任务",
        }

    try:
        bundle = load_config_bundle(config_file)
    except Exception as exc:  # noqa: BLE001
        logger.exception("读取本地自动任务上传配置失败")
        return {
            "ok": False,
            "error_code": "invalid_config",
            "message": f"读取本地配置失败：{exc}",
        }

    account_by_name = {account.account_name: account for account in bundle.accounts}

    try:
        with ServerSyncClient(settings) as client:
            connectivity = ServerConnectivity()
            active_repo = ActivePoolRepository(client, connectivity)
            uploader = AutomationTaskUploader(
                client,
                connectivity,
                config_provider=lambda: config,
            )
            active_accounts = active_repo.refresh_active_list()
            active_account_ids = {
                item.student_id: item.account_id for item in active_accounts
            }
            detail_cache: dict[int, ActiveAccountDetail | None] = {}
            items: list[dict[str, Any]] = []
            created = updated = rejected = 0
            for plan in plans:
                account_name = str(plan.account_name or "").strip()
                if not account_name:
                    rejected += 1
                    items.append(
                        {
                            "plan_id": plan.plan_id,
                            "account_name": "",
                            "room_name": plan.room_name,
                            "seat_number": plan.seat_number,
                            "status": "rejected",
                            "reason": "missing_account_name",
                        }
                    )
                    continue
                account = account_by_name.get(account_name)
                if account is None:
                    rejected += 1
                    items.append(
                        {
                            "plan_id": plan.plan_id,
                            "account_name": account_name,
                            "room_name": plan.room_name,
                            "seat_number": plan.seat_number,
                            "status": "rejected",
                            "reason": "account_not_found",
                        }
                    )
                    continue
                student_id = str(account.student_id or "").strip()
                if not student_id:
                    rejected += 1
                    items.append(
                        {
                            "plan_id": plan.plan_id,
                            "account_name": account_name,
                            "room_name": plan.room_name,
                            "seat_number": plan.seat_number,
                            "status": "rejected",
                            "reason": "missing_student_id",
                        }
                    )
                    continue
                account_id = active_account_ids.get(student_id)
                if account_id is None:
                    rejected += 1
                    items.append(
                        {
                            "plan_id": plan.plan_id,
                            "account_name": account_name,
                            "student_id": student_id,
                            "room_name": plan.room_name,
                            "seat_number": plan.seat_number,
                            "status": "rejected",
                            "reason": "account_not_in_active_pool",
                        }
                    )
                    continue

                detail = detail_cache.get(account_id)
                if account_id not in detail_cache:
                    detail = active_repo.get_active_account_detail(account_id)
                    detail_cache[account_id] = detail
                if detail is None:
                    rejected += 1
                    items.append(
                        {
                            "plan_id": plan.plan_id,
                            "account_name": account_name,
                            "student_id": student_id,
                            "room_name": plan.room_name,
                            "seat_number": plan.seat_number,
                            "status": "rejected",
                            "reason": "account_detail_unavailable",
                        }
                    )
                    continue

                task_id = _automation_task_id_for_plan(plan)
                expected_revision = 0
                for task in detail.automation_tasks:
                    if task.task_id == task_id:
                        expected_revision = int(task.revision)
                        break

                payload = _automation_task_upsert_payload(plan)
                try:
                    result = uploader.upsert(
                        account_id,
                        task_id,
                        payload,
                        expected_revision=expected_revision,
                    )
                except AutomationTaskValidationError as exc:
                    rejected += 1
                    items.append(
                        {
                            "plan_id": plan.plan_id,
                            "account_name": account_name,
                            "student_id": student_id,
                            "room_name": plan.room_name,
                            "seat_number": plan.seat_number,
                            "status": "rejected",
                            "reason": "validation_error",
                            "errors": [
                                {"field": err.field, "message": err.message}
                                for err in exc.errors
                            ],
                        }
                    )
                    continue
                except AutomationTaskRevisionConflict as exc:
                    rejected += 1
                    items.append(
                        {
                            "plan_id": plan.plan_id,
                            "account_name": account_name,
                            "student_id": student_id,
                            "room_name": plan.room_name,
                            "seat_number": plan.seat_number,
                            "status": "rejected",
                            "reason": "revision_conflict",
                            "server_revision": exc.server_revision,
                        }
                    )
                    continue
                except AutomationTaskUploadError as exc:
                    rejected += 1
                    items.append(
                        {
                            "plan_id": plan.plan_id,
                            "account_name": account_name,
                            "student_id": student_id,
                            "room_name": plan.room_name,
                            "seat_number": plan.seat_number,
                            "status": "rejected",
                            "reason": "upload_error",
                            "message": str(exc),
                        }
                    )
                    continue

                if expected_revision == 0:
                    created += 1
                    status_label = "created"
                else:
                    updated += 1
                    status_label = "updated"
                items.append(
                    {
                        "plan_id": plan.plan_id,
                        "account_name": account_name,
                        "student_id": student_id,
                        "room_name": plan.room_name,
                        "seat_number": plan.seat_number,
                        "status": status_label,
                        "task_id": int(result.task.get("task_id") or task_id),
                        "revision": int(result.task.get("revision") or expected_revision or 0),
                        "server_time": result.server_time.isoformat()
                        if result.server_time is not None
                        else "",
                    }
                )
    except ServerUnreachable as exc:
        return _failure_from_exception(exc)
    except (Unauthorized, HttpsRequired) as exc:
        return _failure_from_protocol_exception(exc)
    except RateLimited as exc:
        return {
            "ok": False,
            "error_code": "rate_limited",
            "message": "服务端限频，请稍后再试",
            "retry_after": exc.retry_after,
        }
    except ProtocolError as exc:
        return {
            "ok": False,
            "error_code": "protocol_error",
            "message": exc.message,
            "status_code": exc.status_code,
        }
    except Exception as exc:  # noqa: BLE001
        logger.exception("上传本地自动任务失败")
        return {
            "ok": False,
            "error_code": "internal_error",
            "message": f"上传失败：{exc}",
        }

    message = f"自动任务上传完成：新增 {created}、更新 {updated}、拒绝 {rejected}"
    return {
        "ok": True,
        "total": len(plans),
        "created": created,
        "updated": updated,
        "rejected": rejected,
        "items": items,
        "message": message,
    }


def sync_server_managed_automation_plans_to_local(
    *, config_path: str | Path, automation_scheduler: LocalAutomationPlanScheduler
) -> dict[str, Any]:
    """把服务端下发到 config.json 的自动任务，落成本地计划文件。"""

    config_file = Path(config_path)
    try:
        bundle = load_config_bundle(config_file)
    except Exception as exc:  # noqa: BLE001
        logger.exception("读取本地自动任务落地配置失败")
        return {
            "ok": False,
            "error_code": "invalid_config",
            "message": f"读取本地配置失败：{exc}",
        }

    account_by_name = {account.account_name: account for account in bundle.accounts}
    account_by_student_id = {
        str(account.student_id or "").strip(): account
        for account in bundle.accounts
        if str(account.student_id or "").strip()
    }
    summaries = [
        summary for summary in _load_local_summaries(config_file) if summary.is_server_managed
    ]
    current_plans = automation_scheduler.list_plans()
    desired_plan_ids: set[str] = set()
    created = updated = removed = skipped = 0

    for summary in summaries:
        account = account_by_name.get(summary.account_name) or account_by_student_id.get(
            summary.student_id
        )
        if account is None:
            skipped += len(summary.automation_tasks)
            logger.warning(
                "本地自动任务落地时找不到账号：account_name=%s student_id=%s",
                summary.account_name,
                summary.student_id,
            )
            continue
        student_id = str(account.student_id or summary.student_id or "").strip()
        if not student_id:
            skipped += len(summary.automation_tasks)
            logger.warning(
                "本地自动任务落地时账号缺少 student_id：account_name=%s",
                account.account_name,
            )
            continue
        seat_url = account.seat_urls[0] if account.seat_urls else ""
        if not seat_url:
            skipped += len(summary.automation_tasks)
            logger.warning(
                "本地自动任务落地时账号缺少 seat_url：account_name=%s student_id=%s",
                account.account_name,
                account.student_id,
            )
            continue
        current_time = datetime.now()
        for task in summary.automation_tasks:
            if not task.room_name.strip() or not task.seat_number.strip():
                skipped += 1
                continue
            plan_id = _server_managed_plan_id(student_id, task.task_id)
            desired_plan_ids.add(plan_id)
            existing_plan = automation_scheduler.get_plan(plan_id)
            plan = build_automation_plan(
                account_name=account.account_name,
                seat_url=seat_url,
                room_id="",
                room_name=str(task.room_name).strip(),
                seat_number=str(task.seat_number).strip(),
                selected_date=current_time.date().isoformat(),
                start_hour=_DEFAULT_IMPORTED_START_HOUR,
                duration_hours=_DEFAULT_IMPORTED_DURATION_HOURS,
                reserve_enabled=True,
                checkin_enabled=True,
                checkout_enabled=True,
                continuous_reserve=True,
                reserve_time=_DEFAULT_IMPORTED_RESERVE_TIME,
                checkin_time=_DEFAULT_IMPORTED_CHECKIN_TIME,
                checkout_time=_DEFAULT_IMPORTED_CHECKOUT_TIME,
                reserve_check_interval_minutes=_DEFAULT_IMPORTED_RESERVE_CHECK_INTERVAL_MINUTES,
                now=current_time,
                existing=existing_plan,
            )
            if plan.plan_id != plan_id:
                if existing_plan is not None:
                    try:
                        automation_scheduler.delete_plan(existing_plan.plan_id)
                    except ValueError:
                        pass
                plan = replace(plan, plan_id=plan_id)
            if not task.enabled:
                plan = replace(plan, enabled=False)
            automation_scheduler.save_plan(plan)
            if existing_plan is None:
                created += 1
            else:
                updated += 1

    for plan in current_plans:
        if not plan.plan_id.startswith(_SERVER_MANAGED_PLAN_PREFIX):
            continue
        if plan.plan_id in desired_plan_ids:
            continue
        automation_scheduler.delete_plan(plan.plan_id)
        removed += 1

    message = f"本地自动任务已同步：新增 {created}、更新 {updated}、移除 {removed}、跳过 {skipped}"
    return {
        "ok": True,
        "created": created,
        "updated": updated,
        "removed": removed,
        "skipped": skipped,
        "message": message,
    }


def _automation_task_upsert_payload(plan: AutomationPlan) -> dict[str, Any]:
    return {
        "room_name": str(plan.room_name or "").strip(),
        "seat_number": str(plan.seat_number or "").strip(),
        "mode": "manual",
        "custom_windows": [],
        "enabled": bool(plan.enabled),
    }


def _automation_task_id_for_plan(plan: AutomationPlan) -> int:
    parsed = _parse_server_managed_plan_id(plan.plan_id)
    if parsed is not None and parsed[1] > 0:
        return parsed[1]
    raw = str(plan.plan_id or "").strip()
    if not raw:
        raw = f"{plan.account_name}:{plan.room_name}:{plan.seat_number}"
    digest = hashlib.sha256(raw.encode("utf-8")).digest()
    task_id = int.from_bytes(digest[:8], "big") & ((1 << 62) - 1)
    return task_id + (1 << 62)


def _server_managed_plan_id(student_id: str, task_id: int) -> str:
    return f"{_SERVER_MANAGED_PLAN_PREFIX}{student_id}:{task_id}"


def _parse_server_managed_plan_id(plan_id: str) -> tuple[str, int] | None:
    text = str(plan_id or "").strip()
    if not text.startswith(_SERVER_MANAGED_PLAN_PREFIX):
        return None
    suffix = text[len(_SERVER_MANAGED_PLAN_PREFIX) :]
    student_id, sep, task_id_text = suffix.partition(":")
    if not sep or not student_id.strip():
        return None
    try:
        task_id = int(task_id_text)
    except (TypeError, ValueError):
        return None
    if task_id <= 0:
        return None
    return student_id.strip(), task_id


def _serialize_candidate(candidate: SyncCandidate) -> dict[str, Any]:
    """把 :class:`SyncCandidate` 序列化为前端可渲染的 dict。

    刻意 **不** 输出明文密码：``server_summary`` 只含学号、备注、自动任务条目数；
    密码字段以掩码 ``"********"`` 占位（仅当服务端有该字段时），用于 UI 展示。
    """

    return {
        "kind": candidate.kind,
        "student_id": candidate.student_id,
        "default_checked": candidate.default_checked,
        "server_summary": _summarize_server(candidate.server_payload),
        "local_summary": _summarize_local(candidate.local_summary),
    }


def _summarize_server(detail: ActiveAccountDetail | None) -> dict[str, Any] | None:
    if detail is None:
        return None
    return {
        "account_id": detail.account_id,
        "student_id": detail.student_id,
        "display_name": detail.display_name,
        "password_masked": "********" if detail.password else "",
        "automation_task_count": len(detail.automation_tasks),
        "automation_tasks": [_summarize_server_task(t) for t in detail.automation_tasks],
    }


def _summarize_server_task(task: AutomationTask) -> dict[str, Any]:
    return {
        "task_id": task.task_id,
        "room_name": task.room_name,
        "seat_number": task.seat_number,
        "mode": task.mode,
        "enabled": task.enabled,
        "custom_window_count": len(task.custom_windows),
    }


def _summarize_local(local: LocalAccountSummary | None) -> dict[str, Any] | None:
    if local is None:
        return None
    return {
        "student_id": local.student_id,
        "display_name": local.display_name,
        "password_masked": "********" if local.password else "",
        "automation_task_count": len(local.automation_tasks),
        "is_server_managed": local.is_server_managed,
    }


def _summarize_kinds(candidates: list[SyncCandidate]) -> dict[str, int]:
    counts = {"add": 0, "replace": 0, "remove": 0}
    for candidate in candidates:
        counts[candidate.kind] = counts.get(candidate.kind, 0) + 1
    return counts


def _normalize_selection(selection: Any) -> dict[str, bool]:
    """把 UI 上送的 ``selection`` 强制规范化为 ``dict[str, bool]``。

    UI 端可能传入 ``{"sid": "true"}`` / 缺失某些 sid 等情况；这里统一兜底，
    对非 bool 值按 ``False`` 处理（不写入），避免 ``SyncApplier`` 误判。
    """

    if not isinstance(selection, dict):
        return {}
    normalized: dict[str, bool] = {}
    for key, value in selection.items():
        if not isinstance(key, str):
            continue
        normalized[key] = value is True
    return normalized


__all__ = [
    "ManualSyncCoordinator",
    "compute_sync_button_state",
    "get_server_sync_settings_response",
    "save_server_sync_settings_response",
]
