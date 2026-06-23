from __future__ import annotations

from collections.abc import Callable
from collections import defaultdict
from datetime import datetime
import threading

from prevent_auto.models import Account, BookingSnapshot
from prevent_auto.repositories.booking_snapshots import BookingSnapshotsRepository
from prevent_auto.repositories.monitor_records import MonitorRecordsRepository
from prevent_auto.services.booking_status_service import CANCELABLE_BOOKING_STATUSES

OVERDUE_BOOKING_GRACE_SECONDS = 3 * 60


class MonitorLoop:
    def __init__(
        self,
        *,
        account_service,
        booking_status_service,
        cancel_service,
        now_provider,
        monitor_records_repository=None,
        booking_snapshots_repository: BookingSnapshotsRepository | None = None,
        account_locks: dict[int, threading.Lock] | None = None,
        account_resolver: Callable[[Account], Account] | None = None,
    ) -> None:
        self.account_service = account_service
        self.booking_status_service = booking_status_service
        self.cancel_service = cancel_service
        self.now_provider = now_provider
        self.monitor_records_repository = (
            monitor_records_repository
            or MonitorRecordsRepository(
                self.cancel_service.logs_repository.database_path
            )
        )
        self.booking_snapshots_repository = (
            booking_snapshots_repository
            or BookingSnapshotsRepository(
                self.cancel_service.logs_repository.database_path
            )
        )
        self.account_locks = (
            account_locks if account_locks is not None else defaultdict(threading.Lock)
        )
        self.account_resolver = account_resolver or (lambda account: account)

    def run_cycle_once(self) -> None:
        current_time = self.now_provider()
        detected_at = current_time.replace(microsecond=0).isoformat()
        for account in self.account_service.list_enabled_accounts():
            with self.account_locks[account.id]:
                try:
                    account = self._resolve_account(account)
                    self._run_account_cycle(account, detected_at, current_time)
                except Exception as exc:  # noqa: BLE001
                    self._mark_cycle_error(account.id, detected_at, exc)

    def run_account_once(self, account_id: int) -> None:
        account = self.account_service.get_account(account_id)
        current_time = self.now_provider()
        detected_at = current_time.replace(microsecond=0).isoformat()
        with self.account_locks[account.id]:
            try:
                account = self._resolve_account(account)
                self._run_account_cycle(account, detected_at, current_time)
            except Exception as exc:  # noqa: BLE001
                self._mark_cycle_error(account.id, detected_at, exc)

    def run_status_check_once(self, account_id: int) -> None:
        account = self.account_service.get_account(account_id)
        current_time = self.now_provider()
        detected_at = current_time.replace(microsecond=0).isoformat()
        with self.account_locks[account.id]:
            try:
                account = self._resolve_account(account)
                self._run_account_cycle(account, detected_at, current_time)
            except Exception as exc:  # noqa: BLE001
                self._mark_cycle_error(account.id, detected_at, exc)

    def cancel_current_booking_once(self, account_id: int) -> str:
        account = self.account_service.get_account(account_id)
        current_time = self.now_provider()
        detected_at = current_time.replace(microsecond=0).isoformat()
        with self.account_locks[account.id]:
            try:
                account = self._resolve_account(account)
                return self._cancel_current_booking(account, detected_at, current_time)
            except Exception as exc:  # noqa: BLE001
                self._mark_cycle_error(account.id, detected_at, exc)
                return f"取消预约失败：{exc}"

    def _resolve_account(self, account: Account) -> Account:
        return self.account_resolver(account)

    def _run_account_cycle(
        self,
        account,
        detected_at: str,
        current_time: datetime,
    ) -> None:
        all_bookings = self.booking_status_service.fetch_all_bookings(account)
        # 全量结果先落库供号池管理页活跃池 Tab 直接读，避免页面再次实时调学校接口。
        self.booking_snapshots_repository.replace_for_account(
            account_id=account.id,
            bookings=all_bookings,
            refreshed_at=detected_at,
        )
        today_bookings = self.booking_status_service.filter_bookings_for_today(
            all_bookings, current_time
        )
        primary_booking = self.booking_status_service.pick_primary_booking(
            today_bookings,
            current_time,
        )
        if primary_booking is not None:
            self.account_service.update_detected_booking(account.id, primary_booking)
        if self._cancel_overdue_booking(
            account,
            today_bookings,
            detected_at,
            current_time,
        ):
            return
        booking_status = self.booking_status_service.describe_today_booking(
            account,
            current_time,
            bookings=today_bookings,
        )
        self.monitor_records_repository.create(
            account_id=account.id,
            booking_id=primary_booking.booking_id if primary_booking is not None else "",
            booking_status=primary_booking.status if primary_booking is not None else "",
            booking_start_at=str(primary_booking.start_time) if primary_booking is not None else None,
            detected_at=detected_at,
            decision="status_check",
            detail=booking_status or "今日无预约",
        )
        self.account_service.update_status(
            account.id,
            last_check_at=detected_at,
            last_status=booking_status or "今日无预约",
        )

    def _mark_cycle_error(
        self, account_id: int, detected_at: str, error: Exception
    ) -> None:
        self.account_service.update_status(
            account_id,
            last_check_at=detected_at,
            last_status=f"监控失败：{error}",
        )

    def _cancel_current_booking(
        self, account, detected_at: str, current_time: datetime
    ) -> str:
        all_bookings = self.booking_status_service.fetch_all_bookings(account)
        self.booking_snapshots_repository.replace_for_account(
            account_id=account.id,
            bookings=all_bookings,
            refreshed_at=detected_at,
        )
        today_bookings = self.booking_status_service.filter_bookings_for_today(
            all_bookings,
            current_time,
        )
        primary_booking = self.booking_status_service.pick_primary_booking(
            today_bookings,
            current_time,
        )
        if primary_booking is None:
            message = "取消预约失败：当前没有可取消的预约"
            self.monitor_records_repository.create(
                account_id=account.id,
                booking_id="",
                booking_status="",
                booking_start_at=None,
                detected_at=detected_at,
                decision="cancel_failed",
                detail=message,
            )
            self.account_service.update_status(
                account.id,
                last_check_at=detected_at,
                last_status=message,
            )
            return message

        self.account_service.update_detected_booking(account.id, primary_booking)
        if primary_booking.status not in CANCELABLE_BOOKING_STATUSES:
            message = "取消预约失败：当前预约状态不可取消"
            self.monitor_records_repository.create(
                account_id=account.id,
                booking_id=primary_booking.booking_id,
                booking_status=primary_booking.status,
                booking_start_at=str(primary_booking.start_time),
                detected_at=detected_at,
                decision="cancel_failed",
                detail=message,
            )
            self.account_service.update_status(
                account.id,
                last_check_at=detected_at,
                last_status=message,
            )
            return message

        success, message = self.cancel_service.cancel_booking(
            account,
            primary_booking.booking_id,
        )
        self.monitor_records_repository.create(
            account_id=account.id,
            booking_id=primary_booking.booking_id,
            booking_status=primary_booking.status,
            booking_start_at=str(primary_booking.start_time),
            detected_at=detected_at,
            decision="cancel" if success else "cancel_failed",
            detail=message,
        )
        status_message = message if success else f"取消预约失败：{message}"
        self.account_service.update_status(
            account.id,
            last_check_at=detected_at,
            last_status=status_message,
        )
        return status_message

    def _cancel_overdue_booking(
        self,
        account,
        today_bookings: list[BookingSnapshot],
        detected_at: str,
        current_time: datetime,
    ) -> bool:
        overdue_booking = _find_overdue_booking(today_bookings, current_time)
        if overdue_booking is None:
            return False

        self.account_service.update_detected_booking(account.id, overdue_booking)
        success, message = self.cancel_service.cancel_booking(
            account,
            overdue_booking.booking_id,
        )
        status_message = (
            f"超过预约时间 3 分钟自动取消：{message}"
            if success
            else f"超过预约时间 3 分钟自动取消失败：{message}"
        )
        decision = (
            "overdue_booking_cancel"
            if success
            else "overdue_booking_cancel_failed"
        )
        self.monitor_records_repository.create(
            account_id=account.id,
            booking_id=overdue_booking.booking_id,
            booking_status=overdue_booking.status,
            booking_start_at=str(overdue_booking.start_time),
            detected_at=detected_at,
            decision=decision,
            detail=status_message,
        )
        self.account_service.update_status(
            account.id,
            last_check_at=detected_at,
            last_status=status_message,
        )
        return True


def _find_overdue_booking(
    bookings: list[BookingSnapshot],
    current_time: datetime,
) -> BookingSnapshot | None:
    current_timestamp = current_time.timestamp()
    candidates: list[BookingSnapshot] = []
    for booking in bookings:
        if booking.status not in CANCELABLE_BOOKING_STATUSES:
            continue
        cancel_after = booking.start_time + OVERDUE_BOOKING_GRACE_SECONDS
        if current_timestamp >= cancel_after:
            candidates.append(booking)

    if not candidates:
        return None
    return min(
        candidates,
        key=lambda item: item.start_time,
    )
