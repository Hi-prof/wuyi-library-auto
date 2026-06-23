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
