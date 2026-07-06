# Server Dashboard Health UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/` show service health and actionable account issues before the existing reservation distribution.

**Architecture:** Add a pure dashboard-health context builder in `prevent_auto.web.runtime`, then pass that context from the existing `dashboard()` route into `dashboard.html`. Keep the existing seat distribution flow intact and add focused template/CSS changes for the new health and attention sections.

**Tech Stack:** Python 3.10+, FastAPI, Jinja2 templates, SQLite-backed repositories, plain CSS, unittest-style pytest tests.

## Global Constraints

- Keep the first phase limited to the server dashboard experience.
- Do not change reservation, login, account-pool, or automation-task behavior.
- Do not redesign `/accounts`, `/automation-tasks`, or client token pages.
- Do not add browser polling, push updates, a new frontend framework, or database schema changes.
- Reuse existing account snapshots, account records, recent status fields, and datetime formatting helpers.
- Existing reservation cards must still render after the new health sections.
- Long status messages must wrap instead of overflowing.

---

## File Structure

- Modify `library-fwq/src/prevent_auto/web/runtime.py`: add `build_dashboard_health(summary)` and private classification helpers near `build_dashboard_summary`.
- Modify `library-fwq/src/prevent_auto/web/app.py`: import and pass `dashboard_health` to `dashboard.html`.
- Modify `library-fwq/src/prevent_auto/web/templates/dashboard.html`: render the health summary and needs-attention list above the existing seat distribution.
- Modify `library-fwq/src/prevent_auto/web/static/styles.css`: style the new dashboard health sections in the existing design language.
- Modify `library-fwq/tests/test_web_app.py`: add backend context tests and one route/template rendering regression.

---

### Task 1: Dashboard Health Context Builder

**Files:**
- Modify: `library-fwq/src/prevent_auto/web/runtime.py`
- Test: `library-fwq/tests/test_web_app.py`

**Interfaces:**
- Consumes: `summary: dict[str, object]` shaped like `build_dashboard_summary(...)`.
- Produces: `build_dashboard_health(summary: dict[str, object]) -> dict[str, object]`.
- Produces `dashboard_health["attentionItems"]` as a list of dictionaries with `accountId`, `studentId`, `accountName`, `issueType`, `issueLabel`, `reason`, `tone`, `lastCheckLabel`, and `recommendedActions`.

- [ ] **Step 1: Write failing tests for health classification**

Add this import in `library-fwq/tests/test_web_app.py`:

```python
from prevent_auto.web.runtime import AUTO_RESERVATION_DETAILED_LOG_KEY
from prevent_auto.web.runtime import build_dashboard_health
```

Replace the existing single import of `AUTO_RESERVATION_DETAILED_LOG_KEY` with the two-line import above.

Add this helper near the top of `library-fwq/tests/test_web_app.py`, before `class WebAppTestCase`:

```python
def _health_snapshot(
    *,
    account_id: int = 1,
    student_id: str = "20231121130",
    name: str = "主号",
    current_status: str = "状态检测：正常",
    last_check_label: str = "2026年07月05日08时10分00秒",
    checked_in: bool = False,
    not_reserved: bool = False,
) -> dict[str, object]:
    return {
        "id": account_id,
        "name": name,
        "studentId": student_id,
        "currentStatus": current_status,
        "lastCheckLabel": last_check_label,
        "isCheckedInToday": checked_in,
        "isNotReservedToday": not_reserved,
    }
```

Add these tests inside `WebAppTestCase` after `test_dashboard_page_renders_summary`:

