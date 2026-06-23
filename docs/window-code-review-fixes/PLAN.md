# Windows 客户端审查问题修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `window-code-review-2026-05-13.txt` 中 Windows 客户端审查发现的中低风险问题，并保持现有 Windows 客户端行为、测试和前端资源同步稳定。

**Architecture:** 先处理会影响状态语义、运行时文件可靠性和后台线程可观测性的中风险问题；再处理不会立即导致故障但会降低鲁棒性的低风险问题。所有改动限制在 `library-window/`，优先复用现有 `unittest`、线程锁、日志和 `uv` 验证流程。

**Tech Stack:** Python 标准库、`unittest`、`ThreadingHTTPServer`、项目现有 `uv` 测试命令、Windows PowerShell。

---

## 0. 输入与基线

- 配套审查报告：`window-code-review-2026-05-13.txt`
- 子项目：`library-window`
- 审查基线：
  - `uv run --extra test python -m pytest`：`334 passed`
  - `uv run python scripts/build_web_assets.py --check`：`app.js 已和拆分源码保持一致`
  - `git diff --check -- library-window`：无输出
- 高严重程度问题：未发现
- 中严重程度问题：5 个
- 低严重程度问题：4 个

## 1. 优先级

| 优先级 | 问题 | 原因 | 完成标准 |
| --- | --- | --- | --- |
| P1 | `NetworkMonitor.reconnect_once()` 保留 `degraded` 状态 | 当前会把目标站点不可达误报为 `offline`，影响恢复语义 | `degraded` 复检失败后仍返回 `degraded`，测试覆盖无候选 Wi-Fi 和候选 Wi-Fi 失败两种路径 |
| P1 | 运行时 JSON 原子写入与损坏文件处理 | 并发读写或半写入会影响设置、缓存、定时任务、自动计划 | 新增通用 JSON 工具；关键运行时文件写入使用 `os.replace()`；损坏文件有明确异常或日志 |
| P1 | `SeatWebApp` 配置快照锁 | `ThreadingHTTPServer` 下配置字段和运行时缓存可能被不同请求线程看到不一致状态 | 新增统一 `RLock`；配置读写路径通过同一把锁或不可变快照 |
| P1 | 周期签到巡检异常记录 | 当前吞掉异常，登录态或接口异常长期不可见 | 单账号异常继续处理下一个账号，同时写日志或回调 |
| P2 | worker heartbeat 异常保护 | daemon 线程异常退出会导致心跳误判 | heartbeat 循环捕获异常、记录日志、继续下一轮 |
| P2 | 外部座位接口 schema 校验 | 接口结构变更时容易 `KeyError` 或 500 | 座位 map 入口校验缺字段，统一转为 `ApiRequestError` |
| P3 | 长函数拆分、前端 interval 清理、多账号状态并发 | 当前不是故障，只是维护性和规模化优化 | 独立小 PR / 小提交处理，避免与 P1/P2 混在一起 |

## 2. 影响文件清单

| 类型 | 文件 | 责任 |
| --- | --- | --- |
| 新增工具 | `library-window/src/wuyi_seat_bot/runtime_json.py` | 运行时 JSON 读写工具：原子写入、解析异常、单条记录校验辅助 |
| 网络监控 | `library-window/src/wuyi_seat_bot/network_monitor.py` | 修复 `reconnect_once()` 失败分支的状态语义 |
| 设置 | `library-window/src/wuyi_seat_bot/settings_store.py` | 使用原子 JSON 写；读取损坏设置时抛明确异常 |
| 入口缓存 | `library-window/src/wuyi_seat_bot/entry_url_cache.py` | 使用原子 JSON 写；缓存损坏时记录并显式返回空缓存 |
| 定时任务 | `library-window/src/wuyi_seat_bot/scheduler.py` | 使用原子 JSON 写；文件级损坏报错；单条记录损坏跳过并记录 |
| 自动计划 | `library-window/src/wuyi_seat_bot/automation_plans.py` | 使用原子 JSON 写；文件级损坏报错；单条计划损坏跳过并记录 |
| Web 应用 | `library-window/src/wuyi_seat_bot/web_server.py` | 配置快照锁；周期签到异常回调接入；必要时保护状态缓存写入 |
| 周期巡检 | `library-window/src/wuyi_seat_bot/checkin_monitor.py` | 增加 `on_error` 回调并保留“失败一个账号不影响其他账号”语义 |
| 服务守护 | `library-window/src/wuyi_seat_bot/service_manager.py` | heartbeat 循环异常保护 |
| 预约/任务 | `library-window/src/wuyi_seat_bot/web_task_service.py` | 校验 `seat_map` 和座位字段 |
| 预约/提交 | `library-window/src/wuyi_seat_bot/web_reservation_service.py` | 校验 `seat_map` 和座位字段 |
| 测试 | `library-window/tests/test_runtime_json.py` | 新增 JSON 工具测试 |
| 测试 | `library-window/tests/test_network_monitor.py` | 覆盖 `degraded` 状态保留 |
| 测试 | `library-window/tests/test_settings_store.py` | 覆盖设置文件原子写和损坏文件错误 |
| 测试 | `library-window/tests/test_entry_url_cache.py` | 覆盖缓存损坏处理和写入保留账号映射 |
| 测试 | `library-window/tests/test_scheduler.py` | 覆盖定时任务损坏文件和坏记录处理 |
| 测试 | `library-window/tests/test_automation_plans.py` | 覆盖自动计划损坏文件和坏记录处理 |
| 测试 | `library-window/tests/test_checkin_monitor.py` | 覆盖 `on_error` 回调 |
| 测试 | `library-window/tests/test_service_manager.py` | 覆盖 heartbeat 异常后继续运行 |
| 测试 | `library-window/tests/test_web_server.py` | 覆盖配置刷新锁相关快照行为、座位接口错误转换 |

