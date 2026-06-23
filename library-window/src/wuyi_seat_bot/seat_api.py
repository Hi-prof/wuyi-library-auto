from __future__ import annotations

import base64
import hashlib
import json
import time
import urllib.error
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlparse, urlunparse
from urllib.request import Request, urlopen


DEFAULT_HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    ),
}
SHANGHAI_TZ = timezone(timedelta(hours=8))
WEEKDAY_LABELS = ("周一", "周二", "周三", "周四", "周五", "周六", "周日")
MY_BOOKING_LIST_API_URL = "https://wuyiu.huitu.zhishulib.com/Seat/Index/myBookingList"
CHECKIN_API_URL = "https://wuyiu.huitu.zhishulib.com/Seat/Index/checkIn"
CHECKOUT_API_URL = "https://wuyiu.huitu.zhishulib.com/Seat/Index/checkOut"
CANCEL_BOOKING_API_URL = "https://wuyiu.huitu.zhishulib.com/Seat/Index/cancelBooking"
SEAT_BOOKING_STATUS_LABELS = {
    "0": "待签到",
    "1": "签到成功，使用中",
    "2": "暂离中",
    "3": "已签退结束",
    "4": "已取消",
    "5": "未签到结束",
    "6": "暂离未归结束",
    "7": "系统签退结束",
    "8": "预约待确认",
    "9": "拒绝预约",
}


@dataclass(frozen=True)
class SavedSession:
    cookie_header: str
    user_id: str


@dataclass(frozen=True)
class SeatSelection:
    room_name: str
    seat_id: str
    seat_number: str


@dataclass(frozen=True)
class SearchFilters:
    date_value: str
    start_hour: int
    duration_hours: int
    people_count: int
    seat_url: str = ""
    account_name: str = ""


@dataclass(frozen=True)
class CheckinCandidate:
    booking_id: str
    room_name: str
    seat_number: str
    status: str
    start_time: int
    duration_seconds: int
    now_time: int
    limit_sign_ago_seconds: int
    limit_sign_back_seconds: int
    ibeacon_minors: tuple[int, ...]


@dataclass(frozen=True)
class CheckoutCandidate:
    booking_id: str
    room_name: str
    seat_number: str
    status: str
    start_time: int
    duration_seconds: int


@dataclass(frozen=True)
class SeatRequestResult:
    success: bool
    message: str


def load_saved_session(state_path: str | Path) -> SavedSession:
    payload = json.loads(Path(state_path).read_text(encoding="utf-8"))
    cookie_header = "; ".join(
        f"{cookie['name']}={cookie['value']}"
        for cookie in payload.get("cookies", [])
        if cookie.get("name") and cookie.get("value")
    )
    if not cookie_header:
        raise ValueError("登录态中未找到可用 Cookie，请重新执行 save-login")

    current_user = _load_current_user(payload)
    user_id = str(current_user.get("id", "")).strip()
    if not user_id:
        raise ValueError("登录态中未找到用户 ID，请重新执行 save-login")
    return SavedSession(cookie_header=cookie_header, user_id=user_id)


def extract_search_api_url(entry_url: str) -> str | None:
    parsed = urlparse(entry_url)

    if "/Seat/Index/searchSeats" in parsed.path:
        return _remove_lab_json(
            urlunparse(
                (parsed.scheme, parsed.netloc, parsed.path, "", parsed.query, "")
            )
        )

    fragment = parsed.fragment.lstrip("!")
    if not fragment.startswith("/Seat/Index/searchSeats"):
        return None

    fragment_path, _, fragment_query = fragment.partition("?")
    return _remove_lab_json(
        urlunparse(
            (parsed.scheme, parsed.netloc, fragment_path, "", fragment_query, "")
        )
    )


def append_lab_json(url: str) -> str:
    parsed = urlparse(url)
    query = parse_qs(parsed.query, keep_blank_values=True)
    query["LAB_JSON"] = ["1"]
    return urlunparse(
        (
            parsed.scheme,
            parsed.netloc,
            parsed.path,
            "",
            urlencode(query, doseq=True),
            "",
        )
    )


def build_book_api_url(search_api_url: str) -> str:
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


