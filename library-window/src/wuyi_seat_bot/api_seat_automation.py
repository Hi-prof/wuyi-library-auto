from __future__ import annotations

from collections.abc import Callable
import http.cookiejar
import json
import urllib.error
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlparse, urlunparse
from urllib.request import HTTPCookieProcessor, Request, build_opener

from wuyi_seat_bot.config import resolve_project_path
from wuyi_seat_bot.entry_url_cache import (
    load_resolved_entry_urls,
    save_resolved_entry_url,
    save_resolved_entry_urls,
)
from wuyi_seat_bot.models import ActionType, AppConfig, SeatActionOutcome, SeatActionStatus
from wuyi_seat_bot.seat_api import (
    append_lab_json,
    build_request_error_message,
    build_checkin_candidates,
    build_begin_time,
    build_checkout_candidates,
    build_custom_search_form_payload,
    build_default_filters,
    build_reservation_request_context,
    build_search_form_payload,
    build_single_seat_book_form,
    build_single_seat_book_token,
    current_api_time,
    describe_seat_booking_status,
    extract_search_api_url,
    fetch_json,
    fetch_my_booking_list,
    is_checkin_window_open,
    load_saved_session,
    perform_seat_checkin,
    perform_seat_checkout,
    serialize_seat_map,
)

LOGIN_APPLICATION_ID = "lab4"
LOGIN_METADATA_PATH = "/User/Index/login?LAB_JSON=1"
LOGIN_REQUEST_PATH = "/api/1/login"
LANGUAGE_COOKIE = "web_language=zh-CN"
BLUETOOTH_SCAN_TIMEOUT_SECONDS = 8.0


