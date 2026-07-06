"""Dashboard routes - main landing page and account batch operations."""
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates

router = APIRouter(tags=["dashboard"])


@router.get("/", response_class=HTMLResponse)
def dashboard(request: Request, notice: str = "") -> HTMLResponse:
    """Dashboard page showing account overview and seat distribution."""
    # Import helper functions from app.py - these will be refactored later
    from prevent_auto.web.app import (
        _build_auto_reservation_log_context,
        _build_dashboard_seat_display,
        _normalize_notice,
    )
    from prevent_auto.web.runtime import build_dashboard_summary

    # Access app state through request
    app = request.app
    services = app.state.services
    settings = app.state.settings

    # Build dashboard data
    summary = build_dashboard_summary(services, settings=settings)
    all_accounts = services.account_service.list_accounts()

    # Build seat display with booking snapshots cache
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

    # Preserve top-level stats from summary
    seat_display["accountCount"] = summary["accountCount"]
    seat_display["checkedInTodayCount"] = summary["checkedInTodayCount"]
    seat_display["notReservedTodayCount"] = summary["notReservedTodayCount"]

    # Render template
    templates = Jinja2Templates(
        directory=str(settings.package_root / "src" / "prevent_auto" / "web" / "templates")
    )
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


@router.post("/accounts/check-first-enabled")
def check_first_enabled_accounts(request: Request) -> RedirectResponse:
    """Refresh booking positions for all enabled accounts."""
    from prevent_auto.web.app import _redirect_with_notice, _run_enabled_account_checks

    services = request.app.state.services
    checked_count = _run_enabled_account_checks(services)
    return _redirect_with_notice("/", f"已检测 {checked_count} 个账号")


@router.post("/accounts/run-rolling-reservation")
def run_rolling_reservation_now(
    request: Request,
    return_to: str = Form(default="/"),
) -> RedirectResponse:
    """Trigger immediate rolling reservation check."""
    from prevent_auto.web.app import (
        _normalize_next_path,
        _redirect_with_notice,
        _run_rolling_reservation_with_bootstrap,
    )

    app = request.app
    services = app.state.services
    settings = app.state.settings

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
