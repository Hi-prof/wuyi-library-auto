import json
import re
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from wuyi_seat_bot.models import AppConfig, ConfigBundle

DEFAULT_LOGIN_URL = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
DEFAULT_SEAT_URLS = (DEFAULT_LOGIN_URL,)


EXAMPLE_CONFIG = {
    "default_account": "2023000001",
    "max_attempts": 2,
    "retry_wait_seconds": 2,
    "accounts": [
        {
            "name": "2023000001",
            "student_id": "2023000001",
            "password": "你的密码",
            "login_url": DEFAULT_LOGIN_URL,
            "state_file": "runtime/auth-2023000001.json",
            "seat_urls": list(DEFAULT_SEAT_URLS),
            "preferred_room_names": [],
            "preferred_seat_numbers": ["166", "168"],
        }
    ],
    "server_sync": {
        "base_url": None,
        "bearer_token": None,
        "verify_tls": True,
        "upload_enabled": False,
    },
}


def load_config(config_path: str | Path, account_name: str | None = None) -> AppConfig:
    bundle = load_config_bundle(config_path)
    return bundle.get_account(account_name)


def load_config_bundle(config_path: str | Path) -> ConfigBundle:
    payload = _load_json_payload(config_path)
    return _load_config_bundle_from_payload(payload)


def save_account_config(
    config_path: str | Path,
    *,
    original_name: str | None,
    account_name: str,
    student_id: str,
    password: str | None = None,
    login_url: str | None = None,
    seat_urls: list[str] | None = None,
    preferred_room_names: list[str] | None = None,
    preferred_seat_numbers: list[str] | None = None,
    state_file: str | None = None,
) -> ConfigBundle:
    path = Path(config_path)
    payload = _load_account_management_payload(path)
    accounts_payload = payload["accounts"]
    normalized_original_name = _optional_text(original_name)
    normalized_account_name = _require_text(account_name, "account_name")
    existing_index = next(
        (
            index
            for index, account_payload in enumerate(accounts_payload)
            if account_payload.get("name") == normalized_original_name
        ),
        -1,
    )
    existing_account_payload = (
        accounts_payload[existing_index] if existing_index >= 0 else None
    )
    normalized_student_id = _require_text(student_id, "student_id")
    existing_state_file = (
        _optional_text(existing_account_payload.get("state_file"))
        if existing_account_payload
        else None
    )
    next_state_file = _optional_text(state_file)
    if next_state_file is None:
        if existing_account_payload and _optional_text(
            existing_account_payload.get("state_file")
        ):
            next_state_file = str(existing_account_payload["state_file"]).strip()
        else:
            next_state_file = _build_default_state_file(normalized_student_id)

    next_password = _optional_text(password)
    if next_password is None and existing_account_payload:
        next_password = _optional_text(existing_account_payload.get("password"))
    if next_password is None:
        next_password = normalized_student_id

    next_login_url = _require_url(login_url or DEFAULT_LOGIN_URL, "login_url")
    next_account_payload = dict(existing_account_payload or {})
    next_account_payload.update(
        {
            "name": normalized_account_name,
            "student_id": normalized_student_id,
            "password": next_password,
            "login_url": next_login_url,
            "state_file": next_state_file,
            "seat_urls": list(
                _require_url_list(seat_urls or list(DEFAULT_SEAT_URLS), "seat_urls")
            ),
            "preferred_room_names": list(
                _optional_tuple_of_texts(preferred_room_names, "preferred_room_names"),
            ),
            "preferred_seat_numbers": list(
                _optional_tuple_of_texts(
                    preferred_seat_numbers, "preferred_seat_numbers"
                ),
            ),
        }
    )

    if existing_index >= 0:
        accounts_payload[existing_index] = next_account_payload
    else:
        accounts_payload.append(next_account_payload)

    if payload.get("default_account") == normalized_original_name:
        payload["default_account"] = normalized_account_name
    elif not _optional_text(payload.get("default_account")):
        payload["default_account"] = normalized_account_name

    bundle = _save_config_payload(path, payload)
    state_invalidation_payload = {"password": next_password}
    if _should_invalidate_saved_state(
        existing_account_payload=existing_account_payload,
        account_name=normalized_account_name,
        student_id=normalized_student_id,
        **state_invalidation_payload,
        login_url=next_login_url,
        state_file=next_state_file,
    ):
        _remove_saved_state_files(path, existing_state_file, next_state_file)
    return bundle


