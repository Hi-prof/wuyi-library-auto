package com.wuyi.libraryauto.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.ui.repository.account.AccountBulkImportParser
import com.wuyi.libraryauto.ui.repository.account.AccountExportPayload
import com.wuyi.libraryauto.ui.repository.account.AccountExportRequest
import com.wuyi.libraryauto.ui.repository.account.AccountFilter
import com.wuyi.libraryauto.ui.repository.account.BulkImportDialogState
import com.wuyi.libraryauto.ui.repository.task.AccountSeatAction
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountCardStatus
import com.wuyi.libraryauto.ui.repository.task.AccountStatusRepository
import com.wuyi.libraryauto.ui.repository.task.SeatBookingSnapshotView
import com.wuyi.libraryauto.ui.screen.account.AccountSelectionState
import com.wuyi.libraryauto.ui.screen.account.clear
import com.wuyi.libraryauto.ui.screen.account.isMultiSelectMode
import com.wuyi.libraryauto.ui.screen.account.retainExisting
import com.wuyi.libraryauto.ui.screen.account.selectAll
import com.wuyi.libraryauto.ui.screen.account.selectedIds
import com.wuyi.libraryauto.ui.screen.account.toggle
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AccountManagementViewModel(
    private val accountRepository: SavedAccountRepository,
    private val loginGateway: LoginGateway,
    private val sessionRepository: SessionRepository,
    private val accountStatusRepository: AccountStatusRepository? = null,
    private val accountSeatActionExecutor: AccountSeatActionExecutor? = null,
    private val batchCheckInRunner: BatchCheckInRunner? = null,
    private val batchProgressWriter: BatchCheckInProgressWriter = BatchCheckInProgressWriter.Noop,
    private val accountBulkImportParser: AccountBulkImportParser = AccountBulkImportParser(),
    private val accountFilter: AccountFilter = AccountFilter(),
    private val accountExportPayload: AccountExportPayload = AccountExportPayload(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /**
     * 每天定时自动刷新「所有账号状态」的本地时间点。默认空，表示不启用定时刷新。
     *
     * 仅在 [accountSeatActionExecutor] 非空时启动调度协程；用户手动点「一键刷新」始终不受影响。
     * 该调度只在 ViewModel 存活期间生效，不依赖 WorkManager / 后台进程。
     */
    private val dailyRefreshTimes: List<LocalTime> = emptyList(),
    private val refreshClock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    var uiState by mutableStateOf(AccountManagementUiState())
        private set

    private var batchJob: Job? = null
    private var refreshAllJob: Job? = null
    private val refreshAllMutex = Mutex()
    private val singleRefreshing = mutableSetOf<String>()
    private var lastBatchProgressWriteMillis: Long = 0L
    private var dailyRefreshJob: Job? = null

    init {
        refreshAccounts()
        startDailyRefreshScheduler()
    }

    fun setCurrentAccount(studentId: String) {
        val account = uiState.accounts.firstOrNull { it.studentId == studentId.trim() } ?: return
        if (uiState.pendingStudentId != null) {
            return
        }
        if (sessionRepository.activate(account.studentId)) {
            refreshAccounts()
            return
        }
        viewModelScope.launch {
            uiState =
                uiState.copy(
                    pendingStudentId = account.studentId,
                    pendingAction = AccountPendingAction.SetCurrent,
                    errorMessage = null,
                )
            finishPendingLogin(account)
        }
    }

    fun refreshAuthForAccount(studentId: String) {
        val account = uiState.accounts.firstOrNull { it.studentId == studentId.trim() } ?: return
        if (uiState.pendingStudentId != null) {
            return
        }
        viewModelScope.launch {
            uiState =
                uiState.copy(
                    pendingStudentId = account.studentId,
                    pendingAction = AccountPendingAction.RefreshAuth,
                    errorMessage = null,
                )
            finishPendingLogin(account)
        }
    }

    fun performAccountAction(
        studentId: String,
        action: AccountSeatAction,
        bookingId: String? = null,
    ) {
        if (uiState.pendingStudentId != null) {
            return
        }
        val executor = accountSeatActionExecutor ?: return
        val safeStudentId = studentId.trim()
        val targetBookingId = bookingId?.trim()?.takeIf(String::isNotBlank)
        viewModelScope.launch {
            uiState =
                uiState.copy(
                    pendingStudentId = safeStudentId,
                    pendingAction = action.toPendingAction(),
                    pendingBookingId = targetBookingId,
                    errorMessage = null,
                    actionMessage = null,
                    detailDialog =
                        uiState.detailDialog?.let { dialog ->
                            if (dialog.studentId == safeStudentId) dialog.copy(dialogMessage = null) else dialog
                        },
                )
            val outcome =
                runCatching {
                    executor.performAction(safeStudentId, action, targetBookingId)
                }
            uiState =
                outcome.fold(
                    onSuccess = { result ->
                        uiState.copy(
                            actionMessage = result.message,
                            detailDialog =
                                uiState.detailDialog?.let { dialog ->
                                    if (dialog.studentId == safeStudentId) {
                                        dialog.copy(dialogMessage = result.message)
                                    } else {
                                        dialog
                                    }
                                },
                        )
                    },
                    onFailure = { error ->
                        val message = error.localizedMessage ?: "操作失败，请稍后重试。"
                        uiState.copy(
                            errorMessage = message,
                            detailDialog =
                                uiState.detailDialog?.let { dialog ->
                                    if (dialog.studentId == safeStudentId) {
                                        dialog.copy(dialogMessage = message)
                                    } else {
                                        dialog
                                    }
                                },
                        )
                    },
                )
            uiState = uiState.copy(pendingBookingId = null)
            refreshAccounts()
            // 操作成功时统一在后台拉一遍最新预约列表，避免 UI 还显示已被取消/签退的旧条目。
            // 卡片刷新（loadSingleAccountStatus）和详情对话框刷新走同一逻辑。
            if (outcome.isSuccess) {
                viewModelScope.launch {
                    delay(AUTO_REFRESH_DELAY_MILLIS)
                    refreshSingleAccount(safeStudentId)
                }
                if (uiState.detailDialog?.studentId == safeStudentId) {
                    viewModelScope.launch {
                        delay(AUTO_REFRESH_DELAY_MILLIS)
                        if (uiState.detailDialog?.studentId == safeStudentId) {
                            refreshSingleAccountStatus(safeStudentId)
                        }
                    }
                }
            }
        }
    }

    fun startBatchCheckIn() {
        val runner = batchCheckInRunner ?: return
        val activeJob = batchJob
        if (activeJob?.isActive == true) {
            uiState =
                uiState.copy(
                    batchCheckInState =
                        BatchCheckInState.Cooldown(
                            message = "批量签到正在执行，请稍后再试。",
                            rows = uiState.batchCheckInState.rows,
                        ),
            )
            return
        }
        if (uiState.refreshAllState is AccountRefreshAllState.Running || uiState.selection.isMultiSelectMode()) {
            return
        }
        val rows =
            uiState.accounts.map { account ->
                BatchCheckInRowState(
                    studentId = account.studentId,
                    status = BatchCheckInRowStatus.Pending,
                    message = "等待签到",
                )
            }
        uiState =
            uiState.copy(
                errorMessage = null,
                actionMessage = null,
                batchCheckInState =
                    BatchCheckInState.Running(
                        completed = 0,
                        total = rows.size,
                        rows = rows,
                    ),
            )
        batchJob =
            viewModelScope.launch {
                persistBatchProgress("批量签到开始，共 ${rows.size} 个账号。", force = true)
                val report =
                    runCatching {
                        runner.runBatch { progress ->
                            applyBatchProgress(progress)
                            persistBatchProgress(progress.summaryMessage)
                        }
                    }.getOrElse { error ->
                        BatchCheckInReport(
                            rows = rows.map { row ->
                                row.copy(
                                    status = BatchCheckInRowStatus.Failed,
                                    message = error.message ?: "批量签到失败，请稍后重试。",
                                    canRetry = true,
                                )
                            },
                            summaryMessage = "批量签到失败：${error.message ?: "未知错误"}",
                        )
                    }
                finishBatchCheckIn(report)
            }
    }

    fun retryBatchCheckIn(studentId: String) {
        val runner = batchCheckInRunner ?: return
        if (batchJob?.isActive == true) {
            uiState =
                uiState.copy(
                    batchCheckInState =
                        BatchCheckInState.Cooldown(
                            message = "批量签到正在执行，请稍后再试。",
                            rows = uiState.batchCheckInState.rows,
                        ),
                )
            return
        }
        val rows = uiState.batchCheckInState.rows
        if (rows.none { row -> row.studentId == studentId && row.canRetry }) {
            return
        }
        val runningRows =
            rows.map { row ->
                if (row.studentId == studentId) {
                    row.copy(
                        status = BatchCheckInRowStatus.Running,
                        message = "重试中...",
                        canRetry = false,
                    )
                } else {
                    row
                }
            }
        uiState =
            uiState.copy(
                batchCheckInState =
                    BatchCheckInState.Running(
                        completed = runningRows.count(BatchCheckInRowState::isTerminal),
                        total = runningRows.size,
                        rows = runningRows,
                    ),
            )
        batchJob =
            viewModelScope.launch {
                val result =
                    runCatching { runner.retry(studentId) }
                        .getOrElse { error ->
                            BatchCheckInRowState(
                                studentId = studentId,
                                status = BatchCheckInRowStatus.Failed,
                                message = error.message ?: "重试失败，请稍后再试。",
                                canRetry = true,
                            )
                        }
                val updatedRows =
                    runningRows.map { row ->
                        if (row.studentId == studentId) result else row
                    }
                finishBatchCheckIn(
                    BatchCheckInReport(
                        rows = updatedRows,
                        summaryMessage = buildBatchSummary(updatedRows),
                    ),
                )
            }
    }

    fun removeAccount(studentId: String) {
        accountRepository.remove(studentId)
        sessionRepository.remove(studentId)
        refreshAccounts(
            expandedStudentId = uiState.expandedStudentId.takeUnless { it == studentId },
            pendingDeleteStudentId = null,
        )
    }

    fun toggleAccountExpanded(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank() || uiState.accounts.none { it.studentId == safeStudentId }) {
            return
        }
        uiState =
            uiState.copy(
                expandedStudentId =
                    if (uiState.expandedStudentId == safeStudentId) {
                        null
                    } else {
                        safeStudentId
                    },
            )
    }

    fun requestRemoveAccount(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank() || uiState.accounts.none { it.studentId == safeStudentId }) {
            return
        }
        uiState = uiState.copy(pendingDeleteStudentId = safeStudentId)
    }

    fun cancelRemoveAccount() {
        if (uiState.pendingDeleteStudentId == null) {
            return
        }
        uiState = uiState.copy(pendingDeleteStudentId = null)
    }

    fun confirmRemoveAccount() {
        val studentId = uiState.pendingDeleteStudentId ?: return
        removeAccount(studentId)
    }

    fun refreshAccounts(
        expandedStudentId: String? = uiState.expandedStudentId,
        pendingDeleteStudentId: String? = uiState.pendingDeleteStudentId,
        selection: AccountSelectionState = uiState.selection,
    ) {
        val baseAccounts = buildBaseAccounts()
        val safeExpandedStudentId = expandedStudentId.takeIf { studentId ->
            baseAccounts.any { account -> account.studentId == studentId }
        }
        val safePendingDeleteStudentId = pendingDeleteStudentId.takeIf { studentId ->
            baseAccounts.any { account -> account.studentId == studentId }
        }
        val existingIds = baseAccounts.map(SavedAccountEntry::studentId).toSet()
        uiState =
            uiState.copy(
                accounts = baseAccounts,
                pendingStudentId = null,
                pendingAction = null,
                actionMessage = uiState.actionMessage,
                expandedStudentId = safeExpandedStudentId,
                pendingDeleteStudentId = safePendingDeleteStudentId,
                selection = selection.retainExisting(existingIds),
            ).withVisibleStudentIds()
    }

    fun refreshAllAccountsManually() {
        // 用户主动触发：与定时调度共用同一实现，区别仅在于由谁触发。
        runRefreshAllAccounts()
    }

    private fun runRefreshAllAccounts() {
        if (refreshAllJob?.isActive == true || batchJob?.isActive == true || uiState.selection.isMultiSelectMode()) {
            return
        }
        val baseAccounts = buildBaseAccounts()
        if (baseAccounts.isEmpty()) {
            return
        }
        uiState =
            uiState.copy(
                accounts = baseAccounts,
                refreshAllState = AccountRefreshAllState.Running(completed = 0, total = baseAccounts.size),
                errorMessage = null,
                actionMessage = null,
            ).withVisibleStudentIds()
        refreshAllJob =
            viewModelScope.launch {
                if (!refreshAllMutex.tryLock()) {
                    return@launch
                }
                try {
                    val completed =
                        withTimeoutOrNull(REFRESH_ALL_TIMEOUT_MILLIS) {
                            // 并发拉取每个账号的座位状态。Semaphore 限制学校接口同时在线请求数；
                            // 单账号 withTimeoutOrNull 兜底，避免一个慢账号拖死整体进度。
                            // uiStateMutex 用来串行化并发回写 uiState，避免 .copy 链上的竞态。
                            val gate = Semaphore(REFRESH_ALL_PARALLELISM)
                            val uiStateMutex = Mutex()
                            val finished = java.util.concurrent.atomic.AtomicInteger(0)
                            coroutineScope {
                                baseAccounts
                                    .map { account ->
                                        async {
                                            val refreshed =
                                                gate.withPermit {
                                                    withTimeoutOrNull(SINGLE_REFRESH_TIMEOUT_MILLIS) {
                                                        withContext(ioDispatcher) {
                                                            loadSingleAccountStatus(account)
                                                        }
                                                    } ?: account.copy(statusSummary = "账号状态刷新超时，请稍后重试。")
                                                }
                                            uiStateMutex.lock()
                                            try {
                                                replaceAccount(refreshed)
                                                val done = finished.incrementAndGet()
                                                uiState =
                                                    uiState.copy(
                                                        refreshAllState =
                                                            AccountRefreshAllState.Running(
                                                                completed = done,
                                                                total = baseAccounts.size,
                                                            ),
                                                    ).withVisibleStudentIds()
                                            } finally {
                                                uiStateMutex.unlock()
                                            }
                                        }
                                    }.awaitAll()
                            }
                            true
                        } == true
                    val message =
                        if (completed) {
                            "账号状态刷新完成。"
                        } else {
                            "账号状态刷新超时，请稍后重试。"
                        }
                    uiState =
                        uiState.copy(
                            refreshAllState = AccountRefreshAllState.Cooldown(message),
                            actionMessage = message,
                        ).withVisibleStudentIds()
                } finally {
                    refreshAllMutex.unlock()
                }
            }
    }

    /**
     * 在 ViewModel 存活期间，按 [dailyRefreshTimes] 配置的本地时间点（如 07:30 / 08:10）
     * 自动触发一次「所有账号状态刷新」。
     *
     * - 不做节流；如果同一时间点 ViewModel 多次实例化（页面被销毁 + 重建），会按 ViewModel
     *   生命周期分别调度，与"用户进入页面才请求"的旧行为相比仍然显著降低请求频率。
     * - [accountSeatActionExecutor] 为 null（如部分单测构造）或 [dailyRefreshTimes] 为空
     *   时直接 no-op，不启动协程。
     */
    private fun startDailyRefreshScheduler() {
        if (accountSeatActionExecutor == null || dailyRefreshTimes.isEmpty()) {
            return
        }
        val sortedTimes = dailyRefreshTimes.sorted()
        dailyRefreshJob?.cancel()
        dailyRefreshJob =
            viewModelScope.launch {
                while (isActive) {
                    val now = ZonedDateTime.now(refreshClock)
                    val nextRun = nextRunAt(now, sortedTimes)
                    val delayMillis =
                        java.time.Duration.between(now, nextRun).toMillis().coerceAtLeast(0L)
                    delay(delayMillis)
                    runRefreshAllAccounts()
                    // 触发后立刻回到 while 顶部，由 nextRunAt 计算下一时间点；为避免同一时间点
                    // 因执行耗时小于 1ms 被重复触发，再追加 1 秒最小延迟。
                    delay(1_000L)
                }
            }
    }

    private fun nextRunAt(
        now: ZonedDateTime,
        sortedTimes: List<LocalTime>,
    ): ZonedDateTime {
        val today = now.toLocalDate()
        val candidate =
            sortedTimes
                .map { time -> ZonedDateTime.of(today, time, now.zone) }
                .firstOrNull { candidate -> candidate.isAfter(now) }
        return candidate ?: ZonedDateTime.of(today.plusDays(1), sortedTimes.first(), now.zone)
    }

    fun refreshSingleAccount(studentId: String) {
        val safeStudentId = studentId.trim()
        val account = uiState.accounts.firstOrNull { it.studentId == safeStudentId } ?: return
        if (!singleRefreshing.add(safeStudentId)) {
            return
        }
        uiState = uiState.copy(singleRefreshing = singleRefreshing.toSet())
        viewModelScope.launch {
            try {
                val refreshed =
                    withTimeoutOrNull(SINGLE_REFRESH_TIMEOUT_MILLIS) {
                        withContext(ioDispatcher) { loadSingleAccountStatus(account) }
                    } ?: account.copy(statusSummary = "账号状态刷新超时，请稍后重试。")
                replaceAccount(refreshed)
            } finally {
                singleRefreshing.remove(safeStudentId)
                uiState = uiState.copy(singleRefreshing = singleRefreshing.toSet())
            }
        }
    }

    fun openAccountDetail(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) {
            return
        }
        if (uiState.accounts.none { it.studentId == safeStudentId }) {
            return
        }
        if (uiState.selection.isMultiSelectMode()) {
            toggleAccountSelection(safeStudentId)
            return
        }
        uiState =
            uiState.copy(
                detailDialog =
                    AccountDetailDialogState(
                        studentId = safeStudentId,
                        openedAtNanos = System.nanoTime(),
                    ),
            )
    }

    fun closeAccountDetail() {
        if (uiState.detailDialog == null && uiState.expandedStudentId == null) {
            return
        }
        uiState =
            uiState.copy(
                detailDialog = null,
                expandedStudentId = null,
            )
    }

    /**
     * 详情对话框中"查看自动任务"复合操作：先关闭详情对话框（保证 [AccountManagementUiState.detailDialog]
     * 为 null）后再调用导航回调，避免 dialog 残留干扰任务页路由。
     *
     * @param studentId 详情对话框中正在展示的账号学号；空白学号视为非法输入直接返回，不触发导航。
     * @param navigator 由 Compose 层提供的导航回调，通常等价于 `onOpenTasksForAccount`。
     */
    fun openTasksFromDetail(
        studentId: String,
        navigator: (String) -> Unit,
    ) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) {
            return
        }
        closeAccountDetail()
        navigator(safeStudentId)
    }

    /**
     * 详情对话框中"删除账号"复合操作：先关闭详情对话框再复用既有 [requestRemoveAccount]
     * 触发"确认删除账号"二次确认流程；不直接调用 [removeAccount]。
     */
    fun requestRemoveAccountFromDetail(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) {
            return
        }
        closeAccountDetail()
        requestRemoveAccount(safeStudentId)
    }

    /**
     * 详情对话框互斥状态判定：当 [AccountManagementUiState.pendingAction] 不为空且
     * [AccountManagementUiState.pendingStudentId] 命中详情对话框当前展示的账号时返回 true。
     *
     * Compose 端可据此把"设为当前账号 / 刷新认证 / 座位动作"等按钮设为 disabled，并切换为
     * "签到中... / 签退中... / 取消中... / 刷新中... / 设置中..."文案（见 [pendingActionLabel]）。
     */
    internal fun isPendingActionForDetail(detailStudentId: String): Boolean =
        uiState.pendingAction != null && uiState.pendingStudentId == detailStudentId

    fun refreshSingleAccountStatus(studentId: String) {
        val safeStudentId = studentId.trim()
        val account = uiState.accounts.firstOrNull { it.studentId == safeStudentId } ?: return
        if (!singleRefreshing.add(safeStudentId)) {
            return
        }
        uiState =
            uiState.copy(
                singleRefreshing = singleRefreshing.toSet(),
                detailDialog =
                    uiState.detailDialog?.copy(
                        refreshing = true,
                        dialogMessage = null,
                    ),
            )
        viewModelScope.launch {
            var failureMessage: String? = null
            try {
                val refreshed =
                    withTimeoutOrNull(SINGLE_REFRESH_TIMEOUT_MILLIS) {
                        withContext(ioDispatcher) { loadSingleAccountStatus(account) }
                    } ?: account.copy(statusSummary = "账号状态刷新超时，请稍后重试。")
                replaceAccount(refreshed)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failureMessage = error.localizedMessage ?: "刷新失败，请稍后重试"
            } finally {
                singleRefreshing.remove(safeStudentId)
                uiState =
                    uiState.copy(
                        singleRefreshing = singleRefreshing.toSet(),
                        detailDialog =
                            uiState.detailDialog?.copy(
                                refreshing = false,
                                dialogMessage = failureMessage ?: "已刷新",
                            ),
                    )
            }
        }
    }

    fun openBulkImportDialog() {
        if (uiState.bulkImportDialog.isSubmitting) {
            return
        }
        uiState = uiState.copy(bulkImportDialog = BulkImportDialogState(isVisible = true))
    }

    fun closeBulkImportDialog() {
        if (uiState.bulkImportDialog.isSubmitting) {
            return
        }
        uiState = uiState.copy(bulkImportDialog = BulkImportDialogState())
    }

    fun updateBulkImportRawText(rawText: String) {
        val dialog = uiState.bulkImportDialog
        if (!dialog.isVisible || dialog.isSubmitting) {
            return
        }
        uiState = uiState.copy(bulkImportDialog = dialog.copy(rawText = rawText, result = null))
    }

    fun submitBulkImport() {
        val dialog = uiState.bulkImportDialog
        if (!dialog.isVisible || dialog.isSubmitting) {
            return
        }
        uiState = uiState.copy(bulkImportDialog = dialog.copy(isSubmitting = true))
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    val existingIds = accountRepository.readAll().map(SavedAccountEntry::studentId).toSet()
                    val parseResult = accountBulkImportParser.parse(dialog.rawText, existingIds)
                    if (parseResult.rejectedByCap == null) {
                        parseResult.accepted.forEach(accountRepository::saveImported)
                    }
                    parseResult
                }
            val accounts = buildBaseAccounts()
            uiState =
                uiState.copy(
                    accounts = accounts,
                    bulkImportDialog =
                        BulkImportDialogState(
                            isVisible = true,
                            rawText = "",
                            isSubmitting = false,
                            result = result,
                        ),
                    actionMessage = if (result.acceptedCount > 0) "已导入 ${result.acceptedCount} 个账号。" else uiState.actionMessage,
                ).withVisibleStudentIds()
        }
    }

    fun enterMultiSelectMode() {
        uiState =
            if (uiState.selection is AccountSelectionState.Idle) {
                uiState.copy(selection = AccountSelectionState.MultiSelect())
            } else if (uiState.selection.selectedIds().isEmpty()) {
                uiState.copy(selection = uiState.selection.clear())
            } else {
                uiState
            }
    }

    fun exitMultiSelectMode() {
        uiState = uiState.copy(selection = uiState.selection.clear())
    }

    fun toggleAccountSelection(studentId: String) {
        if (uiState.selection is AccountSelectionState.Idle) {
            return
        }
        uiState = uiState.copy(selection = uiState.selection.toggle(studentId))
    }

    fun selectAllVisible() {
        if (uiState.selection is AccountSelectionState.Idle) {
            return
        }
        uiState = uiState.copy(selection = uiState.selection.selectAll(uiState.visibleStudentIds))
    }

    fun requestBulkDelete() {
        val targets = uiState.selection.selectedIds()
        if (targets.isEmpty()) {
            return
        }
        uiState = uiState.copy(selection = AccountSelectionState.ConfirmDelete(targets))
    }

    fun confirmBulkDelete() {
        val targets = (uiState.selection as? AccountSelectionState.ConfirmDelete)?.targets ?: return
        targets.forEach { studentId ->
            accountRepository.remove(studentId)
            sessionRepository.remove(studentId)
        }
        refreshAccounts(
            expandedStudentId = uiState.expandedStudentId?.takeUnless { it in targets },
            pendingDeleteStudentId = null,
            selection = AccountSelectionState.Idle,
        )
    }

    fun cancelBulkDelete() {
        val targets = (uiState.selection as? AccountSelectionState.ConfirmDelete)?.targets ?: return
        uiState = uiState.copy(selection = AccountSelectionState.MultiSelect(targets))
    }

    fun buildExportIntentPayload(): AccountExportRequest? {
        val selectedIds = uiState.selection.selectedIds()
        if (selectedIds.isEmpty()) {
            return null
        }
        val accounts = uiState.accounts.filter { account -> account.studentId in selectedIds }
        return accountExportPayload.build(accounts)
    }

    fun updateSearchQuery(query: String) {
        uiState = uiState.copy(searchQuery = query).withVisibleStudentIds()
    }

    fun clearSearchQuery() {
        if (uiState.searchQuery.isNotEmpty()) {
            uiState = uiState.copy(searchQuery = "").withVisibleStudentIds()
        }
    }

    fun clearError() {
        if (uiState.errorMessage != null) {
            uiState = uiState.copy(errorMessage = null)
        }
    }

    fun clearActionMessage() {
        if (uiState.actionMessage != null) {
            uiState = uiState.copy(actionMessage = null)
        }
    }

    private suspend fun loadSingleAccountStatus(account: SavedAccountEntry): SavedAccountEntry =
        runCatching {
            val executor = accountSeatActionExecutor
            if (executor != null) {
                // 一次拿到全部活跃预约：聚合一条用于 mergeStatus（卡片摘要 / 详情对话框聚合按钮），
                // 并把整个列表塞给 activeBookings 字段，让 UI 给每条预约单独提供操作按钮。
                val bookings = executor.loadActiveBookings(account.studentId)
                val needLogin =
                    bookings.firstOrNull { it.liveState == SeatBookingLiveState.NEED_LOGIN }
                if (needLogin != null) {
                    // 与执行 performAction 时同样的「登录态失效」语义；返回带提示的 IDLE 视图，
                    // 不抛异常，避免账号列表把已认证账号标成「未认证」。
                    return account.copy(
                        statusSummary =
                            needLogin.statusLabel.ifBlank { "登录态已失效，请刷新认证后重试。" },
                        currentBookingLabel = "",
                        primaryAction = null,
                        primaryActionLabel = "",
                        primaryActionEnabled = false,
                        secondaryAction = null,
                        secondaryActionLabel = "",
                        actionHint = "",
                        activeBookings = emptyList(),
                    )
                }
                val aggregateSnapshot =
                    if (bookings.isNotEmpty()) {
                        // 复用既有 priority：待签到优先于已签到；详情对话框只展示一条聚合状态。
                        bookings.minWithOrNull(
                            compareBy(
                                { booking ->
                                    when (booking.liveState) {
                                        SeatBookingLiveState.RESERVED_WAITING_SIGNIN ->
                                            if (booking.checkinWindowOpen) 0 else 2
                                        SeatBookingLiveState.ACTIVE_SIGNED_IN -> 1
                                        else -> 3
                                    }
                                },
                                { booking -> booking.beginLabel },
                            ),
                        )
                    } else {
                        null
                    }
                val cardStatus = aggregateSnapshot?.toAccountCardStatus(account)
                account.mergeStatus(cardStatus).copy(
                    activeBookings = bookings.map { it.toAccountBookingEntry() },
                )
            } else {
                val status = accountStatusRepository?.load()?.firstOrNull { it.studentId == account.studentId }
                account.mergeStatus(status)
            }
        }.getOrElse { error ->
            account.copy(
                statusSummary = error.message ?: "账号状态刷新失败，请稍后重试。",
                currentBookingLabel = "",
                primaryAction = null,
                primaryActionLabel = "",
                primaryActionEnabled = false,
                secondaryAction = null,
                secondaryActionLabel = "",
                actionHint = "",
                activeBookings = emptyList(),
            )
        }

    private fun replaceAccount(account: SavedAccountEntry) {
        uiState =
            uiState.copy(
                accounts =
                    uiState.accounts.map { current ->
                        if (current.studentId == account.studentId) account else current
                    },
            ).withVisibleStudentIds()
    }

    private fun AccountManagementUiState.withVisibleStudentIds(): AccountManagementUiState {
        val visibleIds = accountFilter.filter(accounts, searchQuery).map(SavedAccountEntry::studentId)
        val existingIds = accounts.map(SavedAccountEntry::studentId).toSet()
        return copy(
            visibleStudentIds = visibleIds,
            selection = selection.retainExisting(existingIds),
        )
    }

    private fun SeatBookingSnapshotView.toAccountCardStatus(account: SavedAccountEntry): AccountCardStatus =
        AccountCardStatus(
            studentId = account.studentId,
            preferredSeatLabel = buildPreferredSeatLabel(account),
            isCurrent = account.studentId == sessionRepository.activeStudentId(),
            isAuthenticated = sessionRepository.currentSession(account.studentId) != null,
            latestPlanMessage = account.latestPlanMessage,
            latestTaskState = account.latestTaskState,
            liveState = liveState.toCardState(),
            statusLabel = statusLabel.ifBlank { liveState.toStatusLabel() },
            currentBookingLabel =
                listOf(roomName, seatNumber, beginLabel)
                    .filter(String::isNotBlank)
                    .joinToString(" / "),
            checkinWindowOpen = checkinWindowOpen,
            primaryAction = buildPrimaryAction(liveState),
            secondaryAction = buildSecondaryAction(liveState),
        )

    private suspend fun finishPendingLogin(account: SavedAccountEntry) {
        uiState =
            when (val result = loginGateway.login(account.studentId, account.password)) {
                LoginResult.Success ->
                    uiState.copy(
                        errorMessage = null,
                        actionMessage = null,
                    )

                is LoginResult.Failure ->
                    uiState.copy(
                        errorMessage = result.message,
                    )
            }
        refreshAccounts()
    }

    private fun buildBaseAccounts(): List<SavedAccountEntry> {
        val activeStudentId = sessionRepository.activeStudentId()
        // 保留之前已经拉取过的预约状态：repo.readAll() 只回放学号 / 密码 / 偏好等持久化字段，
        // activeBookings / primaryAction / currentBookingLabel 等运行期字段依靠 ViewModel 累积。
        // 如果这里直接覆盖，每次 refreshAccounts() 都会让卡片上的预约信息瞬间消失再补上。
        val existingByStudentId = uiState.accounts.associateBy(SavedAccountEntry::studentId)
        return accountRepository.readAll().map { account ->
            val previous = existingByStudentId[account.studentId]
            val isAuthenticated = sessionRepository.currentSession(account.studentId) != null
            val isActive = account.studentId == activeStudentId
            account.copy(
                isAuthenticated = isAuthenticated,
                isActive = isActive,
                preferredSeatLabel = buildPreferredSeatLabel(account),
                latestPlanMessage = previous?.latestPlanMessage.orEmpty().ifBlank { account.latestPlanMessage },
                latestTaskState = previous?.latestTaskState.orEmpty().ifBlank { account.latestTaskState },
                statusSummary = previous?.statusSummary?.takeIf { it.isNotBlank() }
                    ?: buildStatusSummary(
                        isAuthenticated = isAuthenticated,
                        isCurrent = isActive,
                        latestPlanMessage = account.latestPlanMessage,
                        latestTaskState = account.latestTaskState,
                    ),
                currentBookingLabel = previous?.currentBookingLabel.orEmpty(),
                primaryAction = previous?.primaryAction,
                primaryActionLabel = previous?.primaryActionLabel.orEmpty(),
                primaryActionEnabled = previous?.primaryActionEnabled ?: false,
                secondaryAction = previous?.secondaryAction,
                secondaryActionLabel = previous?.secondaryActionLabel.orEmpty(),
                actionHint = previous?.actionHint.orEmpty(),
                activeBookings = previous?.activeBookings.orEmpty(),
            )
        }
    }

    private fun buildPreferredSeatLabel(account: SavedAccountEntry): String =
        listOf(account.preferredRoomName, account.preferredSeatNumber)
            .filter(String::isNotBlank)
            .joinToString(" / ")

    private fun buildStatusSummary(
        isAuthenticated: Boolean,
        isCurrent: Boolean,
        latestPlanMessage: String,
        latestTaskState: String,
    ): String =
        when {
            !isAuthenticated -> "当前未认证，请先刷新认证。"
            latestPlanMessage.isNotBlank() -> latestPlanMessage
            latestTaskState.isNotBlank() -> "最近任务状态：$latestTaskState"
            isCurrent -> "当前账号已认证，可直接用于手动预约和自动任务。"
            else -> "已认证，暂无自动任务。"
        }

    private fun buildPrimaryAction(liveState: SeatBookingLiveState): AccountSeatAction? =
        when (liveState) {
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> AccountSeatAction.CheckIn
            SeatBookingLiveState.ACTIVE_SIGNED_IN -> AccountSeatAction.Checkout
            else -> null
        }

    private fun buildSecondaryAction(liveState: SeatBookingLiveState): AccountSeatAction? =
        when (liveState) {
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> AccountSeatAction.CancelBooking
            // 已签到状态下不再提供「取消预约」语义；学校接口的对应动作是签退（由 primaryAction 提供）。
            else -> null
        }

    private fun SeatBookingLiveState.toStatusLabel(): String =
        when (this) {
            SeatBookingLiveState.NEED_LOGIN -> "需登录"
            SeatBookingLiveState.IDLE -> "暂无预约"
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> "待签到"
            SeatBookingLiveState.ACTIVE_SIGNED_IN -> "已签到"
            SeatBookingLiveState.FINISHED_OR_HISTORY -> "最近记录已结束"
        }

    private fun SeatBookingLiveState.toCardState(): String =
        when (this) {
            SeatBookingLiveState.NEED_LOGIN -> "need-login"
            SeatBookingLiveState.IDLE -> "idle"
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> "reserved-waiting-signin"
            SeatBookingLiveState.ACTIVE_SIGNED_IN -> "active-signed-in"
            SeatBookingLiveState.FINISHED_OR_HISTORY -> "finished-or-history"
        }

    private fun SavedAccountEntry.mergeStatus(status: AccountCardStatus?): SavedAccountEntry =
        copy(
            isAuthenticated = status?.isAuthenticated ?: isAuthenticated,
            isActive = status?.isCurrent ?: isActive,
            preferredSeatLabel = status?.preferredSeatLabel.takeUnless { it.isNullOrBlank() } ?: preferredSeatLabel,
            latestPlanMessage = status?.latestPlanMessage.orEmpty(),
            latestTaskState = status?.latestTaskState.orEmpty(),
            statusSummary =
                status?.statusLabel?.ifBlank {
                    buildStatusSummary(
                        isAuthenticated = status.isAuthenticated,
                        isCurrent = status.isCurrent,
                        latestPlanMessage = status.latestPlanMessage,
                        latestTaskState = status.latestTaskState,
                    )
                } ?: buildStatusSummary(
                    isAuthenticated = isAuthenticated,
                    isCurrent = isActive,
                    latestPlanMessage = latestPlanMessage,
                    latestTaskState = latestTaskState,
                ),
            currentBookingLabel = status?.currentBookingLabel.orEmpty(),
            primaryAction = status?.primaryAction,
            primaryActionLabel =
                when (status?.primaryAction) {
                    AccountSeatAction.CheckIn -> "立即签到"
                    AccountSeatAction.Checkout -> "签退"
                    else -> ""
                },
            primaryActionEnabled =
                when (status?.primaryAction) {
                    AccountSeatAction.CheckIn -> status.checkinWindowOpen
                    null -> false
                    else -> true
                },
            secondaryAction = status?.secondaryAction,
            secondaryActionLabel =
                when (status?.secondaryAction) {
                    AccountSeatAction.CancelBooking -> "取消预约"
                    else -> ""
                },
            actionHint =
                if (status?.primaryAction == AccountSeatAction.CheckIn && !status.checkinWindowOpen) {
                    "未到签到时间"
                } else {
                    ""
                },
        )

    private fun AccountSeatAction.toPendingAction(): AccountPendingAction =
        when (this) {
            AccountSeatAction.CheckIn -> AccountPendingAction.CheckIn
            AccountSeatAction.CancelBooking -> AccountPendingAction.CancelBooking
            AccountSeatAction.Checkout -> AccountPendingAction.Checkout
        }

    /**
     * 把 [SeatBookingSnapshotView] 转成卡片单条预约视图：
     * - 待签到 -> 主操作「立即签到」（仅签到窗口开启时启用）+ 次操作「取消预约」
     * - 已签到 -> 主操作「签退」+ 不再提供取消预约
     * - 其它状态（NEED_LOGIN / IDLE / FINISHED_OR_HISTORY）不会进入活跃列表，理论上不会到这里。
     */
    private fun SeatBookingSnapshotView.toAccountBookingEntry(): AccountBookingEntry {
        val primary =
            when (liveState) {
                SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> AccountSeatAction.CheckIn
                SeatBookingLiveState.ACTIVE_SIGNED_IN -> AccountSeatAction.Checkout
                else -> null
            }
        val secondary =
            when (liveState) {
                SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> AccountSeatAction.CancelBooking
                else -> null
            }
        val primaryLabel =
            when (primary) {
                AccountSeatAction.CheckIn -> "立即签到"
                AccountSeatAction.Checkout -> "签退"
                else -> ""
            }
        val secondaryLabel =
            when (secondary) {
                AccountSeatAction.CancelBooking -> "取消预约"
                else -> ""
            }
        val primaryEnabled =
            when (primary) {
                AccountSeatAction.CheckIn -> checkinWindowOpen
                null -> false
                else -> true
            }
        val hint =
            if (primary == AccountSeatAction.CheckIn && !checkinWindowOpen) "未到签到时间" else ""
        return AccountBookingEntry(
            bookingId = bookingId.orEmpty(),
            roomName = roomName,
            seatNumber = seatNumber,
            beginLabel = beginLabel,
            statusLabel = statusLabel.ifBlank { liveState.toStatusLabel() },
            liveState = liveState,
            checkinWindowOpen = checkinWindowOpen,
            primaryAction = primary,
            primaryActionLabel = primaryLabel,
            primaryActionEnabled = primaryEnabled,
            secondaryAction = secondary,
            secondaryActionLabel = secondaryLabel,
            actionHint = hint,
        )
    }

    private fun applyBatchProgress(progress: BatchCheckInReport) {
        uiState =
            uiState.copy(
                batchCheckInState =
                    BatchCheckInState.Running(
                        completed = progress.rows.count(BatchCheckInRowState::isTerminal),
                        total = progress.rows.size,
                        rows = progress.rows,
                    ),
            )
    }

    private suspend fun finishBatchCheckIn(report: BatchCheckInReport) {
        val rows = report.rows
        val summary = report.summaryMessage.ifBlank { buildBatchSummary(rows) }
        uiState =
            uiState.copy(
                actionMessage = summary,
                batchCheckInState =
                    BatchCheckInState.Cooldown(
                        message = summary,
                        rows = rows,
                    ),
            )
        persistBatchProgress(summary, force = true)
        refreshAccounts()
    }

    private suspend fun persistBatchProgress(
        message: String,
        force: Boolean = false,
    ) {
        val now = clockMillis()
        if (!force && now - lastBatchProgressWriteMillis < BATCH_PROGRESS_WRITE_INTERVAL_MILLIS) {
            return
        }
        lastBatchProgressWriteMillis = now
        batchProgressWriter.record(message.ensureBatchPrefix())
    }

    private fun String.ensureBatchPrefix(): String =
        if (startsWith(BATCH_SUMMARY_PREFIX)) this else "$BATCH_SUMMARY_PREFIX$this"

    private fun buildBatchSummary(rows: List<BatchCheckInRowState>): String {
        val success = rows.count { row -> row.status == BatchCheckInRowStatus.Success }
        val failed = rows.count { row -> row.status == BatchCheckInRowStatus.Failed }
        val skipped = rows.count { row -> row.status == BatchCheckInRowStatus.Skipped }
        return "批量签到完成：成功 $success，失败 $failed，跳过 $skipped。"
    }

    private companion object {
        private const val BATCH_PROGRESS_WRITE_INTERVAL_MILLIS = 5_000L
        private const val BATCH_SUMMARY_PREFIX = "批量签到"
        private const val SINGLE_REFRESH_TIMEOUT_MILLIS = 15_000L
        private const val REFRESH_ALL_TIMEOUT_MILLIS = 60_000L
        private const val AUTO_REFRESH_DELAY_MILLIS = 1_000L
        // 并发刷新所有账号时的同时在线请求数；单账号一次刷新约 1-2 个 HTTP 请求，
        // 4 路并发对学校接口压力可控且能显著缩短整体等待。
        private const val REFRESH_ALL_PARALLELISM = 4
    }
}

