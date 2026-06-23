import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from wuyi_seat_bot.seat_api import (
    SHANGHAI_TZ,
    append_lab_json,
    build_request_error_message,
    build_room_seat_maps,
    build_checkin_candidates,
    build_checkout_candidates,
    build_begin_time,
    build_book_form,
    build_book_token,
    build_date_options,
    build_default_filters,
    build_duration_options,
    build_people_options,
    build_search_form_payload,
    build_single_seat_book_token,
    build_time_options,
    build_reservation_request_context,
    choose_seat,
    describe_seat_booking_status,
    extract_search_api_url,
    is_checkin_window_open,
    find_recommend_seat_item,
    load_saved_session,
    perform_seat_cancel_booking,
    perform_seat_checkin,
    perform_seat_checkout,
    serialize_seat_map,
)


class SeatApiHelpersTestCase(unittest.TestCase):
    def _build_search_page_payload(self) -> dict:
        return {
            "data": {
                "default": {
                    "date": 1774656000,
                    "beginTime": 8,
                    "duration": 1,
                    "num": 1,
                },
                "range": {
                    "max_date": 1774915200,
                    "minBeginTime": 8,
                    "maxEndTime": 22,
                    "min_duration": 1,
                    "max_duration": 14,
                    "max_num": 4,
                },
                "space_category": {"category_id": "591", "content_id": "28"},
            }
        }

    def test_extract_search_api_url_supports_hash_route(self) -> None:
        entry_url = (
            "https://wuyiu.huitu.zhishulib.com/"
            "#!/Seat/Index/searchSeats?space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28"
        )

        self.assertEqual(
            extract_search_api_url(entry_url),
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats"
            "?space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28",
        )

    def test_append_lab_json_preserves_existing_query(self) -> None:
        url = (
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats"
            "?space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28"
        )

        self.assertEqual(
            append_lab_json(url),
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats"
            "?space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28&LAB_JSON=1",
        )

    def test_build_request_error_message_highlights_obsolete_huitu_endpoint(
        self,
    ) -> None:
        message = build_request_error_message(
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/myBookingList?LAB_JSON=1",
            status_code=404,
            payload={"CODE": "NotFound", "ui_type": "com.Message"},
            default_message="请求失败：HTTP 404",
        )

        self.assertIn("旧接口已失效", message)
        self.assertIn("login_url / seat_url", message)

    def test_load_saved_session_reads_cookie_and_user_id(self) -> None:
        payload = {
            "cookies": [
                {"name": "org_id", "value": "137"},
                {"name": "auth", "value": "token"},
            ],
            "origins": [
                {
                    "origin": "https://wuyiu.huitu.zhishulib.com",
                    "localStorage": [
                        {
                            "name": "lrnw_AS_Parse/lab4/currentUser",
                            "value": json.dumps({"id": "405181"}, ensure_ascii=False),
                        }
                    ],
                }
            ],
        }

        with tempfile.TemporaryDirectory() as tmp_dir:
            state_path = Path(tmp_dir) / "auth.json"
            state_path.write_text(
                json.dumps(payload, ensure_ascii=False), encoding="utf-8"
            )
            session = load_saved_session(state_path)

        self.assertEqual(session.cookie_header, "org_id=137; auth=token")
        self.assertEqual(session.user_id, "405181")

    def test_choose_seat_prefers_configured_seat_numbers(self) -> None:
        pois = [
            {"id": "21964", "title": "87", "state": 0},
            {"id": "21961", "title": "58", "state": 0},
            {"id": "21960", "title": "57", "state": 0},
        ]

        selection = choose_seat(pois, ("58", "87"))

        self.assertEqual(selection.seat_id, "21961")
        self.assertEqual(selection.seat_number, "58")

    def test_choose_seat_falls_back_to_lowest_available_seat_number(self) -> None:
        pois = [
            {"id": "21964", "title": "87", "state": 0},
            {"id": "21961", "title": "58", "state": 0},
            {"id": "21960", "title": "57", "state": 0},
        ]

        selection = choose_seat(pois, ())

        self.assertEqual(selection.seat_id, "21960")
        self.assertEqual(selection.seat_number, "57")

    def test_build_single_seat_book_token_matches_frontend_formula(self) -> None:
        token = build_single_seat_book_token(
            begin_time=1774656000,
            duration_seconds=3600,
            seat_id="21422",
            user_id="405181",
            api_time=1774625863,
            is_recommend=1,
        )

        self.assertEqual(token, "ODg2MDc1OTY5Nzg0ODljMWQ2OGU4OTRkZWYwNzU2ZjU=")

    def test_build_book_token_supports_manual_single_seat_reservation(self) -> None:
        token = build_book_token(
            begin_time=1774656000,
            duration_seconds=3600,
            seat_ids=("21422",),
            seat_booker_ids=("405181",),
            api_time=1774625863,
            is_recommend=0,
        )

        self.assertEqual(token, "NTFmNDVlYzI1ZWEwYWQwYjZkZjM5ZmM1OTg2ZWM3NDY=")

    def test_build_book_form_supports_manual_single_seat_reservation(self) -> None:
        self.assertEqual(
            build_book_form(
                begin_time=1774656000,
                duration_seconds=3600,
                seat_ids=("21422",),
                seat_booker_ids=("405181",),
                api_time=1774625863,
                is_recommend=0,
            ),
            [
                ("beginTime", "1774656000"),
                ("duration", "3600"),
                ("seats[0]", "21422"),
                ("is_recommend", "0"),
                ("api_time", "1774625863"),
                ("seatBookers[0]", "405181"),
            ],
        )

    def test_build_reservation_request_context_uses_recommended_room_data(self) -> None:
        search_page_payload = {
            "data": {
                "default": {"date": 1774656000, "duration": 1, "num": 1},
                "space_category": {"category_id": "591", "content_id": "28"},
            }
        }
        search_result_payload = {
            "content": {
                "children": [
                    {
                        "children": {
                            "ui_type": "ht.Seat.RecommendSeatItem",
                            "roomName": "自习室圆形二楼",
                            "seatMap": {
                                "POIs": [
                                    {"id": "21964", "title": "87", "state": 0},
                                    {"id": "21961", "title": "58", "state": 0},
                                ]
                            },
                        }
                    }
                ]
            }
        }

        selection, begin_time, duration_seconds = build_reservation_request_context(
            search_page_payload,
            search_result_payload,
            ("58",),
        )

        self.assertEqual(selection.room_name, "自习室圆形二楼")
        self.assertEqual(selection.seat_number, "58")
        self.assertEqual(begin_time, 1774656000)
        self.assertEqual(duration_seconds, 3600)

    def test_build_reservation_request_context_prefers_configured_room_names(
        self,
    ) -> None:
        search_page_payload = {
            "data": {
                "default": {"date": 1774656000, "duration": 1, "num": 1},
                "space_category": {"category_id": "591", "content_id": "28"},
            }
        }
        search_result_payload = {
            "allContent": {
                "children": [
                    {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "综合阅览室",
                        "ifRecommend": False,
                        "seatMap": {
                            "info": {
                                "id": "1154",
                                "plan": "",
                                "width": "100",
                                "height": "50",
                            },
                            "POIs": [
                                {
                                    "id": "301",
                                    "title": "58",
                                    "x": "1",
                                    "y": "1",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                },
                            ],
                        },
                    },
                    {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "自习室圆形二楼",
                        "ifRecommend": False,
                        "seatMap": {
                            "info": {
                                "id": "1153",
                                "plan": "",
                                "width": "100",
                                "height": "50",
                            },
                            "POIs": [
                                {
                                    "id": "201",
                                    "title": "87",
                                    "x": "1",
                                    "y": "1",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                },
                                {
                                    "id": "202",
                                    "title": "58",
                                    "x": "2",
                                    "y": "2",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                },
                            ],
                        },
                    },
                ]
            }
        }

        selection, begin_time, duration_seconds = build_reservation_request_context(
            search_page_payload,
            search_result_payload,
            ("58",),
            ("自习室圆形二楼",),
        )

        self.assertEqual(selection.room_name, "自习室圆形二楼")
        self.assertEqual(selection.seat_id, "202")
        self.assertEqual(selection.seat_number, "58")
        self.assertEqual(begin_time, 1774656000)
        self.assertEqual(duration_seconds, 3600)

    def test_build_search_form_payload_uses_default_date_and_duration(self) -> None:
        payload = self._build_search_page_payload()

        self.assertEqual(
            build_search_form_payload(payload),
            [
                ("beginTime", "1774656000"),
                ("duration", "3600"),
                ("num", "1"),
                ("space_category[category_id]", "591"),
                ("space_category[content_id]", "28"),
            ],
        )

    def test_build_default_filters_returns_date_start_duration_and_people(self) -> None:
        filters = build_default_filters(self._build_search_page_payload())

        self.assertEqual(filters.date_value, "2026-03-28")
        self.assertEqual(filters.start_hour, 8)
        self.assertEqual(filters.duration_hours, 14)
        self.assertEqual(filters.people_count, 1)

    def test_build_default_filters_prefers_default_begin_time(self) -> None:
        payload = self._build_search_page_payload()
        payload["data"]["default"]["date"] = 1774694482
        payload["data"]["default"]["beginTime"] = 18
        payload["data"]["range"]["minBeginTime"] = 8

        filters = build_default_filters(payload)

        self.assertEqual(filters.date_value, "2026-03-28")
        self.assertEqual(filters.start_hour, 18)
        self.assertEqual(filters.duration_hours, 4)

    def test_build_default_filters_uses_current_time_for_today(self) -> None:
        payload = self._build_search_page_payload()
        payload["data"]["default"]["date"] = 1774742400
        payload["data"]["default"]["beginTime"] = 8

        filters = build_default_filters(
            payload,
            now=datetime(2026, 3, 29, 20, 49, tzinfo=SHANGHAI_TZ),
        )

        self.assertEqual(filters.date_value, "2026-03-29")
        self.assertEqual(filters.start_hour, 21)
        self.assertEqual(filters.duration_hours, 1)

    def test_build_default_filters_uses_current_hour_during_first_half_hour(
        self,
    ) -> None:
        payload = self._build_search_page_payload()
        payload["data"]["default"]["date"] = 1774742400
        payload["data"]["default"]["beginTime"] = 8

        filters = build_default_filters(
            payload,
            now=datetime(2026, 3, 29, 20, 20, tzinfo=SHANGHAI_TZ),
        )

        self.assertEqual(filters.date_value, "2026-03-29")
        self.assertEqual(filters.start_hour, 20)
        self.assertEqual(filters.duration_hours, 2)

    def test_build_date_options_returns_human_readable_labels(self) -> None:
        options = build_date_options(self._build_search_page_payload())

        self.assertEqual(
            options[:2],
            [
                {"value": "2026-03-28", "label": "03月28日 周六"},
                {"value": "2026-03-29", "label": "03月29日 周日"},
            ],
        )

    def test_build_date_options_includes_max_date_boundary(self) -> None:
        options = build_date_options(self._build_search_page_payload())

        self.assertEqual(options[-1]["value"], "2026-03-31")
        self.assertEqual(len(options), 4)

    def test_build_time_options_returns_full_hour_range(self) -> None:
        self.assertEqual(
            build_time_options(self._build_search_page_payload())[:3],
            [
                {"value": 8, "label": "8:00"},
                {"value": 9, "label": "9:00"},
                {"value": 10, "label": "10:00"},
            ],
        )

    def test_build_duration_options_respects_selected_start_hour(self) -> None:
        options = build_duration_options(
            self._build_search_page_payload(), start_hour=20
        )

        self.assertEqual(
            options,
            [
                {"value": 1, "label": "1小时"},
                {"value": 2, "label": "2小时"},
            ],
        )

    def test_build_people_options_returns_configured_range(self) -> None:
        self.assertEqual(
            build_people_options(self._build_search_page_payload()),
            [
                {"value": 1, "label": "1人"},
                {"value": 2, "label": "2人"},
                {"value": 3, "label": "3人"},
                {"value": 4, "label": "4人"},
            ],
        )

    def test_build_begin_time_uses_shanghai_timezone(self) -> None:
        self.assertEqual(build_begin_time("2026-03-29", 9), 1774746000)

    def test_build_checkin_candidates_reads_booking_list_status_and_ibeacons(
        self,
    ) -> None:
        payload = {
            "content": {
                "defaultItems": [
                    {
                        "id": 21546358,
                        "roomName": "自习室圆形二楼",
                        "seatNum": "165",
                        "status": "0",
                        "time": "1774656000",
                        "duration": "3600",
                        "nowTime": 1774654200,
                        "limitSignAgo": 1800,
                        "limitSignBack": 1800,
                        "ibeacons": [
                            {"minor": "34173"},
                            {"minor": "34174"},
                            {"minor": "34173"},
                        ],
                    }
                ]
            }
        }

        candidates = build_checkin_candidates(payload)

        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].booking_id, "21546358")
        self.assertEqual(candidates[0].status, "0")
        self.assertEqual(candidates[0].ibeacon_minors, (34173, 34174))
        self.assertTrue(is_checkin_window_open(candidates[0]))

    def test_build_checkout_candidates_reads_active_booking_status(self) -> None:
        payload = {
            "content": {
                "defaultItems": [
                    {
                        "id": 21546358,
                        "roomName": "自习室圆形二楼",
                        "seatNum": "165",
                        "status": "1",
                        "time": "1774656000",
                    }
                ]
            }
        }

        candidates = build_checkout_candidates(payload)

        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].booking_id, "21546358")
        self.assertEqual(candidates[0].status, "1")
        self.assertEqual(candidates[0].seat_number, "165")

    def test_describe_seat_booking_status_returns_known_label(self) -> None:
        self.assertEqual(describe_seat_booking_status("0"), "待签到")
        self.assertEqual(describe_seat_booking_status("5"), "未签到结束")

    def test_perform_seat_checkin_returns_server_message(self) -> None:
        with patch(
            "wuyi_seat_bot.seat_api.fetch_json",
            return_value={
                "CODE": "ok",
                "MESSAGE": "请求成功",
                "DATA": {"result": "fail", "msg": "当前状态无法操作，状态非待签到状态"},
            },
        ) as fetch_json_mock:
            result = perform_seat_checkin("auth=token", "21546358")

        fetch_json_mock.assert_called_once()
        self.assertFalse(result.success)
        self.assertEqual(result.message, "当前状态无法操作，状态非待签到状态")

    def test_perform_seat_checkin_puts_booking_id_in_query_string(self) -> None:
        with patch(
            "wuyi_seat_bot.seat_api.fetch_json",
            return_value={
                "CODE": "ok",
                "MESSAGE": "请求成功",
                "DATA": {"result": "success", "now_time": 1776429538},
            },
        ) as fetch_json_mock:
            result = perform_seat_checkin("auth=token", "21702521")

        self.assertTrue(result.success)
        fetch_json_mock.assert_called_once_with(
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/checkIn?bookingId=21702521&LAB_JSON=1",
            cookie_header="auth=token",
            method="POST",
            form_data=[],
        )

    def test_perform_seat_checkout_returns_server_message(self) -> None:
        with patch(
            "wuyi_seat_bot.seat_api.fetch_json",
            return_value={
                "CODE": "ok",
                "MESSAGE": "请求成功",
                "DATA": {
                    "result": "fail",
                    "msg": "当前状态无法操作，状态非签到成功状态",
                },
            },
        ) as fetch_json_mock:
            result = perform_seat_checkout("auth=token", "21546358")

        fetch_json_mock.assert_called_once()
        self.assertFalse(result.success)
        self.assertEqual(result.message, "当前状态无法操作，状态非签到成功状态")

    def test_perform_seat_checkout_puts_booking_id_in_query_string(self) -> None:
        with patch(
            "wuyi_seat_bot.seat_api.fetch_json",
            return_value={
                "CODE": "ok",
                "MESSAGE": "请求成功",
                "DATA": {"result": "success", "now_time": 1776429539},
            },
        ) as fetch_json_mock:
            result = perform_seat_checkout("auth=token", "21702521")

        self.assertTrue(result.success)
        fetch_json_mock.assert_called_once_with(
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/checkOut?bookingId=21702521&LAB_JSON=1",
            cookie_header="auth=token",
            method="POST",
            form_data=[],
        )

    def test_perform_seat_cancel_booking_returns_server_message(self) -> None:
        with patch(
            "wuyi_seat_bot.seat_api.fetch_json",
            return_value={
                "CODE": "ok",
                "MESSAGE": "请求成功",
                "DATA": {"result": "fail", "msg": "当前状态无法操作，状态非待签到状态"},
            },
        ) as fetch_json_mock:
            result = perform_seat_cancel_booking("auth=token", "21546358")

        fetch_json_mock.assert_called_once()
        self.assertFalse(result.success)
        self.assertEqual(result.message, "当前状态无法操作，状态非待签到状态")

    def test_perform_seat_cancel_booking_treats_success_message_as_success(
        self,
    ) -> None:
        with patch(
            "wuyi_seat_bot.seat_api.fetch_json",
            return_value={
                "CODE": "ok",
                "MESSAGE": "请求成功",
                "DATA": {"result": "fail", "msg": "取消预约成功"},
            },
        ) as fetch_json_mock:
            result = perform_seat_cancel_booking("auth=token", "21546358")

        fetch_json_mock.assert_called_once()
        self.assertTrue(result.success)
        self.assertEqual(result.message, "取消预约成功")

    def test_find_recommend_seat_item_falls_back_to_all_content_room_list(self) -> None:
        payload = {
            "content": {
                "children": [
                    {"ui_type": "ht.Seat.SysTipBarBlock"},
                    {"ui_type": "ht.Seat.OrderInfoBlock"},
                ]
            },
            "allContent": {
                "children": [
                    {
                        "ui_type": "com.CatCon",
                        "children": {
                            "ui_type": "com.BlockList",
                            "children": [
                                {
                                    "ui_type": "ht.Seat.RecommendSeatItem",
                                    "roomName": "综合阅览室",
                                    "ifRecommend": False,
                                    "seatMap": {
                                        "info": {
                                            "plan": "",
                                            "width": "100",
                                            "height": "50",
                                        },
                                        "POIs": [
                                            {
                                                "id": "1",
                                                "title": "11",
                                                "x": "1",
                                                "y": "1",
                                                "w": "1",
                                                "h": "1",
                                                "state": 0,
                                            }
                                        ],
                                    },
                                },
                                {
                                    "ui_type": "ht.Seat.RecommendSeatItem",
                                    "roomName": "自习室圆形二楼",
                                    "ifRecommend": True,
                                    "seatMap": {
                                        "info": {
                                            "plan": "",
                                            "width": "100",
                                            "height": "50",
                                        },
                                        "POIs": [
                                            {
                                                "id": "2",
                                                "title": "22",
                                                "x": "2",
                                                "y": "2",
                                                "w": "1",
                                                "h": "1",
                                                "state": 0,
                                            }
                                        ],
                                    },
                                },
                            ],
                        },
                    }
                ]
            },
        }

        seat_item = find_recommend_seat_item(payload)

        self.assertEqual(seat_item["roomName"], "自习室圆形二楼")

    def test_build_room_seat_maps_returns_unique_room_list_from_same_response(
        self,
    ) -> None:
        payload = {
            "content": {
                "children": [
                    {
                        "children": {
                            "ui_type": "ht.Seat.RecommendSeatItem",
                            "roomName": "自习室圆形一楼",
                            "ifRecommend": True,
                            "seatMap": {
                                "info": {
                                    "id": "1152",
                                    "plan": "https://example.com/plan-floor-1.png",
                                    "width": "138",
                                    "height": "74",
                                },
                                "POIs": [
                                    {
                                        "id": "21425",
                                        "title": "120",
                                        "x": "1",
                                        "y": "1",
                                        "w": "1",
                                        "h": "1",
                                        "have_socket": "0",
                                        "state": 0,
                                    },
                                    {
                                        "id": "21424",
                                        "title": "119",
                                        "x": "2",
                                        "y": "2",
                                        "w": "1",
                                        "h": "1",
                                        "have_socket": "0",
                                        "state": 2,
                                        "recommend": True,
                                    },
                                ],
                            },
                        }
                    }
                ]
            },
            "allContent": {
                "children": [
                    {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "综合阅览室",
                        "ifRecommend": False,
                        "seatMap": {
                            "info": {
                                "id": "1154",
                                "plan": "",
                                "width": "100",
                                "height": "50",
                            },
                            "POIs": [
                                {
                                    "id": "1",
                                    "title": "11",
                                    "x": "1",
                                    "y": "1",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                }
                            ],
                        },
                    },
                    {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "自习室圆形二楼",
                        "ifRecommend": False,
                        "seatMap": {
                            "info": {
                                "id": "1153",
                                "plan": "",
                                "width": "100",
                                "height": "50",
                            },
                            "POIs": [
                                {
                                    "id": "2",
                                    "title": "22",
                                    "x": "2",
                                    "y": "2",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                }
                            ],
                        },
                    },
                    {
                        "ui_type": "ht.Seat.RecommendSeatItem",
                        "roomName": "自习室圆形一楼",
                        "ifRecommend": False,
                        "seatMap": {
                            "info": {
                                "id": "1152",
                                "plan": "",
                                "width": "100",
                                "height": "50",
                            },
                            "POIs": [
                                {
                                    "id": "3",
                                    "title": "33",
                                    "x": "3",
                                    "y": "3",
                                    "w": "1",
                                    "h": "1",
                                    "state": 0,
                                }
                            ],
                        },
                    },
                ]
            },
        }

        room_maps = build_room_seat_maps(payload)

        self.assertEqual(
            [(room["roomId"], room["roomName"]) for room in room_maps],
            [
                ("1154", "综合阅览室"),
                ("1153", "自习室圆形二楼"),
                ("1152", "自习室圆形一楼"),
            ],
        )
        self.assertEqual(room_maps[-1]["systemRecommendedSeatNumber"], "119")

    def test_serialize_seat_map_counts_available_and_recommended_seats(self) -> None:
        payload = {
            "content": {
                "children": [
                    {
                        "children": {
                            "ui_type": "ht.Seat.RecommendSeatItem",
                            "roomName": "自习室圆形二楼",
                            "seatMap": {
                                "info": {
                                    "plan": "https://example.com/plan.png",
                                    "width": "168",
                                    "height": "84",
                                },
                                "POIs": [
                                    {
                                        "id": "21964",
                                        "title": "87",
                                        "x": "71",
                                        "y": "42",
                                        "w": "2",
                                        "h": "2",
                                        "have_socket": "1",
                                        "state": 2,
                                        "recommend": True,
                                    },
                                    {
                                        "id": "21961",
                                        "title": "58",
                                        "x": "117",
                                        "y": "19",
                                        "w": "2",
                                        "h": "2",
                                        "have_socket": "0",
                                        "state": 0,
                                    },
                                    {
                                        "id": "21960",
                                        "title": "57",
                                        "x": "115",
                                        "y": "19",
                                        "w": "2",
                                        "h": "2",
                                        "have_socket": "0",
                                        "state": "1",
                                    },
                                ],
                            },
                        }
                    }
                ]
            }
        }

        seat_map = serialize_seat_map(payload)

        self.assertEqual(seat_map["availableCount"], 2)
        self.assertEqual(seat_map["lockedCount"], 1)
        self.assertEqual(seat_map["systemRecommendedSeatId"], "21964")
        self.assertEqual(seat_map["systemRecommendedSeatNumber"], "87")
        self.assertTrue(seat_map["seats"][0]["hasSocket"])

    def test_serialize_seat_map_reports_missing_seat_data_clearly(self) -> None:
        payload = {
            "ui_type": "ht.Seat.SysRecommendPage",
            "content": {"ui_type": "com.BlockList", "children": []},
            "allContent": {"ui_type": "com.BlockList", "children": []},
        }

        with self.assertRaisesRegex(ValueError, "未找到座位数据"):
            serialize_seat_map(payload)
