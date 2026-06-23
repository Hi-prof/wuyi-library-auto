# SPEC：手动检查预约 & 一键签到所有账号

> 配套 PRD：`docs/manual-check-and-bulk-checkin/PRD.md`
> 涉及子项目：`library-window`

## 1. 总览

新增两条端到端链路：

```
[自动任务页] ──手动检查预约──▶ POST /api/automation-plans/check-now ──▶ SeatWebApp.run_automation_reserve_now ──▶ LocalAutomationPlanScheduler + execute_automation_reserve
[账号状态页] ──一键签到所有账号──▶ POST /api/accounts/checkin-all ──▶ SeatWebApp.checkin_all_accounts ──▶ run_account_action_result(CHECKIN, ...) for each account
```

两条链路均在主线程的 HTTP 处理器内顺序执行，复用现有锁与运行时缓存，不创建新的后台线程。

## 2. UI Spec

### 2.1 自动任务页 · 手动检查预约

修改 `library-window/src/wuyi_seat_bot/web/index.html` 中 `data-view-panel="tasks"` 的 `header.page-hero`：

```html
<div class="hero-actions">
  <button id="taskCheckReserveButton" class="ghost-button" type="button">手动检查预约</button>
  <button id="taskCreateButton" class="primary-button" type="button">新建自动任务</button>
</div>
```

`app.js` 配合新增：

- `elements.taskCheckReserveButton = document.getElementById("taskCheckReserveButton");`
- 在 `bindEvents()` 中：

  ```js
  elements.taskCheckReserveButton.addEventListener("click", async () => {
    await runManualReserveCheck();
  });
  ```

- 新函数 `runManualReserveCheck()`：

  - 通过 `runBusyButtonAction(elements.taskCheckReserveButton, { busyLabel: "正在检查...", idleLabel: "手动检查预约", debugLabel: "手动检查预约" }, async () => { ... })` 触发请求。
  - 请求体为 `{}`；调用 `requestJsonWithBody("/api/automation-plans/check-now", {}, "手动检查预约")`。
  - 拿到响应后：
    - 优先以 `data.message` 作 toast。
    - 调用 `await Promise.all([loadTasks(true), loadTaskStatuses(true)])` 刷新视图。
- 按钮可见性：与 `taskCreateButton` 保持一致；当 `state.automationPlans` 为空数组时也保持可点击（让后端返回引导文案），不需要额外禁用逻辑。

### 2.2 账号状态页 · 一键签到所有账号

修改 `data-view-panel="status"` 的 `header.page-hero`，把新按钮放在原“检测当前状态”按钮的左侧：

```html
<div class="hero-actions">
  <button id="accountCheckinAllButton" class="ghost-button" type="button">一键签到所有账号</button>
  <button id="taskStatusButton" class="primary-button" type="button">检测当前状态</button>
</div>
```

> 账号管理页（`data-view-panel="accounts"`）的 `hero-actions` 保持原样，仅保留 `accountResetButton`。

`app.js`（拆分后位于 `state.js` / `tasks.js`，重新运行 `scripts/build_web_assets.py` 拼接）配合：

- `elements.accountCheckinAllButton = document.getElementById("accountCheckinAllButton");`。
- 在 `loadAccounts(...)` 完成时根据账号数量更新按钮：

  ```js
  elements.accountCheckinAllButton.disabled = !state.accounts.length;
  elements.accountCheckinAllButton.title = state.accounts.length
    ? ""
    : "没有账号，先到账号管理页新建";
  ```

- `bindEvents()` 中追加：

  ```js
  elements.accountCheckinAllButton.addEventListener("click", async () => {
    await runCheckinAllAccounts();
  });
  ```

- 新函数 `runCheckinAllAccounts()`（实际放在 `tasks.js`，与账号状态页逻辑同位）：

  - 用 `runBusyButtonAction(elements.accountCheckinAllButton, { busyLabel: "正在签到...", idleLabel: "一键签到所有账号", restoreDisabled: () => !state.accounts.length }, ...)`。
  - `await requestJsonWithBody("/api/accounts/checkin-all", {}, "一键签到所有账号")`。
  - 完成后 `await Promise.all([loadAccounts(state.bootstrap?.selectedAccountName || ""), loadTaskStatuses(true)])` 刷新。
  - 不会自动调用 `loadTaskStatuses` 以外的状态检测接口；是否进一步点“检测当前状态”由用户决定。