data class AccountManagementUiState(
    val accounts: List<SavedAccountEntry> = emptyList(),
    val pendingStudentId: String? = null,
    val pendingAction: AccountPendingAction? = null,
    /**
     * 当前进行中的座位动作所针对的预约 ID。
     *
     * 同一账号可能同时持有多条预约（例如今天 + 明天 + 后天），UI 需要区分用户在哪一行
     * 上点击了「取消预约 / 签到 / 签退」，否则旁边几行的按钮状态会被一起误判为「进行中」。
     * 当 [pendingAction] 命中卡片聚合按钮（不指定具体 bookingId）时本字段保持 null。
     */
    val pendingBookingId: String? = null,
    val errorMessage: String? = null,
    val actionMessage: String? = null,
    val expandedStudentId: String? = null,
    val pendingDeleteStudentId: String? = null,
    val batchCheckInState: BatchCheckInState = BatchCheckInState.Idle,
    val refreshAllState: AccountRefreshAllState = AccountRefreshAllState.Idle,
    val singleRefreshing: Set<String> = emptySet(),
    val bulkImportDialog: BulkImportDialogState = BulkImportDialogState(),
    val selection: AccountSelectionState = AccountSelectionState.Idle,
    val searchQuery: String = "",
    val visibleStudentIds: List<String> = emptyList(),
    val detailDialog: AccountDetailDialogState? = null,
) {
    val isAnyBatchActive: Boolean
        get() = refreshAllState is AccountRefreshAllState.Running || batchCheckInState is BatchCheckInState.Running
}

