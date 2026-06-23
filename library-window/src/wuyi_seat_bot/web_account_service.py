from __future__ import annotations

import re
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from http import HTTPStatus
from pathlib import Path
from typing import Any

from wuyi_seat_bot.config import (
    delete_account_config,
    resolve_project_path,
    save_account_config,
    set_default_account_config,
)
from wuyi_seat_bot.models import AppConfig, ConfigBundle
from wuyi_seat_bot.web_errors import ApiRequestError
from wuyi_seat_bot.web_payload import (
    read_optional_text_field,
    read_required_text_field,
    read_text_list_field,
)


ACTIVE_TASK_STATUSES = {"pending", "running"}
MAX_BULK_IMPORT_BYTES = 200 * 1024
MAX_BULK_IMPORT_ACCOUNTS = 200
MAX_BULK_IMPORT_STUDENT_ID_LENGTH = 32
STUDENT_ID_PATTERN = re.compile(r"^[A-Za-z0-9_]+$")
BULK_IMPORT_FORMAT_PATTERNS = (
    re.compile(r"^([^:]+):(.*)$"),
    re.compile(r"^([^,]+),(.*)$"),
    re.compile(r"^(\S+)\s+(.+)$"),
    re.compile(r"^(\S+)$"),
)


@dataclass(frozen=True)
class AccountMutationResult:
    bundle: ConfigBundle
    selected_account_name: str
    message: str


@dataclass(frozen=True)
class AccountBulkImportEntry:
    line_number: int
    student_id: str
    password: str


@dataclass(frozen=True)
class AccountBulkImportFailure:
    line_number: int
    student_id: str
    reason: str


@dataclass(frozen=True)
class AccountBulkImportParseResult:
    accepted: tuple[AccountBulkImportEntry, ...]
    invalid: tuple[AccountBulkImportFailure, ...]
    duplicates: tuple[AccountBulkImportFailure, ...]


@dataclass(frozen=True)
class AccountBulkImportMutationResult:
    bundle: ConfigBundle
    selected_account_name: str
    message: str
    import_result: AccountBulkImportParseResult


def build_account_options(
    config_bundle: ConfigBundle, selected_account_name: str
) -> list[dict[str, str]]:
    return [
        {
            "value": account.account_name,
            "label": build_account_display_label(account)
            + (
                "（默认）"
                if account.account_name == config_bundle.default_account_name
                else ""
            ),
            "selected": "true"
            if account.account_name == selected_account_name
            else "false",
        }
        for account in config_bundle.accounts
    ]


def serialize_account_profile(
    *,
    account: AppConfig,
    config_bundle: ConfigBundle,
    config_path: str | Path,
    selected_account_name: str,
) -> dict[str, Any]:
    state_path = resolve_project_path(config_path, account.state_file)
    return {
        "name": account.account_name,
        "studentId": account.student_id or account.account_name,
        "passwordConfigured": bool(account.password),
        "loginUrl": account.login_url,
        "stateFile": account.state_file,
        "seatUrls": list(account.seat_urls),
        "preferredSeatNumbers": list(account.preferred_seat_numbers),
        "seatUrlCount": len(account.seat_urls),
        "preferredSeatCount": len(account.preferred_seat_numbers),
        "loginStateReady": state_path.exists(),
        "loginStatePath": str(state_path),
        "isDefault": account.account_name == config_bundle.default_account_name,
        "isSelected": account.account_name == selected_account_name,
    }


def build_seat_url_options(seat_urls: tuple[str, ...]) -> list[dict[str, str]]:
    return [
        {
            "value": seat_url,
            "label": f"入口 {index + 1}" + ("（默认）" if index == 0 else ""),
        }
        for index, seat_url in enumerate(seat_urls)
    ]


