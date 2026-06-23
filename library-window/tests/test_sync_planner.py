"""``sync_planner`` 单元测试。

覆盖 design.md「数据流 4：Manual_Sync_Action 全流程」中 diff 算法在 Window 端
的实现：

- ``add``：服务端有、本地无的账号默认勾选 ``True``。
- ``replace``：两端 ``student_id`` 匹配但任一受管字段不一致默认勾选 ``True``；
  受管字段一致时不产出候选。
- ``remove``：本地标记 ``is_server_managed=True`` 但服务端清单已无对应
  ``student_id`` 时默认勾选 ``False``；本地未被服务端管理的账号即使服务端没
  有也不产出候选。
- 自动任务集合的差异对比为顺序无关；自动任务内部 ``custom_windows`` 子集
  也按顺序无关比较。
- 输入两端中重复 ``student_id`` 抛 ``ValueError``。
- ``compute_diff`` 不修改输入，纯函数。
"""

from __future__ import annotations

import unittest
from datetime import datetime, timezone

from wuyi_seat_bot.server_sync.active_pool_repository import (
    ActiveAccountDetail,
    AutomationTask,
    CustomWindow,
)
from wuyi_seat_bot.server_sync.sync_planner import (
    LocalAccountSummary,
    LocalAutomationTask,
    MANAGED_FIELDS,
    SyncCandidate,
    compute_diff,
)


# --------------------------------------------------------------------------- #
# Builders                                                                     #
# --------------------------------------------------------------------------- #


def _build_server_detail(
    *,
    account_id: int = 1,
    student_id: str,
    display_name: str = "",
    password: str = "pa$$w0rd",
    revision: int = 0,
    automation_tasks: list[AutomationTask] | None = None,
) -> ActiveAccountDetail:
    return ActiveAccountDetail(
        account_id=account_id,
        student_id=student_id,
        display_name=display_name,
        password=password,
        revision=revision,
        automation_tasks=list(automation_tasks or []),
        server_time=datetime(2026, 4, 26, 8, 0, tzinfo=timezone.utc),
    )


def _build_server_task(
    *,
    task_id: int = 901,
    room_name: str = "三层东区",
    seat_number: str = "A12",
    mode: str = "preferred",
    custom_windows: list[CustomWindow] | None = None,
    enabled: bool = True,
    revision: int = 7,
) -> AutomationTask:
    return AutomationTask(
        task_id=task_id,
        room_name=room_name,
        seat_number=seat_number,
        mode=mode,
        custom_windows=list(custom_windows or []),
        enabled=enabled,
        revision=revision,
        updated_at=datetime(2026, 4, 26, 7, 0, tzinfo=timezone.utc),
    )


def _build_local_summary(
    *,
    student_id: str,
    display_name: str = "",
    password: str = "pa$$w0rd",
    automation_tasks: tuple[LocalAutomationTask, ...] = (),
    is_server_managed: bool = False,
) -> LocalAccountSummary:
    return LocalAccountSummary(
        student_id=student_id,
        password=password,
        display_name=display_name,
        automation_tasks=automation_tasks,
        is_server_managed=is_server_managed,
    )


def _build_local_task(
    *,
    task_id: int = 901,
    room_name: str = "三层东区",
    seat_number: str = "A12",
    mode: str = "preferred",
    custom_windows: tuple[CustomWindow, ...] = (),
    enabled: bool = True,
) -> LocalAutomationTask:
    return LocalAutomationTask(
        task_id=task_id,
        room_name=room_name,
        seat_number=seat_number,
        mode=mode,
        custom_windows=custom_windows,
        enabled=enabled,
    )


# --------------------------------------------------------------------------- #
# add                                                                          #
# --------------------------------------------------------------------------- #


class AddCandidateTestCase(unittest.TestCase):
    def test_server_only_account_emits_add_with_default_true(self) -> None:
        srv = _build_server_detail(student_id="20231121130", display_name="张三")
        candidates = compute_diff([srv], [])

        self.assertEqual(len(candidates), 1)
        candidate = candidates[0]
        self.assertEqual(candidate.kind, "add")
        self.assertEqual(candidate.student_id, "20231121130")
        self.assertIs(candidate.server_payload, srv)
        self.assertIsNone(candidate.local_summary)
        self.assertTrue(candidate.default_checked)

    def test_empty_local_with_multiple_server_accounts(self) -> None:
        srv1 = _build_server_detail(account_id=1, student_id="2023A")
        srv2 = _build_server_detail(account_id=2, student_id="2023B")
        candidates = compute_diff([srv1, srv2], [])

        self.assertEqual({c.student_id for c in candidates}, {"2023A", "2023B"})
        self.assertTrue(all(c.kind == "add" for c in candidates))
        self.assertTrue(all(c.default_checked for c in candidates))


# --------------------------------------------------------------------------- #
# replace                                                                      #
# --------------------------------------------------------------------------- #


