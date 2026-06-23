# 撤回 spec account-pool-tri-sync 11.4 中的 ConnectivityGate 守卫；本地执行不再依赖服务端可达性
import time
from typing import Protocol

from wuyi_seat_bot.models import ActionResult, ActionType, AppConfig, SeatActionOutcome, SeatActionStatus


class SeatAutomation(Protocol):
    def perform_action(self, action: ActionType, seat_url: str) -> SeatActionOutcome:
        """对单个座位页面执行预约、签到或签退。"""


class SeatWorkflow:
    def __init__(self, config: AppConfig, automation: SeatAutomation) -> None:
        self.config = config
        self.automation = automation

    def run(self, action: ActionType) -> ActionResult:
        last_message = "未命中任何可执行座位"
        target_urls = self._resolve_target_urls(action)

        for attempt in range(1, self.config.max_attempts + 1):
            has_failed_action = False

            for seat_url in target_urls:
                outcome = self.automation.perform_action(action, seat_url)

                if outcome.status == SeatActionStatus.SUCCESS:
                    return ActionResult(
                        success=True,
                        action=action,
                        seat_url=seat_url,
                        attempts=attempt,
                        message=outcome.message,
                    )

                if outcome.status == SeatActionStatus.FAILED:
                    has_failed_action = True
                    last_message = outcome.message
                    continue

                if not has_failed_action and last_message == "未命中任何可执行座位":
                    last_message = outcome.message

            if attempt < self.config.max_attempts:
                time.sleep(self.config.retry_wait_seconds)

        return ActionResult(
            success=False,
            action=action,
            seat_url=None,
            attempts=self.config.max_attempts,
            message=last_message,
        )

    def _resolve_target_urls(self, action: ActionType) -> tuple[str, ...]:
        # 预约按 seat_urls 逐个尝试；签到和签退实际都从“我的预约”里挑记录，
        # 不再依赖某一个座位入口页，这里只触发一次，避免重复扫描蓝牙或重复请求。
        if action in {ActionType.CHECKIN, ActionType.CHECKOUT}:
            return (self.config.seat_urls[0],)
        return self.config.seat_urls