def delete_account_config(config_path: str | Path, account_name: str) -> ConfigBundle:
    path = Path(config_path)
    payload = _load_account_management_payload(path)
    normalized_account_name = _require_text(account_name, "account_name")
    accounts_payload = payload["accounts"]

    target_index = next(
        (
            index
            for index, account_payload in enumerate(accounts_payload)
            if account_payload.get("name") == normalized_account_name
        ),
        -1,
    )
    if target_index < 0:
        raise ValueError(f"未找到账号：{normalized_account_name}")

    accounts_payload.pop(target_index)
    if not accounts_payload:
        payload["default_account"] = ""
    elif payload.get("default_account") == normalized_account_name:
        payload["default_account"] = _require_text(
            accounts_payload[0].get("name"), "default_account"
        )
    return _save_config_payload(path, payload)


def set_default_account_config(
    config_path: str | Path, account_name: str
) -> ConfigBundle:
    path = Path(config_path)
    payload = _load_account_management_payload(path)
    normalized_account_name = _require_text(account_name, "account_name")
    if normalized_account_name not in {
        str(account_payload.get("name", "")).strip()
        for account_payload in payload["accounts"]
    }:
        raise ValueError(f"未找到账号：{normalized_account_name}")
    payload["default_account"] = normalized_account_name
    return _save_config_payload(path, payload)


def _load_config_bundle_from_payload(payload: dict[str, Any]) -> ConfigBundle:
    if payload.get("accounts") is None:
        legacy_account_name = _optional_text(payload.get("account_name")) or "默认账号"
        account = _build_account_config(
            account_payload=payload,
            root_payload=payload,
            account_name=legacy_account_name,
            default_state_file="runtime/auth.json",
        )
        return ConfigBundle(
            accounts=(account,), default_account_name=account.account_name
        )

    accounts_payload = payload.get("accounts")
    if not isinstance(accounts_payload, list):
        raise ValueError("accounts 必须是数组")
    if not accounts_payload:
        return ConfigBundle(accounts=(), default_account_name="")

    accounts: list[AppConfig] = []
    seen_names: set[str] = set()
    seen_state_files: set[str] = set()
    for index, account_payload in enumerate(accounts_payload):
        if not isinstance(account_payload, dict):
            raise ValueError(f"accounts[{index}] 必须是对象")

        account_name = _require_text(
            account_payload.get("name"), f"accounts[{index}].name"
        )
        if account_name in seen_names:
            raise ValueError(f"账号名称重复：{account_name}")
        seen_names.add(account_name)

        account = _build_account_config(
            account_payload=account_payload,
            root_payload=payload,
            account_name=account_name,
            default_state_file=_build_default_state_file(account_name),
        )
        if account.state_file in seen_state_files:
            raise ValueError(f"state_file 不能重复：{account.state_file}")
        seen_state_files.add(account.state_file)
        accounts.append(account)

    default_account_name = (
        _optional_text(payload.get("default_account")) or accounts[0].account_name
    )
    if default_account_name not in seen_names:
        raise ValueError(f"default_account 未匹配任何账号：{default_account_name}")

    return ConfigBundle(
        accounts=tuple(accounts), default_account_name=default_account_name
    )


def resolve_project_path(config_path: str | Path, target_path: str) -> Path:
    path = Path(target_path)
    if path.is_absolute():
        return path
    return Path(config_path).resolve().parent / path


def _should_invalidate_saved_state(
    *,
    existing_account_payload: dict[str, Any] | None,
    account_name: str,
    student_id: str,
    password: str,
    login_url: str,
    state_file: str,
) -> bool:
    if existing_account_payload is None:
        return False
    return any(
        (
            _optional_text(existing_account_payload.get("name")) != account_name,
            _optional_text(existing_account_payload.get("student_id")) != student_id,
            _optional_text(existing_account_payload.get("password")) != password,
            _optional_text(existing_account_payload.get("login_url")) != login_url,
            _optional_text(existing_account_payload.get("state_file")) != state_file,
        )
    )


def _remove_saved_state_files(
    config_path: str | Path, *state_files: str | None
) -> None:
    resolved_paths = {
        resolve_project_path(config_path, state_file)
        for state_file in state_files
        if state_file
    }
    for state_path in resolved_paths:
        state_path.unlink(missing_ok=True)


