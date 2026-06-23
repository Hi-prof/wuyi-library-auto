from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from prevent_auto.services.account_password_cipher import generate_secret_key
from prevent_auto.settings import (
    DEFAULT_ACCOUNT_POOL_AUDIT_RETENTION_DAYS,
    DEFAULT_ACCOUNT_POOL_DETAIL_RATE_LIMIT_PER_MINUTE,
    DEFAULT_ACCOUNT_POOL_HTTPS_REQUIRED,
    DEFAULT_ACCOUNT_POOL_REAPER_INTERVAL_SECONDS,
    DEFAULT_ACCOUNT_POOL_TOKEN_PEPPER,
    load_settings,
)


class AccountPoolSettingsTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())

    def _base_env(self) -> dict[str, str]:
        return {
            "PREVENT_AUTO_HOST": "127.0.0.1",
            "PREVENT_AUTO_AUTH_PASSWORD": "test-password",
            "PREVENT_AUTO_SESSION_SECRET": "test-session-secret-32-bytes-long",
        }

    def test_defaults_when_env_absent(self) -> None:
        env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("PREVENT_AUTO_")
            and not key.startswith("ACCOUNT_POOL_")
        }
        env.update(self._base_env())
        with mock.patch.dict(os.environ, env, clear=True):
            settings = load_settings(base_dir=self.temp_dir)

        # 环境变量缺失时由 settings 自动生成并落地到 runtime/account_pool_secret.key，
        # 保证 web 端默认即可访问号池服务，无需运维手动注入。
        self.assertTrue(settings.account_pool_secret_key)
        self.assertTrue(
            (self.temp_dir / "runtime" / "account_pool_secret.key").exists()
        )
        self.assertEqual(
            settings.account_pool_token_pepper, DEFAULT_ACCOUNT_POOL_TOKEN_PEPPER
        )
        self.assertEqual(
            settings.account_pool_reaper_interval_seconds,
            DEFAULT_ACCOUNT_POOL_REAPER_INTERVAL_SECONDS,
        )
        self.assertEqual(
            settings.account_pool_detail_rate_limit_per_minute,
            DEFAULT_ACCOUNT_POOL_DETAIL_RATE_LIMIT_PER_MINUTE,
        )
        self.assertEqual(
            settings.account_pool_audit_retention_days,
            DEFAULT_ACCOUNT_POOL_AUDIT_RETENTION_DAYS,
        )
        self.assertEqual(
            settings.account_pool_https_required, DEFAULT_ACCOUNT_POOL_HTTPS_REQUIRED
        )

    def test_env_overrides(self) -> None:
        env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("PREVENT_AUTO_")
            and not key.startswith("ACCOUNT_POOL_")
        }
        env.update(self._base_env())
        secret_key = generate_secret_key()
        env.update(
            {
                "ACCOUNT_POOL_SECRET_KEY": secret_key,
                "ACCOUNT_POOL_TOKEN_PEPPER": "pepper-v1",
                "ACCOUNT_POOL_REAPER_INTERVAL_SECONDS": "120",
                "ACCOUNT_POOL_DETAIL_RATE_LIMIT_PER_MINUTE": "12",
                "ACCOUNT_POOL_AUDIT_RETENTION_DAYS": "30",
                "ACCOUNT_POOL_HTTPS_REQUIRED": "false",
            }
        )
        with mock.patch.dict(os.environ, env, clear=True):
            settings = load_settings(base_dir=self.temp_dir)

        self.assertEqual(settings.account_pool_secret_key, secret_key)
        self.assertEqual(settings.account_pool_token_pepper, "pepper-v1")
        self.assertEqual(settings.account_pool_reaper_interval_seconds, 120)
        self.assertEqual(settings.account_pool_detail_rate_limit_per_minute, 12)
        self.assertEqual(settings.account_pool_audit_retention_days, 30)
        self.assertFalse(settings.account_pool_https_required)

    def test_invalid_https_required_raises(self) -> None:
        env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("PREVENT_AUTO_")
            and not key.startswith("ACCOUNT_POOL_")
        }
        env.update(self._base_env())
        env["ACCOUNT_POOL_HTTPS_REQUIRED"] = "maybe"
        with mock.patch.dict(os.environ, env, clear=True):
            with self.assertRaises(ValueError):
                load_settings(base_dir=self.temp_dir)

    def test_non_positive_reaper_interval_raises(self) -> None:
        env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("PREVENT_AUTO_")
            and not key.startswith("ACCOUNT_POOL_")
        }
        env.update(self._base_env())
        env["ACCOUNT_POOL_REAPER_INTERVAL_SECONDS"] = "0"
        with mock.patch.dict(os.environ, env, clear=True):
            with self.assertRaises(ValueError):
                load_settings(base_dir=self.temp_dir)


if __name__ == "__main__":
    unittest.main()