class ApiSeatAutomation:
    def __init__(self, config: AppConfig, config_path: str | Path) -> None:
        self.config = config
        self.config_path = Path(config_path)
        self.state_path = resolve_project_path(config_path, config.state_file)

    def perform_action(self, action: ActionType, seat_url: str) -> SeatActionOutcome:
        if not self.state_path.exists():
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=f"未找到登录态文件：{self.state_path}，请先执行 save-login 刷新认证",
            )

        if action == ActionType.RESERVE:
            return self._perform_reserve_via_api(seat_url)
        if action == ActionType.CHECKIN:
            return self._perform_checkin_via_bluetooth()
        if action == ActionType.CHECKOUT:
            return self._perform_checkout_via_api()
        return SeatActionOutcome(
            status=SeatActionStatus.FAILED,
            message=f"暂不支持的动作：{action.value}",
        )

    def inspect_seat_status(self, seat_url: str) -> str:
        if not self.state_path.exists():
            return f"{seat_url} -> failed: 未找到登录态文件：{self.state_path}，请先执行 save-login"

        try:
            saved_session, search_api_url, search_page_payload = self._load_search_page_payload(seat_url)
            filters = build_default_filters(search_page_payload)
            search_result_payload = self._search_seats(
                search_api_url=search_api_url,
                cookie_header=saved_session.cookie_header,
                form_data=build_custom_search_form_payload(
                    search_page_payload,
                    begin_time=build_begin_time(filters.date_value, filters.start_hour),
                    duration_seconds=filters.duration_hours * 3600,
                    people_count=filters.people_count,
                ),
            )
            seat_map = serialize_seat_map(search_result_payload)
        except ValueError as exc:
            message = str(exc)
            status = "login-required" if "登录态已失效" in message else "unavailable"
            return f"{seat_url} -> {status}: {message}"
        except urllib.error.HTTPError as exc:
            return f"{seat_url} -> failed: 接口返回异常 HTTP {exc.code}"
        except Exception as exc:  # noqa: BLE001
            return f"{seat_url} -> failed: {exc}"

        room_name = seat_map["roomName"] or "未知房间"
        available_count = int(seat_map["availableCount"])
        if available_count > 0:
            return f"{seat_url} -> available: {room_name} 当前可选 {available_count} 个座位"
        return f"{seat_url} -> unavailable: {room_name} 当前没有可用座位"

    def save_login_state(self, wait_for_enter: bool = True, timeout_ms: int = 300000) -> Path:
        del wait_for_enter
        del timeout_ms

        student_id = (self.config.student_id or "").strip()
        password = (self.config.password or "").strip()
        if not student_id or not password:
            raise ValueError("当前已移除浏览器登录，save-login 需要在配置中提供 student_id 和 password")

        state_payload = _login_with_credentials(
            login_url=self.config.login_url,
            student_id=student_id,
            password=password,
        )
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        self.state_path.write_text(
            json.dumps(state_payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        self._warm_up_resolved_entry_urls()
        return self.state_path

    def resolve_search_api_url(self, seat_url: str, *, cookie_header: str | None = None) -> str:
        known_url = self._get_known_search_api_url(seat_url)
        if known_url is not None:
            return known_url

        search_api_url = _resolve_search_api_url_from_entry(
            entry_url=seat_url,
            cookie_header=cookie_header or self._load_saved_session().cookie_header,
        )
        save_resolved_entry_url(
            self.config_path,
            self.config.account_name,
            seat_url,
            search_api_url,
        )
        return search_api_url

    def resolve_seat_urls(self) -> dict[str, str]:
        return self._warm_up_resolved_entry_urls()

    def _load_saved_session(self):
        return load_saved_session(self.state_path)

    def _get_known_search_api_url(
        self,
        seat_url: str,
        *,
        resolved_urls: dict[str, str] | None = None,
    ) -> str | None:
        direct_url = extract_search_api_url(seat_url)
        if direct_url is not None:
            return direct_url
        if resolved_urls is None:
            resolved_urls = load_resolved_entry_urls(self.config_path, self.config.account_name)
        return resolved_urls.get(seat_url)

    def _load_search_page_payload(self, seat_url: str) -> tuple[Any, str, dict[str, Any]]:
        saved_session = self._load_saved_session()
        search_api_url = self.resolve_search_api_url(
            seat_url,
            cookie_header=saved_session.cookie_header,
        )
        search_page_payload = _fetch_authenticated_json(
            search_api_url,
            cookie_header=saved_session.cookie_header,
        )
        return saved_session, search_api_url, search_page_payload

    def _search_seats(
        self,
        *,
        search_api_url: str,
        cookie_header: str,
        form_data: dict[str, Any],
    ) -> dict[str, Any]:
        return _fetch_authenticated_json(
            search_api_url,
            cookie_header=cookie_header,
            method="POST",
            form_data=form_data,
        )

    def _load_booking_candidates(
        self,
        candidate_builder: Callable[[dict[str, Any]], list[Any]],
    ) -> tuple[Any, list[Any]]:
        saved_session = self._load_saved_session()
        booking_list_payload = fetch_my_booking_list(saved_session.cookie_header)
        _ensure_not_login_payload(booking_list_payload)
        return saved_session, candidate_builder(booking_list_payload)

    def _warm_up_resolved_entry_urls(self) -> dict[str, str]:
        if not self.state_path.exists():
            return {}

        saved_session = self._load_saved_session()
        resolved_urls = load_resolved_entry_urls(self.config_path, self.config.account_name)
        updated_urls = dict(resolved_urls)
        changed = False

        for seat_url in self.config.seat_urls:
            known_url = self._get_known_search_api_url(seat_url, resolved_urls=updated_urls)
            if known_url is not None:
                if updated_urls.get(seat_url) != known_url:
                    updated_urls[seat_url] = known_url
                    changed = True
                continue

            try:
                updated_urls[seat_url] = _resolve_search_api_url_from_entry(
                    entry_url=seat_url,
                    cookie_header=saved_session.cookie_header,
                )
            except Exception:  # noqa: BLE001
                continue

            changed = True

        if changed:
            save_resolved_entry_urls(self.config_path, self.config.account_name, updated_urls)
        return updated_urls

    def _perform_checkin_via_bluetooth(self) -> SeatActionOutcome:
        from wuyi_seat_bot.bluetooth import scan_for_matching_ibeacon_minor

        try:
            saved_session, candidates = self._load_booking_candidates(build_checkin_candidates)
        except Exception as exc:  # noqa: BLE001
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=f"读取待签到预约失败：{exc}",
            )

        if not candidates:
            return SeatActionOutcome(
                status=SeatActionStatus.UNAVAILABLE,
                message="当前没有座位预约记录，无法执行蓝牙签到",
            )

        pending_candidates = [candidate for candidate in candidates if candidate.status == "0"]
        if not pending_candidates:
            latest_candidate = candidates[0]
            return _build_latest_booking_status_outcome(
                "当前没有待签到预约，最近一条记录状态为",
                latest_candidate.status,
                latest_candidate.room_name,
                latest_candidate.seat_number,
            )

        ready_candidates = [candidate for candidate in pending_candidates if is_checkin_window_open(candidate)]
        if not ready_candidates:
            candidate = pending_candidates[0]
            return SeatActionOutcome(
                status=SeatActionStatus.UNAVAILABLE,
                message=(
                    "已找到待签到预约，但当前还不在签到时间窗内："
                    f"{candidate.room_name} {candidate.seat_number} 号座位"
                ),
            )

        candidate = ready_candidates[0]
        if not candidate.ibeacon_minors:
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=(
                    "待签到预约未返回蓝牙设备信息，无法按学校当前的蓝牙签到规则自动执行："
                    f"{candidate.room_name} {candidate.seat_number} 号座位"
                ),
            )

        try:
            scan_result = scan_for_matching_ibeacon_minor(
                candidate.ibeacon_minors,
                timeout_seconds=BLUETOOTH_SCAN_TIMEOUT_SECONDS,
            )
        except Exception as exc:  # noqa: BLE001
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=f"蓝牙扫描失败：{exc}",
            )

        if scan_result.matched_minor is None:
            expected_text = "、".join(str(minor) for minor in candidate.ibeacon_minors)
            seen_text = "、".join(str(minor) for minor in scan_result.seen_minors) if scan_result.seen_minors else "未扫描到任何 iBeacon 广播"
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=(
                    "蓝牙扫描未命中预约房间设备，"
                    f"期望 minor：{expected_text}；实际扫描结果：{seen_text}"
                ),
            )

        try:
            checkin_result = perform_seat_checkin(saved_session.cookie_header, candidate.booking_id)
        except Exception as exc:  # noqa: BLE001
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=f"签到接口执行异常：{exc}",
            )

        if checkin_result.success:
            return SeatActionOutcome(
                status=SeatActionStatus.SUCCESS,
                message=(
                    f"签到成功：{candidate.room_name} {candidate.seat_number} 号座位，"
                    f"命中蓝牙 minor {scan_result.matched_minor}"
                ),
            )

        return _build_request_failure_outcome(checkin_result.message)

    def _perform_checkout_via_api(self) -> SeatActionOutcome:
        try:
            saved_session, candidates = self._load_booking_candidates(build_checkout_candidates)
        except Exception as exc:  # noqa: BLE001
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=f"读取可签退预约失败：{exc}",
            )

        if not candidates:
            return SeatActionOutcome(
                status=SeatActionStatus.UNAVAILABLE,
                message="当前没有座位预约记录，无法执行签退",
            )

        active_candidates = [candidate for candidate in candidates if candidate.status in {"1", "2"}]
        if not active_candidates:
            latest_candidate = candidates[0]
            return _build_latest_booking_status_outcome(
                "当前没有可签退的在馆预约，最近一条记录状态为",
                latest_candidate.status,
                latest_candidate.room_name,
                latest_candidate.seat_number,
            )

        candidate = active_candidates[0]
        try:
            checkout_result = perform_seat_checkout(saved_session.cookie_header, candidate.booking_id)
        except Exception as exc:  # noqa: BLE001
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=f"签退接口执行异常：{exc}",
            )

        if checkout_result.success:
            return SeatActionOutcome(
                status=SeatActionStatus.SUCCESS,
                message=f"签退成功：{candidate.room_name} {candidate.seat_number} 号座位",
            )

        return _build_request_failure_outcome(checkout_result.message)

    def _perform_reserve_via_api(self, seat_url: str) -> SeatActionOutcome:
        try:
            saved_session, search_api_url, search_page_payload = self._load_search_page_payload(seat_url)
            search_result_payload = self._search_seats(
                search_api_url=search_api_url,
                cookie_header=saved_session.cookie_header,
                form_data=build_search_form_payload(search_page_payload),
            )

            selection, begin_time, duration_seconds = build_reservation_request_context(
                search_page_payload,
                search_result_payload,
                self.config.preferred_seat_numbers,
                self.config.preferred_room_names,
            )
            api_time = current_api_time()
            book_form = build_single_seat_book_form(
                begin_time=begin_time,
                duration_seconds=duration_seconds,
                seat_id=selection.seat_id,
                user_id=saved_session.user_id,
                api_time=api_time,
                is_recommend=1,
            )
            api_token = build_single_seat_book_token(
                begin_time=begin_time,
                duration_seconds=duration_seconds,
                seat_id=selection.seat_id,
                user_id=saved_session.user_id,
                api_time=api_time,
                is_recommend=1,
            )
            book_result = fetch_json(
                _build_book_api_url(search_api_url),
                cookie_header=saved_session.cookie_header,
                method="POST",
                form_data=book_form,
                extra_headers={"Api-Token": api_token},
            )
        except ValueError as exc:
            message = str(exc)
            status = SeatActionStatus.UNAVAILABLE if "不可用" in message or "没有可用座位" in message else SeatActionStatus.FAILED
            return SeatActionOutcome(status=status, message=message)
        except urllib.error.HTTPError as exc:
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=f"预约接口返回异常：HTTP {exc.code}",
            )
        except Exception as exc:  # noqa: BLE001
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=f"预约接口执行异常：{exc}",
            )

        if book_result.get("CODE") != "ok":
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=book_result.get("MESSAGE", "预约接口返回失败"),
            )

        result_data = book_result.get("DATA", {})
        if result_data.get("result") != "success":
            return SeatActionOutcome(
                status=SeatActionStatus.FAILED,
                message=result_data.get("msg", "预约失败，接口未返回成功结果"),
            )

        return SeatActionOutcome(
            status=SeatActionStatus.SUCCESS,
            message=f"预约成功，已通过接口选择 {selection.room_name} {selection.seat_number} 号座位",
        )


