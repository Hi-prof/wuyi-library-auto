# 实施文档：手动检查预约 & 一键签到所有账号

> 配套 PRD：`docs/manual-check-and-bulk-checkin/PRD.md`
> 配套 SPEC：`docs/manual-check-and-bulk-checkin/SPEC.md`
> 子项目：`library-window`

## 0. 准备

```powershell
# 准备依赖（仅首次需要）
cd library-window
uv sync --extra test

# 验证现有测试基线，确保从绿开始
uv run --extra test python -m pytest
```

> 之后每完成一个阶段都重跑相关 pytest，避免一次性叠加大量改动。

## 1. 实施分阶段

### 阶段 A：后端数据结构调整

文件 `library-window/src/wuyi_seat_bot/automation_plans.py`：

1. 给 `AutomationActionResult` 新增 `created_dates: tuple[str, ...] = ()` 字段（保持 `frozen=True`）。
2. `_apply_action_result` 与现有调用点不需要变更字段含义，只需要在构造 `AutomationActionResult` 时传入新字段。
3. 单元测试：在 `tests/test_automation_plans.py` 中新增一条用例，校验 `replace(plan, ...)` 后 `created_dates` 透传，但不改变 `reserve_next_run_at` 计算逻辑。

文件 `library-window/src/wuyi_seat_bot/web_automation_service.py`：

1. `build_automation_reserve_result` 增加参数 `created_dates: list[str]`，并在返回 `AutomationActionResult` 时把它转为元组传入。
2. `execute_automation_reserve` 已有局部变量 `created_dates`，把它一并传给 `build_automation_reserve_result`。
3. 跑：`uv run --extra test python -m pytest tests/test_web_automation_service.py`

### 阶段 B：调度器扩展

文件 `library-window/src/wuyi_seat_bot/automation_plans.py`：

1. 在 `LocalAutomationPlanScheduler` 上新增公开方法 `apply_manual_reserve_result(plan_id, now, result)`，复用私有 `_apply_action_result`。
2. 注意线程安全：与 `run_due_once` 一样使用 `self._lock`。
3. 单元测试：扩展 `tests/test_automation_plans.py`，调用 `apply_manual_reserve_result` 后断言 `reserve_last_run_at` / `reserve_next_run_at` 都正确刷新；不存在的 plan_id 应返回 `None`。

### 阶段 C：手动检查预约 API

文件 `library-window/src/wuyi_seat_bot/web_automation_service.py`：

1. **新增 `build_manual_reserve_filters(plan, search_page_payload, target_date, now)`**（见 SPEC §4.1.0）：
   - 当天：`start_hour = max(now.hour, minBeginTime)`，`duration_hours = maxEndTime - start_hour`，再按 `build_duration_options` 合法集合做降档，最后 `validate_filters`。
   - 未来日期：直接复用 `build_automation_future_default_filters`。
   - 当天 `start_hour > maxEndTime - min_duration` 时返回 `None`，让上层归类为 `skipped`，message 写“当前时间已超过当天可预约窗口”。
2. **新增 `execute_manual_reserve_check(...)`**（见 SPEC §4.1.0）：复用 `execute_automation_reserve` 的循环骨架，把 `build_automation_reserve_filters` 替换为 `build_manual_reserve_filters`。为减少重复，可在 `web_automation_service.py` 中提取内部函数 `_execute_reserve_loop(filters_builder, ...)`，并让 `execute_automation_reserve` 与 `execute_manual_reserve_check` 都基于它。
3. 新增 `classify_manual_reserve_result(result: AutomationActionResult, *, login_state_ready: bool) -> str`。
4. 新增 `build_manual_reserve_result_entry(plan, classification, result) -> dict[str, Any]`。
5. 新增 `build_manual_reserve_check_response(results: list[dict[str, Any]]) -> dict[str, Any]`，包含 `message` / `checkedCount` / `bookedCount` / `skippedCount` / `failedCount` / `results` 字段。

文件 `library-window/src/wuyi_seat_bot/web_server.py`：