class ReplaceCandidateTestCase(unittest.TestCase):
    def test_password_mismatch_emits_replace_with_default_true(self) -> None:
        srv = _build_server_detail(student_id="2023A", password="server_pwd")
        local = _build_local_summary(
            student_id="2023A", password="local_pwd", is_server_managed=True
        )
        candidates = compute_diff([srv], [local])

        self.assertEqual(len(candidates), 1)
        c = candidates[0]
        self.assertEqual(c.kind, "replace")
        self.assertEqual(c.student_id, "2023A")
        self.assertIs(c.server_payload, srv)
        self.assertIs(c.local_summary, local)
        self.assertTrue(c.default_checked)

    def test_display_name_mismatch_emits_replace(self) -> None:
        srv = _build_server_detail(student_id="2023A", display_name="服务端备注")
        local = _build_local_summary(
            student_id="2023A", display_name="本地备注", is_server_managed=True
        )
        candidates = compute_diff([srv], [local])

        self.assertEqual([c.kind for c in candidates], ["replace"])

    def test_automation_task_count_mismatch_emits_replace(self) -> None:
        srv = _build_server_detail(
            student_id="2023A",
            automation_tasks=[_build_server_task(task_id=901)],
        )
        local = _build_local_summary(
            student_id="2023A",
            is_server_managed=True,
            automation_tasks=(),
        )
        candidates = compute_diff([srv], [local])

        self.assertEqual([c.kind for c in candidates], ["replace"])

    def test_automation_task_field_mismatch_emits_replace(self) -> None:
        srv = _build_server_detail(
            student_id="2023A",
            automation_tasks=[_build_server_task(task_id=901, seat_number="A12")],
        )
        local = _build_local_summary(
            student_id="2023A",
            is_server_managed=True,
            automation_tasks=(_build_local_task(task_id=901, seat_number="B07"),),
        )
        candidates = compute_diff([srv], [local])

        self.assertEqual([c.kind for c in candidates], ["replace"])

    def test_custom_windows_content_mismatch_emits_replace(self) -> None:
        srv = _build_server_detail(
            student_id="2023A",
            automation_tasks=[
                _build_server_task(
                    task_id=901,
                    custom_windows=[
                        CustomWindow(date="2026-04-27", start_hour=8, end_hour=12)
                    ],
                )
            ],
        )
        local = _build_local_summary(
            student_id="2023A",
            is_server_managed=True,
            automation_tasks=(
                _build_local_task(
                    task_id=901,
                    custom_windows=(
                        CustomWindow(date="2026-04-27", start_hour=14, end_hour=18),
                    ),
                ),
            ),
        )
        candidates = compute_diff([srv], [local])

        self.assertEqual([c.kind for c in candidates], ["replace"])

    def test_managed_fields_equal_emits_no_replace(self) -> None:
        windows = [CustomWindow(date="2026-04-27", start_hour=8, end_hour=12)]
        srv = _build_server_detail(
            student_id="2023A",
            display_name="张三",
            password="same_pwd",
            automation_tasks=[
                _build_server_task(task_id=901, custom_windows=windows)
            ],
        )
        local = _build_local_summary(
            student_id="2023A",
            display_name="张三",
            password="same_pwd",
            is_server_managed=True,
            automation_tasks=(
                _build_local_task(task_id=901, custom_windows=tuple(windows)),
            ),
        )
        self.assertEqual(compute_diff([srv], [local]), [])

    def test_replace_ignores_is_server_managed_flag(self) -> None:
        """两端 student_id 匹配且字段不一致时，``is_server_managed`` 不影响 replace 判定。

        哪怕本地标记 ``False``，只要服务端清单包含该 ``student_id``，就视为
        「准备接收服务端管理」，按字段差异产出 replace 候选；这与设计中
        ``replace`` 仅依赖字段差异、``remove`` 才依赖 ``is_server_managed``
        的语义一致。
        """

        srv = _build_server_detail(student_id="2023A", password="server")
        local = _build_local_summary(
            student_id="2023A", password="local", is_server_managed=False
        )
        candidates = compute_diff([srv], [local])

        self.assertEqual([c.kind for c in candidates], ["replace"])

    def test_automation_task_order_independent(self) -> None:
        """两端自动任务顺序不同但内容相同时不应产出 replace。"""

        srv = _build_server_detail(
            student_id="2023A",
            automation_tasks=[
                _build_server_task(task_id=901, seat_number="A12"),
                _build_server_task(task_id=902, seat_number="B07"),
            ],
        )
        local = _build_local_summary(
            student_id="2023A",
            is_server_managed=True,
            automation_tasks=(
                _build_local_task(task_id=902, seat_number="B07"),
                _build_local_task(task_id=901, seat_number="A12"),
            ),
        )
        self.assertEqual(compute_diff([srv], [local]), [])

    def test_custom_windows_order_independent(self) -> None:
        """同一任务内部 ``custom_windows`` 顺序不同但内容相同时不应产出 replace。"""

        windows_server = [
            CustomWindow(date="2026-04-27", start_hour=8, end_hour=12),
            CustomWindow(date="2026-04-28", start_hour=14, end_hour=18),
        ]
        windows_local = (
            CustomWindow(date="2026-04-28", start_hour=14, end_hour=18),
            CustomWindow(date="2026-04-27", start_hour=8, end_hour=12),
        )
        srv = _build_server_detail(
            student_id="2023A",
            automation_tasks=[
                _build_server_task(task_id=901, custom_windows=windows_server)
            ],
        )
        local = _build_local_summary(
            student_id="2023A",
            is_server_managed=True,
            automation_tasks=(
                _build_local_task(task_id=901, custom_windows=windows_local),
            ),
        )
        self.assertEqual(compute_diff([srv], [local]), [])


