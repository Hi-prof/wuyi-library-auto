const PIXELS_PER_UNIT = 10;
const AUTOMATION_ACTIONS = ["reserve", "checkin", "checkout"];
const TASK_STATUS_REFRESH_HOUR = 8;
const TASK_STATUS_REFRESH_MINUTE = 30;

const state = {
  bootstrap: null,
  selectedRoomId: "",
  taskDialogBootstrap: null,
  taskDialogSeatUrl: "",
  taskDialogEditingPlanId: "",
  taskDialogSelectedRoomId: "",
  taskDialogSelectedRoomName: "",
  taskDialogQueryResult: null,
  taskDialogQueryBusy: false,
  taskDialogQueryError: "",
  taskDialogQuerySequence: 0,
  taskDialogFeedbackMessage: "",
  taskDialogFeedbackLevel: "error",
  queryResult: null,
  selectedSeatIds: [],
  tasks: [],
  taskGroupExpanded: {},
  taskStatuses: [],
  taskStatusLoadedAt: "",
  taskStatusLoadingAccounts: {},
  taskStatusRefreshTimer: null,
  accounts: [],
  debugLogs: [],
  debugLastRequest: null,
  debugLogSequence: 0,
  zoom: 1,
  toastTimer: null,
  accountEditorOriginalName: "",
  accountEditorSelectedName: "",
  accountBulkSelectionMode: false,
  selectedAccountNames: [],
};

const elements = {
  accountConfigPath: document.getElementById("accountConfigPath"),
  loginStatus: document.getElementById("loginStatus"),
  currentAccountText: document.getElementById("currentAccountText"),
  sidebarSummary: document.getElementById("sidebarSummary"),
  menuButtons: [...document.querySelectorAll("[data-view-target]")],
  viewPanels: [...document.querySelectorAll("[data-view-panel]")],
  accountSelect: document.getElementById("accountSelect"),
  seatUrlSelect: document.getElementById("seatUrlSelect"),
  dateSelect: document.getElementById("dateSelect"),
  startHourSelect: document.getElementById("startHourSelect"),
  durationSelect: document.getElementById("durationSelect"),
  peopleCountSelect: document.getElementById("peopleCountSelect"),
  roomSelect: document.getElementById("roomSelect"),
  peopleHint: document.getElementById("peopleHint"),
  searchButton: document.getElementById("searchButton"),
  roomName: document.getElementById("roomName"),
  querySummary: document.getElementById("querySummary"),
  availableCount: document.getElementById("availableCount"),
  lockedCount: document.getElementById("lockedCount"),
  zoomValue: document.getElementById("zoomValue"),
  zoomOutButton: document.getElementById("zoomOutButton"),
  zoomResetButton: document.getElementById("zoomResetButton"),
  zoomInButton: document.getElementById("zoomInButton"),
  mapStage: document.getElementById("mapStage"),
  planImage: document.getElementById("planImage"),
  seatLayer: document.getElementById("seatLayer"),
  mapEmptyTip: document.getElementById("mapEmptyTip"),
  seatFilterInput: document.getElementById("seatFilterInput"),
  seatList: document.getElementById("seatList"),
  taskStatusButton: document.getElementById("taskStatusButton"),
  taskStatusSummary: document.getElementById("taskStatusSummary"),
  taskStatusList: document.getElementById("taskStatusList"),
  seatDisplayRefreshButton: document.getElementById("seatDisplayRefreshButton"),
  seatDisplaySummary: document.getElementById("seatDisplaySummary"),
  seatDisplayList: document.getElementById("seatDisplayList"),
  taskCheckReserveButton: document.getElementById("taskCheckReserveButton"),
  taskUploadButton: document.getElementById("taskUploadButton"),
  taskCreateButton: document.getElementById("taskCreateButton"),
  taskCreateHint: document.getElementById("taskCreateHint"),
  taskHint: document.getElementById("taskHint"),
  taskList: document.getElementById("taskList"),
  accountDialog: document.getElementById("accountDialog"),
  accountDialogEyebrow: document.getElementById("accountDialogEyebrow"),
  accountDialogHint: document.getElementById("accountDialogHint"),
  accountDialogCloseButton: document.getElementById("accountDialogCloseButton"),
  accountDialogCancelButton: document.getElementById("accountDialogCancelButton"),
  accountBulkImportDialog: document.getElementById("accountBulkImportDialog"),
  accountBulkImportButton: document.getElementById("accountBulkImportButton"),
  accountBulkImportCloseButton: document.getElementById("accountBulkImportCloseButton"),
  accountBulkImportCancelButton: document.getElementById("accountBulkImportCancelButton"),
  accountBulkImportSubmitButton: document.getElementById("accountBulkImportSubmitButton"),
  accountBulkImportTextInput: document.getElementById("accountBulkImportTextInput"),
  accountBulkImportResult: document.getElementById("accountBulkImportResult"),
  detailsDialog: document.getElementById("detailsDialog"),
  detailsDialogEyebrow: document.getElementById("detailsDialogEyebrow"),
  detailsDialogTitle: document.getElementById("detailsDialogTitle"),
  detailsDialogHint: document.getElementById("detailsDialogHint"),
  detailsDialogContent: document.getElementById("detailsDialogContent"),
  detailsDialogCloseButton: document.getElementById("detailsDialogCloseButton"),
  detailsDialogConfirmButton: document.getElementById("detailsDialogConfirmButton"),
  taskDialog: document.getElementById("taskDialog"),
  taskDialogEyebrow: document.getElementById("taskDialogEyebrow"),
  taskDialogTitle: document.getElementById("taskDialogTitle"),
  taskDialogHint: document.getElementById("taskDialogHint"),
  taskDialogAccountSelect: document.getElementById("taskDialogAccountSelect"),
  taskDialogDateSelect: document.getElementById("taskDialogDateSelect"),
  taskDialogStartHourSelect: document.getElementById("taskDialogStartHourSelect"),
  taskDialogDurationSelect: document.getElementById("taskDialogDurationSelect"),
  taskDialogRoomSelect: document.getElementById("taskDialogRoomSelect"),
  taskDialogSeatNumberInput: document.getElementById("taskDialogSeatNumberInput"),
  taskDialogSeatNumberHint: document.getElementById("taskDialogSeatNumberHint"),
  taskDialogReserveEnabled: document.getElementById("taskDialogReserveEnabled"),
  taskDialogCheckinEnabled: document.getElementById("taskDialogCheckinEnabled"),
  taskDialogCheckoutEnabled: document.getElementById("taskDialogCheckoutEnabled"),
  taskDialogContinuousCheckbox: document.getElementById("taskDialogContinuousCheckbox"),
  taskDialogReserveTimeInput: document.getElementById("taskDialogReserveTimeInput"),
  taskDialogCheckinTimeInput: document.getElementById("taskDialogCheckinTimeInput"),
  taskDialogCheckoutTimeInput: document.getElementById("taskDialogCheckoutTimeInput"),
  taskDialogSeatSummary: document.getElementById("taskDialogSeatSummary"),
  taskDialogPlanSummary: document.getElementById("taskDialogPlanSummary"),
  taskDialogQuerySummary: document.getElementById("taskDialogQuerySummary"),
  taskDialogFeedback: document.getElementById("taskDialogFeedback"),
  taskDialogRefreshButton: document.getElementById("taskDialogRefreshButton"),
  taskDialogCloseButton: document.getElementById("taskDialogCloseButton"),
  taskDialogCancelButton: document.getElementById("taskDialogCancelButton"),
  taskDialogSubmitButton: document.getElementById("taskDialogSubmitButton"),
  selectionSummary: document.getElementById("selectionSummary"),
  recommendedSeatSummary: document.getElementById("recommendedSeatSummary"),
  selectedSeatTags: document.getElementById("selectedSeatTags"),
  reserveButton: document.getElementById("reserveButton"),
  reserveHint: document.getElementById("reserveHint"),
  accountListSummary: document.getElementById("accountListSummary"),
  accountList: document.getElementById("accountList"),
  accountEditorTitle: document.getElementById("accountEditorTitle"),
  accountStudentIdInput: document.getElementById("accountStudentIdInput"),
  accountPasswordInput: document.getElementById("accountPasswordInput"),
  accountAuthHint: document.getElementById("accountAuthHint"),
  accountSaveButton: document.getElementById("accountSaveButton"),
  accountRefreshLoginButton: document.getElementById("accountRefreshLoginButton"),
  accountDeleteButton: document.getElementById("accountDeleteButton"),
  accountStatusRefreshButton: document.getElementById("accountStatusRefreshButton"),
  accountBulkActionButton: document.getElementById("accountBulkActionButton"),
  accountDeleteSelectedButton: document.getElementById("accountDeleteSelectedButton"),
  accountCheckinAllButton: document.getElementById("accountCheckinAllButton"),
  accountResetButton: document.getElementById("accountResetButton"),
  accountFormHint: document.getElementById("accountFormHint"),
  debugRefreshBootstrapButton: document.getElementById("debugRefreshBootstrapButton"),
  debugRetrySearchButton: document.getElementById("debugRetrySearchButton"),
  debugCopySnapshotButton: document.getElementById("debugCopySnapshotButton"),
  debugClearLogsButton: document.getElementById("debugClearLogsButton"),
  debugAccountValue: document.getElementById("debugAccountValue"),
  debugLoginStateValue: document.getElementById("debugLoginStateValue"),
  debugSeatUrlValue: document.getElementById("debugSeatUrlValue"),
  debugSeatUrlMeta: document.getElementById("debugSeatUrlMeta"),
  debugQueryStateValue: document.getElementById("debugQueryStateValue"),
  debugQueryMeta: document.getElementById("debugQueryMeta"),
  debugLastRequestValue: document.getElementById("debugLastRequestValue"),
  debugLastRequestMeta: document.getElementById("debugLastRequestMeta"),
  debugRequestTitle: document.getElementById("debugRequestTitle"),
  debugRequestStatus: document.getElementById("debugRequestStatus"),
  debugRequestBadge: document.getElementById("debugRequestBadge"),
  debugRequestFacts: document.getElementById("debugRequestFacts"),
  debugRequestBody: document.getElementById("debugRequestBody"),
  debugResponseBody: document.getElementById("debugResponseBody"),
  debugSnapshot: document.getElementById("debugSnapshot"),
  debugLogList: document.getElementById("debugLogList"),
  toast: document.getElementById("toast"),
};

document.addEventListener("DOMContentLoaded", () => {
  bindEvents();
  installDebugHooks();
  addDebugLog("info", "调试中心已就绪", "页面已加载，正在读取引导数据。");
  switchView(readRequestedView(), { syncUrl: false });
  renderDebugPanel();
  void initializePage();
  initManualSync();
});

async function initializePage() {
  try {
    await loadBootstrap();
    await loadAccounts(state.bootstrap?.selectedAccountName || "");
    scheduleDailyTaskStatusRefresh();
    if (state.bootstrap && !state.bootstrap.message) {
      await searchSeats();
    }
    window.setInterval(() => {
      void loadTasks(true);
    }, 3000);
  } catch (error) {
    showToast(error.message);
  }
}

function bindEvents() {
  elements.menuButtons.forEach((button) => {
    button.addEventListener("click", () => {
      switchView(button.dataset.viewTarget || "reserve");
    });
  });
  elements.accountSelect.addEventListener("change", async () => {
    await loadBootstrap(elements.accountSelect.value, "", true);
  });
  elements.seatUrlSelect.addEventListener("change", async () => {
    await loadBootstrap(getAccountName(), elements.seatUrlSelect.value, true);
  });
  elements.dateSelect.addEventListener("change", () => {
    handleQueryConditionChanged();
  });
  elements.startHourSelect.addEventListener("change", () => {
    syncDurationOptions();
    handleQueryConditionChanged();
  });
  elements.durationSelect.addEventListener("change", () => {
    handleQueryConditionChanged();
  });
  elements.peopleCountSelect.addEventListener("change", () => {
    handleQueryConditionChanged();
  });
  elements.roomSelect.addEventListener("change", () => {
    state.selectedRoomId = elements.roomSelect.value || "";
    trimSelectedSeats();
    renderQueryResult();
  });
  elements.searchButton.addEventListener("click", async () => {
    await searchSeats();
  });
  elements.taskStatusButton.addEventListener("click", async () => {
    await refreshAllTaskStatuses(elements.taskStatusButton, "一键获取账号状态");
  });
  elements.taskStatusList.addEventListener("click", async (event) => {
    const refreshButton = event.target.closest("[data-status-refresh-account]");
    if (refreshButton) {
      const accountName = refreshButton.dataset.statusRefreshAccount || "";
      if (accountName && !refreshButton.disabled) {
        await loadSingleTaskStatus(accountName, { rethrow: false });
      }
      return;
    }

    const detailButton = event.target.closest("[data-status-detail-account]");
    if (detailButton) {
      openTaskStatusDetails(detailButton.dataset.statusDetailAccount || "");
      return;
    }
    const actionButton = event.target.closest("[data-status-action]");
    if (!actionButton || actionButton.disabled) {
      return;
    }

    const accountName = actionButton.dataset.accountName || "";
    const action = actionButton.dataset.statusAction || "";
    const bookingId = actionButton.dataset.bookingId || "";
    const bookingLabel = actionButton.dataset.bookingLabel || "";
    if (!accountName || !action) {
      return;
    }

    if (action === "open-task-dialog") {
      try {
        await openTaskDialog({
          accountName,
          roomName: actionButton.dataset.roomName || "",
          seatNumber: actionButton.dataset.seatNumber || "",
        });
      } catch (error) {
        showToast(error.message);
      }
      return;
    }

    if (action === "cancel") {
      const confirmMessage = bookingLabel
        ? `确定取消这个预约吗？\n${bookingLabel}`
        : "确定取消当前预约吗？";
      if (!window.confirm(confirmMessage)) {
        return;
      }
    }

    await submitTaskStatusAction(action, accountName, actionButton, bookingId, bookingLabel);
  });
  elements.seatDisplayRefreshButton.addEventListener("click", async () => {
    await runBusyButtonAction(
      elements.seatDisplayRefreshButton,
      {
        busyLabel: "正在刷新...",
        idleLabel: "刷新预约位置",
        restoreDisabled: () => !state.accounts.length,
      },
      async () => {
        await loadTaskStatuses();
      },
    );
  });
  elements.seatDisplayList.addEventListener("click", (event) => {
    const detailButton = event.target.closest("[data-seat-display-detail-account]");
    if (!detailButton) {
      return;
    }
    openTaskStatusDetails(detailButton.dataset.seatDisplayDetailAccount || "");
  });
  elements.taskCreateButton.addEventListener("click", async () => {
    await openTaskDialog();
  });
  elements.taskCheckReserveButton.addEventListener("click", async () => {
    await runManualReserveCheck();
  });
  elements.taskUploadButton?.addEventListener("click", async () => {
    await handleTaskUploadClick();
  });
  elements.accountDialogCloseButton.addEventListener("click", () => {
    closeAccountDialog();
  });
  elements.accountDialogCancelButton.addEventListener("click", () => {
    closeAccountDialog();
  });
  elements.accountDialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    closeAccountDialog();
  });
  elements.detailsDialogCloseButton.addEventListener("click", () => {
    closeDetailsDialog();
  });
  elements.detailsDialogConfirmButton.addEventListener("click", () => {
    closeDetailsDialog();
  });
  elements.detailsDialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    closeDetailsDialog();
  });
  elements.taskDialogCloseButton.addEventListener("click", () => {
    closeTaskDialog();
  });
  elements.taskDialogCancelButton.addEventListener("click", () => {
    closeTaskDialog();
  });
  elements.taskDialogAccountSelect.addEventListener("change", async () => {
    setTaskDialogFeedback("");
    const nextAccountName = elements.taskDialogAccountSelect.value;
    await loadTaskDialogBootstrap(nextAccountName, "", true);
    fillSelect(
      elements.taskDialogDateSelect,
      getTaskDialogBootstrap()?.dateOptions || [],
      getTaskDialogBootstrap()?.defaults?.date || "",
    );
    syncTaskDialogDefaults();
    // 切换任务账号后，把座位号和自习室也按新账号的"上一次"重置；
    // 不重置编辑模式（账号选择器在编辑模式是 disabled，不会触发这里）。
    applyTaskDialogHistoryDefaults(nextAccountName);
    renderTaskDialogRoomOptions();
    markTaskDialogQueryStale();
    void refreshTaskDialogQuery();
  });
  elements.taskDialogDateSelect.addEventListener("change", () => {
    syncTaskDialogDefaults();
    markTaskDialogQueryStale();
  });
  elements.taskDialogStartHourSelect.addEventListener("change", () => {
    syncTaskDialogDurationOptions();
    syncTaskDialogActionTimes();
    markTaskDialogQueryStale();
  });
  elements.taskDialogDurationSelect.addEventListener("change", () => {
    markTaskDialogQueryStale();
  });
  elements.taskDialogRoomSelect.addEventListener("change", () => {
    syncTaskDialogSelectedRoom(elements.taskDialogRoomSelect.value || "");
    updateTaskDialogPreview();
    renderTaskDialogQuerySummary();
  });
  elements.taskDialogSeatNumberInput.addEventListener("input", () => {
    setTaskDialogFeedback("");
    updateTaskDialogPreview();
  });
  elements.taskDialogReserveEnabled.addEventListener("change", () => {
    setTaskDialogFeedback("");
    updateTaskDialogPreview();
  });
  elements.taskDialogCheckinEnabled.addEventListener("change", () => {
    setTaskDialogFeedback("");
    updateTaskDialogPreview();
  });
  elements.taskDialogCheckoutEnabled.addEventListener("change", () => {
    setTaskDialogFeedback("");
    updateTaskDialogPreview();
  });
  elements.taskDialogContinuousCheckbox.addEventListener("change", () => {
    setTaskDialogFeedback("");
    updateTaskDialogPreview();
  });
  elements.taskDialogReserveTimeInput.addEventListener("change", () => {
    setTaskDialogFeedback("");
    updateTaskDialogPreview();
  });
  elements.taskDialogCheckinTimeInput.addEventListener("change", () => {
    setTaskDialogFeedback("");
    updateTaskDialogPreview();
  });
  elements.taskDialogCheckoutTimeInput.addEventListener("change", () => {
    setTaskDialogFeedback("");
    updateTaskDialogPreview();
  });
  elements.taskDialogRefreshButton.addEventListener("click", async () => {
    await refreshTaskDialogQuery();
  });
  elements.taskDialogSubmitButton.addEventListener("click", async () => {
    await createTaskBatch();
  });
  elements.taskDialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    closeTaskDialog();
  });
  elements.reserveButton.addEventListener("click", async () => {
    await submitReservation();
  });
  elements.zoomOutButton.addEventListener("click", () => setZoom(state.zoom - 0.2));
  elements.zoomResetButton.addEventListener("click", () => setZoom(1));
  elements.zoomInButton.addEventListener("click", () => setZoom(state.zoom + 0.2));
  elements.seatFilterInput.addEventListener("input", () => {
    renderSeatList();
  });
  elements.accountResetButton.addEventListener("click", () => {
    openAccountDialog();
  });
  elements.accountBulkImportButton.addEventListener("click", () => {
    openAccountBulkImportDialog();
  });
  elements.accountBulkImportCloseButton.addEventListener("click", () => {
    closeAccountBulkImportDialog();
  });
  elements.accountBulkImportCancelButton.addEventListener("click", () => {
    closeAccountBulkImportDialog();
  });
  elements.accountBulkImportDialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    closeAccountBulkImportDialog();
  });
  elements.accountBulkImportSubmitButton.addEventListener("click", async () => {
    await importAccounts();
  });
  elements.accountStatusRefreshButton.addEventListener("click", async () => {
    await refreshAllAccountLoginStates();
  });
  elements.accountBulkActionButton.addEventListener("click", () => {
    setAccountBulkSelectionMode(!state.accountBulkSelectionMode);
  });
  elements.accountDeleteSelectedButton.addEventListener("click", async () => {
    await deleteSelectedAccounts();
  });
  elements.accountCheckinAllButton.addEventListener("click", async () => {
    await runCheckinAllAccounts();
  });
  elements.accountSaveButton.addEventListener("click", async () => {
    await saveAccount();
  });
  elements.accountRefreshLoginButton.addEventListener("click", async () => {
    await refreshAccountLogin();
  });
  elements.accountDeleteButton.addEventListener("click", async () => {
    await deleteAccountProfile();
  });
  elements.accountList.addEventListener("click", async (event) => {
    const actionButton = event.target.closest("[data-account-action]");
    if (actionButton) {
      const accountName = actionButton.dataset.accountName || "";
      const action = actionButton.dataset.accountAction || "";
      if (!accountName || !action) {
        return;
      }

      if (action === "edit") {
        openAccountDialog(accountName);
        return;
      }
      if (action === "switch") {
        await loadBootstrap(accountName, "", true);
        switchView("reserve");
        showToast(`已切换到账号：${getSelectedAccountLabel(accountName)}`);
        return;
      }
      if (action === "default") {
        await setDefaultAccount(accountName);
      }
      return;
    }

    const accountCard = event.target.closest("[data-account-card]");
    if (!state.accountBulkSelectionMode || !accountCard) {
      return;
    }
    toggleAccountSelected(accountCard.dataset.accountCard || "");
  });
  elements.accountList.addEventListener("keydown", (event) => {
    if (
      !state.accountBulkSelectionMode ||
      event.target.closest("[data-account-action]") ||
      !["Enter", " "].includes(event.key)
    ) {
      return;
    }
    const accountCard = event.target.closest("[data-account-card]");
    if (!accountCard) {
      return;
    }
    event.preventDefault();
    toggleAccountSelected(accountCard.dataset.accountCard || "");
  });
  elements.debugRefreshBootstrapButton.addEventListener("click", async () => {
    await refreshDebugBootstrap();
  });
  elements.debugRetrySearchButton.addEventListener("click", async () => {
    await rerunDebugSearch();
  });
  elements.debugCopySnapshotButton.addEventListener("click", async () => {
    await copyDebugSnapshot();
  });
  elements.debugClearLogsButton.addEventListener("click", () => {
    clearDebugLogs();
  });
}

function getNextTaskStatusRefreshDelay(now = new Date()) {
  const nextRefresh = new Date(now);
  nextRefresh.setHours(TASK_STATUS_REFRESH_HOUR, TASK_STATUS_REFRESH_MINUTE, 0, 0);
  if (nextRefresh <= now) {
    nextRefresh.setDate(nextRefresh.getDate() + 1);
  }
  return nextRefresh.getTime() - now.getTime();
}

function scheduleDailyTaskStatusRefresh() {
  if (state.taskStatusRefreshTimer) {
    window.clearTimeout(state.taskStatusRefreshTimer);
  }
  state.taskStatusRefreshTimer = window.setTimeout(async () => {
    try {
      await loadTaskStatuses(true);
    } finally {
      scheduleDailyTaskStatusRefresh();
    }
  }, getNextTaskStatusRefreshDelay());
}

function handleQueryConditionChanged() {
  trimSelectedSeats();
  updatePeopleHint();
  renderSelectionArea();
  renderMap();
  renderSeatList();
  updateTaskCreateHint();
  updateTaskDialogPreview();
  updateSidebarSummary();
}

async function loadBootstrap(accountName = "", requestedSeatUrl = "", autoSearch = false) {
  await runBusyButtonAction(
    elements.searchButton,
    {
      busyLabel: "正在读取...",
      idleLabel: "查询可选座位",
    },
    async () => {
      const params = new URLSearchParams();
      if (accountName) {
        params.set("accountName", accountName);
    }
    if (requestedSeatUrl) {
      params.set("seatUrl", requestedSeatUrl);
    }
    const queryString = params.toString() ? `?${params.toString()}` : "";
    const data = await requestJson(`/api/bootstrap${queryString}`, {
      debugLabel: "读取引导数据",
    });
    state.bootstrap = data;
    state.queryResult = null;
    state.selectedSeatIds = [];
    applyCachedTaskStatuses(data);
    applyBootstrap(data);
    clearResultArea();
    await loadTasks(true);
    if (data.message) {
      showToast(data.message);
      return;
    }
    if (autoSearch) {
      await searchSeats();
    }
    },
  );
}

async function loadAccounts(selectedAccountName = "") {
  const params = new URLSearchParams();
  if (selectedAccountName) {
    params.set("accountName", selectedAccountName);
  }
  const queryString = params.toString() ? `?${params.toString()}` : "";
  const data = await requestJson(`/api/accounts${queryString}`, {
    debugLabel: "读取账号列表",
  });
  applyAccounts(data);
}

