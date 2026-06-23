from __future__ import annotations

from pathlib import Path
from urllib.error import HTTPError

from wuyi_seat_bot.api_seat_automation import ApiSeatAutomation
from wuyi_seat_bot.entry_url_cache import save_resolved_entry_urls
from wuyi_seat_bot.models import AppConfig
from wuyi_seat_bot.seat_api import (
    append_lab_json,
    build_book_form,
    build_book_token,
    build_custom_search_form_payload,
    build_room_seat_maps,
    build_begin_time,
    build_book_api_url,
    build_checkin_candidates,
    build_checkout_candidates,
    current_api_time,
    fetch_json,
    fetch_my_booking_list,
    load_saved_session,
    perform_seat_cancel_booking,
)

from prevent_auto.models import Account, BookingSnapshot

class WuyiBridge:
    def __init__(self, package_root: str | Path) -> None:
        self.package_root = Path(package_root).resolve()
        self.config_path = self.package_root / "bridge-config.json"

    def refresh_login(self, account: Account) -> Path:
        self._clear_resolved_entry_urls(account)
        automation = self._build_automation(account)
        return automation.save_login_state(wait_for_enter=False)

    def fetch_bookings(self, account: Account) -> list[BookingSnapshot]:
        payload = self._fetch_booking_payload(account)
        checkin_lookup = {
            candidate.booking_id: candidate
            for candidate in build_checkin_candidates(payload)
        }
        bookings: list[BookingSnapshot] = []
        for candidate in build_checkout_candidates(payload):
            checkin_candidate = checkin_lookup.get(candidate.booking_id)
            bookings.append(
                BookingSnapshot(
                    booking_id=candidate.booking_id,
                    room_name=candidate.room_name,
                    seat_number=candidate.seat_number,
                    status=candidate.status,
                    start_time=candidate.start_time,
                    duration_seconds=(
                        0 if checkin_candidate is None else checkin_candidate.duration_seconds
                    ),
                    checkin_deadline_at=(
                        None
                        if checkin_candidate is None
                        else checkin_candidate.start_time
                        + max(checkin_candidate.limit_sign_back_seconds, 0)
                    ),
                )
            )
        return sorted(bookings, key=lambda item: item.start_time, reverse=True)

    def cancel_booking(self, account: Account, booking_id: str) -> tuple[bool, str]:
        try:
            return self._cancel_booking_once(account, booking_id)
        except Exception:  # noqa: BLE001
            try:
                self.refresh_login(account)
                return self._cancel_booking_once(account, booking_id)
            except Exception as retry_exc:  # noqa: BLE001
                return False, f"取消预约失败：{retry_exc}"

    def reserve_specific_seat(
        self,
        account: Account,
        *,
        room_name: str,
        seat_number: str,
        date_value: str,
        start_hour: int,
        end_hour: int,
    ) -> tuple[bool, str]:
        if end_hour <= start_hour:
            return False, "目标时间窗口无效"

        try:
            return self._reserve_specific_seat_once(
                account,
                room_name=room_name,
                seat_number=seat_number,
                date_value=date_value,
                start_hour=start_hour,
                end_hour=end_hour,
            )
        except Exception:  # noqa: BLE001
            try:
                self.refresh_login(account)
                return self._reserve_specific_seat_once(
                    account,
                    room_name=room_name,
                    seat_number=seat_number,
                    date_value=date_value,
                    start_hour=start_hour,
                    end_hour=end_hour,
                )
            except HTTPError as retry_exc:
                return False, f"重约请求失败：HTTP {retry_exc.code}"
            except Exception as retry_exc:  # noqa: BLE001
                return False, f"重约失败：{retry_exc}"

    def _load_saved_session(self, account: Account):
        state_path = self.package_root / account.state_file
        if not state_path.exists():
            self.refresh_login(account)
        return load_saved_session(state_path)

    def _fetch_booking_payload(self, account: Account) -> dict:
        try:
            saved_session = self._load_saved_session(account)
            return fetch_my_booking_list(saved_session.cookie_header)
        except Exception as exc:
            try:
                self.refresh_login(account)
            except Exception as refresh_exc:  # noqa: BLE001
                raise ValueError(f"自动续登失败：{refresh_exc}") from refresh_exc

            try:
                saved_session = self._load_saved_session(account)
                return fetch_my_booking_list(saved_session.cookie_header)
            except Exception as retry_exc:  # noqa: BLE001
                raise ValueError(f"自动续登后仍无法读取预约：{retry_exc}") from retry_exc

    def _build_automation(self, account: Account) -> ApiSeatAutomation:
        return ApiSeatAutomation(
            AppConfig(
                login_url=account.login_url,
                state_file=account.state_file,
                seat_urls=(account.seat_url,),
                account_name=account.name,
                student_id=account.student_id,
                password=account.password,
                preferred_room_names=(),
                preferred_seat_numbers=(),
            ),
            self.config_path,
        )

    def _cancel_booking_once(self, account: Account, booking_id: str) -> tuple[bool, str]:
        saved_session = self._load_saved_session(account)
        result = perform_seat_cancel_booking(saved_session.cookie_header, booking_id)
        return result.success, result.message

    def _reserve_specific_seat_once(
        self,
        account: Account,
        *,
        room_name: str,
        seat_number: str,
        date_value: str,
        start_hour: int,
        end_hour: int,
    ) -> tuple[bool, str]:
        saved_session = self._load_saved_session(account)
        search_api_url = self._resolve_search_api_url(
            account,
            cookie_header=saved_session.cookie_header,
        )
        duration_seconds = (end_hour - start_hour) * 3600
        begin_time = build_begin_time(date_value, start_hour)
        search_page_payload = fetch_json(
            append_lab_json(search_api_url),
            cookie_header=saved_session.cookie_header,
        )
        search_result_payload = fetch_json(
            append_lab_json(search_api_url),
            cookie_header=saved_session.cookie_header,
            method="POST",
            form_data=build_custom_search_form_payload(
                search_page_payload,
                begin_time=begin_time,
                duration_seconds=duration_seconds,
                people_count=1,
            ),
        )
        seat = _find_target_seat(
            room_name=room_name,
            seat_number=seat_number,
            room_maps=build_room_seat_maps(search_result_payload),
        )
        if seat is None:
            return False, f"{room_name} {seat_number} 号座位当前不可预约"

        api_time = current_api_time()
        result = fetch_json(
            build_book_api_url(search_api_url),
            cookie_header=saved_session.cookie_header,
            method="POST",
            form_data=build_book_form(
                begin_time=begin_time,
                duration_seconds=duration_seconds,
                seat_ids=(seat["seatId"],),
                seat_booker_ids=(saved_session.user_id,),
                api_time=api_time,
                is_recommend=0,
            ),
            extra_headers={
                "Api-Token": build_book_token(
                    begin_time=begin_time,
                    duration_seconds=duration_seconds,
                    seat_ids=(seat["seatId"],),
                    seat_booker_ids=(saved_session.user_id,),
                    api_time=api_time,
                    is_recommend=0,
                )
            },
        )
        blacklist_message = _extract_blacklist_message(result)
        if blacklist_message is not None:
            return False, f"账号已被拉黑：{blacklist_message}"
        if result.get("CODE") != "ok":
            return False, str(result.get("MESSAGE", "重约接口返回失败"))
        data = result.get("DATA", {})
        if data.get("result") != "success":
            return False, str(data.get("msg", "重约失败"))
        return True, f"已重约：{room_name} {seat_number} 号座位"

    def _resolve_search_api_url(
        self,
        account: Account,
        *,
        cookie_header: str,
    ) -> str:
        self._clear_resolved_entry_urls(account)
        automation = self._build_automation(account)
        return automation.resolve_search_api_url(
            account.seat_url,
            cookie_header=cookie_header,
        )

    def _clear_resolved_entry_urls(self, account: Account) -> None:
        save_resolved_entry_urls(self.config_path, account.name, {})


def _find_target_seat(*, room_name: str, seat_number: str, room_maps: tuple[dict, ...]) -> dict | None:
    target_room = next(
        (
            room_map
            for room_map in room_maps
            if str(room_map.get("roomName", "")).strip() == room_name
        ),
        None,
    )
    if target_room is None:
        return None
    return next(
        (
            seat
            for seat in target_room.get("seats", [])
            if str(seat.get("seatNumber", "")).strip() == seat_number
            and bool(seat.get("selectable"))
        ),
        None,
    )


def _extract_blacklist_message(result: dict) -> str | None:
    candidates = [str(result.get("MESSAGE", "")).strip()]
    data = result.get("DATA")
    if isinstance(data, dict):
        candidates.append(str(data.get("msg", "")).strip())
    for message in candidates:
        if message and "黑名单" in message:
            return message
    return None
