"""Manual_Sync_Action 差异应用器。

本模块承担「把 :func:`sync_planner.compute_diff` 产出的 ``SyncCandidate``
列表按用户在 Sync_Coverage_Confirmation 弹窗中给出的 ``Sync_Selection``
应用到 Local_Account_Store」这一项原子写入职责。

设计依据：

- Requirement 13.6 / 13.13：仅对 ``selection[sid] == True`` 的条目执行写入；
  ``selection[sid] == False`` 的条目在 Local_Account_Store 中所有字段保持
  调用前完全一致，包括非受管字段（本地备注 / 标签 / UI 偏好折叠状态 / 排序
  顺序 / 用户自添加的扩展 key / 本地 ``note`` 字段）。
- Requirement 13.16 / 13.18：``selection`` 为空 dict 或全部 ``False`` 时为
  noop（既不写入也不删除）；``kind='remove'`` 仅取消 Local_Account_Store 上
  「受服务端管理活跃账号」标记，不物理删除 ``config.json`` 对应账号文件 / 行。
- design 「Sync_Coverage_Confirmation 数据结构与字段口径」：受管字段集合为
  ``{student_id, password, display_name, automation_tasks, custom_windows}``。

写入策略：

- 写入 ``config.json`` 时使用 ``.tmp`` + :meth:`pathlib.Path.replace` 原子替换，
  与 :mod:`wuyi_seat_bot.service_manager` / :mod:`wuyi_seat_bot.web_server`
  现有写入风格一致；并在替换前把当前文件复制到 ``config.json.bak``，避免
  半写。
- 不依赖 :mod:`wuyi_seat_bot.config` 中的 ``save_account_config`` 高阶接口，
  以便保留账号 dict 中所有非受管字段（含用户自添加的扩展 key）原样不动。

调用约束：

- 本模块 **不** 修改 :class:`sync_planner.SyncCandidate` 中的 ``server_payload``
  与 ``local_summary``；只读取它们计算最终写入内容。
- 本模块 **不** 触达 ``automation_plans.json`` 等其它运行时缓存；服务端推送
  的 ``automation_tasks`` 以 ``server_managed_automation_tasks`` 扩展字段
  形式落入对应账号 dict，由上层 ViewModel / 调度器在后续映射到本地执行计划。
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from wuyi_seat_bot.server_sync.active_pool_repository import (
    ActiveAccountDetail,
    AutomationTask,
    CustomWindow,
)
from wuyi_seat_bot.server_sync.sync_planner import SyncCandidate


logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------- #
# 默认值常量                                                                    #
# --------------------------------------------------------------------------- #

# 与 :mod:`wuyi_seat_bot.config` 中的 ``DEFAULT_LOGIN_URL`` 保持同一字面量，
# 但本模块刻意不导入 ``config.py``，避免与未来重构产生强耦合。
_DEFAULT_LOGIN_URL: str = (
    "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
)
_DEFAULT_SEAT_URLS: tuple[str, ...] = (_DEFAULT_LOGIN_URL,)


# --------------------------------------------------------------------------- #
# Data classes                                                                #
# --------------------------------------------------------------------------- #


@dataclass(frozen=True, slots=True)
class ApplyResult:
    """:meth:`SyncApplier.apply` 的返回值，供 UI 渲染「同步成功」提示。

    Attributes:
        added: 实际应用的 ``add`` 候选数。
        replaced: 实际应用的 ``replace`` 候选数。
        removed: 实际应用的 ``remove`` 候选数（含本地原本就不存在该
            ``student_id`` 时的「无操作 remove」，记入计数以便 UI 反馈）。
    """

    added: int
    replaced: int
    removed: int

    @property
    def total(self) -> int:
        """所有应用的候选总数。"""
        return self.added + self.replaced + self.removed


# --------------------------------------------------------------------------- #
# Sync applier                                                                 #
# --------------------------------------------------------------------------- #


class SyncApplier:
    """把 :class:`SyncCandidate` 列表按用户勾选写入 ``config.json``。

    线程不安全：调用方需保证同一时刻只有单个线程执行 :meth:`apply`，避免
    与 UI / 调度器对 ``config.json`` 的并发写入互相覆盖。
    """

    def __init__(self, config_path: str | Path) -> None:
        self.config_path = Path(config_path)

    # ------------------------------------------------------------------ #
    # 公共 API                                                            #
    # ------------------------------------------------------------------ #

    def apply(
        self,
        candidates: list[SyncCandidate],
        selection: dict[str, bool],
    ) -> ApplyResult:
        """执行差异应用。

        Args:
            candidates: :func:`sync_planner.compute_diff` 产出的候选列表。
            selection: 用户在 Sync_Coverage_Confirmation 弹窗中给出的勾选
                状态映射，``student_id -> bool``。仅对 ``selection[sid] is True``
                的条目执行写入；其它条目保持 Local_Account_Store 中所有字段
                完全一致。

        Returns:
            :class:`ApplyResult`，含 ``added`` / ``replaced`` / ``removed``
            三类操作的实际应用条数。

        Raises:
            ValueError: ``server_payload`` 缺失或 ``kind`` 取值非法时抛出。
            OSError: 写入 ``config.json`` 失败时由底层抛出。
        """

        applicable = [
            candidate
            for candidate in candidates
            if selection.get(candidate.student_id) is True
        ]
        # selection 为空 dict、全部 false、或没有任何匹配上的候选 → noop。
        if not applicable:
            logger.debug("sync_applier: 无勾选条目，noop")
            return ApplyResult(added=0, replaced=0, removed=0)

        payload = self._load_payload()
        accounts = _ensure_accounts_list(payload)

        added = replaced = removed = 0
        for candidate in applicable:
            if candidate.kind == "add":
                if candidate.server_payload is None:
                    raise ValueError(
                        f"add 候选 student_id={candidate.student_id!r} 缺少 server_payload"
                    )
                self._apply_add(accounts, candidate.server_payload)
                added += 1
            elif candidate.kind == "replace":
                if candidate.server_payload is None:
                    raise ValueError(
                        f"replace 候选 student_id={candidate.student_id!r} 缺少 server_payload"
                    )
                self._apply_replace(
                    accounts,
                    candidate.student_id,
                    candidate.server_payload,
                )
                replaced += 1
            elif candidate.kind == "remove":
                self._apply_remove(accounts, candidate.student_id)
                removed += 1
            else:
                raise ValueError(f"未知的 SyncCandidate.kind: {candidate.kind!r}")

        payload["accounts"] = accounts
        self._write_payload_atomically(payload)
        logger.info(
            "sync_applier: 已应用同步 added=%d replaced=%d removed=%d",
            added,
            replaced,
            removed,
        )
        return ApplyResult(added=added, replaced=replaced, removed=removed)

    # ------------------------------------------------------------------ #
    # add / replace / remove                                              #
    # ------------------------------------------------------------------ #

    def _apply_add(
        self,
        accounts: list[dict[str, Any]],
        server_payload: ActiveAccountDetail,
    ) -> None:
        new_name = self._allocate_account_name(
            accounts,
            preferred=server_payload.display_name or server_payload.student_id,
            student_id=server_payload.student_id,
        )
        accounts.append(
            {
                # name 是 config.json 里 accounts[*] 的本地唯一键；
                # 与 student_id 不同，name 用于 UI 列表展示 / 命令行 --account。
                "name": new_name,
                # 受管字段：student_id / password / display_name。
                "student_id": server_payload.student_id,
                "password": server_payload.password,
                "display_name": server_payload.display_name,
                # 「受服务端管理活跃账号」标记，仅由 sync_applier 维护。
                "is_server_managed": True,
                # 受管字段：服务端推送的自动任务集合（含每个任务的 custom_windows）。
                "server_managed_automation_tasks": _serialize_server_tasks(
                    server_payload.automation_tasks
                ),
                # 非受管字段：保留与既有账号一致的默认值；用户后续可在
                # 设置页 / 命令行手动覆盖，sync_applier 在 replace / remove
                # 时不再触碰这些字段。
                "login_url": _DEFAULT_LOGIN_URL,
                "state_file": _build_default_state_file(server_payload.student_id),
                "seat_urls": list(_DEFAULT_SEAT_URLS),
                "preferred_room_names": [],
                "preferred_seat_numbers": [],
            }
        )

    def _apply_replace(
        self,
        accounts: list[dict[str, Any]],
        student_id: str,
        server_payload: ActiveAccountDetail,
    ) -> None:
        index = _find_account_index_by_student_id(accounts, student_id)
        if index < 0:
            # diff 阶段把它放进 replace 候选，但 apply 时本地已无对应账号；
            # 退化为「等价的 add」以保证 selection 勾选 True 的条目被忠实
            # 同步到本地。
            self._apply_add(accounts, server_payload)
            return
        account = accounts[index]
        # 仅修改受管字段；其它字段（login_url / state_file / seat_urls /
        # preferred_room_names / preferred_seat_numbers / 本地备注 / UI
        # 偏好 / 用户自添加的扩展 key）保持调用前完全一致。
        account["student_id"] = server_payload.student_id
        account["password"] = server_payload.password
        account["display_name"] = server_payload.display_name
        account["is_server_managed"] = True
        account["server_managed_automation_tasks"] = _serialize_server_tasks(
            server_payload.automation_tasks
        )

    def _apply_remove(
        self,
        accounts: list[dict[str, Any]],
        student_id: str,
    ) -> None:
        index = _find_account_index_by_student_id(accounts, student_id)
        if index < 0:
            # 本地已无该 student_id：保持 noop（不抛异常）。
            return
        account = accounts[index]
        # 仅取消「受服务端管理活跃账号」标记；不物理删除该账号 dict、
        # 不清空已经下发到本地的 server_managed_automation_tasks，让用户
        # 仍可看到最近一次同步的任务快照（Requirement 13.18）。
        account["is_server_managed"] = False

    # ------------------------------------------------------------------ #
    # 辅助：账号名分配                                                     #
    # ------------------------------------------------------------------ #

    def _allocate_account_name(
        self,
        accounts: list[dict[str, Any]],
        *,
        preferred: str,
        student_id: str,
    ) -> str:
        """为新增账号分配 ``name`` 字段，确保在 ``accounts`` 列表内唯一。"""

        existing = {
            str(account.get("name") or "")
            for account in accounts
            if isinstance(account, dict)
        }
        candidate = (preferred or student_id).strip() or student_id
        if candidate not in existing:
            return candidate
        for suffix in range(2, 1000):
            attempt = f"{candidate}_{suffix}"
            if attempt not in existing:
                return attempt
        # 极端情况下名字空间用尽；显式抛错而非静默覆盖现有账号。
        raise ValueError(f"无法为 student_id={student_id!r} 分配唯一的 account name")

    # ------------------------------------------------------------------ #
    # 辅助：I/O                                                           #
    # ------------------------------------------------------------------ #

    def _load_payload(self) -> dict[str, Any]:
        """读取 ``config.json``。

        - 文件不存在或为空：返回最小 payload（空 ``accounts`` 列表），后续
          ``add`` 候选可在其上追加。
        - 顶层不是 JSON 对象：抛 ``ValueError``，让上层显式发现配置损坏。
        """

        if not self.config_path.exists():
            return {"accounts": [], "default_account": ""}
        text = self.config_path.read_text(encoding="utf-8")
        if not text.strip():
            return {"accounts": [], "default_account": ""}
        data = json.loads(text)
        if not isinstance(data, dict):
            raise ValueError("config.json 顶层必须是 JSON 对象")
        return data

    def _write_payload_atomically(self, payload: dict[str, Any]) -> None:
        """原子写入 ``config.json``，并保留一份 ``.bak``。

        步骤：

        1. 把新内容写入 ``config.json.tmp``。
        2. 若 ``config.json`` 已存在，则把当前内容复制到 ``config.json.bak``。
        3. ``Path.replace`` 把 ``config.json.tmp`` 原子替换为 ``config.json``。

        步骤 3 的原子性由操作系统保证（POSIX ``rename(2)`` / Windows
        ``MoveFileExW``）；步骤 2 失败仅打 WARN，不阻断主流程。
        """

        path = self.config_path
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = path.with_name(path.name + ".tmp")
        bak_path = path.with_name(path.name + ".bak")
        text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
        tmp_path.write_text(text, encoding="utf-8")
        if path.exists():
            try:
                bak_path.write_bytes(path.read_bytes())
            except OSError as exc:
                logger.warning("写入 %s 失败：%s", bak_path, exc)
        tmp_path.replace(path)


# --------------------------------------------------------------------------- #
# 模块级辅助                                                                    #
# --------------------------------------------------------------------------- #


def _ensure_accounts_list(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """从 payload 中取出 ``accounts`` 列表；缺失或类型错误时返回空列表。

    注意：本函数 **不** 把单账号 legacy 格式（顶层带 ``student_id`` / ``password``
    等字段）转换为多账号格式；sync_applier 只处理已经具备 ``accounts`` 数组
    的现代格式，避免在升级期意外重写用户既有 legacy 文件。
    """

    accounts = payload.get("accounts")
    if isinstance(accounts, list):
        # 用 list 拷贝避免对原 payload 的子结构产生别名修改风险。
        return [
            account if isinstance(account, dict) else {}
            for account in accounts
        ]
    return []


def _find_account_index_by_student_id(
    accounts: list[dict[str, Any]], student_id: str
) -> int:
    """按 ``student_id`` 在 ``accounts`` 中定位条目下标；找不到时返回 -1。"""

    for index, account in enumerate(accounts):
        if not isinstance(account, dict):
            continue
        if account.get("student_id") == student_id:
            return index
    return -1


def _serialize_server_tasks(
    tasks: Iterable[AutomationTask],
) -> list[dict[str, Any]]:
    """把服务端 :class:`AutomationTask` 序列序列化为 JSON 兼容的 dict 列表。"""

    return [
        {
            "task_id": task.task_id,
            "room_name": task.room_name,
            "seat_number": task.seat_number,
            "mode": task.mode,
            "custom_windows": [_serialize_custom_window(w) for w in task.custom_windows],
            "enabled": bool(task.enabled),
            "revision": int(task.revision),
        }
        for task in tasks
    ]


def _serialize_custom_window(window: CustomWindow) -> dict[str, Any]:
    return {
        "date": window.date,
        "start_hour": int(window.start_hour),
        "end_hour": int(window.end_hour),
    }


def _build_default_state_file(student_id: str) -> str:
    """按 student_id 派生默认 ``state_file`` 路径。

    与 :func:`wuyi_seat_bot.config._build_default_state_file` 等价，但
    显式复写以保持本模块独立可测。
    """

    slug = re.sub(r"[^a-zA-Z0-9_-]+", "-", student_id.strip()).strip("-").lower()
    if not slug:
        slug = "account"
    return f"runtime/auth-{slug}.json"


__all__ = [
    "ApplyResult",
    "SyncApplier",
]