def build_search_form_payload(search_page_payload: dict) -> list[tuple[str, str]]:
    data = search_page_payload["data"]
    default = data["default"]
    space_category = data["space_category"]
    return [
        ("beginTime", str(default["date"])),
        ("duration", str(int(default["duration"]) * 3600)),
        ("num", str(default["num"])),
        ("space_category[category_id]", str(space_category["category_id"])),
        ("space_category[content_id]", str(space_category["content_id"])),
    ]


def build_custom_search_form_payload(
    search_page_payload: dict,
    *,
    begin_time: int,
    duration_seconds: int,
    people_count: int,
) -> list[tuple[str, str]]:
    space_category = search_page_payload["data"]["space_category"]
    return [
        ("beginTime", str(begin_time)),
        ("duration", str(duration_seconds)),
        ("num", str(people_count)),
        ("space_category[category_id]", str(space_category["category_id"])),
        ("space_category[content_id]", str(space_category["content_id"])),
    ]


def find_recommend_seat_item(search_result_payload: dict) -> dict:
    # 学校接口有时把座位图放在 content，有时只放在 allContent 的阅览室列表里。
    candidates = [
        *_collect_recommend_seat_items(search_result_payload.get("content")),
        *_collect_recommend_seat_items(search_result_payload.get("allContent")),
    ]
    if not candidates:
        raise ValueError(_build_missing_seat_data_message(search_result_payload))

    selected_item = next(
        (item for item in candidates if _seat_item_has_selected_poi(item)), None
    )
    if selected_item is not None:
        return selected_item

    recommended_item = next(
        (item for item in candidates if bool(item.get("ifRecommend"))), None
    )
    if recommended_item is not None:
        return recommended_item
    return candidates[0]


def build_room_seat_maps(search_result_payload: dict) -> tuple[dict, ...]:
    room_items = _collect_unique_room_seat_items(search_result_payload)
    if not room_items:
        raise ValueError(_build_missing_seat_data_message(search_result_payload))

    recommended_lookup = _build_recommended_room_lookup(search_result_payload)
    return tuple(
        _serialize_room_seat_item(
            seat_item,
            recommended_item=recommended_lookup.get(_room_item_key(seat_item)),
        )
        for seat_item in room_items
    )


def _collect_recommend_seat_items(node: object) -> list[dict]:
    items: list[dict] = []
    if isinstance(node, dict):
        if node.get("ui_type") == "ht.Seat.RecommendSeatItem" and isinstance(
            node.get("seatMap"), dict
        ):
            items.append(node)
        for value in node.values():
            items.extend(_collect_recommend_seat_items(value))
        return items
    if isinstance(node, list):
        for item in node:
            items.extend(_collect_recommend_seat_items(item))
    return items


def _seat_item_has_selected_poi(seat_item: dict) -> bool:
    seat_map = seat_item.get("seatMap")
    if not isinstance(seat_map, dict):
        return False
    pois = seat_map.get("POIs")
    if not isinstance(pois, list):
        return False
    return any(isinstance(poi, dict) and str(poi.get("state")) == "2" for poi in pois)


def _collect_unique_room_seat_items(search_result_payload: dict) -> list[dict]:
    candidates = [
        *_collect_recommend_seat_items(search_result_payload.get("allContent")),
        *_collect_recommend_seat_items(search_result_payload.get("content")),
    ]
    unique_items: list[dict] = []
    index_by_key: dict[str, int] = {}
    for seat_item in candidates:
        key = _room_item_key(seat_item)
        existing_index = index_by_key.get(key)
        if existing_index is None:
            index_by_key[key] = len(unique_items)
            unique_items.append(seat_item)
            continue
        if _should_replace_room_seat_item(unique_items[existing_index], seat_item):
            unique_items[existing_index] = seat_item
    return unique_items


