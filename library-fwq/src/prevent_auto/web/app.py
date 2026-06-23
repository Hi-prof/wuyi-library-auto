from __future__ import annotations

import csv
import hashlib
import hmac
import io
import logging
import re
import time
from collections.abc import AsyncIterator, Callable
from concurrent.futures import ThreadPoolExecutor
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from dataclasses import asdict, replace
from datetime import UTC, datetime
from urllib.parse import quote

from fastapi import BackgroundTasks, FastAPI, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from prevent_auto.account_pool.models import (
    AccountNotInActivePool,
    AccountPoolError,
    BulkImportItemResult,
    BulkImportItemStatus,
    BulkImportRejectReason,
    BulkImportResult,
    BulkImportRow,
    ClientKind,
    IdleEmpty,
    IllegalPoolTransition,
    MissingLoginCredentials,
    PoolCapacityExceeded,
    PoolMigrationTrigger,
    PoolStatus,
    RevisionConflict,
)
from prevent_auto.database import initialize_database, restrict_private_path
from prevent_auto.models import Account
from prevent_auto.repositories.client_api_tokens import (
    ClientApiToken,
    ClientApiTokensRepository,
)
from prevent_auto.repositories.account_pool import AccountPoolRepository
from prevent_auto.repositories.automation_tasks import AutomationTasksRepository
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogEntry,
    PoolAuditLogQuery,
)
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.account_pool_view import (
    DEFAULT_POOL_TAB,
    POOL_TAB_KEYS,
    build_pool_view_context,
    normalize_pool_tab,
)
from prevent_auto.web.api import active_account as active_account_api
from prevent_auto.web.api import automation_task as automation_task_api
from prevent_auto.web.automation_task_view import (
    build_automation_task_view_context,
)
from prevent_auto.web.exception_handlers import (
    register_account_pool_exception_handlers,
)
from prevent_auto.web.middleware import (
    DetailRateLimiter,
    HttpsRequiredMiddleware,
    install_auth_dependency_state,
    install_detail_rate_limiter,
)
from prevent_auto.web.runtime import (
    AUTO_RESERVATION_DETAILED_LOG_KEY,
    build_dashboard_summary,
    build_services,
    _format_iso_datetime,
    _format_room_seat_label,
    start_background_workers as start_runtime_background_workers,
    start_pool_reaper_async,
    stop_background_workers as stop_runtime_background_workers,
    stop_pool_reaper_async,
)

DEFAULT_WUYI_ENTRY_URL = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
LOGIN_PATH = "/login"
STATIC_PATH_PREFIX = "/static"
AUTH_COOKIE_NAME = "prevent_auto_auth"
AUTH_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 14
ACCOUNT_STATUS_CHECK_BATCH_SIZE = 3
ACCOUNT_STATUS_CHECK_BATCH_DELAY_SECONDS = 3

# /client-tokens 页面允许选择的客户端类型；与 :class:`ClientKind` 严格对齐，仅
# 暴露 ``WINDOW`` / ``ANDROID`` 两个外部客户端值（``WEB`` / ``SYSTEM`` 仅服务端内部使用）。
CLIENT_TOKEN_CLIENT_KIND_CHOICES: tuple[tuple[str, str], ...] = (
    (ClientKind.WINDOW.value, "Windows 客户端"),
    (ClientKind.ANDROID.value, "Android 客户端"),
)
CLIENT_TOKEN_CLIENT_KIND_LABELS: dict[str, str] = {
    value: label for value, label in CLIENT_TOKEN_CLIENT_KIND_CHOICES
}

# /logs 页面「池审计」Tab 的 ``audit_action`` 下拉框选项，与
# :class:`PoolAuditAction` 严格对齐；新增枚举值时需要同步追加文案，否则页面
# 不会展示。顺序按用户最常筛选的动作优先排列（迁移 / 拉黑 / 批量导入 / 抽取
# / 任务上传 / Reaper / 启动迁移）。
POOL_AUDIT_ACTION_LABELS: dict[str, str] = {
    PoolAuditAction.MIGRATE.value: "池迁移",
    PoolAuditAction.BLACKLIST_REPORT.value: "拉黑事件",
    PoolAuditAction.BULK_IMPORT.value: "批量导入",
    PoolAuditAction.RANDOM_PICK.value: "随机抽取",
    PoolAuditAction.TASK_UPLOAD.value: "自动任务上传",
    PoolAuditAction.TASK_UPLOAD_REJECTED.value: "自动任务上传被拒",
    PoolAuditAction.REAPER_TICK.value: "Reaper 扫描",
    PoolAuditAction.STARTUP_MIGRATION.value: "启动迁移",
}
_AUDIT_ACTION_OPTIONS: tuple[tuple[str, str], ...] = tuple(
    POOL_AUDIT_ACTION_LABELS.items()
)
AUTO_RESERVATION_LOG_LIMIT = 80


logger = logging.getLogger(__name__)