data class AccountDetailDialogState(
    val studentId: String,
    val openedAtNanos: Long,
    val refreshing: Boolean = false,
    val dialogMessage: String? = null,
)

data class SavedAccountEntry(
    val studentId: String,
    val password: String,
    val preferredRoomName: String = "",
    val preferredSeatNumber: String = "",
    val preferredSeatLabel: String = "",
    val isAuthenticated: Boolean = false,
    val isActive: Boolean = false,
    val latestPlanMessage: String = "",
    val latestTaskState: String = "",
    val statusSummary: String = "",
    val currentBookingLabel: String = "",
    val primaryAction: AccountSeatAction? = null,
    val primaryActionLabel: String = "",
    val primaryActionEnabled: Boolean = false,
    val secondaryAction: AccountSeatAction? = null,
    val secondaryActionLabel: String = "",
    val actionHint: String = "",
    /**
     * 该账号当前全部活跃预约（待签到 + 已签到）。
     *
     * 与 [primaryAction]/[secondaryAction] 的差异：那两个字段是「最相关一条」的
     * 聚合操作，主要给批量签到/详情对话框使用；本字段保留逐条预约信息，
     * 便于卡片展开后给每条预约单独提供取消/签到/签退按钮。
     */
    val activeBookings: List<AccountBookingEntry> = emptyList(),
)

