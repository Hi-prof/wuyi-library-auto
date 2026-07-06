from __future__ import annotations

import json
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from wuyi_seat_bot.version import __version__
from wuyi_seat_bot.web_errors import ApiRequestError


STATIC_DIR = Path(__file__).resolve().parent / "web"


class SeatWebServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self, server_address: tuple[str, int], app: SeatWebApp) -> None:
        super().__init__(server_address, SeatWebRequestHandler)
        self.app = app


class SeatWebRequestHandler(BaseHTTPRequestHandler):
    server_version = f"WuyiSeatBot/{__version__}"
    STATIC_GET_ROUTES = {
        "/": (STATIC_DIR / "index.html", "text/html; charset=utf-8"),
        "/index.html": (STATIC_DIR / "index.html", "text/html; charset=utf-8"),
        "/styles.css": (STATIC_DIR / "styles.css", "text/css; charset=utf-8"),
        "/app.js": (STATIC_DIR / "app.js", "application/javascript; charset=utf-8"),
    }
    GET_JSON_ROUTES = {
        "/api/bootstrap": "_get_bootstrap",
        "/api/accounts": "_get_accounts",
        "/api/settings": "_get_settings",
        "/api/tasks": "_get_tasks",
        "/api/automation-plans": "_get_automation_plans",
        "/api/task-status": "_get_task_status",
        "/api/sync/state": "_get_sync_state",
        "/api/server-sync/settings": "_get_server_sync_settings",
    }
    NO_CONTENT_GET_ROUTES = {"/favicon.ico"}
    POST_JSON_ROUTES = {
        "/api/search": ("search", HTTPStatus.OK, True),
        "/api/reserve": ("reserve", HTTPStatus.OK, True),
        "/api/checkin": ("checkin", HTTPStatus.OK, True),
        "/api/checkout": ("checkout", HTTPStatus.OK, True),
        "/api/bookings/cancel": ("cancel_booking", HTTPStatus.OK, True),
        "/api/accounts": ("save_account", HTTPStatus.OK, True),
        "/api/accounts/import": ("import_accounts", HTTPStatus.OK, True),
        "/api/accounts/checkin-all": ("checkin_all_accounts", HTTPStatus.OK, True),
        "/api/settings": ("save_settings", HTTPStatus.OK, True),
        "/api/settings/network/check": ("run_network_check", HTTPStatus.OK, False),
        "/api/settings/network/reconnect": ("run_network_reconnect", HTTPStatus.OK, False),
        "/api/settings/stability": ("update_stability_enhancement", HTTPStatus.OK, True),
        "/api/settings/logs/open": ("open_diagnostics_target", HTTPStatus.OK, True),
        "/api/accounts/refresh-login": ("refresh_account_login", HTTPStatus.OK, True),
        "/api/accounts/refresh-login-all": ("refresh_account_logins", HTTPStatus.OK, True),
        "/api/accounts/default": ("set_default_account", HTTPStatus.OK, True),
        "/api/accounts/delete": ("delete_account", HTTPStatus.OK, True),
        "/api/accounts/delete-batch": ("delete_accounts", HTTPStatus.OK, True),
        "/api/tasks": ("create_task", HTTPStatus.CREATED, True),
        "/api/tasks/batch": ("create_task_batch", HTTPStatus.CREATED, True),
        "/api/tasks/delete": ("delete_task", HTTPStatus.OK, True),
        "/api/automation-plans": ("save_automation_plan", HTTPStatus.CREATED, True),
        "/api/automation-plans/check-now": (
            "run_automation_reserve_now",
            HTTPStatus.OK,
            True,
        ),
        "/api/automation-plans/delete": ("delete_automation_plan", HTTPStatus.OK, True),
        "/api/sync/preview": ("preview_manual_sync", HTTPStatus.OK, True),
        "/api/sync/apply": ("apply_manual_sync", HTTPStatus.OK, True),
        "/api/sync/cancel": ("cancel_manual_sync", HTTPStatus.OK, True),
        "/api/sync/upload": ("upload_local_accounts", HTTPStatus.OK, True),
        "/api/sync/upload-automation-plans": (
            "upload_local_automation_plans",
            HTTPStatus.OK,
            True,
        ),
        "/api/server-sync/settings": ("save_server_sync_settings", HTTPStatus.OK, True),
    }

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        static_route = self.STATIC_GET_ROUTES.get(parsed.path)
        if static_route is not None:
            file_path, content_type = static_route
            self._serve_static_file(file_path, content_type)
            return
        if parsed.path in self.NO_CONTENT_GET_ROUTES:
            self.send_response(HTTPStatus.NO_CONTENT)
            self.end_headers()
            return

        route_name = self.GET_JSON_ROUTES.get(parsed.path)
        if route_name is None:
            self._send_not_found()
            return
        route = getattr(self, route_name)
        self._send_json(route(parse_qs(parsed.query)))

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        try:
            payload = self._read_json_body()
            route_info = self.POST_JSON_ROUTES.get(parsed.path)
            if route_info is None:
                self._send_not_found()
                return
            route_name, status_code, needs_payload = route_info
            route = getattr(self.server.app, route_name)
            response = route(payload) if needs_payload else route()
            self._send_json(response, status_code)
        except ApiRequestError as exc:
            self._send_json({"message": exc.message}, exc.status_code)
        except json.JSONDecodeError:
            self._send_json(
                {"message": "请求体不是合法的 JSON"}, HTTPStatus.BAD_REQUEST
            )

    def _get_bootstrap(self, query: dict[str, list[str]]) -> dict[str, Any]:
        seat_url = query.get("seatUrl", [""])[0] or None
        account_name = query.get("accountName", [""])[0] or None
        return self.server.app.get_bootstrap(
            account_name=account_name, seat_url=seat_url
        )

    def _get_accounts(self, query: dict[str, list[str]]) -> dict[str, Any]:
        account_name = query.get("accountName", [""])[0] or None
        return self.server.app.list_accounts(selected_account_name=account_name)

    def _get_settings(self, query: dict[str, list[str]]) -> dict[str, Any]:
        return self.server.app.get_settings()

    def _get_tasks(self, query: dict[str, list[str]]) -> dict[str, Any]:
        return self.server.app.list_tasks()

    def _get_automation_plans(self, query: dict[str, list[str]]) -> dict[str, Any]:
        return self.server.app.list_automation_plans()

    def _get_task_status(self, query: dict[str, list[str]]) -> dict[str, Any]:
        account_name = query.get("accountName", [""])[0] or None
        return self.server.app.inspect_task_statuses(account_name=account_name)

    def _get_sync_state(self, query: dict[str, list[str]]) -> dict[str, Any]:
        del query
        return self.server.app.get_sync_state()

    def _get_server_sync_settings(
        self, query: dict[str, list[str]]
    ) -> dict[str, Any]:
        del query
        return self.server.app.get_server_sync_settings()

    def log_message(self, format: str, *args) -> None:  # noqa: A003
        return

    def _read_json_body(self) -> dict[str, Any]:
        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length) if content_length > 0 else b"{}"
        payload = json.loads(raw_body.decode("utf-8"))
        if not isinstance(payload, dict):
            raise ApiRequestError("请求体必须是 JSON 对象")
        return payload

    def _serve_static_file(self, file_path: Path, content_type: str) -> None:
        if not file_path.exists():
            self._send_json(
                {"message": f"静态资源不存在：{file_path.name}"}, HTTPStatus.NOT_FOUND
            )
            return
        body = file_path.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self._write_response_body(body)

    def _send_not_found(self) -> None:
        self._send_json({"message": "未找到请求资源"}, HTTPStatus.NOT_FOUND)

    def _send_json(
        self, payload: dict[str, Any], status_code: HTTPStatus = HTTPStatus.OK
    ) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self._write_response_body(body)

    def _write_response_body(self, body: bytes) -> None:
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            # 浏览器刷新、页面跳转或前端主动取消请求时，客户端可能会先断开连接。
            # 这类情况不影响服务端状态，直接忽略即可，避免控制台出现误导性的异常堆栈。
            return