def create_app(
    settings: PreventAutoSettings,
    *,
    start_background_workers: bool = True,
) -> FastAPI:
    initialize_database(settings.database_path, settings=settings)
    settings.runtime_dir.mkdir(parents=True, exist_ok=True)
    restrict_private_path(settings.runtime_dir, directory=True)
    app = FastAPI(
        title="Prevent Auto",
        lifespan=_create_lifespan(start_background_workers),
    )
    app.state.settings = settings
    app.state.services = build_services(settings)

    # 注册 account-pool-tri-sync 客户端 API 的鉴权 / 限频依赖状态。
    # ``HttpsRequiredMiddleware`` 通过 add_middleware 安装，仅作用于 /api/v1/。
    app.add_middleware(
        HttpsRequiredMiddleware,
        required=settings.account_pool_https_required,
    )
    install_auth_dependency_state(
        app,
        tokens_repository=ClientApiTokensRepository(
            settings.database_path,
            token_pepper=settings.account_pool_token_pepper,
        ),
    )
    install_detail_rate_limiter(
        app,
        limiter=DetailRateLimiter(
            limit_per_minute=settings.account_pool_detail_rate_limit_per_minute,
        ),
    )
    register_account_pool_exception_handlers(app)
    active_account_api.register(app)
    automation_task_api.register(app)

    templates = Jinja2Templates(
        directory=str(settings.package_root / "src" / "prevent_auto" / "web" / "templates")
    )
    templates.env.globals["format_datetime"] = _format_iso_datetime
    templates.env.globals["format_room_seat"] = _format_room_seat_label
    app.mount(
        "/static",
        StaticFiles(directory=str(settings.package_root / "src" / "prevent_auto" / "web" / "static")),
        name="static",
    )

    @app.middleware("http")
    async def require_login(request: Request, call_next):
        request.state.is_authenticated = _is_authenticated_request(request, settings)
        if _is_public_path(request.url.path):
            return await call_next(request)
        if request.state.is_authenticated:
            return await call_next(request)
        if request.url.path.startswith("/api/"):
            return JSONResponse({"detail": "未登录"}, status_code=401)
        return RedirectResponse(
            url=f"{LOGIN_PATH}?next={quote(_build_next_path(request), safe='')}",
            status_code=303,
        )

    @app.get(LOGIN_PATH, response_class=HTMLResponse)
    def login_page(request: Request, next: str = "/") -> HTMLResponse:
        return templates.TemplateResponse(
            request=request,
            name="login.html",
            context={
                "request": request,
                "page_title": "登录",
                "next_path": _normalize_next_path(next),
                "error_message": "",
            },
        )

    @app.post(LOGIN_PATH, response_class=HTMLResponse)
    def login_submit(
        request: Request,
        username: str = Form(...),
        password: str = Form(...),
        next: str = Form("/"),
    ) -> Response:
        next_path = _normalize_next_path(next)
        if (
            username.strip() != settings.auth_username
            or password.strip() != settings.auth_password
        ):
            return templates.TemplateResponse(
                request=request,
                name="login.html",
                context={
                    "request": request,
                    "page_title": "登录",
                    "next_path": next_path,
                    "error_message": "账号或密码错误",
                },
                status_code=401,
            )

        response = RedirectResponse(url=next_path, status_code=303)
        response.set_cookie(
            key=AUTH_COOKIE_NAME,
            value=_build_auth_cookie(settings),
            httponly=True,
            samesite="lax",
            max_age=AUTH_COOKIE_MAX_AGE_SECONDS,
        )
        return response

    @app.post("/logout")
    def logout() -> RedirectResponse:
        response = RedirectResponse(url=LOGIN_PATH, status_code=303)
        response.delete_cookie(AUTH_COOKIE_NAME)
        return response

    @app.get("/", response_class=HTMLResponse)
    def dashboard(request: Request, notice: str = "") -> HTMLResponse:
        services = app.state.services
        summary = build_dashboard_summary(services, settings=settings)
        all_accounts = services.account_service.list_accounts()
        # 仪表盘的「自习室预约分布」直接读 booking_snapshots 缓存表，与号池管理
        # 活跃池 Tab 一致，避免每次打开都去打学校接口造成卡顿。
        # 缓存由 MonitorLoop 在状态检测时整体替换写入：daily_status_refresher
        # （每天 8:10）+ 「刷新预约位置」按钮触发；账号没有缓存或缓存为空时，
        # 回退到 ``last_detected_*`` 的今日缓存（snapshots_by_id）。
        snapshots_by_id = {
            snapshot["id"]: snapshot
            for snapshot in summary.get("accountSnapshots", [])
        }
        seat_display = _build_dashboard_seat_display(
            services.bridge,
            all_accounts,
            snapshots_by_id=snapshots_by_id,
            booking_snapshots_repository=services.booking_snapshots_repository,
        )
        # 顶部统计卡保持沿用 ``build_dashboard_summary`` 的「今日已签到 / 未预约」
        # 等聚合值，不被多日预约影响。
        seat_display["accountCount"] = summary["accountCount"]
        seat_display["checkedInTodayCount"] = summary["checkedInTodayCount"]
        seat_display["notReservedTodayCount"] = summary["notReservedTodayCount"]
        return templates.TemplateResponse(
            request=request,
            name="dashboard.html",
            context={
                "request": request,
                "page_title": "仪表盘",
                "summary": summary,
                "seat_display": seat_display,
                "accounts": all_accounts,
                "notice_message": _normalize_notice(notice),
                **_build_auto_reservation_log_context(services),
            },
        )

    @app.get("/seat-display")
    def seat_display_redirect() -> RedirectResponse:
        # 仪表盘和座位展示已合并到 ``/``；保留旧链接为 303 兼容入口，
        # 与 ``/accounts/new`` 同口径，避免外部书签 / 旧页面 404。
        return RedirectResponse(url="/", status_code=303)

    @app.post("/seat-display/check-first-enabled")
    def seat_display_check_first_enabled_redirect() -> RedirectResponse:
        # 旧的「刷新预约位置」表单 action 现在统一到 ``/accounts/check-first-enabled``；
        # 这里保留兼容入口：执行同样的批量检测后回到合并后的仪表盘。
        checked_count = _run_enabled_account_checks(app.state.services)
        return _redirect_with_notice("/", f"已检测 {checked_count} 个账号")

    @app.get("/accounts", response_class=HTMLResponse)
    def accounts_page(
        request: Request,
        notice: str = "",
        pool: str = DEFAULT_POOL_TAB,
        random_picked_account_id: str = "",
    ) -> HTMLResponse:
        services = app.state.services
        all_accounts = services.account_service.list_accounts()
        dashboard_summary = build_dashboard_summary(services, settings=settings)
        account_snapshots = dashboard_summary["accountSnapshots"]
        snapshots_by_id = {
            snapshot["id"]: snapshot for snapshot in account_snapshots
        }
        monitor_records_by_account = {
            account.id: services.monitor_records_repository.list_recent(
                account_id=account.id,
                limit=20,
            )
            for account in all_accounts
        }
        action_logs_by_account = {
            account.id: services.action_logs_repository.list_recent(
                account_id=account.id,
                limit=20,
            )
            for account in all_accounts
        }
        pool_view = _build_accounts_pool_view(services, pool=pool)
        random_picked_view = _build_random_picked_view(
            services,
            raw_account_id=random_picked_account_id,
        )
        # 号池管理：默认按选中池过滤主表行；号池服务未装配（缺密钥）时回退展示全部。
        if pool_view.get("pool_view_available"):
            selected_account_ids = {
                row.account_id for row in pool_view.get("pool_rows", [])
            }
            visible_accounts = [
                account
                for account in all_accounts
                if account.id in selected_account_ids
            ]
        else:
            visible_accounts = all_accounts
        # 号池管理：活跃池 Tab 直接读 booking_snapshots 缓存表，
        # 由 MonitorLoop（每天 8:10 自动刷新 + 「刷新预约位置」按钮）写入。
        bookings_by_account: dict[int, list[dict[str, object]]] = {}
        if pool_view.get("selected_pool") == "active" and visible_accounts:
            bookings_by_account = _load_pending_bookings_from_snapshots(
                services.booking_snapshots_repository,
                visible_accounts,
            )
        # 「按当前预约创建自动任务」对话框：候选账号 = 活跃池里的全部账号；
        # 已经有未软删自动任务的账号默认不勾选，避免重复合并到同 (room, seat)。
        active_accounts_for_generation = (
            _list_active_accounts_for_task_generation(services, all_accounts)
        )
        accounts_with_existing_tasks = _list_account_ids_with_active_tasks(
            settings.database_path,
            account_ids=[item["accountId"] for item in active_accounts_for_generation],
        )
        context: dict[str, object] = {
            "request": request,
            "page_title": "号池管理",
            "accounts": visible_accounts,
            "all_accounts": all_accounts,
            "account_details_by_id": {
                account.id: account for account in all_accounts
            },
            "account_snapshots": account_snapshots,
            "account_snapshots_by_id": snapshots_by_id,
            "bookings_by_account": bookings_by_account,
            "monitor_records_by_account": monitor_records_by_account,
            "action_logs_by_account": action_logs_by_account,
            "action_logs": services.action_logs_repository.list_recent(limit=50),
            "monitor_records": services.monitor_records_repository.list_recent(limit=50),
            "notice_message": _normalize_notice(notice),
            **_build_auto_reservation_log_context(services),
            "random_picked_view": random_picked_view,
            "daily_status_refresh_label": (
                dashboard_summary.get("runtime", {}).get("dailyStatusRefreshLabel", "")
                if isinstance(dashboard_summary.get("runtime"), dict)
                else ""
            ),
            "task_generation_candidates": active_accounts_for_generation,
            "task_generation_existing_account_ids": accounts_with_existing_tasks,
        }
        context.update(pool_view)
        return templates.TemplateResponse(
            request=request,
            name="accounts.html",
            context=context,
        )

    @app.get("/accounts/new", response_class=HTMLResponse)
    def new_account_page(request: Request) -> RedirectResponse:
        # 号池管理改造后「新增账号」收敛到 /accounts 页面的弹窗里，独立路由保留为
        # 兼容入口，统一重定向回主页面，避免外部书签 / 旧链接 404。
        return RedirectResponse(url="/accounts", status_code=303)

    @app.post("/accounts")
    def create_account(
        name: str = Form(""),
        student_id: str = Form(...),
        password: str = Form(""),
        login_url: str = Form(""),
        seat_url: str = Form(""),
        rebook_enabled: str | None = Form(default=None),
        rebook_trigger_minutes: str = Form("5"),
        enabled: str | None = Form(default=None),
    ) -> RedirectResponse:
        payload = _normalize_account_payload(
            name=name,
            student_id=student_id,
            password=password,
            login_url=login_url,
            seat_url=seat_url,
            rebook_enabled=rebook_enabled is not None,
            rebook_trigger_minutes=rebook_trigger_minutes,
            enabled=enabled is not None,
        )
        created_account = app.state.services.account_service.create_account(
            **payload,
        )
        login_status = _refresh_account_login_state(
            app.state.services,
            created_account.id,
        )
        return _redirect_with_notice(
            "/accounts",
            f"账号已新增：{created_account.name}，{login_status}",
        )

    @app.post("/accounts/bulk-create")
    def bulk_create_accounts(
        bulk_accounts: str = Form(""),
        enabled: str | None = Form(default=None),
    ) -> RedirectResponse:
        services = app.state.services
        entries, invalid_lines = _parse_bulk_account_entries(bulk_accounts)
        existing_accounts = services.account_service.list_accounts()
        existing_student_ids = {account.student_id for account in existing_accounts}
        existing_names = {account.name for account in existing_accounts}
        created_count = 0
        skipped_duplicates: list[str] = []
        login_failures: list[str] = []

        for student_id, password in entries:
            if student_id in existing_student_ids or student_id in existing_names:
                skipped_duplicates.append(student_id)
                continue
            payload = _normalize_account_payload(
                name="",
                student_id=student_id,
                password=password,
                login_url="",
                seat_url="",
                rebook_enabled=False,
                rebook_trigger_minutes="5",
                enabled=enabled is not None,
            )
            account = services.account_service.create_account(**payload)
            login_status = _refresh_account_login_state(services, account.id)
            created_count += 1
            existing_student_ids.add(account.student_id)
            existing_names.add(account.name)
            if not login_status.startswith("登录态已刷新"):
                login_failures.append(account.student_id)

        return _redirect_with_notice(
            "/accounts",
            _build_bulk_create_notice(
                created_count=created_count,
                skipped_duplicates=skipped_duplicates,
                invalid_lines=invalid_lines,
                login_failures=login_failures,
            ),
        )

    @app.post("/accounts/bulk-delete")
    def bulk_delete_accounts(
        account_ids: list[int] | None = Form(default=None),
    ) -> RedirectResponse:
        services = app.state.services
        selected_ids = sorted(set(account_ids or []))
        deleted_names: list[str] = []
        missing_ids: list[str] = []

        for account_id in selected_ids:
            try:
                account = services.account_service.get_account(account_id)
            except ValueError:
                missing_ids.append(str(account_id))
                continue
            services.account_service.delete_account(account_id)
            deleted_names.append(account.name)

        return _redirect_with_notice(
            "/accounts",
            _build_bulk_delete_notice(
                deleted_names=deleted_names,
                missing_ids=missing_ids,
            ),
        )

    @app.post("/accounts/check-first-enabled")
    def check_first_enabled_accounts() -> RedirectResponse:
        checked_count = _run_enabled_account_checks(app.state.services)
        return _redirect_with_notice("/", f"已检测 {checked_count} 个账号")

    @app.post("/accounts/run-rolling-reservation")
    def run_rolling_reservation_now(
        return_to: str = Form(default="/"),
    ) -> RedirectResponse:
        """立刻触发一次"检查预约 + 没预约就自动补上"。

        与 5 小时定时调度走同一条 :meth:`AutoReservationLoop.run_due_once` 路径：
        先 ``MonitorLoop.run_cycle_once`` 刷新所有启用账号的预约视图，再
        ``AutoReservationService.run_rolling_once`` 把今天到今天 + N - 1 天里所有
        Automation_Task 启用时段中**没预约**的部分立刻补齐。``reset()`` 用来强制
        让 loop 把这一轮当成"到点"，避免被内部 ``last_run_at`` 节流挡掉。

        ``return_to`` 让前端按钮可以决定回到当前页面（号池管理 / 仪表盘），
        默认回首页保持与旧行为兼容。
        """

        services = app.state.services
        services.auto_reservation_loop.reset()
        tick = _run_rolling_reservation_with_bootstrap(
            app=app,
            services=services,
            operator=settings.auth_username,
            current_time=datetime.now().astimezone(),
        )
        result = tick.run_result
        notice = (
            result.to_notice()
            if result is not None
            else "持续预约执行失败，详情请查看日志"
        )
        return _redirect_with_notice(_normalize_next_path(return_to), notice)

    @app.post("/auto-reservation/logging")
    def update_auto_reservation_logging(
        return_to: str = Form(default="/"),
        detailed_logging: str = Form(default=""),
    ) -> RedirectResponse:
        enabled = detailed_logging.strip().lower() in {
            "1",
            "true",
            "yes",
            "on",
            "enabled",
        }
        repository = getattr(app.state.services, "app_settings_repository", None)
        if repository is None:
            return _redirect_with_notice(
                _normalize_next_path(return_to),
                "补约日志设置失败：配置仓库未装配",
            )
        repository.set_bool(AUTO_RESERVATION_DETAILED_LOG_KEY, enabled)
        label = "开启" if enabled else "关闭"
        return _redirect_with_notice(
            _normalize_next_path(return_to),
            f"已{label}详细补约日志",
        )

    @app.post("/accounts/generate-tasks-from-bookings")
    def generate_tasks_from_bookings_route(
        account_ids: list[int] | None = Form(default=None),
        return_to: str = Form(default="/accounts"),
    ) -> RedirectResponse:
        """按选中账号当前预约批量生成 / 合并自动任务。

        流程：

        1. 先调用 ``MonitorLoop.run_account_once`` 刷新选中账号的 ``booking_snapshots``，
           确保读到的不是过期缓存；任意账号刷新失败不影响其它账号。
        2. 走 :meth:`AutomationTaskService.bootstrap_tasks_from_bookings`，按预约
           推导 ``custom_windows``；该方法默认 ``skip_accounts_with_existing_tasks=True``，
           已有任务的账号会被跳过——前端 UI 已经把它们排除在默认勾选之外，但用户
           手动勾上也是合法选择，这里强制传 ``False`` 让用户的勾选完全生效。
        """

        services = app.state.services
        target_url = _normalize_next_path(return_to)
        selected_ids = sorted({int(account_id) for account_id in account_ids or []})
        if not selected_ids:
            return _redirect_with_notice(
                target_url, "请先勾选至少一个账号再生成自动任务"
            )

        task_service = getattr(
            app.state, automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR, None
        )
        if task_service is None:
            return _redirect_with_notice(
                target_url, "生成失败：自动任务服务未装配（缺少 ACCOUNT_POOL_SECRET_KEY）"
            )

        # 先按选中账号刷新一遍预约视图，避免读到过期缓存。失败不阻塞生成。
        for account_id in selected_ids:
            try:
                services.monitor_loop.run_account_once(account_id)
            except Exception:  # noqa: BLE001
                # MonitorLoop 内部已按账号 try/except 兜底；这里再吞一层防御。
                continue

        result = task_service.bootstrap_tasks_from_bookings(
            selected_ids,
            operator=settings.auth_username,
            client_kind=ClientKind.WEB,
            skip_accounts_with_existing_tasks=False,
        )
        return _redirect_with_notice(target_url, result.to_notice())

    @app.post("/accounts/refresh-login-all")
    def refresh_all_account_login_states() -> RedirectResponse:
        result = _refresh_all_account_login_states(app.state.services)
        return _redirect_with_notice(
            "/accounts",
            _build_refresh_login_all_notice(result),
        )

    @app.post("/accounts/bulk-import-to-idle")
    async def bulk_import_to_idle(request: Request) -> RedirectResponse:
        """批量导入未启用池的统一端点，接受「粘贴」与「上传 CSV」两种 content-type。

        * ``application/x-www-form-urlencoded``（粘贴模式）：从表单字段
          ``paste_text`` 解析；每行一条，字段以空白或逗号分隔，顺序为
          ``student_id [password] [display_name] [note] [default_room_name]
          [default_seat_number]``。
        * ``multipart/form-data``（上传 CSV 模式）：从 ``csv_file`` 文件解析；
          首行可选表头（按列名匹配），缺少表头时按位置解析。

        实际写入由 :meth:`AccountPoolService.bulk_import_to_idle` 在单事务里逐条
        校验并落库；本端点只做「文本 → ``BulkImportRow`` 列表」的解析，不会
        二次写审计或改池归属（Requirement 5.3 / 5.4 / 10.2）。

        响应使用 303 重定向回 ``/accounts?pool=idle``，并把条目级成功 / 失败
        统计 + 失败原因写入 ``?notice=`` 文案；这样表单提交不会留下 HTML 5xx
        碎片，同时让 UI 即时刷新看到新数据。

        路由顺序备注：必须注册在 ``@app.post("/accounts/{account_id}")`` 之前，
        否则会被参数化路由误吃成 ``account_id="bulk-import-to-idle"`` 而走到
        update_account，触发 422 / 500。``check-first-enabled`` 与
        ``bulk-create`` / ``bulk-delete`` 也是同一原因放在更早位置。
        """

        services = app.state.services
        pool_service = services.account_pool_service
        if pool_service is None:
            return _redirect_with_notice(
                "/accounts?pool=idle",
                "批量导入失败：号池服务未装配（缺少 ACCOUNT_POOL_SECRET_KEY）",
            )

        content_type = (request.headers.get("content-type") or "").lower()
        try:
            if content_type.startswith("multipart/form-data"):
                form = await request.form()
                rows, parse_errors = _parse_bulk_import_csv_form(form)
            else:
                form = await request.form()
                rows, parse_errors = _parse_bulk_import_paste_form(form)
        except _BulkImportInputError as exc:
            return _redirect_with_notice(
                "/accounts?pool=idle",
                f"批量导入失败：{exc}",
            )

        if not rows and not parse_errors:
            return _redirect_with_notice(
                "/accounts?pool=idle",
                "批量导入失败：未解析到任何账号行",
            )

        result = (
            pool_service.bulk_import_to_idle(
                rows,
                operator=settings.auth_username,
            )
            if rows
            else BulkImportResult(
                total=0, success_count=0, failure_count=0, items=()
            )
        )

        return _redirect_with_notice(
            "/accounts?pool=idle",
            _build_bulk_import_to_idle_notice(
                result=result,
                parse_errors=parse_errors,
            ),
        )

    @app.post("/accounts/random-pick")
    def random_pick_from_idle(request: Request) -> RedirectResponse:
        """从 Idle_Pool 随机抽取一个账号，并回到 Idle Tab 弹出 modal 展示。

        实现遵循 spec ``account-pool-tri-sync`` task 9.3：

        * 调用 :meth:`AccountPoolService.random_pick_from_idle`，命中后 **不** 改池
          归属（Requirement 5.7）；只回带 ``random_picked_account_id`` 让 Idle Tab
          模板触发 modal 展示学号 / 备注（Requirement 5.6，密码字段绝不带回 UI）。
        * 空池命中 :class:`IdleEmpty` 时按 Requirement 5.8 不抛异常给前端，仍走
          303 重定向到 ``/accounts?pool=idle``，notice 文案以 ``idle_empty:`` 前缀
          标识，便于测试与未来 UI 提示色块区分；账号管理服务整体未装配（缺少
          ``ACCOUNT_POOL_SECRET_KEY``）则给出与批量导入端点一致的中文 toast。
        * 路由注册顺序必须在 ``@app.post("/accounts/{account_id}")`` 之前，否则会被
          参数化路由误吃成 ``account_id="random-pick"`` 进入 update_account；与
          ``bulk-import-to-idle`` / ``check-first-enabled`` 同样的考量。
        """

        services = app.state.services
        pool_service = services.account_pool_service
        if pool_service is None:
            return _redirect_with_notice(
                "/accounts?pool=idle",
                "随机抽取失败：号池服务未装配（缺少 ACCOUNT_POOL_SECRET_KEY）",
            )

        try:
            entry = pool_service.random_pick_from_idle(
                operator=settings.auth_username,
            )
        except IdleEmpty:
            # Requirement 5.8：空池只回 toast，不抛异常给前端。
            return _redirect_with_notice(
                "/accounts?pool=idle",
                "idle_empty: 未启用池为空，先批量导入或调整池后再试",
            )

        # 命中即跳回 Idle Tab 并通过 query 参数触发 modal；密码字段绝不出现在
        # URL / 模板里（Requirement 5.6）。``display_name`` 可能为空字符串，模板
        # 上会回退到「未填备注」。
        notice = f"已随机抽中：{entry.student_id}"
        return RedirectResponse(
            url=(
                "/accounts"
                "?pool=idle"
                f"&random_picked_account_id={entry.account_id}"
                f"&notice={quote(_normalize_notice(notice), safe='')}"
            ),
            status_code=303,
        )

    @app.get("/accounts/{account_id}", response_class=HTMLResponse)
    def account_detail(
        request: Request,
        account_id: int,
        notice: str = "",
    ) -> HTMLResponse:
        services = app.state.services
        account = services.account_service.get_account(account_id)
        return templates.TemplateResponse(
            request=request,
            name="account_detail.html",
            context={
                "request": request,
                "page_title": account.name,
                "account": account,
                "notice_message": _normalize_notice(notice),
                "monitor_records": services.monitor_records_repository.list_recent(
                    account_id=account_id,
                    limit=20,
                ),
                "action_logs": services.action_logs_repository.list_recent(
                    account_id=account_id,
                    limit=20,
                ),
            },
        )

    @app.get("/accounts/{account_id}/edit", response_class=HTMLResponse)
    def edit_account_page(request: Request, account_id: int) -> HTMLResponse:
        account = app.state.services.account_service.get_account(account_id)
        return templates.TemplateResponse(
            request=request,
            name="account_form.html",
            context={
                "request": request,
                "page_title": "编辑账号",
                "account": account,
            },
        )

    @app.post("/accounts/{account_id}")
    def update_account(
        account_id: int,
        name: str = Form(""),
        student_id: str = Form(...),
        password: str = Form(""),
        login_url: str = Form(""),
        seat_url: str = Form(""),
        rebook_enabled: str | None = Form(default=None),
        rebook_trigger_minutes: str = Form("5"),
        enabled: str | None = Form(default=None),
    ) -> RedirectResponse:
        current_account = app.state.services.account_service.get_account(account_id)
        payload = _normalize_account_payload(
            name=name,
            student_id=student_id,
            password=password,
            login_url=login_url,
            seat_url=seat_url,
            rebook_enabled=rebook_enabled is not None,
            rebook_trigger_minutes=rebook_trigger_minutes,
            enabled=enabled is not None,
            current_account=current_account,
        )
        updated_account = app.state.services.account_service.update_account(
            account_id,
            **payload,
        )
        login_status = _refresh_account_login_state(
            app.state.services,
            updated_account.id,
        )
        return _redirect_with_notice(
            "/accounts",
            f"账号已保存：{login_status}",
        )

    @app.post("/accounts/{account_id}/toggle")
    def toggle_account(account_id: int) -> RedirectResponse:
        account = app.state.services.account_service.get_account(account_id)
        next_enabled = not account.enabled
        app.state.services.account_service.set_enabled(account_id, enabled=next_enabled)
        return _redirect_with_notice(
            "/accounts",
            f"{account.name} 已{'启用' if next_enabled else '停用'}",
        )

    @app.post("/accounts/{account_id}/delete")
    def delete_account(account_id: int) -> RedirectResponse:
        account = app.state.services.account_service.get_account(account_id)
        app.state.services.account_service.delete_account(account_id)
        return _redirect_with_notice("/accounts", f"账号已删除：{account.name}")

    @app.post("/accounts/{account_id}/check-now")
    def check_account_now(
        account_id: int,
        return_to: str = Form(default="/accounts"),
    ) -> RedirectResponse:
        app.state.services.monitor_loop.run_account_once(account_id)
        account = app.state.services.account_service.get_account(account_id)
        return _redirect_with_notice(
            _normalize_next_path(return_to),
            account.last_status or "立即检测已完成",
        )

    @app.post("/accounts/{account_id}/refresh-login")
    def refresh_login(account_id: int) -> RedirectResponse:
        status_message = _refresh_account_login_state(app.state.services, account_id)
        return _redirect_with_notice("/accounts", status_message)

    @app.post("/accounts/{account_id}/cancel-current")
    def cancel_current_booking(account_id: int) -> RedirectResponse:
        status_message = app.state.services.monitor_loop.cancel_current_booking_once(
            account_id
        )
        return _redirect_with_notice("/accounts", status_message)

    @app.post("/accounts/{account_id}/migrate")
    def migrate_account_pool(
        account_id: int,
        target_pool: str = Form(...),
        pool: str = Form(DEFAULT_POOL_TAB),
    ) -> RedirectResponse:
        """跨池迁移端点，把请求转发到 ``AccountPoolService.migrate``。

        前端不强制阻止非法迁移：``target_pool`` 取值由用户选择三个独立按钮决定，
        服务端按合法迁移矩阵 / 容量 / 缺登录字段返回业务异常，由本视图翻译成
        中文 toast 写回 ``?notice=``（design「Account_Pool_Web_Page UI 设计」、
        Requirement 4.6 / 4.8 / 5.9）。

        ``partial_failure`` 语义：当 ``AccountPoolService.migrate`` 已经把状态变更
        提交到数据库（同事务的成功审计也已落库），后续的 UI 刷新或外围审计仍可能
        因下游故障失败。本端点通过捕获非 :class:`AccountPoolError` 的兜底异常并
        以 ``notice=partial_failure`` 重定向告知用户：状态已变更不会回滚（与
        Requirement 4.8 一致）；服务端不可达 / 服务未装配等环境性问题走单独的
        notice 文案。
        """

        services = app.state.services
        pool_service = services.account_pool_service
        return_pool = normalize_pool_tab(pool)
        if pool_service is None:
            return _redirect_with_notice(
                f"/accounts?pool={return_pool}",
                "号池服务未装配（缺少 ACCOUNT_POOL_SECRET_KEY）",
            )

        target = (target_pool or "").strip().lower()
        if target not in POOL_TAB_KEYS:
            return _redirect_with_notice(
                f"/accounts?pool={return_pool}",
                "未知的目标池",
            )
        target_status = PoolStatus(target)

        try:
            pool_service.migrate(
                account_id,
                target_status,
                operator=settings.auth_username,
                trigger_source=PoolMigrationTrigger.MANUAL,
            )
        except IllegalPoolTransition:
            return _redirect_with_notice(
                f"/accounts?pool={return_pool}",
                "迁移失败：非法的池间迁移路径",
            )
        except PoolCapacityExceeded:
            return _redirect_with_notice(
                f"/accounts?pool={return_pool}",
                "迁移失败：目标池已满",
            )
        except MissingLoginCredentials:
            return _redirect_with_notice(
                f"/accounts?pool={return_pool}",
                "迁移失败：缺少登录所需字段",
            )
        except AccountNotInActivePool:
            return _redirect_with_notice(
                f"/accounts?pool={return_pool}",
                "迁移失败：账号不存在或已删除",
            )
        except AccountPoolError as exc:
            # 其它领域异常落入通用提示，避免泄露具体细节给 UI。
            return _redirect_with_notice(
                f"/accounts?pool={return_pool}",
                f"迁移失败：{exc}" if str(exc) else "迁移失败",
            )

        # 迁移已落库；下面任何外围步骤失败都视为 partial_failure。
        partial_failure = False
        try:
            if target_status is PoolStatus.IDLE:
                # 离开 Active_Pool 后清空旧的登录状态文案，避免 UI 仍显示已过期。
                services.account_service.update_status(
                    account_id,
                    last_check_at=_now_iso(),
                    last_status="账号已迁出活跃池",
                )
        except Exception:  # noqa: BLE001 - 遵循 Requirement 4.8 的不回滚策略
            partial_failure = True

        if partial_failure:
            return _redirect_with_notice(
                f"/accounts?pool={return_pool}",
                "partial_failure: 迁移已落库，UI 刷新失败",
            )

        return _redirect_with_notice(
            f"/accounts?pool={return_pool}",
            f"迁移成功：账号 #{account_id} → {target_status.value}",
        )

    @app.get("/logs", response_class=HTMLResponse)
    def logs_page(
        request: Request,
        audit: str = "",
        account_id: str = "",
        audit_action: str | None = None,
        created_after: str = "",
        created_before: str = "",
    ) -> HTMLResponse:
        services = app.state.services
        active_tab = "pool" if audit.lower() == "pool" else "default"
        context: dict[str, object] = {
            "request": request,
            "page_title": "运行日志",
            "active_tab": active_tab,
            "audit_filter_account_id": account_id.strip(),
            "audit_filter_actions": [],
            "audit_filter_created_after": created_after.strip(),
            "audit_filter_created_before": created_before.strip(),
            "audit_filter_error": "",
            "audit_action_options": _AUDIT_ACTION_OPTIONS,
        }
        if active_tab == "pool":
            audit_actions_raw = request.query_params.getlist("audit_action")
            try:
                filters = _build_pool_audit_log_query(
                    account_id=account_id,
                    audit_actions=audit_actions_raw,
                    created_after=created_after,
                    created_before=created_before,
                )
            except ValueError as exc:
                context["pool_audit_logs"] = []
                context["audit_filter_error"] = str(exc)
                context["audit_filter_actions"] = [
                    value for value in audit_actions_raw if value
                ]
            else:
                repository = services.pool_audit_log_repository
                pool_audit_logs = (
                    repository.query(filters) if repository is not None else []
                )
                context["pool_audit_logs"] = [
                    _build_pool_audit_log_row(entry) for entry in pool_audit_logs
                ]
                context["audit_filter_actions"] = [
                    action.value
                    for action in (
                        filters.audit_action
                        if isinstance(filters.audit_action, tuple)
                        else (filters.audit_action,)
                        if filters.audit_action is not None
                        else ()
                    )
                ]
        else:
            context["pool_audit_logs"] = []

        context["action_logs"] = services.action_logs_repository.list_recent(limit=50)
        context["monitor_records"] = services.monitor_records_repository.list_recent(
            limit=50
        )
        return templates.TemplateResponse(
            request=request,
            name="logs.html",
            context=context,
        )

    @app.get("/automation-tasks", response_class=HTMLResponse)
    def automation_tasks_page(
        request: Request,
        account_id: str = "",
        notice: str = "",
    ) -> HTMLResponse:
        """自动任务总览页面。

        从活跃池逐账号拉取 ``automation_tasks`` 表的非软删行，叠加最近一次
        ``task_upload`` 审计的 ``client_kind`` 字段，展示「谁推上来过」。页面上的
        手动补约按钮会读取这些已启用任务，并按任务时段补齐缺失预约。
        """

        services = app.state.services
        view_context = build_automation_task_view_context(
            account_pool_repo=AccountPoolRepository(settings.database_path),
            automation_tasks_repo=AutomationTasksRepository(settings.database_path),
            audit_repo=services.pool_audit_log_repository,
            filter_account_id=account_id,
        )
        # 「按预约生成」对话框的候选活跃账号清单（用于复选框勾选）。
        all_accounts = services.account_service.list_accounts()
        generation_candidates = _list_active_accounts_for_task_generation(
            services, all_accounts
        )
        candidate_ids = [int(c["accountId"]) for c in generation_candidates]
        existing_account_ids = _list_account_ids_with_active_tasks(
            settings.database_path, account_ids=candidate_ids
        )

        context: dict[str, object] = {
            "request": request,
            "page_title": "自动任务",
            "notice_message": _normalize_notice(notice),
            "task_generation_candidates": generation_candidates,
            "task_generation_existing_account_ids": existing_account_ids,
            **_build_auto_reservation_log_context(services),
        }
        context.update(view_context)
        return templates.TemplateResponse(
            request=request,
            name="automation_tasks.html",
            context=context,
        )

    @app.post("/automation-tasks/run-auto-reserve")
    def automation_task_run_auto_reserve(
        account_id: str = Form(""),
    ) -> RedirectResponse:
        """手动触发自动任务滚动补约检查。

        ``account_id`` 为空时走全局 :class:`AutoReservationLoop`，会先刷新预约视图再
        按配置补齐今天 / 明天 / 后天；有值时只刷新并滚动检查该账号，和页面筛选框保持
        同一语义。
        """

        target_url = _automation_tasks_filter_url(account_id)
        try:
            target_account_id = _parse_optional_positive_int(account_id)
        except ValueError:
            return _redirect_with_notice(
                "/automation-tasks",
                "检查失败：账号 ID 必须是正整数",
            )

        services = app.state.services
        service = getattr(services, "auto_reservation_service", None)
        if service is None:
            return _redirect_with_notice(target_url, "检查失败：自动预约服务未装配")
        current_time = datetime.now().astimezone()
        if target_account_id is None:
            services.auto_reservation_loop.reset()
            tick = _run_rolling_reservation_with_bootstrap(
                app=app,
                services=services,
                operator=settings.auth_username,
                current_time=current_time,
            )
            result = tick.run_result
            if result is None:
                return _redirect_with_notice(
                    target_url, "持续预约执行失败，详情请查看日志"
                )
        else:
            services.monitor_loop.run_account_once(target_account_id)
            result = service.run_rolling_once(
                account_id=target_account_id,
                current_time=current_time,
                days_ahead=settings.rolling_reservation_days_ahead,
            )
        return _redirect_with_notice(target_url, result.to_notice())

    @app.post("/automation-tasks/bulk-enabled")
    def automation_task_bulk_enabled(
        enabled: str = Form(...),
        account_id: str = Form(""),
    ) -> RedirectResponse:
        """批量打开或关闭自动任务。"""

        target_url = _automation_tasks_filter_url(account_id)
        try:
            target_account_id = _parse_optional_positive_int(account_id)
        except ValueError:
            return _redirect_with_notice(
                "/automation-tasks",
                "批量操作失败：账号 ID 必须是正整数",
            )
        target_enabled = enabled.strip().lower() in {"1", "true", "enabled", "on"}

        service = getattr(
            app.state, automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR, None
        )
        if service is None:
            return _redirect_with_notice(
                target_url, "批量操作失败：自动任务服务未装配"
            )
        try:
            result = service.set_enabled_for_active_tasks(
                enabled=target_enabled,
                account_id=target_account_id,
                operator=settings.auth_username,
                client_kind=ClientKind.WEB,
            )
        except AccountNotInActivePool:
            return _redirect_with_notice(
                target_url, "批量操作失败：账号已不在活跃池"
            )
        action_label = "启用" if target_enabled else "停用"
        return _redirect_with_notice(
            target_url,
            (
                f"批量{action_label}完成：匹配 {result.matched_count} 条，"
                f"更新 {result.changed_count} 条，已是目标状态 {result.unchanged_count} 条"
            ),
        )

    @app.post("/automation-tasks/generate-from-bookings")
    def automation_task_generate_from_bookings(
        background_tasks: BackgroundTasks,
        account_ids: list[int] | None = Form(default=None),
        auto_run_rolling: str = Form(default=""),
    ) -> RedirectResponse:
        """按当前预约批量生成 / 合并自动任务（异步）。

        生成与可选的滚动预约都比较耗时，前端容易看上去"卡住"。这里只在请求路径
        里做轻量校验：

        * 解析目标账号（前端勾选；为空回退全活跃池）；
        * 取出 ``AutomationTaskService`` / ``services``；
        * 立刻 303 跳回 ``/automation-tasks?notice=已开始后台生成...``。

        真正的耗时工作（``MonitorLoop.run_account_once`` × N + ``bootstrap_tasks_from_bookings``
        + 可选的 ``auto_reservation_loop.run_due_once``）通过 FastAPI ``BackgroundTasks``
        在响应发送后执行；执行结果走 logger 输出，前端可在「上传记录」与服务端
        日志里查看进度。
        """

        target_url = "/automation-tasks"
        services = app.state.services
        task_service = getattr(
            app.state, automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR, None
        )
        if task_service is None:
            return _redirect_with_notice(
                target_url,
                "按预约生成失败：自动任务服务未装配（缺少 ACCOUNT_POOL_SECRET_KEY）",
            )

        # 1) 目标账号：优先用前端勾选；为空时回退到全活跃池。
        if account_ids:
            target_ids = sorted({int(account_id) for account_id in account_ids})
        else:
            try:
                entries = AccountPoolRepository(
                    settings.database_path
                ).list_by_pool(PoolStatus.ACTIVE)
            except Exception as exc:  # noqa: BLE001
                return _redirect_with_notice(
                    target_url,
                    f"按预约生成失败：读取活跃池失败 {exc}",
                )
            target_ids = [entry.account_id for entry in entries]

        if not target_ids:
            return _redirect_with_notice(
                target_url, "按预约生成跳过：没有可处理的活跃账号"
            )

        run_rolling = auto_run_rolling.strip().lower() in {
            "1",
            "true",
            "on",
            "yes",
        }

        # 2) 把耗时工作丢到后台，立刻返回 303。
        background_tasks.add_task(
            _run_generate_from_bookings_job,
            services=services,
            task_service=task_service,
            target_ids=target_ids,
            operator=settings.auth_username,
            run_rolling=run_rolling,
        )

        rolling_hint = "并触发持续预约" if run_rolling else "不触发持续预约"
        notice = (
            f"已开始后台按预约生成任务（{len(target_ids)} 个账号，{rolling_hint}）。"
            "完成后可在本页刷新查看，或在「上传记录」中跟踪审计。"
        )
        return _redirect_with_notice(target_url, notice)


    @app.post("/automation-tasks/bulk-soft-delete")
    def automation_task_bulk_soft_delete(
        task_keys: list[str] | None = Form(default=None),
        return_to: str = Form(default="/automation-tasks"),
    ) -> RedirectResponse:
        """批量软删自动任务。

        前端通过 checkbox 提交一组 ``task_keys``，每个值是
        ``"{account_id}:{task_id}:{revision}"``。服务端解析后逐条调用
        :meth:`AutomationTaskService.soft_delete`，按"全有或部分成功"语义处理：

        * 任意一条解析失败（格式错误）整体直接拒绝，避免删错。
        * ``revision`` 冲突 / 账号不在活跃池等错误只跳过当条，其它继续。
        * 最后用 toast 汇总成功 / 失败 / 冲突数。
        """

        target_url = _normalize_next_path(return_to)
        raw_keys = list(task_keys or [])
        if not raw_keys:
            return _redirect_with_notice(
                target_url, "请先勾选至少一条自动任务再批量删除"
            )

        triples: list[tuple[int, int, int]] = []
        for raw in raw_keys:
            parts = raw.split(":")
            if len(parts) != 3:
                return _redirect_with_notice(
                    target_url, "批量删除失败：任务标识格式错误，请刷新页面重试"
                )
            try:
                account_id = int(parts[0])
                task_id = int(parts[1])
                revision = int(parts[2])
            except ValueError:
                return _redirect_with_notice(
                    target_url, "批量删除失败：任务标识必须是整数"
                )
            triples.append((account_id, task_id, revision))

        service = getattr(
            app.state, automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR, None
        )
        if service is None:
            return _redirect_with_notice(
                target_url, "批量删除失败：自动任务服务未装配"
            )

        succeeded = 0
        not_active = 0
        conflict = 0
        other_error = 0
        for account_id, task_id, revision in triples:
            try:
                service.soft_delete(
                    account_id,
                    task_id,
                    expected_revision=revision,
                    operator=settings.auth_username,
                    client_kind=ClientKind.WEB,
                )
                succeeded += 1
            except AccountNotInActivePool:
                not_active += 1
            except RevisionConflict:
                conflict += 1
            except AccountPoolError:
                other_error += 1
            except Exception:  # noqa: BLE001
                other_error += 1

        parts = [f"已下线 {succeeded} 条"]
        if not_active:
            parts.append(f"账号非活跃 {not_active} 条")
        if conflict:
            parts.append(f"版本冲突 {conflict} 条（请刷新重试）")
        if other_error:
            parts.append(f"其它失败 {other_error} 条")
        return _redirect_with_notice(target_url, "批量删除：" + "，".join(parts))

    @app.post("/automation-tasks/{account_id}/{task_id}/soft-delete")
    def automation_task_soft_delete(
        account_id: int,
        task_id: int,
        revision: int = Form(...),
    ) -> RedirectResponse:
        """运维侧对某条自动任务执行强制下线（软删）。

        ``client_kind`` 固定写 :class:`ClientKind.WEB`，``operator`` 用当前 web 登录
        用户名；客户端下次同步会通过 revision 变化感知到本次下线。冲突 / 账号不在
        活跃池等失败路径只回 toast，不破坏页面状态。
        """

        service = getattr(
            app.state, automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR, None
        )
        if service is None:
            return _redirect_with_notice(
                "/automation-tasks", "下线失败：自动任务服务未装配"
            )
        try:
            service.soft_delete(
                account_id,
                task_id,
                expected_revision=revision,
                operator=settings.auth_username,
                client_kind=ClientKind.WEB,
            )
        except AccountNotInActivePool:
            return _redirect_with_notice(
                "/automation-tasks", "下线失败：账号已不在活跃池"
            )
        except RevisionConflict as exc:
            return _redirect_with_notice(
                "/automation-tasks",
                f"下线失败：版本冲突，服务端最新 revision 为 v{exc.server_revision}，请刷新后重试",
            )
        except AccountPoolError as exc:
            return _redirect_with_notice(
                "/automation-tasks", f"下线失败：{exc}" if str(exc) else "下线失败"
            )
        return _redirect_with_notice(
            "/automation-tasks",
            f"已下线任务 #{task_id}（账号 #{account_id}）",
        )

    @app.get("/client-tokens", response_class=HTMLResponse)
    def client_tokens_page(
        request: Request,
        issued_token: str = "",
        issued_label: str = "",
        issued_id: int = 0,
        notice: str = "",
    ) -> HTMLResponse:
        repository = _get_client_api_tokens_repository(app)
        tokens = [
            _serialize_client_token_for_template(token)
            for token in repository.list(include_revoked=True)
        ]
        return templates.TemplateResponse(
            request=request,
            name="client_tokens.html",
            context={
                "request": request,
                "page_title": "客户端 Token",
                "tokens": tokens,
                "client_kind_choices": CLIENT_TOKEN_CLIENT_KIND_CHOICES,
                "issued_token": issued_token,
                "issued_label": issued_label,
                "issued_id": issued_id,
                "notice_message": _normalize_notice(notice),
            },
        )

    @app.post("/client-tokens")
    def issue_client_token(
        label: str = Form(...),
        client_kind: str = Form(...),
    ) -> RedirectResponse:
        normalized_label = label.strip()
        if not normalized_label:
            return _redirect_with_notice("/client-tokens", "签发失败：备注不能为空")
        try:
            kind = _parse_client_token_kind(client_kind)
            issued = _get_client_api_tokens_repository(app).issue(
                label=normalized_label,
                client_kind=kind,
            )
        except ValueError as exc:
            return _redirect_with_notice("/client-tokens", f"签发失败：{exc}")
        # 明文 token 经查询参数一次性回显，刷新页面后无法再获取。
        return RedirectResponse(
            url=(
                "/client-tokens"
                f"?issued_token={quote(issued.raw_token, safe='')}"
                f"&issued_label={quote(issued.record.label, safe='')}"
                f"&issued_id={issued.record.id}"
            ),
            status_code=303,
        )

    @app.post("/client-tokens/{token_id}/revoke")
    def revoke_client_token(token_id: int) -> RedirectResponse:
        repository = _get_client_api_tokens_repository(app)
        record = repository.get_by_id(token_id)
        if record is None:
            return _redirect_with_notice("/client-tokens", "撤销失败：Token 不存在")
        if record.revoked_at is not None:
            return _redirect_with_notice(
                "/client-tokens",
                f"Token #{token_id}（{record.label}）已是撤销状态",
            )
        revoked = repository.revoke(token_id)
        message = (
            f"已撤销 Token #{token_id}（{revoked.label}）"
            if revoked is not None
            else "撤销失败：未找到可撤销的 Token"
        )
        return _redirect_with_notice("/client-tokens", message)

    @app.post("/client-tokens/{token_id}/delete")
    def delete_client_token(token_id: int) -> RedirectResponse:
        repository = _get_client_api_tokens_repository(app)
        record = repository.get_by_id(token_id)
        if record is None:
            return _redirect_with_notice("/client-tokens", "删除失败：Token 不存在")
        if record.revoked_at is None:
            return _redirect_with_notice(
                "/client-tokens",
                f"删除失败：Token #{token_id}（{record.label}）尚未撤销，请先撤销再删除",
            )
        deleted = repository.delete(token_id)
        message = (
            f"已删除 Token #{token_id}（{record.label}）"
            if deleted
            else "删除失败：未找到可删除的 Token"
        )
        return _redirect_with_notice("/client-tokens", message)

    @app.post("/client-tokens/purge-revoked")
    def purge_revoked_tokens() -> RedirectResponse:
        repository = _get_client_api_tokens_repository(app)
        count = repository.delete_all_revoked()
        message = (
            f"已清除 {count} 条已撤销的 Token 记录"
            if count > 0
            else "没有需要清除的已撤销 Token"
        )
        return _redirect_with_notice("/client-tokens", message)

    @app.get("/api/dashboard")
    def dashboard_api() -> JSONResponse:
        summary = build_dashboard_summary(app.state.services, settings=settings)
        summary["attentionAccounts"] = [
            asdict(account) for account in summary["attentionAccounts"]
        ]
        return JSONResponse(summary)

    @app.get("/api/accounts")
    def accounts_api() -> JSONResponse:
        return JSONResponse([asdict(account) for account in app.state.services.account_service.list_accounts()])

    @app.post("/api/accounts")
    async def create_account_api(request: Request) -> JSONResponse:
        payload = await request.json()
        normalized_payload = _normalize_account_payload(
            name=payload.get("name"),
            student_id=str(payload["student_id"]),
            password=str(payload.get("password", "")),
            login_url=payload.get("login_url"),
            seat_url=payload.get("seat_url"),
            rebook_enabled=bool(payload.get("rebook_enabled", False)),
            rebook_trigger_minutes=str(payload.get("rebook_trigger_minutes", "5")),
            enabled=bool(payload.get("enabled", True)),
        )
        account = app.state.services.account_service.create_account(
            **normalized_payload,
        )
        _refresh_account_login_state(app.state.services, account.id)
        return JSONResponse(asdict(app.state.services.account_service.get_account(account.id)))

    @app.put("/api/accounts/{account_id}")
    async def update_account_api(account_id: int, request: Request) -> JSONResponse:
        payload = await request.json()
        current_account = app.state.services.account_service.get_account(account_id)
        normalized_payload = _normalize_account_payload(
            name=payload.get("name"),
            student_id=str(payload["student_id"]),
            password=str(payload.get("password", "")),
            login_url=payload.get("login_url"),
            seat_url=payload.get("seat_url"),
            rebook_enabled=bool(payload.get("rebook_enabled", False)),
            rebook_trigger_minutes=str(payload.get("rebook_trigger_minutes", "5")),
            enabled=bool(payload.get("enabled", True)),
            current_account=current_account,
        )
        account = app.state.services.account_service.update_account(
            account_id,
            **normalized_payload,
        )
        _refresh_account_login_state(app.state.services, account.id)
        return JSONResponse(asdict(app.state.services.account_service.get_account(account.id)))

    @app.post("/api/accounts/{account_id}/enable")
    def enable_account_api(account_id: int) -> JSONResponse:
        app.state.services.account_service.set_enabled(account_id, enabled=True)
        return JSONResponse({"ok": True})

    @app.post("/api/accounts/{account_id}/disable")
    def disable_account_api(account_id: int) -> JSONResponse:
        app.state.services.account_service.set_enabled(account_id, enabled=False)
        return JSONResponse({"ok": True})

    @app.post("/api/accounts/{account_id}/check-now")
    def check_now_api(account_id: int) -> JSONResponse:
        app.state.services.monitor_loop.run_account_once(account_id)
        return JSONResponse({"ok": True})

    @app.get("/api/accounts/{account_id}/logs")
    def account_logs_api(account_id: int) -> JSONResponse:
        return JSONResponse(
            {
                "monitorRecords": app.state.services.monitor_records_repository.list_recent(account_id=account_id, limit=50),
                "actionLogs": [asdict(item) for item in app.state.services.action_logs_repository.list_recent(account_id=account_id, limit=50)],
            }
        )

    return app


