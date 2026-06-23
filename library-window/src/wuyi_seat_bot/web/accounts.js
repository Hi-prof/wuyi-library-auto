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
