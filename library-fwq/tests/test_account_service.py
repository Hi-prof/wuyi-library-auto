from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from prevent_auto.database import initialize_database
from prevent_auto.services.account_service import AccountService


class AccountServiceTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.database_path = self.temp_dir / "prevent_auto.db"
        initialize_database(self.database_path)
        self.service = AccountService(self.database_path)

    def test_create_and_list_accounts(self) -> None:
        account = self.service.create_account(
            name="主号",
            student_id="20231121130",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )

        accounts = self.service.list_accounts()

        self.assertEqual(len(accounts), 1)
        self.assertEqual(accounts[0].name, "主号")
        self.assertTrue(account.enabled)
        self.assertFalse(account.rebook_enabled)
        self.assertEqual(account.rebook_trigger_minutes, 5)

    def test_set_enabled_updates_account_status(self) -> None:
        account = self.service.create_account(
            name="室友",
            student_id="20231121151",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )

        self.service.set_enabled(account.id, enabled=False)

        updated = self.service.get_account(account.id)
        self.assertFalse(updated.enabled)

    def test_update_account_status_persists_in_database(self) -> None:
        account = self.service.create_account(
            name="状态号",
            student_id="20231121152",
            password="secret",
            login_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            seat_url="https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            enabled=True,
        )

        self.service.update_account_status(account.id, account_status="blacklisted")

        reloaded_service = AccountService(self.database_path)
        reloaded_account = reloaded_service.get_account(account.id)
        self.assertEqual(reloaded_account.account_status, "blacklisted")
