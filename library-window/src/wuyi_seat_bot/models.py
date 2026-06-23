from dataclasses import dataclass
from enum import Enum


class ActionType(str, Enum):
    RESERVE = "reserve"
    CHECKIN = "checkin"
    CHECKOUT = "checkout"


class SeatActionStatus(str, Enum):
    SUCCESS = "success"
    UNAVAILABLE = "unavailable"
    FAILED = "failed"


@dataclass(frozen=True)
class AppConfig:
    login_url: str
    state_file: str
    seat_urls: tuple[str, ...]
    account_name: str = "默认账号"
    student_id: str | None = None
    password: str | None = None
    preferred_room_names: tuple[str, ...] = ()
    preferred_seat_numbers: tuple[str, ...] = ()
    max_attempts: int = 2
    retry_wait_seconds: float = 2.0


@dataclass(frozen=True)
class ConfigBundle:
    accounts: tuple[AppConfig, ...]
    default_account_name: str

    def get_account(self, account_name: str | None = None) -> AppConfig:
        if not self.accounts:
            raise ValueError("当前还没有账号，请先新增账号")
        expected_name = account_name or self.default_account_name
        for account in self.accounts:
            if account.account_name == expected_name:
                return account
        raise ValueError(f"未找到账号：{expected_name}")


@dataclass(frozen=True)
class SeatActionOutcome:
    status: SeatActionStatus
    message: str
    screenshot_path: str | None = None


@dataclass(frozen=True)
class ActionResult:
    success: bool
    action: ActionType
    seat_url: str | None
    attempts: int
    message: str