function applyBootstrap(data) {
  fillSelect(elements.accountSelect, data.accounts, data.selectedAccountName);
  fillSelect(elements.seatUrlSelect, data.seatUrls, data.defaults.seatUrl);
  fillSelect(elements.dateSelect, data.dateOptions, data.defaults.date);
  fillSelect(elements.startHourSelect, data.timeOptions, data.defaults.startHour);
  fillSelect(elements.peopleCountSelect, data.peopleOptions, data.defaults.peopleCount);
  syncDurationOptions(data.defaults.durationHours);
  elements.taskHint.textContent = data.taskHint || "保持网页服务运行。";
  updateLoginStatus();
  updatePeopleHint();
  renderSelectionArea();
  updateTaskCreateHint();
  updateSidebarSummary();
  renderDebugPanel();
}

function applyAccounts(data) {
  state.accounts = data.accounts || [];
  const accountNames = new Set(state.accounts.map((account) => account.name));
  state.taskStatuses = state.taskStatuses.filter((status) => accountNames.has(status.accountName));
  state.taskStatusLoadingAccounts = Object.fromEntries(
    Object.entries(state.taskStatusLoadingAccounts).filter(([accountName]) => accountNames.has(accountName)),
  );
  state.selectedAccountNames = state.selectedAccountNames.filter((accountName) => accountNames.has(accountName));
  if (!state.accounts.length) {
    state.accountBulkSelectionMode = false;
  }
  const configName = extractFileName(data.configPath || "config.json");
  elements.accountConfigPath.textContent = `配置文件：${configName}`;
  elements.accountListSummary.textContent = state.accounts.length
    ? `共 ${state.accounts.length} 个账号，默认：${data.defaultAccountName}`
    : "没有账号，右上角可以随时新建。";

  if (state.accounts.length) {
    const selectedAccountName =
      state.bootstrap?.selectedAccountName || data.selectedAccountName || state.accounts[0].name;
    fillSelect(elements.accountSelect, buildAccountSelectOptions(state.accounts), selectedAccountName);
  } else {
    fillSelect(elements.accountSelect, [], "");
  }
  syncTaskDialogAccountOptions();
  elements.taskCreateButton.disabled = !state.accounts.length;
  elements.accountBulkActionButton.disabled = !state.accounts.length;
  elements.accountBulkActionButton.title = state.accounts.length
    ? ""
    : "没有账号，先到账号管理页新建";
  elements.accountStatusRefreshButton.disabled = !state.accounts.length;
  elements.accountStatusRefreshButton.title = state.accounts.length
    ? ""
    : "没有账号，先到账号管理页新建";
  elements.accountCheckinAllButton.disabled = !state.accounts.length;
  elements.accountCheckinAllButton.title = state.accounts.length
    ? ""
    : "没有账号，先到账号管理页新建";
  elements.seatDisplayRefreshButton.disabled = !state.accounts.length;
  elements.seatDisplayRefreshButton.title = state.accounts.length
    ? ""
    : "没有账号，先到账号管理页新建";

  const editorTargetName = state.accounts.some((account) => account.name === state.accountEditorSelectedName)
    ? state.accountEditorSelectedName
    : data.selectedAccountName || state.accounts[0]?.name || "";
  syncAccountBulkActions();
  renderAccountList();
  renderTaskStatusList();
  refreshTaskStatusesForTasksView(getActiveViewName());
  if (elements.accountDialog.open && editorTargetName) {
    selectAccountEditor(editorTargetName);
  } else if (elements.accountDialog.open) {
    resetAccountEditor();
  }
  // applyBootstrap 阶段就调过一次 updateTaskCreateHint()，但当时 state.accounts 还没填，
  // 会留下"当前还没有账号"的过期文案；这里 accounts 真正落地后再刷一次。
  updateTaskCreateHint();
  renderDebugPanel();
}

function updateLoginStatus() {
  if (!state.bootstrap) {
    return;
  }
  const accountName = state.bootstrap.selectedAccountName || "";
  elements.currentAccountText.textContent = getSelectedAccountLabel(accountName) || "未配置账号";
  if (!accountName) {
    elements.loginStatus.textContent = "请先新增账号";
    elements.loginStatus.style.background = "rgba(174, 134, 33, 0.14)";
    elements.loginStatus.style.color = "var(--warning)";
    return;
  }
  if (state.bootstrap.loginStateValid === false) {
    elements.loginStatus.textContent = state.bootstrap.loginStateReady ? "登录态已失效" : "未保存登录态";
    elements.loginStatus.style.background = "rgba(143, 61, 54, 0.12)";
    elements.loginStatus.style.color = "var(--danger)";
    return;
  }
  if (state.bootstrap.loginStateReady) {
    elements.loginStatus.textContent = "登录态已保存";
    elements.loginStatus.style.background = "rgba(47, 122, 94, 0.14)";
    elements.loginStatus.style.color = "var(--success)";
    return;
  }
  elements.loginStatus.textContent = "未保存登录态";
  elements.loginStatus.style.background = "rgba(143, 61, 54, 0.12)";
  elements.loginStatus.style.color = "var(--danger)";
}

function updatePeopleHint() {
  if (!state.bootstrap) {
    return;
  }
  const peopleCount = getPeopleCount();
  elements.peopleHint.textContent =
    peopleCount === 1 ? "单人预约可直接提交。" : state.bootstrap.multiReserveHint;
}

function syncDurationOptions(preferredValue = "") {
  if (!state.bootstrap || !state.bootstrap.constraints) {
    fillSelect(elements.durationSelect, [], "");
    return;
  }

  const constraints = state.bootstrap.constraints;
  const startHour = Number(elements.startHourSelect.value || constraints.minBeginTime);
  const options = buildDurationOptionsFromConstraints(constraints, startHour);

  const nextValue = options.some((option) => String(option.value) === String(preferredValue))
    ? preferredValue
    : options[0]?.value ?? "";
  fillSelect(elements.durationSelect, options, nextValue);
}

async function searchSeats() {
  if (!state.bootstrap || state.bootstrap.message) {
    showToast(state.bootstrap?.message || "当前还不能查询座位，请先检查登录态。");
    return;
  }

  await runBusyButtonAction(
    elements.searchButton,
    {
      busyLabel: "正在查询...",
      idleLabel: "查询可选座位",
    },
    async () => {
      const data = await requestJsonWithBody("/api/search", collectFilters(), "查询可选座位");
      applySearchResult(data, state.selectedRoomId || data.selectedRoomId || "");
    },
  );
}

function applySearchResult(queryResult, preferredRoomId = "") {
  state.queryResult = queryResult;
  state.selectedRoomId = resolveRoomSelectionValue(queryResult, preferredRoomId);
  trimSelectedSeats();
  renderQueryResult();
}

async function submitReservation() {
  if (!state.queryResult) {
    showToast("请先查询座位，再提交预约。");
    return;
  }

  await runBusyButtonAction(
    elements.reserveButton,
    {
      busyLabel: "正在预约...",
      idleLabel: "确认预约",
      onFinally: () => {
        renderSelectionArea();
      },
    },
    async () => {
      const currentSeatMap = getCurrentSeatMap();
      const payload = {
        ...collectFilters(),
        selectedRoomId: currentSeatMap?.roomId || state.selectedRoomId || "",
        selectedRoomName: currentSeatMap?.roomName || "",
        selectedSeatIds: state.selectedSeatIds,
      };
      const data = await requestJsonWithBody("/api/reserve", payload, "提交预约");
      showToast(data.message);
      await searchSeats();
    },
  );
}

async function submitTaskStatusAction(
  action,
  accountName,
  triggerButton,
  bookingId = "",
  bookingLabel = "",
) {
  const config = {
    checkin: {
      url: "/api/checkin",
      busyLabel: "正在签到...",
      idleLabel: "立即签到",
      debugLabel: "手动签到",
    },
    checkout: {
      url: "/api/checkout",
      busyLabel: "正在签退...",
      idleLabel: "立即签退",
      debugLabel: "手动签退",
    },
    cancel: {
      url: "/api/bookings/cancel",
      busyLabel: "正在取消...",
      idleLabel: "取消预约",
      debugLabel: bookingLabel ? `取消预约 · ${bookingLabel}` : "取消预约",
    },
  }[action];

  if (!config || !accountName) {
    return;
  }

  const payload = { accountName };
  if (bookingId) {
    payload.bookingId = bookingId;
  }

  await runBusyButtonAction(
    triggerButton,
    {
      busyLabel: config.busyLabel,
      idleLabel: config.idleLabel,
    },
    async () => {
      const data = await requestJsonWithBody(config.url, payload, config.debugLabel);
      showToast(data.message);
      await Promise.all([loadTasks(true), loadTaskStatuses(true)]);
    },
  );
}

async function loadTasks(silent = false) {
  try {
    const data = await requestJson("/api/automation-plans", {
      debugLabel: "读取自动任务计划",
      debugSilent: silent,
    });
    state.tasks = data.plans || [];
    renderTaskList();
  } catch (error) {
    if (!silent) {
      showToast(error.message);
    }
  }
}

async function loadTaskStatuses(silent = false) {
  if (!state.accounts.length) {
    state.taskStatuses = [];
    state.taskStatusLoadingAccounts = {};
    state.taskStatusLoadedAt = "";
    renderTaskStatusList();
    renderAccountList();
    return 0;
  }

  state.accounts.forEach((account) => {
    state.taskStatusLoadingAccounts[account.name] = true;
  });
  renderTaskStatusList();

  const results = await Promise.allSettled(
    state.accounts.map((account) => loadSingleTaskStatus(account.name, { silent: true })),
  );
  const failedCount = results.filter((result) => result.status === "rejected").length;
  if (failedCount && !silent) {
    showToast(`有 ${failedCount} 个账号状态刷新失败，请点“更多”查看详情。`);
  }
  return failedCount;
}

async function loadSingleTaskStatus(accountName, { silent = false, rethrow = true } = {}) {
  if (!accountName) {
    return null;
  }

  state.taskStatusLoadingAccounts[accountName] = true;
  renderTaskStatusList();

  try {
    const params = new URLSearchParams({ accountName });
    const data = await requestJson(`/api/task-status?${params.toString()}`, {
      debugLabel: `检测账号状态 · ${accountName}`,
      debugSilent: silent,
    });
    const nextStatus = (data.statuses || [])[0] || buildTaskStatusPlaceholder(getAccountProfile(accountName));
    upsertTaskStatus(nextStatus);
    state.taskStatusLoadedAt = data.serverTime || state.taskStatusLoadedAt;
    return nextStatus;
  } catch (error) {
    upsertTaskStatus(buildTaskStatusErrorState(accountName, error));
    if (!silent) {
      showToast(error.message);
    }
    if (rethrow) {
      throw error;
    }
    return null;
  } finally {
    delete state.taskStatusLoadingAccounts[accountName];
    renderTaskList();
    renderTaskStatusList();
    renderAccountList();
  }
}

function upsertTaskStatus(nextStatus) {
  const accountName = String(nextStatus?.accountName || "").trim();
  if (!accountName) {
    return;
  }
  state.taskStatuses = state.taskStatuses
    .filter((status) => status.accountName !== accountName)
    .concat(nextStatus)
    .sort((leftStatus, rightStatus) =>
      compareAccountsForDisplay(leftStatus.accountName, rightStatus.accountName),
    );
}

function applyCachedTaskStatuses(data) {
  const statuses = Array.isArray(data?.taskStatuses) ? data.taskStatuses : [];
  state.taskStatuses = statuses
    .filter((status) => String(status?.accountName || "").trim())
    .sort((leftStatus, rightStatus) =>
      compareAccountsForDisplay(leftStatus.accountName, rightStatus.accountName),
    );
  state.taskStatusLoadedAt = data?.taskStatusLoadedAt || "";
}

async function refreshTaskStatusForChangedPlan(accountName) {
  const changedAccountName = String(accountName || "").trim();
  await loadTasks(true);
  if (changedAccountName) {
    await loadSingleTaskStatus(changedAccountName, { silent: true, rethrow: false });
  }
}

async function createTaskBatch() {
  const validationMessage = getTaskDialogValidationMessage();
  if (validationMessage) {
    setTaskDialogFeedback(validationMessage);
    return;
  }

  await runBusyButtonAction(
    elements.taskDialogSubmitButton,
    {
      busyLabel: state.taskDialogEditingPlanId ? "正在保存..." : "正在创建...",
      idleLabel: state.taskDialogEditingPlanId ? "保存自动任务" : "创建自动任务",
      onFinally: updateTaskDialogPreview,
      onError: (error) => {
        setTaskDialogFeedback(error.message);
      },
    },
    async () => {
      setTaskDialogFeedback("");
      const payload = buildTaskDialogPlanPayload();
      const data = await requestJsonWithBody("/api/automation-plans", payload, "保存自动任务计划");
      showToast(data.message);
      closeTaskDialog();
      await refreshTaskStatusForChangedPlan(payload.accountName);
    },
  );
}

async function deleteTask(taskId, triggerButton = null, taskSummary = "", accountName = "") {
  if (!taskId) {
    return;
  }

  const confirmMessage = taskSummary
    ? `确定删除这个自动任务计划吗？\n${taskSummary}`
    : "确定删除这个自动任务计划吗？";
  if (!window.confirm(confirmMessage)) {
    return;
  }

  const actionButton = triggerButton || elements.taskCreateButton;
  await runBusyButtonAction(
    actionButton,
    {
      busyLabel: "正在删除...",
      idleLabel: triggerButton ? "删除计划" : "新建自动任务",
      onFinally: triggerButton ? null : updateTaskCreateHint,
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/automation-plans/delete",
        { planId: taskId },
        "删除自动任务计划",
      );
      showToast(data.message);
      await refreshTaskStatusForChangedPlan(accountName);
    },
  );
}

async function runManualReserveCheck() {
  await runBusyButtonAction(
    elements.taskCheckReserveButton,
    {
      busyLabel: "正在检查...",
      idleLabel: "手动检查预约",
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/automation-plans/check-now",
        {},
        "手动检查预约",
      );
      showToast(data.message);
      await Promise.all([loadTasks(true), loadTaskStatuses(true)]);
    },
  );
}

async function handleTaskUploadClick() {
  const button = elements.taskUploadButton;
  if (!button || button.disabled) {
    return;
  }

  const confirmed = window.confirm(
    "将本地自动任务计划上传到服务端。云端只记录座位信息，不会删除其它账号。继续？",
  );
  if (!confirmed) {
    return;
  }

  await runBusyButtonAction(
    button,
    {
      busyLabel: "上传中…",
      idleLabel: "上传自动任务",
      restoreDisabled: () => !state.tasks.length,
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/sync/upload-automation-plans",
        {},
        "上传本地自动任务到服务端",
      );
      if (!data?.ok) {
        const message =
          SYNC_ERROR_MESSAGES[data?.error_code] || data?.message || "自动任务上传失败";
        showToast(message);
        return;
      }
      showToast(data.message || "自动任务上传完成");
    },
  );
}

async function loadTaskDialogBootstrap(accountName, preferredSeatUrl = "", silent = false) {
  const query = new URLSearchParams();
  if (accountName) {
    query.set("accountName", accountName);
  }
  if (preferredSeatUrl) {
    query.set("seatUrl", preferredSeatUrl);
  }

  const data = await requestJson(`/api/bootstrap?${query.toString()}`, {
    debugLabel: "读取任务弹窗配置",
    debugSilent: silent,
  });
  state.taskDialogBootstrap = data;
  const availableSeatUrls = Array.isArray(data.seatUrls) ? data.seatUrls.map((option) => option.value) : [];
  state.taskDialogSeatUrl =
    (preferredSeatUrl && availableSeatUrls.includes(preferredSeatUrl) ? preferredSeatUrl : "") ||
    availableSeatUrls[0] ||
    state.taskDialogSeatUrl ||
    "";
  return data;
}

function setTaskDialogFeedback(message = "", level = "error") {
  state.taskDialogFeedbackMessage = String(message || "").trim();
  state.taskDialogFeedbackLevel = level || "error";
  if (!elements.taskDialogFeedback) {
    return;
  }

  const classNames = ["task-dialog-feedback"];
  if (state.taskDialogFeedbackMessage) {
    classNames.push(`is-${state.taskDialogFeedbackLevel}`);
  }
  elements.taskDialogFeedback.className = classNames.join(" ");
  elements.taskDialogFeedback.hidden = !state.taskDialogFeedbackMessage;
  elements.taskDialogFeedback.textContent = state.taskDialogFeedbackMessage;
}

function buildTaskDialogSearchPayload() {
  const accountName = elements.taskDialogAccountSelect.value || getAccountName();
  const seatUrl = state.taskDialogSeatUrl || getTaskDialogBootstrap()?.defaults?.seatUrl || "";
  const date = String(elements.taskDialogDateSelect.value || "").trim();
  const startHour = Number(elements.taskDialogStartHourSelect.value || 0);
  const durationHours = Number(elements.taskDialogDurationSelect.value || 0);
  if (!accountName || !seatUrl || !date || !startHour || !durationHours) {
    return null;
  }
  return {
    accountName,
    seatUrl,
    date,
    startHour,
    durationHours,
    peopleCount: 1,
  };
}

function buildRoomOptionValue(room) {
  return String(room?.roomId || room?.roomName || "").trim();
}

function getQueryRooms(queryResult) {
  if (Array.isArray(queryResult?.rooms) && queryResult.rooms.length) {
    return queryResult.rooms;
  }
  if (queryResult?.seatMap) {
    return [queryResult.seatMap];
  }
  return [];
}

function findRoomByName(queryResult, roomName = "") {
  const normalizedRoomName = String(roomName || "").trim();
  if (!normalizedRoomName) {
    return null;
  }
  return getQueryRooms(queryResult).find(
    (room) => String(room.roomName || "").trim() === normalizedRoomName,
  ) || null;
}

function resolveRoomSelectionValue(queryResult, preferredRoomId = "", preferredRoomName = "") {
  const rooms = getQueryRooms(queryResult);
  if (!rooms.length) {
    return "";
  }

  const normalizedPreferred = String(preferredRoomId || "").trim();
  if (normalizedPreferred && rooms.some((room) => buildRoomOptionValue(room) === normalizedPreferred)) {
    return normalizedPreferred;
  }

  const namedRoom = findRoomByName(queryResult, preferredRoomName);
  if (namedRoom) {
    return buildRoomOptionValue(namedRoom);
  }

  const querySelectedRoomId = String(queryResult?.selectedRoomId || queryResult?.seatMap?.roomId || "").trim();
  if (querySelectedRoomId && rooms.some((room) => buildRoomOptionValue(room) === querySelectedRoomId)) {
    return querySelectedRoomId;
  }

  return buildRoomOptionValue(rooms[0]);
}

function findRoomByValue(queryResult, roomValue = "") {
  const normalizedRoomValue = String(roomValue || "").trim();
  if (!normalizedRoomValue) {
    return null;
  }
  return getQueryRooms(queryResult).find((room) => buildRoomOptionValue(room) === normalizedRoomValue) || null;
}

function buildRoomSelectOptions(queryResult) {
  return getQueryRooms(queryResult).map((room) => ({
    value: buildRoomOptionValue(room),
    label: room.roomName || buildRoomOptionValue(room),
  }));
}

function isTaskDialogQueryResultCurrent() {
  const payload = buildTaskDialogSearchPayload();
  const query = state.taskDialogQueryResult?.query;
  if (!payload || !query) {
    return false;
  }
  return (
    String(query.accountName || "") === String(payload.accountName || "") &&
    String(query.seatUrl || "") === String(payload.seatUrl || "") &&
    String(query.date || "") === String(payload.date || "") &&
    Number(query.startHour) === Number(payload.startHour) &&
    Number(query.durationHours) === Number(payload.durationHours) &&
    Number(query.peopleCount) === Number(payload.peopleCount)
  );
}

function getTaskDialogCurrentQueryResult() {
  const payload = buildTaskDialogSearchPayload();
  if (!payload) {
    return null;
  }

  if (isTaskDialogQueryResultCurrent()) {
    return state.taskDialogQueryResult;
  }

  const reserveQuery = state.queryResult?.query;
  if (
    reserveQuery &&
    String(reserveQuery.accountName || "") === String(payload.accountName || "") &&
    String(reserveQuery.seatUrl || "") === String(payload.seatUrl || "") &&
    String(reserveQuery.date || "") === String(payload.date || "") &&
    Number(reserveQuery.startHour) === Number(payload.startHour) &&
    Number(reserveQuery.durationHours) === Number(payload.durationHours) &&
    Number(reserveQuery.peopleCount) === Number(payload.peopleCount)
  ) {
    return state.queryResult;
  }

  return null;
}

function syncTaskDialogSelectedRoom(roomValue = "") {
  const queryResult = getTaskDialogCurrentQueryResult();
  const fallbackRoomId = String(roomValue || state.taskDialogSelectedRoomId || "").trim();
  if (!queryResult) {
    state.taskDialogSelectedRoomId = fallbackRoomId;
    return;
  }

  const selectedRoomId = resolveRoomSelectionValue(
    queryResult,
    fallbackRoomId,
    state.taskDialogSelectedRoomName,
  );
  state.taskDialogSelectedRoomId = selectedRoomId;
  const selectedRoom = findRoomByValue(queryResult, selectedRoomId);
  if (selectedRoom?.roomName) {
    state.taskDialogSelectedRoomName = selectedRoom.roomName;
  }
}

function getTaskDialogCurrentSeatMap() {
  const queryResult = getTaskDialogCurrentQueryResult();
  if (!queryResult) {
    return null;
  }

  const selectedRoomId = resolveRoomSelectionValue(
    queryResult,
    state.taskDialogSelectedRoomId,
    state.taskDialogSelectedRoomName,
  );
  const selectedRoom =
    findRoomByValue(queryResult, selectedRoomId) ||
    findRoomByName(queryResult, state.taskDialogSelectedRoomName) ||
    queryResult.seatMap ||
    getQueryRooms(queryResult)[0] ||
    null;
  if (!selectedRoom) {
    return null;
  }

  state.taskDialogSelectedRoomId = buildRoomOptionValue(selectedRoom);
  state.taskDialogSelectedRoomName = selectedRoom.roomName || state.taskDialogSelectedRoomName || "";
  return selectedRoom;
}

function renderTaskDialogRoomOptions() {
  if (!elements.taskDialogRoomSelect) {
    return;
  }

  const queryResult = getTaskDialogCurrentQueryResult();
  const options = buildRoomSelectOptions(queryResult);
  if (!options.length) {
    fillSelect(elements.taskDialogRoomSelect, [], "");
    elements.taskDialogRoomSelect.disabled = true;
    return;
  }
  const selectedRoomId = resolveRoomSelectionValue(
    queryResult,
    state.taskDialogSelectedRoomId,
    state.taskDialogSelectedRoomName,
  );
  fillSelect(elements.taskDialogRoomSelect, options, selectedRoomId);
  elements.taskDialogRoomSelect.disabled = options.length <= 1;
  syncTaskDialogSelectedRoom(selectedRoomId);
}

function renderTaskDialogQuerySummary() {
  if (!elements.taskDialogQuerySummary) {
    return;
  }

  const payload = buildTaskDialogSearchPayload();
  renderTaskDialogRoomOptions();
  if (!payload) {
    elements.taskDialogQuerySummary.textContent = "请选择账号、日期、开始时间和使用时长后，再查询当前可选座位。";
    elements.taskDialogRefreshButton.disabled = true;
    return;
  }

  elements.taskDialogRefreshButton.disabled = state.taskDialogQueryBusy;

  if (state.taskDialogQueryBusy) {
    elements.taskDialogQuerySummary.textContent = `正在按 ${payload.accountName} 当前条件后台查询可选座位...`;
    return;
  }

  const currentQueryResult = getTaskDialogCurrentQueryResult();
  const seatMap = getTaskDialogCurrentSeatMap();
  if (currentQueryResult && seatMap) {
    const roomName = seatMap.roomName || currentQueryResult.roomName || "当前阅览室";
    const query = currentQueryResult.query || {};
    const summaryText =
      query.date && query.startHour && query.durationHours && query.peopleCount
        ? `${roomName} · ${query.date} · ${query.startHour}:00 开始 · ${query.durationHours} 小时 · ${query.peopleCount} 人`
        : currentQueryResult.summary || "已完成查询";
    elements.taskDialogQuerySummary.textContent =
      `${summaryText} · 可选 ${Number(seatMap.availableCount || 0)} 个`;
    return;
  }

  if (state.taskDialogQueryError) {
    elements.taskDialogQuerySummary.textContent = `查询失败：${state.taskDialogQueryError}`;
    return;
  }

  if (state.taskDialogQueryResult) {
    elements.taskDialogQuerySummary.textContent = "当前条件已变更，请点“刷新查询”更新这次自动任务可用的座位范围。";
    return;
  }

  elements.taskDialogQuerySummary.textContent =
    "打开弹窗后会自动按当前账号后台查询，也可以随时手动点“刷新查询”。";
}

function markTaskDialogQueryStale() {
  state.taskDialogQueryError = "";
  setTaskDialogFeedback("");
  renderTaskDialogQuerySummary();
  updateTaskDialogPreview();
}