```python
    def test_dashboard_health_reports_normal_state(self) -> None:
        health = build_dashboard_health(
            {
                "accountCount": 1,
                "checkedInTodayCount": 1,
                "notReservedTodayCount": 0,
                "accountSnapshots": [
                    _health_snapshot(checked_in=True),
                ],
            }
        )

        self.assertEqual(health["overallState"], "healthy")
        self.assertEqual(health["overallLabel"], "运行正常")
        self.assertEqual(health["counters"]["attentionCount"], 0)
        self.assertEqual(health["attentionItems"], [])

    def test_dashboard_health_prioritizes_login_issue(self) -> None:
        health = build_dashboard_health(
            {
                "accountCount": 1,
                "checkedInTodayCount": 0,
                "notReservedTodayCount": 0,
                "accountSnapshots": [
                    _health_snapshot(
                        current_status="刷新登录失败：账号密码错误",
                        not_reserved=True,
                    ),
                ],
            }
        )

        self.assertEqual(health["overallState"], "attention")
        self.assertEqual(health["counters"]["loginIssueCount"], 1)
        self.assertEqual(health["attentionItems"][0]["issueType"], "login")
        self.assertEqual(health["attentionItems"][0]["issueLabel"], "登录态异常")
        self.assertIn("刷新登录态", health["attentionItems"][0]["recommendedActions"])

    def test_dashboard_health_reports_not_reserved_account(self) -> None:
        health = build_dashboard_health(
            {
                "accountCount": 1,
                "checkedInTodayCount": 0,
                "notReservedTodayCount": 1,
                "accountSnapshots": [
                    _health_snapshot(
                        current_status="今日无预约",
                        not_reserved=True,
                    ),
                ],
            }
        )

        self.assertEqual(health["overallState"], "attention")
        self.assertEqual(health["attentionItems"][0]["issueType"], "not_reserved")
        self.assertEqual(health["attentionItems"][0]["issueLabel"], "未预约")
        self.assertIn("立即检测", health["attentionItems"][0]["recommendedActions"])

    def test_dashboard_health_reports_unchecked_account(self) -> None:
        health = build_dashboard_health(
            {
                "accountCount": 1,
                "checkedInTodayCount": 0,
                "notReservedTodayCount": 0,
                "accountSnapshots": [
                    _health_snapshot(
                        current_status="尚未检测",
                        last_check_label="尚未检测",
                    ),
                ],
            }
        )

        self.assertEqual(health["overallState"], "unchecked")
        self.assertEqual(health["counters"]["uncheckedCount"], 1)
        self.assertEqual(health["attentionItems"][0]["issueType"], "unchecked")
        self.assertEqual(health["attentionItems"][0]["issueLabel"], "尚未检测")
```

- [ ] **Step 2: Run tests to verify they fail**

Run from repo root:

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
.\.venv\Scripts\python.exe -m pytest tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_normal_state tests/test_web_app.py::WebAppTestCase::test_dashboard_health_prioritizes_login_issue tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_not_reserved_account tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_unchecked_account -q
```

Expected: FAIL with an import error for `build_dashboard_health`.

- [ ] **Step 3: Add minimal health builder implementation**

In `library-fwq/src/prevent_auto/web/runtime.py`, add these constants after `AUTO_RESERVATION_DETAILED_LOG_KEY`:

```python
DASHBOARD_LOGIN_ISSUE_KEYWORDS = (
    "登录失败",
    "刷新登录失败",
    "登录态失效",
    "登录态过期",
    "账号密码错误",
    "密码错误",
    "认证失败",
    "凭据",
)

DASHBOARD_NORMAL_STATUS_PREFIXES = NORMAL_STATUS_PREFIXES + (
    "今日无预约",
)
```

Add this public builder after `build_dashboard_summary(...)`:

```python
def build_dashboard_health(summary: dict[str, object]) -> dict[str, object]:
    snapshots = [
        item
        for item in summary.get("accountSnapshots", [])
        if isinstance(item, dict)
    ]
    attention_items = [
        item
        for item in (
            _classify_dashboard_health_snapshot(snapshot)
            for snapshot in snapshots
        )
        if item is not None
    ]
    attention_items.sort(key=lambda item: (item["priority"], item["studentId"]))
    for item in attention_items:
        item.pop("priority", None)

    unchecked_count = sum(
        1 for snapshot in snapshots if _is_unchecked_dashboard_snapshot(snapshot)
    )
    login_issue_count = sum(
        1 for item in attention_items if item["issueType"] == "login"
    )
    account_count = int(summary.get("accountCount") or len(snapshots))
    if account_count == 0:
        overall_state = "unchecked"
        overall_label = "暂无账号"
        overall_tone = "muted"
    elif attention_items:
        overall_state = "attention"
        overall_label = "需要处理"
        overall_tone = "warning"
    elif unchecked_count == account_count:
        overall_state = "unchecked"
        overall_label = "尚未检测"
        overall_tone = "muted"
    else:
        overall_state = "healthy"
        overall_label = "运行正常"
        overall_tone = "success"

    return {
        "overallState": overall_state,
        "overallLabel": overall_label,
        "overallTone": overall_tone,
        "counters": {
            "accountCount": account_count,
            "checkedInTodayCount": int(summary.get("checkedInTodayCount") or 0),
            "notReservedTodayCount": int(summary.get("notReservedTodayCount") or 0),
            "attentionCount": len(attention_items),
            "loginIssueCount": login_issue_count,
            "uncheckedCount": unchecked_count,
        },
        "attentionItems": attention_items,
    }