/**
 * 用于在账号卡片上独立呈现的「单条预约」视图模型。
 *
 * 字段命名与 [SavedAccountEntry] 中聚合字段保持一致语义：
 * - [primaryAction] 优先展示「签到 / 签退」中可执行的一项；签到时 [primaryActionEnabled]
 *   仅在签到窗口打开时为 true，避免提前点击。
 * - [secondaryAction] 用于「待签到」状态下的「取消预约」入口；已签到状态下不应提供
 *   取消（学校接口语义改为签退）。
 */
data class AccountBookingEntry(
    val bookingId: String,
    val roomName: String,
    val seatNumber: String,
    val beginLabel: String,
    val statusLabel: String,
    val liveState: SeatBookingLiveState = SeatBookingLiveState.IDLE,
    val checkinWindowOpen: Boolean = false,
    val primaryAction: AccountSeatAction? = null,
    val primaryActionLabel: String = "",
    val primaryActionEnabled: Boolean = false,
    val secondaryAction: AccountSeatAction? = null,
    val secondaryActionLabel: String = "",
    val actionHint: String = "",
)

enum class AccountPendingAction {
    SetCurrent,
    RefreshAuth,
    CheckIn,
    CancelBooking,
    Checkout,
}

/**
 * 详情对话框中按钮"进行中"文案映射。
 *
 * - [AccountPendingAction.CheckIn]      -> "签到中..."
 * - [AccountPendingAction.Checkout]     -> "签退中..."
 * - [AccountPendingAction.CancelBooking] -> "取消中..."
 * - [AccountPendingAction.RefreshAuth]  -> "刷新中..."
 * - [AccountPendingAction.SetCurrent]   -> "设置中..."
 *
 * 当 [action] 为 null 或与待匹配语义不符时返回 [default]，由调用方决定回退文案。
 */
