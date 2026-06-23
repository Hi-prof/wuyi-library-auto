"""号池领域常量。

本模块集中存放号池相关的容量与时间常量，供 ``database.py`` 启动迁移钩子、
``services/account_pool_service.py`` 业务校验、``scheduler/pool_reaper_job.py``
到期回收复用，避免常量值散落造成不一致。

设计文档对应小节：design.md「Account_Pool_Service」与「Data Models」。
"""

from __future__ import annotations

from datetime import timedelta


POOL_CAPACITY: int = 100
"""号池总容量（含 active / suspended / idle 三池），软上限。

启动迁移阶段超过此值仅打 WARN（含 ``pool_capacity_exceeded`` 字段），不阻断
启动；服务层在新增 / 批量导入 / 跨池迁移路径上做硬校验。
"""


ACTIVE_POOL_CAPACITY: int | None = None
"""Active_Pool 单池上限；``None`` 表示不设上限（Requirement 2-Q2 默认值）。"""


SUSPENSION_TTL: timedelta = timedelta(hours=168)
"""Suspended_Pool 默认 7 天 (168 小时) 暂停 TTL（Requirement 3-Q2）。"""


__all__ = ["POOL_CAPACITY", "ACTIVE_POOL_CAPACITY", "SUSPENSION_TTL"]
