from __future__ import annotations

import json
from pathlib import Path


def build_entry_url_cache_path(config_path: str | Path) -> Path:
    config_file = Path(config_path).resolve()
    return config_file.parent / "runtime" / "resolved-seat-urls.json"


def load_resolved_entry_urls(config_path: str | Path, account_name: str) -> dict[str, str]:
    cache_path = build_entry_url_cache_path(config_path)
    if not cache_path.exists():
        return {}

    payload = json.loads(cache_path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        return {}

    accounts_payload = payload.get("accounts")
    if not isinstance(accounts_payload, dict):
        return {}

    account_payload = accounts_payload.get(account_name)
    if not isinstance(account_payload, dict):
        return {}

    result: dict[str, str] = {}
    for entry_url, search_api_url in account_payload.items():
        if isinstance(entry_url, str) and entry_url.strip() and isinstance(search_api_url, str) and search_api_url.strip():
            result[entry_url.strip()] = search_api_url.strip()
    return result


def save_resolved_entry_urls(config_path: str | Path, account_name: str, resolved_urls: dict[str, str]) -> None:
    cache_path = build_entry_url_cache_path(config_path)
    cache_path.parent.mkdir(parents=True, exist_ok=True)

    payload: dict[str, object]
    if cache_path.exists():
        try:
            existing_payload = json.loads(cache_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            existing_payload = {}
        payload = existing_payload if isinstance(existing_payload, dict) else {}
    else:
        payload = {}

    accounts_payload = payload.get("accounts")
    if not isinstance(accounts_payload, dict):
        accounts_payload = {}
        payload["accounts"] = accounts_payload

    normalized_mapping = {
        entry_url.strip(): search_api_url.strip()
        for entry_url, search_api_url in resolved_urls.items()
        if entry_url.strip() and search_api_url.strip()
    }
    accounts_payload[account_name] = normalized_mapping
    cache_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def save_resolved_entry_url(config_path: str | Path, account_name: str, entry_url: str, search_api_url: str) -> None:
    existing_mapping = load_resolved_entry_urls(config_path, account_name)
    existing_mapping[entry_url.strip()] = search_api_url.strip()
    save_resolved_entry_urls(config_path, account_name, existing_mapping)