## 3. 实施步骤

### Task 0: 建立实施基线

**Files:**
- Read: `window-code-review-2026-05-13.txt`
- Read: `AGENTS.md`
- Read: `library-window/pyproject.toml`

- [ ] **Step 1: 查看当前工作区状态**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto
git status --short
```

Expected: 记录已有用户改动；不回滚、不覆盖无关改动。

- [ ] **Step 2: 运行 Windows 客户端测试基线**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest
```

Expected: 全部通过。若失败，先确认是否为已有失败，再继续修复。

- [ ] **Step 3: 运行前端资源同步检查**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run python scripts/build_web_assets.py --check
```

Expected: `app.js 已和拆分源码保持一致`。

- [ ] **Step 4: 运行空白和冲突标记检查**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto
git diff --check -- library-window
```

Expected: 无输出。

### Task 1: 修复 `reconnect_once()` 将 `degraded` 误报为 `offline`

**Files:**
- Modify: `library-window/src/wuyi_seat_bot/network_monitor.py:158`
- Test: `library-window/tests/test_network_monitor.py`

- [ ] **Step 1: 写失败测试：候选 Wi-Fi 复检仍为 `degraded` 时保留状态**

在 `NetworkMonitorTestCase` 中追加：

```python
def test_reconnect_once_preserves_degraded_after_wifi_retry_failure(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
        config_path = Path(tmp_dir) / "config.json"
        config_path.write_text("{}", encoding="utf-8")
        monitor = NetworkMonitor(config_path)

        degraded_status = {
            "networkState": "degraded",
            "message": "学校目标站点连通性检测失败",
            "connectedInterfaces": ["WLAN"],
        }
        with (
            patch.object(
                monitor,
                "load_settings",
                return_value={
                    "networkMonitoring": {
                        "enabled": True,
                        "intervalMinutes": 120,
                        "preferredWifiNames": ["WYU"],
                    },
                    "campusNetwork": {
                        "enabled": True,
                        "wifiName": "WYU",
                        "loginUrl": "",
                        "username": "",
                        "password": "",
                    },
                },
            ),
            patch("wuyi_seat_bot.network_monitor.list_saved_wifi_profiles", return_value=["WYU"]),
            patch("wuyi_seat_bot.network_monitor.try_connect_wifi", return_value=True),
            patch.object(monitor, "detect_once", side_effect=[degraded_status, degraded_status]),
        ):
            result = monitor.reconnect_once()

    self.assertEqual(result["networkState"], "degraded")
    self.assertEqual(result["reconnectState"], "failed")
    self.assertEqual(result["wifiName"], "WYU")
    self.assertIn("学校目标站点连通性检测失败", result["message"])
```

- [ ] **Step 2: 写失败测试：无候选 Wi-Fi 且当前为 `degraded` 时保留状态**

```python
def test_reconnect_once_preserves_degraded_without_wifi_candidates(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
        config_path = Path(tmp_dir) / "config.json"
        config_path.write_text("{}", encoding="utf-8")
        monitor = NetworkMonitor(config_path)

        degraded_status = {
            "networkState": "degraded",
            "message": "学校目标站点连通性检测失败",
            "connectedInterfaces": ["WLAN"],
        }
        with (
            patch.object(
                monitor,
                "load_settings",
                return_value={
                    "networkMonitoring": {
                        "enabled": True,
                        "intervalMinutes": 120,
                        "preferredWifiNames": [],
                    },
                    "campusNetwork": {
                        "enabled": True,
                        "wifiName": "",
                        "loginUrl": "",
                        "username": "",
                        "password": "",
                    },
                },
            ),
            patch("wuyi_seat_bot.network_monitor.list_saved_wifi_profiles", return_value=[]),
            patch.object(monitor, "detect_once", return_value=degraded_status),
        ):
            result = monitor.reconnect_once()

    self.assertEqual(result["networkState"], "degraded")
    self.assertEqual(result["reconnectState"], "failed")
    self.assertEqual(result["wifiName"], "")
```