def _run_enabled_account_checks(services) -> int:
    accounts = services.account_service.list_enabled_accounts()
    for index, account in enumerate(accounts):
        if index > 0 and index % ACCOUNT_STATUS_CHECK_BATCH_SIZE == 0:
            time.sleep(ACCOUNT_STATUS_CHECK_BATCH_DELAY_SECONDS)
        services.monitor_loop.run_account_once(account.id)
    return len(accounts)


def _refresh_all_account_login_states(services) -> dict[str, int]:
    accounts = services.account_service.list_accounts()
    success_count = 0
    failure_count = 0
    for index, account in enumerate(accounts):
        if index > 0 and index % ACCOUNT_STATUS_CHECK_BATCH_SIZE == 0:
            time.sleep(ACCOUNT_STATUS_CHECK_BATCH_DELAY_SECONDS)
        status_message = _refresh_account_login_state(services, account.id)
        if status_message.startswith("登录态已刷新"):
            success_count += 1
        else:
            failure_count += 1
    return {
        "total_count": len(accounts),
        "success_count": success_count,
        "failure_count": failure_count,
    }


def _normalize_account_payload(
    *,
    name: str | None,
    student_id: str,
    password: str,
    login_url: str | None,
    seat_url: str | None,
    rebook_enabled: bool,
    rebook_trigger_minutes: str | None,
    enabled: bool,
    current_account: Account | None = None,
) -> dict[str, str | bool]:
    student_id_value = student_id.strip()
    return {
        "name": _pick_non_empty(name, current_account.name if current_account else None, student_id_value),
        "student_id": student_id_value,
        "password": _pick_non_empty(
            password,
            student_id_value,
        ),
        "login_url": _pick_non_empty(
            login_url,
            current_account.login_url if current_account else None,
            DEFAULT_WUYI_ENTRY_URL,
        ),
        "seat_url": _pick_non_empty(
            seat_url,
            current_account.seat_url if current_account else None,
            DEFAULT_WUYI_ENTRY_URL,
        ),
        "rebook_enabled": rebook_enabled,
        "rebook_trigger_minutes": _normalize_rebook_trigger_minutes(
            rebook_trigger_minutes,
            current_account.rebook_trigger_minutes if current_account else 5,
        ),
        "enabled": enabled,
    }