async function refreshTaskDialogQuery() {
  const payload = buildTaskDialogSearchPayload();
  if (!payload) {
    renderTaskDialogQuerySummary();
    return null;
  }

  const requestSequence = state.taskDialogQuerySequence + 1;
  state.taskDialogQuerySequence = requestSequence;
  state.taskDialogQueryBusy = true;
  state.taskDialogQueryError = "";
  setTaskDialogFeedback("");
  renderTaskDialogQuerySummary();
  updateTaskDialogPreview();

  try {
    const data = await requestJsonWithBody("/api/search", payload, "自动任务弹窗查询座位");
    if (requestSequence !== state.taskDialogQuerySequence) {
      return null;
    }
    state.taskDialogQueryResult = data;
    // 不要在这里把 data.selectedRoomId 当 fallback 传进去——否则会覆盖 booking 历史回填的 roomName，
    // 导致默认自习室被搜索接口的默认值（往往是综合阅览室）顶替。
    // syncTaskDialogSelectedRoom 内部已经会按 state.taskDialogSelectedRoomName 兜底匹配。
    const preferredRoomId =
      state.taskDialogSelectedRoomId ||
      (state.taskDialogSelectedRoomName ? "" : data.selectedRoomId || "");
    syncTaskDialogSelectedRoom(preferredRoomId);
    return data;
  } catch (error) {
    if (requestSequence !== state.taskDialogQuerySequence) {
      return null;
    }
    state.taskDialogQueryError = error.message;
    state.taskDialogQueryResult = null;
    return null;
  } finally {
    if (requestSequence === state.taskDialogQuerySequence) {
      state.taskDialogQueryBusy = false;
      renderTaskDialogQuerySummary();
      updateTaskDialogPreview();
    }
  }
}

async function saveAccount() {
  const payload = collectAccountFormPayload();
  if (!payload) {
    return;
  }

  await runBusyButtonAction(
    elements.accountSaveButton,
    {
      busyLabel: "正在保存...",
      idleLabel: "保存账号",
    },
    async () => {
      const data = await requestJsonWithBody("/api/accounts", payload, "保存账号配置");
      showToast(data.message);
      applyAccounts(data);
      await loadBootstrap(payload.studentId, "", false);
      closeAccountDialog();
      switchView("accounts");
    },
  );
}

async function runCheckinAllAccounts() {
  await runBusyButtonAction(
    elements.accountCheckinAllButton,
    {
      busyLabel: "正在签到...",
      idleLabel: "一键签到所有账号",
      restoreDisabled: () => !state.accounts.length,
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/accounts/checkin-all",
        {},
        "一键签到所有账号",
      );
      showToast(data.message);
      await Promise.all([
        loadAccounts(state.bootstrap?.selectedAccountName || ""),
        loadTaskStatuses(true),
      ]);
    },
  );
}

async function refreshAllTaskStatuses(button, idleLabel) {
  await runBusyButtonAction(
    button,
    {
      busyLabel: "正在获取...",
      idleLabel,
      restoreDisabled: () => !state.accounts.length,
    },
    async () => {
      const failedCount = await loadTaskStatuses();
      if (!failedCount) {
        showToast("账号状态已更新。");
      }
    },
  );
}

async function refreshAllAccountLoginStates() {
  await runBusyButtonAction(
    elements.accountStatusRefreshButton,
    {
      busyLabel: "正在登录...",
      idleLabel: "一键获取登录状态",
      restoreDisabled: () => !state.accounts.length,
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/accounts/refresh-login-all",
        {},
        "一键获取登录状态",
      );
      showToast(data.message);
      applyAccounts(data);
      await Promise.all([
        loadBootstrap(data.selectedAccountName || data.defaultAccountName, "", false),
        loadTaskStatuses(true),
      ]);
    },
  );
}

async function importAccounts() {
  const rawText = elements.accountBulkImportTextInput.value.trim();
  if (!rawText) {
    showToast("请先粘贴要导入的账号。");
    return;
  }

  await runBusyButtonAction(
    elements.accountBulkImportSubmitButton,
    {
      busyLabel: "正在导入...",
      idleLabel: "导入账号",
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/accounts/import",
        { rawText },
        "批量导入账号",
      );
      showToast(data.message);
      elements.accountBulkImportResult.textContent = formatAccountImportResult(data.importResult);
      applyAccounts(data);
      if (data.importResult?.acceptedCount > 0) {
        await loadBootstrap(data.selectedAccountName || data.defaultAccountName, "", false);
      }
    },
  );
}

async function deleteSelectedAccounts() {
  const accountNames = getSelectedAccountNames();
  if (!accountNames.length) {
    showToast("请先选择要删除的账号。");
    return;
  }

  const accountLabels = accountNames
    .map((accountName) => getAccountDisplayName(getAccountProfile(accountName)) || accountName)
    .join("、");
  if (!window.confirm(`确定删除选中的 ${accountNames.length} 个账号吗？\n${accountLabels}`)) {
    return;
  }

  await runBusyButtonAction(
    elements.accountDeleteSelectedButton,
    {
      busyLabel: "正在删除...",
      idleLabel: "删除选中",
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/accounts/delete-batch",
        { accountNames },
        "批量删除账号",
      );
      showToast(data.message);
      state.accountBulkSelectionMode = false;
      state.selectedAccountNames = [];
      applyAccounts(data);
      await loadBootstrap(data.selectedAccountName || data.defaultAccountName, "", false);
      switchView("accounts");
    },
  );
}

async function refreshAccountLogin() {
  const payload = collectAccountFormPayload();
  if (!payload) {
    return;
  }
  const requestPayload = state.accountEditorOriginalName
    ? { ...payload, accountName: state.accountEditorOriginalName }
    : payload;
  const targetAccountName = payload.studentId;

  showToast("正在刷新登录态，请稍候。");
  await runBusyButtonAction(
    elements.accountRefreshLoginButton,
    {
      busyLabel: "正在登录...",
      idleLabel: "刷新认证",
      restoreDisabled: () => false,
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/accounts/refresh-login",
        requestPayload,
        "刷新认证",
      );
      const refreshedAccountName = data.selectedAccountName || targetAccountName;
      showToast(data.message);
      applyAccounts(data);
      await loadBootstrap(refreshedAccountName, "", false);
      selectAccountEditor(refreshedAccountName);
    },
  );
}

async function deleteAccountProfile() {
  if (!state.accountEditorOriginalName) {
    showToast("请先选择要删除的账号。");
    return;
  }

  const account = getAccountProfile(state.accountEditorOriginalName);
  const accountLabel = getAccountDisplayName(account) || state.accountEditorOriginalName;
  if (!window.confirm(`确定删除账号“${accountLabel}”吗？删除后需要重新新建才能继续使用。`)) {
    return;
  }

  await runBusyButtonAction(
    elements.accountDeleteButton,
    {
      busyLabel: "正在删除...",
      idleLabel: "删除账号",
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/accounts/delete",
        { accountName: state.accountEditorOriginalName },
        "删除账号配置",
      );
      showToast(data.message);
      applyAccounts(data);
      await loadBootstrap(data.selectedAccountName || data.defaultAccountName, "", false);
      closeAccountDialog();
      switchView("accounts");
    },
  );
}

async function setDefaultAccount(accountName) {
  try {
    const data = await requestJsonWithBody("/api/accounts/default", { accountName }, "切换默认账号");
    showToast(data.message);
    applyAccounts(data);
  } catch (error) {
    showToast(error.message);
  }
}

function collectFilters() {
  return {
    accountName: getAccountName(),
    seatUrl: elements.seatUrlSelect.value,
    date: elements.dateSelect.value,
    startHour: Number(elements.startHourSelect.value),
    durationHours: Number(elements.durationSelect.value),
    peopleCount: getPeopleCount(),
  };
}

function collectAccountFormPayload() {
  const studentId = elements.accountStudentIdInput.value.trim();
  const password = elements.accountPasswordInput.value.trim();

  if (!studentId) {
    showToast("请填写学号。");
    return null;
  }
  if (!/^\d{6,}$/.test(studentId)) {
    showToast("学号格式看起来不对，请检查后再试。");
    return null;
  }

  return {
    originalName: state.accountEditorOriginalName,
    studentId,
    password,
  };
}

function formatAccountImportResult(result) {
  if (!result) {
    return "";
  }
  const invalid = Array.isArray(result.invalid) ? result.invalid : [];
  const duplicates = Array.isArray(result.duplicates) ? result.duplicates : [];
  const skipped = [...invalid, ...duplicates];
  const lines = [`成功导入 ${Number(result.acceptedCount || 0)} 个账号。`];
  if (!skipped.length) {
    return lines.join("\n");
  }

  lines.push(`跳过 ${skipped.length} 行：`);
  skipped.slice(0, 8).forEach((item) => {
    const studentId = item.studentId ? ` ${item.studentId}` : "";
    lines.push(`第 ${item.lineNumber} 行${studentId}：${item.reason}`);
  });
  if (skipped.length > 8) {
    lines.push(`还有 ${skipped.length - 8} 行未显示。`);
  }
  return lines.join("\n");
}

function syncTaskDialogAccountOptions(preferredAccountName = "") {
  const options = state.accounts.length ? buildAccountSelectOptions(state.accounts) : [];
  fillSelect(elements.taskDialogAccountSelect, options, preferredAccountName || getAccountName());
}

function applyTaskDialogMode(plan = null) {
  const isEditing = Boolean(plan?.planId);
  if (elements.taskDialogEyebrow) {
    elements.taskDialogEyebrow.textContent = isEditing ? "编辑计划" : "自动计划";
  }
  if (elements.taskDialogTitle) {
    elements.taskDialogTitle.textContent = isEditing ? "修改这个账号的自动计划" : "预约、签到、签退一次设置";
  }
  elements.taskDialogSubmitButton.textContent = isEditing ? "保存自动任务" : "创建自动任务";
}

function getTaskDialogBootstrap() {
  return state.taskDialogBootstrap || state.bootstrap;
}

function findLatestPlanForAccount(accountName) {
  const targetAccountName = String(accountName || "").trim();
  if (!targetAccountName) {
    return null;
  }
  return [...state.tasks]
    .filter((task) => String(task.accountName || "").trim() === targetAccountName)
    .sort((leftTask, rightTask) =>
      String(rightTask.updatedAt || "").localeCompare(String(leftTask.updatedAt || "")),
    )[0] || null;
}

function findLatestBookingForAccount(accountName) {
  const targetAccountName = String(accountName || "").trim();
  if (!targetAccountName) {
    return null;
  }
  const status = state.taskStatuses.find(
    (entry) => String(entry?.accountName || "").trim() === targetAccountName,
  );
  const bookings = Array.isArray(status?.bookings) ? status.bookings : [];
  // 后端已经按 "在馆 → 可签到 → 已预约 → 已结束" 的优先级 + 起始时间倒序排好；取首条即"最贴近当下"的那条。
  return bookings.find((booking) => String(booking?.seatNumber || "").trim()) || null;
}

function applyTaskDialogSeatDefaults({ roomId = "", roomName = "", seatNumber = "" } = {}) {
  elements.taskDialogSeatNumberInput.value = String(seatNumber || "");
  state.taskDialogSelectedRoomName = String(roomName || "");
  state.taskDialogSelectedRoomId = String(roomId || "");
}

function applyTaskDialogHistoryDefaults(accountName) {
  // 用同账号"上一次"作为新建弹窗座位号 / 自习室的默认值。
  // 优先级：图书馆 booking 历史最近一条 → 本地保存过的自动计划 → 不动。
  const lastBooking = findLatestBookingForAccount(accountName);
  const lastPlan = findLatestPlanForAccount(accountName);
  const seatNumber = lastBooking?.seatNumber || lastPlan?.seatNumber || "";
  const roomName = lastBooking?.roomName || lastPlan?.roomName || "";
  const roomId = lastPlan?.roomId || "";

  // booking 没有稳定 roomId；先保留 plan.roomId，等 query 回来 syncTaskDialogSelectedRoom 会按 roomName 匹配真实 id。
  applyTaskDialogSeatDefaults({ roomId, roomName, seatNumber });
}

async function openTaskDialog(plan = null) {
  if (!state.accounts.length || !getAccountName()) {
    showToast("当前还没有账号，请先新增账号。");
    return;
  }

  const isEditing = Boolean(plan?.planId);
  const accountName = plan?.accountName || getAccountName();
  const reuseCurrentQueryContext = isEditing || accountName === getAccountName();

  state.taskDialogQuerySequence += 1;
  state.taskDialogQueryResult = null;
  state.taskDialogQueryBusy = false;
  state.taskDialogQueryError = "";
  setTaskDialogFeedback("");
  state.taskDialogEditingPlanId = plan?.planId || "";
  // 仅当用户在选座页主动锁定了一个具体座位时，才把当前选中的房间/座位带进弹窗；
  // 否则一律走"上一次"的默认值（图书馆 booking 历史 / 已保存自动计划），避免被默认查询结果覆盖成错的房间。
  const userPickedSeatFromMap =
    !isEditing && reuseCurrentQueryContext && state.selectedSeatIds.length === 1
      ? getSelectedSeats()[0] || null
      : null;
  state.taskDialogSelectedRoomId =
    plan?.roomId || (userPickedSeatFromMap ? state.selectedRoomId || state.queryResult?.selectedRoomId || "" : "");
  state.taskDialogSelectedRoomName =
    plan?.roomName || (userPickedSeatFromMap ? getCurrentSeatMap()?.roomName || "" : "");
  const preferredSeatUrl = plan?.seatUrl || (reuseCurrentQueryContext ? elements.seatUrlSelect.value || "" : "");
  syncTaskDialogAccountOptions(accountName);
  elements.taskDialogAccountSelect.disabled = isEditing;
  state.taskDialogSeatUrl = preferredSeatUrl;
  applyTaskDialogMode(plan);
  await loadTaskDialogBootstrap(accountName, preferredSeatUrl, true);

  const dialogBootstrap = getTaskDialogBootstrap();
  fillSelect(
    elements.taskDialogDateSelect,
    dialogBootstrap?.dateOptions || [],
    plan?.selectedDate || (reuseCurrentQueryContext ? state.queryResult?.query?.date : "") || dialogBootstrap?.defaults?.date || "",
  );

  const preferredStartHour =
    plan?.startHour || (reuseCurrentQueryContext ? state.queryResult?.query?.startHour : "") || dialogBootstrap?.defaults?.startHour || "";
  fillSelect(elements.taskDialogStartHourSelect, dialogBootstrap?.timeOptions || [], preferredStartHour);
  syncTaskDialogDurationOptions(
    plan?.durationHours ||
      (reuseCurrentQueryContext ? state.queryResult?.query?.durationHours : "") ||
      dialogBootstrap?.defaults?.durationHours ||
      "",
  );

  if (isEditing) {
    elements.taskDialogSeatNumberInput.value = String(plan?.seatNumber || "");
  } else if (userPickedSeatFromMap) {
    // 用户从选座页带了具体座位过来；前面已经把对应房间也写入 state，这里只填座位号即可。
    elements.taskDialogSeatNumberInput.value = String(userPickedSeatFromMap.seatNumber || "");
  } else if (plan?.seatNumber || plan?.roomName || plan?.roomId) {
    applyTaskDialogSeatDefaults({
      roomId: plan?.roomId || "",
      roomName: plan?.roomName || "",
      seatNumber: plan?.seatNumber || "",
    });
  } else {
    applyTaskDialogHistoryDefaults(accountName);
  }
  elements.taskDialogReserveEnabled.checked = isEditing ? Boolean(plan.reserve?.enabled) : true;
  elements.taskDialogCheckinEnabled.checked = isEditing ? Boolean(plan.checkin?.enabled) : true;
  elements.taskDialogCheckoutEnabled.checked = isEditing ? Boolean(plan.checkout?.enabled) : true;
  elements.taskDialogContinuousCheckbox.checked = isEditing ? Boolean(plan.continuousReserve) : true;
  renderTaskDialogRoomOptions();
  if (isEditing) {
    elements.taskDialogReserveTimeInput.value = plan.reserve?.time || "";
    elements.taskDialogCheckinTimeInput.value = plan.checkin?.time || "";
    elements.taskDialogCheckoutTimeInput.value = plan.checkout?.time || "21:59";
  } else {
    syncTaskDialogActionTimes();
  }
  updateTaskDialogPreview();
  if (typeof elements.taskDialog.showModal === "function") {
    if (!elements.taskDialog.open) {
      elements.taskDialog.showModal();
    }
  } else {
    elements.taskDialog.setAttribute("open", "open");
  }
  renderTaskDialogQuerySummary();
  void refreshTaskDialogQuery();
}

function closeTaskDialog() {
  state.taskDialogQuerySequence += 1;
  state.taskDialogEditingPlanId = "";
  state.taskDialogBootstrap = null;
  state.taskDialogSeatUrl = "";
  state.taskDialogSelectedRoomId = "";
  state.taskDialogSelectedRoomName = "";
  state.taskDialogQueryResult = null;
  state.taskDialogQueryBusy = false;
  state.taskDialogQueryError = "";
  elements.taskDialogAccountSelect.disabled = false;
  setTaskDialogFeedback("");
  if (elements.taskDialog.open && typeof elements.taskDialog.close === "function") {
    elements.taskDialog.close();
    return;
  }
  elements.taskDialog.removeAttribute("open");
}

function syncTaskDialogDefaults() {
  const dialogBootstrap = getTaskDialogBootstrap();
  if (!dialogBootstrap) {
    return;
  }

  const selectedDate = elements.taskDialogDateSelect.value || dialogBootstrap.defaults?.date || "";
  const startHour = buildTaskDialogDefaultStartHour(selectedDate);
  fillSelect(elements.taskDialogStartHourSelect, dialogBootstrap.timeOptions || [], startHour);
  syncTaskDialogDurationOptions(buildTaskDialogDefaultDuration(selectedDate, startHour));
  syncTaskDialogActionTimes();
  updateTaskDialogPreview();
}

function syncTaskDialogDurationOptions(preferredValue = "") {
  const dialogBootstrap = getTaskDialogBootstrap();
  if (!dialogBootstrap || !dialogBootstrap.constraints) {
    fillSelect(elements.taskDialogDurationSelect, [], "");
    return;
  }

  const constraints = dialogBootstrap.constraints;
  const startHour = Number(elements.taskDialogStartHourSelect.value || constraints.minBeginTime);
  const options = buildDurationOptionsFromConstraints(constraints, startHour);

  const nextValue = options.some((option) => String(option.value) === String(preferredValue))
    ? preferredValue
    : options[options.length - 1]?.value ?? "";
  fillSelect(elements.taskDialogDurationSelect, options, nextValue);
}

function syncTaskDialogActionTimes() {
  const startHour = Number(elements.taskDialogStartHourSelect.value || 8);
  const defaultStartTime = `${padNumber(startHour)}:00`;
  elements.taskDialogReserveTimeInput.value = defaultStartTime;
  elements.taskDialogCheckinTimeInput.value = buildTaskDialogDefaultCheckinTime(startHour);
  if (!elements.taskDialogCheckoutTimeInput.value) {
    elements.taskDialogCheckoutTimeInput.value = "21:59";
  }
}

function buildTaskDialogDefaultCheckinTime(startHour) {
  const totalMinutes = Math.max(Number(startHour || 0) * 60 - 25, 0);
  const hour = Math.floor(totalMinutes / 60);
  const minute = totalMinutes % 60;
  return `${padNumber(hour)}:${padNumber(minute)}`;
}

function getTaskDialogMatchedQuery(dateValue, startHour = null) {
  const dialogAccountName = elements.taskDialogAccountSelect.value || getAccountName();
  const queryCandidates = [state.taskDialogQueryResult?.query, state.queryResult?.query];
  return (
    queryCandidates.find((query) => {
      if (
        !query ||
        query.accountName !== dialogAccountName ||
        String(query.seatUrl || "") !== String(state.taskDialogSeatUrl || "")
      ) {
        return false;
      }
      if (String(query.date || "") !== String(dateValue || "")) {
        return false;
      }
      if (startHour !== null && Number(query.startHour) !== Number(startHour)) {
        return false;
      }
      return true;
    }) || null
  );
}

function buildTaskDialogDefaultStartHour(dateValue) {
  const matchedQuery = getTaskDialogMatchedQuery(dateValue);
  const dialogBootstrap = getTaskDialogBootstrap();
  const fallbackStartHour = Number(dialogBootstrap?.constraints?.minBeginTime || 8);
  if (matchedQuery) {
    return resolveTaskDialogDefaultStartHour(dateValue, Number(matchedQuery.startHour), fallbackStartHour);
  }
  if (String(dialogBootstrap?.defaults?.date || "") === String(dateValue || "")) {
    return resolveTaskDialogDefaultStartHour(
      dateValue,
      Number(dialogBootstrap?.defaults?.startHour || fallbackStartHour),
      fallbackStartHour,
    );
  }
  return fallbackStartHour;
}

function buildTaskDialogDefaultDuration(dateValue, startHour) {
  const matchedQuery = getTaskDialogMatchedQuery(dateValue, startHour);
  if (matchedQuery) {
    return Number(matchedQuery.durationHours);
  }
  const dialogBootstrap = getTaskDialogBootstrap();
  if (!dialogBootstrap?.constraints) {
    return 1;
  }
  return Math.min(
    Number(dialogBootstrap.constraints.maxDuration),
    Number(dialogBootstrap.constraints.maxEndTime) - Number(startHour),
  );
}

function buildDurationOptionsFromConstraints(constraints, startHour) {
  const maxDuration = Math.min(
    Number(constraints.maxDuration),
    Number(constraints.maxEndTime) - Number(startHour),
  );
  const options = [];
  for (let hour = Number(constraints.minDuration); hour <= maxDuration; hour += 1) {
    options.push({ value: hour, label: `${hour}小时` });
  }
  return options;
}

function getTaskDialogSelectedDates() {
  const selectedDate = String(elements.taskDialogDateSelect.value || "").trim();
  if (!selectedDate) {
    return [];
  }
  const options = getTaskDialogBootstrap()?.dateOptions || [];
  if (!elements.taskDialogContinuousCheckbox.checked) {
    return [selectedDate];
  }
  const startIndex = options.findIndex((option) => String(option.value) === selectedDate);
  if (startIndex < 0) {
    return [selectedDate];
  }
  return options.slice(startIndex).map((option) => String(option.value));
}

function getTaskDialogSelectableSeats() {
  const currentQueryResult = getTaskDialogCurrentQueryResult();
  const roomCandidates = Array.isArray(currentQueryResult?.rooms) && currentQueryResult.rooms.length
    ? currentQueryResult.rooms
    : currentQueryResult?.seatMap
      ? [currentQueryResult.seatMap]
      : [];
  const selectedRoomId = String(state.taskDialogSelectedRoomId || "").trim();
  const seatMap =
    roomCandidates.find((room) => String(room.roomId || room.roomName || "").trim() === selectedRoomId) ||
    currentQueryResult?.seatMap ||
    roomCandidates[0] ||
    null;
  if (!seatMap?.seats) {
    return [];
  }
  return seatMap.seats.filter((seat) => seat.selectable);
}

function buildTaskDialogSeatRangeLabel(selectableSeats) {
  const payload = buildTaskDialogSearchPayload();
  const currentQueryResult = getTaskDialogCurrentQueryResult();
  if (!payload) {
    return "请选择账号、日期、开始时间和使用时长后，再查询座位号范围";
  }
  if (state.taskDialogQueryBusy) {
    return "正在按当前条件查询座位号范围";
  }
  if (state.taskDialogQueryError) {
    return "当前查询失败，请修正条件后点“刷新查询”重试";
  }
  if (!currentQueryResult) {
    if (state.taskDialogQueryResult) {
      return "当前条件已变更，请点“刷新查询”更新座位号范围";
    }
    return "打开弹窗后会自动查询当前条件，也可以点“刷新查询”更新座位号范围";
  }
  if (!selectableSeats.length) {
    return "当前查询结果里还没有可预约座位，也可以直接输入座位号";
  }
  if (selectableSeats.length === 1) {
    return `当前可输入座位号：${selectableSeats[0].seatNumber} 号`;
  }
  return `当前可输入座位号范围：${selectableSeats[0].seatNumber} 号到 ${
    selectableSeats[selectableSeats.length - 1].seatNumber
  } 号`;
}

