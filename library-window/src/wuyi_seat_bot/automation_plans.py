from __future__ import annotations

import json
import threading
import uuid
from dataclasses import asdict, dataclass, replace
from datetime import date, datetime, time as time_value, timedelta
from pathlib import Path
from typing import Callable, Iterable


AUTOMATION_ACTIONS = ("reserve", "checkin", "checkout")


@dataclass(frozen=True)
class AutomationActionResult:
    message: str
    target_dates: tuple[str, ...] = ()
    booked_dates: tuple[str, ...] = ()
    created_dates: tuple[str, ...] = ()
    retry_delay_minutes: int | None = None


@dataclass(frozen=True)
class AutomationPlan:
    plan_id: str
    account_name: str
    seat_url: str
    room_id: str
    room_name: str
    seat_number: str
    selected_date: str
    start_hour: int
    duration_hours: int
    reserve_enabled: bool
    checkin_enabled: bool
    checkout_enabled: bool
    continuous_reserve: bool
    reserve_time: str
    checkin_time: str
    checkout_time: str
    reserve_check_interval_minutes: int
    created_at: str
    updated_at: str
    enabled: bool = True
    reserve_next_run_at: str | None = None
    reserve_last_run_at: str | None = None
    reserve_last_message: str = ""
    reserve_target_dates: tuple[str, ...] = ()
    reserve_booked_dates: tuple[str, ...] = ()
    checkin_next_run_at: str | None = None
    checkin_last_run_at: str | None = None
    checkin_last_message: str = ""
    checkout_next_run_at: str | None = None
    checkout_last_run_at: str | None = None
    checkout_last_message: str = ""