def read_account_name(
    payload: dict[str, Any] | None,
    allowed_account_names: tuple[str, ...],
    default_account_name: str,
) -> str:
    if not allowed_account_names:
        raise ApiRequestError("当前还没有账号，请先到账号管理里新建账号")
    if payload is None:
        return default_account_name
    if not isinstance(payload, dict):
        raise ApiRequestError("请求体必须是 JSON 对象")

    account_name = str(payload.get("accountName", "")).strip() or default_account_name
    if account_name not in allowed_account_names:
        raise ApiRequestError(f"accountName 不在配置允许范围内：{account_name}")
    return account_name


def build_accounts_response(
    *,
    config_path: str | Path,
    config_bundle: ConfigBundle,
    account_names: tuple[str, ...],
    default_account_name: str,
    selected_account_name: str | None = None,
) -> dict[str, Any]:
    target_account_name = selected_account_name or default_account_name
    if not account_names:
        target_account_name = ""
    elif target_account_name not in account_names:
        target_account_name = default_account_name
    return {
        "configPath": str(config_path),
        "defaultAccountName": config_bundle.default_account_name,
        "selectedAccountName": target_account_name,
        "accounts": [
            serialize_account_profile(
                account=account,
                config_bundle=config_bundle,
                config_path=config_path,
                selected_account_name=target_account_name,
            )
            for account in config_bundle.accounts
        ],
    }


def save_account_bundle_from_payload(
    config_path: str | Path, payload: dict[str, Any]
) -> tuple[ConfigBundle, str]:
    _require_payload_object(payload)
    original_name = read_optional_text_field(payload.get("originalName"))
    student_id = read_required_text_field(payload.get("studentId"), "studentId")
    password = read_optional_text_field(payload.get("password"))
    preferred_seat_numbers = read_text_list_field(
        payload.get("preferredSeatNumbers"),
        "preferredSeatNumbers",
        allow_empty=True,
    )

    try:
        bundle = save_account_config(
            config_path,
            original_name=original_name,
            account_name=student_id,
            student_id=student_id,
            password=password,
            preferred_seat_numbers=preferred_seat_numbers,
        )
    except ValueError as exc:
        raise ApiRequestError(str(exc)) from exc
    return bundle, student_id


def import_accounts_mutation(
    config_path: str | Path,
    payload: dict[str, Any],
    config_bundle: ConfigBundle,
) -> AccountBulkImportMutationResult:
    _require_payload_object(payload)
    raw_text_value = payload.get("rawText")
    if not isinstance(raw_text_value, str) or not raw_text_value.strip():
        raise ApiRequestError("rawText 不能为空")
    existing_account_ids = _build_existing_account_ids(config_bundle)
    import_result = parse_account_bulk_import(raw_text_value, existing_account_ids)

    bundle = config_bundle
    for account in import_result.accepted:
        try:
            bundle = save_account_config(
                config_path,
                original_name=None,
                account_name=account.student_id,
                student_id=account.student_id,
                password=account.password,
                preferred_seat_numbers=[],
            )
        except ValueError as exc:
            raise ApiRequestError(str(exc)) from exc

    selected_account_name = (
        import_result.accepted[0].student_id
        if import_result.accepted
        else bundle.default_account_name
    )
    skipped_count = len(import_result.invalid) + len(import_result.duplicates)
    message = f"已导入 {len(import_result.accepted)} 个账号"
    if skipped_count:
        message += f"，跳过 {skipped_count} 行"
    return AccountBulkImportMutationResult(
        bundle=bundle,
        selected_account_name=selected_account_name,
        message=message,
        import_result=import_result,
    )