function getTaskDialogSeatContext() {
  const inputSeatNumber = String(elements.taskDialogSeatNumberInput.value || "").trim();
  const selectableSeats = [...getTaskDialogSelectableSeats()].sort(compareSeatNumber);
  const currentQueryResult = getTaskDialogCurrentQueryResult();
  const roomCandidates = Array.isArray(currentQueryResult?.rooms) && currentQueryResult.rooms.length
    ? currentQueryResult.rooms
    : currentQueryResult?.seatMap
      ? [currentQueryResult.seatMap]
      : [];
  const selectedRoomId = String(state.taskDialogSelectedRoomId || "").trim();
  const currentSeatMap =
    roomCandidates.find((room) => String(room.roomId || room.roomName || "").trim() === selectedRoomId) ||
    findRoomByName(currentQueryResult, state.taskDialogSelectedRoomName) ||
    currentQueryResult?.seatMap ||
    roomCandidates[0] ||
    null;
  const querySeats = [...(currentSeatMap?.seats || [])].sort(compareSeatNumber);
  const rangeLabel = buildTaskDialogSeatRangeLabel(selectableSeats);
  if (inputSeatNumber) {
    const matchedSeat =
      querySeats.find((seat) => String(seat.seatNumber).trim() === inputSeatNumber) || null;
    return {
      rangeLabel,
      inputSeatNumber,
      seat: matchedSeat,
      invalidMessage: currentQueryResult && !matchedSeat ? `当前查询结果里没有 ${inputSeatNumber} 号座位。` : "",
    };
  }

  return {
    rangeLabel,
    inputSeatNumber: "",
    seat: selectableSeats.find((seat) => state.selectedSeatIds.includes(seat.seatId)) || null,
    invalidMessage: "",
  };
}

function updateTaskDialogPreview() {
  const currentQueryResult = getTaskDialogCurrentQueryResult();
  const currentSeatMap = getTaskDialogCurrentSeatMap();
  const seatContext = getTaskDialogSeatContext();
  const selectedSeat = seatContext.seat;
  const selectedDates = getTaskDialogSelectedDates();
  const actionLabels = getTaskDialogEnabledActions().map((action) => getTaskActionLabel(action));
  const dialogAccountName = elements.taskDialogAccountSelect.value || getAccountName();
  const selectedSeatFallback = getSelectedSeats()[0];
  const reserveWindowLabel =
    elements.taskDialogDurationSelect.value && elements.taskDialogStartHourSelect.value
      ? `${elements.taskDialogStartHourSelect.value}:00 - ${
          Number(elements.taskDialogStartHourSelect.value) + Number(elements.taskDialogDurationSelect.value)
        }:00`
      : "--";

  elements.taskDialogSeatNumberHint.textContent = selectedSeatFallback
    ? `${seatContext.rangeLabel}，也可以留空继续使用当前已选座位。`
    : `${seatContext.rangeLabel}。`;

  if (selectedSeat && currentSeatMap) {
    elements.taskDialogSeatSummary.textContent =
      `${currentSeatMap.roomName || currentQueryResult?.roomName || "当前自习室"} ${selectedSeat.seatNumber} 号 · 预约时段 ${reserveWindowLabel}`;
  } else if (seatContext.inputSeatNumber) {
    elements.taskDialogSeatSummary.textContent =
      `当前输入 ${seatContext.inputSeatNumber} 号座位 · 预约时段 ${reserveWindowLabel}`;
  } else {
    elements.taskDialogSeatSummary.textContent =
      `${seatContext.rangeLabel}；如果不想回到选座页，也可以直接在这里输入座位号。`;
  }

  if (!actionLabels.length) {
    elements.taskDialogPlanSummary.textContent = "请至少勾选一个自动任务动作。";
  } else if (!selectedDates.length) {
    elements.taskDialogPlanSummary.textContent = "请选择预约日期后，再生成自动计划。";
  } else {
    const dateLabel =
      selectedDates.length > 1
        ? `${selectedDates[0]} 起滚动维护 ${selectedDates.length} 天`
        : `${selectedDates[0]} 当天`;
    elements.taskDialogPlanSummary.textContent =
      `${dialogAccountName} · ${dateLabel} · ${actionLabels.join("、")}${
        elements.taskDialogReserveEnabled.checked ? ` · 预约时段 ${reserveWindowLabel}` : ""
      }${
        selectedSeat || seatContext.inputSeatNumber
          ? ` · ${seatContext.inputSeatNumber || selectedSeat?.seatNumber} 号座位`
          : ""
      }。`;
  }

  if (elements.taskDialogReserveEnabled.checked && seatContext.invalidMessage) {
    elements.taskDialogHint.textContent = seatContext.invalidMessage;
    return;
  }
  if (elements.taskDialogReserveEnabled.checked && !selectedSeat && !seatContext.inputSeatNumber) {
    elements.taskDialogHint.textContent =
      "自动预约需要座位号。你可以先在选座页选中座位，也可以直接在弹窗里输入座位号。";
    return;
  }
  if (hasTaskDialogAutoAdjustedTime()) {
    elements.taskDialogHint.textContent =
      "今天已经过去的执行时间会自动顺延到最近可执行时间，避免计划因为时间已过而失效。";
    return;
  }
  elements.taskDialogHint.textContent =
    "当天预约开始时间会按当前小时开始并持续到 22:00；隔天和后续日期使用 8:00 - 22:00；自动签到会在检测到当前已可签到时立即补签，签退放在 21:59。";
}

function getTaskDialogEnabledActions() {
  const actions = [];
  if (elements.taskDialogReserveEnabled.checked) {
    actions.push("reserve");
  }
  if (elements.taskDialogCheckinEnabled.checked) {
    actions.push("checkin");
  }
  if (elements.taskDialogCheckoutEnabled.checked) {
    actions.push("checkout");
  }
  return actions;
}

function buildTaskDialogPlanPayload() {
  const accountName = elements.taskDialogAccountSelect.value || getAccountName();
  const seatContext = getTaskDialogSeatContext();
  const currentQueryResult =
    typeof getTaskDialogCurrentQueryResult === "function" ? getTaskDialogCurrentQueryResult() : null;
  const roomCandidates = Array.isArray(currentQueryResult?.rooms) && currentQueryResult.rooms.length
    ? currentQueryResult.rooms
    : currentQueryResult?.seatMap
      ? [currentQueryResult.seatMap]
      : [];
  const selectedRoomId = String(state.taskDialogSelectedRoomId || "").trim();
  const currentSeatMap =
    roomCandidates.find((room) => String(room.roomId || room.roomName || "").trim() === selectedRoomId) ||
    findRoomByName(currentQueryResult, state.taskDialogSelectedRoomName) ||
    currentQueryResult?.seatMap ||
    roomCandidates[0] ||
    null;
  return {
    planId: state.taskDialogEditingPlanId || "",
    accountName,
    seatUrl: state.taskDialogSeatUrl || elements.seatUrlSelect.value,
    selectedRoomId: currentSeatMap?.roomId || state.taskDialogSelectedRoomId || "",
    selectedRoomName: currentSeatMap?.roomName || state.taskDialogSelectedRoomName || "",
    selectedDate: elements.taskDialogDateSelect.value,
    startHour: Number(elements.taskDialogStartHourSelect.value || 0),
    durationHours: Number(elements.taskDialogDurationSelect.value || 0),
    seatNumber: seatContext.inputSeatNumber || String(seatContext.seat?.seatNumber || ""),
    reserveEnabled: elements.taskDialogReserveEnabled.checked,
    checkinEnabled: elements.taskDialogCheckinEnabled.checked,
    checkoutEnabled: elements.taskDialogCheckoutEnabled.checked,
    continuousReserve: elements.taskDialogContinuousCheckbox.checked,
    reserveTime: elements.taskDialogReserveTimeInput.value,
    checkinTime: elements.taskDialogCheckinTimeInput.value,
    checkoutTime: elements.taskDialogCheckoutTimeInput.value,
    reserveCheckIntervalMinutes: 30,
  };
}

function getTaskDialogValidationMessage() {
  const accountName = elements.taskDialogAccountSelect.value || "";
  if (!accountName) {
    return "请选择任务账号。";
  }

  if (!getTaskDialogEnabledActions().length) {
    return "请至少勾选一个任务动作。";
  }

  if (!elements.taskDialogDateSelect.value) {
    return "请选择预约日期。";
  }

  if (elements.taskDialogReserveEnabled.checked) {
    const seatContext = getTaskDialogSeatContext();
    if (seatContext.invalidMessage) {
      return seatContext.invalidMessage;
    }
    const seatNumber = seatContext.inputSeatNumber || String(seatContext.seat?.seatNumber || "");
    if (!seatNumber) {
      return "请先输入座位号，或先在选座页选中 1 个座位。";
    }
    if (!/^\d+$/.test(seatNumber)) {
      return "座位号只能填写数字。";
    }
    if (!elements.taskDialogDurationSelect.value) {
      return "请选择使用时长。";
    }
  }

  if (elements.taskDialogReserveEnabled.checked && !elements.taskDialogReserveTimeInput.value) {
    return "请填写自动预约执行时间。";
  }
  if (elements.taskDialogCheckinEnabled.checked && !elements.taskDialogCheckinTimeInput.value) {
    return "请填写自动签到时间。";
  }
  if (elements.taskDialogCheckoutEnabled.checked && !elements.taskDialogCheckoutTimeInput.value) {
    return "请填写自动签退时间。";
  }

  return "";
}

function buildTaskDialogRunAt(dateValue, timeValue) {
  const [rawHour = "00", rawMinute = "00"] = String(timeValue || "").split(":");
  return `${dateValue}T${padNumber(rawHour)}:${padNumber(rawMinute)}`;
}

function buildTaskDialogScheduledRunAt(dateValue, timeValue) {
  const rawRunAt = buildTaskDialogRunAt(dateValue, timeValue);
  const runAtDate = new Date(rawRunAt);
  if (Number.isNaN(runAtDate.getTime()) || runAtDate.getTime() > Date.now()) {
    return rawRunAt;
  }

  const now = new Date();
  if (!isSameLocalDate(runAtDate, now)) {
    return rawRunAt;
  }

  const nextDate = new Date(now.getTime() + 60 * 1000);
  nextDate.setSeconds(0, 0);
  return buildTaskDialogRunAt(
    dateValue,
    `${padNumber(nextDate.getHours())}:${padNumber(nextDate.getMinutes())}`,
  );
}

function isSameLocalDate(leftDate, rightDate) {
  return (
    leftDate.getFullYear() === rightDate.getFullYear() &&
    leftDate.getMonth() === rightDate.getMonth() &&
    leftDate.getDate() === rightDate.getDate()
  );
}

function resolveTaskDialogDefaultStartHour(dateValue, startHour, fallbackStartHour) {
  const now = new Date();
  const targetDate = new Date(`${dateValue}T00:00`);
  if (Number.isNaN(targetDate.getTime()) || !isSameLocalDate(targetDate, now)) {
    return startHour;
  }

  const dialogBootstrap = getTaskDialogBootstrap();
  const currentHour = now.getHours();
  const minStartHour = Number(dialogBootstrap?.constraints?.minBeginTime || fallbackStartHour || 8);
  const maxStartHour =
    Number(dialogBootstrap?.constraints?.maxEndTime || 22) -
    Number(dialogBootstrap?.constraints?.minDuration || 1);

  if (maxStartHour < minStartHour) {
    return startHour;
  }
  return Math.min(Math.max(startHour, minStartHour, currentHour), maxStartHour);
}

function hasTaskDialogAutoAdjustedTime() {
  const selectedDate = String(elements.taskDialogDateSelect.value || "").trim();
  if (!selectedDate) {
    return false;
  }

  const enabledTimeValues = [];
  if (elements.taskDialogReserveEnabled.checked) {
    enabledTimeValues.push(elements.taskDialogReserveTimeInput.value);
  }
  if (elements.taskDialogCheckinEnabled.checked) {
    enabledTimeValues.push(elements.taskDialogCheckinTimeInput.value);
  }
  if (elements.taskDialogCheckoutEnabled.checked) {
    enabledTimeValues.push(elements.taskDialogCheckoutTimeInput.value);
  }

  return enabledTimeValues.some(
    (timeValue) => buildTaskDialogScheduledRunAt(selectedDate, timeValue) !== buildTaskDialogRunAt(selectedDate, timeValue),
  );
}

function getTaskActionLabel(action) {
  return (
    {
      reserve: "自动预约",
      checkin: "自动签到",
      checkout: "自动签退",
    }[action] || action
  );
}

function getCurrentSeatMap() {
  if (!state.queryResult) {
    return null;
  }

  const selectedRoomId = resolveRoomSelectionValue(state.queryResult, state.selectedRoomId);
  const selectedRoom =
    findRoomByValue(state.queryResult, selectedRoomId) ||
    state.queryResult.seatMap ||
    getQueryRooms(state.queryResult)[0] ||
    null;
  if (!selectedRoom) {
    return null;
  }

  state.selectedRoomId = buildRoomOptionValue(selectedRoom);
  return selectedRoom;
}

function buildCurrentQuerySummaryText() {
  const seatMap = getCurrentSeatMap();
  if (!state.queryResult || !seatMap) {
    return state.queryResult?.summary || "";
  }

  const query = state.queryResult.query || {};
  const accountPrefix = query.accountName ? `${query.accountName} · ` : "";
  if (!query.date || !query.startHour || !query.durationHours || !query.peopleCount) {
    return state.queryResult.summary || seatMap.roomName || "";
  }
  return `${accountPrefix}${seatMap.roomName || "当前自习室"} · ${query.date} · ${query.startHour}:00 开始 · ${query.durationHours} 小时 · ${query.peopleCount} 人`;
}

function renderRoomOptions() {
  if (!elements.roomSelect) {
    return;
  }

  const options = buildRoomSelectOptions(state.queryResult);
  const selectedRoomId = resolveRoomSelectionValue(state.queryResult, state.selectedRoomId);
  fillSelect(elements.roomSelect, options, selectedRoomId);
  elements.roomSelect.disabled = options.length <= 1;
  state.selectedRoomId = selectedRoomId;
}

function renderQueryResult() {
  if (!state.queryResult) {
    clearResultArea();
    return;
  }

  const seatMap = getCurrentSeatMap();
  if (!seatMap) {
    clearResultArea();
    return;
  }

  renderRoomOptions();
  elements.roomName.textContent = seatMap.roomName || "当前阅览室";
  elements.querySummary.textContent = buildCurrentQuerySummaryText();
  elements.availableCount.textContent = `可选 ${seatMap.availableCount}`;
  elements.lockedCount.textContent = `不可选 ${seatMap.lockedCount}`;
  elements.recommendedSeatSummary.textContent = seatMap.systemRecommendedSeatNumber
    ? `系统推荐：${seatMap.systemRecommendedSeatNumber} 号座位`
    : "当前没有推荐座位。";
  renderMap();
  renderSeatList();
  renderSelectionArea();
  updateSidebarSummary();
  updateTaskDialogPreview();
  addDebugLog("success", "座位查询已更新", state.queryResult.summary, {
    roomName: seatMap.roomName,
    availableCount: seatMap.availableCount,
    lockedCount: seatMap.lockedCount,
    selectedSeatNumber: seatMap.selectedSeatNumber,
    recommendedSeatNumber: seatMap.systemRecommendedSeatNumber,
  });
  renderDebugPanel();
}

function clearResultArea() {
  state.selectedRoomId = "";
  elements.roomName.textContent = "还没有查询座位";
  elements.querySummary.textContent = "请先查询可选座位。";
  elements.availableCount.textContent = "可选 0";
  elements.lockedCount.textContent = "不可选 0";
  elements.recommendedSeatSummary.textContent = "系统推荐座位会在查询后显示。";
  fillSelect(elements.roomSelect, [], "");
  elements.roomSelect.disabled = true;
  elements.mapStage.style.display = "none";
  elements.seatLayer.innerHTML = "";
  elements.mapEmptyTip.textContent = state.bootstrap?.message || "查询后会显示座位图。";
  elements.seatList.innerHTML = "";
  renderSelectionArea();
  updateSidebarSummary();
  updateTaskDialogPreview();
  renderDebugPanel();
}

function renderMap() {
  const seatMap = getCurrentSeatMap();
  if (!seatMap) {
    return;
  }

  const width = Number(seatMap.width) * PIXELS_PER_UNIT;
  const height = Number(seatMap.height) * PIXELS_PER_UNIT;
  elements.mapStage.style.display = "block";
  elements.mapStage.style.width = `${width}px`;
  elements.mapStage.style.height = `${height}px`;
  elements.mapStage.style.transform = `scale(${state.zoom})`;
  elements.mapEmptyTip.textContent = "可直接点图，也可用座位号选择。";
  elements.planImage.src = seatMap.planUrl || "";
  elements.planImage.style.display = seatMap.planUrl ? "block" : "none";
  elements.planImage.alt = `${seatMap.roomName || "阅览室"}座位平面图`;
  elements.seatLayer.innerHTML = "";

  seatMap.seats.forEach((seat) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = buildSeatButtonClass(seat);
    button.style.left = `${Number(seat.x) * PIXELS_PER_UNIT}px`;
    button.style.top = `${Number(seat.y) * PIXELS_PER_UNIT}px`;
    button.style.width = `${Math.max(Number(seat.w) * PIXELS_PER_UNIT, 18)}px`;
    button.style.height = `${Math.max(Number(seat.h) * PIXELS_PER_UNIT, 18)}px`;
    button.textContent = seat.seatNumber;
    button.title = buildSeatTitle(seat);
    if (!seat.selectable) {
      button.disabled = true;
    }
    button.addEventListener("click", () => {
      toggleSeat(seat.seatId);
    });
    elements.seatLayer.appendChild(button);
  });
}

function renderSeatList() {
  const seatMap = getCurrentSeatMap();
  if (!seatMap) {
    elements.seatList.innerHTML = "";
    return;
  }

  const keyword = elements.seatFilterInput.value.trim();
  const seats = [...seatMap.seats]
    .sort(compareSeatNumber)
    .filter((seat) => !keyword || String(seat.seatNumber).includes(keyword));

  elements.seatList.innerHTML = "";
  if (seats.length === 0) {
    const empty = document.createElement("p");
    empty.className = "panel-subtitle";
    empty.textContent = "没有匹配的座位号。";
    elements.seatList.appendChild(empty);
    return;
  }

  seats.forEach((seat) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `seat-list-button${seat.selectable ? "" : " is-locked"}${
      state.selectedSeatIds.includes(seat.seatId) ? " is-selected" : ""
    }`;
    button.disabled = !seat.selectable;
    button.innerHTML = `
      <strong>${seat.seatNumber} 号</strong>
      <small>${buildSeatMeta(seat)}</small>
    `;
    button.addEventListener("click", () => {
      toggleSeat(seat.seatId);
    });
    elements.seatList.appendChild(button);
  });
}

function renderTaskList() {
  elements.taskList.innerHTML = "";
  if (elements.taskUploadButton) {
    elements.taskUploadButton.disabled = !state.tasks.length;
    elements.taskUploadButton.title = state.tasks.length ? "" : "没有可上传的自动任务";
  }
  if (!state.tasks.length) {
    const empty = document.createElement("p");
    empty.className = "panel-subtitle";
    empty.textContent = "还没有自动任务计划。";
    elements.taskList.appendChild(empty);
    return;
  }

  const groups = buildTaskAccountGroups(state.tasks);
  groups.forEach((group) => {
    const card = document.createElement("article");
    card.className = "task-overview-card";

    const head = document.createElement("div");
    head.className = "task-account-head";

    const summaryCopy = document.createElement("div");
    summaryCopy.className = "task-account-copy";

    const summaryTitle = document.createElement("strong");
    summaryTitle.className = "task-account-name";
    summaryTitle.textContent = getSelectedAccountLabel(group.accountName) || group.accountName;

    const summaryMeta = document.createElement("p");
    summaryMeta.className = "task-account-meta";
    summaryMeta.textContent = buildTaskGroupMeta(group.plan);

    summaryCopy.append(summaryTitle, summaryMeta);

    const enabledCount = document.createElement("span");
    enabledCount.className = "task-state-badge state-active";
    enabledCount.textContent = `已启用 ${getEnabledPlanActionCount(group.plan)} 项`;

    head.append(summaryCopy, enabledCount);

    const preview = document.createElement("div");
    preview.className = "task-account-preview";
    buildTaskGroupPreviewLabels(group.plan).forEach((item) => {
      preview.appendChild(createMiniTag(item.text, item.className));
    });

    const roomSummary = document.createElement("p");
    roomSummary.className = "task-message";
    roomSummary.textContent = `${group.plan.roomName || "未指定自习室"} · ${
      group.plan.seatNumber ? `${group.plan.seatNumber} 号座位` : "未设置座位号"
    } · ${buildReserveWindowLabel(group.plan.startHour, group.plan.durationHours)}`;

    const actionBar = document.createElement("div");
    actionBar.className = "task-item-actions task-account-actions";
    actionBar.append(
      createTaskActionButton("编辑计划", async (event) => {
        event.preventDefault();
        await openTaskDialog(group.plan);
      }),
      createTaskActionButton(
        "删除计划",
        async (event, button) => {
          event.preventDefault();
          await deleteTask(
            group.plan.planId,
            button,
            buildTaskDeleteSummary(group.plan),
            group.plan.accountName,
          );
        },
        "task-delete-button",
      ),
      createTaskActionButton("更多", async (event) => {
        event.preventDefault();
        openAutomationPlanDetails(group.plan);
      }),
    );
    card.append(head, preview, roomSummary, actionBar);
    elements.taskList.appendChild(card);
  });
  renderDebugPanel();
}

function buildTaskAccountGroups(tasks) {
  return [...tasks]
    .map((plan) => ({
      accountName: String(plan.accountName || "未命名账号").trim() || "未命名账号",
      plan,
    }))
    .sort((leftGroup, rightGroup) => compareAccountsForDisplay(leftGroup.accountName, rightGroup.accountName));
}

function compareAccountsForDisplay(leftAccountName, rightAccountName) {
  const leftIndex = state.accounts.findIndex((account) => account.name === leftAccountName);
  const rightIndex = state.accounts.findIndex((account) => account.name === rightAccountName);
  if (leftIndex >= 0 && rightIndex >= 0) {
    return leftIndex - rightIndex;
  }
  if (leftIndex >= 0) {
    return -1;
  }
  if (rightIndex >= 0) {
    return 1;
  }
  return String(leftAccountName).localeCompare(String(rightAccountName), "zh-CN");
}

function buildTaskGroupMeta(plan) {
  const enabledActions = getEnabledPlanActionCount(plan);
  const textParts = [
    `已启用 ${enabledActions} 项自动任务`,
    plan.roomName || "未指定自习室",
    plan.seatNumber ? `${plan.seatNumber} 号座位` : "未设置座位号",
    `预约时段 ${buildReserveWindowLabel(plan.startHour, plan.durationHours)}`,
  ];
  if (plan.updatedAt) {
    textParts.push(`最近更新 ${formatDateTime(plan.updatedAt)}`);
  }
  return textParts.join(" · ");
}

function buildTaskGroupPreviewLabels(plan) {
  const reserveSlotLabels = buildReservePreviewSlotLabels(plan);
  if (reserveSlotLabels.length) {
    return reserveSlotLabels;
  }
  return AUTOMATION_ACTIONS.map((action) => {
    const actionPlan = plan[action] || {};
    return {
      className: `task-preview-tag ${actionPlan.enabled ? "is-active" : "is-disabled"}`,
      text: `${getTaskActionLabel(action)} · ${actionPlan.enabled ? "已开启" : "未开启"}`,
    };
  });
}

function buildReservePreviewSlotLabels(plan) {
  const reservePlan = plan.reserve || {};
  if (!reservePlan.enabled || !Array.isArray(reservePlan.previewSlots)) {
    return [];
  }
  return reservePlan.previewSlots.map((slot) => {
    const booked = isReservePreviewSlotBooked(plan, slot);
    return {
      className: `task-preview-tag task-reserve-slot ${booked ? "status-success" : "status-pending"}`,
      text: `${slot.label || slot.date || "预约"} ${compactReserveWindowLabel(slot.windowLabel || "")} · ${
        booked ? "已预约" : "未预约"
      }`,
    };
  });
}

function isReservePreviewSlotBooked(plan, slot) {
  const dateValue = String(slot?.date || "").trim();
  if (!dateValue) {
    return Boolean(slot?.booked);
  }
  const actualBooking = findActualReserveBooking(plan, dateValue);
  if (actualBooking) {
    return true;
  }
  if (hasReadyTaskStatusForReservePreview(plan)) {
    return false;
  }
  const reservePlan = plan.reserve || {};
  const storedBookedDates = Array.isArray(reservePlan.bookedDates) ? reservePlan.bookedDates : [];
  if (slot?.booked || storedBookedDates.some((value) => String(value || "").trim() === dateValue)) {
    return true;
  }
  return false;
}

function findActualReserveBooking(plan, dateValue) {
  const targetAccountName = String(plan?.accountName || "").trim();
  if (!targetAccountName) {
    return null;
  }
  const status = state.taskStatuses.find(
    (entry) => String(entry?.accountName || "").trim() === targetAccountName,
  );
  const bookings = Array.isArray(status?.bookings) ? status.bookings : [];
  return (
    bookings.find((booking) => {
      const bookingDate = String(booking?.startAt || booking?.startAtLabel || "").slice(0, 10);
      const statusValue = String(booking?.status || "").trim();
      return bookingDate === dateValue && ["0", "1", "2", "8"].includes(statusValue);
    }) || null
  );
}