1. 新增 `def run_automation_reserve_now(self, payload: dict[str, Any]) -> dict[str, Any]:` 见 SPEC §4.1.1。
2. 内部辅助 `_run_manual_reserve_check_for_plan(plan, now)`：
   - 检查登录态：`self._account_state_path(plan.account_name).exists()`（沿用现有助手）。无登录态直接返回 `state=skipped`，message 为“未保存登录态”。
   - 否则新增 `_execute_manual_reserve_check(plan, now)`：内部加载 `search_page_payload` / `booking_list_payload` / `saved_session`，调用 `execute_manual_reserve_check(...)`（而不是 `execute_automation_reserve`）获得 `AutomationActionResult`。异常归类为 `state=failed`。
   - 成功后 `apply_manual_reserve_result(plan.plan_id, now, result)`。
   - 调用 `classify_manual_reserve_result` 并 `build_manual_reserve_result_entry`。

文件 `library-window/src/wuyi_seat_bot/web_routes.py`：

1. 在 `POST_JSON_ROUTES` 中追加：
   ```python
   "/api/automation-plans/check-now": ("run_automation_reserve_now", HTTPStatus.OK, True),
   ```

测试：

```powershell
cd library-window
uv run --extra test python -m pytest tests/test_web_automation_service.py tests/test_web_server_routes.py
```

### 阶段 D：一键签到所有账号 API

文件 `library-window/src/wuyi_seat_bot/web_action_service.py`：

1. 新增 `build_checkin_all_response(results: list[dict[str, Any]]) -> dict[str, Any]`。
2. 输出字段：`message` / `successCount` / `failedCount` / `results`。

文件 `library-window/src/wuyi_seat_bot/web_server.py`：

1. 新增 `def checkin_all_accounts(self, payload: dict[str, Any]) -> dict[str, Any]:` 见 SPEC §4.2。
2. 顺序遍历 `self.account_names`，每个账号包一层 `try/except ApiRequestError`，让单账号失败不污染整体响应。

文件 `library-window/src/wuyi_seat_bot/web_routes.py`：

1. `POST_JSON_ROUTES` 追加：
   ```python
   "/api/accounts/checkin-all": ("checkin_all_accounts", HTTPStatus.OK, True),
   ```

测试：

```powershell
uv run --extra test python -m pytest tests/test_web_action_service.py tests/test_web_server_routes.py
```

### 阶段 E：前端 UI

文件 `library-window/src/wuyi_seat_bot/web/index.html`：

1. 自动任务页 `hero-actions` 内追加：
   ```html
   <button id="taskCheckReserveButton" class="ghost-button" type="button">手动检查预约</button>
   ```
2. 账号状态页 `hero-actions` 内在 `taskStatusButton` 左侧追加（账号管理页保持原样，不要在账号管理页加按钮）：
   ```html
   <button id="accountCheckinAllButton" class="ghost-button" type="button">一键签到所有账号</button>
   ```

文件 `library-window/src/wuyi_seat_bot/web/app.js`：

1. `elements` 字典补两个键：`taskCheckReserveButton` / `accountCheckinAllButton`。
2. `bindEvents()` 中添加点击事件：分别调用 `runManualReserveCheck()` / `runCheckinAllAccounts()`。
3. 实现两个函数：
   - `runManualReserveCheck()`：用 `runBusyButtonAction` 包住 `requestJsonWithBody("/api/automation-plans/check-now", {}, "手动检查预约")`，结束后 `await Promise.all([loadTasks(true), loadTaskStatuses(true)])`。
   - `runCheckinAllAccounts()`：同理对 `/api/accounts/checkin-all`，结束后 `await Promise.all([loadAccounts(state.bootstrap?.selectedAccountName || ""), loadTaskStatuses(true)])`。
4. `loadAccounts(...)` 完成时根据 `state.accounts.length` 设置 `accountCheckinAllButton.disabled` 与 `title`。

如果项目里有 `scripts/build_web_assets.py --check`：

```powershell
cd library-window
uv run python scripts/build_web_assets.py --check
```

### 阶段 F：联调与回归

```powershell
# 子项目级回归
cd library-window
uv run --extra test python -m pytest

# 跨项目验证（默认走全量验证）
cd ..
python .\scripts\verify_all.py
```

可选：在本机启动一次 `fwq-qd.bat` 或 Windows 客户端 GUI，手动验证：

1. 自动任务页右上角 → 手动检查预约：
   - 无计划：toast 显示提示文案。
   - 有计划且当天有预约：toast 显示“全部已有预约”。
   - 有计划且当天无预约：toast 显示补订成功，自动任务卡片刷新最近一次执行时间。
