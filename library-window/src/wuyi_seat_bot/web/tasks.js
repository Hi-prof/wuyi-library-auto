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