def _build_recommended_room_lookup(search_result_payload: dict) -> dict[str, dict]:
    lookup: dict[str, dict] = {}
    candidates = [
        *_collect_recommend_seat_items(search_result_payload.get("content")),
        *_collect_recommend_seat_items(search_result_payload.get("allContent")),
    ]
    for seat_item in candidates:
        if not (
            _seat_item_has_selected_poi(seat_item) or bool(seat_item.get("ifRecommend"))
        ):
            continue
        key = _room_item_key(seat_item)
        current_item = lookup.get(key)
        if current_item is None or _seat_item_has_selected_poi(seat_item):
            lookup[key] = seat_item
    return lookup


def _room_item_key(seat_item: dict) -> str:
    room_id = _read_room_id(seat_item)
    if room_id:
        return room_id
    room_name = str(seat_item.get("roomName", "")).strip()
    return room_name or str(id(seat_item))


def _read_room_id(seat_item: dict) -> str:
    seat_map = seat_item.get("seatMap")
    if not isinstance(seat_map, dict):
        return ""
    info = seat_map.get("info")
    if not isinstance(info, dict):
        return ""
    return str(info.get("id", "")).strip()


def _should_replace_room_seat_item(current_item: dict, candidate_item: dict) -> bool:
    current_recommended = bool(current_item.get("ifRecommend"))
    candidate_recommended = bool(candidate_item.get("ifRecommend"))
    if current_recommended != candidate_recommended:
        return current_recommended and not candidate_recommended
    return _count_room_pois(candidate_item) > _count_room_pois(current_item)


def _count_room_pois(seat_item: dict) -> int:
    seat_map = seat_item.get("seatMap")
    if not isinstance(seat_map, dict):
        return 0
    pois = seat_map.get("POIs")
    if not isinstance(pois, list):
        return 0
    return len(pois)


def _build_missing_seat_data_message(search_result_payload: dict) -> str:
    payload_message = _extract_payload_message(search_result_payload)
    if payload_message:
        return f"未找到座位数据：{payload_message}"
    return "未找到座位数据，当前查询条件可能暂无可选座位，也可能需要重新保存登录态"


def _extract_payload_message(node: object) -> str | None:
    if isinstance(node, dict):
        candidate_keys = ("message", "msg", "error")
        if node.get("ui_type") == "com.Message":
            candidate_keys = ("title", "text", "content", *candidate_keys)
        for key in candidate_keys:
            value = node.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        for value in node.values():
            message = _extract_payload_message(value)
            if message:
                return message
        return None
    if isinstance(node, list):
        for item in node:
            message = _extract_payload_message(item)
            if message:
                return message
    return None


def choose_seat(
    pois: list[dict], preferred_seat_numbers: tuple[str, ...]
) -> SeatSelection:
    available = [poi for poi in pois if str(poi.get("state")) == "0"]
    if not available:
        raise ValueError("当前没有可用座位")

    preferred_order = {
        seat_number.strip(): index
        for index, seat_number in enumerate(preferred_seat_numbers)
        if seat_number.strip()
    }

    preferred_candidates = [
        poi for poi in available if str(poi.get("title", "")).strip() in preferred_order
    ]
    if preferred_order and not preferred_candidates:
        expected = "、".join(preferred_seat_numbers)
        raise ValueError(f"优先座位 {expected} 当前都不可用")

    candidates = preferred_candidates or available
    selected = min(
        candidates,
        key=lambda poi: (
            preferred_order.get(
                str(poi.get("title", "")).strip(), len(preferred_order)
            ),
            _seat_number_sort_key(str(poi.get("title", ""))),
        ),
    )
    return SeatSelection(
        room_name="",
        seat_id=str(selected["id"]),
        seat_number=str(selected["title"]),
    )


def _choose_room_seat_item(
    search_result_payload: dict,
    preferred_room_names: tuple[str, ...],
) -> dict:
    room_items = _collect_unique_room_seat_items(search_result_payload)
    if not room_items:
        raise ValueError(_build_missing_seat_data_message(search_result_payload))

    preferred_order = {
        room_name.strip(): index
        for index, room_name in enumerate(preferred_room_names)
        if room_name.strip()
    }
    preferred_items = [
        seat_item
        for seat_item in room_items
        if str(seat_item.get("roomName", "")).strip() in preferred_order
    ]
    if preferred_order and not preferred_items:
        expected = "、".join(preferred_room_names)
        raise ValueError(f"优先自习室 {expected} 当前都不可用")
    if not preferred_items:
        return room_items[0]
    return min(
        preferred_items,
        key=lambda seat_item: preferred_order[
            str(seat_item.get("roomName", "")).strip()
        ],
    )