class LocalAutomationPlanScheduler:
    def __init__(
        self,
        storage_path: str | Path,
        execute_action: Callable[
            [AutomationPlan, str, datetime], AutomationActionResult
        ],
        *,
        poll_interval_seconds: float = 300.0,
    ) -> None:
        self.storage_path = Path(storage_path)
        self.execute_action = execute_action
        self.poll_interval_seconds = poll_interval_seconds
        self._lock = threading.Lock()
        self._wake_event = threading.Event()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._plans = self._load_plans()

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run_loop,
            name="local-automation-plan-scheduler",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        self._wake_event.set()
        if self._thread is not None:
            self._thread.join(timeout=3)

    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def list_plans(self) -> list[AutomationPlan]:
        with self._lock:
            plans = list(self._plans.values())
        return sorted(plans, key=_plan_sort_key)

    def get_plan(self, plan_id: str) -> AutomationPlan | None:
        with self._lock:
            return self._plans.get(plan_id)

    def find_plan_by_account(self, account_name: str) -> AutomationPlan | None:
        with self._lock:
            return next(
                (
                    plan
                    for plan in self._plans.values()
                    if plan.account_name == account_name
                ),
                None,
            )

    def save_plan(self, plan: AutomationPlan) -> AutomationPlan:
        with self._lock:
            self._plans[plan.plan_id] = plan
            self._save_locked()
        self._wake_event.set()
        return plan

    def delete_plan(self, plan_id: str) -> AutomationPlan:
        with self._lock:
            plan = self._plans.get(plan_id)
            if plan is None:
                raise ValueError(f"未找到自动任务计划：{plan_id}")
            deleted = self._plans.pop(plan_id)
            self._save_locked()
        self._wake_event.set()
        return deleted

    def prune_plans_for_unknown_accounts(
        self, allowed_account_names: Iterable[str]
    ) -> tuple[AutomationPlan, ...]:
        # config 里直接被手改删账号时，对应的自动任务计划会变成孤儿：
        # 调度器仍会按时触发，但 _get_account_runtime 找不到账号会抛异常，
        # 用户既看不到有用的状态，又无法在 UI 上修复。这里在加载/账号变更时清理。
        allowed = {
            str(name).strip()
            for name in allowed_account_names
            if str(name).strip()
        }
        with self._lock:
            orphan_plan_ids = [
                plan.plan_id
                for plan in self._plans.values()
                if plan.account_name not in allowed
            ]
            if not orphan_plan_ids:
                return ()
            removed = tuple(self._plans.pop(plan_id) for plan_id in orphan_plan_ids)
            self._save_locked()
        self._wake_event.set()
        return removed

    def run_due_once(self, now: datetime | None = None) -> bool:
        current_time = now or datetime.now()
        due = self._take_due_action(current_time)
        if due is None:
            return False

        plan, action = due
        try:
            result = self.execute_action(plan, action, current_time)
        except Exception as exc:  # noqa: BLE001
            result = AutomationActionResult(message=str(exc))

        with self._lock:
            current_plan = self._plans.get(plan.plan_id)
            if current_plan is None:
                return True
            self._plans[plan.plan_id] = _apply_action_result(
                current_plan, action, current_time, result
            )
            self._save_locked()
        return True

    def apply_manual_reserve_result(
        self,
        plan_id: str,
        now: datetime,
        result: AutomationActionResult,
    ) -> AutomationPlan | None:
        with self._lock:
            current_plan = self._plans.get(plan_id)
            if current_plan is None:
                return None
            updated = _apply_action_result(current_plan, "reserve", now, result)
            self._plans[plan_id] = updated
            self._save_locked()
        return updated

    def _take_due_action(self, now: datetime) -> tuple[AutomationPlan, str] | None:
        with self._lock:
            due_actions: list[tuple[datetime, int, AutomationPlan, str]] = []
            for plan in self._plans.values():
                if not plan.enabled:
                    continue
                for action, due_at in _iter_due_times(plan):
                    if due_at is None:
                        continue
                    due_time = _parse_iso(due_at)
                    if due_time <= now:
                        due_actions.append(
                            (due_time, _action_priority(action), plan, action)
                        )

            if not due_actions:
                return None

            _, _, plan, action = sorted(
                due_actions,
                key=lambda item: (
                    item[0],
                    item[1],
                    item[2].account_name,
                    item[2].plan_id,
                ),
            )[0]
            return plan, action

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            executed = self.run_due_once()
            if executed:
                continue
            self._wake_event.wait(self._compute_wait_timeout())
            self._wake_event.clear()

    def _compute_wait_timeout(self, now: datetime | None = None) -> float:
        current_time = now or datetime.now()
        with self._lock:
            next_run_at = min(
                (
                    _parse_iso(due_at)
                    for plan in self._plans.values()
                    if plan.enabled
                    for _, due_at in _iter_due_times(plan)
                    if due_at is not None
                ),
                default=None,
            )
        if next_run_at is None:
            return self.poll_interval_seconds
        seconds_until_due = (next_run_at - current_time).total_seconds()
        if seconds_until_due <= 0:
            return 0.0
        return min(seconds_until_due, self.poll_interval_seconds)

    def _load_plans(self) -> dict[str, AutomationPlan]:
        if not self.storage_path.exists():
            return {}
        payload = json.loads(self.storage_path.read_text(encoding="utf-8"))
        if not isinstance(payload, list):
            return {}

        plans: dict[str, AutomationPlan] = {}
        for item in payload:
            if not isinstance(item, dict):
                continue
            plan = _build_plan_from_payload(item)
            if plan.plan_id:
                plans[plan.plan_id] = plan
        return plans

    def _save_locked(self) -> None:
        self.storage_path.parent.mkdir(parents=True, exist_ok=True)
        payload = [
            asdict(plan) for plan in sorted(self._plans.values(), key=_plan_sort_key)
        ]
        self.storage_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )


def build_automation_plan(
    *,
    account_name: str,
    seat_url: str,
    room_id: str = "",
    room_name: str = "",
    seat_number: str,
    selected_date: str,
    start_hour: int,
    duration_hours: int,
    reserve_enabled: bool,
    checkin_enabled: bool,
    checkout_enabled: bool,
    continuous_reserve: bool,
    reserve_time: str,
    checkin_time: str,
    checkout_time: str,
    reserve_check_interval_minutes: int = 30,
    now: datetime | None = None,
    existing: AutomationPlan | None = None,
    reserve_target_dates: tuple[str, ...] = (),
    reserve_booked_dates: tuple[str, ...] = (),
) -> AutomationPlan:
    current_time = now or datetime.now()
    created_at = (
        existing.created_at if existing is not None else _datetime_to_iso(current_time)
    )
    plan_id = existing.plan_id if existing is not None else uuid.uuid4().hex[:12]

    # 自动预约的核心目标是“创建后立刻接管当前可预约窗口”。
    # 用户可能在 22:00 后新建计划，学校这时已经放开后三天预约；如果还等到次日 08:00 再跑，
    # 就会错过“创建后立即补订”的预期。所以首轮巡检统一立即执行，之后再按固定间隔继续轮询。
    reserve_next_run_at = (
        _build_initial_reserve_run_at(
            selected_date=selected_date,
            reserve_time=reserve_time,
            interval_minutes=reserve_check_interval_minutes,
            now=current_time,
        )
        if reserve_enabled
        else None
    )
    checkin_next_run_at = _datetime_to_iso(current_time) if checkin_enabled else None
    checkout_next_run_at = (
        _build_initial_daily_run_at(
            selected_date=selected_date, run_time=checkout_time, now=current_time
        )
        if checkout_enabled
        else None
    )

    return AutomationPlan(
        plan_id=plan_id,
        account_name=account_name,
        seat_url=seat_url,
        room_id=str(room_id or "").strip(),
        room_name=str(room_name or "").strip(),
        seat_number=seat_number,
        selected_date=selected_date,
        start_hour=int(start_hour),
        duration_hours=int(duration_hours),
        reserve_enabled=bool(reserve_enabled),
        checkin_enabled=bool(checkin_enabled),
        checkout_enabled=bool(checkout_enabled),
        continuous_reserve=bool(continuous_reserve),
        reserve_time=reserve_time,
        checkin_time=checkin_time,
        checkout_time=checkout_time,
        reserve_check_interval_minutes=max(int(reserve_check_interval_minutes), 5),
        created_at=created_at,
        updated_at=_datetime_to_iso(current_time),
        enabled=True,
        reserve_next_run_at=reserve_next_run_at,
        reserve_last_run_at=existing.reserve_last_run_at
        if existing is not None
        else None,
        reserve_last_message=existing.reserve_last_message
        if existing is not None
        else "",
        reserve_target_dates=reserve_target_dates
        or (existing.reserve_target_dates if existing is not None else ()),
        reserve_booked_dates=reserve_booked_dates
        or (existing.reserve_booked_dates if existing is not None else ()),
        checkin_next_run_at=checkin_next_run_at,
        checkin_last_run_at=existing.checkin_last_run_at
        if existing is not None
        else None,
        checkin_last_message=existing.checkin_last_message
        if existing is not None
        else "",
        checkout_next_run_at=checkout_next_run_at,
        checkout_last_run_at=existing.checkout_last_run_at
        if existing is not None
        else None,
        checkout_last_message=existing.checkout_last_message
        if existing is not None
        else "",
    )


def _build_plan_from_payload(payload: dict) -> AutomationPlan:
    return AutomationPlan(
        plan_id=str(payload.get("plan_id", "")),
        account_name=str(payload.get("account_name", "")),
        seat_url=str(payload.get("seat_url", "")),
        room_id=str(payload.get("room_id", "")),
        room_name=str(payload.get("room_name", "")),
        seat_number=str(payload.get("seat_number", "")),
        selected_date=str(payload.get("selected_date", "")),
        start_hour=int(payload.get("start_hour", 8) or 8),
        duration_hours=int(payload.get("duration_hours", 1) or 1),
        reserve_enabled=bool(payload.get("reserve_enabled", False)),
        checkin_enabled=bool(payload.get("checkin_enabled", False)),
        checkout_enabled=bool(payload.get("checkout_enabled", False)),
        continuous_reserve=bool(payload.get("continuous_reserve", False)),
        reserve_time=str(payload.get("reserve_time", "08:00") or "08:00"),
        checkin_time=str(payload.get("checkin_time", "08:00") or "08:00"),
        checkout_time=str(payload.get("checkout_time", "21:59") or "21:59"),
        reserve_check_interval_minutes=max(
            int(payload.get("reserve_check_interval_minutes", 30) or 30), 5
        ),
        created_at=str(payload.get("created_at", "")),
        updated_at=str(payload.get("updated_at", "")),
        enabled=bool(payload.get("enabled", True)),
        reserve_next_run_at=_optional_text(payload.get("reserve_next_run_at")),
        reserve_last_run_at=_optional_text(payload.get("reserve_last_run_at")),
        reserve_last_message=str(payload.get("reserve_last_message", "")),
        reserve_target_dates=_tuple_of_texts(payload.get("reserve_target_dates")),
        reserve_booked_dates=_tuple_of_texts(payload.get("reserve_booked_dates")),
        checkin_next_run_at=_optional_text(payload.get("checkin_next_run_at")),
        checkin_last_run_at=_optional_text(payload.get("checkin_last_run_at")),
        checkin_last_message=str(payload.get("checkin_last_message", "")),
        checkout_next_run_at=_optional_text(payload.get("checkout_next_run_at")),
        checkout_last_run_at=_optional_text(payload.get("checkout_last_run_at")),
        checkout_last_message=str(payload.get("checkout_last_message", "")),
    )