internal fun pendingActionLabel(action: AccountPendingAction?, default: String): String? =
    when (action) {
        AccountPendingAction.CheckIn -> "签到中..."
        AccountPendingAction.Checkout -> "签退中..."
        AccountPendingAction.CancelBooking -> "取消中..."
        AccountPendingAction.RefreshAuth -> "刷新中..."
        AccountPendingAction.SetCurrent -> "设置中..."
        null -> default
    }

sealed class AccountRefreshAllState {
    object Idle : AccountRefreshAllState()

    data class Running(
        val completed: Int,
        val total: Int,
    ) : AccountRefreshAllState()

    data class Cooldown(
        val message: String,
    ) : AccountRefreshAllState()
}

sealed class BatchCheckInState {
    open val rows: List<BatchCheckInRowState> = emptyList()

    object Idle : BatchCheckInState()

    data class Running(
        val completed: Int,
        val total: Int,
        override val rows: List<BatchCheckInRowState>,
    ) : BatchCheckInState()

    data class Cooldown(
        val message: String,
        override val rows: List<BatchCheckInRowState>,
    ) : BatchCheckInState()
}

data class BatchCheckInRowState(
    val studentId: String,
    val status: BatchCheckInRowStatus,
    val message: String,
    val canRetry: Boolean = status == BatchCheckInRowStatus.Failed,
) {
    fun isTerminal(): Boolean =
        status == BatchCheckInRowStatus.Success ||
            status == BatchCheckInRowStatus.Failed ||
            status == BatchCheckInRowStatus.Skipped
}

