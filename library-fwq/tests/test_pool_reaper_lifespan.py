"""Pool_Reaper_Job 在 FastAPI lifespan 中的接入测试。

对应 spec ``account-pool-tri-sync`` task 6.2，覆盖：

* 启动后立即触发首次 tick：与 :class:`PoolReaperJob.run_forever` 的循环约定一致，
  ``yield`` 之前已有一条 ``reaper_tick`` 审计行落库，验证「服务停机期间累积的
  到期项由首次 tick 自然处理」（Requirement 3.5）。
* shutdown 时 ``stop_event`` 被设置并 ``await`` 任务退出，避免 web 进程残留
  asyncio 任务。
* ``ACCOUNT_POOL_SECRET_KEY`` 缺失时 lifespan 跳过 reaper 启动，保持既有测试
  路径不受影响。
* 当 ``start_background_workers=False`` 时（既有 web 测试用法），lifespan 不会
  启动 reaper。
"""

from __future__ import annotations

import tempfile
import unittest
from datetime import time as dtime
from pathlib import Path

from fastapi.testclient import TestClient

from prevent_auto.database import initialize_database
from prevent_auto.repositories.pool_audit_log import (
    PoolAuditAction,
    PoolAuditLogQuery,
    PoolAuditLogRepository,
)
from prevent_auto.scheduler.pool_reaper_job import PoolReaperJob
from prevent_auto.services.account_password_cipher import generate_secret_key
from prevent_auto.settings import PreventAutoSettings
from prevent_auto.web.app import create_app


def _build_settings(temp_dir: Path, *, secret_key: str = "") -> PreventAutoSettings:
    return PreventAutoSettings(
        project_root=temp_dir,
        package_root=Path(__file__).resolve().parents[1],
        data_dir=temp_dir,
        runtime_dir=temp_dir / "runtime",
        database_path=temp_dir / "prevent_auto.db",
        host="127.0.0.1",
        port=8080,
        monitor_interval_seconds=1500,
        rebook_poll_interval_seconds=15,
        log_retention_days=30,
        daily_status_refresh_time=dtime(8, 10),
        account_pool_secret_key=secret_key,
        # 把扫描周期拉长，避免单次 lifespan 内多次 tick 导致审计行数不稳定。
        account_pool_reaper_interval_seconds=3600,
    )


class PoolReaperLifespanTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())

    def test_lifespan_starts_pool_reaper_and_runs_first_tick_immediately(
        self,
    ) -> None:
        secret_key = generate_secret_key()
        settings = _build_settings(self.temp_dir, secret_key=secret_key)
        initialize_database(settings.database_path, settings=settings)

        app = create_app(settings)
        audit_repo = PoolAuditLogRepository(settings.database_path)

        with TestClient(app):
            # 进入 lifespan 后，PoolReaperJob 已经被 asyncio.create_task 拉起；
            # ``run_forever`` 启动后立即触发首次 tick，因此到这里一定能查到
            # 至少一条 ``reaper_tick`` 审计行（异步调度有微小延迟，给 1s 等待）。
            tick_rows = _wait_for_reaper_tick(audit_repo, timeout_seconds=2.0)
            self.assertGreaterEqual(len(tick_rows), 1)
            latest = tick_rows[0]
            self.assertEqual(latest.audit_action, PoolAuditAction.REAPER_TICK)
            self.assertEqual(latest.operator, "system")

            # lifespan 内 reaper 任务存在且未结束
            task = app.state.pool_reaper_task
            self.assertIsNotNone(task)
            self.assertFalse(task.done())
            self.assertIsInstance(app.state.pool_reaper_job, PoolReaperJob)

        # 退出 TestClient 触发 shutdown：stop_event 已被 set 并 await 完成。
        self.assertIsNone(app.state.pool_reaper_task)
        self.assertIsNone(app.state.pool_reaper_stop_event)

    def test_lifespan_skips_reaper_when_secret_key_absent(self) -> None:
        settings = _build_settings(self.temp_dir, secret_key="")
        initialize_database(settings.database_path, settings=settings)

        app = create_app(settings)
        with TestClient(app):
            self.assertIsNone(app.state.pool_reaper_job)
            self.assertIsNone(app.state.pool_reaper_task)
            self.assertIsNone(app.state.pool_reaper_stop_event)

    def test_lifespan_skips_reaper_when_background_workers_disabled(self) -> None:
        secret_key = generate_secret_key()
        settings = _build_settings(self.temp_dir, secret_key=secret_key)
        initialize_database(settings.database_path, settings=settings)

        app = create_app(settings, start_background_workers=False)
        with TestClient(app):
            # 既有 web 测试用 start_background_workers=False 跳过 worker；
            # PoolReaperJob 同样要遵守该开关，避免单元测试在临时数据库上拉起
            # 后台任务。
            self.assertFalse(hasattr(app.state, "pool_reaper_task")
                             and app.state.pool_reaper_task is not None)


def _wait_for_reaper_tick(
    audit_repo: PoolAuditLogRepository,
    *,
    timeout_seconds: float,
) -> list:
    """轮询审计表直至出现 ``reaper_tick`` 行或超时。

    ``asyncio.create_task`` 在 lifespan ``yield`` 之前立即返回，但首轮 tick 的
    ``asyncio.to_thread`` 调用需要切到 worker 线程；用 50ms 步长轮询给调度
    留出窗口。
    """

    import time as _time

    end_at = _time.monotonic() + timeout_seconds
    while True:
        rows = audit_repo.query(
            PoolAuditLogQuery(audit_action=PoolAuditAction.REAPER_TICK)
        )
        if rows:
            return rows
        if _time.monotonic() >= end_at:
            return rows
        _time.sleep(0.05)


if __name__ == "__main__":
    unittest.main()
