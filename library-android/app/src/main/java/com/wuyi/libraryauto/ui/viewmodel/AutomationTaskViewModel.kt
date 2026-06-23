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
import com.wuyi.libraryauto.ui.repository.settings.AutofillAuditLevel
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogRepository
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanDraft
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRecord
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRepository
import com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode
import com.wuyi.libraryauto.ui.repository.task.HistorySource
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
) : ViewModel() {

    var uiState by mutableStateOf(
        AutomationTaskUiState(
            accounts = buildAccounts(),
            studentFilter = initialStudentFilter.trim(),
        ),
    )
        private set

    private var allPlans: List<AutomationPlanRecord> = emptyList()

    init {
        observePlans()
    }

    fun refreshAccounts() {
        uiState = uiState.copy(accounts = buildAccounts())
    }

    fun updateStudentFilter(studentId: String) {
        uiState = uiState.copy(
            studentFilter = studentId.trim(),
            plans = filterAutomationPlans(allPlans, studentId),
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

                val firstHit = history.firstOrNull()
                if (firstHit == null) {
                    // 即使不命中也把全量 history（此处为 emptyList）暴露给 UI 下拉与"曾用"标记。
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
                                roomName = firstHit.roomName,
                                seatNumber = firstHit.seatNumber,
                                lastAutofill = AutoFillSnapshot(firstHit.roomName, firstHit.seatNumber),
                                historyHints = history,
                                dialogMessage = null,
                            ),
                    )
                diagnosticsLogRepository.recordAutomationAutofill(
                    studentId = safeStudentId,
                    source = firstHit.source.toAuditTag(),
                    roomName = firstHit.roomName,
                    seatNumber = firstHit.seatNumber,
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

    private fun observePlans() {
        viewModelScope.launch {
            automationPlanRepository.observePlans().collect { plans ->
                allPlans = plans
                uiState = uiState.copy(plans = filterAutomationPlans(plans, uiState.studentFilter))
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