def _parse_bulk_account_entries(raw_text: str) -> tuple[list[tuple[str, str]], list[str]]:
    entries: list[tuple[str, str]] = []
    invalid_lines: list[str] = []
    for line_number, raw_line in enumerate(raw_text.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        parts = [part for part in re.split(r"[\s,，]+", line) if part]
        if len(parts) > 2:
            invalid_lines.append(f"第 {line_number} 行")
            continue
        student_id = parts[0].strip()
        password = parts[1].strip() if len(parts) == 2 else ""
        if not student_id:
            invalid_lines.append(f"第 {line_number} 行")
            continue
        entries.append((student_id, password))
    return entries, invalid_lines


# ----------------------------------------------------------------------------
# account-pool-tri-sync · task 9.2：批量导入未启用池的入参解析。
#
# 粘贴模式按行拆字段（空白 / 中英文逗号），CSV 模式优先 :class:`csv.DictReader`
# 按列名匹配，缺失表头时退回 :class:`csv.reader` 按位置解析。两种模式都生成
# :class:`BulkImportRow` 列表交给 :meth:`AccountPoolService.bulk_import_to_idle`
# 做条目级校验（必填 / 唯一键 / 容量），不在 web 层提前丢条目。
# ----------------------------------------------------------------------------

# 字段位置顺序：与 design「Components and Interfaces」批量导入示例对齐，前两列必填。
_BULK_IMPORT_FIELD_ORDER: tuple[str, ...] = (
    "student_id",
    "password",
    "display_name",
    "note",
    "default_room_name",
    "default_seat_number",
)
# 上传 CSV 时的最大字节数（512KB），防止恶意大文件吃满进程内存。
_BULK_IMPORT_CSV_MAX_BYTES = 512 * 1024


class _BulkImportInputError(Exception):
    """批量导入入参解析阶段的硬错误（未读到 form / 未上传文件 / 文件超限等）。

    解析阶段拒绝即整体拒绝；条目级错误（字段缺失、唯一键冲突）走
    :meth:`AccountPoolService.bulk_import_to_idle` 的逐条返回。
    """


def _parse_bulk_import_paste_form(
    form: object,
) -> tuple[list[BulkImportRow], list[str]]:
    """解析「粘贴」Tab 提交的 textarea 文本为 :class:`BulkImportRow` 列表。

    每行一条；空行跳过；行内字段顺序固定为
    ``student_id password display_name note default_room_name default_seat_number``，
    缺尾按空字符串补齐，多余字段视为格式错误（避免误把脚本拼装的密码 / 备注
    切碎）。返回 ``(rows, parse_errors)``：``rows`` 即使逐条字段非空也不在此处
    校验必填，由 service 层统一做 ``VALIDATION_ERROR`` 反馈。

    便利约定：仅给出 ``student_id`` 时，``password`` 默认填回 ``student_id`` 本身，
    与 ``/accounts/bulk-create``、``/accounts/{id}`` 编辑表单的「密码留空时默认
    和学号一致」口径保持一致，避免用户重复输入。
    """

    raw = (form.get("paste_text") or "").strip() if hasattr(form, "get") else ""
    if not isinstance(raw, str):
        raise _BulkImportInputError("粘贴内容格式不正确")

    rows: list[BulkImportRow] = []
    parse_errors: list[str] = []
    for line_number, raw_line in enumerate(raw.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        parts = [part for part in re.split(r"[\s,，]+", line) if part]
        if len(parts) > len(_BULK_IMPORT_FIELD_ORDER):
            parse_errors.append(f"第 {line_number} 行字段过多")
            continue
        # 字段缺失按空串补齐，由 service 走 VALIDATION_ERROR 路径
        padded = list(parts) + [""] * (len(_BULK_IMPORT_FIELD_ORDER) - len(parts))
        student_id_value = padded[0].strip()
        password_value = padded[1] or student_id_value
        rows.append(
            BulkImportRow(
                student_id=student_id_value,
                password=password_value,
                display_name=padded[2].strip(),
                note=padded[3].strip(),
                default_room_name=padded[4].strip(),
                default_seat_number=padded[5].strip(),
                source_row=line_number,
            )
        )
    return rows, parse_errors


def _parse_bulk_import_csv_form(
    form: object,
) -> tuple[list[BulkImportRow], list[str]]:
    """解析「上传 CSV」Tab 提交的文件为 :class:`BulkImportRow` 列表。

    优先使用 :class:`csv.DictReader` 按列名匹配（第一行存在已知列名时视为表头），
    否则退回 :class:`csv.reader` 按位置解析。无表头模式下列顺序与
    :data:`_BULK_IMPORT_FIELD_ORDER` 一致。文件大小硬上限
    :data:`_BULK_IMPORT_CSV_MAX_BYTES`，超出抛 :class:`_BulkImportInputError`，
    由路由层翻译为重定向 + notice。
    """

    upload = form.get("csv_file") if hasattr(form, "get") else None
    if upload is None or not hasattr(upload, "file"):
        raise _BulkImportInputError("缺少上传的 CSV 文件")

    file_obj = upload.file
    if file_obj is None:
        raise _BulkImportInputError("CSV 文件无法读取")

    try:
        file_obj.seek(0)
    except Exception:  # noqa: BLE001
        # 部分实现的 SpooledTemporaryFile 在已读完后 seek 可能抛错，忽略后继续读
        pass
    raw_bytes = file_obj.read(_BULK_IMPORT_CSV_MAX_BYTES + 1)
    if not raw_bytes:
        raise _BulkImportInputError("CSV 文件为空")
    if len(raw_bytes) > _BULK_IMPORT_CSV_MAX_BYTES:
        raise _BulkImportInputError(
            f"CSV 文件超过 {_BULK_IMPORT_CSV_MAX_BYTES // 1024} KB 上限"
        )

    text = _decode_csv_bytes(raw_bytes)
    return _parse_bulk_import_csv_text(text)


def _decode_csv_bytes(raw: bytes) -> str:
    """按 ``utf-8-sig`` → ``utf-8`` → ``gbk`` 顺序尝试解码 CSV 字节流。

    Excel 默认 GBK 导出的概率不低；优先 ``utf-8-sig`` 是为了吃掉 BOM。所有候选
    解码失败则把无法解码的字节替换为 ``?`` 让用户至少看到错误行号，而不是直接
    把整个上传请求 5xx。
    """

    for encoding in ("utf-8-sig", "utf-8", "gbk"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def _parse_bulk_import_csv_text(text: str) -> tuple[list[BulkImportRow], list[str]]:
    """把 CSV 文本切分为 :class:`BulkImportRow` 列表。

    检测策略：把第一行 lower-case 后与 :data:`_BULK_IMPORT_FIELD_ORDER` 取交集；
    交集非空且包含 ``student_id`` 即视为表头，走 :class:`csv.DictReader`；否则
    走 :class:`csv.reader` 按位置解析（首行也作为数据）。
    """

    stripped = text.strip("\ufeff")  # 二次兜底 BOM
    lines = stripped.splitlines()
    if not lines:
        return [], []

    first_row_fields = next(
        csv.reader(io.StringIO(lines[0])), []
    )
    normalized_first = [field.strip().lower() for field in first_row_fields]
    has_header = (
        "student_id" in normalized_first
        and any(name in _BULK_IMPORT_FIELD_ORDER for name in normalized_first)
    )

    rows: list[BulkImportRow] = []
    parse_errors: list[str] = []

    if has_header:
        reader = csv.DictReader(io.StringIO(stripped))
        for index, raw_row in enumerate(reader, start=2):
            normalized = {
                (key or "").strip().lower(): (value or "").strip()
                for key, value in raw_row.items()
            }
            if not any(normalized.values()):
                continue
            student_id_value = normalized.get("student_id", "")
            password_value = raw_row.get("password", "") or ""
            if not password_value.strip():
                password_value = student_id_value
            rows.append(
                BulkImportRow(
                    student_id=student_id_value,
                    # password 不剥首尾空白：用户故意在末尾加空格的密码场景较少，
                    # 但服务端走 raw 字符串再做 strip 由 service 决定。
                    password=password_value,
                    display_name=normalized.get("display_name", ""),
                    note=normalized.get("note", ""),
                    default_room_name=normalized.get("default_room_name", ""),
                    default_seat_number=normalized.get("default_seat_number", ""),
                    source_row=index,
                )
            )
        return rows, parse_errors

    reader = csv.reader(io.StringIO(stripped))
    for index, parts in enumerate(reader, start=1):
        if not any(part.strip() for part in parts):
            continue
        if len(parts) > len(_BULK_IMPORT_FIELD_ORDER):
            parse_errors.append(f"第 {index} 行字段过多")
            continue
        padded = list(parts) + [""] * (len(_BULK_IMPORT_FIELD_ORDER) - len(parts))
        student_id_value = padded[0].strip()
        password_value = padded[1] or student_id_value
        rows.append(
            BulkImportRow(
                student_id=student_id_value,
                password=password_value,
                display_name=padded[2].strip(),
                note=padded[3].strip(),
                default_room_name=padded[4].strip(),
                default_seat_number=padded[5].strip(),
                source_row=index,
            )
        )
    return rows, parse_errors


_BULK_IMPORT_REJECT_REASON_LABELS: dict[BulkImportRejectReason, str] = {
    BulkImportRejectReason.VALIDATION_ERROR: "字段校验失败",
    BulkImportRejectReason.DUPLICATE_STUDENT_ID: "学号已存在",
    BulkImportRejectReason.POOL_FULL: "号池已满",
}


def _build_bulk_import_to_idle_notice(
    *,
    result: BulkImportResult,
    parse_errors: list[str],
) -> str:
    """根据 :class:`BulkImportResult` 与解析阶段错误拼装 notice 文案。

    优先列出整体成功 / 失败统计，再按拒绝原因分桶展示前若干条；最后追加解析阶段
    跳过的格式错误行。整体长度仍受 :func:`_normalize_notice` 240 字符截断。
    """

    parts: list[str] = []
    parts.append(
        f"批量导入完成：共 {result.total} 条，"
        f"成功 {result.success_count} 条，失败 {result.failure_count} 条"
    )

    rejected_buckets: dict[BulkImportRejectReason, list[BulkImportItemResult]] = {
        BulkImportRejectReason.VALIDATION_ERROR: [],
        BulkImportRejectReason.DUPLICATE_STUDENT_ID: [],
        BulkImportRejectReason.POOL_FULL: [],
    }
    for item in result.items:
        if item.status is BulkImportItemStatus.REJECTED and item.reason is not None:
            rejected_buckets.setdefault(item.reason, []).append(item)

    for reason, bucket in rejected_buckets.items():
        if not bucket:
            continue
        label = _BULK_IMPORT_REJECT_REASON_LABELS.get(reason, reason.value)
        sample = _format_limited_items(
            [
                f"第 {item.source_row} 行 {item.student_id or '空学号'}".strip()
                for item in bucket
            ],
        )
        parts.append(f"{label}：{len(bucket)} 条（{sample}）")

    if parse_errors:
        parts.append(
            f"解析失败 {len(parse_errors)} 行："
            f"{_format_limited_items(parse_errors)}"
        )

    return "；".join(parts)


def _build_bulk_create_notice(
    *,
    created_count: int,
    skipped_duplicates: list[str],
    invalid_lines: list[str],
    login_failures: list[str],
) -> str:
    parts: list[str] = []
    if created_count:
        parts.append(f"批量添加完成：新增 {created_count} 个账号")
    if login_failures:
        parts.append(f"{len(login_failures)} 个账号登录态刷新失败")
    if skipped_duplicates:
        parts.append(
            f"跳过 {len(skipped_duplicates)} 个重复账号："
            f"{_format_limited_items(skipped_duplicates)}"
        )
    if invalid_lines:
        parts.append(
            f"忽略 {len(invalid_lines)} 行格式错误："
            f"{_format_limited_items(invalid_lines)}"
        )
    if parts:
        return "；".join(parts)
    return "批量添加失败：请至少填写一个学号"


def _build_bulk_delete_notice(
    *,
    deleted_names: list[str],
    missing_ids: list[str],
) -> str:
    parts: list[str] = []
    if deleted_names:
        parts.append(
            f"批量删除完成：删除 {len(deleted_names)} 个账号："
            f"{_format_limited_items(deleted_names)}"
        )
    if missing_ids:
        parts.append(
            f"{len(missing_ids)} 个账号不存在：{_format_limited_items(missing_ids)}"
        )
    if parts:
        return "；".join(parts)
    return "批量删除失败：请先选择账号"


def _build_refresh_login_all_notice(result: dict[str, int]) -> str:
    total_count = int(result.get("total_count", 0))
    success_count = int(result.get("success_count", 0))
    failure_count = int(result.get("failure_count", 0))
    if total_count <= 0:
        return "一键刷新所有登录态失败：当前没有账号"
    if failure_count:
        return (
            "一键刷新所有登录态完成："
            f"共 {total_count} 个账号，成功 {success_count} 个，失败 {failure_count} 个；"
            "失败原因已写入对应账号的最近状态"
        )
    return f"一键刷新所有登录态完成：共 {total_count} 个账号，成功 {success_count} 个"


def _format_limited_items(items: list[str], *, limit: int = 5) -> str:
    visible_items = items[:limit]
    suffix = "" if len(items) <= limit else f" 等 {len(items)} 项"
    return "、".join(visible_items) + suffix


def _pick_non_empty(*values: str | None) -> str:
    for value in values:
        if value is None:
            continue
        text = value.strip()
        if text:
            return text
    return ""


def _normalize_rebook_trigger_minutes(
    value: str | None,
    fallback: int,
) -> int:
    text = str(value or "").strip()
    if not text:
        return max(fallback, 0)
    try:
        return max(int(text), 0)
    except ValueError:
        return max(fallback, 0)


def _is_public_path(path: str) -> bool:
    if path == LOGIN_PATH or path.startswith(STATIC_PATH_PREFIX):
        return True
    # account-pool-tri-sync 客户端同步 API 走 Bearer Token，
    # 由路由级依赖与异常处理器统一接管，不应被 cookie 鉴权拦截。
    if path.startswith("/api/v1/"):
        return True
    return False


def _create_lifespan(
    start_background_workers: bool,
) -> Callable[[FastAPI], AbstractAsyncContextManager[None]]:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if start_background_workers:
            start_runtime_background_workers(app)
            await start_pool_reaper_async(app)
        try:
            yield
        finally:
            if start_background_workers:
                await stop_pool_reaper_async(app)
                stop_runtime_background_workers(app)

    return lifespan


def _is_authenticated_request(request: Request, settings: PreventAutoSettings) -> bool:
    return _is_valid_auth_cookie(request.cookies.get(AUTH_COOKIE_NAME), settings)


def _normalize_next_path(next_path: str | None) -> str:
    if not next_path:
        return "/"
    text = next_path.strip()
    if not text.startswith("/") or text.startswith("//"):
        return "/"
    return text


def _build_next_path(request: Request) -> str:
    next_path = request.url.path
    if request.url.query:
        next_path = f"{next_path}?{request.url.query}"
    return _normalize_next_path(next_path)


def _redirect_with_notice(url: str, message: str) -> RedirectResponse:
    separator = "&" if "?" in url else "?"
    return RedirectResponse(
        url=f"{url}{separator}notice={quote(_normalize_notice(message), safe='')}",
        status_code=303,
    )


def _automation_tasks_filter_url(account_id: str) -> str:
    value = account_id.strip()
    if not value:
        return "/automation-tasks"
    return f"/automation-tasks?account_id={quote(value, safe='')}"


def _build_auto_reservation_log_context(services) -> dict[str, object]:
    repository = getattr(services, "app_settings_repository", None)
    detailed_enabled = (
        repository.get_bool(AUTO_RESERVATION_DETAILED_LOG_KEY)
        if repository is not None
        else False
    )
    logs = services.action_logs_repository.list_recent(
        action_type="auto_reserve",
        limit=AUTO_RESERVATION_LOG_LIMIT,
    )
    return {
        "auto_reservation_logs": logs,
        "auto_reservation_detailed_log_enabled": detailed_enabled,
        "auto_reservation_log_limit": AUTO_RESERVATION_LOG_LIMIT,
    }


def _run_generate_from_bookings_job(
    *,
    services,
    task_service,
    target_ids: list[int],
    operator: str,
    run_rolling: bool,
) -> None:
    """后台执行：刷预约视图 → 按预约 bootstrap 任务 → 可选触发持续预约。

    通过 :class:`fastapi.BackgroundTasks` 在响应发送后调用，整段失败都吞掉但写
    ``logger``，不能让后台崩溃影响主线程。耗时来源主要是每个账号 1 次
    ``MonitorLoop.run_account_once``（同步 IO）+ ``run_due_once`` 内的滚动预约
    扫描，单次量级在分钟级，所以必须放后台。
    """

    logger.info(
        "automation_task.generate_from_bookings.bg.start "
        "account_count=%d run_rolling=%s",
        len(target_ids),
        run_rolling,
    )

    # 刷新预约视图。逐账号兜底，失败不阻塞后续。
    for refresh_id in target_ids:
        try:
            services.monitor_loop.run_account_once(refresh_id)
        except Exception:  # noqa: BLE001
            logger.exception(
                "automation_task.generate_from_bookings.bg.refresh_failed "
                "account_id=%s",
                refresh_id,
            )

    try:
        result = task_service.bootstrap_tasks_from_bookings(
            target_ids,
            operator=operator,
            client_kind=ClientKind.WEB,
            skip_accounts_with_existing_tasks=False,
        )
        logger.info(
            "automation_task.generate_from_bookings.bg.bootstrap_done %s",
            result.to_notice(),
        )
    except Exception:  # noqa: BLE001
        logger.exception(
            "automation_task.generate_from_bookings.bg.bootstrap_failed"
        )
        return

    if not run_rolling:
        return

    try:
        services.auto_reservation_loop.reset()
        tick = services.auto_reservation_loop.run_due_once(
            datetime.now().astimezone()
        )
        if tick.run_result is not None:
            logger.info(
                "automation_task.generate_from_bookings.bg.rolling_done %s",
                tick.run_result.to_notice(),
            )
        else:
            logger.warning(
                "automation_task.generate_from_bookings.bg.rolling_no_result"
            )
    except Exception:  # noqa: BLE001
        logger.exception(
            "automation_task.generate_from_bookings.bg.rolling_failed"
        )


def _run_rolling_reservation_with_bootstrap(
    *,
    app: FastAPI,
    services,
    operator: str,
    current_time: datetime,
):
    """手动滚动补约入口：任务为空时先从当前预约快照生成任务模板再重试。"""

    tick = services.auto_reservation_loop.run_due_once(current_time)
    result = tick.run_result
    checked_accounts_value = getattr(result, "checked_accounts", 0)
    checked_tasks_value = getattr(result, "checked_tasks", 1)
    checked_accounts = 0 if checked_accounts_value is None else int(checked_accounts_value)
    checked_tasks = 1 if checked_tasks_value is None else int(checked_tasks_value)
    if result is None or checked_accounts <= 0 or checked_tasks > 0:
        return tick

    task_service = getattr(
        app.state, automation_task_api.AUTOMATION_TASK_SERVICE_STATE_ATTR, None
    )
    if task_service is None:
        return tick

    try:
        entries = AccountPoolRepository(
            services.action_logs_repository.database_path
        ).list_by_pool(PoolStatus.ACTIVE)
        account_ids = [entry.account_id for entry in entries]
    except Exception:  # noqa: BLE001
        logger.exception("rolling_reservation.bootstrap.read_active_accounts_failed")
        return tick
    if not account_ids:
        return tick

    try:
        bootstrap_result = task_service.bootstrap_tasks_from_bookings(
            account_ids,
            operator=operator,
            client_kind=ClientKind.WEB,
            skip_accounts_with_existing_tasks=True,
        )
    except Exception:  # noqa: BLE001
        logger.exception("rolling_reservation.bootstrap.failed")
        return tick

    if bootstrap_result.created_count <= 0 and bootstrap_result.updated_count <= 0:
        return tick

    services.auto_reservation_loop.reset()
    retry_tick = services.auto_reservation_loop.run_due_once(current_time)
    if retry_tick.run_result is None:
        return retry_tick
    logger.info(
        "rolling_reservation.bootstrap.retry bootstrap=%s retry=%s",
        bootstrap_result.to_notice(),
        retry_tick.run_result.to_notice(),
    )
    return retry_tick


def _list_active_accounts_for_task_generation(
    services,
    accounts: list[Account],
) -> list[dict[str, object]]:
    """活跃池里的账号 → 给"按当前预约创建自动任务"对话框渲染候选清单。

    通过 :class:`AccountPoolRepository` 取活跃池成员；查询失败（号池服务相关表
    异常）时回退到空集合，模板会展示"暂无可生成任务的账号"占位。
    """

    db_path = services.action_logs_repository.database_path
    repo = AccountPoolRepository(db_path)
    try:
        entries = repo.list_by_pool(PoolStatus.ACTIVE)
    except Exception:  # noqa: BLE001
        return []
    active_account_ids = {entry.account_id for entry in entries}
    return [
        {
            "accountId": account.id,
            "studentId": account.student_id,
            "displayName": account.name,
        }
        for account in accounts
        if account.id in active_account_ids
    ]


def _list_account_ids_with_active_tasks(
    database_path,
    *,
    account_ids: list[int],
) -> set[int]:
    """返回 ``account_ids`` 中已经存在未软删自动任务的账号 ID 集合。

    用于"按当前预约创建自动任务"对话框默认不勾选——已经有任务的账号不重复生成；
    用户仍可手动勾选去合并 ``custom_windows``。
    """

    if not account_ids:
        return set()
    repo = AutomationTasksRepository(database_path)
    return {
        account_id
        for account_id in account_ids
        if repo.list_for_account(account_id)
    }


def _parse_optional_positive_int(value: str) -> int | None:
    normalized = value.strip()
    if not normalized:
        return None
    parsed = int(normalized)
    if parsed <= 0:
        raise ValueError("must be positive")
    return parsed


def _normalize_notice(message: str | None) -> str:
    if not message:
        return ""
    return message.strip()[:240]


def _build_auth_cookie(settings: PreventAutoSettings) -> str:
    digest = hmac.new(
        settings.session_secret.encode("utf-8"),
        settings.auth_username.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return f"{settings.auth_username}.{digest}"


def _is_valid_auth_cookie(cookie_value: str | None, settings: PreventAutoSettings) -> bool:
    if not cookie_value:
        return False
    expected_value = _build_auth_cookie(settings)
    return hmac.compare_digest(cookie_value, expected_value)


def _now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat()


_BOOKING_FETCH_MAX_WORKERS = 8


def _booking_to_view(
    booking,
    today_local,
    SHANGHAI_TZ,
) -> dict[str, object] | None:
    """把 :class:`BookingSnapshot` 转成模板 / 仪表盘消费的视图 dict。

    与原实时拉取路径同口径：过滤掉今天之前的历史预约，输出 ``date_label`` /
    ``time_range_label`` 等中文字段。``checkin_status_*`` 已映射颜色。
    """

    start_dt = datetime.fromtimestamp(booking.start_time, SHANGHAI_TZ)
    if start_dt.date() < today_local:
        return None
    duration = max(int(booking.duration_seconds or 0), 0)
    end_dt = datetime.fromtimestamp(
        booking.start_time + duration, SHANGHAI_TZ
    )
    if duration <= 0:
        time_range = start_dt.strftime("%H:%M")
    else:
        time_range = (
            f"{start_dt.strftime('%H:%M')}-{end_dt.strftime('%H:%M')}"
        )
    checkin_label, checkin_tone = _booking_checkin_pill(booking.status)
    return {
        "booking_id": booking.booking_id,
        "room_name": booking.room_name,
        "seat_number": booking.seat_number,
        "seat_label": (
            f"{booking.room_name} · {booking.seat_number} 号座位"
            if booking.room_name and booking.seat_number
            else "未识别到预约座位"
        ),
        "date_label": _format_booking_date_label(start_dt, today_local),
        "time_range_label": time_range,
        "checkin_status_label": checkin_label,
        "checkin_status_tone": checkin_tone,
    }


def _load_pending_bookings_from_snapshots(
    snapshots_repository,
    accounts,
) -> dict[int, list[dict[str, object]]]:
    """从 ``booking_snapshots`` 缓存表批量加载多日预约视图。

    返回字典 ``{account_id: [booking_view, ...]}``，没有缓存行的账号回落为空
    列表，与既有调用方约定一致；视图字段与原实时路径完全一致，模板无需改动。

    缓存由 :class:`MonitorLoop` 在状态检测时整体替换写入：daily_status_refresher
    （每天 8:10）+ 仪表盘 / 号池页的「刷新预约位置」按钮触发；UI 不再每次打开都
    去打学校接口。
    """

    from wuyi_seat_bot.seat_api import SHANGHAI_TZ

    if not accounts:
        return {}

    today_local = datetime.now(tz=SHANGHAI_TZ).date()
    account_ids = [account.id for account in accounts]
    snapshots_by_account = (
        snapshots_repository.list_by_account_ids(account_ids)
        if snapshots_repository is not None
        else {}
    )

    results: dict[int, list[dict[str, object]]] = {}
    for account in accounts:
        bookings = snapshots_by_account.get(account.id, [])
        views: list[dict[str, object]] = []
        for booking in bookings:
            view = _booking_to_view(booking, today_local, SHANGHAI_TZ)
            if view is not None:
                views.append(view)
        views.sort(key=lambda item: item["date_label"] + item["time_range_label"])
        results[account.id] = views
    return results


def _fetch_pending_bookings_concurrently(
    bridge,
    accounts,
) -> dict[int, list[dict[str, object]]]:
    """并发拉取每个账号未来的预约（今天起算），按账号分组返回 dict。

    返回字典 ``{account_id: [booking_view, ...]}``，``booking_view`` 字段：

    * ``room_name`` / ``seat_number`` / ``seat_label``
    * ``date_label``：``8月19日``
    * ``time_range_label``：``08:00-22:00``
    * ``checkin_status_label`` / ``checkin_status_tone``：着色后的签到状态

    单个账号拉取失败不抛异常，对应 dict value 为空列表，让模板回退到「尚未检测」。
    """

    from wuyi_seat_bot.seat_api import SHANGHAI_TZ

    if not accounts:
        return {}

    today_local = datetime.now(tz=SHANGHAI_TZ).date()

    def _fetch_one(account) -> tuple[int, list[dict[str, object]]]:
        try:
            bookings = bridge.fetch_bookings(account)
        except Exception:  # noqa: BLE001
            return account.id, []
        views: list[dict[str, object]] = []
        for booking in bookings:
            start_dt = datetime.fromtimestamp(booking.start_time, SHANGHAI_TZ)
            if start_dt.date() < today_local:
                continue
            duration = max(int(booking.duration_seconds or 0), 0)
            end_dt = datetime.fromtimestamp(
                booking.start_time + duration, SHANGHAI_TZ
            )
            if duration <= 0:
                time_range = start_dt.strftime("%H:%M")
            else:
                time_range = (
                    f"{start_dt.strftime('%H:%M')}-{end_dt.strftime('%H:%M')}"
                )
            checkin_label, checkin_tone = _booking_checkin_pill(booking.status)
            views.append(
                {
                    "booking_id": booking.booking_id,
                    "room_name": booking.room_name,
                    "seat_number": booking.seat_number,
                    "seat_label": (
                        f"{booking.room_name} · {booking.seat_number} 号座位"
                        if booking.room_name and booking.seat_number
                        else "未识别到预约座位"
                    ),
                    "date_label": _format_booking_date_label(start_dt, today_local),
                    "time_range_label": time_range,
                    "checkin_status_label": checkin_label,
                    "checkin_status_tone": checkin_tone,
                }
            )
        views.sort(key=lambda item: item["date_label"] + item["time_range_label"])
        return account.id, views

    results: dict[int, list[dict[str, object]]] = {}
    workers = min(_BOOKING_FETCH_MAX_WORKERS, len(accounts))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for account_id, views in pool.map(_fetch_one, accounts):
            results[account_id] = views
    return results


def _format_booking_date_label(start_dt: datetime, today_local) -> str:
    delta = (start_dt.date() - today_local).days
    if delta == 0:
        return "今天"
    if delta == 1:
        return "明天"
    if delta == 2:
        return "后天"
    return f"{start_dt.month}月{start_dt.day}日"


_BOOKING_CHECKIN_PRESETS: dict[str, tuple[str, str]] = {
    "0": ("待签到", "warning"),
    "1": ("已签到", "success"),
    "2": ("已签到", "success"),
    "3": ("已签退", "muted"),
    "4": ("已取消", "muted"),
    "5": ("未签到", "danger"),
    "6": ("暂离未归", "danger"),
    "7": ("系统签退", "muted"),
    "8": ("待确认", "warning"),
    "9": ("已拒绝", "muted"),
}


def _booking_checkin_pill(booking_status: str) -> tuple[str, str]:
    code = (booking_status or "").strip()
    return _BOOKING_CHECKIN_PRESETS.get(code, ("未到签到时间", "muted"))


# 仪表盘「自习室预约分布」展示的签到状态白名单：与 Window 端 seat-display
# 一致，只展示「待签到 / 在馆 / 暂离 / 待确认」之类有意义的状态，过滤掉「已签退
# / 已取消 / 已拒绝 / 系统签退」等终态。
_DASHBOARD_SEAT_DISPLAY_VISIBLE_TONES: frozenset[str] = frozenset({"warning", "success", "muted"})
_DASHBOARD_SEAT_DISPLAY_HIDDEN_LABELS: frozenset[str] = frozenset(
    {"已签退", "已取消", "已拒绝", "系统签退"}
)
# snapshot 兜底路径只信任状态码（避免 ``last_status`` 文案噪音）。与原
# ``runtime.VISIBLE_SEAT_DISPLAY_STATUSES`` 保持一致：``0=待签到`` / ``1,2=在馆 /
# 暂离`` / ``8=待确认``。
_DASHBOARD_SEAT_DISPLAY_VISIBLE_CODES: frozenset[str] = frozenset({"0", "1", "2", "8"})


def _build_dashboard_seat_display(
    bridge,
    accounts,
    *,
    snapshots_by_id: dict[int, dict[str, object]] | None = None,
    booking_snapshots_repository=None,
) -> dict[str, object]:
    """仪表盘「自习室预约分布」上下文，参考号池管理页活跃池的多日预约视图。

    与号池管理共用一份 booking 视图：默认从 ``booking_snapshots`` 缓存表读，
    避免每次打开仪表盘都去打学校接口造成卡顿。``booking_snapshots_repository``
    缺省时退回到 :func:`_fetch_pending_bookings_concurrently` 的实时路径，保留
    既有测试与极端场景的兼容性。

    单个账号没有缓存（或实时拉取失败时）会回退到 ``snapshots_by_id`` 中的
    ``last_detected_*`` 字段渲染单条今日预约，对齐 Window 客户端「失联时仍展示
    最近一次缓存」的口径。
    """

    snapshots_by_id = snapshots_by_id or {}
    if booking_snapshots_repository is not None:
        bookings_by_account = _load_pending_bookings_from_snapshots(
            booking_snapshots_repository, accounts
        )
    else:
        bookings_by_account = _fetch_pending_bookings_concurrently(bridge, accounts)

    items: list[dict[str, object]] = []
    for account in accounts:
        account_bookings = bookings_by_account.get(account.id, [])
        account_items = _convert_bookings_to_dashboard_items(account, account_bookings)
        if not account_items:
            snapshot = snapshots_by_id.get(account.id)
            if snapshot is not None:
                fallback = _convert_snapshot_to_dashboard_item(account, snapshot)
                if fallback is not None:
                    account_items = [fallback]
        items.extend(account_items)

    # 同一座位（``房间 + 座位号 + 账号``）的多个预约合并为一张卡片，内部按时间
    # 顺序展示「今天 / 明天 / 后天」等多个时段，避免重复出现同号座位的卡片。
    merged_items = _merge_dashboard_items_by_seat(items)

    # 仪表盘按「房间 → 首个时段日期 → 首个时段时间 → 座位号 → 学号」稳定排序，
    # ``dateOrder`` 来自 ``_fetch_pending_bookings_concurrently`` 内部已按
    # ``date_label + time_range_label`` 排过序的索引，避免重复解析中文日期。
    merged_items.sort(
        key=lambda item: (
            str(item["roomName"]),
            int(item.get("dateOrder", 0)),
            str(item["timeRangeLabel"]),
            _normalize_seat_sort_key(item["seatNumber"]),
            str(item["studentId"]),
        )
    )

    items_by_room: dict[str, list[dict[str, object]]] = {}
    for item in merged_items:
        room = str(item["roomName"])
        items_by_room.setdefault(room, []).append(item)

    groups: list[dict[str, object]] = []
    for room_name, room_items in items_by_room.items():
        seat_numbers: list[str] = []
        for item in room_items:
            seat = str(item["seatNumber"])
            if seat not in seat_numbers:
                seat_numbers.append(seat)
        if len(seat_numbers) > 4:
            summary = f"{'、'.join(seat_numbers[:4])} 等 {len(seat_numbers)} 个座位"
        elif seat_numbers:
            summary = f"{'、'.join(seat_numbers)} 号座位"
        else:
            summary = "暂无座位"
        groups.append(
            {
                "roomName": room_name,
                "items": room_items,
                "seatSummary": summary,
            }
        )

    return {
        "groups": groups,
        "seatCount": len(merged_items),
        "roomCount": len(groups),
    }


def _dashboard_slot_sort_key(slot: dict[str, object]) -> tuple[int, str]:
    """卡片内时段排序键。按「今天 → 明天 → 后天 → M月D日」+ 起始时间排列。

    跨账号合并后，原来仅在账号内有意义的 ``dateOrder`` 不再可靠，因此重新基于
    ``dateLabel`` 解析出排序权重；解析失败时落到 ``9999``，保证未识别值排到尾
    部，不污染主排序。
    """

    label = str(slot.get("dateLabel", "")).strip()
    if label == "今天":
        weight = 0
    elif label == "明天":
        weight = 1
    elif label == "后天":
        weight = 2
    else:
        match = re.match(r"^(\d{1,2})月(\d{1,2})日$", label)
        if match is not None:
            weight = 3 + int(match.group(1)) * 100 + int(match.group(2))
        else:
            weight = 9999
    return weight, str(slot.get("timeRangeLabel", ""))


def _merge_dashboard_items_by_seat(
    items: list[dict[str, object]],
) -> list[dict[str, object]]:
    """把同一座位号的多条预约合并为单张卡片。

    合并键为 ``(roomName, seatNumber)``：同一房间同一座位号即视为同一张卡片，
    无论被同一个账号还是不同账号预约（学校接口下，同时段内同一座位不会被多账号
    占用，跨日同号则可能不同账号）。每条原始 item 转成 ``slots`` 列表中的一项，
    保留各自的日期、时段、签到状态及账号信息，模板可在每个时段单独显示。

    卡片头部沿用首个 item 的 ``accountId`` / ``studentId`` 作为主账号链接；
    其余账号在对应时段的 slot 中以 ``studentId`` 字段呈现，保证多账号同号时
    不会丢失归属信息。
    """

    merged: dict[tuple[str, str], dict[str, object]] = {}
    order: list[tuple[str, str]] = []
    for item in items:
        key = (
            str(item.get("roomName", "")),
            str(item.get("seatNumber", "")),
        )
        slot = {
            "dateLabel": item.get("dateLabel", ""),
            "timeRangeLabel": item.get("timeRangeLabel", ""),
            "bookingStatusLabel": item.get("bookingStatusLabel", ""),
            "badgeTone": item.get("badgeTone", "muted"),
            "dateOrder": int(item.get("dateOrder", 0) or 0),
            "accountId": item.get("accountId"),
            "studentId": item.get("studentId", ""),
            "accountName": item.get("accountName", ""),
        }
        existing = merged.get(key)
        if existing is None:
            new_item = dict(item)
            new_item["slots"] = [slot]
            merged[key] = new_item
            order.append(key)
        else:
            slots = existing.setdefault("slots", [])
            slots.append(slot)

    # 合并完成后按 ``dateLabel`` 解析出真实日期权重排序卡内时段，保证「今天 →
    # 明天 → 后天 → M月D日」自然排列；外层排序仍能根据卡片首个时段决定房间内
    # 顺序，跨账号合并时也不会受原来仅账号内有效的 ``dateOrder`` 干扰。
    results: list[dict[str, object]] = []
    for key in order:
        merged_item = merged[key]
        slots = list(merged_item.get("slots", []))
        slots.sort(key=_dashboard_slot_sort_key)
        merged_item["slots"] = slots
        if slots:
            head = slots[0]
            merged_item["dateOrder"] = _dashboard_slot_sort_key(head)[0]
            merged_item["dateLabel"] = head.get("dateLabel", merged_item.get("dateLabel", ""))
            merged_item["timeRangeLabel"] = head.get(
                "timeRangeLabel", merged_item.get("timeRangeLabel", "")
            )
        results.append(merged_item)
    return results


def _convert_bookings_to_dashboard_items(
    account, account_bookings: list[dict[str, object]]
) -> list[dict[str, object]]:
    """把 ``_fetch_pending_bookings_concurrently`` 的视图转换为仪表盘 items，
    并按白名单过滤掉「已签退 / 已取消」等终态。"""

    items: list[dict[str, object]] = []
    for index, booking in enumerate(account_bookings):
        checkin_label = str(booking.get("checkin_status_label", "")).strip()
        checkin_tone = str(booking.get("checkin_status_tone", "muted")).strip()
        if checkin_label in _DASHBOARD_SEAT_DISPLAY_HIDDEN_LABELS:
            continue
        if checkin_tone not in _DASHBOARD_SEAT_DISPLAY_VISIBLE_TONES:
            continue
        room_name = str(booking.get("room_name", "")).strip()
        seat_number = str(booking.get("seat_number", "")).strip()
        if not room_name or not seat_number:
            continue
        items.append(
            {
                "accountId": account.id,
                "accountName": account.name,
                "studentId": account.student_id,
                "roomName": room_name,
                "seatNumber": seat_number,
                "seatLabel": str(booking.get("seat_label", "")).strip(),
                "dateLabel": str(booking.get("date_label", "")).strip() or "今天",
                "dateOrder": index,
                "timeRangeLabel": str(booking.get("time_range_label", "")).strip(),
                "bookingStatusLabel": checkin_label or "未知",
                "badgeTone": checkin_tone or "muted",
            }
        )
    return items


def _convert_snapshot_to_dashboard_item(
    account, snapshot: dict[str, object]
) -> dict[str, object] | None:
    """fetch 失败兜底：把 ``build_dashboard_summary`` 中的 snapshot 字段转换为
    单条仪表盘座位卡。命中条件参考原 ``_build_seat_display_item``：必须有房间
    号 + 座位号，且状态码命中「待签到 / 在馆 / 暂离 / 待确认」白名单。"""

    booking_summary = str(snapshot.get("bookingSummary", "")).strip()
    room_name = str(snapshot.get("roomName", "")).strip()
    seat_number = str(snapshot.get("seatNumber", "")).strip()
    if not room_name or not seat_number:
        parsed = (
            _extract_room_seat_from_text(booking_summary)
            or _extract_room_seat_from_text(str(snapshot.get("currentStatus", "")))
            or _extract_room_seat_from_text(str(snapshot.get("seatLabel", "")))
        )
        if parsed is not None:
            room_name, seat_number = parsed
    if not room_name or not seat_number:
        return None

    status_code = str(snapshot.get("bookingStatusCode", "")).strip()
    if status_code and status_code not in _DASHBOARD_SEAT_DISPLAY_VISIBLE_CODES:
        return None
    pill_label, pill_tone = _booking_checkin_pill(status_code)
    status_label = (
        str(snapshot.get("bookingStatusLabel", "")).strip() or pill_label or "未知"
    )
    if status_label == "未知" and "待签到" in booking_summary:
        status_label = "待签到"
        pill_tone = "warning"
    if not status_code and pill_tone == "muted" and not status_label:
        return None
    if status_label == "无预约":
        return None

    booking_time_label = str(snapshot.get("bookingTimeLabel", "")).strip()
    time_range = _extract_time_range_from_text(booking_summary) or booking_time_label

    seat_label = (
        f"{room_name} · {seat_number} 号座位"
        if room_name and seat_number
        else "未识别到预约座位"
    )

    return {
        "accountId": account.id,
        "accountName": account.name,
        "studentId": account.student_id,
        "roomName": room_name,
        "seatNumber": seat_number,
        "seatLabel": seat_label,
        "dateLabel": "今天",
        "dateOrder": 0,
        "timeRangeLabel": time_range,
        "bookingStatusLabel": status_label,
        "badgeTone": pill_tone or "muted",
    }


def _extract_time_range_from_text(text: str) -> str:
    """从中文 booking summary 中提取 ``HH:MM-HH:MM`` 时段；与 runtime 内部的
    ``_extract_booking_time_range`` 保持一致，但避免跨模块依赖。"""

    match = re.search(r"\b(\d{1,2}:\d{2}-\d{1,2}:\d{2})\b", text or "")
    if match is None:
        return ""
    return match.group(1)


def _extract_room_seat_from_text(text: str) -> tuple[str, str] | None:
    """从 ``自习室圆形二楼 166 号座位`` 类中文文案提取房间与座位号。"""

    match = re.search(r"(?:：|^)(?P<room>.+?)(?: · | )(?P<seat>\S+) 号座位", text.strip())
    if match is None:
        return None
    return match.group("room").strip(), match.group("seat").strip()


def _normalize_seat_sort_key(value: object) -> tuple[int, object]:
    text = str(value or "").strip()
    if text.isdigit():
        return (0, int(text))
    return (1, text)


def _build_accounts_pool_view(
    services,
    *,
    pool: str,
) -> dict[str, object]:
    """根据 ``services.account_pool_service`` 装配 accounts.html 三池 Tab 上下文。

    服务未装配（``ACCOUNT_POOL_SECRET_KEY`` 缺失，仅本地 / 测试场景）时回退到
    单池表格视图：把所有账号当作 Active_Pool 展示，关闭跨池迁移 / 随机抽取 /
    批量导入未启用池等高级功能（由 ``pool_advanced_available`` 控制）。生产环境
    强制要求密钥存在（settings 校验），所以这里的回退路径只服务于开发期。
    """

    from prevent_auto.web.account_pool_view import (
        LOGIN_STATUS_CACHE_MISSING_LABEL,
        PoolRowView,
        PoolTab,
    )

    selected = normalize_pool_tab(pool)
    pool_service = getattr(services, "account_pool_service", None)
    if pool_service is None:
        accounts = services.account_service.list_accounts()
        rows: list[PoolRowView] = []
        if selected == PoolStatus.ACTIVE.value:
            for account in accounts:
                rows.append(
                    PoolRowView(
                        account_id=account.id,
                        student_id=account.student_id,
                        display_name=account.name,
                        pool=PoolStatus.ACTIVE.value,
                        pool_label="活跃池",
                        enabled=bool(account.enabled),
                        enabled_label=(
                            "启用中" if account.enabled else "已停用"
                        ),
                        last_status_label=(
                            (account.last_status or "").strip() or "尚未检测"
                        ),
                        login_status_label=LOGIN_STATUS_CACHE_MISSING_LABEL,
                        suspended_at_label="",
                        remaining_label="",
                        expires_at_label="",
                        entered_at_label="",
                        pool_previous_label="",
                        migration_buttons=(),
                    )
                )
        counts = {
            PoolStatus.ACTIVE.value: len(accounts),
            PoolStatus.SUSPENDED.value: 0,
            PoolStatus.IDLE.value: 0,
        }
        labels = {
            PoolStatus.ACTIVE.value: "活跃池",
            PoolStatus.SUSPENDED.value: "拉黑号池",
            PoolStatus.IDLE.value: "未启用池",
        }
        empty_labels = {
            PoolStatus.ACTIVE.value: "活跃池暂无账号",
            PoolStatus.SUSPENDED.value: "拉黑号池暂无账号（号池服务未装配）",
            PoolStatus.IDLE.value: "未启用池暂无账号（号池服务未装配）",
        }
        tabs = tuple(
            PoolTab(
                key=key,
                label=labels[key],
                href=f"/accounts?pool={key}",
                active=(selected == key),
                count=counts[key],
            )
            for key in POOL_TAB_KEYS
        )
        return {
            "selected_pool": selected,
            "pool_tabs": tabs,
            "pool_rows": rows,
            "pool_counts": counts,
            "pool_empty_label": empty_labels[selected],
            "migration_targets": (),
            "pool_view_available": True,
            "pool_advanced_available": False,
        }
    context = build_pool_view_context(
        pool=selected,
        pool_service=pool_service,
        account_service=services.account_service,
    )
    context["pool_view_available"] = True
    context["pool_advanced_available"] = True
    return context


def _build_random_picked_view(
    services,
    *,
    raw_account_id: str,
) -> dict[str, object] | None:
    """根据 URL ``?random_picked_account_id=`` 装配 Random_Pick 模态上下文。

    * 仅在号池服务已装配且账号确实仍处于 Idle_Pool 时返回非 None 字典；其它情况
      （参数缺失 / 非整数 / 服务未装配 / 账号已被迁出 / 软删）一律返回 ``None``，
      让模板分支不渲染 modal。
    * 严格不携带密码字段（Requirement 5.6）；仅暴露 ``student_id`` / ``display_name``
      两项给模板，符合 design「Random_Pick」的「不展示密码」要求。
    * 返回字典还包含 ``migrate_target`` / ``confirm_message`` 给模板底部「迁入活跃池
      （二次确认）」按钮使用，方便复用 ``/accounts/{id}/migrate`` 端点；二次确认通过
      模板里 ``data-confirm-message`` 提示「确认迁入活跃池」。
    """

    text = (raw_account_id or "").strip()
    if not text:
        return None
    try:
        account_id = int(text)
    except ValueError:
        return None
    if account_id <= 0:
        return None

    pool_service = getattr(services, "account_pool_service", None)
    if pool_service is None:
        return None

    repo = getattr(pool_service, "_account_pool_repo", None)
    if repo is None:
        return None
    try:
        entry = repo.get_by_id(account_id)
    except Exception:  # noqa: BLE001 - 仓库异常不应影响整页渲染
        return None
    if entry is None or entry.pool_status is not PoolStatus.IDLE:
        # 账号已被迁出（手动 / 拉黑）或软删时不再触发模态。
        return None

    return {
        "account_id": entry.account_id,
        "student_id": entry.student_id,
        "display_name": entry.display_name,
        "migrate_target": PoolStatus.ACTIVE.value,
        "confirm_message": (
            f"确认把账号 {entry.student_id} 迁入活跃池"
        ),
    }


def _refresh_account_login_state(services, account_id: int) -> str:
    account = services.account_service.get_account(account_id)
    try:
        account = _resolve_account_login_password(services, account)
        state_path = services.bridge.refresh_login(account)
        _save_account_login_state_to_database(services, account, state_path)
        last_status = "登录态已刷新"
    except Exception as exc:  # noqa: BLE001
        last_status = f"刷新登录失败：{exc}"
    services.account_service.update_status(
        account_id,
        last_check_at=_now_iso(),
        last_status=last_status,
    )
    return last_status


def _resolve_account_login_password(services, account: Account) -> Account:
    if account.password:
        return account
    pool_service = getattr(services, "account_pool_service", None)
    if pool_service is None:
        return account
    password = pool_service.get_login_password(account.id)
    return replace(account, password=password)


def _save_account_login_state_to_database(services, account: Account, state_path) -> None:
    repository = getattr(services, "account_login_states_repository", None)
    if repository is None:
        raise RuntimeError("登录态数据库仓库未装配")
    repository.upsert(
        account.id,
        state_file=account.state_file,
        state_json=state_path.read_text(encoding="utf-8"),
    )


def _get_client_api_tokens_repository(app: FastAPI) -> ClientApiTokensRepository:
    """返回与本应用共享的 :class:`ClientApiTokensRepository` 实例。

    在 ``create_app`` 中已通过 :func:`install_auth_dependency_state` 把 repository
    挂到 ``app.state.account_pool_auth.tokens_repository`` 上；这里直接复用，
    避免管理页面与鉴权中间件读到不同 ``token_pepper`` 配置。
    """

    auth_state = getattr(app.state, "account_pool_auth", None)
    repository = getattr(auth_state, "tokens_repository", None)
    if repository is None:  # pragma: no cover - 仅启动顺序异常会触发
        raise RuntimeError(
            "client_api_tokens repository 未注册，请先调用 install_auth_dependency_state"
        )
    return repository


def _parse_client_token_kind(value: str) -> ClientKind:
    """把表单提交的 ``client_kind`` 字面量解析为 :class:`ClientKind`。

    页面下拉框只暴露 ``window`` / ``android``，但表单值仍可能被篡改；这里在服务
    端再做一次白名单校验，非合法值统一抛 ``ValueError`` 让上层翻译成中文 toast。
    """

    text = (value or "").strip().lower()
    if text not in CLIENT_TOKEN_CLIENT_KIND_LABELS:
        allowed = "、".join(CLIENT_TOKEN_CLIENT_KIND_LABELS.keys())
        raise ValueError(f"client_kind 必须是 {allowed} 之一")
    return ClientKind(text)


def _serialize_client_token_for_template(token: ClientApiToken) -> dict[str, object]:
    """把 :class:`ClientApiToken` 转成模板可消费的字典。

    模板里的 ``format_datetime`` 期望 ``str | None``；这里把 UTC ISO8601 ``Z``
    后缀转成 ``+00:00``，与 ``runtime._format_iso_datetime`` 的解析路径对齐。
    同时附加 ``client_kind_label`` 字段，避免在模板里调用 enum 属性。
    """

    return {
        "id": token.id,
        "label": token.label,
        "client_kind_label": CLIENT_TOKEN_CLIENT_KIND_LABELS.get(
            token.client_kind.value,
            token.client_kind.value,
        ),
        "token_preview": token.token_preview,
        "created_at": _format_optional_datetime(token.created_at),
        "revoked_at": _format_optional_datetime(token.revoked_at),
    }


def _format_optional_datetime(value: datetime | None) -> str | None:
    """把可空 :class:`datetime` 转成 :func:`datetime.fromisoformat` 可解析的文本。"""

    if value is None:
        return None
    return value.isoformat()


def _build_pool_audit_log_query(
    *,
    account_id: str,
    audit_actions: list[str],
    created_after: str,
    created_before: str,
) -> PoolAuditLogQuery:
    """把 /logs?audit=pool 的查询参数翻译成 :class:`PoolAuditLogQuery`。

    所有字段为空时返回「不过滤」的查询；任一字段格式非法（``account_id`` 不是
    正整数、``audit_action`` 取值不在 :class:`PoolAuditAction` 中、时间字段不是合
    法的 ``datetime.fromisoformat`` 文本）抛 :class:`ValueError`，由调用方翻译成
    页面顶部的中文提示，避免直接给前端展示堆栈。
    """

    parsed_account_id: int | None = None
    text_account_id = account_id.strip()
    if text_account_id:
        try:
            parsed_account_id = int(text_account_id)
        except ValueError as exc:
            raise ValueError("账号 ID 必须是整数") from exc
        if parsed_account_id <= 0:
            raise ValueError("账号 ID 必须是正整数")

    parsed_actions: list[PoolAuditAction] = []
    seen_action_values: set[str] = set()
    for raw in audit_actions:
        text = raw.strip()
        if not text or text in seen_action_values:
            continue
        try:
            parsed_actions.append(PoolAuditAction(text))
        except ValueError as exc:
            raise ValueError(f"未知的审计动作：{text}") from exc
        seen_action_values.add(text)

    parsed_after = _parse_audit_filter_datetime(
        created_after, label="起始时间"
    )
    parsed_before = _parse_audit_filter_datetime(
        created_before, label="截止时间"
    )
    if (
        parsed_after is not None
        and parsed_before is not None
        and parsed_after > parsed_before
    ):
        raise ValueError("起始时间不能晚于截止时间")

    audit_action_filter: (
        PoolAuditAction | tuple[PoolAuditAction, ...] | None
    )
    if not parsed_actions:
        audit_action_filter = None
    elif len(parsed_actions) == 1:
        audit_action_filter = parsed_actions[0]
    else:
        audit_action_filter = tuple(parsed_actions)

    return PoolAuditLogQuery(
        account_id=parsed_account_id,
        audit_action=audit_action_filter,
        created_after=parsed_after,
        created_before=parsed_before,
        limit=200,
    )


def _parse_audit_filter_datetime(value: str, *, label: str) -> datetime | None:
    """把 UI 提交的时间文本解析为 UTC aware :class:`datetime`。

    支持三种常见输入：

    * ``YYYY-MM-DDTHH:MM`` / ``YYYY-MM-DDTHH:MM:SS`` —— ``<input type="datetime-local">``
      的默认提交格式，按 Asia/Shanghai 解释（与页面其他时间字段一致）。
    * 带时区的 ISO8601（如 ``2026-04-26T08:30:00+08:00`` 或末尾 ``Z``）—— 直接解析。

    空字符串返回 ``None``；非法格式抛 :class:`ValueError`。
    """

    text = value.strip()
    if not text:
        return None
    normalized = text
    if normalized.endswith("Z"):
        normalized = normalized[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ValueError(f"{label}格式不正确，请使用 YYYY-MM-DDTHH:MM") from exc
    if parsed.tzinfo is None:
        # ``datetime-local`` 不带时区；按 Asia/Shanghai 解释，与页面其他时间一致。
        from wuyi_seat_bot.seat_api import SHANGHAI_TZ

        parsed = parsed.replace(tzinfo=SHANGHAI_TZ)

    return parsed.astimezone(UTC)


def _build_pool_audit_log_row(entry: PoolAuditLogEntry) -> dict[str, object]:
    """把 :class:`PoolAuditLogEntry` 转换为模板渲染所需的扁平字典。

    模板沿用既有 ``log-list`` 样式，列出「时间 + 动作 + 操作者 + 备注」一行多列。
    时间字段统一用 :func:`runtime._format_iso_datetime` 渲染，与现有日志列表一致。
    """

    created_at_iso = (
        entry.created_at.isoformat() if entry.created_at is not None else None
    )
    summary_parts: list[str] = []
    if entry.from_pool is not None or entry.to_pool is not None:
        from_label = entry.from_pool.value if entry.from_pool else "-"
        to_label = entry.to_pool.value if entry.to_pool else "-"
        summary_parts.append(f"{from_label} → {to_label}")
    if entry.account_id is not None:
        summary_parts.append(f"账号 #{entry.account_id}")
    if entry.task_id is not None:
        summary_parts.append(f"任务 #{entry.task_id}")
    if entry.client_kind is not None:
        summary_parts.append(f"客户端：{entry.client_kind.value}")
    if entry.reason:
        summary_parts.append(f"原因：{entry.reason}")
    return {
        "id": entry.id,
        "created_at": created_at_iso,
        "audit_action_label": POOL_AUDIT_ACTION_LABELS.get(
            entry.audit_action.value, entry.audit_action.value
        ),
        "audit_action": entry.audit_action.value,
        "trigger_source": entry.trigger_source.value,
        "operator": entry.operator,
        "success": entry.success,
        "summary": " · ".join(summary_parts) if summary_parts else "",
    }
