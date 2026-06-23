from __future__ import annotations

from datetime import datetime

from wuyi_seat_bot.web_errors import ApiRequestError


def read_int_field(value: object, field_name: str) -> int:
    if isinstance(value, bool):
        raise ApiRequestError(f"{field_name} 必须是整数")
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip():
        try:
            return int(value.strip())
        except ValueError as exc:
            raise ApiRequestError(f"{field_name} 必须是整数") from exc
    raise ApiRequestError(f"{field_name} 必须是整数")


def read_optional_int_field(value: object, field_name: str, *, default: int) -> int:
    if value is None or (isinstance(value, str) and not value.strip()):
        return default
    return read_int_field(value, field_name)


def read_bool_field(value: object, field_name: str) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1"}:
            return True
        if normalized in {"false", "0"}:
            return False
    raise ApiRequestError(f"{field_name} 必须是布尔值")


def read_required_text_field(value: object, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ApiRequestError(f"{field_name} 不能为空")
    return value.strip()


def read_optional_text_field(value: object) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ApiRequestError("文本字段必须是字符串")
    text = value.strip()
    return text or None


def read_text_list_field(
    value: object, field_name: str, *, allow_empty: bool = False
) -> list[str]:
    if value is None:
        return [] if allow_empty else _raise_text_list_error(field_name)
    if not isinstance(value, list):
        raise ApiRequestError(f"{field_name} 必须是字符串数组")

    items: list[str] = []
    for item in value:
        if not isinstance(item, str):
            raise ApiRequestError(f"{field_name} 必须是字符串数组")
        text = item.strip()
        if not text:
            continue
        items.append(text)

    if not items and not allow_empty:
        _raise_text_list_error(field_name)
    return items


def _raise_text_list_error(field_name: str) -> list[str]:
    raise ApiRequestError(f"{field_name} 至少需要填写一项")


def read_time_field(value: object, field_name: str) -> str:
    text = read_required_text_field(value, field_name)
    try:
        parsed = datetime.strptime(text, "%H:%M")
    except ValueError as exc:
        raise ApiRequestError(f"{field_name} 必须是 HH:MM 格式") from exc
    return parsed.strftime("%H:%M")