2. 账号状态页右上角 → 一键签到所有账号：
   - 无账号：按钮禁用，title 提示“先到账号管理页新建”。
   - 多账号：toast 汇总成功 / 失败数量；同页账号状态卡片同步刷新。

## 2. 影响文件清单

| 类型 | 文件 | 改动 |
| --- | --- | --- |
| 后端 | `library-window/src/wuyi_seat_bot/automation_plans.py` | `AutomationActionResult` 新增字段；调度器新增公开方法。 |
| 后端 | `library-window/src/wuyi_seat_bot/web_automation_service.py` | 新增 `build_manual_reserve_filters` / `execute_manual_reserve_check` / `classify_manual_reserve_result` / `build_manual_reserve_result_entry` / `build_manual_reserve_check_response`；`build_automation_reserve_result` 接收 `created_dates`；抽出 `_execute_reserve_loop` 供自动 / 手动共用。 |
| 后端 | `library-window/src/wuyi_seat_bot/web_server.py` | 新增 `run_automation_reserve_now` / `checkin_all_accounts` / `_run_manual_reserve_check_for_plan`。 |
| 后端 | `library-window/src/wuyi_seat_bot/web_action_service.py` | 新增 `build_checkin_all_response`。 |
| 后端 | `library-window/src/wuyi_seat_bot/web_routes.py` | 新增两个 `POST_JSON_ROUTES` 入口。 |
| 前端 | `library-window/src/wuyi_seat_bot/web/index.html` | 两个 `hero-actions` 各加一颗按钮。 |
| 前端 | `library-window/src/wuyi_seat_bot/web/app.js` | `elements` / `bindEvents` / 两个新函数 / `loadAccounts` 中按钮状态。 |
| 测试 | `library-window/tests/test_automation_plans.py` | `apply_manual_reserve_result` / `created_dates` 用例。 |
| 测试 | `library-window/tests/test_web_automation_service.py` | `build_manual_reserve_filters`（15:40 → 15-22、07:10 → 8-22、21:30 → 21-22、22:10 → None、duration 降档）、`execute_manual_reserve_check`、`classify_manual_reserve_result`、`build_manual_reserve_check_response` 用例。 |
| 测试 | `library-window/tests/test_web_action_service.py` | `build_checkin_all_response` 用例。 |
| 测试 | `library-window/tests/test_web_server_routes.py` | 两个新路由的集成用例（fake scheduler）。 |

## 3. 不修改的文件

- `library-fwq/`：服务端不参与，无需变更。
- `library-android/`：原生客户端不调用这些新接口，保持原样。
- `config.json` / `config.example.json`：无新增配置项。
- `runtime/automation_plans.json`：字段不变，老数据兼容。

## 4. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 手动巡检触发同一账号上正在跑的自动巡检 | 复用 `_lock` / `recovery_lock`；如出现冲突，新接口会按现有同步语义排队完成。 |
| 大量账号串行签到导致接口长时间阻塞 | 顺序执行 + 单账号超时由现有 HTTP 客户端管理；如未来需要并发，可在响应里追加 `durations` 字段，再独立改造。 |
| 前端按钮误触多次 | `runBusyButtonAction` 已经禁用按钮并更新文案；额外加 `data-busy` 兜底。 |
| 未保存登录态触发不必要的网络请求 | `_run_manual_reserve_check_for_plan` 提前判定登录态文件是否存在，无登录态直接 `state=skipped`。 |

## 5. 发布与验证

- 提交前：
  - `cd library-window; uv run --extra test python -m pytest`
  - `python .\scripts\verify_all.py`
  - 必要时：`python .\scripts\release_check.py`
- 发布说明加一段：
  - “自动任务页右上角新增‘手动检查预约’，点一次立即对所有自动预约计划做一轮补订巡检。”
  - “账号状态页右上角新增‘一键签到所有账号’，按账号顺序执行签到并在 toast 汇总结果。”

## 6. 后续可扩展

- 支持按账号勾选签到 / 巡检（在请求体里支持 `accountNames`，后端忽略列表外的项）。
- toast 中新增“查看详情”按钮，跳转到调试中心定位失败账号。
- 把 `results[].durationMs` 加进响应，方便排查接口耗时问题。