def write_example_config(target_path: str | Path, overwrite: bool = False) -> Path:
    path = Path(target_path)
    if path.exists() and not overwrite:
        raise FileExistsError(f"配置文件已存在：{path}")
    path.write_text(
        json.dumps(EXAMPLE_CONFIG, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return path


def _build_account_config(
    *,
    account_payload: dict,
    root_payload: dict,
    account_name: str,
    default_state_file: str,
) -> AppConfig:
    login_url = _require_url(
        _pick_value(account_payload, root_payload, "login_url", DEFAULT_LOGIN_URL),
        "login_url",
    )
    seat_urls = _require_url_list(
        _pick_value(
            account_payload, root_payload, "seat_urls", list(DEFAULT_SEAT_URLS)
        ),
        "seat_urls",
    )
    state_file = (
        _optional_text(_pick_value(account_payload, root_payload, "state_file"))
        or default_state_file
    )
    student_id = _optional_text(
        _pick_value(account_payload, root_payload, "student_id")
    )
    password = _optional_text(_pick_value(account_payload, root_payload, "password"))

    max_attempts = _require_positive_int(
        _pick_value(account_payload, root_payload, "max_attempts", 2),
        "max_attempts",
    )
    retry_wait_seconds = _require_non_negative_number(
        _pick_value(account_payload, root_payload, "retry_wait_seconds", 2),
        "retry_wait_seconds",
    )

    return AppConfig(
        login_url=login_url,
        state_file=state_file,
        seat_urls=seat_urls,
        account_name=account_name,
        student_id=student_id,
        password=password,
        preferred_room_names=_optional_tuple_of_texts(
            _pick_value(account_payload, root_payload, "preferred_room_names"),
            "preferred_room_names",
        ),
        preferred_seat_numbers=_optional_tuple_of_texts(
            _pick_value(account_payload, root_payload, "preferred_seat_numbers"),
            "preferred_seat_numbers",
        ),
        max_attempts=max_attempts,
        retry_wait_seconds=retry_wait_seconds,
    )


def _pick_value(
    account_payload: dict,
    root_payload: dict,
    field_name: str,
    default: object | None = None,
) -> object | None:
    if field_name in account_payload:
        return account_payload.get(field_name)
    if field_name in root_payload:
        return root_payload.get(field_name)
    return default


def _require_text(value: object, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field_name} 必须是非空字符串")
    return value.strip()


def _optional_text(value: object) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError("文本字段必须是字符串")
    text = value.strip()
    return text or None


def _require_url(value: object, field_name: str) -> str:
    text = _require_text(value, field_name)
    parsed = urlparse(text)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError(f"{field_name} 必须是有效的 http/https 地址")
    return text


def _require_url_list(value: object, field_name: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not value:
        raise ValueError(f"{field_name} 至少需要提供一个网址")
    return tuple(_require_url(item, field_name) for item in value)


def _require_positive_int(value: object, field_name: str) -> int:
    if not isinstance(value, int) or value <= 0:
        raise ValueError(f"{field_name} 必须是正整数")
    return value


def _require_non_negative_number(value: object, field_name: str) -> float:
    if not isinstance(value, (int, float)) or value < 0:
        raise ValueError(f"{field_name} 必须是非负数")
    return float(value)


def _tuple_of_texts(value: object, field_name: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not value:
        raise ValueError(f"{field_name} 至少需要提供一个文本")
    result: list[str] = []
    for item in value:
        result.append(_require_text(item, field_name))
    return tuple(result)


def _optional_tuple_of_texts(value: object, field_name: str) -> tuple[str, ...]:
    if value is None:
        return ()
    if value == []:
        return ()
    return _tuple_of_texts(value, field_name)


def _build_default_state_file(account_name: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "-", account_name.strip()).strip("-").lower()
    if not slug:
        slug = "account"
    return f"runtime/auth-{slug}.json"


def _load_json_payload(config_path: str | Path) -> dict[str, Any]:
    path = Path(config_path)
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("配置文件内容必须是 JSON 对象")
    return payload


def _load_account_management_payload(config_path: str | Path) -> dict[str, Any]:
    payload = dict(_load_json_payload(config_path))
    accounts_payload = payload.get("accounts")
    if accounts_payload is None:
        legacy_account_name = _optional_text(payload.get("account_name")) or "默认账号"
        payload["accounts"] = [
            {
                "name": legacy_account_name,
                "student_id": _optional_text(payload.get("student_id")),
                "password": _optional_text(payload.get("password")),
                "login_url": payload.get("login_url") or DEFAULT_LOGIN_URL,
                "state_file": _optional_text(payload.get("state_file"))
                or "runtime/auth.json",
                "seat_urls": list(payload.get("seat_urls") or DEFAULT_SEAT_URLS),
                "preferred_room_names": list(payload.get("preferred_room_names") or []),
                "preferred_seat_numbers": list(
                    payload.get("preferred_seat_numbers") or []
                ),
            }
        ]
        payload["default_account"] = (
            _optional_text(payload.get("default_account")) or legacy_account_name
        )
    else:
        payload["accounts"] = [
            dict(account_payload) for account_payload in accounts_payload
        ]
        payload["default_account"] = (
            _optional_text(payload.get("default_account")) or ""
        )
    payload.pop("account_name", None)
    return payload


def _save_config_payload(config_path: Path, payload: dict[str, Any]) -> ConfigBundle:
    bundle = _load_config_bundle_from_payload(payload)
    config_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return bundle