### 2.3 文案集中表

| Key | 文案 |
| --- | --- |
| 自动任务按钮文本 | `手动检查预约` |
| 自动任务忙态 | `正在检查...` |
| 自动任务空计划 | `当前没有启用自动预约的计划，无需检查` |
| 自动任务全部已订 | `已检查 {checkedCount} 个计划，全部已有预约` |
| 自动任务部分补订 | `已补订 {bookedCount} 个账号` + `（共检查 {checkedCount} 个计划）` |
| 自动任务有失败 | `检查完成：成功 {bookedCount}，跳过 {skippedCount}，失败 {failedCount}` |
| 账号状态按钮文本 | `一键签到所有账号` |
| 账号状态空账号 | `没有账号，先到账号管理页新建` |
| 账号状态忙态 | `正在签到...` |
| 账号状态全部成功 | `已为 {successCount} 个账号执行签到` |
| 账号状态部分失败 | `签到完成：成功 {successCount}，失败 {failedCount}` |

## 3. HTTP API Spec

### 3.1 `POST /api/automation-plans/check-now`

请求体：`{}`（暂不接受过滤参数，预留 `accountNames?: string[]` 字段用于未来扩展，当前实现忽略）。

响应体：

```json
{
  "message": "已补订 1 个账号（共检查 2 个计划）",
  "checkedCount": 2,
  "bookedCount": 1,
  "skippedCount": 1,
  "failedCount": 0,
  "results": [
    {
      "accountName": "20231121130",
      "planId": "abcd1234",
      "state": "booked",            // booked | already-booked | failed | skipped
      "message": "已补订 1 天：2026-05-08",
      "targetDates": ["2026-05-08"],
      "bookedDates": ["2026-05-08"]
    }
  ]
}
```

字段说明：

- `state` 枚举：
  - `booked`：本次成功新增预约。
  - `already-booked`：所有目标日期均已存在预约。
  - `failed`：执行抛错或所有目标日期补订失败。
  - `skipped`：未启用自动预约 / 未启用 / 缺少登录态等不可执行情况。
- `message`：直接复用 `AutomationActionResult.message`。
- `targetDates` / `bookedDates`：来自现有 `_apply_action_result`，方便前端排查。

错误响应：复用 `ApiRequestError`，结构 `{ "message": "..." }`，HTTP 状态码继承现有惯例。

### 3.2 `POST /api/accounts/checkin-all`

请求体：`{}`（预留 `accountNames?: string[]`，当前忽略）。

响应体：

```json
{
  "message": "签到完成：成功 2，失败 1",
  "successCount": 2,
  "failedCount": 1,
  "results": [
    {
      "accountName": "20231121130",
      "success": true,
      "message": "签到成功"
    },
    {
      "accountName": "20231121200",
      "success": false,
      "message": "未保存登录态"
    }
  ]
}
```

错误响应：单个账号失败不会让整个请求 5xx；只有出现“账号配置读取失败”这种全局异常才返回错误结构。

## 4. 服务层 Spec

### 4.1 自动预约即时巡检

#### 4.1.0 手动预约 `SearchFilters` 规则（与自动巡检的差异点）

手动检查的目标是“立刻把当天剩余时段都订满”，因此**不沿用** `build_automation_reserve_filters`，而是新增一个专用函数：

```python
# library-window/src/wuyi_seat_bot/web_automation_service.py
def build_manual_reserve_filters(
    plan: AutomationPlan,
    search_page_payload: dict[str, Any],
    target_date: str,
    now: datetime,
) -> SearchFilters | None:
    """手动检查预约时使用的 SearchFilters 构造器。

    - 当 target_date 为今天：start_hour = max(当前小时, minBeginTime)，
      duration_hours = maxEndTime - start_hour，均受 build_time_options / build_duration_options 校验。
    - 当 target_date 为未来日期（仅 continuous_reserve=true 时走到这里）：
      复用 build_automation_future_default_filters，保持与自动定时巡检一致。
    - 当 target_date 为今天但当前小时已超过最晚起订时间 → 返回 None，由上层记为 skipped。
    """
```

细节：

