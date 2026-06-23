"""``sync_applier`` 单元测试。

覆盖 spec account-pool-tri-sync 任务 11.11 的关键行为：

- ``selection`` 为空 dict / 全部 false → noop（不写入也不删除，保持 config.json 字节级不变）。
- ``selection[sid] == True`` 时 ``add`` / ``replace`` / ``remove`` 三类候选生效。
- ``selection[sid] == False`` 的条目在 Local_Account_Store 中所有字段保持调用前完全一致。
- 仅修改受管字段集合（``student_id`` / ``password`` / ``display_name`` /
  ``server_managed_automation_tasks`` / ``is_server_managed``）；非受管字段
  （本地备注 / 本地 ``note`` / UI 偏好 / 用户自添加扩展 key / 排序顺序）不变。
- ``kind == 'remove'`` 仅取消 ``is_server_managed`` 标记，不物理删除账号。
- 写入使用 ``.tmp`` + ``.bak`` 原子替换策略。
"""

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from wuyi_seat_bot.server_sync import (
    ActiveAccountDetail,
    AutomationTask,
    CustomWindow,
    LocalAccountSummary,
    LocalAutomationTask,
    SyncApplier,
    SyncCandidate,
    compute_diff,
)


# --------------------------------------------------------------------------- #
# 辅助构造函数                                                                  #
# --------------------------------------------------------------------------- #


def _build_server_detail(
    *,
    account_id: int,
    student_id: str,
    display_name: str = "",
    password: str = "secret",
    revision: int = 1,
    automation_tasks: tuple[AutomationTask, ...] = (),
) -> ActiveAccountDetail:
    return ActiveAccountDetail(
        account_id=account_id,
        student_id=student_id,
        display_name=display_name,
        password=password,
        revision=revision,
        automation_tasks=list(automation_tasks),
        server_time=datetime(2026, 4, 26, 8, 0, 0, tzinfo=timezone.utc),
    )


def _build_local_summary(
    *,
    student_id: str,
    password: str = "secret",
    display_name: str = "",
    automation_tasks: tuple[LocalAutomationTask, ...] = (),
    is_server_managed: bool = True,
) -> LocalAccountSummary:
    return LocalAccountSummary(
        student_id=student_id,
        password=password,
        display_name=display_name,
        automation_tasks=automation_tasks,
        is_server_managed=is_server_managed,
    )


def _build_automation_task(
    *,
    task_id: int = 1,
    room_name: str = "三层东区",
    seat_number: str = "A12",
    mode: str = "preferred",
    custom_windows: tuple[CustomWindow, ...] = (),
    enabled: bool = True,
    revision: int = 0,
) -> AutomationTask:
    return AutomationTask(
        task_id=task_id,
        room_name=room_name,
        seat_number=seat_number,
        mode=mode,
        custom_windows=list(custom_windows),
        enabled=enabled,
        revision=revision,
        updated_at=None,
    )


