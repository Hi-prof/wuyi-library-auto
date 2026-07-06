# Server Dashboard Health UX Design

## Goal

Improve the server UI so an operator can open `/` and immediately understand whether the library automation service needs attention.

The dashboard should answer three questions without requiring the user to inspect account rows manually:

1. Is the system currently healthy?
2. Which accounts need action?
3. What is the next useful action for each problem?

## Scope

This first phase is limited to the server dashboard experience.

In scope:

- Add a health summary section to `/`.
- Add a prioritized "needs attention" list above the existing seat distribution.
- Keep the current seat distribution cards as the detailed reservation view.
- Reuse existing account snapshots, account records, and recent status fields.
- Add focused tests for dashboard rendering and health context construction.

Out of scope:

- Changing reservation, login, account-pool, or automation-task behavior.
- Redesigning `/accounts`, `/automation-tasks`, or client token pages.
- Adding live push updates or background polling in the browser.
- Introducing a new frontend framework.

## User Experience

The dashboard remains the first screen. It changes from a seat-first page into a status-first control surface.

Top order:

1. Header and primary actions.
2. Health summary strip.
3. Needs-attention list.
4. Existing self-study-room reservation distribution.

The health summary shows one overall state:

- `运行正常`: no actionable issues are detected.
- `需要处理`: one or more accounts need action.
- `尚未检测`: accounts exist, but no account has a recent check result.

The summary also shows compact counters:

- Total accounts.
- Today checked in.
- Today not reserved.
- Needs attention.
- Login-state issues, when detectable from status text.

The needs-attention list is intentionally small and action-oriented. Each item includes:

- Student ID and display name.
- Issue label.
- Short reason.
- Last check time.
- Recommended actions: `立即检测`, `刷新登录态`, and `查看详情` where applicable.

If there are no issues, the section shows a quiet normal state instead of disappearing. This avoids making users guess whether the page failed to load.

## Issue Classification

The first implementation uses conservative classification from existing fields.

Issue types:

- `未预约`: snapshot indicates the account is not reserved today.
- `登录态异常`: status text contains login failure, login expired, credential, or authentication failure wording.
- `检测失败`: status text looks abnormal and is not one of the known normal prefixes.
- `尚未检测`: the account has no `last_check_at`.

Priority order:

1. Login-state issue.
2. Detection failure.
3. Not reserved today.
4. Never checked.

The backend should avoid guessing beyond available data. If a condition is ambiguous, present the existing status text as the reason instead of inventing a cause.

## Backend Design

Add a dashboard health context builder near the existing dashboard summary logic.

Proposed shape:

```python
{
    "overallState": "healthy" | "attention" | "unchecked",
    "overallLabel": "...",
    "overallTone": "success" | "warning" | "muted",
    "counters": {
        "accountCount": int,
        "checkedInTodayCount": int,
        "notReservedTodayCount": int,
        "attentionCount": int,
        "loginIssueCount": int,
        "uncheckedCount": int,
    },
    "attentionItems": [
        {
            "accountId": int,
            "studentId": str,
            "accountName": str,
            "issueType": str,
            "issueLabel": str,
            "reason": str,
            "tone": str,
            "lastCheckLabel": str,
            "recommendedActions": tuple[str, ...],
        }
    ],
}
```

`dashboard()` in `web/app.py` passes this context to `dashboard.html`.

The builder should reuse:

- `build_dashboard_summary(...)`
- `summary["accountSnapshots"]`
- `services.account_service.list_accounts()`
- existing datetime formatting helpers

No database schema changes are required.

## Template Design

`dashboard.html` adds two sections before the seat distribution panel:

- `.health-overview-panel`: overall state and counters.
- `.attention-list-panel`: actionable account list or normal empty state.

The existing action buttons remain in the hero. Button priority should be clearer:

- Primary: `立刻检查并补齐预约`.
- Secondary: `补约日志`, `号池管理`.

Each attention item uses a compact row/card that works on desktop and mobile. The row must not depend on hover-only interactions because mobile usage is expected.

## Styling

Keep the current visual language, but make this area more operational and less decorative:

- Use restrained cards with radius no larger than the surrounding dashboard style already uses.
- Use clear status tones: green for normal, amber for warning, muted for unchecked, red only for destructive or severe login/detection failures.
- Avoid adding new palette families or ornamental backgrounds.
- Ensure long status messages wrap instead of overflowing.

## Error Handling

The health builder should be defensive:

- Missing snapshots should not break the page.
- Missing `last_check_at` should produce `尚未检测`.
- Unknown status text should appear as the reason with a generic `检测失败` label.
- If no accounts exist, the dashboard should show a clear empty state and keep the existing seat distribution empty state.

## Testing

Add or extend tests around the dashboard:

- Healthy accounts render `运行正常`.
- An account with no reservation renders a `未预约` attention item.
- An abnormal login status renders a `登录态异常` attention item.
- A never-checked account renders `尚未检测`.
- The existing seat distribution remains visible.

Tests should target the backend context builder where possible and one end-to-end template rendering path through `TestClient`.

## Acceptance Criteria

- Opening `/` shows overall health before reservation distribution.
- Users can identify problematic accounts without opening `/accounts`.
- Each problem row has a clear recommended action.
- Existing reservation cards still render as before.
- No existing server workflow or client API behavior changes.