- 取 `current_hour = now.astimezone(SHANGHAI_TZ).hour if now.tzinfo else now.hour`（已有 `SHANGHAI_TZ` 助手）。
- `min_begin = int(range_data["minBeginTime"])`；`max_end = int(range_data["maxEndTime"])`。
- `start_hour = max(current_hour, min_begin)`；若 `start_hour > max_end - int(range_data["min_duration"])` → 返回 `None`。
- `duration_hours = max_end - start_hour`；再用 `build_duration_options(..., start_hour=start_hour)` 校验，若 `duration_hours` 不在合法集合里，降到该集合里的最大值。
- `people_count = 1`（与自动巡检一致，学校接口不支持直接多人）。
- 最终调用现有 `validate_filters` 做统一校验，异常向上抛，让 `_run_manual_reserve_check_for_plan` 归类为 `failed`。

并新增一个辅助，组装“当天 + 未来日期”的目标日期列表，直接复用 `build_automation_target_dates` 即可，不需要改动。

在 `execute_automation_reserve` 的基础上抽出一个 **新的** 顶层函数，和它共用补订骨架但注入不同的 filters 构造器：

```python
def execute_manual_reserve_check(
    *,
    plan: AutomationPlan,
    now: datetime,
    search_page_payload: dict[str, Any],
    booking_list_payload: dict[str, Any],
    reserve_once: Callable[[SearchFilters], str | None],
    wait_reserve_gap: Callable[[str], None],
) -> AutomationActionResult:
    ...
```

内部循环与 `execute_automation_reserve` 相同，仅在拿 `filters` 时改用 `build_manual_reserve_filters`。为避免复制，可把现有 `execute_automation_reserve` 提炼出一个内部骨架 `_execute_reserve_loop(filters_builder, ...)`，两个入口各传一个 `filters_builder`。

测试补充：
- `build_manual_reserve_filters`：
  - `now=15:40` + `minBeginTime=8, maxEndTime=22, min_duration=1` → `start_hour=15, duration_hours=7`。
  - `now=07:10` + 同上 → `start_hour=8, duration_hours=14`。
  - `now=21:30` + 同上 → `start_hour=21, duration_hours=1`。
  - `now=22:10` → 返回 `None`。
  - `duration_options` 只允许 `{1,2,3}` 时，22-15=7 → 降到 3，并断言 `validate_filters` 通过。

#### 4.1.1 Application 层

新增方法：

```python
# library-window/src/wuyi_seat_bot/web_server.py
def run_automation_reserve_now(
    self, payload: dict[str, Any]
) -> dict[str, Any]:
    plans = [
        plan for plan in self.automation_scheduler.list_plans()
        if plan.enabled and plan.reserve_enabled
    ]
    if not plans:
        return build_manual_reserve_check_response(results=[])

    results: list[dict[str, Any]] = []
    now = datetime.now()
    for plan in plans:
        result_entry = self._run_manual_reserve_check_for_plan(plan, now)
        results.append(result_entry)
    return build_manual_reserve_check_response(results=results)
```

- 单计划执行：
  - 调用新增的 `_execute_manual_reserve_check_for_plan(plan, now)`，它内部使用 `execute_manual_reserve_check`（见 §4.1.0）而非 `_execute_automation_reserve`，以便套用“当前小时 → maxEndTime”的规则。
  - 然后调用 `self.automation_scheduler.apply_manual_reserve_result(plan.plan_id, now, result)`，让计划的 `reserve_last_run_at` / `reserve_next_run_at` 等字段被一并更新。需要在 `LocalAutomationPlanScheduler` 上新增一个公开方法：

    ```python
    def apply_manual_reserve_result(
        self, plan_id: str, now: datetime, result: AutomationActionResult
    ) -> AutomationPlan | None:
        with self._lock:
            current_plan = self._plans.get(plan_id)
            if current_plan is None:
                return None
            updated = _apply_action_result(current_plan, "reserve", now, result)
            self._plans[plan_id] = updated
            self._save_locked()
            return updated
    ```

  - 异常时捕获为 `state=failed`，message 取异常 `str(exc)`。
  - 跳过条件：登录态文件不存在（`_account_state_path(plan.account_name).exists()` 判定）→ `state=skipped`，避免重复触发登录态错误。