def _write_config(path: Path, payload: dict) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def _read_config(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


# --------------------------------------------------------------------------- #
# selection 为空 / 全 false → noop                                              #
# --------------------------------------------------------------------------- #


class SelectionEmptyOrFalseNoopTestCase(unittest.TestCase):
    def test_empty_selection_does_not_modify_config(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            initial_payload = {
                "default_account": "alice",
                "accounts": [
                    {
                        "name": "alice",
                        "student_id": "20231121130",
                        "password": "old-pwd",
                        "display_name": "Alice",
                        "login_url": "https://example.com/login",
                        "state_file": "runtime/auth-alice.json",
                        "seat_urls": ["https://example.com/seat/166"],
                        "preferred_room_names": ["阅览室"],
                        "preferred_seat_numbers": ["8"],
                        "note": "本地备注，不该被覆盖",
                        "is_server_managed": True,
                    }
                ],
            }
            _write_config(config_path, initial_payload)
            original_bytes = config_path.read_bytes()

            applier = SyncApplier(config_path)
            server_detail = _build_server_detail(
                account_id=17,
                student_id="20231121200",
                display_name="新增账号",
            )
            candidates = [
                SyncCandidate(
                    kind="add",
                    student_id="20231121200",
                    server_payload=server_detail,
                    local_summary=None,
                    default_checked=True,
                )
            ]

            result = applier.apply(candidates, selection={})

            self.assertEqual(result.added, 0)
            self.assertEqual(result.replaced, 0)
            self.assertEqual(result.removed, 0)
            # 字节级别不变。
            self.assertEqual(config_path.read_bytes(), original_bytes)
            # 不该写入 .tmp / .bak 副作用文件。
            self.assertFalse(config_path.with_name("config.json.tmp").exists())
            self.assertFalse(config_path.with_name("config.json.bak").exists())

    def test_all_false_selection_does_not_modify_config(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [
                        {
                            "name": "alice",
                            "student_id": "20231121130",
                            "password": "old-pwd",
                            "display_name": "Alice",
                            "is_server_managed": True,
                        }
                    ]
                },
            )
            original_bytes = config_path.read_bytes()

            server_detail = _build_server_detail(
                account_id=17,
                student_id="20231121200",
                display_name="新增账号",
            )
            candidates = [
                SyncCandidate(
                    kind="add",
                    student_id="20231121200",
                    server_payload=server_detail,
                    local_summary=None,
                    default_checked=True,
                ),
                SyncCandidate(
                    kind="remove",
                    student_id="20231121130",
                    server_payload=None,
                    local_summary=_build_local_summary(student_id="20231121130"),
                    default_checked=False,
                ),
            ]

            applier = SyncApplier(config_path)
            result = applier.apply(
                candidates,
                selection={"20231121200": False, "20231121130": False},
            )

            self.assertEqual(result.total, 0)
            self.assertEqual(config_path.read_bytes(), original_bytes)


# --------------------------------------------------------------------------- #
# add / replace / remove 行为                                                   #
# --------------------------------------------------------------------------- #


class ApplyAddTestCase(unittest.TestCase):
    def test_add_appends_new_account_with_managed_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "default_account": "alice",
                    "accounts": [
                        {
                            "name": "alice",
                            "student_id": "20231121130",
                            "password": "alice-pwd",
                            "display_name": "Alice",
                            "is_server_managed": True,
                        }
                    ],
                },
            )

            server_detail = _build_server_detail(
                account_id=17,
                student_id="20231121200",
                display_name="Bob",
                password="bob-pwd",
                automation_tasks=(
                    _build_automation_task(
                        task_id=901,
                        room_name="三层东区",
                        seat_number="A12",
                        custom_windows=(
                            CustomWindow(
                                date="2026-04-27", start_hour=8, end_hour=12
                            ),
                        ),
                    ),
                ),
            )
            candidates = [
                SyncCandidate(
                    kind="add",
                    student_id="20231121200",
                    server_payload=server_detail,
                    local_summary=None,
                    default_checked=True,
                )
            ]

            applier = SyncApplier(config_path)
            result = applier.apply(candidates, selection={"20231121200": True})

            self.assertEqual(result.added, 1)
            self.assertEqual(result.replaced, 0)
            self.assertEqual(result.removed, 0)

            payload = _read_config(config_path)
            self.assertEqual(len(payload["accounts"]), 2)
            new_account = next(
                a for a in payload["accounts"] if a["student_id"] == "20231121200"
            )
            self.assertEqual(new_account["password"], "bob-pwd")
            self.assertEqual(new_account["display_name"], "Bob")
            self.assertTrue(new_account["is_server_managed"])
            self.assertEqual(len(new_account["server_managed_automation_tasks"]), 1)
            task_payload = new_account["server_managed_automation_tasks"][0]
            self.assertEqual(task_payload["task_id"], 901)
            self.assertEqual(task_payload["room_name"], "三层东区")
            self.assertEqual(task_payload["custom_windows"][0]["date"], "2026-04-27")
            # default_account 不变。
            self.assertEqual(payload["default_account"], "alice")

    def test_add_allocates_unique_name_when_display_name_collides(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [
                        {
                            "name": "Alice",
                            "student_id": "20231121130",
                            "password": "x",
                            "display_name": "Alice",
                            "is_server_managed": True,
                        }
                    ]
                },
            )
            server_detail = _build_server_detail(
                account_id=17,
                student_id="20231121200",
                display_name="Alice",
            )
            applier = SyncApplier(config_path)
            applier.apply(
                [
                    SyncCandidate(
                        kind="add",
                        student_id="20231121200",
                        server_payload=server_detail,
                        local_summary=None,
                        default_checked=True,
                    )
                ],
                selection={"20231121200": True},
            )

            payload = _read_config(config_path)
            names = {a["name"] for a in payload["accounts"]}
            self.assertIn("Alice", names)
            # 第二个 "Alice" 应该被分配新名字 "Alice_2"。
            self.assertIn("Alice_2", names)


