import unittest
from collections import deque

from wuyi_seat_bot.models import ActionType, ActionResult, AppConfig, SeatActionOutcome, SeatActionStatus
from wuyi_seat_bot.workflow import SeatWorkflow


class FakeAutomation:
    def __init__(self, scripted_outcomes: dict[tuple[ActionType, str], list[SeatActionOutcome]]) -> None:
        self.scripted_outcomes = {
            key: deque(value) for key, value in scripted_outcomes.items()
        }
        self.calls: list[tuple[ActionType, str]] = []

    def perform_action(self, action: ActionType, seat_url: str) -> SeatActionOutcome:
        self.calls.append((action, seat_url))
        queue = self.scripted_outcomes[(action, seat_url)]
        if len(queue) == 1:
            return queue[0]
        return queue.popleft()


CONFIG = AppConfig(
    login_url="https://example.com/login",
    state_file="runtime/auth.json",
    seat_urls=("https://example.com/seat/166", "https://example.com/seat/168"),
    max_attempts=2,
    retry_wait_seconds=0,
)


class SeatWorkflowTestCase(unittest.TestCase):
    def test_workflow_uses_next_preferred_seat_when_first_unavailable(self) -> None:
        automation = FakeAutomation(
            {
                (ActionType.RESERVE, "https://example.com/seat/166"): [
                    SeatActionOutcome(status=SeatActionStatus.UNAVAILABLE, message="166 已被占用")
                ],
                (ActionType.RESERVE, "https://example.com/seat/168"): [
                    SeatActionOutcome(status=SeatActionStatus.SUCCESS, message="168 预约成功")
                ],
            }
        )

        result = SeatWorkflow(CONFIG, automation).run(ActionType.RESERVE)

        self.assertEqual(
            result,
            ActionResult(
                success=True,
                action=ActionType.RESERVE,
                seat_url="https://example.com/seat/168",
                attempts=1,
                message="168 预约成功",
            ),
        )

    def test_workflow_retries_after_temporary_failure(self) -> None:
        automation = FakeAutomation(
            {
                (ActionType.RESERVE, "https://example.com/seat/166"): [
                    SeatActionOutcome(status=SeatActionStatus.FAILED, message="网络超时"),
                    SeatActionOutcome(status=SeatActionStatus.SUCCESS, message="166 预约成功"),
                ],
                (ActionType.RESERVE, "https://example.com/seat/168"): [
                    SeatActionOutcome(status=SeatActionStatus.UNAVAILABLE, message="168 已被占用")
                ],
            }
        )

        result = SeatWorkflow(CONFIG, automation).run(ActionType.RESERVE)

        self.assertTrue(result.success)
        self.assertEqual(result.attempts, 2)
        self.assertEqual(result.seat_url, "https://example.com/seat/166")
        self.assertEqual(
            automation.calls[:2],
            [
                (ActionType.RESERVE, "https://example.com/seat/166"),
                (ActionType.RESERVE, "https://example.com/seat/168"),
            ],
        )

    def test_workflow_returns_failure_after_all_attempts(self) -> None:
        automation = FakeAutomation(
            {
                (ActionType.CHECKIN, "https://example.com/seat/166"): [
                    SeatActionOutcome(status=SeatActionStatus.FAILED, message="状态已过期"),
                    SeatActionOutcome(status=SeatActionStatus.FAILED, message="状态已过期"),
                ],
            }
        )

        result = SeatWorkflow(CONFIG, automation).run(ActionType.CHECKIN)

        self.assertFalse(result.success)
        self.assertEqual(result.attempts, 2)
        self.assertEqual(result.message, "状态已过期")
        self.assertEqual(
            automation.calls,
            [
                (ActionType.CHECKIN, "https://example.com/seat/166"),
                (ActionType.CHECKIN, "https://example.com/seat/166"),
            ],
        )

    def test_workflow_checkout_runs_only_once_on_first_entry_url(self) -> None:
        automation = FakeAutomation(
            {
                (ActionType.CHECKOUT, "https://example.com/seat/166"): [
                    SeatActionOutcome(status=SeatActionStatus.SUCCESS, message="签退成功")
                ],
            }
        )

        result = SeatWorkflow(CONFIG, automation).run(ActionType.CHECKOUT)

        self.assertTrue(result.success)
        self.assertEqual(result.seat_url, "https://example.com/seat/166")
        self.assertEqual(
            automation.calls,
            [(ActionType.CHECKOUT, "https://example.com/seat/166")],
        )
