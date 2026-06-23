"""账号密码 AES-GCM 加解密封装。

本模块给 ``account-pool-tri-sync`` 特性使用，对账号池中的账号密码做应用层
AES-GCM 对称加密落库，传输 / Web 编辑场景下解密后再返回明文。

设计要点：

- 密钥从 ``ACCOUNT_POOL_SECRET_KEY`` 环境变量（已经登记到 ``PreventAutoSettings``）
  读取，要求 32 字节明文 key 经过 base64 编码后填入；缺失或非法时直接
  ``raise ValueError`` 让进程启动失败，避免后续以脏状态运行。
- 加密结果拆分为三段（cipher / nonce / tag）持久化到 ``accounts.password_cipher``、
  ``accounts.password_nonce``、``accounts.password_tag`` 三列，对照 design 中
  ``Data Models`` 章节。
- 不在错误信息中夹带密文 / nonce / tag，避免间接泄露。
"""

from __future__ import annotations

import base64
import binascii
import secrets
from dataclasses import dataclass

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


_REQUIRED_KEY_HINT = "ACCOUNT_POOL_SECRET_KEY 必须配置 32 字节 base64"


@dataclass(frozen=True)
class EncryptedPassword:
    """AES-GCM 加密结果三元组。

    所有字段为原始字节串，调用方负责持久化时按对应列写入。
    """

    cipher: bytes
    nonce: bytes
    tag: bytes


class AccountPasswordCipher:
    """对账号密码进行 AES-GCM 加解密。"""

    KEY_SIZE = 32  # AES-256
    NONCE_SIZE = 12  # AES-GCM 推荐 96 bit nonce
    TAG_SIZE = 16  # AES-GCM 标准 128 bit tag

    def __init__(self, secret_key: str | None) -> None:
        self._aes_gcm = AESGCM(self._decode_key(secret_key))

    @classmethod
    def _decode_key(cls, secret_key: str | None) -> bytes:
        if secret_key is None or not secret_key.strip():
            raise ValueError(_REQUIRED_KEY_HINT)
        try:
            key_bytes = base64.b64decode(secret_key.strip(), validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ValueError(_REQUIRED_KEY_HINT) from exc
        if len(key_bytes) != cls.KEY_SIZE:
            raise ValueError(_REQUIRED_KEY_HINT)
        return key_bytes

    def encrypt(self, plaintext: str) -> EncryptedPassword:
        """加密明文密码，返回 ``(cipher, nonce, tag)`` 三元组。"""

        if not isinstance(plaintext, str):
            raise TypeError("plaintext 必须是 str")
        nonce = secrets.token_bytes(self.NONCE_SIZE)
        ciphertext_with_tag = self._aes_gcm.encrypt(
            nonce, plaintext.encode("utf-8"), associated_data=None
        )
        cipher = ciphertext_with_tag[: -self.TAG_SIZE]
        tag = ciphertext_with_tag[-self.TAG_SIZE :]
        return EncryptedPassword(cipher=cipher, nonce=nonce, tag=tag)

    def decrypt(self, encrypted: EncryptedPassword) -> str:
        """解密 :class:`EncryptedPassword` 三元组，返回明文密码。"""

        if len(encrypted.nonce) != self.NONCE_SIZE:
            raise ValueError("nonce 长度非法")
        if len(encrypted.tag) != self.TAG_SIZE:
            raise ValueError("tag 长度非法")
        ciphertext_with_tag = encrypted.cipher + encrypted.tag
        try:
            plaintext = self._aes_gcm.decrypt(
                encrypted.nonce, ciphertext_with_tag, associated_data=None
            )
        except InvalidTag as exc:
            raise ValueError("AES-GCM 校验失败，密文 / nonce / tag 不匹配") from exc
        return plaintext.decode("utf-8")


def generate_secret_key() -> str:
    """生成一份新的 base64 编码 32 字节密钥，便于运维 / 测试一次性签发。"""

    return base64.b64encode(secrets.token_bytes(AccountPasswordCipher.KEY_SIZE)).decode(
        "ascii"
    )


__all__ = [
    "AccountPasswordCipher",
    "EncryptedPassword",
    "generate_secret_key",
]