def _login_with_credentials(*, login_url: str, student_id: str, password: str) -> dict[str, Any]:
    origin = _resolve_origin(login_url)
    cookie_jar = http.cookiejar.CookieJar()
    opener = build_opener(HTTPCookieProcessor(cookie_jar))
    installation_id = str(uuid.uuid4())

    login_meta = _open_json(
        opener,
        Request(
            url=urljoin(origin, LOGIN_METADATA_PATH),
            headers=_build_request_headers(origin),
            method="GET",
        ),
    )
    raw_data = login_meta.get("content", {}).get("data", {})
    org_id = str(
        login_meta.get("content", {})
        .get("itemHeader", {})
        .get("defaultData", {})
        .get("custom_value", "")
    ).strip()
    if not isinstance(raw_data, dict) or not raw_data.get("code") or not raw_data.get("str") or not org_id:
        raise ValueError("登录页未返回完整认证参数，请稍后再试")

    login_payload = {
        "login_name": student_id,
        "password": password,
        "ui_type": "com.Raw",
        "code": raw_data["code"],
        "str": raw_data["str"],
        "org_id": org_id,
        "_ApplicationId": LOGIN_APPLICATION_ID,
        "_JavaScriptKey": LOGIN_APPLICATION_ID,
        "_ClientVersion": "js_xxx",
        "_InstallationId": installation_id,
    }
    current_user = _open_json(
        opener,
        Request(
            url=urljoin(origin, LOGIN_REQUEST_PATH),
            data=json.dumps(login_payload, ensure_ascii=False).encode("utf-8"),
            headers=_build_request_headers(
                origin,
                extra_headers={
                    "Content-Type": "text/plain",
                    "Origin": origin,
                },
            ),
            method="POST",
        ),
    )
    if not isinstance(current_user, dict) or not str(current_user.get("id", "")).strip():
        raise ValueError(_extract_error_message(current_user, "登录失败，请检查学号或密码"))

    return _build_state_payload(
        origin=origin,
        cookie_jar=cookie_jar,
        installation_id=installation_id,
        current_user=_normalize_current_user_payload(current_user),
    )


