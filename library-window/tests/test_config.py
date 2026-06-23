import json
import tempfile
import unittest
from pathlib import Path

from wuyi_seat_bot.config import (
    DEFAULT_LOGIN_URL,
    delete_account_config,
    load_config,
    load_config_bundle,
    save_account_config,
    set_default_account_config,
)


def write_config(tmp_path: Path, payload: dict) -> Path:
    config_path = tmp_path / "config.json"
    config_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return config_path


class LoadConfigTestCase(unittest.TestCase):
    def test_load_config_returns_validated_legacy_config(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "login_url": "https://example.com/login",
                    "state_file": "runtime/auth.json",
                    "seat_urls": [
                        "https://example.com/seat/166",
                        "https://example.com/seat/168",
                    ],
                    "preferred_seat_numbers": ["166", "168"],
                    "max_attempts": 3,
                    "retry_wait_seconds": 1,
                },
            )

            config = load_config(config_path)

        self.assertEqual(config.account_name, "默认账号")
        self.assertEqual(config.login_url, "https://example.com/login")
        self.assertEqual(config.state_file, "runtime/auth.json")
        self.assertEqual(
            config.seat_urls,
            ("https://example.com/seat/166", "https://example.com/seat/168"),
        )
        self.assertEqual(config.preferred_seat_numbers, ("166", "168"))
        self.assertEqual(config.max_attempts, 3)

    def test_load_config_reads_preferred_room_names(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "login_url": "https://example.com/login",
                    "state_file": "runtime/auth.json",
                    "seat_urls": ["https://example.com/seat/166"],
                    "preferred_room_names": ["自习室圆形二楼", "综合阅览室"],
                },
            )

            config = load_config(config_path)

        self.assertEqual(config.preferred_room_names, ("自习室圆形二楼", "综合阅览室"))

    def test_load_config_allows_empty_preferred_seat_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "login_url": "https://example.com/login",
                    "state_file": "runtime/auth.json",
                    "seat_urls": ["https://example.com/seat/166"],
                    "preferred_seat_numbers": [],
                },
            )

            config = load_config(config_path)

        self.assertEqual(config.preferred_seat_numbers, ())

    def test_load_config_bundle_supports_multi_account(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "default_account": "主号",
                    "max_attempts": 2,
                    "retry_wait_seconds": 1,
                    "accounts": [
                        {
                            "name": "主号",
                            "login_url": "https://example.com/login-a",
                            "state_file": "runtime/auth-a.json",
                            "seat_urls": ["https://example.com/seat/a"],
                            "preferred_seat_numbers": ["8"],
                        },
                        {
                            "name": "室友",
                            "login_url": "https://example.com/login-b",
                            "state_file": "runtime/auth-b.json",
                            "seat_urls": ["https://example.com/seat/b"],
                        },
                    ],
                },
            )

            bundle = load_config_bundle(config_path)
            roommate = load_config(config_path, account_name="室友")

        self.assertEqual(bundle.default_account_name, "主号")
        self.assertEqual(
            tuple(account.account_name for account in bundle.accounts), ("主号", "室友")
        )
        self.assertEqual(roommate.account_name, "室友")
        self.assertEqual(roommate.state_file, "runtime/auth-b.json")
        self.assertEqual(roommate.seat_urls, ("https://example.com/seat/b",))

    def test_load_config_bundle_allows_empty_accounts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "default_account": "",
                    "accounts": [],
                },
            )

            bundle = load_config_bundle(config_path)

        self.assertEqual(bundle.accounts, ())
        self.assertEqual(bundle.default_account_name, "")

    def test_load_config_bundle_rejects_duplicate_state_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "login_url": "https://example.com/login",
                    "seat_urls": ["https://example.com/seat/a"],
                    "accounts": [
                        {"name": "主号", "state_file": "runtime/auth.json"},
                        {"name": "室友", "state_file": "runtime/auth.json"},
                    ],
                },
            )

            with self.assertRaisesRegex(ValueError, "state_file 不能重复"):
                load_config_bundle(config_path)

    def test_load_config_rejects_invalid_login_url(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "login_url": "not-a-url",
                    "state_file": "runtime/auth.json",
                    "seat_urls": ["https://example.com/seat/166"],
                },
            )

            with self.assertRaisesRegex(ValueError, "login_url"):
                load_config(config_path)

    def test_load_config_requires_seat_urls(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "login_url": "https://example.com/login",
                    "state_file": "runtime/auth.json",
                    "seat_urls": [],
                },
            )

            with self.assertRaisesRegex(ValueError, "seat_urls"):
                load_config(config_path)

    def test_save_account_config_converts_legacy_payload_to_accounts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "login_url": "https://example.com/login",
                    "state_file": "runtime/auth.json",
                    "seat_urls": ["https://example.com/seat/166"],
                    "preferred_seat_numbers": ["166"],
                    "max_attempts": 3,
                },
            )

            bundle = save_account_config(
                config_path,
                original_name="默认账号",
                account_name="20231121130",
                student_id="20231121130",
                password="",
            )
            payload = json.loads(config_path.read_text(encoding="utf-8"))

        self.assertEqual(bundle.default_account_name, "20231121130")
        self.assertEqual(
            tuple(account.account_name for account in bundle.accounts), ("20231121130",)
        )
        self.assertNotIn("account_name", payload)
        self.assertEqual(payload["default_account"], "20231121130")
        self.assertEqual(payload["max_attempts"], 3)
        self.assertEqual(
            payload["accounts"],
            [
                {
                    "name": "20231121130",
                    "student_id": "20231121130",
                    "password": "20231121130",
                    "login_url": DEFAULT_LOGIN_URL,
                    "state_file": "runtime/auth.json",
                    "seat_urls": [DEFAULT_LOGIN_URL],
                    "preferred_room_names": [],
                    "preferred_seat_numbers": [],
                }
            ],
        )

    def test_delete_account_config_reassigns_default_account(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "default_account": "主号",
                    "accounts": [
                        {
                            "name": "主号",
                            "login_url": "https://example.com/login-a",
                            "state_file": "runtime/auth-a.json",
                            "seat_urls": ["https://example.com/seat/a"],
                        },
                        {
                            "name": "室友",
                            "login_url": "https://example.com/login-b",
                            "state_file": "runtime/auth-b.json",
                            "seat_urls": ["https://example.com/seat/b"],
                        },
                    ],
                },
            )

            bundle = delete_account_config(config_path, "主号")
            payload = json.loads(config_path.read_text(encoding="utf-8"))

        self.assertEqual(bundle.default_account_name, "室友")
        self.assertEqual(
            tuple(account.account_name for account in bundle.accounts), ("室友",)
        )
        self.assertEqual(payload["default_account"], "室友")

    def test_delete_account_config_allows_removing_last_account(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "default_account": "主号",
                    "accounts": [
                        {
                            "name": "主号",
                            "student_id": "20231121130",
                            "login_url": "https://example.com/login-a",
                            "state_file": "runtime/auth-a.json",
                            "seat_urls": ["https://example.com/seat/a"],
                        }
                    ],
                },
            )

            bundle = delete_account_config(config_path, "主号")
            payload = json.loads(config_path.read_text(encoding="utf-8"))

        self.assertEqual(bundle.accounts, ())
        self.assertEqual(bundle.default_account_name, "")
        self.assertEqual(payload["accounts"], [])
        self.assertEqual(payload["default_account"], "")

    def test_set_default_account_config_updates_default_account(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = write_config(
                Path(tmp_dir),
                {
                    "default_account": "主号",
                    "accounts": [
                        {
                            "name": "主号",
                            "login_url": "https://example.com/login-a",
                            "state_file": "runtime/auth-a.json",
                            "seat_urls": ["https://example.com/seat/a"],
                        },
                        {
                            "name": "室友",
                            "login_url": "https://example.com/login-b",
                            "state_file": "runtime/auth-b.json",
                            "seat_urls": ["https://example.com/seat/b"],
                        },
                    ],
                },
            )

            bundle = set_default_account_config(config_path, "室友")

        self.assertEqual(bundle.default_account_name, "室友")

    def test_save_account_config_removes_saved_state_when_credentials_change(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            config_path = write_config(
                tmp_path,
                {
                    "default_account": "主号",
                    "accounts": [
                        {
                            "name": "主号",
                            "student_id": "20231121130",
                            "password": "old-password",
                            "login_url": "https://example.com/login-a",
                            "state_file": "runtime/auth-a.json",
                            "seat_urls": ["https://example.com/seat/a"],
                        }
                    ],
                },
            )
            state_path = tmp_path / "runtime" / "auth-a.json"
            state_path.parent.mkdir(parents=True, exist_ok=True)
            state_path.write_text('{"cookies": []}', encoding="utf-8")
            payload = {"password": "new-password"}

            save_account_config(
                config_path,
                original_name="主号",
                account_name="主号",
                student_id="20231121130",
                **payload,
            )

            self.assertFalse(state_path.exists())


class ExampleConfigServerSyncTestCase(unittest.TestCase):
    """验证 config.json 模板包含 server_sync 默认字段。"""

    def test_example_config_contains_server_sync_defaults(self) -> None:
        from wuyi_seat_bot.config import EXAMPLE_CONFIG

        self.assertIn("server_sync", EXAMPLE_CONFIG)
        self.assertEqual(
            EXAMPLE_CONFIG["server_sync"],
            {
                "base_url": None,
                "bearer_token": None,
                "verify_tls": True,
                "upload_enabled": False,
            },
        )

    def test_write_example_config_emits_server_sync_section(self) -> None:
        from wuyi_seat_bot.config import write_example_config

        with tempfile.TemporaryDirectory() as tmp_dir:
            target = Path(tmp_dir) / "config.json"
            write_example_config(target)
            payload = json.loads(target.read_text(encoding="utf-8"))

        self.assertIn("server_sync", payload)
        self.assertEqual(payload["server_sync"]["base_url"], None)
        self.assertEqual(payload["server_sync"]["bearer_token"], None)
        self.assertEqual(payload["server_sync"]["verify_tls"], True)
        self.assertEqual(payload["server_sync"]["upload_enabled"], False)
