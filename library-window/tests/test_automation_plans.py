import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from wuyi_seat_bot.automation_plans import (
    AutomationActionResult,
    LocalAutomationPlanScheduler,
    build_automation_plan,
)


class AutomationPlansTestCase(unittest.TestCase):
    def test_build_automation_plan_schedules_reserve_at_custom_time_and_checkin_immediately(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="主号",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-30",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=True,
            checkout_enabled=True,
            continuous_reserve=True,
            reserve_time="23:10",
            checkin_time="08:00",
            checkout_time="21:59",
            reserve_check_interval_minutes=30,
            now=datetime(2026, 3, 29, 22, 59, 44),
        )

        self.assertEqual(plan.reserve_next_run_at, "2026-03-29T23:10:00")
        self.assertEqual(plan.checkin_next_run_at, "2026-03-29T22:59:00")
        self.assertEqual(plan.checkout_next_run_at, "2026-03-30T21:59:00")

    def test_build_automation_plan_schedules_reserve_next_day_when_custom_time_passed(
        self,
    ) -> None:
        plan = build_automation_plan(
            account_name="main",
            seat_url="https://example.com/seat/a",
            seat_number="58",
            selected_date="2026-03-30",
            start_hour=8,
            duration_hours=14,
            reserve_enabled=True,
            checkin_enabled=False,
            checkout_enabled=False,
            continuous_reserve=True,
            reserve_time="23:10",
            checkin_time="08:00",
            checkout_time="21:59",
            reserve_check_interval_minutes=30,
            now=datetime(2026, 3, 29, 23, 11, 0),
        )

        self.assertEqual(plan.reserve_next_run_at, "2026-03-30T23:10:00")

    def test_scheduler_runs_due_action_and_updates_plan_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            storage_path = Path(tmp_dir) / "automation-plans.json"
            scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message=f"{action} ok",
                    target_dates=("2026-03-29", "2026-03-30"),
                    booked_dates=("2026-03-29",),
                ),
            )
            plan = build_automation_plan(
                account_name="主号",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-29",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=True,
                reserve_time="08:00",
                checkin_time="08:00",
                checkout_time="21:59",
                reserve_check_interval_minutes=30,
                now=datetime(2026, 3, 29, 7, 55),
            )
            scheduler.save_plan(plan)

            executed = scheduler.run_due_once(datetime(2026, 3, 29, 8, 0))
            saved_plan = scheduler.get_plan(plan.plan_id)

        self.assertTrue(executed)
        self.assertIsNotNone(saved_plan)
        self.assertEqual(saved_plan.reserve_last_message, "reserve ok")
        self.assertEqual(saved_plan.reserve_target_dates, ("2026-03-29", "2026-03-30"))
        self.assertEqual(saved_plan.reserve_booked_dates, ("2026-03-29",))
        self.assertEqual(saved_plan.reserve_next_run_at, "2026-03-29T08:30:00")

    def test_manual_reserve_result_updates_plan_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            storage_path = Path(tmp_dir) / "automation-plans.json"
            scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message=f"{action} ok"
                ),
            )
            plan = build_automation_plan(
                account_name="主号",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-29",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=True,
                reserve_time="08:00",
                checkin_time="08:00",
                checkout_time="21:59",
                reserve_check_interval_minutes=30,
                now=datetime(2026, 3, 29, 7, 55),
            )
            scheduler.save_plan(plan)

            updated = scheduler.apply_manual_reserve_result(
                plan.plan_id,
                datetime(2026, 3, 29, 8, 0),
                AutomationActionResult(
                    message="manual reserve ok",
                    target_dates=("2026-03-29", "2026-03-30"),
                    booked_dates=("2026-03-29",),
                    created_dates=("2026-03-29",),
                ),
            )
            missing = scheduler.apply_manual_reserve_result(
                "missing",
                datetime(2026, 3, 29, 8, 0),
                AutomationActionResult(message="missing"),
            )
            saved_plan = scheduler.get_plan(plan.plan_id)

        self.assertIsNotNone(updated)
        self.assertIsNone(missing)
        self.assertIsNotNone(saved_plan)
        self.assertEqual(saved_plan.reserve_last_run_at, "2026-03-29T08:00:00")
        self.assertEqual(saved_plan.reserve_last_message, "manual reserve ok")
        self.assertEqual(saved_plan.reserve_target_dates, ("2026-03-29", "2026-03-30"))
        self.assertEqual(saved_plan.reserve_booked_dates, ("2026-03-29",))
        self.assertEqual(saved_plan.reserve_next_run_at, "2026-03-29T08:30:00")

    def test_reserve_followup_uses_custom_time_as_interval_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            storage_path = Path(tmp_dir) / "automation-plans.json"
            scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message=f"{action} ok"
                ),
            )
            plan = build_automation_plan(
                account_name="main",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-29",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=True,
                reserve_time="07:10",
                checkin_time="08:00",
                checkout_time="21:59",
                reserve_check_interval_minutes=30,
                now=datetime(2026, 3, 29, 7, 0),
            )
            scheduler.save_plan(plan)

            executed = scheduler.run_due_once(datetime(2026, 3, 29, 7, 10))
            saved_plan = scheduler.get_plan(plan.plan_id)

        self.assertTrue(executed)
        self.assertIsNotNone(saved_plan)
        self.assertEqual(saved_plan.reserve_next_run_at, "2026-03-29T07:40:00")

    def test_scheduler_wait_timeout_runs_immediate_checkin_without_waiting(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            storage_path = Path(tmp_dir) / "automation-plans.json"
            scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message=f"{action} ok"
                ),
                poll_interval_seconds=300,
            )
            plan = build_automation_plan(
                account_name="主号",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-29",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=False,
                checkin_enabled=True,
                checkout_enabled=False,
                continuous_reserve=False,
                reserve_time="07:00",
                checkin_time="08:00",
                checkout_time="21:59",
                reserve_check_interval_minutes=30,
                now=datetime(2026, 3, 29, 7, 55),
            )
            scheduler.save_plan(plan)

            wait_seconds = scheduler._compute_wait_timeout(
                datetime(2026, 3, 29, 7, 59, 50)
            )

        self.assertEqual(wait_seconds, 0.0)

    def test_scheduler_retries_checkin_later_same_day_when_result_requests_retry(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            storage_path = Path(tmp_dir) / "automation-plans.json"
            scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message="已找到待签到预约，但当前还不在签到时间窗内：自习室圆形二楼 166 号座位",
                    retry_delay_minutes=5,
                ),
            )
            plan = build_automation_plan(
                account_name="主号",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-29",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=False,
                checkin_enabled=True,
                checkout_enabled=False,
                continuous_reserve=False,
                reserve_time="07:00",
                checkin_time="10:00",
                checkout_time="21:59",
                reserve_check_interval_minutes=30,
                now=datetime(2026, 3, 29, 9, 55),
            )
            scheduler.save_plan(plan)

            executed = scheduler.run_due_once(datetime(2026, 3, 29, 10, 0))
            saved_plan = scheduler.get_plan(plan.plan_id)

        self.assertTrue(executed)
        self.assertIsNotNone(saved_plan)
        self.assertEqual(
            saved_plan.checkin_last_message,
            "已找到待签到预约，但当前还不在签到时间窗内：自习室圆形二楼 166 号座位",
        )
        self.assertEqual(saved_plan.checkin_next_run_at, "2026-03-29T10:05:00")

    def test_prune_plans_for_unknown_accounts_removes_orphans_and_persists(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            storage_path = Path(tmp_dir) / "automation-plans.json"
            scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message=f"{action} ok"
                ),
            )
            kept_plan = build_automation_plan(
                account_name="20231121151",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-29",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=False,
                reserve_time="08:00",
                checkin_time="08:00",
                checkout_time="21:59",
                now=datetime(2026, 3, 29, 7, 55),
            )
            orphan_plan = build_automation_plan(
                account_name="20211121101",
                seat_url="https://example.com/seat/a",
                seat_number="77",
                selected_date="2026-03-29",
                start_hour=13,
                duration_hours=9,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=True,
                reserve_time="13:00",
                checkin_time="08:00",
                checkout_time="21:59",
                now=datetime(2026, 3, 29, 7, 55),
            )
            scheduler.save_plan(kept_plan)
            scheduler.save_plan(orphan_plan)

            removed = scheduler.prune_plans_for_unknown_accounts(
                ["20231121151", "  ", ""]
            )

            remaining_plan_ids = [plan.plan_id for plan in scheduler.list_plans()]

            # 重新加载一次，确认磁盘也清掉了
            reloaded_scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message="reloaded"
                ),
            )
            reloaded_plan_ids = [
                plan.plan_id for plan in reloaded_scheduler.list_plans()
            ]

        self.assertEqual(
            tuple(plan.plan_id for plan in removed), (orphan_plan.plan_id,)
        )
        self.assertEqual(removed[0].account_name, "20211121101")
        self.assertEqual(remaining_plan_ids, [kept_plan.plan_id])
        self.assertEqual(reloaded_plan_ids, [kept_plan.plan_id])

    def test_prune_plans_for_unknown_accounts_returns_empty_when_all_known(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            storage_path = Path(tmp_dir) / "automation-plans.json"
            scheduler = LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(
                    message=f"{action} ok"
                ),
            )
            plan = build_automation_plan(
                account_name="20231121151",
                seat_url="https://example.com/seat/a",
                seat_number="58",
                selected_date="2026-03-29",
                start_hour=8,
                duration_hours=14,
                reserve_enabled=True,
                checkin_enabled=False,
                checkout_enabled=False,
                continuous_reserve=False,
                reserve_time="08:00",
                checkin_time="08:00",
                checkout_time="21:59",
                now=datetime(2026, 3, 29, 7, 55),
            )
            scheduler.save_plan(plan)

            removed = scheduler.prune_plans_for_unknown_accounts(["20231121151"])

            remaining_plan_ids = [item.plan_id for item in scheduler.list_plans()]

        self.assertEqual(removed, ())
        self.assertEqual(remaining_plan_ids, [plan.plan_id])