def _resolve_search_api_url_from_entry(*, entry_url: str, cookie_header: str) -> str:
    entry_api_url = _extract_entry_api_url(entry_url)
    entry_payload = _fetch_authenticated_json(
        entry_api_url,
        cookie_header=cookie_header,
    )

    search_api_url = _extract_search_api_url_from_payload(entry_payload, request_url=entry_api_url)
    if search_api_url is None:
        raise ValueError("未能从入口页解析 searchSeats 接口地址，请检查 seat_urls 配置")
    return search_api_url


def _extract_entry_api_url(entry_url: str) -> str:
    parsed = urlparse(entry_url)
    fragment = parsed.fragment.lstrip("!")
    if fragment.startswith("/"):
        fragment_path, _, fragment_query = fragment.partition("?")
        return urlunparse((parsed.scheme, parsed.netloc, fragment_path, "", fragment_query, ""))
    return urlunparse((parsed.scheme, parsed.netloc, parsed.path, "", parsed.query, ""))


def _extract_search_api_url_from_payload(payload: Any, *, request_url: str) -> str | None:
    if isinstance(payload, dict):
        link_payload = payload.get("link")
        if isinstance(link_payload, dict):
            raw_url = str(link_payload.get("url", "")).strip()
            if raw_url:
                resolved = extract_search_api_url(urljoin(request_url, raw_url))
                if resolved is not None:
                    return resolved
        for value in payload.values():
            resolved = _extract_search_api_url_from_payload(value, request_url=request_url)
            if resolved is not None:
                return resolved
    elif isinstance(payload, list):
        for item in payload:
            resolved = _extract_search_api_url_from_payload(item, request_url=request_url)
            if resolved is not None:
                return resolved
    return None


