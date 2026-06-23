import json
import os
import subprocess
import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
WEB_DIR = PROJECT_ROOT / "src" / "wuyi_seat_bot" / "web"
BUILD_WEB_ASSETS_SCRIPT = PROJECT_ROOT / "scripts" / "build_web_assets.py"


def _run_app_js_functions(
    function_names: tuple[str, ...],
    *,
    context_script: str,
    result_script: str,
) -> str:
    script_path = WEB_DIR / "app.js"
    function_sources = ",\n    ".join(
        f'extractFunction("{name}")' for name in function_names
    )
    node_script = f"""
const fs = require("fs");
const vm = require("vm");

const source = fs.readFileSync(process.argv[1], "utf8");

function extractFunction(name) {{
  const markers = [`function ${{name}}(`, `async function ${{name}}(`];
  const start = markers.map((marker) => source.indexOf(marker)).find((index) => index >= 0);
  if (start < 0) {{
    throw new Error(`missing function: ${{name}}`);
  }}
  const bodyStart = source.indexOf("{{", start);
  let depth = 0;
  for (let index = bodyStart; index < source.length; index += 1) {{
    const char = source[index];
    if (char === "{{") {{
      depth += 1;
    }} else if (char === "}}") {{
      depth -= 1;
      if (depth === 0) {{
        return source.slice(start, index + 1);
      }}
    }}
  }}
  throw new Error(`unclosed function: ${{name}}`);
}}

{context_script}
vm.runInContext(
  [
    {function_sources},
  ].join("\\n"),
  context,
);
{result_script}
"""
    result = subprocess.run(
        ["node", "-e", node_script, str(script_path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    )
    return result.stdout


class WebAssetsTestCase(unittest.TestCase):
    def test_app_js_matches_split_source_files(self) -> None:
        result = subprocess.run(
            [sys.executable, str(BUILD_WEB_ASSETS_SCRIPT), "--check"],
            cwd=PROJECT_ROOT,
            env={**os.environ, "PYTHONUTF8": "1"},
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
        )

        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)

    def test_app_js_resolves_today_start_hour_from_current_hour(self) -> None:
        result = _run_app_js_functions(
            (
                "resolveTaskDialogDefaultStartHour",
                "isSameLocalDate",
            ),
            context_script="""
const context = {
  Date: Date,
  getTaskDialogBootstrap: () => ({
    constraints: {
      minBeginTime: 8,
      maxEndTime: 22,
      minDuration: 1,
    },
  }),
};
vm.createContext(context);
""",
            result_script="""
const RealDate = Date;
context.Date = class extends RealDate {
  constructor(value) {
    super(value ?? "2026-04-02T08:20:00");
  }
  static now() {
    return new RealDate("2026-04-02T08:20:00").getTime();
  }
};
const first = context.resolveTaskDialogDefaultStartHour("2026-04-02", 8, 8);
context.Date = class extends RealDate {
  constructor(value) {
    super(value ?? "2026-04-02T08:40:00");
  }
  static now() {
    return new RealDate("2026-04-02T08:40:00").getTime();
  }
};
const second = context.resolveTaskDialogDefaultStartHour("2026-04-02", 8, 8);
process.stdout.write(JSON.stringify({ first, second }));
""",
        )

        self.assertEqual(json.loads(result), {"first": 8, "second": 8})

    def test_app_js_schedules_task_status_refresh_without_initial_fetch(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")
        initialize_start = script.index("async function initializePage()")
        bind_events_start = script.index("function bindEvents()", initialize_start)
        initialize_source = script[initialize_start:bind_events_start]

        self.assertIn("scheduleDailyTaskStatusRefresh();", initialize_source)
        self.assertNotIn("loadTaskStatuses(true)", initialize_source)
        self.assertIn("applyCachedTaskStatuses(data);", script)

    def test_app_js_schedules_next_task_status_refresh_at_0830(self) -> None:
        result = _run_app_js_functions(
            ("getNextTaskStatusRefreshDelay",),
            context_script="""
const context = {
  TASK_STATUS_REFRESH_HOUR: 8,
  TASK_STATUS_REFRESH_MINUTE: 30,
};
vm.createContext(context);
""",
            result_script="""
const before = context.getNextTaskStatusRefreshDelay(new Date("2026-05-08T08:29:00"));
const after = context.getNextTaskStatusRefreshDelay(new Date("2026-05-08T08:31:00"));
process.stdout.write(JSON.stringify({ before, after }));
""",
        )

        self.assertEqual(json.loads(result), {"before": 60_000, "after": 86_340_000})

    def test_index_contains_debug_center_panel(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertIn('data-view-target="debug"', html)
        self.assertIn("调试中心", html)
        self.assertIn('id="debugLogList"', html)
        self.assertIn('id="debugSnapshot"', html)
        self.assertIn('id="debugRequestFacts"', html)

    def test_index_contains_simplified_account_auth_controls(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertIn('id="accountRefreshLoginButton"', html)
        self.assertIn('id="accountStudentIdInput"', html)
        self.assertIn('id="accountPasswordInput"', html)
        self.assertIn("刷新认证", html)
        self.assertIn("留空则默认等于学号", html)

    def test_index_contains_account_dialog_controls(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertIn('id="accountDialog"', html)
        self.assertIn('id="accountDialogCloseButton"', html)
        self.assertIn('id="accountDialogCancelButton"', html)
        self.assertIn("没有账号也可以先空着", html)

    def test_index_places_seat_list_before_map_panel(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertLess(html.index('id="seatList"'), html.index('id="mapFrame"'))

    def test_index_contains_room_selectors_for_manual_and_automation_booking(
        self,
    ) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertIn('id="roomSelect"', html)
        self.assertIn('id="taskDialogRoomSelect"', html)

    def test_index_contains_task_dialog_and_status_controls(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertIn('id="taskDialog"', html)
        self.assertIn('data-view-target="status"', html)
        self.assertIn('id="taskStatusButton"', html)
        self.assertIn('id="taskStatusList"', html)
        self.assertIn('id="taskDialogAccountSelect"', html)
        self.assertIn('id="taskDialogContinuousCheckbox"', html)
        self.assertIn('id="taskDialogSeatNumberInput"', html)
        self.assertIn('id="taskDialogSeatNumberHint"', html)
        self.assertIn('id="taskDialogRefreshButton"', html)
        self.assertIn('id="taskDialogQuerySummary"', html)
        self.assertIn('id="taskDialogFeedback"', html)
        self.assertIn('id="taskDialogEyebrow"', html)
        self.assertIn('id="taskDialogTitle"', html)
        self.assertIn('id="taskCheckReserveButton"', html)
        self.assertIn("新建自动任务", html)
        self.assertIn("手动检查预约", html)
        self.assertIn("账号状态", html)
        self.assertIn("一键获取账号状态", html)
        self.assertIn("每天 08:30", html)
        self.assertIn("自动计划", html)

    def test_index_contains_bulk_account_checkin_control(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertIn('id="accountCheckinAllButton"', html)
        self.assertIn("一键签到所有账号", html)

    def test_index_contains_seat_display_page(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertIn('data-view-target="seat-display"', html)
        self.assertIn('data-view-panel="seat-display"', html)
        self.assertIn('id="seatDisplayRefreshButton"', html)
        self.assertIn('id="seatDisplaySummary"', html)
        self.assertIn('id="seatDisplayList"', html)
        self.assertIn("座位展示", html)

        self.assertLess(
            html.index('data-view-target="accounts"'),
            html.index('data-view-target="seat-display"'),
        )
        self.assertLess(
            html.index('data-view-target="seat-display"'),
            html.index('data-view-target="debug"'),
        )

    def test_bulk_checkin_button_is_inside_status_view_panel(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        status_panel_start = html.index('data-view-panel="status"')
        accounts_panel_start = html.index('data-view-panel="accounts"')
        button_position = html.index('id="accountCheckinAllButton"')

        self.assertLess(status_panel_start, button_position)
        self.assertLess(button_position, accounts_panel_start)

        accounts_panel_segment = html[accounts_panel_start:]
        self.assertNotIn('id="accountCheckinAllButton"', accounts_panel_segment)

    def test_index_places_status_menu_after_tasks_menu(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertLess(
            html.index('data-view-target="tasks"'),
            html.index('data-view-target="status"'),
        )

    def test_index_no_longer_contains_browser_settings_view(self) -> None:
        html = (WEB_DIR / "index.html").read_text(encoding="utf-8")

        self.assertNotIn('data-view-target="settings"', html)
        self.assertNotIn('data-view-panel="settings"', html)
        self.assertNotIn('id="settingsReloadButton"', html)
        self.assertNotIn("设置中心", html)

    def test_app_js_contains_debug_helpers(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertIn("function renderDebugPanel()", script)
        self.assertIn("function addDebugLog(", script)
        self.assertIn("debugRefreshBootstrapButton", script)
        self.assertIn("debugRetrySearchButton", script)
        self.assertIn("debugCopySnapshotButton", script)

    def test_app_js_contains_account_refresh_logic(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertIn("function refreshAccountLogin()", script)
        self.assertIn("/api/accounts/refresh-login", script)
        self.assertIn("accountRefreshLoginButton", script)

    def test_app_js_contains_manual_reserve_and_bulk_checkin_logic(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertIn("async function runManualReserveCheck()", script)
        self.assertIn("/api/automation-plans/check-now", script)
        self.assertIn("async function runCheckinAllAccounts()", script)
        self.assertIn("/api/accounts/checkin-all", script)

    def test_app_js_contains_account_dialog_helpers(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertIn("function openAccountDialog(", script)
        self.assertIn("function closeAccountDialog(", script)
        self.assertIn("accountDialogCloseButton", script)

    def test_app_js_contains_shared_action_helpers(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertIn("function requestJsonWithBody(", script)
        self.assertIn("async function runBusyButtonAction(", script)
        self.assertIn("async function createTaskBatch()", script)
        self.assertIn("async function loadTaskStatuses(", script)
        self.assertIn("function scheduleDailyTaskStatusRefresh(", script)
        self.assertIn("data-status-refresh-account", script)
        self.assertIn("async function submitTaskStatusAction(", script)
        self.assertIn("function getTaskStatusActionTargets(", script)
        self.assertIn("function buildTaskStatusActionConfigs(", script)
        self.assertIn("function buildTaskAccountGroups(", script)
        self.assertIn("function createTaskListItem(", script)
        self.assertIn("async function openTaskDialog(", script)
        self.assertIn("async function refreshTaskDialogQuery(", script)
        self.assertIn("function buildTaskDialogSearchPayload()", script)
        self.assertIn("function isTaskDialogQueryResultCurrent()", script)
        self.assertIn("function setTaskDialogFeedback(", script)
        self.assertIn("function getTaskDialogSeatContext()", script)
        self.assertIn("function buildTaskDialogScheduledRunAt(", script)
        self.assertIn("function renderSeatDisplay(", script)
        self.assertIn("data-seat-display-detail-account", script)
        self.assertIn("seat-display-chair-icon", script)
        self.assertIn("/api/checkin", script)
        self.assertIn("/api/bookings/cancel", script)
        self.assertIn("/api/automation-plans", script)
        self.assertIn("function buildTaskDialogPlanPayload()", script)
        self.assertIn("编辑计划", script)

    def test_changed_task_refreshes_only_changed_account_status(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")
        refresh_helper_start = script.index("async function refreshTaskStatusForChangedPlan(")
        create_task_start = script.index("async function createTaskBatch()")
        delete_task_start = script.index("async function deleteTask(", create_task_start)
        manual_check_start = script.index("async function runManualReserveCheck()", delete_task_start)
        refresh_helper_source = script[refresh_helper_start:create_task_start]
        create_task_source = script[create_task_start:delete_task_start]
        delete_task_source = script[delete_task_start:manual_check_start]

        self.assertIn("async function refreshTaskStatusForChangedPlan(", script)
        self.assertIn(
            "await refreshTaskStatusForChangedPlan(payload.accountName);",
            create_task_source,
        )
        self.assertIn(
            "await refreshTaskStatusForChangedPlan(accountName);",
            delete_task_source,
        )
        self.assertIn("loadSingleTaskStatus(changedAccountName", refresh_helper_source)
        self.assertNotIn("loadTaskStatuses(true)", refresh_helper_source)
        self.assertNotIn("loadTaskStatuses(true)", create_task_source)
        self.assertNotIn("loadTaskStatuses(true)", delete_task_source)
        self.assertIn("group.plan.accountName", script)

    def test_app_js_mentions_manual_status_actions(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertIn("立即签到", script)
        self.assertIn("已签到", script)
        self.assertIn("已预约", script)
        self.assertIn("立即签退", script)
        self.assertIn("需登录", script)

    def test_task_group_preview_labels_show_three_day_reserve_status(self) -> None:
        result = _run_app_js_functions(
            (
                "buildTaskGroupPreviewLabels",
                "buildReservePreviewSlotLabels",
                "isReservePreviewSlotBooked",
                "findActualReserveBooking",
                "hasReadyTaskStatusForReservePreview",
                "compactReserveWindowLabel",
                "getTaskStatusByAccountName",
            ),
            context_script="""
const context = {
  state: {
    taskStatuses: [
      {
        accountName: "主号",
        bookings: [
          { startAt: "2026-05-08T15:00:00+08:00", status: "0" },
          { startAt: "2026-05-09T08:00:00+08:00", status: "0" },
        ],
      },
    ],
  },
};
vm.createContext(context);
""",
            result_script="""
const labels = context.buildTaskGroupPreviewLabels({
  accountName: "主号",
  reserve: {
    enabled: true,
    bookedDates: ["2026-05-08"],
    previewSlots: [
      { date: "2026-05-08", label: "今天", windowLabel: "15:00 - 22:00", booked: false },
      { date: "2026-05-09", label: "明天", windowLabel: "8:00 - 22:00", booked: false },
      { date: "2026-05-10", label: "后天", windowLabel: "8:00 - 22:00", booked: false },
    ],
  },
});
console.log(JSON.stringify(labels));
""",
        )

        labels = json.loads(result)
        self.assertEqual(
            [label["text"] for label in labels],
            [
                "今天 15:00-22:00 · 已预约",
                "明天 8:00-22:00 · 已预约",
                "后天 8:00-22:00 · 未预约",
            ],
        )
        self.assertIn("status-success", labels[0]["className"])
        self.assertIn("status-success", labels[1]["className"])
        self.assertIn("status-pending", labels[2]["className"])

    def test_app_js_no_longer_contains_browser_settings_logic(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertNotIn("async function loadSettings(", script)
        self.assertNotIn("function renderSettings()", script)
        self.assertNotIn("switchSettingsSection(", script)
        self.assertNotIn("settingsReloadButton", script)
        self.assertNotIn("/api/settings", script)

    def test_app_js_marks_checked_in_state_as_signed_and_checkout_warning(self) -> None:
        result = _run_app_js_functions(
            (
                "findLatestPlanForAccount",
                "getTaskStatusActionTargets",
                "buildTaskStatusActionConfigs",
            ),
            context_script="""
const context = { state: { tasks: [] } };
vm.createContext(context);
""",
            result_script="""
const status = {
  accountName: "主号",
  loginStateReady: true,
  bookings: [
    {
      bookingId: "1",
      roomName: "自习室圆形二楼",
      seatNumber: "18",
      status: "1",
    },
  ],
};
const targets = context.getTaskStatusActionTargets(status);
const configs = context.buildTaskStatusActionConfigs(status, targets).map((item) => ({
  action: item.action,
  label: item.label,
  tone: item.tone,
  enabled: item.enabled,
  statusMode: item.statusMode,
}));
process.stdout.write(JSON.stringify(configs));
""",
        )

        self.assertEqual(
            result,
            '[{"action":"checkin","label":"已签到","tone":"success","enabled":false,"statusMode":true},{"action":"checkout","label":"立即签退","tone":"warning","enabled":true,"statusMode":false},{"action":"open-task-dialog","label":"新建自动任务","tone":"default","enabled":true,"statusMode":false}]',
        )

    def test_app_js_marks_reserved_state_as_reserved_success(self) -> None:
        result = _run_app_js_functions(
            (
                "findLatestPlanForAccount",
                "getTaskStatusActionTargets",
                "buildTaskStatusActionConfigs",
            ),
            context_script="""
const context = { state: { tasks: [] } };
vm.createContext(context);
""",
            result_script="""
const status = {
  accountName: "主号",
  loginStateReady: true,
  bookings: [
    {
      bookingId: "2",
      roomName: "自习室圆形二楼",
      seatNumber: "28",
      status: "0",
      checkinWindowOpen: false,
    },
  ],
};
const targets = context.getTaskStatusActionTargets(status);
const configs = context.buildTaskStatusActionConfigs(status, targets).map((item) => ({
  action: item.action,
  label: item.label,
  tone: item.tone,
  enabled: item.enabled,
  statusMode: item.statusMode,
}));
process.stdout.write(JSON.stringify(configs));
""",
        )

        self.assertEqual(
            result,
            '[{"action":"checkin","label":"待签到","tone":"neutral","enabled":false,"statusMode":false},{"action":"checkout","label":"未在馆","tone":"neutral","enabled":false,"statusMode":false},{"action":"cancel","label":"取消预约","tone":"warning","enabled":true,"statusMode":false},{"action":"open-task-dialog","label":"新建自动任务","tone":"default","enabled":true,"statusMode":false}]',
        )

    def test_app_js_create_plan_button_carries_status_booking_context(self) -> None:
        result = _run_app_js_functions(
            (
                "findLatestPlanForAccount",
                "getTaskStatusActionTargets",
                "buildTaskStatusActionConfigs",
            ),
            context_script="""
const context = {
  state: { tasks: [] },
};
vm.createContext(context);
""",
            result_script="""
const status = {
  accountName: "主号",
  loginStateReady: true,
  bookings: [
    {
      bookingId: "2",
      roomName: "自习室圆形二楼",
      seatNumber: "28",
      status: "0",
      checkinWindowOpen: false,
    },
  ],
};
const targets = context.getTaskStatusActionTargets(status);
const configs = context.buildTaskStatusActionConfigs(status, targets);
const createConfig = configs[configs.length - 1];
process.stdout.write(JSON.stringify(createConfig.booking));
""",
        )

        payload = json.loads(result)
        self.assertEqual(payload["roomName"], "自习室圆形二楼")
        self.assertEqual(payload["seatNumber"], "28")

        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")
        self.assertIn("data-room-name", script)
        self.assertIn("data-seat-number", script)

    def test_app_js_marks_create_plan_button_as_already_created_when_plan_exists(
        self,
    ) -> None:
        result = _run_app_js_functions(
            (
                "findLatestPlanForAccount",
                "getTaskStatusActionTargets",
                "buildTaskStatusActionConfigs",
            ),
            context_script="""
const context = {
  state: {
    tasks: [
      {
        planId: "plan-1",
        accountName: "主号",
        seatNumber: "18",
        roomName: "自习室圆形二楼",
        updatedAt: "2026-05-08T12:00:00",
      },
    ],
  },
};
vm.createContext(context);
""",
            result_script="""
const status = {
  accountName: "主号",
  loginStateReady: true,
  bookings: [],
};
const targets = context.getTaskStatusActionTargets(status);
const configs = context.buildTaskStatusActionConfigs(status, targets).map((item) => ({
  action: item.action,
  label: item.label,
  tone: item.tone,
  enabled: item.enabled,
  statusMode: item.statusMode,
}));
process.stdout.write(JSON.stringify(configs[configs.length - 1]));
""",
        )

        self.assertEqual(
            result,
            '{"action":"open-task-dialog","label":"已创建","tone":"success","enabled":false,"statusMode":true}',
        )

    def test_app_js_prefers_status_room_name_over_query_default_room(self) -> None:
        result = _run_app_js_functions(
            (
                "buildRoomOptionValue",
                "getQueryRooms",
                "findRoomByName",
                "resolveRoomSelectionValue",
            ),
            context_script="""
const context = {};
vm.createContext(context);
""",
            result_script="""
const queryResult = {
  selectedRoomId: "1152",
  rooms: [
    { roomId: "1152", roomName: "综合阅览室" },
    { roomId: "1153", roomName: "自习室圆形二楼" },
  ],
};
process.stdout.write(context.resolveRoomSelectionValue(queryResult, "", "自习室圆形二楼"));
""",
        )

        self.assertEqual(result, "1153")

    def test_app_js_builds_task_status_booking_summary_text(self) -> None:
        result = _run_app_js_functions(
            ("buildTaskStatusBookingSummaryText",),
            context_script="""
const context = {};
vm.createContext(context);
""",
            result_script="""
const text = context.buildTaskStatusBookingSummaryText([
  { bookingId: "1" },
  { bookingId: "2" },
  { bookingId: "3" },
  { bookingId: "4" },
]);
process.stdout.write(text);
""",
        )

        self.assertEqual(result, "共 4 条预约记录")

    def test_app_js_groups_seat_display_bookings_by_room(self) -> None:
        result = _run_app_js_functions(
            (
                "shouldShowBookingInSeatDisplay",
                "getSeatDisplayBookingState",
                "formatSeatDisplaySeatLabel",
                "compareSeatDisplayTimeSlots",
                "buildSeatDisplayTimeSlotLabel",
                "buildSeatDisplayRoomGroups",
                "buildSeatDisplayRoomSeatSummary",
            ),
            context_script="""
const context = {};
vm.createContext(context);
""",
            result_script="""
const bookings = [
  {
    accountName: "primary",
    accountLabel: "主账号",
    roomName: "自习室圆形二楼",
    seatNumber: "18",
    status: "0",
    statusLabel: "待签到",
    startAtLabel: "2026-04-02 08:00",
    startTimestamp: 1775088000,
  },
  {
    accountName: "primary",
    accountLabel: "主账号",
    roomName: "自习室圆形二楼",
    seatNumber: "18",
    status: "8",
    statusLabel: "已预约",
    startAtLabel: "2026-04-01 13:00",
    startTimestamp: 1775019600,
  },
  { roomName: "自习室圆形二楼", seatNumber: "20", status: "1" },
  { roomName: "综合阅览室", seatNumber: "5", status: "8" },
  { roomName: "过期记录", seatNumber: "9", status: "3" },
];
const visibleItems = bookings
  .filter(context.shouldShowBookingInSeatDisplay)
  .map((booking) => ({
    accountName: booking.accountName || "",
    accountLabel: booking.accountLabel || "",
    roomName: booking.roomName,
    seatNumber: booking.seatNumber,
    seatLabel: context.formatSeatDisplaySeatLabel(booking.seatNumber),
    statusLabel: booking.statusLabel || "",
    startAtLabel: booking.startAtLabel || "",
    startTimestamp: booking.startTimestamp || 0,
    state: context.getSeatDisplayBookingState(booking),
  }));
const groups = context.buildSeatDisplayRoomGroups(visibleItems).map((group) => ({
  roomName: group.roomName,
  seats: group.items.map((item) => ({
    label: item.seatLabel,
    slots: item.timeSlots.map((slot) => context.buildSeatDisplayTimeSlotLabel(slot)),
  })),
  summary: context.buildSeatDisplayRoomSeatSummary(group.items),
}));
process.stdout.write(JSON.stringify(groups));
""",
        )

        self.assertEqual(
            json.loads(result),
            [
                {
                    "roomName": "自习室圆形二楼",
                    "seats": [
                        {
                            "label": "18 号",
                            "slots": [
                                "2026-04-01 13:00 · 已预约",
                                "2026-04-02 08:00 · 待签到",
                            ],
                        },
                        {"label": "20 号", "slots": ["暂无开始时间"]},
                    ],
                    "summary": "18 号、20 号",
                },
                {
                    "roomName": "综合阅览室",
                    "seats": [{"label": "5 号", "slots": ["暂无开始时间"]}],
                    "summary": "5 号",
                },
            ],
        )

    def test_app_js_formats_seat_display_time_slot_rows(self) -> None:
        result = _run_app_js_functions(
            (
                "parseSeatDisplaySlotStartDate",
                "formatSeatDisplayDateLabel",
                "buildSeatDisplaySlotDayLabel",
                "formatSeatDisplayClock",
                "extractSeatDisplayClockLabel",
                "buildSeatDisplaySlotTimeRangeLabel",
                "buildSeatDisplaySlotTagLabels",
            ),
            context_script="""
const context = {};
vm.createContext(context);
""",
            result_script="""
const referenceDate = new Date(2026, 3, 1, 9, 0, 0);
const slots = [
  {
    startTimestamp: new Date(2026, 3, 1, 13, 0, 0).getTime() / 1000,
    durationSeconds: 3600,
    statusLabel: "已预约",
  },
  {
    startTimestamp: new Date(2026, 3, 2, 8, 0, 0).getTime() / 1000,
    durationSeconds: 7200,
    statusLabel: "待签到",
    checkinWindowOpen: true,
    accountLabel: "主账号",
  },
  {
    startAtLabel: "2026-04-03 09:30",
    statusLabel: "已预约",
  },
];
const rows = slots.map((slot) => ({
  day: context.buildSeatDisplaySlotDayLabel(slot, referenceDate),
  time: context.buildSeatDisplaySlotTimeRangeLabel(slot),
  tags: context.buildSeatDisplaySlotTagLabels(slot, true),
}));
process.stdout.write(JSON.stringify(rows));
""",
        )

        self.assertEqual(
            json.loads(result),
            [
                {"day": "今天", "time": "13:00-14:00", "tags": ["已预约"]},
                {
                    "day": "明天",
                    "time": "08:00-10:00",
                    "tags": ["待签到", "可签到", "主账号"],
                },
                {"day": "后天", "time": "09:30", "tags": ["已预约"]},
            ],
        )

    def test_app_js_builds_task_dialog_search_payload_from_dialog_fields(self) -> None:
        result = _run_app_js_functions(
            ("getAccountName", "buildTaskDialogSearchPayload"),
            context_script="""
const context = {
  state: {
    bootstrap: { selectedAccountName: "default-account" },
    accounts: [],
    taskDialogSeatUrl: "https://example.com/seat/a",
  },
  elements: {
    accountSelect: { value: "" },
    taskDialogAccountSelect: { value: "dialog-account" },
    taskDialogDateSelect: { value: "2026-04-01" },
    taskDialogStartHourSelect: { value: "8" },
    taskDialogDurationSelect: { value: "4" },
  },
};
vm.createContext(context);
""",
            result_script="""
process.stdout.write(JSON.stringify(context.buildTaskDialogSearchPayload()));
""",
        )

        self.assertEqual(
            result,
            '{"accountName":"dialog-account","seatUrl":"https://example.com/seat/a","date":"2026-04-01","startHour":8,"durationHours":4,"peopleCount":1}',
        )

    def test_load_bootstrap_does_not_reload_full_account_list(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertNotIn(
            "Promise.all([loadAccounts(data.selectedAccountName), loadTasks(true)])",
            script,
        )
        self.assertIn("await loadTasks(true);", script)

    def test_app_js_builds_task_dialog_plan_payload_with_selected_room_context(
        self,
    ) -> None:
        result = _run_app_js_functions(
            (
                "getAccountName",
                "getQueryRooms",
                "findRoomByName",
                "buildTaskDialogPlanPayload",
            ),
            context_script="""
const context = {
  state: {
    bootstrap: { selectedAccountName: "default-account" },
    accounts: [],
    taskDialogEditingPlanId: "",
    taskDialogSeatUrl: "https://example.com/seat/a",
    taskDialogSelectedRoomId: "1153",
    taskDialogSelectedRoomName: "自习室圆形二楼",
  },
  elements: {
    accountSelect: { value: "" },
    taskDialogAccountSelect: { value: "dialog-account" },
    taskDialogDateSelect: { value: "2026-04-01" },
    taskDialogStartHourSelect: { value: "8" },
    taskDialogDurationSelect: { value: "4" },
    taskDialogSeatNumberInput: { value: "58" },
    taskDialogReserveEnabled: { checked: true },
    taskDialogCheckinEnabled: { checked: false },
    taskDialogCheckoutEnabled: { checked: false },
    taskDialogContinuousCheckbox: { checked: false },
    taskDialogReserveTimeInput: { value: "08:00" },
    taskDialogCheckinTimeInput: { value: "07:35" },
    taskDialogCheckoutTimeInput: { value: "21:59" },
  },
  getTaskDialogSeatContext: () => ({
    inputSeatNumber: "58",
    seat: { seatNumber: "58" },
  }),
};
vm.createContext(context);
""",
            result_script="""
process.stdout.write(JSON.stringify(context.buildTaskDialogPlanPayload()));
""",
        )

        self.assertEqual(
            result,
            '{"planId":"","accountName":"dialog-account","seatUrl":"https://example.com/seat/a","selectedRoomId":"1153","selectedRoomName":"自习室圆形二楼","selectedDate":"2026-04-01","startHour":8,"durationHours":4,"seatNumber":"58","reserveEnabled":true,"checkinEnabled":false,"checkoutEnabled":false,"continuousReserve":false,"reserveTime":"08:00","checkinTime":"07:35","checkoutTime":"21:59","reserveCheckIntervalMinutes":30}',
        )

    def test_task_dialog_validation_allows_existing_but_unavailable_seat_number(
        self,
    ) -> None:
        result = _run_app_js_functions(
            (
                "getAccountName",
                "getTaskDialogBootstrap",
                "buildTaskDialogSearchPayload",
                "isTaskDialogQueryResultCurrent",
                "getTaskDialogCurrentQueryResult",
                "getTaskDialogSelectableSeats",
                "buildTaskDialogSeatRangeLabel",
                "compareSeatNumber",
                "getTaskDialogSeatContext",
                "getTaskDialogEnabledActions",
                "getTaskDialogValidationMessage",
            ),
            context_script="""
const context = {
  state: {
    bootstrap: { selectedAccountName: "主号" },
    accounts: [],
    taskDialogSeatUrl: "https://example.com/seat/a",
    taskDialogQueryBusy: false,
    taskDialogQueryError: "",
    taskDialogQueryResult: {
      query: {
        accountName: "主号",
        seatUrl: "https://example.com/seat/a",
        date: "2026-04-01",
        startHour: 8,
        durationHours: 4,
        peopleCount: 1,
      },
      seatMap: {
        seats: [
          { seatId: "seat-58", seatNumber: "58", selectable: false },
          { seatId: "seat-60", seatNumber: "60", selectable: true },
        ],
      },
    },
    selectedSeatIds: [],
  },
  elements: {
    accountSelect: { value: "" },
    taskDialogAccountSelect: { value: "主号" },
    taskDialogDateSelect: { value: "2026-04-01" },
    taskDialogStartHourSelect: { value: "8" },
    taskDialogDurationSelect: { value: "4" },
    taskDialogSeatNumberInput: { value: "58" },
    taskDialogReserveEnabled: { checked: true },
    taskDialogCheckinEnabled: { checked: false },
    taskDialogCheckoutEnabled: { checked: false },
    taskDialogReserveTimeInput: { value: "08:00" },
    taskDialogCheckinTimeInput: { value: "" },
    taskDialogCheckoutTimeInput: { value: "" },
  },
};
vm.createContext(context);
""",
            result_script="""
const seatContext = context.getTaskDialogSeatContext();
const validationMessage = context.getTaskDialogValidationMessage();
process.stdout.write(JSON.stringify({
  seatNumber: seatContext.seat && seatContext.seat.seatNumber,
  selectable: seatContext.seat && seatContext.seat.selectable,
  invalidMessage: seatContext.invalidMessage,
  validationMessage,
}));
""",
        )

        self.assertEqual(
            result,
            '{"seatNumber":"58","selectable":false,"invalidMessage":"","validationMessage":""}',
        )

    def test_task_dialog_validation_rejects_missing_seat_number_from_current_query(
        self,
    ) -> None:
        result = _run_app_js_functions(
            (
                "getAccountName",
                "getTaskDialogBootstrap",
                "buildTaskDialogSearchPayload",
                "isTaskDialogQueryResultCurrent",
                "getTaskDialogCurrentQueryResult",
                "getTaskDialogSelectableSeats",
                "buildTaskDialogSeatRangeLabel",
                "compareSeatNumber",
                "getTaskDialogSeatContext",
                "getTaskDialogEnabledActions",
                "getTaskDialogValidationMessage",
            ),
            context_script="""
const context = {
  state: {
    bootstrap: { selectedAccountName: "主号" },
    accounts: [],
    taskDialogSeatUrl: "https://example.com/seat/a",
    taskDialogQueryBusy: false,
    taskDialogQueryError: "",
    taskDialogQueryResult: {
      query: {
        accountName: "主号",
        seatUrl: "https://example.com/seat/a",
        date: "2026-04-01",
        startHour: 8,
        durationHours: 4,
        peopleCount: 1,
      },
      seatMap: {
        seats: [
          { seatId: "seat-58", seatNumber: "58", selectable: false },
          { seatId: "seat-60", seatNumber: "60", selectable: true },
        ],
      },
    },
    selectedSeatIds: [],
  },
  elements: {
    accountSelect: { value: "" },
    taskDialogAccountSelect: { value: "主号" },
    taskDialogDateSelect: { value: "2026-04-01" },
    taskDialogStartHourSelect: { value: "8" },
    taskDialogDurationSelect: { value: "4" },
    taskDialogSeatNumberInput: { value: "88" },
    taskDialogReserveEnabled: { checked: true },
    taskDialogCheckinEnabled: { checked: false },
    taskDialogCheckoutEnabled: { checked: false },
    taskDialogReserveTimeInput: { value: "08:00" },
    taskDialogCheckinTimeInput: { value: "" },
    taskDialogCheckoutTimeInput: { value: "" },
  },
};
vm.createContext(context);
""",
            result_script="""
process.stdout.write(JSON.stringify({
  validationMessage: context.getTaskDialogValidationMessage(),
}));
""",
        )

        self.assertEqual(
            result,
            '{"validationMessage":"当前查询结果里没有 88 号座位。"}',
        )

    def test_app_js_marks_task_dialog_query_result_stale_when_duration_changes(
        self,
    ) -> None:
        result = _run_app_js_functions(
            (
                "getAccountName",
                "buildTaskDialogSearchPayload",
                "isTaskDialogQueryResultCurrent",
            ),
            context_script="""
const context = {
  state: {
    bootstrap: { selectedAccountName: "default-account" },
    accounts: [],
    taskDialogSeatUrl: "https://example.com/seat/a",
    taskDialogQueryResult: {
      query: {
        accountName: "dialog-account",
        seatUrl: "https://example.com/seat/a",
        date: "2026-04-01",
        startHour: 8,
        durationHours: 6,
        peopleCount: 1,
      },
    },
  },
  elements: {
    accountSelect: { value: "" },
    taskDialogAccountSelect: { value: "dialog-account" },
    taskDialogDateSelect: { value: "2026-04-01" },
    taskDialogStartHourSelect: { value: "8" },
    taskDialogDurationSelect: { value: "4" },
  },
};
vm.createContext(context);
""",
            result_script="""
process.stdout.write(JSON.stringify(context.isTaskDialogQueryResultCurrent()));
""",
        )

        self.assertEqual(result, "false")

    def test_app_js_defaults_checkin_to_25_minutes_before_start_hour(self) -> None:
        result = _run_app_js_functions(
            (
                "padNumber",
                "buildTaskDialogDefaultCheckinTime",
                "syncTaskDialogActionTimes",
            ),
            context_script="""
const context = {
  elements: {
    taskDialogStartHourSelect: { value: "8" },
    taskDialogReserveTimeInput: { value: "" },
    taskDialogCheckinTimeInput: { value: "" },
    taskDialogCheckoutTimeInput: { value: "" },
  },
};
vm.createContext(context);
""",
            result_script="""
context.syncTaskDialogActionTimes();
process.stdout.write(JSON.stringify({
  reserveTime: context.elements.taskDialogReserveTimeInput.value,
  checkinTime: context.elements.taskDialogCheckinTimeInput.value,
  checkoutTime: context.elements.taskDialogCheckoutTimeInput.value,
}));
""",
        )

        self.assertEqual(
            result,
            '{"reserveTime":"08:00","checkinTime":"07:35","checkoutTime":"21:59"}',
        )

    def test_app_js_builds_account_select_labels_with_student_id(self) -> None:
        result = _run_app_js_functions(
            ("getAccountDisplayName", "buildAccountSelectOptions"),
            context_script="""
const context = {};
vm.createContext(context);
""",
            result_script="""
const options = context.buildAccountSelectOptions([
  { name: "主号", studentId: "20231121130", isDefault: true },
  { name: "室友", studentId: "20231121151", isDefault: false },
]);
process.stdout.write(JSON.stringify({
  firstStartsWithValue: options[0].label.startsWith(options[0].value),
  secondStartsWithValue: options[1].label.startsWith(options[1].value),
  firstHasStudentId: options[0].label.includes("20231121130"),
  secondHasStudentId: options[1].label.includes("20231121151"),
  firstLabelLongerThanSecond: options[0].label.length > options[1].label.length,
}));
""",
        )

        self.assertEqual(
            result,
            '{"firstStartsWithValue":true,"secondStartsWithValue":true,"firstHasStudentId":true,"secondHasStudentId":true,"firstLabelLongerThanSecond":true}',
        )

    def test_app_js_mentions_current_hour_window_and_immediate_checkin(self) -> None:
        script = (WEB_DIR / "app.js").read_text(encoding="utf-8")

        self.assertIn(
            "当天预约开始时间会按当前小时开始并持续到 22:00；隔天和后续日期使用 8:00 - 22:00；自动签到会在检测到当前已可签到时立即补签，签退放在 21:59。",
            script,
        )

    def test_styles_include_debug_page_layout(self) -> None:
        styles = (WEB_DIR / "styles.css").read_text(encoding="utf-8")

        self.assertIn(".debug-overview-grid", styles)
        self.assertIn(".debug-request-card", styles)
        self.assertIn(".debug-log-list", styles)
        self.assertIn(".debug-code-block", styles)

    def test_styles_include_account_auth_layout(self) -> None:
        styles = (WEB_DIR / "styles.css").read_text(encoding="utf-8")

        self.assertIn(".account-auth-panel", styles)
        self.assertIn(".account-layout", styles)

    def test_styles_include_task_dialog_and_status_layout(self) -> None:
        styles = (WEB_DIR / "styles.css").read_text(encoding="utf-8")

        self.assertIn(".task-dialog", styles)
        self.assertIn(".task-dialog-head-actions", styles)
        self.assertIn(".task-dialog-query-card", styles)
        self.assertIn(".task-dialog-feedback", styles)
        self.assertIn(".task-status-card", styles)
        self.assertIn(".task-status-actions", styles)
        self.assertIn(".task-status-action-button.is-success", styles)
        self.assertIn(".task-status-action-button.is-warning", styles)
        self.assertIn(".task-status-action-hint", styles)
        self.assertIn(".task-plan-card", styles)
        self.assertIn(".task-account-group", styles)
        self.assertIn(".task-account-summary", styles)
        self.assertIn(".task-account-action-grid", styles)
        self.assertIn(".automation-task-item", styles)

    def test_styles_include_seat_display_layout(self) -> None:
        styles = (WEB_DIR / "styles.css").read_text(encoding="utf-8")

        self.assertIn(".seat-display-list", styles)
        self.assertIn(".seat-display-room", styles)
        self.assertIn(".seat-display-seat-card", styles)
        self.assertIn(".seat-display-time-slot", styles)
        self.assertIn(".seat-display-slot-time", styles)
        self.assertIn(".seat-display-chair-icon", styles)
        self.assertIn(".seat-display-detail-button", styles)
