import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from wuyi_seat_bot.scheduler import FAILED_STATUS, PENDING_STATUS, SUCCESS_STATUS, LocalTaskScheduler


class LocalTaskSchedulerTestCase(unittest.TestCase):
    def test_add_task_persists_and_lists_task(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            scheduler = LocalTaskScheduler(Path(tmp_dir) / "tasks.json", lambda task: "ok")
            task = scheduler.add_task(
                action="reserve",
                account_name="主号",
                run_at="2026-03-28T08:00:00",
                summary="主号 · 定时预约",
                payload={"accountName": "主号"},
            )

            tasks = scheduler.list_tasks()

        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0].task_id, task.task_id)
        self.assertEqual(tasks[0].status, PENDING_STATUS)

    def test_run_pending_once_marks_task_success(self) -> None:
        executed: list[str] = []
        with tempfile.TemporaryDirectory() as tmp_dir:
            scheduler = LocalTaskScheduler(
                Path(tmp_dir) / "tasks.json",
                lambda task: executed.append(task.task_id) or "预约成功",
            )
            task = scheduler.add_task(
                action="reserve",
                account_name="主号",
                run_at="2026-03-28T08:00:00",
                summary="主号 · 定时预约",
                payload={"accountName": "主号"},
            )

            handled = scheduler.run_pending_once(datetime(2026, 3, 28, 8, 0, 1))
            tasks = scheduler.list_tasks()

        self.assertTrue(handled)
        self.assertEqual(executed, [task.task_id])
        self.assertEqual(tasks[0].status, SUCCESS_STATUS)
        self.assertEqual(tasks[0].last_message, "预约成功")

    def test_run_pending_once_marks_task_failed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            scheduler = LocalTaskScheduler(
                Path(tmp_dir) / "tasks.json",
                lambda task: (_ for _ in ()).throw(RuntimeError("预约失败")),
            )
            scheduler.add_task(
                action="reserve",
                account_name="主号",
                run_at="2026-03-28T08:00:00",
                summary="主号 · 定时预约",
                payload={"accountName": "主号"},
            )

            scheduler.run_pending_once(datetime(2026, 3, 28, 8, 0, 1))
            tasks = scheduler.list_tasks()

        self.assertEqual(tasks[0].status, FAILED_STATUS)
        self.assertEqual(tasks[0].last_message, "预约失败")

    def test_delete_task_removes_pending_task(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            scheduler = LocalTaskScheduler(Path(tmp_dir) / "tasks.json", lambda task: "ok")
            task = scheduler.add_task(
                action="checkin",
                account_name="主号",
                run_at="2026-03-28T08:00:00",
                summary="主号 · 定时签到",
                payload={"accountName": "主号"},
            )

            deleted = scheduler.delete_task(task.task_id)

        self.assertEqual(deleted.task_id, task.task_id)
        self.assertEqual(scheduler.list_tasks(), [])

    def test_compute_wait_timeout_uses_next_due_task(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            scheduler = LocalTaskScheduler(Path(tmp_dir) / "tasks.json", lambda task: "ok", poll_interval_seconds=60)
            scheduler.add_task(
                action="reserve",
                account_name="主号",
                run_at="2026-03-28T08:00:10",
                summary="主号 · 定时预约",
                payload={"accountName": "主号"},
            )

            wait_seconds = scheduler._compute_wait_timeout(datetime(2026, 3, 28, 8, 0, 0))

        self.assertEqual(wait_seconds, 10.0)

    def test_compute_wait_timeout_uses_idle_interval_without_pending_task(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            scheduler = LocalTaskScheduler(Path(tmp_dir) / "tasks.json", lambda task: "ok", poll_interval_seconds=60)

            wait_seconds = scheduler._compute_wait_timeout(datetime(2026, 3, 28, 8, 0, 0))

        self.assertEqual(wait_seconds, 60)