class ApplyReplaceTestCase(unittest.TestCase):
    def test_replace_overwrites_only_managed_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "default_account": "alice",
                    "accounts": [
                        {
                            "name": "alice-local-name",
                            "student_id": "20231121130",
                            "password": "old-pwd",
                            "display_name": "旧备注",
                            "login_url": "https://custom.example/login",
                            "state_file": "runtime/auth-alice.json",
                            "seat_urls": ["https://custom.example/seat/1"],
                            "preferred_room_names": ["旧偏好"],
                            "preferred_seat_numbers": ["8"],
                            "note": "用户本地备注",
                            "ui_collapsed": True,
                            "custom_extension_key": "用户自添加",
                            "is_server_managed": False,
                        }
                    ],
                },
            )
            server_detail = _build_server_detail(
                account_id=17,
                student_id="20231121130",
                display_name="新备注",
                password="new-pwd",
                automation_tasks=(
                    _build_automation_task(task_id=42, seat_number="B12"),
                ),
            )
            local_summary = _build_local_summary(
                student_id="20231121130",
                password="old-pwd",
                display_name="旧备注",
            )
            candidates = [
                SyncCandidate(
                    kind="replace",
                    student_id="20231121130",
                    server_payload=server_detail,
                    local_summary=local_summary,
                    default_checked=True,
                )
            ]

            applier = SyncApplier(config_path)
            result = applier.apply(candidates, selection={"20231121130": True})

            self.assertEqual(result.replaced, 1)
            payload = _read_config(config_path)
            account = payload["accounts"][0]
            # 受管字段被覆盖。
            self.assertEqual(account["password"], "new-pwd")
            self.assertEqual(account["display_name"], "新备注")
            self.assertTrue(account["is_server_managed"])
            self.assertEqual(
                account["server_managed_automation_tasks"][0]["seat_number"], "B12"
            )
            # 非受管字段保持不变。
            self.assertEqual(account["name"], "alice-local-name")
            self.assertEqual(account["login_url"], "https://custom.example/login")
            self.assertEqual(account["state_file"], "runtime/auth-alice.json")
            self.assertEqual(account["seat_urls"], ["https://custom.example/seat/1"])
            self.assertEqual(account["preferred_room_names"], ["旧偏好"])
            self.assertEqual(account["preferred_seat_numbers"], ["8"])
            self.assertEqual(account["note"], "用户本地备注")
            self.assertTrue(account["ui_collapsed"])
            self.assertEqual(account["custom_extension_key"], "用户自添加")
            self.assertEqual(payload["default_account"], "alice")


class ApplyRemoveTestCase(unittest.TestCase):
    def test_remove_only_clears_server_managed_flag(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "default_account": "alice",
                    "accounts": [
                        {
                            "name": "alice",
                            "student_id": "20231121130",
                            "password": "alice-pwd",
                            "display_name": "Alice",
                            "is_server_managed": True,
                            "server_managed_automation_tasks": [
                                {"task_id": 1, "room_name": "X"}
                            ],
                            "note": "本地备注",
                        }
                    ],
                },
            )
            local_summary = _build_local_summary(student_id="20231121130")
            candidates = [
                SyncCandidate(
                    kind="remove",
                    student_id="20231121130",
                    server_payload=None,
                    local_summary=local_summary,
                    default_checked=False,
                )
            ]

            applier = SyncApplier(config_path)
            result = applier.apply(candidates, selection={"20231121130": True})

            self.assertEqual(result.removed, 1)
            payload = _read_config(config_path)
            self.assertEqual(len(payload["accounts"]), 1)
            account = payload["accounts"][0]
            # 仅取消 is_server_managed 标记。
            self.assertFalse(account["is_server_managed"])
            # 其它字段（包含密码、display_name、最近一次同步的任务快照、本地备注）保留。
            self.assertEqual(account["password"], "alice-pwd")
            self.assertEqual(account["display_name"], "Alice")
            self.assertEqual(account["note"], "本地备注")
            self.assertEqual(
                account["server_managed_automation_tasks"], [{"task_id": 1, "room_name": "X"}]
            )

    def test_remove_for_nonexistent_local_account_is_noop(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [
                        {
                            "name": "alice",
                            "student_id": "20231121130",
                            "password": "x",
                            "is_server_managed": True,
                        }
                    ]
                },
            )
            applier = SyncApplier(config_path)
            local_summary = _build_local_summary(student_id="20231121200")
            result = applier.apply(
                [
                    SyncCandidate(
                        kind="remove",
                        student_id="20231121200",
                        server_payload=None,
                        local_summary=local_summary,
                        default_checked=False,
                    )
                ],
                selection={"20231121200": True},
            )
            self.assertEqual(result.removed, 1)
            payload = _read_config(config_path)
            # 不应物理删除已有的 alice。
            self.assertEqual(len(payload["accounts"]), 1)
            self.assertEqual(payload["accounts"][0]["student_id"], "20231121130")