def parse_account_bulk_import(
    raw_text: str,
    existing_account_ids: set[str],
) -> AccountBulkImportParseResult:
    if len(raw_text.encode("utf-8")) > MAX_BULK_IMPORT_BYTES:
        raise ApiRequestError("导入内容不能超过 200KB")

    accepted: list[AccountBulkImportEntry] = []
    invalid: list[AccountBulkImportFailure] = []
    duplicates: list[AccountBulkImportFailure] = []
    current_batch: set[str] = set()

    for line_number, raw_line in enumerate(raw_text.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue

        student_id, password = _parse_bulk_import_line(line)
        validation_error = _validate_bulk_import_student_id(student_id)
        if validation_error is not None:
            invalid.append(
                AccountBulkImportFailure(line_number, student_id, validation_error)
            )
            continue
        if student_id in existing_account_ids:
            duplicates.append(
                AccountBulkImportFailure(line_number, student_id, "账号已存在")
            )
            continue
        if student_id in current_batch:
            duplicates.append(
                AccountBulkImportFailure(line_number, student_id, "本次导入重复")
            )
            continue

        current_batch.add(student_id)
        accepted.append(
            AccountBulkImportEntry(
                line_number=line_number,
                student_id=student_id,
                password=password or student_id,
            )
        )
        if len(accepted) > MAX_BULK_IMPORT_ACCOUNTS:
            raise ApiRequestError("一次最多导入 200 个账号")

    return AccountBulkImportParseResult(
        accepted=tuple(accepted),
        invalid=tuple(invalid),
        duplicates=tuple(duplicates),
    )


def read_submitted_student_id(payload: dict[str, Any]) -> str | None:
    _require_payload_object(payload)
    return read_optional_text_field(payload.get("studentId"))


def set_default_account_bundle_from_payload(
    config_path: str | Path, payload: dict[str, Any]
) -> tuple[ConfigBundle, str]:
    account_name = read_account_name_field(payload)
    try:
        bundle = set_default_account_config(config_path, account_name)
    except ValueError as exc:
        raise ApiRequestError(str(exc)) from exc
    return bundle, account_name


def read_account_name_field(payload: dict[str, Any]) -> str:
    _require_payload_object(payload)
    return read_required_text_field(payload.get("accountName"), "accountName")


def read_account_names_field(payload: dict[str, Any]) -> list[str]:
    _require_payload_object(payload)
    account_names = read_text_list_field(payload.get("accountNames"), "accountNames")
    unique_names = list(dict.fromkeys(account_names))
    if not unique_names:
        raise ApiRequestError("accountNames 不能为空")
    return unique_names


def ensure_account_can_be_deleted(
    account_name: str,
    scheduled_tasks: Iterable[Any],
    *,
    automation_plan_exists: bool,
) -> None:
    has_active_task = any(
        task.account_name == account_name and task.status in ACTIVE_TASK_STATUSES
        for task in scheduled_tasks
    )
    if has_active_task:
        raise ApiRequestError(
            f"账号“{account_name}”还有未完成任务，删除前请先处理任务记录",
            HTTPStatus.CONFLICT,
        )
    if automation_plan_exists:
        raise ApiRequestError(
            f"账号“{account_name}”还有自动任务计划，删除前请先删除计划",
            HTTPStatus.CONFLICT,
        )


def delete_account_bundle(config_path: str | Path, account_name: str) -> ConfigBundle:
    try:
        return delete_account_config(config_path, account_name)
    except ValueError as exc:
        raise ApiRequestError(str(exc)) from exc


def _require_payload_object(payload: dict[str, Any]) -> None:
    if not isinstance(payload, dict):
        raise ApiRequestError("请求体必须是 JSON 对象")


def build_account_display_label(account: AppConfig) -> str:
    student_id = (account.student_id or "").strip()
    if student_id and student_id != account.account_name:
        return f"{account.account_name} · {student_id}"
    return account.account_name


def save_account_mutation(
    config_path: str | Path, payload: dict[str, Any]
) -> AccountMutationResult:
    bundle, student_id = save_account_bundle_from_payload(config_path, payload)
    return AccountMutationResult(
        bundle=bundle,
        selected_account_name=student_id,
        message=f"账号已保存：{student_id}",
    )


def set_default_account_mutation(
    config_path: str | Path, payload: dict[str, Any]
) -> AccountMutationResult:
    bundle, account_name = set_default_account_bundle_from_payload(config_path, payload)
    return AccountMutationResult(
        bundle=bundle,
        selected_account_name=account_name,
        message=f"默认账号已切换为：{account_name}",
    )


def delete_account_mutation(
    config_path: str | Path,
    account_name: str,
    scheduled_tasks: Iterable[Any],
    *,
    automation_plan_exists: bool,
) -> AccountMutationResult:
    ensure_account_can_be_deleted(
        account_name,
        scheduled_tasks,
        automation_plan_exists=automation_plan_exists,
    )
    bundle = delete_account_bundle(config_path, account_name)
    return AccountMutationResult(
        bundle=bundle,
        selected_account_name=bundle.default_account_name,
        message=f"账号已删除：{account_name}",
    )


def delete_accounts_mutation(
    config_path: str | Path,
    account_names: list[str],
    scheduled_tasks: Iterable[Any],
    *,
    existing_account_names: Iterable[str],
    automation_plan_exists: Callable[[str], bool],
) -> AccountMutationResult:
    existing_names = set(existing_account_names)
    missing_names = [
        account_name
        for account_name in account_names
        if account_name not in existing_names
    ]
    if missing_names:
        raise ApiRequestError("未找到账号：" + "、".join(missing_names))

    scheduled_task_list = list(scheduled_tasks)
    for account_name in account_names:
        ensure_account_can_be_deleted(
            account_name,
            scheduled_task_list,
            automation_plan_exists=automation_plan_exists(account_name),
        )

    bundle = None
    for account_name in account_names:
        bundle = delete_account_bundle(config_path, account_name)
    if bundle is None:
        raise ApiRequestError("accountNames 不能为空")

    return AccountMutationResult(
        bundle=bundle,
        selected_account_name=bundle.default_account_name,
        message=f"已删除 {len(account_names)} 个账号",
    )


def build_account_mutation_response(
    result: AccountMutationResult, accounts_payload: dict[str, Any]
) -> dict[str, Any]:
    return {"message": result.message, **accounts_payload}


def build_account_import_response(
    result: AccountBulkImportMutationResult, accounts_payload: dict[str, Any]
) -> dict[str, Any]:
    return {
        "message": result.message,
        "importResult": serialize_account_import_result(result.import_result),
        **accounts_payload,
    }


def serialize_account_import_result(
    result: AccountBulkImportParseResult,
) -> dict[str, Any]:
    return {
        "acceptedCount": len(result.accepted),
        "invalid": [
            _serialize_account_import_failure(failure) for failure in result.invalid
        ],
        "duplicates": [
            _serialize_account_import_failure(failure)
            for failure in result.duplicates
        ],
    }


def _serialize_account_import_failure(
    failure: AccountBulkImportFailure,
) -> dict[str, Any]:
    return {
        "lineNumber": failure.line_number,
        "studentId": failure.student_id,
        "reason": failure.reason,
    }


def _build_existing_account_ids(config_bundle: ConfigBundle) -> set[str]:
    result: set[str] = set()
    for account in config_bundle.accounts:
        result.add(account.account_name.strip())
        if account.student_id:
            result.add(account.student_id.strip())
    return {item for item in result if item}


def _parse_bulk_import_line(line: str) -> tuple[str, str]:
    for pattern in BULK_IMPORT_FORMAT_PATTERNS:
        match = pattern.match(line)
        if match is None:
            continue
        student_id = match.group(1).strip()
        password = match.group(2).strip() if match.lastindex and match.lastindex >= 2 else ""
        return student_id, password
    return "", ""


def _validate_bulk_import_student_id(student_id: str) -> str | None:
    if not student_id:
        return "学号不能为空"
    if len(student_id) > MAX_BULK_IMPORT_STUDENT_ID_LENGTH:
        return "学号不能超过 32 个字符"
    if STUDENT_ID_PATTERN.fullmatch(student_id) is None:
        return "学号只能包含字母、数字和下划线"
    return None
