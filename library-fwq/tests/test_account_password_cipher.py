from __future__ import annotations

import base64
import unittest

from prevent_auto.services.account_password_cipher import (
    AccountPasswordCipher,
    EncryptedPassword,
    generate_secret_key,
)


class AccountPasswordCipherTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.key = generate_secret_key()
        self.cipher = AccountPasswordCipher(self.key)

    def test_round_trip_ascii(self) -> None:
        encrypted = self.cipher.encrypt("Pa$$w0rd")
        self.assertEqual(self.cipher.decrypt(encrypted), "Pa$$w0rd")

    def test_round_trip_unicode_with_newline_and_emoji(self) -> None:
        plaintext = "中文密码🔥\n含换行 abc"
        encrypted = self.cipher.encrypt(plaintext)
        self.assertEqual(self.cipher.decrypt(encrypted), plaintext)

    def test_round_trip_empty_string(self) -> None:
        encrypted = self.cipher.encrypt("")
        self.assertEqual(self.cipher.decrypt(encrypted), "")
        self.assertEqual(len(encrypted.nonce), AccountPasswordCipher.NONCE_SIZE)
        self.assertEqual(len(encrypted.tag), AccountPasswordCipher.TAG_SIZE)

    def test_each_encrypt_uses_new_nonce(self) -> None:
        first = self.cipher.encrypt("same-input")
        second = self.cipher.encrypt("same-input")
        self.assertNotEqual(first.nonce, second.nonce)
        self.assertNotEqual(first.cipher, second.cipher)

    def test_ciphertext_does_not_contain_plaintext_substring(self) -> None:
        plaintext = "very_secret_password_123"
        encrypted = self.cipher.encrypt(plaintext)
        self.assertNotIn(plaintext.encode("utf-8"), encrypted.cipher)
        self.assertNotIn(plaintext.encode("utf-8"), encrypted.nonce)
        self.assertNotIn(plaintext.encode("utf-8"), encrypted.tag)

    def test_missing_key_raises_value_error(self) -> None:
        with self.assertRaises(ValueError) as ctx:
            AccountPasswordCipher(None)
        self.assertIn("ACCOUNT_POOL_SECRET_KEY 必须配置 32 字节 base64", str(ctx.exception))

    def test_blank_key_raises_value_error(self) -> None:
        for blank in ("", "   ", "\t\n"):
            with self.subTest(blank=blank):
                with self.assertRaises(ValueError):
                    AccountPasswordCipher(blank)

    def test_non_base64_key_raises_value_error(self) -> None:
        with self.assertRaises(ValueError):
            AccountPasswordCipher("not!base64!!")

    def test_wrong_length_key_raises_value_error(self) -> None:
        too_short = base64.b64encode(b"x" * 16).decode("ascii")
        too_long = base64.b64encode(b"x" * 64).decode("ascii")
        for key in (too_short, too_long):
            with self.subTest(key=key):
                with self.assertRaises(ValueError):
                    AccountPasswordCipher(key)

    def test_tampered_ciphertext_rejected(self) -> None:
        encrypted = self.cipher.encrypt("hello")
        tampered = EncryptedPassword(
            cipher=b"\x00" * len(encrypted.cipher),
            nonce=encrypted.nonce,
            tag=encrypted.tag,
        )
        with self.assertRaises(ValueError) as ctx:
            self.cipher.decrypt(tampered)
        self.assertIn("AES-GCM 校验失败", str(ctx.exception))

    def test_wrong_key_cannot_decrypt(self) -> None:
        encrypted = self.cipher.encrypt("hello")
        other_cipher = AccountPasswordCipher(generate_secret_key())
        with self.assertRaises(ValueError):
            other_cipher.decrypt(encrypted)

    def test_invalid_nonce_length_raises(self) -> None:
        encrypted = self.cipher.encrypt("hello")
        bad = EncryptedPassword(cipher=encrypted.cipher, nonce=b"\x00" * 8, tag=encrypted.tag)
        with self.assertRaises(ValueError):
            self.cipher.decrypt(bad)

    def test_invalid_tag_length_raises(self) -> None:
        encrypted = self.cipher.encrypt("hello")
        bad = EncryptedPassword(cipher=encrypted.cipher, nonce=encrypted.nonce, tag=b"\x00" * 8)
        with self.assertRaises(ValueError):
            self.cipher.decrypt(bad)

    def test_encrypt_rejects_non_string(self) -> None:
        with self.assertRaises(TypeError):
            self.cipher.encrypt(b"bytes-not-allowed")  # type: ignore[arg-type]

    def test_generate_secret_key_decodes_to_32_bytes(self) -> None:
        for _ in range(5):
            key = generate_secret_key()
            self.assertEqual(len(base64.b64decode(key)), AccountPasswordCipher.KEY_SIZE)


if __name__ == "__main__":
    unittest.main()