# --------------------------------------------------------------------------- #
# remove                                                                       #
# --------------------------------------------------------------------------- #


class RemoveCandidateTestCase(unittest.TestCase):
    def test_local_managed_only_emits_remove_with_default_false(self) -> None:
        local = _build_local_summary(student_id="2023A", is_server_managed=True)
        candidates = compute_diff([], [local])

        self.assertEqual(len(candidates), 1)
        c = candidates[0]
        self.assertEqual(c.kind, "remove")
        self.assertEqual(c.student_id, "2023A")
        self.assertIsNone(c.server_payload)
        self.assertIs(c.local_summary, local)
        self.assertFalse(c.default_checked)

    def test_local_unmanaged_only_emits_no_candidate(self) -> None:
        """本地从未被服务端管理的账号即使服务端没有也不应被「移除」。"""

        local = _build_local_summary(student_id="2023A", is_server_managed=False)
        self.assertEqual(compute_diff([], [local]), [])

    def test_remove_only_when_server_does_not_have_sid(self) -> None:
        """两端都有该 student_id 时永远不产出 remove 候选。"""

        srv = _build_server_detail(student_id="2023A")
        local = _build_local_summary(student_id="2023A", is_server_managed=True)
        candidates = compute_diff([srv], [local])
        self.assertNotIn("remove", {c.kind for c in candidates})


# --------------------------------------------------------------------------- #
# default_checked 总览                                                          #
# --------------------------------------------------------------------------- #


class DefaultCheckedTestCase(unittest.TestCase):
    def test_default_checked_matches_kind(self) -> None:
        """混合场景下：add/replace 默认 True，remove 默认 False。"""

        srv_new = _build_server_detail(account_id=1, student_id="NEW")
        srv_replace = _build_server_detail(
            account_id=2, student_id="REPL", password="server_pwd"
        )
        local_replace = _build_local_summary(
            student_id="REPL", password="local_pwd", is_server_managed=True
        )
        local_remove = _build_local_summary(
            student_id="GONE", is_server_managed=True
        )

        candidates = compute_diff(
            [srv_new, srv_replace], [local_replace, local_remove]
        )

        # 按 kind+sid 排序后：add/NEW, replace/REPL, remove/GONE
        kinds = [(c.kind, c.student_id, c.default_checked) for c in candidates]
        self.assertEqual(
            kinds,
            [
                ("add", "NEW", True),
                ("replace", "REPL", True),
                ("remove", "GONE", False),
            ],
        )


# --------------------------------------------------------------------------- #
# Validation                                                                   #
# --------------------------------------------------------------------------- #


class ValidationTestCase(unittest.TestCase):
    def test_duplicate_server_student_id_raises(self) -> None:
        srv1 = _build_server_detail(account_id=1, student_id="DUP")
        srv2 = _build_server_detail(account_id=2, student_id="DUP")
        with self.assertRaises(ValueError):
            compute_diff([srv1, srv2], [])

    def test_duplicate_local_student_id_raises(self) -> None:
        local1 = _build_local_summary(student_id="DUP", is_server_managed=True)
        local2 = _build_local_summary(student_id="DUP", is_server_managed=False)
        with self.assertRaises(ValueError):
            compute_diff([], [local1, local2])

    def test_managed_fields_constant_matches_design(self) -> None:
        self.assertEqual(
            MANAGED_FIELDS,
            frozenset(
                {
                    "student_id",
                    "password",
                    "display_name",
                    "automation_tasks",
                    "custom_windows",
                }
            ),
        )


# --------------------------------------------------------------------------- #
# Purity                                                                        #
# --------------------------------------------------------------------------- #


class PurityTestCase(unittest.TestCase):
    def test_does_not_mutate_inputs(self) -> None:
        srv = _build_server_detail(
            student_id="2023A",
            automation_tasks=[_build_server_task(task_id=901)],
        )
        local = _build_local_summary(
            student_id="2023B",
            is_server_managed=True,
            automation_tasks=(_build_local_task(task_id=902),),
        )
        server_list = [srv]
        local_list = [local]

        compute_diff(server_list, local_list)

        # 引用未被替换且字段未变
        self.assertIs(server_list[0], srv)
        self.assertIs(local_list[0], local)
        self.assertEqual(srv.student_id, "2023A")
        self.assertEqual(local.student_id, "2023B")


if __name__ == "__main__":
    unittest.main()