def _iter_due_times(plan: AutomationPlan) -> tuple[tuple[str, str | None], ...]:
    return (
        ("reserve", plan.reserve_next_run_at if plan.reserve_enabled else None),
        ("checkin", plan.checkin_next_run_at if plan.checkin_enabled else None),
        ("checkout", plan.checkout_next_run_at if plan.checkout_enabled else None),
    )


def _apply_action_result(
    plan: AutomationPlan,
    action: str,
    now: datetime,
    result: AutomationActionResult,
) -> AutomationPlan:
    updated_at = _datetime_to_iso(now)
    if action == "reserve":
        return replace(
            plan,
            updated_at=updated_at,
            reserve_last_run_at=updated_at,
            reserve_last_message=result.message,
            reserve_target_dates=result.target_dates,
            reserve_booked_dates=result.booked_dates,
            reserve_next_run_at=_datetime_to_iso(
                _build_next_interval_run_at(now, plan.reserve_check_interval_minutes),
            ),
        )
    if action == "checkin":
        return replace(
            plan,
            updated_at=updated_at,
            checkin_last_run_at=updated_at,
            checkin_last_message=result.message,
            checkin_next_run_at=_datetime_to_iso(
                _build_next_checkin_run_at(
                    now, plan.checkin_time, result.retry_delay_minutes
                ),
            ),
        )
    return replace(
        plan,
        updated_at=updated_at,
        checkout_last_run_at=updated_at,
        checkout_last_message=result.message,
        checkout_next_run_at=_datetime_to_iso(
            _build_next_daily_followup(now, plan.checkout_time),
        ),
    )


def _build_initial_reserve_run_at(
    *,
    selected_date: str,
    reserve_time: str,
    interval_minutes: int,
    now: datetime,
) -> str:
    return _datetime_to_iso(now)


def _build_initial_daily_run_at(
    *, selected_date: str, run_time: str, now: datetime
) -> str:
    selected_day = _parse_date(selected_date)
    current_day = now.date()
    anchor_day = selected_day if selected_day > current_day else current_day
    candidate = datetime.combine(anchor_day, _parse_time(run_time))
    if candidate > now:
        return _datetime_to_iso(candidate)
    return _datetime_to_iso(candidate + timedelta(days=1))


def _build_next_daily_followup(now: datetime, run_time: str) -> datetime:
    base = datetime.combine(now.date(), _parse_time(run_time)) + timedelta(days=1)
    return base.replace(second=0, microsecond=0)


def _build_next_checkin_run_at(
    now: datetime,
    run_time: str,
    retry_delay_minutes: int | None,
) -> datetime:
    retry_at = _build_same_day_retry_run_at(now, retry_delay_minutes)
    if retry_at is not None:
        return retry_at
    return _build_next_daily_followup(now, run_time)


def _build_same_day_retry_run_at(
    now: datetime, retry_delay_minutes: int | None
) -> datetime | None:
    if retry_delay_minutes is None:
        return None
    retry_minutes = max(int(retry_delay_minutes), 1)
    retry_base = now.replace(second=0, microsecond=0)
    if now.second or now.microsecond:
        retry_base += timedelta(minutes=1)
    retry_at = retry_base + timedelta(minutes=retry_minutes)
    if retry_at.date() != now.date():
        return None
    return retry_at


def _build_next_interval_run_at(now: datetime, interval_minutes: int) -> datetime:
    next_time = now.replace(second=0, microsecond=0) + timedelta(minutes=1)
    remainder = next_time.minute % interval_minutes
    if remainder:
        next_time += timedelta(minutes=interval_minutes - remainder)
    return next_time.replace(second=0, microsecond=0)


def _action_priority(action: str) -> int:
    return {"reserve": 0, "checkin": 1, "checkout": 2}.get(action, 9)


def _plan_sort_key(plan: AutomationPlan) -> tuple[str, str]:
    return (plan.account_name, plan.plan_id)


def _parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value)


def _parse_date(value: str) -> date:
    return datetime.strptime(value, "%Y-%m-%d").date()


def _parse_time(value: str) -> time_value:
    return datetime.strptime(value, "%H:%M").time()


def _datetime_to_iso(value: datetime) -> str:
    return value.replace(second=0, microsecond=0).isoformat()


def _optional_text(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None


def _tuple_of_texts(value: object) -> tuple[str, ...]:
    if not isinstance(value, list):
        return ()
    return tuple(str(item).strip() for item in value if str(item).strip())