function hasReadyTaskStatusForReservePreview(plan) {
  const targetAccountName = String(plan?.accountName || "").trim();
  if (!targetAccountName) {
    return false;
  }
  const status = getTaskStatusByAccountName(targetAccountName);
  return Boolean(
    status &&
      status.dataReady !== false &&
      status.state !== "error" &&
      status.loginStateReady !== false &&
      Array.isArray(status.bookings),
  );
}

function compactReserveWindowLabel(label) {
  return String(label || "").replace(/\s*-\s*/g, "-");
}

function buildAutomationActionItem(action, plan) {
  const actionPlan = plan[action] || {};
  const facts =
    action === "reserve"
      ? buildReserveActionFacts(plan, actionPlan)
      : buildDailyActionFacts(action, actionPlan);

  if (actionPlan.nextRunAt) {
    facts.push(`下次执行 ${formatDateTime(actionPlan.nextRunAt)}`);
  }
  if (actionPlan.lastRunAt) {
    facts.push(`最近执行 ${formatDateTime(actionPlan.lastRunAt)}`);
  }

  return {
    title: getTaskActionLabel(action),
    enabled: Boolean(actionPlan.enabled),
    message: actionPlan.lastMessage || getAutomationActionFallbackMessage(action, actionPlan.enabled),
    facts,
  };
}

function createTaskListItem(task) {
  const item = document.createElement("article");
  item.className = `task-item automation-task-item${task.enabled ? " is-enabled" : " is-disabled"}`;

  const head = document.createElement("div");
  head.className = "task-item-head";

  const copy = document.createElement("div");
  copy.className = "task-item-copy";

  const title = document.createElement("strong");
  title.textContent = task.title;

  const facts = document.createElement("div");
  facts.className = "task-item-facts";
  task.facts.forEach((fact) => {
    facts.appendChild(createMiniTag(fact));
  });

  copy.append(title, facts);

  const status = document.createElement("span");
  status.className = `task-status ${task.enabled ? "success" : "failed"}`;
  status.textContent = task.enabled ? "已开启" : "未开启";

  head.append(copy, status);

  const message = document.createElement("p");
  message.className = "task-message";
  message.textContent = task.message;

  item.append(head, message);
  return item;
}

function buildTaskDeleteSummary(plan) {
  const enabledLabels = getTaskDialogEnabledActionLabelsFromPlan(plan);
  return `${plan.accountName} · ${enabledLabels.join("、")} · ${plan.roomName || "未指定自习室"} · ${plan.seatNumber || "未设置座位号"}`;
}

function getEnabledPlanActionCount(plan) {
  return AUTOMATION_ACTIONS.filter((action) => plan[action]?.enabled).length;
}

function getTaskDialogEnabledActionLabelsFromPlan(plan) {
  return AUTOMATION_ACTIONS
    .filter((action) => plan[action]?.enabled)
    .map((action) => getTaskActionLabel(action));
}

function buildReserveActionFacts(plan, actionPlan) {
  const facts = [
    `巡检时间 ${actionPlan.time || "--"}`,
    `每 ${actionPlan.intervalMinutes || 30} 分钟检查一次`,
    `预约时段 ${actionPlan.windowLabel || buildReserveWindowLabel(plan.startHour, plan.durationHours)}`,
  ];
  if (actionPlan.targetDates?.length) {
    facts.push(`目标日期 ${actionPlan.targetDates.join("、")}`);
  } else {
    facts.push(
      plan.continuousReserve
        ? `从 ${plan.selectedDate} 起补订后续日期（后续日期按学校默认开馆时段）`
        : `只维护 ${plan.selectedDate} 这一天`,
    );
  }
  if (plan.continuousReserve) {
    facts.push("续订提示：只有 selected_date 当天按计划时段，后续日期改用学校默认时段");
  }
  if (actionPlan.bookedDates?.length) {
    facts.push(`已预约 ${actionPlan.bookedDates.join("、")}`);
  }
  return facts;
}

function buildDailyActionFacts(action, actionPlan) {
  return [
    `执行时间 ${actionPlan.time || "--"}`,
    `${action === "checkin" ? "未来 3 天签到" : "未来 3 天签退"} ${
      formatPreviewRuns(actionPlan.previewRuns || [])
    }`,
  ];
}

function createTaskActionButton(label, onClick, extraClassName = "") {
  const button = document.createElement("button");
  button.type = "button";
  button.className = extraClassName ? `ghost-button ${extraClassName}` : "ghost-button";
  button.textContent = label;
  button.addEventListener("click", async (event) => {
    await onClick(event, button);
  });
  return button;
}

function getAutomationActionFallbackMessage(action, enabled) {
  if (!enabled) {
    return "当前未启用。";
  }
  if (action === "reserve") {
    return "会按计划持续检测预约状态，发现缺失预约后自动补订。";
  }
  if (action === "checkin") {
    return "会按设置时间优先尝试签到，服务启动后和运行期间也会每 30 分钟补查一次签到状态。";
  }
  return "会在设置的时间自动尝试签退。";
}

function buildReserveWindowLabel(startHour, durationHours) {
  const endHour = Number(startHour) + Number(durationHours);
  return `${startHour}:00 - ${endHour}:00`;
}

function formatPreviewRuns(values) {
  if (!values.length) {
    return "--";
  }
  return values.map((value) => formatTaskCompactTime(value)).join("、");
}

function createMiniTag(text, className = "") {
  const tag = document.createElement("span");
  tag.className = className ? `mini-tag ${className}` : "mini-tag";
  tag.textContent = text;
  return tag;
}

function buildTaskStatusPlaceholder(account, loading = false) {
  const profile = account || null;
  const loginStateReady = Boolean(profile?.loginStateReady);
  if (!loginStateReady) {
    return {
      accountName: profile?.name || "",
      state: "missing-login",
      summary: "未保存登录态",
      detail: "请先到账号管理里刷新认证。",
      loginStateReady: false,
      pendingTaskCount: 0,
      runningTaskCount: 0,
      bookings: [],
      dataReady: false,
    };
  }
  return {
    accountName: profile?.name || "",
    state: loading ? "loading" : "empty",
    summary: loading ? "正在刷新当前状态" : "等待刷新当前状态",
    detail: loading ? "这个账号正在单独读取最新预约状态。" : "每天 08:30 会定时获取，也可以手动刷新这张卡。",
    loginStateReady: true,
    pendingTaskCount: 0,
    runningTaskCount: 0,
    bookings: [],
    dataReady: false,
  };
}

function buildTaskStatusErrorState(accountName, error) {
  const account = getAccountProfile(accountName);
  const previousStatus = getTaskStatusByAccountName(accountName);
  return {
    accountName,
    state: "error",
    summary: "状态检测失败",
    detail: error?.message || "读取状态失败",
    loginStateReady: account?.loginStateReady ?? previousStatus?.loginStateReady ?? false,
    pendingTaskCount: previousStatus?.pendingTaskCount || 0,
    runningTaskCount: previousStatus?.runningTaskCount || 0,
    bookings: previousStatus?.bookings || [],
    dataReady: true,
  };
}

function getTaskStatusByAccountName(accountName) {
  return state.taskStatuses.find((status) => status.accountName === accountName) || null;
}

function getTaskStatusCardState(account) {
  const existingStatus = getTaskStatusByAccountName(account.name);
  if (existingStatus) {
    return existingStatus;
  }
  return buildTaskStatusPlaceholder(account, Boolean(state.taskStatusLoadingAccounts[account.name]));
}

function getTaskStatusActionTargets(status) {
  const bookings = Array.isArray(status?.bookings) ? status.bookings : [];
  return {
    checkinBooking:
      bookings.find((booking) => String(booking.status || "").trim() === "0") || null,
    checkoutBooking:
      bookings.find((booking) => ["1", "2"].includes(String(booking.status || "").trim())) || null,
    cancelBooking:
      bookings.find((booking) => ["0", "8"].includes(String(booking.status || "").trim())) || null,
  };
}

function buildTaskStatusActionConfigs(status, actionTargets) {
  if (status?.dataReady === false) {
    const waitingLabel = status.loginStateReady ? "等待刷新" : "需登录";
    return [
      {
        label: waitingLabel,
        action: "checkin",
        booking: null,
        enabled: false,
        tone: "neutral",
        statusMode: false,
      },
      {
        label: waitingLabel,
        action: "checkout",
        booking: null,
        enabled: false,
        tone: "neutral",
        statusMode: false,
      },
      {
        label: waitingLabel,
        action: "cancel",
        booking: null,
        enabled: false,
        tone: "neutral",
        statusMode: false,
      },
    ];
  }

  const loginReady = Boolean(status?.loginStateReady);
  const activeBooking = actionTargets.checkoutBooking;
  const reservedBooking = actionTargets.cancelBooking;
  const checkinBooking = actionTargets.checkinBooking;
  const currentBooking = reservedBooking || checkinBooking || activeBooking || null;

  const checkinConfig = activeBooking
    ? {
        label: "已签到",
        action: "checkin",
        booking: activeBooking,
        enabled: false,
        tone: "success",
        statusMode: true,
      }
    : checkinBooking
      ? {
          label: checkinBooking.checkinWindowOpen ? "立即签到" : "待签到",
          action: "checkin",
          booking: checkinBooking,
          enabled: loginReady && Boolean(checkinBooking.checkinWindowOpen),
          tone: checkinBooking.checkinWindowOpen ? "default" : "neutral",
          statusMode: false,
        }
      : {
          label: loginReady ? "未签到" : "需登录",
          action: "checkin",
          booking: null,
          enabled: false,
          tone: "neutral",
          statusMode: false,
        };

  const checkoutConfig = activeBooking
    ? {
        label: "立即签退",
        action: "checkout",
        booking: activeBooking,
        enabled: loginReady,
        tone: "warning",
        statusMode: false,
      }
    : {
        label: loginReady ? "未在馆" : "需登录",
        action: "checkout",
        booking: null,
        enabled: false,
        tone: "neutral",
        statusMode: false,
      };

  const cancelConfig = reservedBooking
    ? {
        label: "取消预约",
        action: "cancel",
        booking: reservedBooking,
        enabled: loginReady,
        tone: "warning",
        statusMode: false,
      }
    : null;

  // 该账号是否已经存在自动任务计划——已存在就把入口标记成"已创建"，仅作状态展示，
  // 不让用户在状态卡里反复新建（编辑请去"自动任务"页）。
  const hasExistingPlan = Boolean(findLatestPlanForAccount(status?.accountName || ""));
  const createPlanConfig = hasExistingPlan
    ? {
        label: "已创建",
        action: "open-task-dialog",
        booking: null,
        enabled: false,
        tone: "success",
        statusMode: true,
      }
    : {
        label: loginReady ? "新建自动任务" : "需登录",
        action: loginReady ? "open-task-dialog" : "cancel",
        booking: currentBooking,
        enabled: loginReady,
        tone: loginReady ? "default" : "neutral",
        statusMode: false,
      };

  return cancelConfig
    ? [checkinConfig, checkoutConfig, cancelConfig, createPlanConfig]
    : [checkinConfig, checkoutConfig, createPlanConfig];
}

function buildTaskStatusActionButton({
  label,
  action,
  accountName,
  booking = null,
  enabled = false,
  tone = "default",
  statusMode = false,
}) {
  const bookingLabel = booking
    ? `${booking.roomName} ${booking.seatNumber} 号${booking.startAtLabel ? ` · ${booking.startAtLabel}` : ""}`
    : "";
  const classNames = ["ghost-button", "task-status-action-button"];
  if (tone && tone !== "default") {
    classNames.push(`is-${tone}`);
  }
  if (statusMode) {
    classNames.push("is-status");
  }
  return `
    <button
      class="${classNames.join(" ")}"
      type="button"
      data-status-action="${escapeHtml(action)}"
      data-account-name="${escapeHtml(accountName)}"
      ${booking?.bookingId ? `data-booking-id="${escapeHtml(booking.bookingId)}"` : ""}
      ${booking?.roomName ? `data-room-name="${escapeHtml(booking.roomName)}"` : ""}
      ${booking?.seatNumber ? `data-seat-number="${escapeHtml(booking.seatNumber)}"` : ""}
      ${bookingLabel ? `data-booking-label="${escapeHtml(bookingLabel)}"` : ""}
      ${enabled ? "" : "disabled"}
    >${escapeHtml(label)}</button>
  `;
}

function buildTaskStatusActionHint(status, actionTargets) {
  if (status?.dataReady === false) {
    return status.loginStateReady
      ? "这张卡还在读取当前账号状态，稍后会自动更新。"
      : "这个账号还没有登录态，请先到账号管理里刷新认证。";
  }
  if (!status.loginStateReady) {
    return "这个账号还没有登录态，请先到账号管理里刷新认证。";
  }

  const hints = [];
  if (actionTargets.checkinBooking) {
    hints.push(
      actionTargets.checkinBooking.checkinWindowOpen
        ? `当前可签到：${actionTargets.checkinBooking.roomName} ${actionTargets.checkinBooking.seatNumber} 号`
        : `待签到：${actionTargets.checkinBooking.roomName} ${actionTargets.checkinBooking.seatNumber} 号`,
    );
  }
  if (actionTargets.checkoutBooking) {
    hints.push(`当前已签到，可签退：${actionTargets.checkoutBooking.roomName} ${actionTargets.checkoutBooking.seatNumber} 号`);
  }
  if (actionTargets.cancelBooking) {
    hints.push(`当前已预约，可点“取消预约”：${actionTargets.cancelBooking.roomName} ${actionTargets.cancelBooking.seatNumber} 号`);
  } else if (status.loginStateReady) {
    hints.push("当前未预约，可点“新建自动任务”为这个账号补一个自动计划。");
  }
  if (!hints.length) {
    return "当前没有可手动操作的预约记录。";
  }
  return hints.join("；");
}

function buildTaskStatusBookingSummaryText(bookings) {
  const bookingCount = Array.isArray(bookings) ? bookings.filter(Boolean).length : 0;
  return bookingCount ? `共 ${bookingCount} 条预约记录` : "没有更多预约记录";
}

function shouldShowBookingInSeatDisplay(booking) {
  const status = String(booking?.status || "").trim();
  return ["0", "1", "2", "8"].includes(status) || Boolean(booking?.checkinWindowOpen);
}

function getSeatDisplayBookingState(booking) {
  const status = String(booking?.status || "").trim();
  if (["1", "2"].includes(status)) {
    return "active";
  }
  if (booking?.checkinWindowOpen) {
    return "checkin-ready";
  }
  if (["0", "8"].includes(status)) {
    return "reserved";
  }
  return "finished";
}

function formatSeatDisplaySeatLabel(seatNumber) {
  const normalizedSeatNumber = String(seatNumber || "").trim();
  if (!normalizedSeatNumber) {
    return "未知座位";
  }
  if (normalizedSeatNumber.endsWith("号") || normalizedSeatNumber.includes("座位")) {
    return normalizedSeatNumber;
  }
  return `${normalizedSeatNumber} 号`;
}

function compareSeatDisplayItems(leftItem, rightItem) {
  const roomCompare = String(leftItem.roomName).localeCompare(String(rightItem.roomName), "zh-CN", {
    numeric: true,
    sensitivity: "base",
  });
  if (roomCompare !== 0) {
    return roomCompare;
  }
  const seatCompare = compareSeatNumber(leftItem, rightItem);
  if (seatCompare !== 0) {
    return seatCompare;
  }
  return compareAccountsForDisplay(leftItem.accountName, rightItem.accountName);
}

function compareSeatDisplayTimeSlots(leftSlot, rightSlot) {
  const leftTimestamp = Number(leftSlot.startTimestamp || 0);
  const rightTimestamp = Number(rightSlot.startTimestamp || 0);
  if (leftTimestamp && rightTimestamp && leftTimestamp !== rightTimestamp) {
    return leftTimestamp - rightTimestamp;
  }
  const timeCompare = String(leftSlot.startAt || leftSlot.startAtLabel || "").localeCompare(
    String(rightSlot.startAt || rightSlot.startAtLabel || ""),
    "zh-CN",
    { numeric: true, sensitivity: "base" },
  );
  if (timeCompare !== 0) {
    return timeCompare;
  }
  const accountCompare = String(leftSlot.accountLabel || leftSlot.accountName || "").localeCompare(
    String(rightSlot.accountLabel || rightSlot.accountName || ""),
    "zh-CN",
    { numeric: true, sensitivity: "base" },
  );
  if (accountCompare !== 0) {
    return accountCompare;
  }
  return String(leftSlot.statusLabel || "").localeCompare(String(rightSlot.statusLabel || ""), "zh-CN", {
    numeric: true,
    sensitivity: "base",
  });
}

function buildSeatDisplayTimeSlotLabel(slot, includeAccountLabel = false) {
  const parts = [slot.startAtLabel || slot.startAt || "暂无开始时间"];
  if (slot.statusLabel) {
    parts.push(slot.statusLabel);
  }
  if (slot.checkinWindowOpen) {
    parts.push("当前可签到");
  }
  if (includeAccountLabel && slot.accountLabel) {
    parts.push(slot.accountLabel);
  }
  return parts.join(" · ");
}

function parseSeatDisplaySlotStartDate(slot) {
  const timestamp = Number(slot.startTimestamp || 0);
  if (timestamp > 0) {
    return new Date(timestamp * 1000);
  }
  const value = String(slot.startAt || slot.startAtLabel || "").trim();
  if (!value) {
    return null;
  }
  const parsed = new Date(value);
  if (!Number.isNaN(parsed.getTime())) {
    return parsed;
  }
  const labelMatch = value.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})/);
  if (!labelMatch) {
    return null;
  }
  return new Date(
    Number(labelMatch[1]),
    Number(labelMatch[2]) - 1,
    Number(labelMatch[3]),
    Number(labelMatch[4]),
    Number(labelMatch[5]),
  );
}

function formatSeatDisplayDateLabel(date, referenceDate = new Date()) {
  const currentYear = referenceDate.getFullYear();
  const monthDay = `${date.getMonth() + 1}月${date.getDate()}日`;
  return date.getFullYear() === currentYear ? monthDay : `${date.getFullYear()}年${monthDay}`;
}

function buildSeatDisplaySlotDayLabel(slot, referenceDate = new Date()) {
  const startDate = parseSeatDisplaySlotStartDate(slot);
  if (!startDate) {
    return "日期待定";
  }
  const startDay = new Date(startDate.getFullYear(), startDate.getMonth(), startDate.getDate());
  const referenceDay = new Date(
    referenceDate.getFullYear(),
    referenceDate.getMonth(),
    referenceDate.getDate(),
  );
  const diffDays = Math.round((startDay.getTime() - referenceDay.getTime()) / 86400000);
  if (diffDays === 0) {
    return "今天";
  }
  if (diffDays === 1) {
    return "明天";
  }
  if (diffDays === 2) {
    return "后天";
  }
  return formatSeatDisplayDateLabel(startDate, referenceDate);
}

