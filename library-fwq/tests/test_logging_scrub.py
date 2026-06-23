from __future__ import annotations

import unittest

from prevent_auto.logging import (
    DEFAULT_SENSITIVE_FIELDS,
    SCRUBBED_PLACEHOLDER,
    scrub,
)


class ScrubTestCase(unittest.TestCase):
    def test_scrub_top_level_password(self) -> None:
        result = scrub({"password": "secret", "student_id": "20231121130"})
        self.assertEqual(result["password"], SCRUBBED_PLACEHOLDER)
        self.assertEqual(result["student_id"], "20231121130")

    def test_scrub_authorization_header_case_insensitive(self) -> None:
        result = scrub({"Authorization": "Bearer abc.def"})
        self.assertEqual(result["Authorization"], SCRUBBED_PLACEHOLDER)

        result_upper = scrub({"AUTHORIZATION": "Bearer abc.def"})
        self.assertEqual(result_upper["AUTHORIZATION"], SCRUBBED_PLACEHOLDER)

    def test_scrub_nested_dict(self) -> None:
        payload = {
            "outer": {
                "inner": {"PASSWORD": "x", "name": "alice"},
                "ok": "value",
            },
        }
        result = scrub(payload)
        self.assertEqual(result["outer"]["inner"]["PASSWORD"], SCRUBBED_PLACEHOLDER)
        self.assertEqual(result["outer"]["inner"]["name"], "alice")
        self.assertEqual(result["outer"]["ok"], "value")

    def test_scrub_list_of_dicts(self) -> None:
        payload = {
            "items": [
                {"token": "tk1", "label": "ok1"},
                {"token": "tk2", "label": "ok2"},
            ]
        }
        result = scrub(payload)
        self.assertEqual(result["items"][0]["token"], SCRUBBED_PLACEHOLDER)
        self.assertEqual(result["items"][0]["label"], "ok1")
        self.assertEqual(result["items"][1]["token"], SCRUBBED_PLACEHOLDER)
        self.assertEqual(result["items"][1]["label"], "ok2")

    def test_scrub_does_not_mutate_input(self) -> None:
        payload = {"password": "x", "nested": {"token": "t"}}
        original_repr = repr(payload)
        scrub(payload)
        self.assertEqual(repr(payload), original_repr)

    def test_scrub_preserves_non_string_keys(self) -> None:
        payload = {1: "ok", ("password",): "tuple-key-not-scrubbed"}
        result = scrub(payload)
        self.assertEqual(result[1], "ok")
        self.assertEqual(result[("password",)], "tuple-key-not-scrubbed")

    def test_scrub_handles_tuple_and_set(self) -> None:
        payload = {"items": ({"password": "x"}, {"name": "ok"})}
        result = scrub(payload)
        self.assertIsInstance(result["items"], tuple)
        self.assertEqual(result["items"][0]["password"], SCRUBBED_PLACEHOLDER)
        self.assertEqual(result["items"][1]["name"], "ok")

        set_payload = {"items": frozenset({"a", "b"})}  # frozenset 不是 set，按原样返回
        result_fs = scrub(set_payload)
        self.assertEqual(result_fs["items"], frozenset({"a", "b"}))

    def test_scrub_returns_primitives_unchanged(self) -> None:
        for value in (None, 42, 3.14, "hello", b"bytes"):
            self.assertEqual(scrub(value), value)

    def test_custom_sensitive_fields_overrides_default(self) -> None:
        # 仅声明 'custom_secret' 为敏感，其它字段不再脱敏
        result = scrub(
            {"password": "should_remain", "custom_secret": "hide_me"},
            sensitive_fields={"custom_secret"},
        )
        self.assertEqual(result["password"], "should_remain")
        self.assertEqual(result["custom_secret"], SCRUBBED_PLACEHOLDER)

    def test_default_sensitive_fields_contains_required_keys(self) -> None:
        for required in ("password", "authorization", "cookie", "session_token"):
            self.assertIn(required, DEFAULT_SENSITIVE_FIELDS)


if __name__ == "__main__":
    unittest.main()