- 状态归类规则（在新模块 `web_automation_service.py` 中实现 `classify_manual_reserve_result`）：
  - `result.target_dates` 为空：`skipped` + 原 message。
  - `result.target_dates` 全部出现在 `result.booked_dates` 且 `created_dates`（通过 message 解析或新增结构化字段）为零：`already-booked`。
  - 实际新增过预约：`booked`。
  - 否则：`failed`。

  > 备注：当前 `AutomationActionResult` 没有显式区分 `created_dates`，可在 `build_automation_reserve_result` 输出里补充 `created_dates: tuple[str, ...]` 字段，以便前后端都能稳健区分 `booked` / `already-booked`。这个改动属于内部数据结构调整，不影响外部 API。

- 响应组装：`build_manual_reserve_check_response(results)` 在 `web_automation_service.py` 中实现，负责统计 `bookedCount` 等字段并生成 `message` 文案。

- 路由注册：`web_routes.py` 增加 `("/api/automation-plans/check-now", ("run_automation_reserve_now", HTTPStatus.OK, True))`。

### 4.2 一键签到所有账号

新增方法：

```python
# library-window/src/wuyi_seat_bot/web_server.py
def checkin_all_accounts(self, payload: dict[str, Any]) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    for account_name in self.account_names:
        try:
            action_result = self._run_account_action_result(
                ActionType.CHECKIN, {"accountName": account_name}
            )
            results.append(
                {
                    "accountName": account_name,
                    "success": action_result.success,
                    "message": action_result.message,
                }
            )
        except ApiRequestError as exc:
            results.append(
                {
                    "accountName": account_name,
                    "success": False,
                    "message": exc.message,
                }
            )
    return build_checkin_all_response(results)
```

- `build_checkin_all_response(results)` 放在新文件 `web_action_service.py` 末尾或新建辅助 `build_checkin_all_response` 函数中，负责统计 `successCount` / `failedCount` 并生成 toast 文案。
- 顺序执行（不并发）。
- 若 `account_names` 为空，返回 `{message: "没有账号，先新建后再签到", results: []}`，HTTP 200。
- 路由注册：`web_routes.py` 增加 `("/api/accounts/checkin-all", ("checkin_all_accounts", HTTPStatus.OK, True))`。

## 5. 数据结构变更

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `AutomationActionResult` | 新增 `created_dates: tuple[str, ...] = ()` | 补订成功的日期，便于上层判断 `booked` vs `already-booked`。仅内存结构，不影响序列化。 |
| `AutomationPlan` 持久化 | 无变更 | 不引入新字段。 |
| `config.json` | 无变更 | 不引入新字段。 |

## 6. 错误与日志

- 后端使用 `logger`（已有 `logging.getLogger(__name__)` 的子项目惯例）记录每个账号的执行耗时和结果摘要。
- 前端 `requestJsonWithBody` 自动写入“调试中心”操作日志（label：`手动检查预约` / `一键签到所有账号`）。
- 异常分类：
  - 后端非业务异常（如配置读取失败）：抛出 `ApiRequestError`，让前端 toast 显示统一错误。
  - 单账号业务异常：归入 `results[i]` 中，整体响应仍 200。

## 7. 测试策略

| 层级 | 用例 |
| --- | --- |
| 单元测试 (`tests/test_web_automation_service.py`) | `classify_manual_reserve_result` / `build_manual_reserve_check_response` 的纯函数测试，覆盖 booked / already-booked / failed / skipped 四种 state。 |
| 单元测试 (`tests/test_web_action_service.py`) | `build_checkin_all_response` 的统计 / 文案。 |
| 服务测试 (`tests/test_web_server_routes.py`) | 用现有 fake runtime 模拟两条新路由的 happy path 与异常 path。 |
| 端到端 (手动) | 在 Windows 真机上：删除当天预约 → 点“手动检查预约”，验证立刻补订；点“一键签到所有账号”，验证 toast 与状态页同步。 |

> 受测命令：`cd library-window; uv run --extra test python -m pytest tests/test_web_automation_service.py tests/test_web_action_service.py tests/test_web_server_routes.py`

## 8. 兼容性 & 回滚

- 新接口与新按钮均为可选添加项，旧版前端不会调用，旧版后端遇到旧前端也不会报错。
- 回滚方案：移除新路由、按钮、文案与对应测试即可，不会触发持久化迁移。