- [ ] **Step 3: 运行测试确认失败**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_network_monitor.py -k reconnect_once -v
```

Expected: 新增测试失败，当前代码返回 `networkState == "offline"`。

- [ ] **Step 4: 实现最小修复**

在 `reconnect_once()` 中维护 `last_detection`，失败返回时基于最后一次真实探测状态组装结果：

```python
last_detection = current_status

if not candidates:
    failed_status = {
        **last_detection,
        "reconnectState": "failed",
        "wifiName": "",
    }
    if last_detection["networkState"] != "degraded":
        failed_status["networkState"] = "offline"
        failed_status["message"] = "未找到已保存的 Wi-Fi 配置"
    return self._save_status(failed_status)

for wifi_name in candidates:
    if not try_connect_wifi(wifi_name):
        continue
    time.sleep(RECONNECT_RETRY_WAIT_SECONDS)
    detection = self.detect_once()
    last_detection = detection
    ...

failed_status = {
    **last_detection,
    "reconnectState": "failed",
    "wifiName": candidates[-1],
}
if last_detection["networkState"] != "degraded":
    failed_status["networkState"] = "offline"
    failed_status["message"] = "已尝试连接所有已保存 Wi-Fi，但联网复检仍未通过"
return self._save_status(failed_status)
```

- [ ] **Step 5: 运行网络监控测试**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_network_monitor.py -v
```

Expected: `test_network_monitor.py` 全部通过。

### Task 2: 新增运行时 JSON 原子读写工具

**Files:**
- Create: `library-window/src/wuyi_seat_bot/runtime_json.py`
- Test: `library-window/tests/test_runtime_json.py`

- [ ] **Step 1: 写 JSON 工具测试**

新增 `tests/test_runtime_json.py`：

```python
import json
import tempfile
import unittest
from pathlib import Path

from wuyi_seat_bot.runtime_json import (
    RuntimeJsonDecodeError,
    atomic_write_json,
    load_json_file,
)


class RuntimeJsonTestCase(unittest.TestCase):
    def test_atomic_write_json_replaces_existing_payload(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "runtime.json"
            path.write_text('{"old": true}', encoding="utf-8")

            atomic_write_json(path, {"new": True})

            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"new": True})
            self.assertEqual(list(path.parent.glob("*.tmp")), [])

    def test_load_json_file_raises_clear_error_for_invalid_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "runtime.json"
            path.write_text("{broken", encoding="utf-8")

            with self.assertRaises(RuntimeJsonDecodeError) as raised:
                load_json_file(path)

            self.assertIn("runtime.json", str(raised.exception))
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_runtime_json.py -v
```

Expected: `ModuleNotFoundError: No module named 'wuyi_seat_bot.runtime_json'`。

- [ ] **Step 3: 新增工具模块**

实现 `runtime_json.py`：

```python
from __future__ import annotations

import json
import os
import threading
from pathlib import Path
from typing import Any


class RuntimeJsonError(ValueError):
    pass


class RuntimeJsonDecodeError(RuntimeJsonError):
    pass


def load_json_file(path: str | Path) -> Any:
    json_path = Path(path)
    try:
        return json.loads(json_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise RuntimeJsonDecodeError(f"JSON 文件解析失败：{json_path}") from exc
    except OSError as exc:
        raise RuntimeJsonError(f"JSON 文件读取失败：{json_path}") from exc


def atomic_write_json(path: str | Path, payload: Any) -> None:
    json_path = Path(path)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = json_path.with_name(
        f".{json_path.name}.{os.getpid()}.{threading.get_ident()}.tmp"
    )
    try:
        with temp_path.open("w", encoding="utf-8") as output:
            json.dump(payload, output, ensure_ascii=False, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp_path, json_path)
    finally:
        if temp_path.exists():
            temp_path.unlink()
```