function formatSeatDisplayClock(date) {
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

function extractSeatDisplayClockLabel(value) {
  const match = String(value || "").match(/(\d{1,2}):(\d{2})/);
  if (!match) {
    return "";
  }
  return `${String(Number(match[1])).padStart(2, "0")}:${match[2]}`;
}

function buildSeatDisplaySlotTimeRangeLabel(slot) {
  const startDate = parseSeatDisplaySlotStartDate(slot);
  const durationSeconds = Number(slot.durationSeconds || slot.duration || 0);
  if (startDate && durationSeconds > 0) {
    const endDate = new Date(startDate.getTime() + durationSeconds * 1000);
    return `${formatSeatDisplayClock(startDate)}-${formatSeatDisplayClock(endDate)}`;
  }
  const startLabel = extractSeatDisplayClockLabel(slot.startAtLabel || slot.startAt || "");
  const endLabel = extractSeatDisplayClockLabel(slot.endAtLabel || slot.endAt || "");
  if (startLabel && endLabel) {
    return `${startLabel}-${endLabel}`;
  }
  return startLabel || slot.startAtLabel || slot.startAt || "暂无时间";
}

function buildSeatDisplaySlotTagLabels(slot, includeAccountLabel = false) {
  const labels = [];
  if (slot.statusLabel) {
    labels.push(slot.statusLabel);
  }
  if (slot.checkinWindowOpen) {
    labels.push("可签到");
  }
  if (includeAccountLabel && slot.accountLabel) {
    labels.push(slot.accountLabel);
  }
  return labels;
}

function buildSeatDisplayItems() {
  return state.accounts
    .flatMap((account) => {
      const status = getTaskStatusCardState(account);
      const bookings = Array.isArray(status?.bookings) ? status.bookings : [];
      return bookings.filter(shouldShowBookingInSeatDisplay).map((booking) => {
        const roomName = String(booking.roomName || "").trim() || "未知自习室";
        const seatNumber = String(booking.seatNumber || "").trim() || "未知座位";
        const stateValue = getSeatDisplayBookingState(booking);
        return {
          accountName: account.name,
          accountLabel: getSelectedAccountLabel(account.name) || account.name,
          roomName,
          seatNumber,
          seatLabel: formatSeatDisplaySeatLabel(seatNumber),
          statusLabel: booking.statusLabel || getTaskStateLabel(stateValue),
          startAt: booking.startAt || "",
          startAtLabel: booking.startAtLabel || "",
          endAt: booking.endAt || "",
          endAtLabel: booking.endAtLabel || "",
          durationSeconds: Number(booking.durationSeconds || 0),
          startTimestamp: Number(booking.startTimestamp || 0),
          checkinWindowOpen: Boolean(booking.checkinWindowOpen),
          state: stateValue,
        };
      });
    })
    .sort(compareSeatDisplayItems);
}

function buildSeatDisplayRoomGroups(items) {
  const groups = [];
  const groupMap = new Map();
  const seatItemMap = new Map();
  const statePriority = {
    active: 0,
    "checkin-ready": 1,
    reserved: 2,
    finished: 3,
  };
  const getStatePriority = (stateValue) =>
    Object.prototype.hasOwnProperty.call(statePriority, stateValue) ? statePriority[stateValue] : 99;

  items.forEach((item) => {
    const roomName = item.roomName || "未知自习室";
    if (!groupMap.has(roomName)) {
      const group = { roomName, items: [] };
      groupMap.set(roomName, group);
      groups.push(group);
    }
    const rawSeatKey = String(item.seatNumber || item.seatLabel || "").trim();
    const seatKey =
      rawSeatKey || `unknown:${item.accountName || ""}:${item.startAt || item.startAtLabel || ""}`;
    const itemMapKey = `${roomName}\u0000${seatKey}`;
    let seatItem = seatItemMap.get(itemMapKey);
    if (!seatItem) {
      const seatNumber = rawSeatKey || "未知座位";
      seatItem = {
        ...item,
        roomName,
        seatNumber,
        seatLabel: item.seatLabel || formatSeatDisplaySeatLabel(seatNumber),
        accountName: "",
        accountLabel: "",
        detailAccounts: [],
        timeSlots: [],
        checkinWindowOpen: false,
      };
      seatItemMap.set(itemMapKey, seatItem);
      groupMap.get(roomName).items.push(seatItem);
    }

    const accountName = item.accountName || "";
    const accountLabel = item.accountLabel || accountName;
    if (accountName && !seatItem.detailAccounts.some((account) => account.accountName === accountName)) {
      seatItem.detailAccounts.push({ accountName, accountLabel });
    }

    const slot = {
      accountName,
      accountLabel,
      statusLabel: item.statusLabel || "",
      startAt: item.startAt || "",
      startAtLabel: item.startAtLabel || "",
      endAt: item.endAt || "",
      endAtLabel: item.endAtLabel || "",
      durationSeconds: Number(item.durationSeconds || 0),
      startTimestamp: Number(item.startTimestamp || 0),
      checkinWindowOpen: Boolean(item.checkinWindowOpen),
      state: item.state || "",
    };
    seatItem.timeSlots.push(slot);
    seatItem.checkinWindowOpen = seatItem.checkinWindowOpen || slot.checkinWindowOpen;
    if (getStatePriority(slot.state) < getStatePriority(seatItem.state)) {
      seatItem.state = slot.state;
      seatItem.statusLabel = slot.statusLabel || seatItem.statusLabel;
    }
    if (!seatItem.statusLabel && slot.statusLabel) {
      seatItem.statusLabel = slot.statusLabel;
    }
  });
  groups.forEach((group) => {
    group.items.forEach((item) => {
      item.timeSlots.sort(compareSeatDisplayTimeSlots);
      item.accountNames = item.detailAccounts.map((account) => account.accountName);
      item.accountLabels = item.detailAccounts.map((account) => account.accountLabel);
      item.accountName = item.accountNames[0] || item.accountName || "";
      item.accountLabel = item.accountLabels.join("、") || item.accountName || "未知账号";
      item.startAtLabel = item.timeSlots[0]?.startAtLabel || "";
    });
  });
  return groups;
}

function buildSeatDisplayRoomSeatSummary(items) {
  const seatLabels = items.map((item) => item.seatLabel).filter(Boolean);
  const visibleLabels = seatLabels.slice(0, 4).join("、");
  return seatLabels.length > 4 ? `${visibleLabels} 等 ${seatLabels.length} 个座位` : visibleLabels;
}

function buildSeatDisplayDetailButtons(item) {
  const detailAccounts = Array.isArray(item.detailAccounts) ? item.detailAccounts : [];
  return detailAccounts
    .map((account) => {
      const buttonLabel = detailAccounts.length > 1 ? `${account.accountLabel}详情` : "查看详情";
      return `
        <button
          class="ghost-button seat-display-detail-button"
          type="button"
          data-seat-display-detail-account="${escapeHtml(account.accountName)}"
        >${escapeHtml(buttonLabel)}</button>
      `;
    })
    .join("");
}

function getSeatDisplayUnreadyAccountCount() {
  return state.accounts.filter((account) => {
    const status = getTaskStatusByAccountName(account.name);
    return !status || status.dataReady === false || status.state === "loading";
  }).length;
}

function getSeatDisplayErrorAccountCount() {
  return state.accounts.filter((account) => getTaskStatusByAccountName(account.name)?.state === "error").length;
}

function buildSeatDisplayStatusMeta() {
  const parts = [
    state.taskStatusLoadedAt
      ? `最近刷新：${formatDateTime(state.taskStatusLoadedAt)}`
      : "还未刷新账号状态",
  ];
  const loadingCount = Object.keys(state.taskStatusLoadingAccounts).length;
  if (loadingCount) {
    parts.push(`正在刷新 ${loadingCount} 个账号`);
  }
  const unreadyCount = getSeatDisplayUnreadyAccountCount();
  if (unreadyCount && !loadingCount) {
    parts.push(`${unreadyCount} 个账号待刷新`);
  }
  const errorCount = getSeatDisplayErrorAccountCount();
  if (errorCount) {
    parts.push(`${errorCount} 个账号检测失败`);
  }
  return parts.join("，");
}

function buildSeatDisplayEmptyText() {
  if (!state.accounts.length) {
    return "当前还没有账号，新增账号后再查看预约位置。";
  }
  const loadingCount = Object.keys(state.taskStatusLoadingAccounts).length;
  if (loadingCount) {
    return "正在刷新账号预约位置。";
  }
  if (getSeatDisplayUnreadyAccountCount() === state.accounts.length) {
    return "还未获取账号状态，点击“刷新预约位置”后再查看。";
  }
  if (getSeatDisplayErrorAccountCount()) {
    return "暂时没有读到可展示的预约位置；部分账号检测失败，可到账号状态页查看详情。";
  }
  return "当前没有正在预约、待签到或在馆的座位。";
}

function renderSeatDisplay() {
  elements.seatDisplayList.innerHTML = "";
  if (!state.accounts.length) {
    elements.seatDisplaySummary.textContent = "没有账号时这里会保持为空。";
    const empty = document.createElement("p");
    empty.className = "panel-subtitle";
    empty.textContent = buildSeatDisplayEmptyText();
    elements.seatDisplayList.appendChild(empty);
    return;
  }

  const items = buildSeatDisplayItems();
  const groups = buildSeatDisplayRoomGroups(items);
  const meta = buildSeatDisplayStatusMeta();
  const seatCount = groups.reduce((total, group) => total + group.items.length, 0);
  elements.seatDisplaySummary.textContent = groups.length
    ? `当前 ${seatCount} 个预约位置，分布在 ${groups.length} 个自习室；${meta}`
    : `没有可展示的预约位置；${meta}`;

  if (!groups.length) {
    const empty = document.createElement("p");
    empty.className = "panel-subtitle";
    empty.textContent = buildSeatDisplayEmptyText();
    elements.seatDisplayList.appendChild(empty);
    return;
  }

  groups.forEach((group) => {
    const roomCard = document.createElement("article");
    roomCard.className = "seat-display-room";
    const seats = group.items
      .map((item) => {
        const includeAccountLabel = (item.detailAccounts || []).length > 1;
        const timeSlots = (item.timeSlots || [])
          .map((slot) => {
            const tagLabels = buildSeatDisplaySlotTagLabels(slot, includeAccountLabel)
              .map(
                (label) =>
                  `<span class="seat-display-slot-tag">${escapeHtml(label)}</span>`,
              )
              .join("");
            return `
              <div class="seat-display-time-slot state-${escapeHtml(slot.state || item.state || "")}">
                <span class="seat-display-slot-day">${escapeHtml(buildSeatDisplaySlotDayLabel(slot))}</span>
                <strong class="seat-display-slot-time">${escapeHtml(buildSeatDisplaySlotTimeRangeLabel(slot))}</strong>
                <span class="seat-display-slot-tags">${tagLabels}</span>
              </div>
            `;
          })
          .join("");
        return `
          <article class="seat-display-seat-card state-${escapeHtml(item.state)}">
            <div class="seat-display-seat-head">
              <div class="seat-display-seat-title">
                <span class="seat-display-chair-icon" aria-hidden="true"></span>
                <div>
                  <strong>${escapeHtml(item.seatLabel)}</strong>
                  <p>${escapeHtml(item.accountLabel)}</p>
                </div>
              </div>
              <span class="task-state-badge state-${escapeHtml(item.state)}">${escapeHtml(item.statusLabel)}</span>
            </div>
            <div class="seat-display-seat-meta" aria-label="预约时间段">
              ${
                timeSlots ||
                '<div class="seat-display-time-slot is-empty"><span class="seat-display-slot-day">日期待定</span><strong class="seat-display-slot-time">暂无时间</strong></div>'
              }
            </div>
            ${buildSeatDisplayDetailButtons(item)}
          </article>
        `;
      })
      .join("");
    roomCard.innerHTML = `
      <div class="seat-display-room-head">
        <div>
          <h3>${escapeHtml(group.roomName)}</h3>
          <p>${group.items.length} 个预约座位</p>
        </div>
        <span class="mini-tag">${escapeHtml(buildSeatDisplayRoomSeatSummary(group.items))}</span>
      </div>
      <div class="seat-display-seat-grid">${seats}</div>
    `;
    elements.seatDisplayList.appendChild(roomCard);
  });
}

function buildTaskStatusMoreButton(accountName) {
  return `
    <button
      class="ghost-button task-status-more-button"
      type="button"
      data-status-detail-account="${escapeHtml(accountName)}"
    >更多</button>
  `;
}

function renderTaskStatusList() {
  elements.taskStatusList.innerHTML = "";
  if (!state.accounts.length) {
    const empty = document.createElement("p");
    empty.className = "panel-subtitle";
    empty.textContent = "当前还没有账号，新增账号后再检测状态。";
    elements.taskStatusList.appendChild(empty);
    elements.taskStatusSummary.textContent = "没有账号时这里会保持为空。";
    renderSeatDisplay();
    return;
  }

  const loadingCount = Object.keys(state.taskStatusLoadingAccounts).length;
  elements.taskStatusSummary.textContent = loadingCount
    ? `正在逐个刷新 ${loadingCount} 个账号的状态${state.taskStatusLoadedAt ? `，上次完成时间：${formatDateTime(state.taskStatusLoadedAt)}` : ""}`
    : state.taskStatusLoadedAt
      ? `最近检测时间：${formatDateTime(state.taskStatusLoadedAt)}`
      : "账号会先直接显示在这里，每天 08:30 或手动获取后再更新状态。";

  state.accounts.forEach((account) => {
    const status = getTaskStatusCardState(account);
    const actionTargets = getTaskStatusActionTargets(status);
    const item = document.createElement("article");
    const accountLabel = getSelectedAccountLabel(status.accountName) || status.accountName;
    const isLoading = Boolean(state.taskStatusLoadingAccounts[account.name]);
    item.className = `task-status-card task-status-tile${
      isLoading ? " is-loading" : ""
    }`;
    const actionButtons = buildTaskStatusActionConfigs(status, actionTargets)
      .map((config) =>
        buildTaskStatusActionButton({
          accountName: status.accountName,
          ...config,
        }),
      )
      .join("");
    item.innerHTML = `
      <div class="task-status-card-head">
        <div>
          <strong>${escapeHtml(accountLabel)}</strong>
          <p class="task-status-summary">${escapeHtml(status.summary || "暂无状态")}</p>
        </div>
        <div class="task-status-card-tools">
          <button
            class="task-status-refresh-button"
            type="button"
            data-status-refresh-account="${escapeHtml(status.accountName)}"
            aria-label="刷新${escapeHtml(accountLabel)}的账号状态"
            title="刷新账号状态"
            ${isLoading ? "disabled" : ""}
          ><span aria-hidden="true">↻</span></button>
          <span class="task-state-badge state-${status.state || "empty"}">${getTaskStateLabel(status.state)}</span>
        </div>
      </div>
      <p class="task-status-detail">${escapeHtml(status.detail || "暂无更多详情。")}</p>
      <div class="task-status-facts">
        <span class="mini-tag">待执行 ${Number(status.pendingTaskCount || 0)}</span>
        <span class="mini-tag">执行中 ${Number(status.runningTaskCount || 0)}</span>
        <span class="mini-tag">${status.loginStateReady ? "已保存登录态" : "未保存登录态"}</span>
      </div>
      <div class="task-status-actions">${actionButtons}${buildTaskStatusMoreButton(status.accountName)}</div>
    `;
    elements.taskStatusList.appendChild(item);
  });
  renderSeatDisplay();
}

function renderAccountList() {
  elements.accountList.innerHTML = "";
  if (!state.accounts.length) {
    const empty = document.createElement("p");
    empty.className = "panel-subtitle";
    empty.textContent = "没有账号。右上角点“新建账号”就能补上。";
    elements.accountList.appendChild(empty);
    syncAccountBulkActions();
    return;
  }

  state.accounts.forEach((account) => {
    const isBulkSelected = state.selectedAccountNames.includes(account.name);
    const card = document.createElement("article");
    card.className = [
      "account-card",
      account.name === state.accountEditorSelectedName ? "is-active" : "",
      state.accountBulkSelectionMode ? "is-bulk-selectable" : "",
      isBulkSelected ? "is-selected" : "",
    ].filter(Boolean).join(" ");
    card.dataset.accountCard = account.name;
    if (state.accountBulkSelectionMode) {
      card.tabIndex = 0;
      card.setAttribute("role", "checkbox");
      card.setAttribute("aria-checked", isBulkSelected ? "true" : "false");
    }

    const head = document.createElement("div");
    head.className = "account-card-head";

    const titleWrap = document.createElement("div");
    const title = document.createElement("h3");
    title.className = "account-card-title";
    title.textContent = getAccountDisplayName(account);
    const meta = document.createElement("p");
    meta.className = "account-card-meta";
    meta.textContent = `${account.passwordConfigured ? "已保存登录密码" : "密码默认等于学号"} · ${
      account.isDefault ? "默认账号" : "普通账号"
    }`;
    titleWrap.append(title, meta);

    const status = document.createElement("span");
    status.className = `status-badge${account.loginStateReady ? " is-ready" : " is-missing"}`;
    status.textContent = account.loginStateReady ? "已保存登录态" : "未保存登录态";
    head.append(titleWrap, status);

    const infoRow = document.createElement("div");
    infoRow.className = "task-status-facts";
    const taskStatus = getTaskStatusCardState(account);
    infoRow.append(
      createMiniTag(`入口 ${Number(account.seatUrlCount || 0)} 个`),
      createMiniTag(`偏好座位 ${Number(account.preferredSeatCount || 0)} 个`),
      createMiniTag(`当前状态 ${getTaskStateLabel(taskStatus.state)}`),
    );

    const path = document.createElement("p");
    path.className = "account-card-path";
    path.textContent = `登录态文件：${extractFileName(account.stateFile)}`;

    const actions = document.createElement("div");
    actions.className = "card-actions";
    actions.append(
      buildAccountActionButton("编辑", "edit", account.name),
      buildAccountActionButton("切换", "switch", account.name),
    );
    if (!account.isDefault) {
      actions.append(buildAccountActionButton("设默认", "default", account.name));
    } else {
      const badge = document.createElement("span");
      badge.className = "mini-tag";
      badge.textContent = "默认账号";
      actions.append(badge);
    }

    card.append(head, infoRow, path, actions);
    elements.accountList.appendChild(card);
  });
  syncAccountBulkActions();
}

function renderSelectionArea() {
  const peopleCount = Math.max(getPeopleCount(), 1);
  const selectedSeats = getSelectedSeats();

  elements.selectionSummary.textContent = `已选 ${selectedSeats.length}/${peopleCount}`;
  elements.selectedSeatTags.innerHTML = "";

  if (!selectedSeats.length) {
    const empty = document.createElement("span");
    empty.className = "panel-subtitle";
    empty.textContent = peopleCount === 1 ? "还没有选座。" : `还需要选择 ${peopleCount} 个座位。`;
    elements.selectedSeatTags.appendChild(empty);
  } else {
    selectedSeats.forEach((seat) => {
      const tag = document.createElement("span");
      tag.className = "selected-seat-tag";
      tag.textContent = `${seat.seatNumber} 号${seat.hasSocket ? " · 插座" : ""}`;
      elements.selectedSeatTags.appendChild(tag);
    });
  }

  updateReserveButtonState();
  renderDebugPanel();
}

function updateReserveButtonState() {
  if (!state.queryResult) {
    elements.reserveButton.disabled = true;
    elements.reserveHint.textContent = "查询到座位后再提交。";
    return;
  }

  if (!isQueryResultCurrent()) {
    elements.reserveButton.disabled = true;
    elements.reserveHint.textContent = "预约条件已变更，请重新查询座位。";
    return;
  }

  if (!state.queryResult.supportsDirectReserve || getPeopleCount() !== 1) {
    elements.reserveButton.disabled = true;
    elements.reserveHint.textContent =
      state.queryResult.reservationHint || "当前条件暂不支持直接提交预约。";
    return;
  }

  if (state.selectedSeatIds.length !== 1) {
    elements.reserveButton.disabled = true;
    elements.reserveHint.textContent = "请选择 1 个座位后提交。";
    return;
  }

  const selectedSeat = getSelectedSeats()[0];
  const isBusy = elements.reserveButton.dataset.busy === "true";
  elements.reserveButton.disabled = isBusy;
  elements.reserveHint.textContent = selectedSeat
    ? `将提交 ${selectedSeat.seatNumber} 号座位。`
    : "请选择 1 个座位后提交。";
}

function toggleSeat(seatId) {
  const seat = findSeatById(seatId);
  if (!seat || !seat.selectable) {
    return;
  }

  const maxSelection = Math.max(getPeopleCount(), 1);
  const isSelected = state.selectedSeatIds.includes(seatId);

  if (isSelected) {
    state.selectedSeatIds = state.selectedSeatIds.filter((item) => item !== seatId);
  } else if (maxSelection === 1) {
    state.selectedSeatIds = [seatId];
  } else if (state.selectedSeatIds.length >= maxSelection) {
    showToast(`当前人数最多选择 ${maxSelection} 个座位。`);
    return;
  } else {
    state.selectedSeatIds = [...state.selectedSeatIds, seatId];
  }

  renderMap();
  renderSeatList();
  renderSelectionArea();
  updateSidebarSummary();
}

function trimSelectedSeats() {
  const seatMap = getCurrentSeatMap();
  if (!seatMap) {
    state.selectedSeatIds = [];
    return;
  }

  const validSeatIds = new Set(seatMap.seats.filter((seat) => seat.selectable).map((seat) => seat.seatId));
  const maxSelection = Math.max(getPeopleCount(), 1);
  state.selectedSeatIds = state.selectedSeatIds
    .filter((seatId) => validSeatIds.has(seatId))
    .slice(0, maxSelection);
}

function getSelectedSeats() {
  return state.selectedSeatIds.map((seatId) => findSeatById(seatId)).filter(Boolean);
}

function findSeatById(seatId) {
  const seatMap = getCurrentSeatMap();
  if (!seatMap) {
    return null;
  }
  return seatMap.seats.find((seat) => seat.seatId === seatId) || null;
}

function isQueryResultCurrent() {
  if (!state.queryResult) {
    return false;
  }

  const query = state.queryResult.query || {};
  const filters = collectFilters();
  return (
    String(query.accountName || "") === String(filters.accountName || "") &&
    String(query.seatUrl || "") === String(filters.seatUrl || "") &&
    String(query.date || "") === String(filters.date || "") &&
    Number(query.startHour) === Number(filters.startHour) &&
    Number(query.durationHours) === Number(filters.durationHours) &&
    Number(query.peopleCount) === Number(filters.peopleCount)
  );
}

function fillSelect(selectElement, options, selectedValue = "") {
  const normalizedOptions = Array.isArray(options) ? options : [];
  selectElement.innerHTML = "";
  selectElement.disabled = normalizedOptions.length === 0;

  normalizedOptions.forEach((option) => {
    const optionElement = document.createElement("option");
    if (typeof option === "object" && option !== null) {
      optionElement.value = String(option.value ?? "");
      optionElement.textContent = String(option.label ?? option.value ?? "");
      if (String(option.selected) === "true") {
        optionElement.selected = true;
      }
    } else {
      optionElement.value = String(option);
      optionElement.textContent = String(option);
    }
    selectElement.appendChild(optionElement);
  });

  if (!normalizedOptions.length) {
    return;
  }

  const nextValue = selectedValue === undefined || selectedValue === null ? "" : String(selectedValue);
  if ([...selectElement.options].some((option) => option.value === nextValue)) {
    selectElement.value = nextValue;
    return;
  }

  const selectedOption = [...selectElement.options].find((option) => option.selected);
  if (selectedOption) {
    selectElement.value = selectedOption.value;
    return;
  }

  selectElement.selectedIndex = 0;
}

function setZoom(nextZoom) {
  const clampedZoom = Math.min(Math.max(Number(nextZoom) || 1, 0.4), 2.4);
  state.zoom = Math.round(clampedZoom * 10) / 10;
  elements.zoomValue.textContent = `${Math.round(state.zoom * 100)}%`;
  elements.mapStage.style.transform = `scale(${state.zoom})`;
}

function compareSeatNumber(leftSeat, rightSeat) {
  return String(leftSeat.seatNumber).localeCompare(String(rightSeat.seatNumber), "zh-CN", {
    numeric: true,
    sensitivity: "base",
  });
}

function buildSeatButtonClass(seat) {
  const classNames = ["seat-pin"];
  if (!seat.selectable) {
    classNames.push("is-locked");
  }
  if (seat.recommended) {
    classNames.push("is-recommended");
  }
  if (seat.hasSocket) {
    classNames.push("has-socket");
  }
  if (state.selectedSeatIds.includes(seat.seatId)) {
    classNames.push("is-selected");
  }
  return classNames.join(" ");
}

function buildSeatTitle(seat) {
  const parts = [`${seat.seatNumber} 号座位`, seat.selectable ? "可选" : "不可选"];
  if (seat.recommended) {
    parts.push("系统推荐");
  }
  if (seat.hasSocket) {
    parts.push("带插座");
  }
  if (state.selectedSeatIds.includes(seat.seatId)) {
    parts.push("已选中");
  }
  return parts.join(" · ");
}

function buildSeatMeta(seat) {
  const parts = [seat.selectable ? "可选" : "不可选"];
  if (seat.recommended) {
    parts.push("推荐");
  }
  if (seat.hasSocket) {
    parts.push("插座");
  }
  return parts.join(" · ");
}

function updateTaskCreateHint() {
  if (!state.accounts.length) {
    elements.taskCreateHint.textContent = "当前还没有账号，请先到账号管理里新建一个账号。";
    return;
  }

  if (!state.queryResult || !isQueryResultCurrent()) {
    elements.taskCreateHint.textContent =
      "打开弹窗后会按当前账号自动查询可选座位；切换账号时也会重新查询，不想回选座页时可直接输入座位号。";
    return;
  }

  if (getPeopleCount() !== 1) {
    elements.taskCreateHint.textContent = "自动任务里的自动预约会按单人条件重新查询；如需换条件，可在弹窗里点“刷新查询”。";
    return;
  }

  if (state.selectedSeatIds.length !== 1) {
    elements.taskCreateHint.textContent =
      "打开弹窗后会自动带入当前账号的查询结果；你也可以在弹窗里手动刷新或直接输入座位号。";
    return;
  }

  const selectedSeat = getSelectedSeats()[0];
  elements.taskCreateHint.textContent = selectedSeat
    ? `当前已选 ${selectedSeat.seatNumber} 号座位，打开弹窗会直接带入；也可以在弹窗里刷新查询或改成别的座位号。`
    : "打开弹窗后会自动查询当前账号座位，也可以直接输入座位号。";
}

function getTaskStatusLabel(status) {
  return (
    {
      pending: "待执行",
      running: "执行中",
      success: "已完成",
      failed: "失败",
    }[status] || status
  );
}

function getTaskStateLabel(stateValue) {
  return (
    {
      loading: "刷新中",
      active: "在馆中",
      "checkin-ready": "可签到",
      reserved: "已预约",
      finished: "最近结束",
      empty: "暂无记录",
      error: "检测失败",
      "missing-login": "未登录",
    }[stateValue] || "未知状态"
  );
}

function formatDateTime(value) {
  if (!value) {
    return "--";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value).replace("T", " ");
  }

  return `${date.getFullYear()}-${padNumber(date.getMonth() + 1)}-${padNumber(date.getDate())} ${padNumber(
    date.getHours(),
  )}:${padNumber(date.getMinutes())}`;
}

function formatTaskCompactTime(value) {
  if (!value) {
    return "--";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value).replace("T", " ");
  }

  return `${padNumber(date.getMonth() + 1)}-${padNumber(date.getDate())} ${padNumber(
    date.getHours(),
  )}:${padNumber(date.getMinutes())}`;
}

function padNumber(value) {
  return String(value).padStart(2, "0");
}

function getPeopleCount() {
  return Math.max(Number(elements.peopleCountSelect.value) || 1, 1);
}

function getAccountName() {
  return (
    elements.accountSelect.value ||
    state.bootstrap?.selectedAccountName ||
    state.accounts.find((account) => account.isSelected)?.name ||
    ""
  );
}

function getSelectedAccountLabel(accountName = "") {
  const profile = getAccountProfile(accountName);
  if (profile) {
    return getAccountDisplayName(profile);
  }

  const bootstrapOptions = Array.isArray(state.bootstrap?.accounts) ? state.bootstrap.accounts : [];
  const matchedOption = bootstrapOptions.find((option) => String(option.value || "") === String(accountName || ""));
  if (matchedOption) {
    return String(matchedOption.label || "").replace(/（默认）$/, "");
  }

  return String(accountName || "").trim();
}

function switchView(targetView, options = {}) {
  const { syncUrl = true } = options;
  const requestedView = targetView || "reserve";
  const viewName = elements.menuButtons.some((button) => button.dataset.viewTarget === requestedView)
    ? requestedView
    : "reserve";
  elements.menuButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.viewTarget === viewName);
  });
  elements.viewPanels.forEach((panel) => {
    panel.classList.toggle("is-active", panel.dataset.viewPanel === viewName);
  });
  if (syncUrl) {
    updateViewQuery(viewName);
  }
  refreshTaskStatusesForTasksView(viewName);
  renderDebugPanel();
}

function getActiveViewName() {
  return elements.viewPanels.find((panel) => panel.classList.contains("is-active"))?.dataset.viewPanel || "reserve";
}

function refreshTaskStatusesForTasksView(viewName) {
  if (
    !["tasks", "seat-display"].includes(viewName) ||
    !state.accounts.length ||
    Object.keys(state.taskStatusLoadingAccounts).length
  ) {
    return;
  }
  void loadTaskStatuses(true);
}

function readRequestedView() {
  const requestedView = new URLSearchParams(window.location.search).get("view");
  return requestedView || "reserve";
}

function updateViewQuery(viewName) {
  const url = new URL(window.location.href);
  if (viewName === "reserve") {
    url.searchParams.delete("view");
  } else {
    url.searchParams.set("view", viewName);
  }
  window.history.replaceState({}, "", url);
}

function updateSidebarSummary() {
  if (state.queryResult) {
    if (!isQueryResultCurrent()) {
      elements.sidebarSummary.textContent = "预约条件已变更，请重新查询座位。";
      return;
    }
    const selectedSeatText = state.selectedSeatIds.length
      ? ` · 已选 ${getSelectedSeats()
          .map((seat) => `${seat.seatNumber}号`)
          .join("、")}`
      : "";
    elements.sidebarSummary.textContent = `${buildCurrentQuerySummaryText()}${selectedSeatText}`;
    return;
  }

  if (state.bootstrap?.message) {
    elements.sidebarSummary.textContent = state.bootstrap.message;
    return;
  }

  elements.sidebarSummary.textContent = "未查询座位";
}