```

Add these helpers below `build_dashboard_health(...)`:

```python
def _classify_dashboard_health_snapshot(
    snapshot: dict[str, object],
) -> dict[str, object] | None:
    status_text = str(snapshot.get("currentStatus", "") or "").strip()
    last_check_label = str(snapshot.get("lastCheckLabel", "") or "").strip()
    reason = status_text or "尚未检测"
    base_item = {
        "accountId": int(snapshot.get("id") or 0),
        "studentId": str(snapshot.get("studentId", "") or "").strip(),
        "accountName": str(snapshot.get("name", "") or "").strip(),
        "reason": reason,
        "lastCheckLabel": last_check_label or "尚未检测",
    }
    if _is_login_issue_status(status_text):
        return {
            **base_item,
            "priority": 0,
            "issueType": "login",
            "issueLabel": "登录态异常",
            "tone": "danger",
            "recommendedActions": ("刷新登录态", "查看详情"),
        }
    if _is_unchecked_dashboard_snapshot(snapshot):
        return {
            **base_item,
            "priority": 3,
            "issueType": "unchecked",
            "issueLabel": "尚未检测",
            "tone": "muted",
            "recommendedActions": ("立即检测", "查看详情"),
        }
    if _is_abnormal_dashboard_status(status_text):
        return {
            **base_item,
            "priority": 1,
            "issueType": "check_failed",
            "issueLabel": "检测失败",
            "tone": "danger",
            "recommendedActions": ("立即检测", "查看详情"),
        }
    if bool(snapshot.get("isNotReservedToday")):
        return {
            **base_item,
            "priority": 2,
            "issueType": "not_reserved",
            "issueLabel": "未预约",
            "tone": "warning",
            "recommendedActions": ("立即检测", "查看详情"),
        }
    return None


def _is_login_issue_status(status_text: str) -> bool:
    return any(keyword in status_text for keyword in DASHBOARD_LOGIN_ISSUE_KEYWORDS)


def _is_abnormal_dashboard_status(status_text: str) -> bool:
    text = status_text.strip()
    if not text:
        return False
    return not any(
        text.startswith(prefix) for prefix in DASHBOARD_NORMAL_STATUS_PREFIXES
    )


def _is_unchecked_dashboard_snapshot(snapshot: dict[str, object]) -> bool:
    last_check_label = str(snapshot.get("lastCheckLabel", "") or "").strip()
    status_text = str(snapshot.get("currentStatus", "") or "").strip()
    return last_check_label in {"", "尚未检测"} or status_text in {"", "尚未检测"}
