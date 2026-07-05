package com.wuyi.libraryauto.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.core.domain.usecase.BuildContinuousReservationWindowsUseCase
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupLoadResult
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatRoomSnapshot
import com.wuyi.libraryauto.ui.repository.seat.SmartSeatRecommender
import com.wuyi.libraryauto.ui.repository.settings.AutofillAuditLevel
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogRepository
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanDraft
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRecord
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRepository
import com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode
import com.wuyi.libraryauto.ui.repository.task.HistorySource
import com.wuyi.libraryauto.ui.repository.task.SeatBookingSnapshotView
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import java.time.Clock
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class AutomationTaskViewModel(
    private val accountRepository: SavedAccountRepository,
    private val automationPlanRepository: AutomationPlanRepository,
    private val seatLookupRepository: SeatLookupRepository,
    private val sessionRepository: SessionRepository,
    private val buildContinuousReservationWindowsUseCase: BuildContinuousReservationWindowsUseCase =
        BuildContinuousReservationWindowsUseCase(),
    private val clock: Clock = Clock.systemDefaultZone(),
    initialStudentFilter: String = "",
    private val historyReader: AccountReservationHistoryReader,
    private val diagnosticsLogRepository: DiagnosticsLogRepository,
    private val smartSeatRecommender: SmartSeatRecommender? = null,
    private val accountSeatActionExecutor: AccountSeatActionExecutor? = null,
) : ViewModel() {

    var uiState by mutableStateOf(
        AutomationTaskUiState(
            accounts = buildAccounts(),
            studentFilter = initialStudentFilter.trim(),
        ),
    )
        private set

    private var allPlans: List<AutomationPlanRecord> = emptyList()
    private var reservationChecks: Map<String, AutomationTaskReservationCheckUiState> = emptyMap()

    init {
        observePlans()
    }

    fun refreshAccounts() {
        uiState = uiState.copy(accounts = buildAccounts())
    }

    fun updateStudentFilter(studentId: String) {
        uiState = uiState.copy(
            studentFilter = studentId.trim(),
            plans = filterAutomationPlans(allPlans, studentId, reservationChecks),
        )
    }

    fun openCreateDialog(studentId: String = defaultDialogStudentId()) {
        uiState =
            uiState.copy(
                dialog = buildDialogState(studentId = studentId, visible = true),
                statusMessage = null,
            )
    }

    fun closeDialog() {
        if (!uiState.dialog.visible) {
            return
        }
        uiState = uiState.copy(dialog = AutomationTaskDialogState())
    }

    fun switchMode(mode: AutomationTaskMode) {
        val currentDialog = uiState.dialog
        if (!currentDialog.visible) {
            return
        }
        uiState =
            uiState.copy(
                dialog =
                    currentDialog.copy(
                        mode = mode,
                        previewText = if (mode == AutomationTaskMode.CONTINUOUS) buildContinuousPreview() else "",
                        customDate = if (mode == AutomationTaskMode.SINGLE_CUSTOM) "" else currentDialog.customDate,
                        customStartTime = if (mode == AutomationTaskMode.SINGLE_CUSTOM) "" else currentDialog.customStartTime,
                        customEndTime = if (mode == AutomationTaskMode.SINGLE_CUSTOM) "" else currentDialog.customEndTime,
                        dialogMessage = null,
                    ),
            )
    }

    fun updateDialogStudentId(studentId: String) {
        if (!uiState.dialog.visible) {
            return
        }
        val safeStudentId = studentId.trim()
        val previousDialog = uiState.dialog
        val userManuallyChanged =
            previousDialog.lastAutofill != null &&
                (previousDialog.roomName != previousDialog.lastAutofill.roomName ||
                    previousDialog.seatNumber != previousDialog.lastAutofill.seatNumber)
        val existingPlan = resolvePlanForStudent(safeStudentId)
        val account = resolveAccount(safeStudentId)
        val resolvedStudentId = account?.studentId ?: safeStudentId

        val nextDialog =
            if (userManuallyChanged) {
                // 用户已手动改过 roomName/seatNumber：保留用户输入，仅刷新元数据。
                previousDialog.copy(
                    selectedStudentId = resolvedStudentId,
                    mode = existingPlan?.mode ?: AutomationTaskMode.CONTINUOUS,
                    previewText = existingPlan?.previewText ?: buildContinuousPreview(),
                    customDate = existingPlan?.singleDate.orEmpty(),
                    customStartTime = existingPlan?.singleStartTime.orEmpty(),
                    customEndTime = existingPlan?.singleEndTime.orEmpty(),
                    seatOptions = emptyList(),
                    isRefreshingSeats = false,
                    dialogMessage = null,
                    historyHints = emptyList(),
                )
            } else {
                buildDialogState(studentId = safeStudentId, visible = true)
            }
        uiState = uiState.copy(dialog = nextDialog)

        // 当 existingPlan == null 且账号偏好也为空（同步分支未写入 lastAutofill）时，
        // 再去异步加载 historyReader 的命中作为兜底；通过守卫避免覆盖用户后续切换或手动编辑。
        if (!userManuallyChanged && existingPlan == null && nextDialog.lastAutofill == null) {
            viewModelScope.launch {
                val history =
                    try {
                        historyReader.loadHistory(safeStudentId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Throwable) {
                        // 字段保持空：existingPlan == null 已保证同步分支没有自动填入。
                        val failedDialog = uiState.dialog
                        if (failedDialog.visible &&
                            failedDialog.selectedStudentId == resolvedStudentId &&
                            failedDialog.lastAutofill == null &&
                            resolvePlanForStudent(safeStudentId) == null
                        ) {
                            uiState =
                                uiState.copy(
                                    dialog = failedDialog.copy(historyHints = emptyList()),
                                )
                        }
                        diagnosticsLogRepository.recordAutomationAutofill(
                            studentId = safeStudentId,
                            source = "empty",
                            roomName = "",
                            seatNumber = "",
                            level = AutofillAuditLevel.WARN,
                            errorClass = e::class.simpleName ?: "Throwable",
                            errorMessage = e.message ?: e.toString(),
                        )
                        return@launch
                    }

                val currentDialog = uiState.dialog
                if (!currentDialog.visible) return@launch
                if (currentDialog.selectedStudentId != resolvedStudentId) return@launch
                if (currentDialog.lastAutofill != null) return@launch
                if (resolvePlanForStudent(safeStudentId) != null) return@launch

                if (history.isEmpty()) {
                    uiState =
                        uiState.copy(
                            dialog = currentDialog.copy(historyHints = history),
                        )
                    return@launch
                }

                val recommendation = smartSeatRecommender?.let { recommender ->
                    SmartSeatRecommender.analyzeHistory(history)
                } ?: history.firstOrNull()?.let { firstHit ->
                    com.wuyi.libraryauto.ui.repository.seat.SeatRecommendation(
                        roomName = firstHit.roomName,
                        seatNumber = firstHit.seatNumber,
                        confidence = 1.0,
                        usageCount = 1,
                        latestUsedTimestamp = firstHit.timestampEpochSeconds,
                    )
                }

                if (recommendation == null) {
                    uiState =
                        uiState.copy(
                            dialog = currentDialog.copy(historyHints = history),
                        )
                    return@launch
                }

                uiState =
                    uiState.copy(
                        dialog =
                            currentDialog.copy(
                                roomName = recommendation.roomName,
                                seatNumber = recommendation.seatNumber,
                                lastAutofill = AutoFillSnapshot(recommendation.roomName, recommendation.seatNumber),
                                historyHints = history,
                                dialogMessage = null,
                            ),
                    )
                diagnosticsLogRepository.recordAutomationAutofill(
                    studentId = safeStudentId,
                    source = "smart_recommendation",
                    roomName = recommendation.roomName,
                    seatNumber = recommendation.seatNumber,
                    level = AutofillAuditLevel.INFO,
                )
            }
        }
    }

    fun updateDialogRoomName(roomName: String) {
        val safeRoomName = roomName.trim()
        val matchingOption = uiState.dialog.seatOptions.firstOrNull { option -> option.roomName == safeRoomName }
        val keepCurrentSeat =
            matchingOption?.seatNumbers?.contains(uiState.dialog.seatNumber) == true
        val nextSeatNumber =
            when {
                matchingOption == null -> uiState.dialog.seatNumber
                keepCurrentSeat -> uiState.dialog.seatNumber
                else -> matchingOption.recommendedSeatNumber.orEmpty()
            }
        uiState =
            uiState.copy(
                dialog = uiState.dialog.copy(
                    roomName = safeRoomName,
                    seatNumber = nextSeatNumber,
                    dialogMessage = null,
                ),
            )
    }

    fun updateDialogSeatNumber(seatNumber: String) {
        uiState = uiState.copy(dialog = uiState.dialog.copy(seatNumber = seatNumber.trim(), dialogMessage = null))
    }

    fun updateCustomDate(date: String) {
        uiState = uiState.copy(dialog = uiState.dialog.copy(customDate = date.trim(), dialogMessage = null))
    }

    fun updateCustomStartTime(startTime: String) {
        uiState = uiState.copy(dialog = uiState.dialog.copy(customStartTime = startTime.trim(), dialogMessage = null))
    }

    fun updateCustomEndTime(endTime: String) {
        uiState = uiState.copy(dialog = uiState.dialog.copy(customEndTime = endTime.trim(), dialogMessage = null))
    }

    fun refreshSeatOptions() {
        val studentId = uiState.dialog.selectedStudentId.trim()
        if (studentId.isBlank()) {
            uiState = uiState.copy(dialog = uiState.dialog.copy(dialogMessage = "请先选择账号。"))
            return
        }
        viewModelScope.launch {
            uiState = uiState.copy(dialog = uiState.dialog.copy(isRefreshingSeats = true, dialogMessage = null))
            when (val result = seatLookupRepository.loadDefaultSeats(studentId)) {
                SeatLookupLoadResult.NotLoggedIn ->
                    uiState =
                        uiState.copy(
                            dialog =
                                uiState.dialog.copy(
                                    isRefreshingSeats = false,
                                    dialogMessage = "当前账号未认证，请先去账号列表刷新认证。",
                                ),
                        )

                is SeatLookupLoadResult.Failure ->
                    uiState =
                        uiState.copy(
                            dialog =
                                uiState.dialog.copy(
                                    isRefreshingSeats = false,
                                    dialogMessage = result.message,
                                ),
                        )

                is SeatLookupLoadResult.Success -> applySeatOptions(result.data.rooms, result.data.notice)
                is SeatLookupLoadResult.Empty ->
                    applySeatOptions(
                        result.data.rooms,
                        result.data.notice ?: "当前没有可选座位，请稍后再试。",
                    )
            }
        }
    }

    fun chooseSeat(
        roomName: String,
        seatNumber: String,
    ) {
        uiState =
            uiState.copy(
                dialog =
                    uiState.dialog.copy(
                        roomName = roomName,
                        seatNumber = seatNumber,
                        dialogMessage = null,
                    ),
            )
    }

    fun savePlan() {
        val draft = buildDraft() ?: return
        viewModelScope.launch {
            automationPlanRepository.savePlan(draft)
            uiState =
                uiState.copy(
                    dialog = AutomationTaskDialogState(),
                    statusMessage = "自动任务已保存",
                )
            refreshAccounts()
        }
    }

    fun deletePlan(planId: String) {
        val safePlanId = planId.trim()
        if (safePlanId.isBlank()) {
            return
        }
        viewModelScope.launch {
            automationPlanRepository.deletePlan(safePlanId)
            uiState = uiState.copy(statusMessage = "自动任务已删除")
        }
    }

    fun checkReservationsForPlans() {
        val executor = accountSeatActionExecutor
        if (executor == null) {
            uiState = uiState.copy(statusMessage = "当前版本未配置预约检查入口")
            return
        }
        val visiblePlanIds = uiState.plans.map { plan -> plan.planId }.toSet()
        val plansToCheck =
            allPlans.filter { plan -> plan.planId in visiblePlanIds }
        if (plansToCheck.isEmpty()) {
            uiState = uiState.copy(statusMessage = "暂无自动任务可检查")
            return
        }
        val checkingState =
            plansToCheck.associate { plan ->
                plan.planId to
                    AutomationTaskReservationCheckUiState(
                        status = AutomationTaskReservationCheckStatus.CHECKING,
                        label = "检查中",
                    )
            }
        reservationChecks = reservationChecks + checkingState
        uiState =
            uiState.copy(
                isCheckingReservations = true,
                statusMessage = "正在检查自动任务对应账号的预约状态",
                plans = filterAutomationPlans(allPlans, uiState.studentFilter, reservationChecks),
            )

        viewModelScope.launch {
            val results = linkedMapOf<String, AutomationTaskReservationCheckUiState>()
            for (plan in plansToCheck) {
                results[plan.planId] = checkPlanReservation(plan, executor)
                reservationChecks = reservationChecks + results
                uiState =
                    uiState.copy(
                        plans = filterAutomationPlans(allPlans, uiState.studentFilter, reservationChecks),
                    )
            }
            uiState =
                uiState.copy(
                    isCheckingReservations = false,
                    statusMessage = buildReservationCheckSummary(results.values),
                    plans = filterAutomationPlans(allPlans, uiState.studentFilter, reservationChecks),
                )
        }
    }

    fun openCreateFromBookingsDialog() {
        val executor = accountSeatActionExecutor
        if (executor == null) {
            uiState =
                uiState.copy(
                    createFromBookingsDialog = CreateFromBookingsDialogState(),
                    statusMessage = "当前版本未配置预约读取入口，无法根据当前预约创建自动任务",
                )
            return
        }
        val accounts = uiState.accounts.ifEmpty { buildAccounts() }
        if (accounts.isEmpty()) {
            uiState =
                uiState.copy(
                    createFromBookingsDialog = CreateFromBookingsDialogState(),
                    statusMessage = "还没有可用账号，无法根据当前预约创建自动任务",
                )
            return
        }
        uiState =
            uiState.copy(
                createFromBookingsDialog =
                    CreateFromBookingsDialogState(
                        visible = true,
                        loading = true,
                        message = "正在读取当前预约...",
                    ),
                statusMessage = null,
            )
        viewModelScope.launch {
            val rows =
                accounts
                    .map { account -> buildCreateFromBookingsRow(account.studentId, executor) }
                    .sortedWith(
                        compareBy<CreateFromBookingsRowUiState> { it.hasExistingPlan }
                            .thenByDescending { it.canCreate }
                            .thenBy { it.studentId },
                    )
            uiState =
                uiState.copy(
                    createFromBookingsDialog =
                        CreateFromBookingsDialogState(
                            visible = true,
                            rows = rows,
                            message =
                                if (rows.none { it.canCreate }) {
                                    "没有读取到可用于创建自动任务的当前预约"
                                } else {
                                    "已读取 ${rows.count { it.canCreate }} 个可创建账号"
                                },
                        ),
                )
        }
    }

    fun closeCreateFromBookingsDialog() {
        uiState = uiState.copy(createFromBookingsDialog = CreateFromBookingsDialogState())
    }

    fun toggleCreateFromBookingsRow(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) return
        val dialog = uiState.createFromBookingsDialog
        uiState =
            uiState.copy(
                createFromBookingsDialog =
                    dialog.copy(
                        rows =
                            dialog.rows.map { row ->
                                if (row.studentId == safeStudentId && row.canCreate) {
                                    row.copy(selected = !row.selected)
                                } else {
                                    row
                                }
                            },
                        message = null,
                    ),
            )
    }

    fun selectAllCreateFromBookingsRows() {
        val dialog = uiState.createFromBookingsDialog
        uiState =
            uiState.copy(
                createFromBookingsDialog =
                    dialog.copy(
                        rows = dialog.rows.map { row -> if (row.canCreate) row.copy(selected = true) else row },
                        message = null,
                    ),
            )
    }

    fun clearCreateFromBookingsRows() {
        val dialog = uiState.createFromBookingsDialog
        uiState =
            uiState.copy(
                createFromBookingsDialog =
                    dialog.copy(
                        rows = dialog.rows.map { row -> row.copy(selected = false) },
                        message = null,
                    ),
            )
    }

    fun confirmCreateFromBookings() {
        val dialog = uiState.createFromBookingsDialog
        val selectedRows = dialog.rows.filter { row -> row.selected && row.canCreate }
        if (selectedRows.isEmpty()) {
            uiState =
                uiState.copy(
                    createFromBookingsDialog = dialog.copy(message = "请选择要创建自动任务的账号"),
                )
            return
        }
        uiState = uiState.copy(createFromBookingsDialog = dialog.copy(saving = true, message = null))
        viewModelScope.launch {
            var success = 0
            var failed = 0
            for (row in selectedRows) {
                try {
                    automationPlanRepository.savePlan(
                        AutomationPlanDraft(
                            studentId = row.studentId,
                            roomName = row.roomName,
                            seatNumber = row.seatNumber,
                            mode = AutomationTaskMode.CONTINUOUS,
                        ),
                    )
                    success += 1
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    failed += 1
                }
            }
            refreshAccounts()
            uiState =
                uiState.copy(
                    createFromBookingsDialog = CreateFromBookingsDialogState(),
                    statusMessage = "创建自动任务完成：成功 $success 个，失败 $failed 个",
                )
        }
    }

    private fun observePlans() {
        viewModelScope.launch {
            automationPlanRepository.observePlans().collect { plans ->
                allPlans = plans
                reservationChecks = reservationChecks.filterKeys { planId -> plans.any { plan -> plan.planId == planId } }
                uiState = uiState.copy(plans = filterAutomationPlans(plans, uiState.studentFilter, reservationChecks))
            }
        }
    }

    private fun buildAccounts(): List<SavedAccountEntry> {
        val activeStudentId = sessionRepository.activeStudentId()
        return accountRepository.readAll().map { account ->
            account.copy(
                isAuthenticated = sessionRepository.currentSession(account.studentId) != null,
                isActive = account.studentId == activeStudentId,
            )
        }
    }

    private fun defaultDialogStudentId(): String =
        uiState.studentFilter.takeIf(String::isNotBlank)
            ?: uiState.accounts.firstOrNull { it.isActive }?.studentId
            ?: uiState.accounts.firstOrNull()?.studentId
            ?: ""

    private fun buildDialogState(
        studentId: String,
        visible: Boolean,
    ): AutomationTaskDialogState {
        val safeStudentId = studentId.trim()
        val account = resolveAccount(safeStudentId)
        val existingPlan = resolvePlanForStudent(safeStudentId)
        val resolvedRoomName = existingPlan?.roomName ?: account?.preferredRoomName.orEmpty()
        val resolvedSeatNumber = existingPlan?.seatNumber ?: account?.preferredSeatNumber.orEmpty()
        val autofillSnapshot =
            if (resolvedRoomName.isNotBlank() && resolvedSeatNumber.isNotBlank()) {
                AutoFillSnapshot(resolvedRoomName, resolvedSeatNumber)
            } else {
                null
            }
        val emptyHistoryMessage =
            if (existingPlan == null && resolvedRoomName.isBlank() && resolvedSeatNumber.isBlank()) {
                "该账号暂无历史预约，可手动输入或刷新座位"
            } else {
                null
            }
        // 同步分支审计：existingPlan != null → historyPlan；preferred 非空 → preferredSeat；
        // 都为空 → empty（roomName / seatNumber 写空字符串）。同步函数内通过 viewModelScope.launch 异步落库，避免阻塞。
        val auditSource: String
        val auditRoomName: String
        val auditSeatNumber: String
        when {
            existingPlan != null -> {
                auditSource = "historyPlan"
                auditRoomName = resolvedRoomName
                auditSeatNumber = resolvedSeatNumber
            }
            resolvedRoomName.isNotBlank() && resolvedSeatNumber.isNotBlank() -> {
                auditSource = "preferredSeat"
                auditRoomName = resolvedRoomName
                auditSeatNumber = resolvedSeatNumber
            }
            else -> {
                auditSource = "empty"
                auditRoomName = ""
                auditSeatNumber = ""
            }
        }
        viewModelScope.launch {
            diagnosticsLogRepository.recordAutomationAutofill(
                studentId = safeStudentId,
                source = auditSource,
                roomName = auditRoomName,
                seatNumber = auditSeatNumber,
                level = AutofillAuditLevel.INFO,
            )
        }
        return AutomationTaskDialogState(
            visible = visible,
            selectedStudentId = account?.studentId ?: safeStudentId,
            mode = existingPlan?.mode ?: AutomationTaskMode.CONTINUOUS,
            roomName = resolvedRoomName,
            seatNumber = resolvedSeatNumber,
            previewText = existingPlan?.previewText ?: buildContinuousPreview(),
            customDate = existingPlan?.singleDate.orEmpty(),
            customStartTime = existingPlan?.singleStartTime.orEmpty(),
            customEndTime = existingPlan?.singleEndTime.orEmpty(),
            lastAutofill = autofillSnapshot,
            dialogMessage = emptyHistoryMessage,
        )
    }

    private fun resolveAccount(studentId: String): SavedAccountEntry? =
        uiState.accounts.firstOrNull { it.studentId == studentId.trim() }

    private fun resolvePlanForStudent(studentId: String): AutomationPlanRecord? =
        allPlans.firstOrNull { it.studentId == studentId.trim() }

    private suspend fun buildCreateFromBookingsRow(
        studentId: String,
        executor: AccountSeatActionExecutor,
    ): CreateFromBookingsRowUiState {
        val safeStudentId = studentId.trim()
        val hasExistingPlan = allPlans.any { plan -> plan.studentId == safeStudentId }
        return try {
            val booking =
                executor.loadActiveBookings(safeStudentId)
                    .firstOrNull { booking ->
                        booking.roomName.isNotBlank() && booking.seatNumber.isNotBlank()
                    }
            if (booking == null) {
                CreateFromBookingsRowUiState(
                    studentId = safeStudentId,
                    hasExistingPlan = hasExistingPlan,
                    message = "当前没有可用预约",
                )
            } else {
                CreateFromBookingsRowUiState(
                    studentId = safeStudentId,
                    roomName = booking.roomName.trim(),
                    seatNumber = booking.seatNumber.trim(),
                    beginLabel = booking.beginLabel,
                    statusLabel = booking.statusLabel,
                    hasExistingPlan = hasExistingPlan,
                    selected = !hasExistingPlan,
                    canCreate = true,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            CreateFromBookingsRowUiState(
                studentId = safeStudentId,
                hasExistingPlan = hasExistingPlan,
                message = error.message?.takeIf(String::isNotBlank) ?: "读取当前预约失败",
            )
        }
    }

    private fun buildContinuousPreview(): String {
        val now = LocalDateTime.now(clock)
        val windows = buildContinuousReservationWindowsUseCase(now)
        return windows.joinToString("；") { window ->
            "${window.targetDate} ${window.startHour}:00-${window.endHour}:00"
        }
    }

    private fun applySeatOptions(
        rooms: List<SeatRoomSnapshot>,
        notice: String?,
    ) {
        val options = rooms.map(SeatRoomSnapshot::toAutomationTaskSeatOptionUiModel)
        val currentDialog = uiState.dialog
        val selectedOption = options.firstOrNull { option -> option.roomName == currentDialog.roomName }
        val firstSuggestion = selectedOption?.defaultSeatSuggestion() ?: options.firstOrNull()?.defaultSeatSuggestion()
        val nextRoomName = currentDialog.roomName.ifBlank { firstSuggestion?.first.orEmpty() }
        val keepCurrentSeat =
            options.firstOrNull { option -> option.roomName == nextRoomName }
                ?.seatNumbers
                ?.contains(currentDialog.seatNumber) == true
        val nextSeatNumber =
            when {
                currentDialog.seatNumber.isBlank() -> firstSuggestion?.second.orEmpty()
                keepCurrentSeat -> currentDialog.seatNumber
                else -> options.firstOrNull { option -> option.roomName == nextRoomName }
                    ?.defaultSeatSuggestion()
                    ?.second
                    .orEmpty()
            }
        uiState =
            uiState.copy(
                dialog =
                    currentDialog.copy(
                        isRefreshingSeats = false,
                        seatOptions = options,
                        roomName = nextRoomName,
                        seatNumber = nextSeatNumber,
                        dialogMessage = notice,
                    ),
            )
    }

    private fun buildDraft(): AutomationPlanDraft? {
        val dialog = uiState.dialog
        if (dialog.selectedStudentId.isBlank()) {
            uiState = uiState.copy(dialog = dialog.copy(dialogMessage = "请先选择账号。"))
            return null
        }
        if (dialog.roomName.isBlank() || dialog.seatNumber.isBlank()) {
            uiState = uiState.copy(dialog = dialog.copy(dialogMessage = "请填写目标自习室和座位号。"))
            return null
        }
        if (
            dialog.mode == AutomationTaskMode.SINGLE_CUSTOM &&
            (dialog.customDate.isBlank() || dialog.customStartTime.isBlank() || dialog.customEndTime.isBlank())
        ) {
            uiState = uiState.copy(dialog = dialog.copy(dialogMessage = "单次模式需要完整填写日期和时间段。"))
            return null
        }
        return AutomationPlanDraft(
            studentId = dialog.selectedStudentId,
            roomName = dialog.roomName,
            seatNumber = dialog.seatNumber,
            mode = dialog.mode,
            singleDate = dialog.customDate.ifBlank { null },
            singleStartTime = dialog.customStartTime.ifBlank { null },
            singleEndTime = dialog.customEndTime.ifBlank { null },
        )
    }

    private suspend fun checkPlanReservation(
        plan: AutomationPlanRecord,
        executor: AccountSeatActionExecutor,
    ): AutomationTaskReservationCheckUiState =
        try {
            val bookings = executor.loadActiveBookings(plan.studentId)
            if (bookings.isEmpty()) {
                AutomationTaskReservationCheckUiState(
                    status = AutomationTaskReservationCheckStatus.EMPTY,
                    label = "暂无预约",
                    detail = "该账号当前没有活跃预约",
                )
            } else {
                val matching =
                    bookings.firstOrNull { booking ->
                        booking.roomName.trim() == plan.roomName.trim() &&
                            booking.seatNumber.trim() == plan.seatNumber.trim()
                    }
                if (matching != null) {
                    AutomationTaskReservationCheckUiState(
                        status = AutomationTaskReservationCheckStatus.MATCHED,
                        label = "目标已预约",
                        detail = matching.toReservationCheckLabel(),
                    )
                } else {
                    AutomationTaskReservationCheckUiState(
                        status = AutomationTaskReservationCheckStatus.OTHER_BOOKING,
                        label = "已有其它预约",
                        detail = bookings.joinToString("；") { booking -> booking.toReservationCheckLabel() },
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            AutomationTaskReservationCheckUiState(
                status = AutomationTaskReservationCheckStatus.FAILED,
                label = "检查失败",
                detail = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty(),
            )
        }

    private fun SeatBookingSnapshotView.toReservationCheckLabel(): String =
        listOf(roomName, seatNumber, beginLabel, statusLabel)
            .filter(String::isNotBlank)
            .joinToString(" / ")
            .ifBlank { bookingId ?: "已预约" }

    private fun buildReservationCheckSummary(results: Collection<AutomationTaskReservationCheckUiState>): String {
        val matched = results.count { it.status == AutomationTaskReservationCheckStatus.MATCHED }
        val other = results.count { it.status == AutomationTaskReservationCheckStatus.OTHER_BOOKING }
        val empty = results.count { it.status == AutomationTaskReservationCheckStatus.EMPTY }
        val failed = results.count { it.status == AutomationTaskReservationCheckStatus.FAILED }
        return "预约检查完成：目标已预约 $matched 个，已有其它预约 $other 个，暂无预约 $empty 个，失败 $failed 个"
    }
}

/**
 * 把 [HistorySource] 映射为 [DiagnosticsLogRepository.recordAutomationAutofill] 期望的审计标签字符串。
 *
 * 标签命名与 design.md 5.1 / Property 20 保持一致：
 *   - HISTORY_PLAN     → "historyPlan"
 *   - RESERVATION_TASK → "reservationTask"
 *   - SEAT_SNAPSHOT    → "seatSnapshot"
 *   - PREFERRED_SEAT   → "preferredSeat"
 */
private fun HistorySource.toAuditTag(): String =
    when (this) {
        HistorySource.HISTORY_PLAN -> "historyPlan"
        HistorySource.RESERVATION_TASK -> "reservationTask"
        HistorySource.SEAT_SNAPSHOT -> "seatSnapshot"
        HistorySource.PREFERRED_SEAT -> "preferredSeat"
    }