function selectAccountEditor(accountName) {
  const account = getAccountProfile(accountName);
  if (!account) {
    resetAccountEditor();
    return;
  }

  state.accountEditorOriginalName = account.name;
  state.accountEditorSelectedName = account.name;
  elements.accountDialogEyebrow.textContent = "账号管理";
  elements.accountEditorTitle.textContent = getAccountDisplayName(account);
  elements.accountDialogHint.textContent = "请确认学号后再刷新认证或删除。删除前还会再确认一次。";
  elements.accountStudentIdInput.value = account.studentId || account.name;
  elements.accountPasswordInput.value = "";
  setAccountEditorActionState({
    canRefresh: true,
    canDelete: true,
    authHint: buildAccountAuthHint(account),
    formHint: buildAccountFormHint(account),
  });
  renderAccountList();
}

function resetAccountEditor() {
  state.accountEditorOriginalName = "";
  state.accountEditorSelectedName = "";
  elements.accountDialogEyebrow.textContent = "账号管理";
  elements.accountEditorTitle.textContent = "新建账号";
  elements.accountDialogHint.textContent = "只需要填写学号和密码，其他学校配置会自动补齐。";
  elements.accountStudentIdInput.value = "";
  elements.accountPasswordInput.value = "";
  setAccountEditorActionState({
    canRefresh: true,
    canDelete: false,
    authHint: "填好学号和密码后，直接点击“刷新认证”，程序会自动保存账号并完成登录。",
    formHint: "只需要填写学号和密码。密码留空时默认等于学号，点击“刷新认证”时也会先保存当前账号。",
  });
  renderAccountList();
}

function getAccountProfile(accountName) {
  return state.accounts.find((account) => account.name === accountName) || null;
}

function getAccountDisplayName(account) {
  const name = String(account?.name || "").trim();
  const studentId = String(account?.studentId || "").trim();
  if (name && studentId && name !== studentId) {
    return `${name} · ${studentId}`;
  }
  return studentId || name;
}

function buildAccountAuthHint(account) {
  if (account.loginStateReady) {
    return "需要重新登录时，点击“刷新认证”，程序会用当前保存的学号密码重新获取登录态。";
  }
  return "这个账号还没有登录态。点击“刷新认证”后，程序会自动完成登录并保存登录态。";
}

function buildAccountFormHint(account) {
  if (account.loginStateReady) {
    return `登录态已保存：${extractFileName(account.loginStatePath || account.stateFile)}。密码留空时会继续沿用当前已保存密码。`;
  }
  return "点击“刷新认证”后，程序会自动先保存当前账号，再用学号密码登录。密码留空时默认等于学号。";
}

function setAccountEditorActionState({ canRefresh, canDelete, authHint, formHint }) {
  elements.accountRefreshLoginButton.disabled = !canRefresh;
  elements.accountDeleteButton.disabled = !canDelete;
  elements.accountAuthHint.textContent = authHint;
  elements.accountFormHint.textContent = formHint;
}

function openAccountDialog(accountName = "") {
  if (accountName) {
    selectAccountEditor(accountName);
  } else {
    resetAccountEditor();
  }

  if (typeof elements.accountDialog.showModal === "function") {
    if (!elements.accountDialog.open) {
      elements.accountDialog.showModal();
    }
    return;
  }
  elements.accountDialog.setAttribute("open", "open");
}

function closeAccountDialog() {
  resetAccountEditor();
  if (elements.accountDialog.open && typeof elements.accountDialog.close === "function") {
    elements.accountDialog.close();
    return;
  }
  elements.accountDialog.removeAttribute("open");
}

function openAccountBulkImportDialog() {
  elements.accountBulkImportResult.textContent = "";
  elements.accountBulkImportTextInput.value = "";
  if (typeof elements.accountBulkImportDialog.showModal === "function") {
    if (!elements.accountBulkImportDialog.open) {
      elements.accountBulkImportDialog.showModal();
    }
    return;
  }
  elements.accountBulkImportDialog.setAttribute("open", "open");
}

function closeAccountBulkImportDialog() {
  elements.accountBulkImportResult.textContent = "";
  if (
    elements.accountBulkImportDialog.open &&
    typeof elements.accountBulkImportDialog.close === "function"
  ) {
    elements.accountBulkImportDialog.close();
    return;
  }
  elements.accountBulkImportDialog.removeAttribute("open");
}

function openDetailsDialog({ eyebrow = "更多信息", title = "详情", hint = "", bodyHtml = "" }) {
  elements.detailsDialogEyebrow.textContent = eyebrow;
  elements.detailsDialogTitle.textContent = title;
  elements.detailsDialogHint.textContent = hint || "这里会展示当前卡片对应的完整信息。";
  elements.detailsDialogContent.innerHTML = bodyHtml;
  if (typeof elements.detailsDialog.showModal === "function") {
    if (!elements.detailsDialog.open) {
      elements.detailsDialog.showModal();
    }
    return;
  }
  elements.detailsDialog.setAttribute("open", "open");
}

function closeDetailsDialog() {
  elements.detailsDialogContent.innerHTML = "";
  if (elements.detailsDialog.open && typeof elements.detailsDialog.close === "function") {
    elements.detailsDialog.close();
    return;
  }
  elements.detailsDialog.removeAttribute("open");
}

function buildDetailsSection(title, bodyHtml) {
  return `
    <section class="details-section">
      <div class="details-section-head">
        <strong>${escapeHtml(title)}</strong>
      </div>
      <div class="details-section-body">${bodyHtml}</div>
    </section>
  `;
}

function openTaskStatusDetails(accountName) {
  const account = getAccountProfile(accountName);
  const status = account ? getTaskStatusCardState(account) : getTaskStatusByAccountName(accountName);
  if (!status) {
    showToast("暂时没有可展示的账号状态。");
    return;
  }

  const actionTargets = getTaskStatusActionTargets(status);
  const bookingHtml = (status.bookings || []).length
    ? status.bookings
        .map(
          (booking) => `
            <article class="details-booking-item">
              <div class="details-booking-head">
                <strong>${escapeHtml(booking.statusLabel || "预约记录")}</strong>
                <span class="mini-tag">${escapeHtml(booking.roomName)} ${escapeHtml(booking.seatNumber)} 号</span>
              </div>
              <p>${escapeHtml(booking.startAtLabel || "暂无开始时间")}${
                booking.checkinWindowOpen ? " · 当前可签到" : ""
              }</p>
            </article>
          `,
        )
        .join("")
    : '<p class="panel-subtitle">当前没有更多预约记录。</p>';

  const metricsHtml = `
    <div class="details-metric-grid">
      <article class="details-metric-card">
        <span class="debug-stat-label">当前状态</span>
        <strong>${escapeHtml(getTaskStateLabel(status.state))}</strong>
        <p>${escapeHtml(status.summary || "暂无状态")}</p>
      </article>
      <article class="details-metric-card">
        <span class="debug-stat-label">登录状态</span>
        <strong>${status.loginStateReady ? "已保存" : "未保存"}</strong>
        <p>${status.loginStateReady ? "可继续读取预约状态" : "需要先刷新认证"}</p>
      </article>
      <article class="details-metric-card">
        <span class="debug-stat-label">待执行</span>
        <strong>${Number(status.pendingTaskCount || 0)}</strong>
        <p>包含自动任务计划和待执行任务。</p>
      </article>
      <article class="details-metric-card">
        <span class="debug-stat-label">执行中</span>
        <strong>${Number(status.runningTaskCount || 0)}</strong>
        <p>只统计当前正在跑的任务。</p>
      </article>
    </div>
  `;

  openDetailsDialog({
    eyebrow: "账号状态详情",
    title: getSelectedAccountLabel(accountName) || accountName,
    hint: buildTaskStatusActionHint(status, actionTargets),
    bodyHtml:
      metricsHtml +
      buildDetailsSection("状态说明", `<p>${escapeHtml(status.detail || "暂无更多详情。")}</p>`) +
      buildDetailsSection(
        buildTaskStatusBookingSummaryText(status.bookings || []),
        `<div class="details-booking-list">${bookingHtml}</div>`,
      ),
  });
}

function openAutomationPlanDetails(plan) {
  if (!plan) {
    showToast("暂时没有可展示的自动任务计划。");
    return;
  }

  const overviewHtml = `
    <div class="details-metric-grid">
      <article class="details-metric-card">
        <span class="debug-stat-label">账号</span>
        <strong>${escapeHtml(getSelectedAccountLabel(plan.accountName) || plan.accountName)}</strong>
        <p>${escapeHtml(plan.roomName || "未指定自习室")}</p>
      </article>
      <article class="details-metric-card">
        <span class="debug-stat-label">座位</span>
        <strong>${escapeHtml(plan.seatNumber ? `${plan.seatNumber} 号` : "未设置")}</strong>
        <p>${escapeHtml(buildReserveWindowLabel(plan.startHour, plan.durationHours))}</p>
      </article>
      <article class="details-metric-card">
        <span class="debug-stat-label">目标日期</span>
        <strong>${escapeHtml(plan.selectedDate || "--")}</strong>
        <p>${plan.continuousReserve ? "会补订后续日期（后续日期按学校默认开馆时段）" : "只维护当前选定日期"}</p>
      </article>
      <article class="details-metric-card">
        <span class="debug-stat-label">最近更新</span>
        <strong>${escapeHtml(formatDateTime(plan.updatedAt || plan.createdAt || ""))}</strong>
        <p>计划创建和修改时间都会反映在这里。</p>
      </article>
    </div>
  `;

  const actionSections = AUTOMATION_ACTIONS.map((action) => {
    const item = buildAutomationActionItem(action, plan);
    const factsHtml = item.facts.length
      ? `<div class="details-tag-list">${item.facts
          .map((fact) => `<span class="mini-tag">${escapeHtml(fact)}</span>`)
          .join("")}</div>`
      : '<p class="panel-subtitle">当前没有更多执行信息。</p>';
    return buildDetailsSection(
      item.title,
      `
        <div class="details-section-summary">
          <span class="task-state-badge state-${item.enabled ? "active" : "error"}">${
            item.enabled ? "已开启" : "未开启"
          }</span>
          <p>${escapeHtml(item.message)}</p>
        </div>
        ${factsHtml}
      `,
    );
  }).join("");

  openDetailsDialog({
    eyebrow: "自动任务详情",
    title: getSelectedAccountLabel(plan.accountName) || plan.accountName,
    hint: "这里只展示完整计划与执行信息；修改和删除仍然直接在卡片上操作。",
    bodyHtml: overviewHtml + actionSections,
  });
}

function buildAccountActionButton(label, action, accountName) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "ghost-button";
  button.textContent = label;
  button.dataset.accountAction = action;
  button.dataset.accountName = accountName;
  if (action === "switch" && accountName === getAccountName()) {
    button.disabled = true;
  }
  return button;
}

function setAccountSelected(accountName, selected) {
  if (!accountName) {
    return;
  }
  const selectedNames = new Set(state.selectedAccountNames);
  if (selected) {
    selectedNames.add(accountName);
  } else {
    selectedNames.delete(accountName);
  }
  state.selectedAccountNames = state.accounts
    .map((account) => account.name)
    .filter((name) => selectedNames.has(name));
  syncAccountBulkActions();
  renderAccountList();
}

function toggleAccountSelected(accountName) {
  setAccountSelected(accountName, !state.selectedAccountNames.includes(accountName));
}

function setAccountBulkSelectionMode(enabled) {
  state.accountBulkSelectionMode = Boolean(enabled);
  if (!state.accountBulkSelectionMode) {
    state.selectedAccountNames = [];
  }
  syncAccountBulkActions();
  renderAccountList();
}

function getSelectedAccountNames() {
  const accountNames = new Set(state.accounts.map((account) => account.name));
  return state.selectedAccountNames.filter((accountName) => accountNames.has(accountName));
}

function syncAccountBulkActions() {
  const selectedCount = getSelectedAccountNames().length;
  elements.accountBulkActionButton.textContent = state.accountBulkSelectionMode
    ? "退出批量"
    : "批量操作";
  elements.accountDeleteSelectedButton.hidden = !state.accountBulkSelectionMode;
  elements.accountDeleteSelectedButton.disabled = !state.accountBulkSelectionMode || selectedCount === 0;
  elements.accountDeleteSelectedButton.textContent = selectedCount
    ? `删除选中 ${selectedCount}`
    : "删除选中";
}

function buildAccountSelectOptions(accounts) {
  return accounts.map((account) => ({
    value: account.name,
    label: `${getAccountDisplayName(account)}${account.isDefault ? "（默认）" : ""}`,
  }));
}

function splitTokenValues(value) {
  return [
    ...new Set(
      String(value)
        .split(/[\s,，;；]+/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ];
}

function extractFileName(filePath) {
  const normalizedPath = String(filePath || "").trim();
  if (!normalizedPath) {
    return "";
  }
  return normalizedPath.split(/[\\/]/).pop() || normalizedPath;
}


/* spec account-pool-tri-sync 11.13: Manual_Sync_Action UI 入口 + Sync_Coverage_Confirmation 弹窗 */
const manualSyncState = {
  initialized: false,
  candidates: [],
  selection: {},
  token: "",
  buttonState: "",
  buttonLabel: "正在检查",
  failureReason: "",
  uploadEnabled: false,
  pollTimer: null,
  syncDialog: null,
  syncStateChip: null,
  syncStateLabel: null,
  syncHint: null,
  manualSyncButton: null,
  manualUploadButton: null,
  candidateList: null,
  summaryRoot: null,
  hint: null,
  selectAllButton: null,
  clearAllButton: null,
  invertButton: null,
  confirmButton: null,
  cancelButton: null,
  closeButton: null,
};

const SYNC_KIND_LABELS = {
  add: "新增",
  replace: "替换",
  remove: "移除",
};

const SYNC_ERROR_MESSAGES = {
  unconfigured: "未配置服务端",
  server_unreachable: "同步失败：服务端不可达（server_unreachable）",
  unauthorized_401: "同步失败：服务端鉴权失败（unauthorized_401）",
  https_required_426: "同步失败：服务端要求 HTTPS（https_required_426）",
  server_5xx: "同步失败：服务端错误（server_5xx）",
  rate_limited: "同步失败：服务端限频，请稍后再试",
  protocol_error: "同步失败：服务端响应异常",
  internal_error: "同步失败：客户端内部错误",
  token_expired: "弹窗会话已过期，请重新点击同步按钮",
  upload_disabled: "未启用同步上行，请先在服务端配置中勾选上行开关",
};

function initManualSync() {
  if (manualSyncState.initialized) {
    return;
  }
  manualSyncState.initialized = true;

  manualSyncState.syncDialog = document.getElementById("syncDialog");
  manualSyncState.syncStateChip = document.getElementById("syncStateChip");
  manualSyncState.syncStateLabel = document.getElementById("syncStateLabel");
  manualSyncState.syncHint = document.getElementById("syncHint");
  manualSyncState.manualSyncButton = document.getElementById("manualSyncButton");
  manualSyncState.manualUploadButton = document.getElementById("manualUploadButton");
  manualSyncState.candidateList = document.getElementById("syncDialogCandidateList");
  manualSyncState.summaryRoot = document.getElementById("syncDialogSummary");
  manualSyncState.hint = document.getElementById("syncDialogHint");
  manualSyncState.selectAllButton = document.getElementById("syncDialogSelectAllButton");
  manualSyncState.clearAllButton = document.getElementById("syncDialogClearAllButton");
  manualSyncState.invertButton = document.getElementById("syncDialogInvertButton");
  manualSyncState.confirmButton = document.getElementById("syncDialogConfirmButton");
  manualSyncState.cancelButton = document.getElementById("syncDialogCancelButton");
  manualSyncState.closeButton = document.getElementById("syncDialogCloseButton");

  if (!manualSyncState.manualSyncButton) {
    return;
  }

  manualSyncState.manualSyncButton.addEventListener("click", () => {
    void handleManualSyncClick();
  });
  manualSyncState.manualUploadButton?.addEventListener("click", () => {
    void handleManualUploadClick();
  });
  manualSyncState.selectAllButton?.addEventListener("click", () => {
    setAllSyncSelections(true);
  });
  manualSyncState.clearAllButton?.addEventListener("click", () => {
    setAllSyncSelections(false);
  });
  manualSyncState.invertButton?.addEventListener("click", () => {
    invertSyncSelections();
  });
  manualSyncState.confirmButton?.addEventListener("click", () => {
    void handleSyncConfirm();
  });
  manualSyncState.cancelButton?.addEventListener("click", () => {
    handleSyncCancel();
  });
  manualSyncState.closeButton?.addEventListener("click", () => {
    handleSyncCancel();
  });

  initServerSyncConfigDialog();

  void refreshSyncButtonState();
  if (!manualSyncState.pollTimer) {
    // 每 30 秒重新读取一次三态指示，让用户在配置变更后看到最新可用性。
    manualSyncState.pollTimer = window.setInterval(() => {
      void refreshSyncButtonState();
    }, 30000);
  }
}

async function refreshSyncButtonState() {
  if (!manualSyncState.manualSyncButton) {
    return;
  }
  try {
    const data = await requestJson("/api/sync/state", {
      debugLabel: "读取同步按钮状态",
      debugSilent: true,
    });
    manualSyncState.buttonState = data.state || "";
    manualSyncState.buttonLabel = data.label || "";
    manualSyncState.failureReason = data.failure_reason || "";
    manualSyncState.uploadEnabled = Boolean(data.upload_enabled);
    applyManualSyncState();
  } catch (error) {
    // 读取状态失败不阻塞页面其它功能；按未配置展示，不弹 toast。
    manualSyncState.buttonState = "disabled_unconfigured";
    manualSyncState.buttonLabel = "未配置服务端";
    manualSyncState.failureReason = "";
    manualSyncState.uploadEnabled = false;
    applyManualSyncState();
  }
}

function applyManualSyncState() {
  const button = manualSyncState.manualSyncButton;
  const uploadButton = manualSyncState.manualUploadButton;
  const chip = manualSyncState.syncStateChip;
  const label = manualSyncState.syncStateLabel;
  const hint = manualSyncState.syncHint;
  if (!button || !chip || !label) {
    return;
  }
  const state = manualSyncState.buttonState;
  chip.dataset.syncState = state;
  chip.textContent = manualSyncState.buttonLabel || "正在检查";
  if (state === "enabled") {
    button.disabled = false;
    if (uploadButton) {
      uploadButton.disabled = !manualSyncState.uploadEnabled;
    }
    label.textContent = manualSyncState.uploadEnabled
      ? "服务端已配置，可拉取服务端清单，也可上传本地账号到服务端。"
      : "服务端已配置，可点击「从服务端同步活跃池」拉取最新清单。";
    if (hint) {
      hint.textContent = manualSyncState.uploadEnabled
        ? "上传会把本地账号基础配置写入服务端 Active_Pool，不会删除服务端其它账号。"
        : "上传需在「配置服务端」中勾选同步上行开关。";
    }
  } else if (state === "disabled_unconfigured") {
    button.disabled = true;
    if (uploadButton) {
      uploadButton.disabled = true;
    }
    label.textContent = "未配置服务端，按钮置灰；不影响本地任务执行与登录刷新。";
    if (hint) {
      hint.textContent = "点击右上角「配置服务端」填写 base_url，bearer_token 可以先留空，或直接编辑 config.json 的 server_sync 段。";
    }
  } else if (state === "disabled_unreachable") {
    button.disabled = true;
    if (uploadButton) {
      uploadButton.disabled = true;
    }
    const reason = manualSyncState.failureReason
      ? `服务端不可达（${manualSyncState.failureReason}）`
      : "服务端不可达";
    label.textContent = `${reason}；本地任务执行与登录刷新不受影响。`;
    if (hint) {
      hint.textContent = "稍后服务端恢复后再次点击同步即可。";
    }
  } else {
    button.disabled = true;
    if (uploadButton) {
      uploadButton.disabled = true;
    }
    label.textContent = "正在检查连接状态…";
  }
}

async function handleManualSyncClick() {
  const button = manualSyncState.manualSyncButton;
  if (!button || button.disabled) {
    return;
  }

  // 13.3：未配置服务端时仅 toast 提示，不发任何网络请求。
  // 这里依赖按钮的 disabled 状态拦截，但兜底再判一次。
  if (manualSyncState.buttonState === "disabled_unconfigured") {
    showToast(SYNC_ERROR_MESSAGES.unconfigured);
    return;
  }

  await runBusyButtonAction(
    button,
    {
      busyLabel: "同步中…",
      idleLabel: "从服务端同步活跃池",
      restoreDisabled: () => manualSyncState.buttonState !== "enabled",
    },
    async () => {
      const data = await requestJsonWithBody("/api/sync/preview", {}, "拉取服务端活跃池");
      if (!data?.ok) {
        const message = SYNC_ERROR_MESSAGES[data?.error_code] || data?.message || "同步失败";
        showToast(message);
        // 失败后刷新一次按钮状态，让连接指示与最近一次结果一致。
        void refreshSyncButtonState();
        return;
      }

      manualSyncState.token = data.token || "";
      manualSyncState.candidates = Array.isArray(data.candidates) ? data.candidates : [];
      manualSyncState.selection = {};
      manualSyncState.candidates.forEach((candidate) => {
        manualSyncState.selection[candidate.student_id] = Boolean(candidate.default_checked);
      });
      renderSyncDialog(data.summary || {});
      openSyncDialog();
    },
  );
}

async function handleManualUploadClick() {
  const button = manualSyncState.manualUploadButton;
  if (!button || button.disabled) {
    return;
  }
  if (!manualSyncState.uploadEnabled) {
    showToast(SYNC_ERROR_MESSAGES.upload_disabled);
    return;
  }
  const confirmed = window.confirm(
    "将本地账号基础配置上传到服务端 Active_Pool，不会删除服务端其它账号。继续？",
  );
  if (!confirmed) {
    return;
  }

  await runBusyButtonAction(
    button,
    {
      busyLabel: "上传中…",
      idleLabel: "上传到服务端",
      restoreDisabled: () =>
        manualSyncState.buttonState !== "enabled" || !manualSyncState.uploadEnabled,
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/sync/upload",
        {},
        "上传本地账号到服务端",
      );
      if (!data?.ok) {
        const message =
          SYNC_ERROR_MESSAGES[data?.error_code] || data?.message || "上传失败";
        showToast(message);
        void refreshSyncButtonState();
        return;
      }
      showToast(data.message || "上传完成");
      void refreshSyncButtonState();
    },
  );
}

function renderSyncDialog(summary) {
  renderSyncSummary(summary);
  renderSyncCandidates();
  updateSyncConfirmButton();
}

function renderSyncSummary(summary) {
  const root = manualSyncState.summaryRoot;
  if (!root) {
    return;
  }
  const counts = computeSyncSummary();
  root.innerHTML = `
    <span class="sync-chip add">新增 ${counts.add}</span>
    <span class="sync-chip replace">替换 ${counts.replace}</span>
    <span class="sync-chip remove">移除 ${counts.remove}</span>
  `;
}

function computeSyncSummary() {
  const counts = { add: 0, replace: 0, remove: 0 };
  manualSyncState.candidates.forEach((candidate) => {
    if (counts[candidate.kind] !== undefined) {
      counts[candidate.kind] += 1;
    }
  });
  return counts;
}

function renderSyncCandidates() {
  const root = manualSyncState.candidateList;
  if (!root) {
    return;
  }
  if (!manualSyncState.candidates.length) {
    root.innerHTML = '<p class="sync-candidate-empty">本地账号已与服务端清单一致，无需同步。</p>';
    return;
  }
  root.innerHTML = manualSyncState.candidates
    .map((candidate) => renderSyncCandidate(candidate))
    .join("");

  root.querySelectorAll('input[type="checkbox"][data-sync-sid]').forEach((checkbox) => {
    checkbox.addEventListener("change", (event) => {
      const target = event.target;
      const sid = target?.dataset?.syncSid || "";
      if (!sid) {
        return;
      }
      manualSyncState.selection[sid] = Boolean(target.checked);
      updateSyncConfirmButton();
    });
  });
}

function renderSyncCandidate(candidate) {
  const sid = candidate.student_id;
  const checked = Boolean(manualSyncState.selection[sid]);
  const kindLabel = SYNC_KIND_LABELS[candidate.kind] || candidate.kind;
  const server = candidate.server_summary;
  const local = candidate.local_summary;

  const lines = [];
  lines.push(`<strong>${escapeHtml(sid)}</strong>`);

  if (candidate.kind === "add" && server) {
    lines.push(
      `<span class="sync-candidate-meta">服务端：备注「${escapeHtml(server.display_name || "—")}」 · 自动任务 ${server.automation_task_count} 条</span>`,
    );
  } else if (candidate.kind === "replace" && server && local) {
    lines.push(
      `<span class="sync-candidate-meta">服务端：备注「${escapeHtml(server.display_name || "—")}」 · 自动任务 ${server.automation_task_count} 条</span>`,
      `<span class="sync-candidate-meta">本地：备注「${escapeHtml(local.display_name || "—")}」 · 自动任务 ${local.automation_task_count} 条</span>`,
    );
  } else if (candidate.kind === "remove" && local) {
    lines.push(
      `<span class="sync-candidate-meta">本地：备注「${escapeHtml(local.display_name || "—")}」 · 自动任务 ${local.automation_task_count} 条（仅取消「受服务端管理」标记，不删除本地账号）</span>`,
    );
  }

  return `
    <label class="sync-candidate">
      <input type="checkbox" data-sync-sid="${escapeHtml(sid)}"${checked ? " checked" : ""} />
      <span class="sync-candidate-kind ${candidate.kind}">${kindLabel}</span>
      <span class="sync-candidate-body">${lines.join("")}</span>
    </label>
  `;
}

function setAllSyncSelections(value) {
  const flag = Boolean(value);
  manualSyncState.candidates.forEach((candidate) => {
    manualSyncState.selection[candidate.student_id] = flag;
  });
  syncCandidateCheckboxes();
  updateSyncConfirmButton();
}

function invertSyncSelections() {
  manualSyncState.candidates.forEach((candidate) => {
    const sid = candidate.student_id;
    manualSyncState.selection[sid] = !manualSyncState.selection[sid];
  });
  syncCandidateCheckboxes();
  updateSyncConfirmButton();
}

function syncCandidateCheckboxes() {
  const root = manualSyncState.candidateList;
  if (!root) {
    return;
  }
  root.querySelectorAll('input[type="checkbox"][data-sync-sid]').forEach((checkbox) => {
    const sid = checkbox.dataset.syncSid || "";
    checkbox.checked = Boolean(manualSyncState.selection[sid]);
  });
}

function updateSyncConfirmButton() {
  const button = manualSyncState.confirmButton;
  if (!button) {
    return;
  }
  const total = manualSyncState.candidates.length;
  const checkedCount = manualSyncState.candidates.reduce((sum, candidate) => {
    return sum + (manualSyncState.selection[candidate.student_id] ? 1 : 0);
  }, 0);
  if (!total) {
    button.disabled = true;
    button.textContent = "确认覆盖";
    return;
  }
  // 13.15：全空时也允许点击，由后端按 noop 提示文案，避免「按钮不可点」的歧义。
  button.disabled = false;
  button.textContent = checkedCount > 0 ? `确认覆盖（${checkedCount}/${total}）` : "确认覆盖";
}

function openSyncDialog() {
  const dialog = manualSyncState.syncDialog;
  if (!dialog) {
    return;
  }
  if (typeof dialog.showModal === "function" && !dialog.open) {
    dialog.showModal();
  } else {
    dialog.setAttribute("open", "open");
  }
}

function closeSyncDialog() {
  const dialog = manualSyncState.syncDialog;
  if (!dialog) {
    return;
  }
  if (typeof dialog.close === "function" && dialog.open) {
    dialog.close();
  } else {
    dialog.removeAttribute("open");
  }
  // 13.16：弹窗关闭后不持久化 Sync_Selection，下次按 default_checked 重新初始化。
  manualSyncState.candidates = [];
  manualSyncState.selection = {};
  manualSyncState.token = "";
}

function handleSyncCancel() {
  const token = manualSyncState.token;
  closeSyncDialog();
  if (token) {
    // 主动通知后端丢弃 preview 会话；失败也忽略，TTL 兜底。
    void requestJsonWithBody("/api/sync/cancel", { token }, "取消同步弹窗")
      .catch(() => {});
  }
}

async function handleSyncConfirm() {
  const button = manualSyncState.confirmButton;
  if (!button || button.disabled) {
    return;
  }
  const token = manualSyncState.token;
  const selection = { ...manualSyncState.selection };
  await runBusyButtonAction(
    button,
    {
      busyLabel: "应用中…",
      idleLabel: "确认覆盖",
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/sync/apply",
        { token, selection },
        "应用服务端同步",
      );
      if (!data?.ok) {
        const message = SYNC_ERROR_MESSAGES[data?.error_code] || data?.message || "同步失败";
        showToast(message);
        if (data?.error_code === "token_expired") {
          closeSyncDialog();
          void refreshSyncButtonState();
        }
        return;
      }
      showToast(data.message || "同步成功");
      closeSyncDialog();
      // 同步成功后刷新账号列表，让新增 / 替换 / 移除标记的结果立即可见。
      try {
        await loadAccounts(state.bootstrap?.selectedAccountName || "");
      } catch (error) {
        // 加载失败不影响同步结果展示
      }
    },
  );
}