```

- [ ] **Step 4: Run health builder tests**

Run:

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
.\.venv\Scripts\python.exe -m pytest tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_normal_state tests/test_web_app.py::WebAppTestCase::test_dashboard_health_prioritizes_login_issue tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_not_reserved_account tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_unchecked_account -q
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```powershell
git add library-fwq/src/prevent_auto/web/runtime.py library-fwq/tests/test_web_app.py
git commit -m "feat: build dashboard health context"
```

---

### Task 2: Dashboard Route and Template Rendering

**Files:**
- Modify: `library-fwq/src/prevent_auto/web/app.py`
- Modify: `library-fwq/src/prevent_auto/web/templates/dashboard.html`
- Test: `library-fwq/tests/test_web_app.py`

**Interfaces:**
- Consumes: `build_dashboard_health(summary: dict[str, object]) -> dict[str, object]` from Task 1.
- Produces: Template context key `dashboard_health`.

- [ ] **Step 1: Write failing route/template rendering test**

Add this test inside `WebAppTestCase`, after the Task 1 health tests:

```python
    def test_dashboard_page_renders_health_sections_before_seat_distribution(self) -> None:
        self.login()

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("运行状态", response.text)
        self.assertIn("待处理事项", response.text)
        self.assertIn("尚未检测", response.text)
        self.assertLess(
            response.text.index("运行状态"),
            response.text.index("自习室预约分布"),
        )
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
.\.venv\Scripts\python.exe -m pytest tests/test_web_app.py::WebAppTestCase::test_dashboard_page_renders_health_sections_before_seat_distribution -q
```

Expected: FAIL because `运行状态` and `待处理事项` are not rendered yet.

- [ ] **Step 3: Wire dashboard health into the route**

In `library-fwq/src/prevent_auto/web/app.py`, update the runtime imports to include `build_dashboard_health`:

```python
from prevent_auto.web.runtime import (
    AUTO_RESERVATION_DETAILED_LOG_KEY,
    build_dashboard_health,
    build_dashboard_summary,
    build_services,
    _format_iso_datetime,
    _format_room_seat_label,
    start_background_workers as start_runtime_background_workers,
    start_pool_reaper_async,
    stop_background_workers as stop_runtime_background_workers,
    stop_pool_reaper_async,
)
```

Inside `dashboard(...)`, after `summary = build_dashboard_summary(...)`, add:

```python
        dashboard_health = build_dashboard_health(summary)
```

In the template context, add:

```python
                "dashboard_health": dashboard_health,
```

The resulting local section should look like:

```python
        services = app.state.services
        summary = build_dashboard_summary(services, settings=settings)
        dashboard_health = build_dashboard_health(summary)
        all_accounts = services.account_service.list_accounts()
```

- [ ] **Step 4: Render health sections in `dashboard.html`**

In `library-fwq/src/prevent_auto/web/templates/dashboard.html`, insert this block after `{% include "_auto_reservation_log_dialog.html" %}` and before the existing `<section class="dashboard-summary-shell seat-display-summary-shell">`:

```jinja
<section class="panel dashboard-health-panel state-{{ dashboard_health.overallTone }}">
  <div class="dashboard-health-main">
    <div>
      <span class="status-pill {{ dashboard_health.overallTone }}">运行状态</span>
      <h3>{{ dashboard_health.overallLabel }}</h3>
      <p class="panel-subtitle">
        {% if dashboard_health.overallState == "healthy" %}
        当前没有发现需要处理的账号。
        {% elif dashboard_health.overallState == "unchecked" %}
        账号还没有形成完整检测结果，建议先执行一次检查。
        {% else %}
        有 {{ dashboard_health.counters.attentionCount }} 个账号需要处理。
        {% endif %}
      </p>
    </div>
    <div class="dashboard-health-counters" aria-label="运行状态统计">
      <article>
        <span>账号</span>
        <strong>{{ dashboard_health.counters.accountCount }}</strong>
      </article>
      <article>
        <span>今日已签到</span>
        <strong>{{ dashboard_health.counters.checkedInTodayCount }}</strong>
      </article>
      <article>
        <span>今日未预约</span>
        <strong>{{ dashboard_health.counters.notReservedTodayCount }}</strong>
      </article>
      <article>
        <span>需处理</span>
        <strong>{{ dashboard_health.counters.attentionCount }}</strong>
      </article>
      <article>
        <span>登录态异常</span>
        <strong>{{ dashboard_health.counters.loginIssueCount }}</strong>
      </article>
    </div>
  </div>
</section>