def build_single_seat_book_token(
    begin_time: int,
    duration_seconds: int,
    seat_id: str,
    user_id: str,
    api_time: int,
    is_recommend: int = 1,
) -> str:
    return build_book_token(
        begin_time=begin_time,
        duration_seconds=duration_seconds,
        seat_ids=(seat_id,),
        seat_booker_ids=(user_id,),
        api_time=api_time,
        is_recommend=is_recommend,
    )


def build_single_seat_book_form(
    begin_time: int,
    duration_seconds: int,
    seat_id: str,
    user_id: str,
    api_time: int,
    is_recommend: int = 1,
) -> list[tuple[str, str]]:
    return build_book_form(
        begin_time=begin_time,
        duration_seconds=duration_seconds,
        seat_ids=(seat_id,),
        seat_booker_ids=(user_id,),
        api_time=api_time,
        is_recommend=is_recommend,
    )


def build_book_token(
    begin_time: int,
    duration_seconds: int,
    seat_ids: tuple[str, ...],
    seat_booker_ids: tuple[str, ...],
    api_time: int,
    is_recommend: int = 0,
) -> str:
    _validate_booking_arrays(seat_ids, seat_booker_ids)

    sign_source = (
        "post&/Seat/Index/bookSeats?LAB_JSON=1"
        f"&api_time{api_time}"
        f"&beginTime{begin_time}"
        f"&duration{duration_seconds}"
        f"&is_recommend{is_recommend}"
    )
    for index, seat_booker_id in enumerate(seat_booker_ids):
        sign_source += f"&seatBookers[{index}]{int(seat_booker_id)}"
    for index, seat_id in enumerate(seat_ids):
        sign_source += f"&seats[{index}]{seat_id}"

    digest = hashlib.md5(sign_source.encode("utf-8")).hexdigest()
    return base64.b64encode(digest.encode("utf-8")).decode("ascii")


def build_book_form(
    begin_time: int,
    duration_seconds: int,
    seat_ids: tuple[str, ...],
    seat_booker_ids: tuple[str, ...],
    api_time: int,
    is_recommend: int = 0,
) -> list[tuple[str, str]]:
    _validate_booking_arrays(seat_ids, seat_booker_ids)

    return [
        ("beginTime", str(begin_time)),
        ("duration", str(duration_seconds)),
        *[(f"seats[{index}]", seat_id) for index, seat_id in enumerate(seat_ids)],
        ("is_recommend", str(is_recommend)),
        ("api_time", str(api_time)),
        *[
            (f"seatBookers[{index}]", seat_booker_id)
            for index, seat_booker_id in enumerate(seat_booker_ids)
        ],
    ]