/* 服务端配置弹窗（账号管理页「从服务端同步活跃池」卡片旁的「配置服务端」按钮） */
const serverSyncConfigState = {
  initialized: false,
  dialog: null,
  baseUrlInput: null,
  bearerTokenInput: null,
  verifyTlsInput: null,
  uploadEnabledInput: null,
  openButton: null,
  saveButton: null,
  clearButton: null,
  cancelButton: null,
  closeButton: null,
};

function initServerSyncConfigDialog() {
  if (serverSyncConfigState.initialized) {
    return;
  }
  serverSyncConfigState.initialized = true;

  serverSyncConfigState.dialog = document.getElementById("serverSyncConfigDialog");
  serverSyncConfigState.baseUrlInput = document.getElementById("serverSyncBaseUrlInput");
  serverSyncConfigState.bearerTokenInput = document.getElementById("serverSyncBearerTokenInput");
  serverSyncConfigState.verifyTlsInput = document.getElementById("serverSyncVerifyTlsInput");
  serverSyncConfigState.uploadEnabledInput = document.getElementById("serverSyncUploadEnabledInput");
  serverSyncConfigState.openButton = document.getElementById("serverSyncConfigButton");
  serverSyncConfigState.saveButton = document.getElementById("serverSyncConfigSaveButton");
  serverSyncConfigState.clearButton = document.getElementById("serverSyncConfigClearButton");
  serverSyncConfigState.cancelButton = document.getElementById("serverSyncConfigCancelButton");
  serverSyncConfigState.closeButton = document.getElementById("serverSyncConfigCloseButton");

  if (!serverSyncConfigState.openButton || !serverSyncConfigState.dialog) {
    return;
  }

  serverSyncConfigState.openButton.addEventListener("click", () => {
    void openServerSyncConfigDialog();
  });
  serverSyncConfigState.cancelButton?.addEventListener("click", () => {
    closeServerSyncConfigDialog();
  });
  serverSyncConfigState.closeButton?.addEventListener("click", () => {
    closeServerSyncConfigDialog();
  });
  serverSyncConfigState.clearButton?.addEventListener("click", () => {
    void handleServerSyncConfigClear();
  });
  serverSyncConfigState.saveButton?.addEventListener("click", () => {
    void handleServerSyncConfigSave();
  });
}

async function openServerSyncConfigDialog() {
  const button = serverSyncConfigState.openButton;
  if (!button) {
    return;
  }
  await runBusyButtonAction(
    button,
    {
      busyLabel: "读取中…",
      idleLabel: "配置服务端",
    },
    async () => {
      const data = await requestJson("/api/server-sync/settings", {
        debugLabel: "读取服务端配置",
      });
      applyServerSyncConfigToInputs(data?.config);
      showServerSyncConfigDialog();
    },
  );
}

function applyServerSyncConfigToInputs(config) {
  const safeConfig = config && typeof config === "object" ? config : {};
  if (serverSyncConfigState.baseUrlInput) {
    serverSyncConfigState.baseUrlInput.value = safeConfig.base_url || "";
  }
  if (serverSyncConfigState.bearerTokenInput) {
    serverSyncConfigState.bearerTokenInput.value = safeConfig.bearer_token || "";
  }
  if (serverSyncConfigState.verifyTlsInput) {
    serverSyncConfigState.verifyTlsInput.checked = safeConfig.verify_tls !== false;
  }
  if (serverSyncConfigState.uploadEnabledInput) {
    serverSyncConfigState.uploadEnabledInput.checked = Boolean(safeConfig.upload_enabled);
  }
}

function showServerSyncConfigDialog() {
  const dialog = serverSyncConfigState.dialog;
  if (!dialog) {
    return;
  }
  if (typeof dialog.showModal === "function" && !dialog.open) {
    dialog.showModal();
  } else {
    dialog.setAttribute("open", "open");
  }
}

function closeServerSyncConfigDialog() {
  const dialog = serverSyncConfigState.dialog;
  if (!dialog) {
    return;
  }
  if (typeof dialog.close === "function" && dialog.open) {
    dialog.close();
  } else {
    dialog.removeAttribute("open");
  }
}

async function handleServerSyncConfigSave() {
  const button = serverSyncConfigState.saveButton;
  if (!button) {
    return;
  }
  const payload = readServerSyncConfigInputs();
  await runBusyButtonAction(
    button,
    {
      busyLabel: "保存中…",
      idleLabel: "保存",
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/server-sync/settings",
        payload,
        "保存服务端配置",
      );
      applyServerSyncConfigToInputs(data?.config);
      showToast(data?.message || "已保存服务端配置");
      closeServerSyncConfigDialog();
      // 配置变更后立即刷新同步按钮三态，避免再等 30 秒轮询。
      void refreshSyncButtonState();
    },
  );
}

async function handleServerSyncConfigClear() {
  const button = serverSyncConfigState.clearButton;
  if (!button) {
    return;
  }
  await runBusyButtonAction(
    button,
    {
      busyLabel: "清除中…",
      idleLabel: "清除配置",
    },
    async () => {
      const data = await requestJsonWithBody(
        "/api/server-sync/settings",
        { base_url: "", bearer_token: "", verify_tls: true, upload_enabled: false },
        "清除服务端配置",
      );
      applyServerSyncConfigToInputs(data?.config);
      showToast(data?.message || "已清除服务端配置");
      closeServerSyncConfigDialog();
      void refreshSyncButtonState();
    },
  );
}

function readServerSyncConfigInputs() {
  return {
    base_url: serverSyncConfigState.baseUrlInput?.value?.trim() || "",
    bearer_token: serverSyncConfigState.bearerTokenInput?.value?.trim() || "",
    verify_tls: Boolean(serverSyncConfigState.verifyTlsInput?.checked),
    upload_enabled: Boolean(serverSyncConfigState.uploadEnabledInput?.checked),
  };
}

async function requestJson(url, options = {}) {
  const {
    debugLabel = "",
    debugSilent = false,
    ...fetchOptions
  } = options;
  const method = String(fetchOptions.method || "GET").toUpperCase();
  const startedAt = new Date().toISOString();
  const startedMs = performance.now();
  const requestBody = extractDebugPayload(fetchOptions.body);
  const label = debugLabel || `${method} ${url}`;

  try {
    const response = await fetch(url, fetchOptions);
    const rawText = await response.text();
    let data = {};

    if (rawText) {
      try {
        data = JSON.parse(rawText);
      } catch (error) {
        const parseError = new Error(`接口返回了无法解析的内容：${rawText}`);
        parseError.debugHandled = true;
        commitDebugRequest(
          {
            label,
            method,
            url,
            startedAt,
            durationMs: Math.round(performance.now() - startedMs),
            status: response.status,
            ok: false,
            requestBody,
            responseBody: rawText,
            errorMessage: parseError.message,
          },
          "error",
          label,
          parseError.message,
        );
        throw parseError;
      }
    }

    const requestRecord = {
      label,
      method,
      url,
      startedAt,
      durationMs: Math.round(performance.now() - startedMs),
      status: response.status,
      ok: response.ok,
      requestBody,
      responseBody: data,
      errorMessage: "",
    };

    if (!response.ok) {
      const requestError = new Error(data.message || data.error || `请求失败：HTTP ${response.status}`);
      requestError.debugHandled = true;
      requestRecord.errorMessage = requestError.message;
      commitDebugRequest(requestRecord, "error", label, requestError.message);
      throw requestError;
    }

    if (!debugSilent) {
      commitDebugRequest(
        requestRecord,
        "success",
        label,
        `HTTP ${response.status} · ${requestRecord.durationMs}ms`,
      );
    }
    return data;
  } catch (error) {
    if (error?.debugHandled) {
      throw error;
    }

    const message = describeDebugError(error);
    commitDebugRequest(
      {
        label,
        method,
        url,
        startedAt,
        durationMs: Math.round(performance.now() - startedMs),
        status: 0,
        ok: false,
        requestBody,
        responseBody: "",
        errorMessage: message,
      },
      "error",
      label,
      message,
    );
    throw error;
  }
}

function requestJsonWithBody(url, payload, debugLabel) {
  return requestJson(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    debugLabel,
  });
}

async function runBusyButtonAction(button, options, action) {
  const {
    busyLabel,
    idleLabel,
    onFinally = null,
    restoreDisabled = null,
    onError = null,
  } = options;

  setButtonBusy(button, true, busyLabel);
  try {
    return await action();
  } catch (error) {
    if (typeof onError === "function") {
      onError(error);
    } else {
      showToast(error.message);
    }
    return null;
  } finally {
    setButtonBusy(button, false, idleLabel);
    if (typeof restoreDisabled === "function") {
      button.disabled = restoreDisabled();
    }
    if (typeof onFinally === "function") {
      onFinally();
    }
  }
}

function setButtonBusy(button, busy, label) {
  button.dataset.busy = busy ? "true" : "false";
  if (label) {
    button.textContent = label;
  }
  button.disabled = busy;
}

function showToast(message, options = {}) {
  const text = String(message || "操作失败");
  elements.toast.textContent = text;
  elements.toast.classList.add("is-visible");

  if (options.log !== false) {
    addDebugLog(inferToastLevel(text), "界面提示", text);
  }

  if (state.toastTimer) {
    window.clearTimeout(state.toastTimer);
  }

  state.toastTimer = window.setTimeout(() => {
    elements.toast.classList.remove("is-visible");
  }, 2600);
}

function installDebugHooks() {
  window.addEventListener("error", (event) => {
    addDebugLog("error", "页面脚本异常", event.message || "出现未捕获错误", {
      fileName: event.filename,
      line: event.lineno,
      column: event.colno,
    });
  });
  window.addEventListener("unhandledrejection", (event) => {
    addDebugLog("error", "未处理的异步异常", describeDebugError(event.reason), {
      reason: serializeDebugValue(event.reason),
    });
  });
}

function addDebugLog(level, title, message = "", context = null) {
  const entry = {
    id: state.debugLogSequence + 1,
    level: normalizeDebugLevel(level),
    title: String(title || "调试事件"),
    message: String(message || ""),
    context,
    createdAt: new Date().toISOString(),
  };
  state.debugLogSequence = entry.id;
  state.debugLogs = [entry, ...state.debugLogs].slice(0, 120);
  renderDebugPanel();
}

function renderDebugPanel() {
  renderDebugSummary();
  renderDebugRequest();
  renderDebugSnapshot();
  renderDebugLogs();
}

function renderDebugSummary() {
  const currentSeatUrl = elements.seatUrlSelect.value || state.bootstrap?.selectedSeatUrl || "";
  elements.debugAccountValue.textContent = getAccountName() || "未选择";
  elements.debugLoginStateValue.textContent = state.bootstrap?.loginStateReady
    ? "登录态已保存"
    : "登录态未保存";
  elements.debugSeatUrlValue.textContent = currentSeatUrl ? extractFileName(currentSeatUrl) : "未读取";
  elements.debugSeatUrlValue.title = currentSeatUrl;
  elements.debugSeatUrlMeta.textContent = currentSeatUrl || "读取引导数据后显示当前入口页。";

  const queryStatus = buildDebugQueryStatus();
  elements.debugQueryStateValue.textContent = queryStatus.title;
  elements.debugQueryMeta.textContent = queryStatus.meta;

  const lastRequest = state.debugLastRequest;
  elements.debugLastRequestValue.textContent = lastRequest
    ? `${lastRequest.method} ${lastRequest.status || "网络异常"}`
    : "暂无";
  elements.debugLastRequestMeta.textContent = lastRequest
    ? `${lastRequest.label} · ${lastRequest.durationMs}ms`
    : "执行读取、查询或预约后会显示。";

  elements.debugRetrySearchButton.disabled = !state.bootstrap || Boolean(state.bootstrap.message);
}

function renderDebugRequest() {
  const lastRequest = state.debugLastRequest;
  if (!lastRequest) {
    elements.debugRequestTitle.textContent = "还没有接口请求";
    elements.debugRequestStatus.textContent = "执行读取、查询、预约或账号操作后会显示。";
    elements.debugRequestBadge.textContent = "等待中";
    elements.debugRequestBadge.className = "status-badge";
    elements.debugRequestFacts.innerHTML = "";
    elements.debugRequestBody.textContent = "--";
    elements.debugResponseBody.textContent = "--";
    return;
  }

  elements.debugRequestTitle.textContent = lastRequest.label;
  elements.debugRequestStatus.textContent = lastRequest.ok
    ? `请求完成：HTTP ${lastRequest.status}，耗时 ${lastRequest.durationMs}ms`
    : `请求失败：${lastRequest.errorMessage || `HTTP ${lastRequest.status || "网络异常"}`}`;
  elements.debugRequestBadge.textContent = lastRequest.ok ? "成功" : "失败";
  elements.debugRequestBadge.className = `status-badge ${lastRequest.ok ? "is-ready" : "is-missing"}`;

  const facts = [
    ["方法", lastRequest.method],
    ["地址", lastRequest.url],
    ["开始时间", formatDebugTime(lastRequest.startedAt)],
    ["耗时", `${lastRequest.durationMs}ms`],
    ["状态码", lastRequest.status || "网络异常"],
  ];
  elements.debugRequestFacts.innerHTML = facts
    .map(
      ([label, value]) => `
        <div class="debug-fact-row">
          <span class="debug-fact-label">${label}</span>
          <span class="debug-fact-value">${escapeHtml(String(value))}</span>
        </div>
      `,
    )
    .join("");
  elements.debugRequestBody.textContent = formatDebugCodeBlock(lastRequest.requestBody);
  elements.debugResponseBody.textContent = formatDebugCodeBlock(
    lastRequest.ok ? lastRequest.responseBody : lastRequest.errorMessage || lastRequest.responseBody,
  );
}

function renderDebugSnapshot() {
  elements.debugSnapshot.textContent = formatDebugCodeBlock(buildDebugSnapshot());
}

function renderDebugLogs() {
  if (!state.debugLogs.length) {
    elements.debugLogList.innerHTML = '<p class="panel-subtitle">还没有调试日志。</p>';
    return;
  }

  elements.debugLogList.innerHTML = state.debugLogs
    .map((entry) => {
      const contextMarkup = entry.context
        ? `
          <details class="debug-log-details">
            <summary>展开上下文</summary>
            <pre>${escapeHtml(formatDebugCodeBlock(entry.context))}</pre>
          </details>
        `
        : "";
      return `
        <article class="debug-log-item">
          <div class="debug-log-head">
            <span class="debug-log-badge ${entry.level}">${getDebugLevelLabel(entry.level)}</span>
            <strong>${escapeHtml(entry.title)}</strong>
            <time>${escapeHtml(formatDebugTime(entry.createdAt))}</time>
          </div>
          <p class="debug-log-message">${escapeHtml(entry.message || "无附加信息")}</p>
          ${contextMarkup}
        </article>
      `;
    })
    .join("");
}

async function refreshDebugBootstrap() {
  addDebugLog("info", "手动刷新引导数据", "正在重新读取当前账号和入口页状态。");
  await loadBootstrap(getAccountName(), elements.seatUrlSelect.value, false);
}

async function rerunDebugSearch() {
  addDebugLog("info", "手动重新查询", "正在使用当前条件重新查询座位。", collectFilters());
  await searchSeats();
}

async function copyDebugSnapshot() {
  await copyTextToClipboard(formatDebugCodeBlock(buildDebugSnapshot()));
  showToast("已复制当前状态快照。", { log: false });
  addDebugLog("success", "状态快照已复制", "可直接把快照发给我继续排查。");
}

function clearDebugLogs() {
  state.debugLogs = [];
  renderDebugPanel();
  showToast("已清空调试日志。", { log: false });
}

function commitDebugRequest(requestRecord, level, title, message) {
  state.debugLastRequest = requestRecord;
  addDebugLog(level, title, message, requestRecord);
}

function buildDebugSnapshot() {
  const currentSeatMap = getCurrentSeatMap();
  return {
    generatedAt: new Date().toISOString(),
    activeView: getActiveViewName(),
    accountName: getAccountName(),
    loginStateReady: Boolean(state.bootstrap?.loginStateReady),
    bootstrapMessage: state.bootstrap?.message || "",
    currentSeatUrl: elements.seatUrlSelect.value || "",
    currentFilters: state.bootstrap ? collectFilters() : null,
    querySummary: buildCurrentQuerySummaryText(),
    queryCurrent: isQueryResultCurrent(),
    selectedRoomId: state.selectedRoomId || "",
    selectedRoomName: currentSeatMap?.roomName || "",
    selectedSeatIds: [...state.selectedSeatIds],
    selectedSeats: getSelectedSeats().map((seat) => ({
      seatId: seat.seatId,
      seatNumber: seat.seatNumber,
      hasSocket: seat.hasSocket,
      recommended: seat.recommended,
    })),
    taskCount: state.tasks.length,
    accountCount: state.accounts.length,
    lastRequest: state.debugLastRequest,
    recentLogs: state.debugLogs.slice(0, 12).map((entry) => ({
      time: entry.createdAt,
      level: entry.level,
      title: entry.title,
      message: entry.message,
    })),
  };
}

function buildDebugQueryStatus() {
  if (!state.bootstrap) {
    return { title: "未初始化", meta: "引导数据尚未返回。" };
  }
  if (state.bootstrap.message) {
    return { title: "入口异常", meta: state.bootstrap.message };
  }
  if (!state.queryResult) {
    return { title: "未查询", meta: "还没有执行座位查询。" };
  }
  if (!isQueryResultCurrent()) {
    return { title: "结果过期", meta: "筛选条件已变更，请重新查询。" };
  }
  const currentSeatMap = getCurrentSeatMap();
  return {
    title: "结果有效",
    meta: `${Number(currentSeatMap?.availableCount || 0)} 个可选座位，已选 ${state.selectedSeatIds.length} 个。`,
  };
}

function getActiveViewName() {
  return elements.viewPanels.find((panel) => panel.classList.contains("is-active"))?.dataset.viewPanel || "reserve";
}

function extractDebugPayload(body) {
  if (!body) {
    return "";
  }
  if (typeof body === "string") {
    try {
      return JSON.parse(body);
    } catch (error) {
      return body;
    }
  }
  if (body instanceof FormData) {
    return Object.fromEntries(body.entries());
  }
  return serializeDebugValue(body);
}

function formatDebugCodeBlock(value) {
  if (value === undefined || value === null || value === "") {
    return "--";
  }
  if (typeof value === "string") {
    return value;
  }
  return JSON.stringify(serializeDebugValue(value), null, 2);
}

function formatDebugTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value || "--");
  }
  return `${padNumber(date.getHours())}:${padNumber(date.getMinutes())}:${padNumber(date.getSeconds())}`;
}

function describeDebugError(error) {
  if (error instanceof Error) {
    return error.message || error.name;
  }
  return String(error || "未知错误");
}

function serializeDebugValue(value, depth = 0) {
  if (value === null || value === undefined) {
    return value;
  }
  if (depth >= 4) {
    return "[已截断]";
  }
  if (value instanceof Error) {
    return {
      name: value.name,
      message: value.message,
      stack: value.stack,
    };
  }
  if (Array.isArray(value)) {
    return value.slice(0, 20).map((item) => serializeDebugValue(item, depth + 1));
  }
  if (typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .slice(0, 30)
        .map(([key, item]) => [key, serializeDebugValue(item, depth + 1)]),
    );
  }
  return value;
}

function normalizeDebugLevel(level) {
  return ["success", "info", "warn", "error"].includes(level) ? level : "info";
}

function getDebugLevelLabel(level) {
  return (
    {
      success: "成功",
      info: "信息",
      warn: "提醒",
      error: "错误",
    }[level] || "信息"
  );
}

function inferToastLevel(message) {
  if (/(失败|错误|未能|异常|不可|关闭|断开)/.test(message)) {
    return "error";
  }
  if (/(请|等待|需要|重新|暂不)/.test(message)) {
    return "warn";
  }
  if (/(成功|已|完成|复制)/.test(message)) {
    return "success";
  }
  return "info";
}

async function copyTextToClipboard(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "readonly");
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand("copy");
  document.body.removeChild(textarea);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
