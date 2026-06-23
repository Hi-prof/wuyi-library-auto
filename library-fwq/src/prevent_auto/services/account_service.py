from __future__ import annotations

from pathlib import Path

from prevent_auto.models import Account
from prevent_auto.repositories.accounts import AccountsRepository


class AccountService:
    def __init__(self, database_path: str | Path) -> None:
        self.repository = AccountsRepository(database_path)

    def create_account(
        self,
        *,
        name: str,
        student_id: str,
        password: str,
        login_url: str,
        seat_url: str,
        enabled: bool = True,
        rebook_enabled: bool = False,
        rebook_trigger_minutes: int = 5,
    ) -> Account:
        return self.repository.create(
            name=name,
            student_id=student_id,
            password=password,
            login_url=login_url,
            seat_url=seat_url,
            rebook_enabled=rebook_enabled,
            rebook_trigger_minutes=rebook_trigger_minutes,
            enabled=enabled,
        )

    def update_account(
        self,
        account_id: int,
        *,
        name: str,
        student_id: str,
        password: str,
        login_url: str,
        seat_url: str,
        enabled: bool = True,
        rebook_enabled: bool = False,
        rebook_trigger_minutes: int = 5,
    ) -> Account:
        return self.repository.update(
            account_id,
            name=name,
            student_id=student_id,
            password=password,
            login_url=login_url,
            seat_url=seat_url,
            rebook_enabled=rebook_enabled,
            rebook_trigger_minutes=rebook_trigger_minutes,
            enabled=enabled,
        )

    def list_accounts(self) -> list[Account]:
        return self.repository.list_all()

    def list_enabled_accounts(self) -> list[Account]:
        return self.repository.list_enabled()

    def get_account(self, account_id: int) -> Account:
        return self.repository.get(account_id)

    def set_enabled(self, account_id: int, *, enabled: bool) -> None:
        self.repository.set_enabled(account_id, enabled)

    def update_account_status(self, account_id: int, *, account_status: str) -> None:
        self.repository.update_account_status(
            account_id,
            account_status=account_status,
        )

    def delete_account(self, account_id: int) -> None:
        self.repository.delete(account_id)

    def update_status(
        self,
        account_id: int,
        *,
        last_check_at: str,
        last_status: str,
    ) -> None:
        self.repository.update_status(
            account_id,
            last_check_at=last_check_at,
            last_status=last_status,
        )

    def update_detected_booking(self, account_id: int, booking) -> None:
        self.repository.update_detected_booking(
            account_id,
            room_name=booking.room_name,
            seat_number=booking.seat_number,
            booking_start_at=str(booking.start_time),
            booking_status=booking.status,
        )