<section class="panel dashboard-attention-panel">
  <div class="panel-heading-row">
    <div>
      <h3>待处理事项</h3>
      <p class="panel-subtitle">按影响程度排序，优先处理登录态和检测失败。</p>
    </div>
  </div>
  {% if dashboard_health.attentionItems %}
  <div class="dashboard-attention-list">
    {% for item in dashboard_health.attentionItems %}
    <article class="dashboard-attention-item state-{{ item.tone }}">
      <div class="dashboard-attention-copy">
        <span class="status-pill {{ item.tone }}">{{ item.issueLabel }}</span>
        <h4>
          {{ item.studentId }}
          {% if item.accountName and item.accountName != item.studentId %}
          <small>{{ item.accountName }}</small>
          {% endif %}
        </h4>
        <p>{{ item.reason }}</p>
        <span class="dashboard-attention-time">最近检测：{{ item.lastCheckLabel }}</span>
      </div>
      <div class="dashboard-attention-actions">
        {% if "立即检测" in item.recommendedActions %}
        <form method="post" action="/accounts/{{ item.accountId }}/check-now">
          <button class="button primary" type="submit">立即检测</button>
        </form>
        {% endif %}
        {% if "刷新登录态" in item.recommendedActions %}
        <form method="post" action="/accounts/{{ item.accountId }}/refresh-login">
          <button class="button ghost" type="submit">刷新登录态</button>
        </form>
        {% endif %}
        <a class="button ghost" href="/accounts/{{ item.accountId }}">查看详情</a>
      </div>
    </article>
    {% endfor %}
  </div>
  {% else %}
  <p class="dashboard-attention-empty">当前没有待处理事项。</p>
  {% endif %}
</section>
```

- [ ] **Step 5: Run route/template test**

Run:

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
.\.venv\Scripts\python.exe -m pytest tests/test_web_app.py::WebAppTestCase::test_dashboard_page_renders_health_sections_before_seat_distribution -q
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```powershell
git add library-fwq/src/prevent_auto/web/app.py library-fwq/src/prevent_auto/web/templates/dashboard.html library-fwq/tests/test_web_app.py
git commit -m "feat: show dashboard health sections"
```

---

### Task 3: Dashboard Health Styling and Regression Verification

**Files:**
- Modify: `library-fwq/src/prevent_auto/web/static/styles.css`
- Test: `library-fwq/tests/test_web_app.py`

**Interfaces:**
- Consumes: Template classes from Task 2: `dashboard-health-panel`, `dashboard-health-counters`, `dashboard-attention-panel`, `dashboard-attention-list`, `dashboard-attention-item`, `dashboard-attention-actions`.
- Produces: Responsive, wrapping-safe dashboard health styles.

- [ ] **Step 1: Write failing regression test for actionable controls**

Add this test inside `WebAppTestCase`, after the Task 2 route/template test:

```python
    def test_dashboard_attention_item_includes_recommended_actions(self) -> None:
        with connect_database(self.database_path) as connection:
            connection.execute(
                """
                UPDATE accounts
                SET last_check_at = '2026-07-05T08:10:00+08:00',
                    last_status = '刷新登录失败：账号密码错误'
                WHERE id = 1
                """
            )
        self.login()

        response = self.client.get("/")

        self.assertEqual(response.status_code, 200)
        self.assertIn("登录态异常", response.text)
        self.assertIn("刷新登录态", response.text)
        self.assertIn("/accounts/1/refresh-login", response.text)
        self.assertIn("查看详情", response.text)
```

- [ ] **Step 2: Run the new regression test**

Run:

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
.\.venv\Scripts\python.exe -m pytest tests/test_web_app.py::WebAppTestCase::test_dashboard_attention_item_includes_recommended_actions -q
```

Expected: PASS if Task 2 already rendered the attention actions. If it fails, fix the template action block so login issues render both `刷新登录态` and `查看详情`.

- [ ] **Step 3: Add dashboard health CSS**

Append this CSS near the dashboard/seat-display styles in `library-fwq/src/prevent_auto/web/static/styles.css`, before the `.seat-display-summary-shell` rule:

```css
.dashboard-health-panel {
  margin-bottom: 16px;
  border-radius: 8px;
  box-shadow: none;
}

.dashboard-health-panel.state-success {
  border-color: rgba(51, 110, 73, 0.22);
}

.dashboard-health-panel.state-warning {
  border-color: rgba(232, 184, 41, 0.34);
}

.dashboard-health-panel.state-muted {
  border-color: rgba(31, 26, 23, 0.12);
}

.dashboard-health-main {
  display: grid;
  grid-template-columns: minmax(240px, 0.8fr) minmax(0, 1.2fr);
  gap: 18px;
  align-items: start;
}

.dashboard-health-main h3 {
  margin: 10px 0 0;
  font-size: 28px;
}

.dashboard-health-counters {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.dashboard-health-counters article {
  display: grid;
  gap: 6px;
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(31, 26, 23, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.62);
}

.dashboard-health-counters span,
.dashboard-attention-time {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.4;
}

.dashboard-health-counters strong {
  font-family: var(--heading-font);
  font-size: 28px;
  line-height: 1;
}

.dashboard-attention-panel {
  border-radius: 8px;
  box-shadow: none;
}

.dashboard-attention-list {
  display: grid;
  gap: 10px;
}

.dashboard-attention-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  align-items: center;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.68);
}

.dashboard-attention-item.state-danger {
  border-color: rgba(125, 41, 31, 0.24);
  background: rgba(253, 244, 242, 0.82);
}

.dashboard-attention-item.state-warning {
  border-color: rgba(232, 184, 41, 0.36);
  background: rgba(255, 251, 244, 0.84);
}

.dashboard-attention-item.state-muted {
  border-color: rgba(31, 26, 23, 0.12);
}

.dashboard-attention-copy {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.dashboard-attention-copy h4 {
  margin: 0;
  font-family: var(--heading-font);
  font-size: 20px;
  line-height: 1.2;
}

.dashboard-attention-copy h4 small {
  margin-left: 8px;
  color: var(--muted);
  font-family: var(--body-font);
  font-size: 13px;
  font-weight: 500;
}

.dashboard-attention-copy p {
  margin: 0;
  color: var(--ink);
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.dashboard-attention-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.dashboard-attention-actions form {
  margin: 0;
}

.dashboard-attention-actions .button {
  min-height: 38px;
  border-radius: 8px;
  padding: 0 14px;
}

.dashboard-attention-empty {
  margin: 0;
  padding: 16px;
  border: 1px dashed rgba(51, 110, 73, 0.28);
  border-radius: 8px;
  background: rgba(244, 252, 249, 0.72);
  color: #336e49;
  font-weight: 700;
}

@media (max-width: 900px) {
  .dashboard-health-main {
    grid-template-columns: 1fr;
  }

  .dashboard-health-counters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dashboard-attention-item {
    grid-template-columns: 1fr;
  }

  .dashboard-attention-actions {
    justify-content: stretch;
  }

  .dashboard-attention-actions form,
  .dashboard-attention-actions .button {
    flex: 1 1 130px;
  }
}
```

- [ ] **Step 4: Run focused dashboard tests**

Run:

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
.\.venv\Scripts\python.exe -m pytest tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_normal_state tests/test_web_app.py::WebAppTestCase::test_dashboard_health_prioritizes_login_issue tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_not_reserved_account tests/test_web_app.py::WebAppTestCase::test_dashboard_health_reports_unchecked_account tests/test_web_app.py::WebAppTestCase::test_dashboard_page_renders_health_sections_before_seat_distribution tests/test_web_app.py::WebAppTestCase::test_dashboard_attention_item_includes_recommended_actions -q
```

Expected: PASS.

- [ ] **Step 5: Run broader web app regression**

Run:

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
.\.venv\Scripts\python.exe -m pytest tests/test_web_app.py tests/test_web_base_template.py tests/test_web_base_rendering.py -q
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```powershell
git add library-fwq/src/prevent_auto/web/static/styles.css library-fwq/tests/test_web_app.py
git commit -m "style: polish dashboard health ux"
```

---

## Final Verification

- [ ] Run focused server tests:

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
.\.venv\Scripts\python.exe -m pytest tests/test_web_app.py tests/test_web_base_template.py tests/test_web_base_rendering.py -q
```

Expected: PASS.

- [ ] Confirm changed files:

```powershell
git status --short
```

Expected: only intentional dashboard UX files should be modified or committed; unrelated existing workspace changes should remain untouched.

## Self-Review Notes

- Spec coverage: Task 1 covers backend health context and classification; Task 2 covers page placement and actionable attention list; Task 3 covers styling, mobile wrapping, and regression verification.
- Type consistency: The plan consistently uses `build_dashboard_health(summary: dict[str, object]) -> dict[str, object]`, `dashboard_health`, and `attentionItems`.
- Scope control: No task changes reservation logic, account-pool rules, client APIs, schema, or pages outside `/`.