- [ ] **Step 4: 运行 JSON 工具测试**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_runtime_json.py -v
```

Expected: 通过。

### Task 3: 替换设置和入口缓存的 JSON 写入

**Files:**
- Modify: `library-window/src/wuyi_seat_bot/settings_store.py`
- Modify: `library-window/src/wuyi_seat_bot/entry_url_cache.py`
- Test: `library-window/tests/test_settings_store.py`
- Test: `library-window/tests/test_entry_url_cache.py`

- [ ] **Step 1: 在设置测试中覆盖损坏 JSON**

在 `test_settings_store.py` 追加：

```python
def test_load_app_settings_reports_invalid_json(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
        config_path = Path(tmp_dir) / "config.json"
        settings_path = Path(tmp_dir) / "runtime" / "app_settings.json"
        settings_path.parent.mkdir()
        settings_path.write_text("{broken", encoding="utf-8")

        with self.assertRaises(ValueError) as raised:
            load_app_settings(config_path)

    self.assertIn("app_settings.json", str(raised.exception))
```

- [ ] **Step 2: 在入口缓存测试中覆盖损坏缓存**

缓存是可重建数据，损坏时允许返回空缓存，但必须可观测。测试使用 `assertLogs`：

```python
def test_load_resolved_entry_urls_logs_and_ignores_invalid_cache(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
        config_path = Path(tmp_dir) / "config.json"
        cache_path = build_entry_url_cache_path(config_path)
        cache_path.parent.mkdir()
        cache_path.write_text("{broken", encoding="utf-8")

        with self.assertLogs("wuyi_seat_bot.entry_url_cache", level="WARNING") as logs:
            result = load_resolved_entry_urls(config_path, "主号")

    self.assertEqual(result, {})
    self.assertIn("入口 URL 缓存解析失败", "\n".join(logs.output))
```

- [ ] **Step 3: 运行测试确认失败**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_settings_store.py tests/test_entry_url_cache.py -v
```

Expected: 入口缓存日志测试失败，设置测试可能直接抛 `JSONDecodeError` 而不是项目内错误。

- [ ] **Step 4: 修改 `settings_store.py`**

替换直接 `json.loads()` 和 `write_text()`：

```python
from wuyi_seat_bot.runtime_json import atomic_write_json, load_json_file


def load_app_settings(config_path: str | Path) -> dict[str, Any]:
    settings_path = _resolve_app_settings_path(config_path)
    if not settings_path.exists():
        return _clone_default_settings()
    payload = load_json_file(settings_path)
    return _normalize_app_settings(payload)


def save_app_settings(config_path: str | Path, payload: dict[str, Any]) -> dict[str, Any]:
    normalized = _normalize_app_settings(payload)
    settings_path = _resolve_app_settings_path(config_path)
    atomic_write_json(settings_path, normalized)
    return normalized
```

- [ ] **Step 5: 修改 `entry_url_cache.py`**

新增 logger，并用原子写入：

```python
import logging

from wuyi_seat_bot.runtime_json import RuntimeJsonError, atomic_write_json, load_json_file

LOGGER = logging.getLogger(__name__)
```

读取损坏缓存时记录 warning：

```python
try:
    payload = load_json_file(cache_path)
except RuntimeJsonError as exc:
    LOGGER.warning("入口 URL 缓存解析失败：%s", exc)
    return {}
```

写入时：

```python
atomic_write_json(cache_path, payload)
```

- [ ] **Step 6: 运行相关测试**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_runtime_json.py tests/test_settings_store.py tests/test_entry_url_cache.py -v
```

Expected: 全部通过。

### Task 4: 修复定时任务和自动计划的损坏 JSON 处理

**Files:**
- Modify: `library-window/src/wuyi_seat_bot/scheduler.py`
- Modify: `library-window/src/wuyi_seat_bot/automation_plans.py`
- Test: `library-window/tests/test_scheduler.py`
- Test: `library-window/tests/test_automation_plans.py`

- [ ] **Step 1: 写定时任务损坏文件测试**

```python
def test_scheduler_reports_corrupt_storage_file(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
        storage_path = Path(tmp_dir) / "tasks.json"
        storage_path.write_text("{broken", encoding="utf-8")

        with self.assertRaises(RuntimeError) as raised:
            LocalTaskScheduler(storage_path, lambda task: "ok")

    self.assertIn("定时任务文件解析失败", str(raised.exception))
```

- [ ] **Step 2: 写定时任务坏记录跳过测试**

```python
def test_scheduler_skips_task_with_invalid_timestamp(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
        storage_path = Path(tmp_dir) / "tasks.json"
        storage_path.write_text(
            json.dumps(
                [
                    {
                        "task_id": "bad",
                        "action": "reserve",
                        "account_name": "主号",
                        "run_at": "not-a-date",
                        "created_at": "2026-03-28T08:00:00",
                        "summary": "bad",
                        "payload": {},
                        "status": PENDING_STATUS,
                    },
                    {
                        "task_id": "good",
                        "action": "reserve",
                        "account_name": "主号",
                        "run_at": "2026-03-28T08:00:00",
                        "created_at": "2026-03-28T07:00:00",
                        "summary": "good",
                        "payload": {},
                        "status": PENDING_STATUS,
                    },
                ],
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        scheduler = LocalTaskScheduler(storage_path, lambda task: "ok")

    self.assertEqual([task.task_id for task in scheduler.list_tasks()], ["good"])
```

- [ ] **Step 3: 写自动计划损坏文件和坏记录测试**

在 `test_automation_plans.py` 中用同样模式覆盖：

```python
def test_automation_scheduler_reports_corrupt_storage_file(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
        storage_path = Path(tmp_dir) / "automation-plans.json"
        storage_path.write_text("{broken", encoding="utf-8")

        with self.assertRaises(RuntimeError) as raised:
            LocalAutomationPlanScheduler(
                storage_path,
                execute_action=lambda plan, action, now: AutomationActionResult(message="ok"),
            )

    self.assertIn("自动计划文件解析失败", str(raised.exception))
```

- [ ] **Step 4: 运行测试确认失败**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_scheduler.py tests/test_automation_plans.py -k "corrupt or invalid" -v
```

Expected: 当前直接 `json.loads()` 或 `datetime.fromisoformat()` 抛原始异常。

- [ ] **Step 5: 修改 `scheduler.py`**

新增 logger，替换读写：

```python
import logging

from wuyi_seat_bot.runtime_json import RuntimeJsonError, atomic_write_json, load_json_file

LOGGER = logging.getLogger(__name__)
```

文件级损坏直接报错：

```python
try:
    payload = load_json_file(self.storage_path)
except RuntimeJsonError as exc:
    raise RuntimeError(f"定时任务文件解析失败：{self.storage_path}") from exc
```

单条记录构造前校验时间：

```python
try:
    task = ScheduledTask(...)
    _parse_iso(task.run_at)
    _parse_iso(task.created_at)
    if task.finished_at is not None:
        _parse_iso(task.finished_at)
except (TypeError, ValueError) as exc:
    LOGGER.warning("跳过损坏的定时任务记录：%s", item.get("task_id", ""), exc_info=exc)
    continue
```

保存时：

```python
atomic_write_json(self.storage_path, payload)
```

- [ ] **Step 6: 修改 `automation_plans.py`**

使用同样策略：

```python
try:
    payload = load_json_file(self.storage_path)
except RuntimeJsonError as exc:
    raise RuntimeError(f"自动计划文件解析失败：{self.storage_path}") from exc
```

单条记录：

```python
try:
    plan = _build_plan_from_payload(item)
    for value in (
        plan.reserve_next_run_at,
        plan.reserve_last_run_at,
        plan.checkin_next_run_at,
        plan.checkin_last_run_at,
        plan.checkout_next_run_at,
        plan.checkout_last_run_at,
    ):
        if value:
            _parse_iso(value)
except (TypeError, ValueError) as exc:
    LOGGER.warning("跳过损坏的自动计划记录：%s", item.get("plan_id", ""), exc_info=exc)
    continue
```

保存时：

```python
atomic_write_json(self.storage_path, payload)
```

- [ ] **Step 7: 运行相关测试**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_runtime_json.py tests/test_scheduler.py tests/test_automation_plans.py -v
```

Expected: 全部通过。

### Task 5: 给 `SeatWebApp` 配置快照增加统一锁

**Files:**
- Modify: `library-window/src/wuyi_seat_bot/web_server.py`
- Test: `library-window/tests/test_web_server.py`

- [ ] **Step 1: 写配置快照测试**

新增测试目标是防止 `_reload_config_bundle()` 只更新一部分字段：

```python
def test_reload_config_bundle_updates_snapshot_under_config_lock(self) -> None:
    app = SeatWebApp.__new__(SeatWebApp)
    app.config_path = Path("config.json")
    app._runtime_lock = threading.Lock()
    app._runtimes = {"旧账号": object()}
    app.automation_scheduler = SimpleNamespace(list_plans=lambda: [], delete_plan=MagicMock())
    app._config_lock = threading.RLock()

    config_bundle = build_test_config_bundle(["主号", "室友"], default_account_name="主号")

    app._reload_config_bundle(config_bundle)

    with app._config_lock:
        self.assertEqual(app.default_account_name, "主号")
        self.assertEqual(app.account_names, ("主号", "室友"))
        self.assertEqual(app._runtimes, {})
```

`build_test_config_bundle()` 使用现有 `test_web_server.py` 里的配置构造方式；如果没有现成 helper，就沿用该文件内其他 `SeatWebApp.__new__` 测试的对象搭建方式。

- [ ] **Step 2: 实现锁**

在 `__init__()` 中先初始化锁，再加载配置：

```python
self._config_lock = threading.RLock()
self.config_bundle = load_config_bundle(self.config_path)
```

修改 `_reload_config_bundle()`：

```python
def _reload_config_bundle(self, config_bundle: ConfigBundle | None = None) -> None:
    with self._config_lock:
        self.config_bundle = config_bundle or load_config_bundle(self.config_path)
        self.default_account_name = self.config_bundle.default_account_name
        self.account_names = tuple(
            account.account_name for account in self.config_bundle.accounts
        )
        with self._runtime_lock:
            self._runtimes = {}
        self._prune_orphan_automation_plans()
```

新增快照 helper：

```python
def _get_config_snapshot(self) -> tuple[ConfigBundle, str, tuple[str, ...]]:
    with self._config_lock:
        return self.config_bundle, self.default_account_name, self.account_names
```

在高频入口先取快照，例如 `get_bootstrap()` 和 `inspect_task_statuses()`：

```python
config_bundle, default_account_name, account_names = self._get_config_snapshot()
```

后续在该方法内使用局部变量，避免中途被另一个请求刷新配置。

- [ ] **Step 3: 运行 Web 服务测试**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_web_server.py -k "config or bootstrap or task_status" -v
```

Expected: 相关测试通过。

### Task 6: 记录周期签到巡检异常

**Files:**
- Modify: `library-window/src/wuyi_seat_bot/checkin_monitor.py`
- Modify: `library-window/src/wuyi_seat_bot/web_server.py`
- Test: `library-window/tests/test_checkin_monitor.py`
- Test: `library-window/tests/test_web_server.py`

- [ ] **Step 1: 写 `on_error` 回调测试**

```python
def test_run_cycle_reports_single_account_failure(self) -> None:
    calls: list[str] = []
    errors: list[tuple[str, str]] = []

    def execute_checkin(account_name: str) -> None:
        calls.append(account_name)
        if account_name == "主号":
            raise RuntimeError("蓝牙扫描失败")

    monitor = PeriodicAccountCheckinMonitor(
        list_account_names=lambda: ("主号", "室友"),
        execute_checkin=execute_checkin,
        on_error=lambda account_name, exc: errors.append((account_name, str(exc))),
    )

    monitor.run_cycle_once()

    self.assertEqual(calls, ["主号", "室友"])
    self.assertEqual(errors, [("主号", "蓝牙扫描失败")])
```

- [ ] **Step 2: 实现 `on_error` 参数**

```python
class PeriodicAccountCheckinMonitor:
    def __init__(
        self,
        *,
        list_account_names: Callable[[], tuple[str, ...]],
        execute_checkin: Callable[[str], None],
        on_error: Callable[[str, BaseException], None] | None = None,
        poll_interval_seconds: float = 1800.0,
    ) -> None:
        ...
        self.on_error = on_error

    def run_cycle_once(self) -> None:
        for account_name in self.list_account_names():
            try:
                self.execute_checkin(account_name)
            except Exception as exc:  # noqa: BLE001
                if self.on_error is not None:
                    self.on_error(account_name, exc)
                continue
```

- [ ] **Step 3: 在 `SeatWebApp` 接入日志**

实例化时传入：

```python
self.checkin_monitor = PeriodicAccountCheckinMonitor(
    list_account_names=self._list_account_names_for_periodic_checkin,
    execute_checkin=self._run_periodic_account_checkin,
    on_error=self._record_periodic_checkin_error,
)
```

新增方法：

```python
def _record_periodic_checkin_error(self, account_name: str, exc: BaseException) -> None:
    LOGGER.warning(
        "周期签到巡检失败：account=%s error=%s: %s",
        account_name,
        type(exc).__name__,
        exc,
    )
```

- [ ] **Step 4: 运行巡检测试**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_checkin_monitor.py tests/test_web_server.py -k "checkin_monitor or periodic" -v
```

Expected: 全部通过。

### Task 7: 保护 worker heartbeat 循环

**Files:**
- Modify: `library-window/src/wuyi_seat_bot/service_manager.py:744`
- Test: `library-window/tests/test_service_manager.py`

- [ ] **Step 1: 写 heartbeat 异常后继续运行测试**

```python
def test_worker_heartbeat_loop_continues_after_background_service_error(self) -> None:
    stop_event = threading.Event()
    calls = {"count": 0}

    class FakeApp:
        def ensure_background_services(self) -> None:
            calls["count"] += 1
            if calls["count"] == 1:
                raise RuntimeError("restart failed")
            stop_event.set()

        def get_service_snapshot(self) -> dict[str, object]:
            return {}

    with tempfile.TemporaryDirectory() as tmp_dir:
        status_path = Path(tmp_dir) / "worker-status.json"
        log_path = Path(tmp_dir) / "worker.log"
        with patch(
            "wuyi_seat_bot.service_manager.WORKER_HEARTBEAT_INTERVAL_SECONDS",
            0.01,
        ):
            _worker_heartbeat_loop(
                status_path,
                FakeApp(),
                stop_event,
                "2026-05-13T08:00:00",
                "127.0.0.1",
                8765,
                "http://127.0.0.1:8765/",
                log_path,
            )

    self.assertGreaterEqual(calls["count"], 2)
```

- [ ] **Step 2: 实现循环保护**

```python
logger = logging.getLogger("wuyi-seat-bot.service-worker")
while not stop_event.is_set():
    try:
        app.ensure_background_services()
        _write_worker_status(...)
    except Exception as exc:  # noqa: BLE001
        logger.exception("worker heartbeat failed: %s", exc)
        stop_event.wait(min(5.0, WORKER_HEARTBEAT_INTERVAL_SECONDS))
        continue
    stop_event.wait(WORKER_HEARTBEAT_INTERVAL_SECONDS)
```

不要在 `except` 中再次强依赖 `_write_worker_status()`；如果状态文件本身不可写，重复写状态会造成异常循环。

- [ ] **Step 3: 运行服务管理测试**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_service_manager.py -v
```

Expected: 通过。

### Task 8: 给座位接口派生数据增加 schema 校验

**Files:**
- Modify: `library-window/src/wuyi_seat_bot/web_task_service.py`
- Modify: `library-window/src/wuyi_seat_bot/web_reservation_service.py`
- Test: `library-window/tests/test_web_server.py`

- [ ] **Step 1: 写任务座位选择坏结构测试**

```python
def test_read_selected_task_seat_rejects_malformed_seat_map(self) -> None:
    filters = SearchFilters(date_value="2026-03-28", start_hour=8, duration_hours=2, people_count=1)

    with self.assertRaises(ApiRequestError) as raised:
        read_selected_task_seat(
            {"selectedSeatIds": ["A1"]},
            filters,
            search_seat_map=lambda payload: {"roomName": "一楼"},
        )

    self.assertIn("座位接口返回结构异常", str(raised.exception))
```

- [ ] **Step 2: 写预约提交坏结构测试**

覆盖 `_find_seat_by_id()` 或提交预约入口中 `selected_seat["selectable"]` 缺失的路径，期望 `ApiRequestError("座位接口返回结构异常，请重新查询")`。

- [ ] **Step 3: 新增校验 helper**

可以放在 `web_reservation_service.py`，并由 `web_task_service.py` 导入；若担心循环依赖，就新建 `web_seat_map_validation.py`。

```python
def validate_seat_map_payload(seat_map: dict[str, Any]) -> list[dict[str, Any]]:
    seats = seat_map.get("seats")
    if not isinstance(seats, list):
        raise ApiRequestError("座位接口返回结构异常，请重新查询")
    normalized_seats: list[dict[str, Any]] = []
    for seat in seats:
        if not isinstance(seat, dict):
            raise ApiRequestError("座位接口返回结构异常，请重新查询")
        if "seatId" not in seat or "selectable" not in seat:
            raise ApiRequestError("座位接口返回结构异常，请重新查询")
        normalized_seats.append(seat)
    return normalized_seats
```

- [ ] **Step 4: 替换固定 key 访问**

`web_task_service.py`：

```python
seats = validate_seat_map_payload(seat_map)
selected_seat = next(
    (seat for seat in seats if seat["seatId"] == selected_seat_ids[0]),
    None,
)
```

`web_reservation_service.py`：

```python
def _find_seat_by_id(seat_map: dict[str, Any], seat_id: str) -> dict[str, Any] | None:
    return next(
        (seat for seat in validate_seat_map_payload(seat_map) if seat["seatId"] == seat_id),
        None,
    )
```

保留 `seatNumber` 的业务错误提示前也要校验：

```python
seat_number = str(selected_seat.get("seatNumber", "")).strip() or "未知"
```

- [ ] **Step 5: 运行 Web 相关测试**

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest tests/test_web_server.py -k "seat or reserve or task" -v
```

Expected: 通过。

### Task 9: 低风险维护性和性能优化单独处理

**Files:**
- Optional Modify: `library-window/src/wuyi_seat_bot/network_monitor.py`
- Optional Modify: `library-window/src/wuyi_seat_bot/service_manager.py`
- Optional Modify: `library-window/src/wuyi_seat_bot/web_server.py`
- Optional Modify: `library-window/src/wuyi_seat_bot/web/app.js`

- [ ] **Step 1: 拆分长函数，不改变行为**

只在 P1/P2 全部通过后做，避免把行为修复和结构调整混在一起。

建议拆分：

```text
network_monitor.py
- _perform_campus_network_login()
  - resolve_login_page()
  - submit_login_form()
  - build_login_diagnostics()

service_manager.py
- run_web_worker()
  - create_app_server()
  - start_heartbeat()
  - write_worker_state()

web_server.py
- get_bootstrap()
  - build_base_bootstrap_payload()
  - attach_entry_context()
```

- [ ] **Step 2: 前端 interval 清理**

若 `app.js` 的 3 秒刷新仍由全局 `setInterval` 直接创建，则保存 timer id：

```javascript
state.taskStatusRefreshTimer = window.setInterval(refreshTaskStatuses, 3000);
window.addEventListener("beforeunload", () => {
  if (state.taskStatusRefreshTimer) {
    window.clearInterval(state.taskStatusRefreshTimer);
  }
});
```

修改前确认拆分源码是否存在；如果存在，优先编辑拆分源码，再运行构建脚本。

- [ ] **Step 3: 多账号状态读取保持串行，暂不并发**

本次不建议立即引入线程池。原因是账号动作已有 `action_lock`、登录态和接口限流约束；账号数量没有增长到瓶颈前，串行更可预测。

后续如果账号数超过 8 个且状态页明显慢，再单独做：

```python
max_workers = min(4, len(account_names))
```

并为每个账号保留现有 `action_lock` 路径。

## 4. 阶段验证命令

每完成一个 Task 先跑局部测试，全部完成后跑以下命令：

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-window
uv run --extra test python -m pytest
uv run python scripts/build_web_assets.py --check

cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto
git diff --check -- library-window
```

如果改动扩大到共享逻辑并影响服务端导入，再补跑：

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto\library-fwq
$env:PYTHONPATH="..\library-window\src;src"
uv run --extra test python -m pytest
```

如果准备发布或打包，再跑：

```powershell
cd C:\Users\xuhuangbin\Desktop\wuyi-library-auto
python .\scripts\release_check.py
```

## 5. 建议提交边界

| 提交 | 范围 | 建议提交信息 |
| --- | --- | --- |
| 1 | Task 1 | `fix(window): preserve degraded reconnect status` |
| 2 | Task 2-4 | `fix(window): harden runtime json persistence` |
| 3 | Task 5 | `fix(window): guard config snapshot updates` |
| 4 | Task 6-7 | `fix(window): log background worker failures` |
| 5 | Task 8 | `fix(window): validate seat map payloads` |
| 6 | Task 9 | `refactor(window): split long service helpers` |

不要把 P3 维护性优化合并进 P1/P2 修复提交；否则回归时很难定位行为变化。

## 6. 风险与回滚策略

| 风险 | 触发条件 | 缓解 |
| --- | --- | --- |
| JSON 损坏处理从“隐式空数据”变成“显式报错” | 用户本地已有坏的 `runtime/*.json` | 错误信息必须带文件路径；不要删除用户文件；必要时提示用户手动备份后修复 |
| 配置锁扩大临界区导致请求变慢 | 在锁内执行网络请求或磁盘重操作 | 锁内只复制配置字段和清空 runtime dict；网络请求放锁外 |
| heartbeat 捕获异常掩盖真实崩溃 | 所有异常都被吞掉且不重启 | 必须 `logger.exception()`；连续失败可以让 supervisor 基于其他健康指标处理 |
| 座位 schema 校验过严 | 上游字段类型轻微变化 | 只强校验当前代码必需字段：`seats`、`seatId`、`selectable`；展示字段如 `seatNumber` 可用兜底文本 |
| 前端资源不同步 | 直接编辑 `web/app.js` 但拆分源码存在 | 修改前确认 `scripts/build_web_assets.py --check`；如果失败，按脚本提示编辑拆分源码再生成 |

## 7. 完成定义

- P1 和 P2 全部测试通过。
- `uv run --extra test python -m pytest` 全量通过。
- `uv run python scripts/build_web_assets.py --check` 通过，除非本次完全未触碰 Web 资源；未触碰时仍建议跑一次确认。
- `git diff --check -- library-window` 无输出。
- 最终说明列出：
  - 实际修改文件
  - 每个审查问题对应的修复位置
  - 已运行验证命令和结果
  - 未实施的 P3 项及原因

## 8. 自检结果

- 审查报告中的 5 个中风险问题均有 P1/P2 任务覆盖。
- 审查报告中的 4 个低风险问题均有 P2/P3 处理策略。
- 所有执行步骤都有明确文件、命令或代码示例。
- 本计划不包含 `config.json`、账号密码、令牌、运行日志内容。
