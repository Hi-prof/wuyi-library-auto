import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from wuyi_seat_bot.api_seat_automation import (
    ApiSeatAutomation,
    _extract_search_api_url_from_payload,
    _login_with_credentials,
    _normalize_current_user_payload,
)
from wuyi_seat_bot.models import ActionType, AppConfig, SeatActionOutcome, SeatActionStatus


def write_state_file(root: Path) -> Path:
    state_path = root / "runtime" / "auth.json"
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(
        json.dumps(
            {
                "cookies": [
                    {"name": "auth", "value": "token", "domain": "example.com", "path": "/"},
                ],
                "origins": [
                    {
                        "origin": "https://example.com",
                        "localStorage": [
                            {"name": "lrnw_AS_Parse/lab4/currentUser", "value": json.dumps({"id": "405059"})},
                        ],
                    }
                ],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    return state_path


class PerformActionTestCase(unittest.TestCase):
    def test_perform_action_requires_saved_state_before_dispatch(self) -> None:
        automation = self._build_automation(create_state_file=False)

        with patch.object(ApiSeatAutomation, "_perform_reserve_via_api") as reserve_mock:
            result = automation.perform_action(ActionType.RESERVE, "https://example.com/entry")

        reserve_mock.assert_not_called()
        self.assertEqual(result.status, SeatActionStatus.FAILED)
        self.assertIn("未找到登录态文件", result.message)

    def test_perform_action_dispatches_by_action_type(self) -> None:
        automation = self._build_automation()

        with (
            patch.object(
                ApiSeatAutomation,
                "_perform_reserve_via_api",
                return_value=SeatActionOutcome(status=SeatActionStatus.SUCCESS, message="预约成功"),
            ) as reserve_mock,
            patch.object(
                ApiSeatAutomation,
                "_perform_checkin_via_bluetooth",
                return_value=SeatActionOutcome(status=SeatActionStatus.SUCCESS, message="签到成功"),
            ) as checkin_mock,
            patch.object(
                ApiSeatAutomation,
                "_perform_checkout_via_api",
                return_value=SeatActionOutcome(status=SeatActionStatus.SUCCESS, message="签退成功"),
            ) as checkout_mock,
        ):
            reserve_result = automation.perform_action(ActionType.RESERVE, "https://example.com/entry")
            checkin_result = automation.perform_action(ActionType.CHECKIN, "https://example.com/entry")
            checkout_result = automation.perform_action(ActionType.CHECKOUT, "https://example.com/entry")

        reserve_mock.assert_called_once_with("https://example.com/entry")
        checkin_mock.assert_called_once_with()
        checkout_mock.assert_called_once_with()
        self.assertEqual(reserve_result.message, "预约成功")
        self.assertEqual(checkin_result.message, "签到成功")
        self.assertEqual(checkout_result.message, "签退成功")

    def _build_automation(self, *, create_state_file: bool = True) -> ApiSeatAutomation:
        tmp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(tmp_dir.cleanup)
        root = Path(tmp_dir.name)
        if create_state_file:
            write_state_file(root)
        return ApiSeatAutomation(
            AppConfig(
                login_url="https://example.com/login",
                state_file="runtime/auth.json",
                seat_urls=("https://example.com/entry",),
                account_name="主号",
            ),
            root / "config.json",
        )


class SaveLoginStateTestCase(unittest.TestCase):
    def test_save_login_state_requires_credentials(self) -> None:
        automation = ApiSeatAutomation(
            AppConfig(
                login_url="https://example.com/login",
                state_file="runtime/auth.json",
                seat_urls=("https://example.com/entry",),
                account_name="主号",
            ),
            Path(tempfile.gettempdir()) / "config.json",
        )

        with self.assertRaisesRegex(ValueError, "需要在配置中提供 student_id 和 password"):
            automation.save_login_state()

    def test_save_login_state_writes_state_file_and_warms_cache(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            automation = ApiSeatAutomation(
                AppConfig(
                    login_url="https://example.com/login",
                    state_file="runtime/auth.json",
                    seat_urls=("https://example.com/entry",),
                    account_name="主号",
                    student_id="20231121130",
                    password="20231121130",
                ),
                root / "config.json",
            )
            state_payload = {
                "cookies": [{"name": "auth", "value": "token", "domain": "example.com", "path": "/"}],
                "origins": [],
            }

            with (
                patch("wuyi_seat_bot.api_seat_automation._login_with_credentials", return_value=state_payload) as login_mock,
                patch.object(ApiSeatAutomation, "_warm_up_resolved_entry_urls", return_value={}) as warm_up_mock,
            ):
                state_path = automation.save_login_state()

            login_mock.assert_called_once_with(
                login_url="https://example.com/login",
                student_id="20231121130",
                password="20231121130",
            )
            warm_up_mock.assert_called_once_with()
            self.assertEqual(state_path.resolve(), (root / "runtime" / "auth.json").resolve())
            self.assertEqual(
                json.loads(state_path.read_text(encoding="utf-8")),
                state_payload,
            )


class ResolveSearchApiUrlTestCase(unittest.TestCase):
    def test_extract_search_api_url_from_payload_reads_nested_link(self) -> None:
        payload = {
            "content": {
                "defaultItems": [
                    {
                        "name": "自习室",
                        "link": {
                            "type": "push",
                            "url": "/Seat/Index/searchSeats?space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28",
                        },
                    }
                ]
            }
        }

        resolved = _extract_search_api_url_from_payload(
            payload,
            request_url="https://wuyiu.huitu.zhishulib.com/Space/Category/list",
        )

        self.assertEqual(
            resolved,
            "https://wuyiu.huitu.zhishulib.com/Seat/Index/searchSeats?space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28",
        )

    def test_resolve_search_api_url_prefers_direct_search_url(self) -> None:
        automation = ApiSeatAutomation(
            AppConfig(
                login_url="https://example.com/login",
                state_file="runtime/auth.json",
                seat_urls=("https://example.com/entry",),
                account_name="主号",
            ),
            Path(tempfile.gettempdir()) / "config.json",
        )

        resolved = automation.resolve_search_api_url(
            "https://example.com/#!/Seat/Index/searchSeats?space_category%5Bcategory_id%5D=591"
        )

        self.assertEqual(
            resolved,
            "https://example.com/Seat/Index/searchSeats?space_category%5Bcategory_id%5D=591",
        )


class LoginApiTestCase(unittest.TestCase):
    def test_login_with_credentials_uses_dynamic_signature_parameters(self) -> None:
        login_meta = {
            "content": {
                "data": {"code": "code-1", "str": "str-1"},
                "itemHeader": {"defaultData": {"custom_value": "137"}},
            }
        }
        current_user = {"id": "405059", "name": "许煌斌", "accessToken": "token-1"}
        opener = MagicMock()

        with (
            patch("wuyi_seat_bot.api_seat_automation.build_opener", return_value=opener),
            patch(
                "wuyi_seat_bot.api_seat_automation._open_json",
                side_effect=[login_meta, current_user],
            ) as open_json_mock,
            patch(
                "wuyi_seat_bot.api_seat_automation._build_state_payload",
                return_value={"cookies": [], "origins": []},
            ) as build_state_mock,
        ):
            result = _login_with_credentials(
                login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
                student_id="20231121130",
                password="20231121130",
            )

        self.assertEqual(result, {"cookies": [], "origins": []})
        login_request = open_json_mock.call_args_list[1].args[1]
        login_body = json.loads(login_request.data.decode("utf-8"))
        self.assertEqual(login_body["login_name"], "20231121130")
        self.assertEqual(login_body["password"], "20231121130")
        self.assertEqual(login_body["code"], "code-1")
        self.assertEqual(login_body["str"], "str-1")
        self.assertEqual(login_body["org_id"], "137")
        self.assertEqual(login_body["_ApplicationId"], "lab4")
        self.assertEqual(login_body["_JavaScriptKey"], "lab4")
        self.assertTrue(login_body["_InstallationId"])
        build_state_mock.assert_called_once()

    def test_normalize_current_user_payload_backfills_required_fields(self) -> None:
        normalized = _normalize_current_user_payload({"id": "405059", "accessToken": "token-1"})

        self.assertEqual(normalized["access_token"], "token-1")
        self.assertEqual(normalized["objectId"], "405059")
        self.assertEqual(normalized["sessionToken"], "fake")
        self.assertEqual(normalized["className"], "_User")