# --------------------------------------------------------------------------- #
# 部分勾选：未选中的条目保持完全一致                                              #
# --------------------------------------------------------------------------- #


class PartialSelectionTestCase(unittest.TestCase):
    def test_unselected_entries_remain_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "default_account": "alice",
                    "accounts": [
                        {
                            "name": "alice",
                            "student_id": "20231121130",
                            "password": "old-alice-pwd",
                            "display_name": "Alice",
                            "note": "alice 本地备注",
                            "is_server_managed": True,
                        },
                        {
                            "name": "carol",
                            "student_id": "20231121300",
                            "password": "carol-pwd",
                            "display_name": "Carol",
                            "note": "carol 本地备注",
                            "is_server_managed": True,
                        },
                    ],
                },
            )

            # 服务端：alice 替换、bob 新增、carol 不再出现（应在 remove 候选中）。
            server_alice = _build_server_detail(
                account_id=1,
                student_id="20231121130",
                display_name="Alice 新备注",
                password="new-alice-pwd",
            )
            server_bob = _build_server_detail(
                account_id=2,
                student_id="20231121200",
                display_name="Bob",
                password="bob-pwd",
            )
            local_alice = _build_local_summary(
                student_id="20231121130",
                password="old-alice-pwd",
                display_name="Alice",
            )
            local_carol = _build_local_summary(
                student_id="20231121300",
                password="carol-pwd",
                display_name="Carol",
            )
            candidates = [
                SyncCandidate(
                    kind="add",
                    student_id="20231121200",
                    server_payload=server_bob,
                    local_summary=None,
                    default_checked=True,
                ),
                SyncCandidate(
                    kind="replace",
                    student_id="20231121130",
                    server_payload=server_alice,
                    local_summary=local_alice,
                    default_checked=True,
                ),
                SyncCandidate(
                    kind="remove",
                    student_id="20231121300",
                    server_payload=None,
                    local_summary=local_carol,
                    default_checked=False,
                ),
            ]

            applier = SyncApplier(config_path)
            # 只勾选 alice 替换，其它两条不勾选。
            result = applier.apply(
                candidates,
                selection={
                    "20231121130": True,
                    "20231121200": False,
                    "20231121300": False,
                },
            )

            self.assertEqual(result.added, 0)
            self.assertEqual(result.replaced, 1)
            self.assertEqual(result.removed, 0)

            payload = _read_config(config_path)
            self.assertEqual(len(payload["accounts"]), 2)
            alice = next(
                a for a in payload["accounts"] if a["student_id"] == "20231121130"
            )
            carol = next(
                a for a in payload["accounts"] if a["student_id"] == "20231121300"
            )
            # alice 受管字段被覆盖。
            self.assertEqual(alice["password"], "new-alice-pwd")
            self.assertEqual(alice["display_name"], "Alice 新备注")
            self.assertEqual(alice["note"], "alice 本地备注")
            # carol 完全不变（未勾选）。
            self.assertEqual(carol["password"], "carol-pwd")
            self.assertEqual(carol["display_name"], "Carol")
            self.assertEqual(carol["note"], "carol 本地备注")
            self.assertTrue(carol["is_server_managed"])


# --------------------------------------------------------------------------- #
# 端到端：compute_diff + apply 配合                                              #
# --------------------------------------------------------------------------- #


