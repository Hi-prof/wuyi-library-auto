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
