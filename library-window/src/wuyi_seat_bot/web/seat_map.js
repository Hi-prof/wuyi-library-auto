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
