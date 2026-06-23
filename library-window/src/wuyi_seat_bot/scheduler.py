# 撤回 spec account-pool-tri-sync 11.4 中的 ConnectivityGate 守卫；本地执行不再依赖服务端可达性
from __future__ import annotations

import json
import threading
import uuid
from dataclasses import asdict, dataclass, replace
from datetime import datetime
from pathlib import Path
from typing import Any, Callable


PENDING_STATUS = "pending"
RUNNING_STATUS = "running"
SUCCESS_STATUS = "success"
FAILED_STATUS = "failed"


@dataclass(frozen=True)
class ScheduledTask:
    task_id: str
    action: str
    account_name: str
    run_at: str
    created_at: str
    summary: str
    payload: dict[str, Any]
    status: str = PENDING_STATUS
    last_message: str = ""
    finished_at: str | None = None


class LocalTaskScheduler:
    def __init__(
        self,
        storage_path: str | Path,
        execute_task: Callable[[ScheduledTask], str],
        *,
        poll_interval_seconds: float = 60.0,
    ) -> None:
        self.storage_path = Path(storage_path)
        self.execute_task = execute_task
        self.poll_interval_seconds = poll_interval_seconds
        self._lock = threading.Lock()
        self._wake_event = threading.Event()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._tasks = self._load_tasks()

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, name="local-task-scheduler", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        self._wake_event.set()
        if self._thread is not None:
            self._thread.join(timeout=3)

    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def list_tasks(self) -> list[ScheduledTask]:
        with self._lock:
            tasks = list(self._tasks.values())
        return sorted(tasks, key=_task_sort_key)

    def add_task(
        self,
        *,
        action: str,
        account_name: str,
        run_at: str,
        summary: str,
        payload: dict[str, Any],
    ) -> ScheduledTask:
        task = ScheduledTask(
            task_id=uuid.uuid4().hex[:12],
            action=action,
            account_name=account_name,
            run_at=run_at,
            created_at=_now_iso(),
            summary=summary,
            payload=payload,
        )
        with self._lock:
            self._tasks[task.task_id] = task
            self._save_locked()
        self._wake_event.set()
        return task

    def delete_task(self, task_id: str) -> ScheduledTask:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise ValueError(f"未找到定时任务：{task_id}")
            if task.status == RUNNING_STATUS:
                raise ValueError("任务正在执行，暂时不能删除")
            deleted = self._tasks.pop(task_id)
            self._save_locked()
        self._wake_event.set()
        return deleted

    def run_pending_once(self, now: datetime | None = None) -> bool:
        due_task = self._take_due_task(now)
        if due_task is None:
            return False

        try:
            message = self.execute_task(due_task)
            status = SUCCESS_STATUS
        except Exception as exc:  # noqa: BLE001
            message = str(exc)
            status = FAILED_STATUS

        finished_at = _datetime_to_iso(now or datetime.now())
        with self._lock:
            current_task = self._tasks.get(due_task.task_id)
            if current_task is None:
                return True
            self._tasks[due_task.task_id] = replace(
                current_task,
                status=status,
                last_message=message,
                finished_at=finished_at,
            )
            self._save_locked()
        return True

    def _take_due_task(self, now: datetime | None = None) -> ScheduledTask | None:
        current_time = now or datetime.now()
        with self._lock:
            due_tasks = [
                task
                for task in self._tasks.values()
                if task.status == PENDING_STATUS and _parse_iso(task.run_at) <= current_time
            ]
            if not due_tasks:
                return None

            due_task = sorted(due_tasks, key=_task_sort_key)[0]
            self._tasks[due_task.task_id] = replace(
                due_task,
                status=RUNNING_STATUS,
                last_message="任务已开始执行",
            )
            self._save_locked()
            return self._tasks[due_task.task_id]

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            executed = self.run_pending_once()
            if executed:
                continue
            self._wake_event.wait(self._compute_wait_timeout())
            self._wake_event.clear()

    def _compute_wait_timeout(self, now: datetime | None = None) -> float:
        current_time = now or datetime.now()
        with self._lock:
            next_run_at = min(
                (
                    _parse_iso(task.run_at)
                    for task in self._tasks.values()
                    if task.status == PENDING_STATUS
                ),
                default=None,
            )
        if next_run_at is None:
            return self.poll_interval_seconds
        seconds_until_due = (next_run_at - current_time).total_seconds()
        if seconds_until_due <= 0:
            return 0.0
        return min(seconds_until_due, self.poll_interval_seconds)

    def _load_tasks(self) -> dict[str, ScheduledTask]:
        if not self.storage_path.exists():
            return {}
        payload = json.loads(self.storage_path.read_text(encoding="utf-8"))
        if not isinstance(payload, list):
            return {}
        tasks: dict[str, ScheduledTask] = {}
        for item in payload:
            if not isinstance(item, dict):
                continue
            task = ScheduledTask(
                task_id=str(item.get("task_id", "")),
                action=str(item.get("action", "")),
                account_name=str(item.get("account_name", "")),
                run_at=str(item.get("run_at", "")),
                created_at=str(item.get("created_at", "")),
                summary=str(item.get("summary", "")),
                payload=item.get("payload", {}) if isinstance(item.get("payload", {}), dict) else {},
                status=str(item.get("status", PENDING_STATUS)),
                last_message=str(item.get("last_message", "")),
                finished_at=None if item.get("finished_at") in {None, ""} else str(item.get("finished_at")),
            )
            if task.task_id:
                tasks[task.task_id] = task
        return tasks

    def _save_locked(self) -> None:
        self.storage_path.parent.mkdir(parents=True, exist_ok=True)
        payload = [asdict(task) for task in sorted(self._tasks.values(), key=_task_sort_key)]
        self.storage_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _task_sort_key(task: ScheduledTask) -> tuple[int, datetime, datetime, str]:
    status_priority = {
        PENDING_STATUS: 0,
        RUNNING_STATUS: 1,
        FAILED_STATUS: 2,
        SUCCESS_STATUS: 3,
    }
    return (
        status_priority.get(task.status, 9),
        _parse_iso(task.run_at),
        _parse_iso(task.created_at),
        task.task_id,
    )


def _parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value)


def _now_iso() -> str:
    return _datetime_to_iso(datetime.now())


def _datetime_to_iso(value: datetime) -> str:
    return value.replace(microsecond=0).isoformat()