enum class BatchCheckInRowStatus {
    Pending,
    Running,
    Success,
    Failed,
    Skipped,
}

data class BatchCheckInReport(
    val rows: List<BatchCheckInRowState>,
    val summaryMessage: String,
)

interface BatchCheckInRunner {
    suspend fun runBatch(onProgress: suspend (BatchCheckInReport) -> Unit = {}): BatchCheckInReport

    suspend fun retry(studentId: String): BatchCheckInRowState
}

interface BatchCheckInProgressWriter {
    suspend fun record(message: String)

    object Noop : BatchCheckInProgressWriter {
        override suspend fun record(message: String) = Unit
    }
}

interface SavedAccountRepository {
    fun readAll(): List<SavedAccountEntry>

    fun remove(studentId: String)

    fun saveImported(account: SavedAccountEntry) {
        error("Account import is not supported by this repository")
    }
}

class AccountManagementViewModelFactory(
    private val accountRepository: SavedAccountRepository,
    private val loginGateway: LoginGateway,
    private val sessionRepository: SessionRepository,
    private val accountStatusRepository: AccountStatusRepository? = null,
    private val accountSeatActionExecutor: AccountSeatActionExecutor? = null,
    private val batchCheckInRunner: BatchCheckInRunner? = null,
    private val batchProgressWriter: BatchCheckInProgressWriter = BatchCheckInProgressWriter.Noop,
    private val dailyRefreshTimes: List<LocalTime> = DEFAULT_DAILY_REFRESH_TIMES,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AccountManagementViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return AccountManagementViewModel(
            accountRepository = accountRepository,
            loginGateway = loginGateway,
            sessionRepository = sessionRepository,
            accountStatusRepository = accountStatusRepository,
            accountSeatActionExecutor = accountSeatActionExecutor,
            batchCheckInRunner = batchCheckInRunner,
            batchProgressWriter = batchProgressWriter,
            dailyRefreshTimes = dailyRefreshTimes,
        ) as T
    }

    companion object {
        /**
         * 默认每天定时刷新「所有账号状态」的本地时间点：07:30 与 08:10。
         *
         * 与人工预约签到窗口对齐，让用户进入页面时已有最新预约状态可读，又避免每次进入页面
         * 都重新拉取全量账号被同 IP 风控。如需在其它时间点刷新，可在创建 Factory 时覆盖此参数。
         */
        val DEFAULT_DAILY_REFRESH_TIMES: List<LocalTime> =
            listOf(LocalTime.of(7, 30), LocalTime.of(8, 10))
    }
}
