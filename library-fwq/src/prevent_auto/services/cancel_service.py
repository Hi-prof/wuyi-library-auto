from __future__ import annotations

import json
from pathlib import Path

from prevent_auto.repositories.action_logs import ActionLogsRepository


class CancelService:
    def __init__(self, bridge, database_path: str | Path | None = None) -> None:
        self.bridge = bridge
        self.logs_repository = (
            None if database_path is None else ActionLogsRepository(database_path)
        )

    def cancel_booking(self, account, booking_id: str) -> tuple[bool, str]:
        success, message = self.bridge.cancel_booking(account, booking_id)
        if self.logs_repository is not None:
            self.logs_repository.create(
                account_id=account.id,
                action_type="cancel",
                success=success,
                message=message,
                payload_json=json.dumps({"bookingId": booking_id}, ensure_ascii=False),
            )
        return success, message