class IntegrationWithComputeDiffTestCase(unittest.TestCase):
    def test_compute_diff_then_apply_full_selection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "default_account": "alice",
                    "accounts": [
                        {
                            "name": "alice",
                            "student_id": "20231121130",
                            "password": "alice-pwd-old",
                            "display_name": "Alice",
                            "is_server_managed": True,
                        },
                        {
                            "name": "carol",
                            "student_id": "20231121300",
                            "password": "carol-pwd",
                            "display_name": "Carol",
                            "is_server_managed": True,
                        },
                    ],
                },
            )

            server_alice = _build_server_detail(
                account_id=1,
                student_id="20231121130",
                display_name="Alice",
                password="alice-pwd-new",
            )
            server_bob = _build_server_detail(
                account_id=2,
                student_id="20231121200",
                display_name="Bob",
                password="bob-pwd",
            )
            local_alice = LocalAccountSummary(
                student_id="20231121130",
                password="alice-pwd-old",
                display_name="Alice",
                automation_tasks=(),
                is_server_managed=True,
            )
            local_carol = LocalAccountSummary(
                student_id="20231121300",
                password="carol-pwd",
                display_name="Carol",
                automation_tasks=(),
                is_server_managed=True,
            )
            candidates = compute_diff(
                server_snapshot=[server_alice, server_bob],
                local_store=[local_alice, local_carol],
            )

            kinds = sorted(c.kind for c in candidates)
            self.assertEqual(kinds, ["add", "remove", "replace"])

            applier = SyncApplier(config_path)
            result = applier.apply(
                candidates,
                selection={c.student_id: True for c in candidates},
            )

            self.assertEqual(result.added, 1)
            self.assertEqual(result.replaced, 1)
            self.assertEqual(result.removed, 1)

            payload = _read_config(config_path)
            alice = next(a for a in payload["accounts"] if a["student_id"] == "20231121130")
            bob = next(a for a in payload["accounts"] if a["student_id"] == "20231121200")
            carol = next(a for a in payload["accounts"] if a["student_id"] == "20231121300")
            self.assertEqual(alice["password"], "alice-pwd-new")
            self.assertEqual(bob["password"], "bob-pwd")
            self.assertFalse(carol["is_server_managed"])
            # 物理记录依然存在（13.18：不删除本地条目）。
            self.assertEqual(carol["password"], "carol-pwd")


# --------------------------------------------------------------------------- #
# 原子写入 + 备份                                                                #
# --------------------------------------------------------------------------- #


class AtomicWriteTestCase(unittest.TestCase):
    def test_apply_writes_backup_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(
                config_path,
                {
                    "accounts": [
                        {
                            "name": "alice",
                            "student_id": "20231121130",
                            "password": "old",
                            "display_name": "Alice",
                            "is_server_managed": True,
                        }
                    ]
                },
            )
            original_bytes = config_path.read_bytes()
            server_alice = _build_server_detail(
                account_id=1,
                student_id="20231121130",
                display_name="Alice",
                password="new",
            )
            candidates = [
                SyncCandidate(
                    kind="replace",
                    student_id="20231121130",
                    server_payload=server_alice,
                    local_summary=_build_local_summary(
                        student_id="20231121130", password="old"
                    ),
                    default_checked=True,
                )
            ]

            applier = SyncApplier(config_path)
            applier.apply(candidates, selection={"20231121130": True})

            bak_path = config_path.with_name("config.json.bak")
            tmp_path = config_path.with_name("config.json.tmp")
            self.assertFalse(tmp_path.exists())
            self.assertTrue(bak_path.exists())
            self.assertEqual(bak_path.read_bytes(), original_bytes)

    def test_apply_creates_config_when_absent(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            self.assertFalse(config_path.exists())

            server_detail = _build_server_detail(
                account_id=1,
                student_id="20231121200",
                display_name="Bob",
            )
            candidates = [
                SyncCandidate(
                    kind="add",
                    student_id="20231121200",
                    server_payload=server_detail,
                    local_summary=None,
                    default_checked=True,
                )
            ]

            applier = SyncApplier(config_path)
            applier.apply(candidates, selection={"20231121200": True})

            self.assertTrue(config_path.exists())
            payload = _read_config(config_path)
            self.assertEqual(len(payload["accounts"]), 1)
            self.assertEqual(payload["accounts"][0]["student_id"], "20231121200")


# --------------------------------------------------------------------------- #
# 异常分支                                                                      #
# --------------------------------------------------------------------------- #


class InvalidCandidatesTestCase(unittest.TestCase):
    def test_add_without_server_payload_raises(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            _write_config(config_path, {"accounts": []})
            applier = SyncApplier(config_path)
            candidate = SyncCandidate(
                kind="add",
                student_id="20231121200",
                server_payload=None,
                local_summary=None,
                default_checked=True,
            )
            with self.assertRaises(ValueError):
                applier.apply([candidate], selection={"20231121200": True})


if __name__ == "__main__":
    unittest.main()