def _build_state_payload(
    *,
    origin: str,
    cookie_jar: http.cookiejar.CookieJar,
    installation_id: str,
    current_user: dict[str, Any],
) -> dict[str, Any]:
    cookies = [
        {
            "name": cookie.name,
            "value": cookie.value,
            "domain": cookie.domain,
            "path": cookie.path,
        }
        for cookie in cookie_jar
        if cookie.name and cookie.value
    ]
    if not cookies:
        raise ValueError("登录成功后未拿到任何 Cookie，请稍后再试")

    storage_prefix = f"lrnw_AS_Parse/{LOGIN_APPLICATION_ID}"
    return {
        "cookies": cookies,
        "origins": [
            {
                "origin": origin,
                "localStorage": [
                    {
                        "name": f"{storage_prefix}/installationId",
                        "value": installation_id,
                    },
                    {
                        "name": f"{storage_prefix}/currentUser",
                        "value": json.dumps(current_user, ensure_ascii=False),
                    },
                ],
            }
        ],
    }


def _normalize_current_user_payload(current_user: dict[str, Any]) -> dict[str, Any]:
    payload = dict(current_user)
    if "accessToken" in payload and "access_token" not in payload:
        payload["access_token"] = payload["accessToken"]
    if "objectId" not in payload and payload.get("id") is not None:
        payload["objectId"] = str(payload["id"])
    payload.setdefault("sessionToken", "fake")
    payload.setdefault("className", "_User")
    return payload


def _fetch_authenticated_json(
    url: str,
    *,
    cookie_header: str,
    method: str = "GET",
    form_data: dict[str, Any] | None = None,
    extra_headers: dict[str, str] | None = None,
) -> dict[str, Any]:
    payload = fetch_json(
        append_lab_json(url),
        cookie_header=cookie_header,
        method=method,
        form_data=form_data,
        extra_headers=extra_headers,
    )
    _ensure_not_login_payload(payload)
    return payload


def _open_json(opener, request: Request) -> dict[str, Any]:
    try:
        with opener.open(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace").strip()
        payload: Any = ""
        if body:
            try:
                payload = json.loads(body)
            except json.JSONDecodeError:
                payload = body
        raise ValueError(
            build_request_error_message(
                request.full_url,
                status_code=exc.code,
                payload=payload,
                default_message=f"请求失败：HTTP {exc.code}",
            )
        ) from exc


def _build_request_headers(origin: str, *, extra_headers: dict[str, str] | None = None) -> dict[str, str]:
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Cookie": LANGUAGE_COOKIE,
        "Referer": f"{origin}/",
    }
    if extra_headers:
        headers.update(extra_headers)
    return headers


def _resolve_origin(url: str) -> str:
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.netloc:
        raise ValueError(f"无效的登录地址：{url}")
    return urlunparse((parsed.scheme, parsed.netloc, "", "", "", ""))


def _ensure_not_login_payload(payload: dict[str, Any]) -> None:
    if payload.get("ui_type") == "ht.LoginPage":
        raise ValueError("当前登录态已失效，请重新执行 save-login")


def _extract_error_message(payload: Any, default_message: str) -> str:
    if isinstance(payload, dict):
        for key in ("message", "msg", "error", "code"):
            value = str(payload.get(key, "")).strip()
            if value:
                return value
    elif isinstance(payload, str) and payload.strip():
        return payload.strip()
    return default_message


def _build_book_api_url(search_api_url: str) -> str:
    parsed = urlparse(search_api_url)
    return urlunparse(
        (
            parsed.scheme,
            parsed.netloc,
            parsed.path.replace("/searchSeats", "/bookSeats"),
            "",
            "LAB_JSON=1",
            "",
        )
    )


def _is_booking_state_conflict(message: str) -> bool:
    normalized_message = message.strip()
    return "当前状态无法操作" in normalized_message or "状态非" in normalized_message


def _build_request_failure_outcome(message: str) -> SeatActionOutcome:
    status = SeatActionStatus.UNAVAILABLE if _is_booking_state_conflict(message) else SeatActionStatus.FAILED
    return SeatActionOutcome(status=status, message=message)


def _build_latest_booking_status_outcome(
    prefix: str,
    status: str,
    room_name: str,
    seat_number: str,
) -> SeatActionOutcome:
    return SeatActionOutcome(
        status=SeatActionStatus.UNAVAILABLE,
        message=(
            f"{prefix}{describe_seat_booking_status(status)}："
            f"{room_name} {seat_number} 号座位"
        ),
    )