def fetch_json(
    url: str,
    *,
    cookie_header: str,
    method: str = "GET",
    form_data: list[tuple[str, str]] | None = None,
    extra_headers: dict[str, str] | None = None,
) -> dict:
    parsed = urlparse(url)
    headers = {
        **DEFAULT_HEADERS,
        "Cookie": cookie_header,
        "Origin": f"{parsed.scheme}://{parsed.netloc}",
        "Referer": f"{parsed.scheme}://{parsed.netloc}/",
    }
    if extra_headers:
        headers.update(extra_headers)

    body = None
    if form_data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8"
        body = urlencode(form_data).encode("utf-8")

    request = Request(url=url, data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise ValueError(
            build_request_error_message(
                request.full_url,
                status_code=exc.code,
                payload=_read_http_error_payload(exc),
                default_message=f"请求失败：HTTP {exc.code}",
            )
        ) from exc


def fetch_my_booking_list(cookie_header: str) -> dict:
    return fetch_json(
        append_lab_json(MY_BOOKING_LIST_API_URL),
        cookie_header=cookie_header,
    )


def build_request_error_message(
    url: str,
    *,
    status_code: int,
    payload: object,
    default_message: str,
) -> str:
    if _is_obsolete_huitu_endpoint(url, status_code=status_code, payload=payload):
        path = urlparse(url).path or "/"
        return (
            f"当前座位系统旧接口已失效（{path} 返回 HTTP {status_code}），"
            "请更新 login_url / seat_url 到学校当前仍在使用的入口"
        )

    if isinstance(payload, dict):
        for key in ("message", "msg", "error", "MESSAGE"):
            value = str(payload.get(key, "")).strip()
            if value:
                return value
    elif isinstance(payload, str):
        text = payload.strip()
        if text:
            return text
    return default_message


def _read_http_error_payload(exc: urllib.error.HTTPError) -> object:
    body = exc.read().decode("utf-8", errors="replace").strip()
    if not body:
        return ""
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return body


def _is_obsolete_huitu_endpoint(url: str, *, status_code: int, payload: object) -> bool:
    if status_code != 404:
        return False

    parsed = urlparse(url)
    if not parsed.netloc.endswith(".huitu.zhishulib.com"):
        return False
    if not parsed.path.startswith(
        ("/User/Index/", "/api/1/", "/Seat/Index/", "/Space/", "/space/")
    ):
        return False

    if isinstance(payload, dict):
        return str(payload.get("CODE", "")).strip() == "NotFound"
    if isinstance(payload, str):
        return "NotFound" in payload
    return False


def build_checkin_candidates(
    booking_list_payload: dict,
) -> tuple[CheckinCandidate, ...]:
    candidates: list[CheckinCandidate] = []
    for item in _iter_booking_items(booking_list_payload):
        booking_id = str(item.get("id", "")).strip()
        status = str(item.get("status", "")).strip()
        if not booking_id or not status:
            continue

        start_time = _read_optional_int(item.get("time"))
        duration_seconds = _read_optional_int(item.get("duration"))
        now_time = _read_optional_int(item.get("nowTime"))
        if start_time is None or duration_seconds is None or now_time is None:
            continue

        candidates.append(
            CheckinCandidate(
                booking_id=booking_id,
                room_name=str(item.get("roomName", "")).strip() or "未知房间",
                seat_number=str(item.get("seatNum", "")).strip() or "未知座位",
                status=status,
                start_time=start_time,
                duration_seconds=duration_seconds,
                now_time=now_time,
                limit_sign_ago_seconds=_read_optional_int(item.get("limitSignAgo"))
                or 0,
                limit_sign_back_seconds=_read_optional_int(item.get("limitSignBack"))
                or 0,
                ibeacon_minors=_collect_ibeacon_minors(item.get("ibeacons")),
            )
        )
    return tuple(candidates)


def build_checkout_candidates(
    booking_list_payload: dict,
) -> tuple[CheckoutCandidate, ...]:
    candidates: list[CheckoutCandidate] = []
    for item in _iter_booking_items(booking_list_payload):
        booking_id = str(item.get("id", "")).strip()
        status = str(item.get("status", "")).strip()
        if not booking_id or not status:
            continue

        candidates.append(
            CheckoutCandidate(
                booking_id=booking_id,
                room_name=str(item.get("roomName", "")).strip() or "未知房间",
                seat_number=str(item.get("seatNum", "")).strip() or "未知座位",
                status=status,
                start_time=_read_optional_int(item.get("time")) or 0,
                duration_seconds=_read_optional_int(item.get("duration")) or 0,
            )
        )
    return tuple(candidates)


def is_checkin_window_open(candidate: CheckinCandidate) -> bool:
    window_start = candidate.start_time - candidate.limit_sign_ago_seconds
    window_end = candidate.start_time + candidate.limit_sign_back_seconds
    return window_start <= candidate.now_time < window_end


def describe_seat_booking_status(status: str) -> str:
    return SEAT_BOOKING_STATUS_LABELS.get(status, f"未知状态({status})")


def perform_seat_checkin(cookie_header: str, booking_id: str) -> SeatRequestResult:
    return _perform_seat_request(
        CHECKIN_API_URL,
        cookie_header,
        booking_id,
        action_label="签到",
    )


def perform_seat_checkout(cookie_header: str, booking_id: str) -> SeatRequestResult:
    return _perform_seat_request(
        CHECKOUT_API_URL,
        cookie_header,
        booking_id,
        action_label="签退",
    )


def perform_seat_cancel_booking(
    cookie_header: str, booking_id: str
) -> SeatRequestResult:
    return _perform_seat_request(
        CANCEL_BOOKING_API_URL,
        cookie_header,
        booking_id,
        action_label="取消预约",
        success_messages=("取消预约成功", "取消成功"),
    )


def _perform_seat_request(
    api_url: str,
    cookie_header: str,
    booking_id: str,
    *,
    action_label: str,
    success_messages: tuple[str, ...] = (),
) -> SeatRequestResult:
    payload = fetch_json(
        append_lab_json(f"{api_url}?bookingId={booking_id}"),
        cookie_header=cookie_header,
        method="POST",
        form_data=[],
    )
    if payload.get("CODE") != "ok":
        return SeatRequestResult(
            success=False,
            message=str(payload.get("MESSAGE", f"{action_label}请求失败")).strip()
            or f"{action_label}请求失败",
        )

    data = payload.get("DATA")
    if not isinstance(data, dict):
        return SeatRequestResult(
            success=False, message=f"{action_label}请求未返回有效结果"
        )

    message = (
        str(data.get("msg", "")).strip()
        or str(payload.get("MESSAGE", "")).strip()
        or f"{action_label}失败"
    )
    return SeatRequestResult(
        success=_is_successful_seat_request(
            data.get("result"),
            message,
            success_messages=success_messages,
        ),
        message=message,
    )


def _is_successful_seat_request(
    result: object,
    message: str,
    *,
    success_messages: tuple[str, ...] = (),
) -> bool:
    normalized_result = str(result).strip().lower()
    if normalized_result in {"success", "ok", "true", "1"}:
        return True
    return any(message.startswith(candidate) for candidate in success_messages)


def _iter_booking_items(booking_list_payload: dict) -> tuple[dict, ...]:
    content = booking_list_payload.get("content")
    if not isinstance(content, dict):
        return ()

    raw_items = content.get("defaultItems")
    if not isinstance(raw_items, list):
        return ()

    return tuple(item for item in raw_items if isinstance(item, dict))


def build_reservation_request_context(
    search_page_payload: dict,
    search_result_payload: dict,
    preferred_seat_numbers: tuple[str, ...],
    preferred_room_names: tuple[str, ...] = (),
) -> tuple[SeatSelection, int, int]:
    search_form = dict(build_search_form_payload(search_page_payload))
    seat_item = (
        _choose_room_seat_item(search_result_payload, preferred_room_names)
        if preferred_room_names
        else find_recommend_seat_item(search_result_payload)
    )
    selection = choose_seat(seat_item["seatMap"]["POIs"], preferred_seat_numbers)
    return (
        SeatSelection(
            room_name=str(seat_item.get("roomName", "")).strip(),
            seat_id=selection.seat_id,
            seat_number=selection.seat_number,
        ),
        int(search_form["beginTime"]),
        int(search_form["duration"]),
    )


def current_api_time() -> int:
    return int(time.time())


def build_default_filters(
    search_page_payload: dict, *, now: datetime | None = None
) -> SearchFilters:
    data = search_page_payload["data"]
    default = data["default"]
    range_data = data["range"]
    begin_time = int(default["date"])
    begin_datetime = datetime.fromtimestamp(begin_time, SHANGHAI_TZ)
    start_hour = _resolve_default_start_hour(
        begin_datetime=begin_datetime,
        default_start_hour=int(default.get("beginTime", begin_datetime.hour)),
        range_data=range_data,
        now=now,
    )
    min_duration = int(range_data["min_duration"])
    max_duration = min(
        int(range_data["max_duration"]),
        int(range_data["maxEndTime"]) - start_hour,
    )
    duration_hours = (
        max_duration if max_duration >= min_duration else int(default["duration"])
    )
    return SearchFilters(
        date_value=begin_datetime.date().isoformat(),
        start_hour=start_hour,
        duration_hours=duration_hours,
        people_count=int(default["num"]),
    )


def _resolve_default_start_hour(
    *,
    begin_datetime: datetime,
    default_start_hour: int,
    range_data: dict,
    now: datetime | None = None,
) -> int:
    current_time = now or datetime.now(SHANGHAI_TZ)
    if begin_datetime.date() != current_time.date():
        return default_start_hour

    min_start_hour = int(range_data["minBeginTime"])
    max_start_hour = int(range_data["maxEndTime"]) - int(range_data["min_duration"])
    if max_start_hour < min_start_hour:
        return default_start_hour

    next_available_hour = current_time.hour + (1 if current_time.minute >= 30 else 0)

    return min(
        max(default_start_hour, min_start_hour, next_available_hour), max_start_hour
    )


def build_date_options(search_page_payload: dict) -> list[dict[str, str]]:
    data = search_page_payload["data"]
    default_begin_time = int(data["default"]["date"])
    max_date = datetime.fromtimestamp(
        int(data["range"]["max_date"]), SHANGHAI_TZ
    ).date()
    current_date = datetime.fromtimestamp(default_begin_time, SHANGHAI_TZ).date()

    options: list[dict[str, str]] = []
    while current_date <= max_date:
        options.append(
            {
                "value": current_date.isoformat(),
                "label": _format_date_label(current_date),
            }
        )
        current_date += timedelta(days=1)
    return options


def build_time_options(search_page_payload: dict) -> list[dict[str, int | str]]:
    range_data = search_page_payload["data"]["range"]
    return [
        {"value": hour, "label": f"{hour}:00"}
        for hour in range(
            int(range_data["minBeginTime"]), int(range_data["maxEndTime"])
        )
    ]


def build_duration_options(
    search_page_payload: dict,
    *,
    start_hour: int | None = None,
) -> list[dict[str, int | str]]:
    range_data = search_page_payload["data"]["range"]
    effective_start_hour = start_hour
    if effective_start_hour is None:
        effective_start_hour = datetime.fromtimestamp(
            int(search_page_payload["data"]["default"]["date"]),
            SHANGHAI_TZ,
        ).hour

    max_duration = min(
        int(range_data["max_duration"]),
        int(range_data["maxEndTime"]) - int(effective_start_hour),
    )
    if max_duration < int(range_data["min_duration"]):
        return []
    return [
        {"value": hours, "label": f"{hours}小时"}
        for hours in range(int(range_data["min_duration"]), max_duration + 1)
    ]


def build_people_options(search_page_payload: dict) -> list[dict[str, int | str]]:
    max_num = int(search_page_payload["data"]["range"]["max_num"])
    return [{"value": count, "label": f"{count}人"} for count in range(1, max_num + 1)]


def build_begin_time(date_value: str, start_hour: int) -> int:
    date_part = datetime.strptime(date_value, "%Y-%m-%d").date()
    return int(
        datetime(
            date_part.year,
            date_part.month,
            date_part.day,
            int(start_hour),
            tzinfo=SHANGHAI_TZ,
        ).timestamp()
    )


def serialize_seat_map(search_result_payload: dict, room_id: str | None = None) -> dict:
    room_maps = build_room_seat_maps(search_result_payload)
    if room_id:
        matched_room = next(
            (item for item in room_maps if item["roomId"] == room_id), None
        )
        if matched_room is None:
            raise ValueError(f"未找到房间 {room_id} 的座位数据")
        return matched_room

    recommended_item = find_recommend_seat_item(search_result_payload)
    recommended_key = _room_item_key(recommended_item)
    matched_room = next(
        (
            item
            for item in room_maps
            if item["roomId"] == recommended_key or item["roomName"] == recommended_key
        ),
        None,
    )
    return matched_room or room_maps[0]


def _serialize_room_seat_item(
    seat_item: dict, *, recommended_item: dict | None = None
) -> dict:
    seat_map = seat_item["seatMap"]
    seats = [serialize_poi(poi) for poi in seat_map["POIs"]]
    selected_seat = next((seat for seat in seats if seat["state"] == "2"), None)
    recommended_poi = _extract_recommended_poi(recommended_item or seat_item)
    recommended_seat_id = (
        None if recommended_poi is None else str(recommended_poi.get("id"))
    )
    recommended_seat_number = (
        None
        if recommended_poi is None
        else str(recommended_poi.get("title", "")).strip()
    )
    return {
        "roomId": _read_room_id(seat_item),
        "roomName": str(seat_item.get("roomName", "")).strip(),
        "storey": str(seat_map["info"].get("storey", "")).strip(),
        "planUrl": str(seat_map["info"].get("plan", "")).strip(),
        "width": int(seat_map["info"]["width"]),
        "height": int(seat_map["info"]["height"]),
        "availableCount": sum(1 for seat in seats if seat["selectable"]),
        "lockedCount": sum(1 for seat in seats if not seat["selectable"]),
        "selectedSeatId": None if selected_seat is None else selected_seat["seatId"],
        "selectedSeatNumber": None
        if selected_seat is None
        else selected_seat["seatNumber"],
        "systemRecommendedSeatId": recommended_seat_id,
        "systemRecommendedSeatNumber": recommended_seat_number,
        "seats": seats,
    }


def _extract_recommended_poi(seat_item: dict) -> dict | None:
    seat_map = seat_item.get("seatMap")
    if not isinstance(seat_map, dict):
        return None
    pois = seat_map.get("POIs")
    if not isinstance(pois, list):
        return None
    selected_poi = next(
        (poi for poi in pois if isinstance(poi, dict) and str(poi.get("state")) == "2"),
        None,
    )
    if selected_poi is not None:
        return selected_poi
    return next(
        (poi for poi in pois if isinstance(poi, dict) and bool(poi.get("recommend"))),
        None,
    )


def serialize_poi(poi: dict) -> dict:
    state = str(poi.get("state"))
    return {
        "seatId": str(poi["id"]),
        "seatNumber": str(poi.get("title", "")).strip(),
        "x": int(poi["x"]),
        "y": int(poi["y"]),
        "w": int(poi["w"]),
        "h": int(poi["h"]),
        "state": state,
        "selectable": state in {"0", "2"},
        "recommended": bool(poi.get("recommend")),
        "hasSocket": str(poi.get("have_socket", "0")) == "1",
    }


def _load_current_user(payload: dict) -> dict:
    for origin in payload.get("origins", []):
        for local_storage in origin.get("localStorage", []):
            if not str(local_storage.get("name", "")).endswith("/currentUser"):
                continue
            value = local_storage.get("value")
            if isinstance(value, str) and value:
                return json.loads(value)
    raise ValueError("登录态中未找到当前用户信息，请重新执行 save-login")


def _remove_lab_json(url: str) -> str:
    parsed = urlparse(url)
    query = parse_qs(parsed.query, keep_blank_values=True)
    query.pop("LAB_JSON", None)
    return urlunparse(
        (
            parsed.scheme,
            parsed.netloc,
            parsed.path,
            "",
            urlencode(query, doseq=True),
            "",
        )
    )


def _seat_number_sort_key(seat_number: str) -> tuple[int, int | str]:
    text = seat_number.strip()
    if text.isdigit():
        return (0, int(text))
    return (1, text)


def _validate_booking_arrays(
    seat_ids: tuple[str, ...], seat_booker_ids: tuple[str, ...]
) -> None:
    if not seat_ids:
        raise ValueError("至少需要提供一个座位 ID")
    if len(seat_ids) != len(seat_booker_ids):
        raise ValueError("座位数量与预约人数量必须一致")


def _format_date_label(date_value) -> str:
    return f"{date_value.month:02d}月{date_value.day:02d}日 {WEEKDAY_LABELS[date_value.weekday()]}"


def _collect_ibeacon_minors(raw_beacons: object) -> tuple[int, ...]:
    if not isinstance(raw_beacons, list):
        return ()

    minors: list[int] = []
    seen_minors: set[int] = set()
    for beacon in raw_beacons:
        if not isinstance(beacon, dict):
            continue
        minor = _read_optional_int(beacon.get("minor"))
        if minor is None or minor in seen_minors:
            continue
        seen_minors.add(minor)
        minors.append(minor)
    return tuple(minors)


def _read_optional_int(value: object) -> int | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        return None
