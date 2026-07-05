package com.wuyi.libraryauto.ui.viewmodel

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.usecase.BuildContinuousReservationWindowsUseCase
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.storage.db.ExecutionLogDao
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupLoadResult
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupData
import com.wuyi.libraryauto.ui.repository.seat.SeatRoomSnapshot
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogRepository
import com.wuyi.libraryauto.ui.repository.settings.ExecutionLogRepository
import com.wuyi.libraryauto.ui.repository.settings.LoginAuditRepository
import com.wuyi.libraryauto.ui.repository.settings.SeatActionAuditRepository
import com.wuyi.libraryauto.ui.repository.settings.SeatLookupAuditRepository
import com.wuyi.libraryauto.ui.repository.settings.SeatStatusAuditRepository
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.AccountSeatAction
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutionResult
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanDraft
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRecord
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRepository
import com.wuyi.libraryauto.ui.repository.task.AutomationTaskMode
import com.wuyi.libraryauto.ui.repository.task.ReservationHistoryHit
import com.wuyi.libraryauto.ui.repository.task.SeatBookingSnapshotView
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationTaskViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `task dialog defaults to continuous mode and current preferred seat`() = runTest {
        val viewModel = buildViewModel()

        viewModel.openCreateDialog()
        advanceUntilIdle()

        assertThat(viewModel.uiState.dialog.visible).isTrue()
        assertThat(viewModel.uiState.dialog.mode).isEqualTo(AutomationTaskMode.CONTINUOUS)
        assertThat(viewModel.uiState.dialog.roomName).isEqualTo("自习室圆形二楼")
        assertThat(viewModel.uiState.dialog.seatNumber).isEqualTo("166")
        assertThat(viewModel.uiState.dialog.previewText).contains("2026-04-11 9:00-22:00")
        assertThat(viewModel.uiState.dialog.previewText).contains("2026-04-13 8:00-22:00")
    }

    @Test
    fun `task dialog preview includes day after tomorrow after 10am`() = runTest {
        val viewModel =
            buildViewModel(
                clock = Clock.fixed(Instant.parse("2026-04-11T02:30:00Z"), ZoneId.of("Asia/Shanghai")),
            )

        viewModel.openCreateDialog()
        advanceUntilIdle()

        assertThat(viewModel.uiState.dialog.previewText)
            .isEqualTo("2026-04-11 10:00-22:00；2026-04-12 8:00-22:00；2026-04-13 8:00-22:00")
    }

    @Test
    fun `task dialog switches to single custom mode and exposes editable time fields`() = runTest {
        val viewModel = buildViewModel()

        viewModel.openCreateDialog()
        viewModel.switchMode(AutomationTaskMode.SINGLE_CUSTOM)

        assertThat(viewModel.uiState.dialog.mode).isEqualTo(AutomationTaskMode.SINGLE_CUSTOM)
        assertThat(viewModel.uiState.dialog.previewText).isEmpty()
        assertThat(viewModel.uiState.dialog.customDate).isEmpty()
        assertThat(viewModel.uiState.dialog.customStartTime).isEmpty()
        assertThat(viewModel.uiState.dialog.customEndTime).isEmpty()
    }

    @Test
    fun `openCreateDialog preloads existing account plan into dialog`() = runTest {
        val repository =
            FakeAutomationPlanRepository(
                plans =
                    listOf(
                        AutomationPlanRecord(
                            planId = "plan-1",
                            studentId = "20230001",
                            roomName = "自习室圆形二楼",
                            seatNumber = "166",
                            mode = AutomationTaskMode.SINGLE_CUSTOM,
                            singleDate = "2026-04-11",
                            singleStartTime = "09:00",
                            singleEndTime = "12:00",
                            enabled = true,
                            previewText = "2026-04-11 09:00-12:00",
                            lastResultMessage = "待执行",
                        ),
                    ),
            )
        val viewModel = buildViewModel(automationPlanRepository = repository)
        advanceUntilIdle()

        viewModel.openCreateDialog("20230001")

        assertThat(viewModel.uiState.dialog.roomName).isEqualTo("自习室圆形二楼")
        assertThat(viewModel.uiState.dialog.seatNumber).isEqualTo("166")
        assertThat(viewModel.uiState.dialog.mode).isEqualTo(AutomationTaskMode.SINGLE_CUSTOM)
        assertThat(viewModel.uiState.dialog.customDate).isEqualTo("2026-04-11")
        assertThat(viewModel.uiState.dialog.customStartTime).isEqualTo("09:00")
        assertThat(viewModel.uiState.dialog.customEndTime).isEqualTo("12:00")
    }

    @Test
    fun `updateDialogStudentId loads existing selected account plan and clears stale query state`() = runTest {
        val repository =
            FakeAutomationPlanRepository(
                plans =
                    listOf(
                        AutomationPlanRecord(
                            planId = "plan-2",
                            studentId = "20230002",
                            roomName = "综合阅览室",
                            seatNumber = "021",
                            mode = AutomationTaskMode.SINGLE_CUSTOM,
                            singleDate = "2026-04-12",
                            singleStartTime = "08:30",
                            singleEndTime = "11:30",
                            enabled = true,
                            previewText = "2026-04-12 08:30-11:30",
                            lastResultMessage = "最近成功",
                        ),
                    ),
            )
        val seatLookupRepository =
            FakeSeatLookupRepository(
                result =
                    SeatLookupLoadResult.Success(
                        SeatLookupData(
                            beginTimeEpochSeconds = 1_712_800_000,
                            durationHours = 4,
                            peopleCount = 1,
                            rooms =
                                listOf(
                                    SeatRoomSnapshot(
                                        roomId = "room-1",
                                        roomName = "自习室圆形二楼",
                                        storey = "2F",
                                        availableCount = 2,
                                        seatNumbers = listOf("166", "168"),
                                        recommendedSeatNumber = "166",
                                    ),
                                ),
                            notice = "旧账号提示",
                        ),
                    ),
            )
        val viewModel = buildViewModel(automationPlanRepository = repository, seatLookupRepository = seatLookupRepository)

        viewModel.openCreateDialog("20230001")
        viewModel.refreshSeatOptions()
        advanceUntilIdle()

        assertThat(viewModel.uiState.dialog.seatOptions).isNotEmpty()
        assertThat(viewModel.uiState.dialog.dialogMessage).isEqualTo("旧账号提示")

        viewModel.updateDialogStudentId("20230002")

        assertThat(viewModel.uiState.dialog.selectedStudentId).isEqualTo("20230002")
        assertThat(viewModel.uiState.dialog.roomName).isEqualTo("综合阅览室")
        assertThat(viewModel.uiState.dialog.seatNumber).isEqualTo("021")
        assertThat(viewModel.uiState.dialog.mode).isEqualTo(AutomationTaskMode.SINGLE_CUSTOM)
        assertThat(viewModel.uiState.dialog.customDate).isEqualTo("2026-04-12")
        assertThat(viewModel.uiState.dialog.customStartTime).isEqualTo("08:30")
        assertThat(viewModel.uiState.dialog.customEndTime).isEqualTo("11:30")
        assertThat(viewModel.uiState.dialog.seatOptions).isEmpty()
        assertThat(viewModel.uiState.dialog.dialogMessage).isNull()
        assertThat(viewModel.uiState.dialog.isRefreshingSeats).isFalse()
    }

    @Test
    fun `updateDialogStudentId falls back to selected account defaults and resets stale custom fields`() = runTest {
        val seatLookupRepository =
            FakeSeatLookupRepository(
                result =
                    SeatLookupLoadResult.Success(
                        SeatLookupData(
                            beginTimeEpochSeconds = 1_712_800_000,
                            durationHours = 4,
                            peopleCount = 1,
                            rooms =
                                listOf(
                                    SeatRoomSnapshot(
                                        roomId = "room-1",
                                        roomName = "自习室圆形二楼",
                                        storey = "2F",
                                        availableCount = 2,
                                        seatNumbers = listOf("166", "168"),
                                        recommendedSeatNumber = "166",
                                    ),
                                ),
                            notice = "旧账号提示",
                        ),
                    ),
            )
        val viewModel = buildViewModel(seatLookupRepository = seatLookupRepository)

        viewModel.openCreateDialog("20230001")
        viewModel.switchMode(AutomationTaskMode.SINGLE_CUSTOM)
        viewModel.updateCustomDate("2026-04-18")
        viewModel.updateCustomStartTime("10:00")
        viewModel.updateCustomEndTime("12:00")
        viewModel.refreshSeatOptions()
        advanceUntilIdle()

        assertThat(viewModel.uiState.dialog.seatOptions).isNotEmpty()
        assertThat(viewModel.uiState.dialog.dialogMessage).isEqualTo("旧账号提示")

        viewModel.updateDialogStudentId("20230002")

        assertThat(viewModel.uiState.dialog.selectedStudentId).isEqualTo("20230002")
        assertThat(viewModel.uiState.dialog.roomName).isEqualTo("综合阅览室")
        assertThat(viewModel.uiState.dialog.seatNumber).isEqualTo("021")
        assertThat(viewModel.uiState.dialog.mode).isEqualTo(AutomationTaskMode.CONTINUOUS)
        assertThat(viewModel.uiState.dialog.customDate).isEmpty()
        assertThat(viewModel.uiState.dialog.customStartTime).isEmpty()
        assertThat(viewModel.uiState.dialog.customEndTime).isEmpty()
        assertThat(viewModel.uiState.dialog.previewText).contains("2026-04-11 9:00-22:00")
        assertThat(viewModel.uiState.dialog.seatOptions).isEmpty()
        assertThat(viewModel.uiState.dialog.dialogMessage).isNull()
        assertThat(viewModel.uiState.dialog.isRefreshingSeats).isFalse()
    }

    @Test
    fun `student filter shows only matching account plans`() = runTest {
        val repository =
            FakeAutomationPlanRepository(
                plans =
                    listOf(
                        AutomationPlanRecord(
                            planId = "plan-1",
                            studentId = "20230001",
                            roomName = "自习室圆形二楼",
                            seatNumber = "166",
                            mode = AutomationTaskMode.CONTINUOUS,
                            singleDate = null,
                            singleStartTime = null,
                            singleEndTime = null,
                            enabled = true,
                            previewText = "2026-04-11 9:00-22:00；2026-04-12 8:00-22:00；2026-04-13 8:00-22:00",
                            lastResultMessage = "待执行",
                        ),
                        AutomationPlanRecord(
                            planId = "plan-2",
                            studentId = "20230002",
                            roomName = "综合阅览室",
                            seatNumber = "021",
                            mode = AutomationTaskMode.SINGLE_CUSTOM,
                            singleDate = "2026-04-12",
                            singleStartTime = "08:00",
                            singleEndTime = "12:00",
                            enabled = true,
                            previewText = "2026-04-12 08:00-12:00",
                            lastResultMessage = "最近成功",
                        ),
                    ),
            )
        val viewModel = buildViewModel(automationPlanRepository = repository, initialStudentFilter = "20230002")
        advanceUntilIdle()

        assertThat(viewModel.uiState.studentFilter).isEqualTo("20230002")
        assertThat(viewModel.uiState.plans.map { it.planId }).containsExactly("plan-2")
    }

    @Test
    fun `checkReservationsForPlans marks matched and empty account bookings`() = runTest {
        val repository =
            FakeAutomationPlanRepository(
                plans =
                    listOf(
                        AutomationPlanRecord(
                            planId = "plan-1",
                            studentId = "20230001",
                            roomName = "自习室圆形二楼",
                            seatNumber = "166",
                            mode = AutomationTaskMode.CONTINUOUS,
                            enabled = true,
                            previewText = "2026-04-11 9:00-22:00",
                            lastResultMessage = "",
                        ),
                        AutomationPlanRecord(
                            planId = "plan-2",
                            studentId = "20230002",
                            roomName = "综合阅览室",
                            seatNumber = "021",
                            mode = AutomationTaskMode.CONTINUOUS,
                            enabled = true,
                            previewText = "2026-04-11 9:00-22:00",
                            lastResultMessage = "",
                        ),
                    ),
            )
        val viewModel =
            buildViewModel(
                automationPlanRepository = repository,
                accountSeatActionExecutor =
                    FakeAccountSeatActionExecutor(
                        activeBookings =
                            mapOf(
                                "20230001" to
                                    listOf(
                                        SeatBookingSnapshotView(
                                            roomName = "自习室圆形二楼",
                                            seatNumber = "166",
                                            beginLabel = "2026-04-11 9:00",
                                            statusLabel = "待签到",
                                        ),
                                    ),
                                "20230002" to emptyList(),
                            ),
                    ),
            )
        advanceUntilIdle()

        viewModel.checkReservationsForPlans()
        advanceUntilIdle()

        val checks = viewModel.uiState.plans.associate { plan -> plan.planId to plan.reservationCheck }
        assertThat(checks["plan-1"]?.status).isEqualTo(AutomationTaskReservationCheckStatus.MATCHED)
        assertThat(checks["plan-1"]?.label).isEqualTo("目标已预约")
        assertThat(checks["plan-2"]?.status).isEqualTo(AutomationTaskReservationCheckStatus.EMPTY)
        assertThat(viewModel.uiState.statusMessage).contains("目标已预约 1 个")
        assertThat(viewModel.uiState.statusMessage).contains("暂无预约 1 个")
    }

    @Test
    fun `openCreateFromBookingsDialog orders accounts without plans first and selects them by default`() = runTest {
        val repository =
            FakeAutomationPlanRepository(
                plans =
                    listOf(
                        AutomationPlanRecord(
                            planId = "plan-1",
                            studentId = "20230001",
                            roomName = "旧自习室",
                            seatNumber = "001",
                            mode = AutomationTaskMode.CONTINUOUS,
                            enabled = true,
                            previewText = "旧计划",
                            lastResultMessage = "",
                        ),
                    ),
            )
        val viewModel =
            buildViewModel(
                automationPlanRepository = repository,
                accountSeatActionExecutor =
                    FakeAccountSeatActionExecutor(
                        activeBookings =
                            mapOf(
                                "20230001" to
                                    listOf(
                                        SeatBookingSnapshotView(
                                            roomName = "二楼自习室",
                                            seatNumber = "166",
                                            beginLabel = "2026-04-11 09:00",
                                            statusLabel = "待签到",
                                        ),
                                    ),
                                "20230002" to
                                    listOf(
                                        SeatBookingSnapshotView(
                                            roomName = "综合阅览室",
                                            seatNumber = "021",
                                            beginLabel = "2026-04-11 10:00",
                                            statusLabel = "待签到",
                                        ),
                                    ),
                            ),
                    ),
            )
        advanceUntilIdle()

        viewModel.openCreateFromBookingsDialog()
        advanceUntilIdle()

        val dialog = viewModel.uiState.createFromBookingsDialog
        assertThat(dialog.visible).isTrue()
        assertThat(dialog.rows.map { it.studentId }).containsExactly("20230002", "20230001").inOrder()
        assertThat(dialog.rows.map { it.hasExistingPlan }).containsExactly(false, true).inOrder()
        assertThat(dialog.rows.filter { it.selected }.map { it.studentId }).containsExactly("20230002")
        assertThat(dialog.rows.all { it.canCreate }).isTrue()
    }

    @Test
    fun `openCreateFromBookingsDialog treats disabled plans as existing tasks`() = runTest {
        val repository =
            FakeAutomationPlanRepository(
                plans =
                    listOf(
                        AutomationPlanRecord(
                            planId = "plan-disabled",
                            studentId = "20230001",
                            roomName = "旧自习室",
                            seatNumber = "001",
                            mode = AutomationTaskMode.CONTINUOUS,
                            enabled = false,
                            previewText = "旧计划",
                            lastResultMessage = "",
                        ),
                    ),
            )
        val viewModel =
            buildViewModel(
                automationPlanRepository = repository,
                accountSeatActionExecutor =
                    FakeAccountSeatActionExecutor(
                        activeBookings =
                            mapOf(
                                "20230001" to
                                    listOf(
                                        SeatBookingSnapshotView(
                                            roomName = "二楼自习室",
                                            seatNumber = "166",
                                            beginLabel = "2026-04-11 09:00",
                                            statusLabel = "待签到",
                                        ),
                                    ),
                                "20230002" to
                                    listOf(
                                        SeatBookingSnapshotView(
                                            roomName = "综合阅览室",
                                            seatNumber = "021",
                                            beginLabel = "2026-04-11 10:00",
                                            statusLabel = "待签到",
                                        ),
                                    ),
                            ),
                    ),
            )
        advanceUntilIdle()

        viewModel.openCreateFromBookingsDialog()
        advanceUntilIdle()

        val dialog = viewModel.uiState.createFromBookingsDialog
        assertThat(dialog.rows.map { it.studentId }).containsExactly("20230002", "20230001").inOrder()
        assertThat(dialog.rows.map { it.hasExistingPlan }).containsExactly(false, true).inOrder()
        assertThat(dialog.rows.filter { it.selected }.map { it.studentId }).containsExactly("20230002")
    }

    @Test
    fun `selectAllCreateFromBookingsRows and confirmCreateFromBookings create tasks for selected bookings`() = runTest {
        val repository =
            FakeAutomationPlanRepository(
                plans =
                    listOf(
                        AutomationPlanRecord(
                            planId = "plan-1",
                            studentId = "20230001",
                            roomName = "旧自习室",
                            seatNumber = "001",
                            mode = AutomationTaskMode.CONTINUOUS,
                            enabled = true,
                            previewText = "旧计划",
                            lastResultMessage = "",
                        ),
                    ),
            )
        val viewModel =
            buildViewModel(
                automationPlanRepository = repository,
                accountSeatActionExecutor =
                    FakeAccountSeatActionExecutor(
                        activeBookings =
                            mapOf(
                                "20230001" to
                                    listOf(
                                        SeatBookingSnapshotView(
                                            roomName = "二楼自习室",
                                            seatNumber = "166",
                                            beginLabel = "2026-04-11 09:00",
                                            statusLabel = "待签到",
                                        ),
                                    ),
                                "20230002" to
                                    listOf(
                                        SeatBookingSnapshotView(
                                            roomName = "综合阅览室",
                                            seatNumber = "021",
                                            beginLabel = "2026-04-11 10:00",
                                            statusLabel = "待签到",
                                        ),
                                    ),
                            ),
                    ),
            )
        advanceUntilIdle()
        viewModel.openCreateFromBookingsDialog()
        advanceUntilIdle()

        viewModel.selectAllCreateFromBookingsRows()
        viewModel.confirmCreateFromBookings()
        advanceUntilIdle()

        assertThat(repository.savedDrafts.map { it.studentId }).containsExactly("20230002", "20230001").inOrder()
        assertThat(repository.savedDrafts[0]).isEqualTo(
            AutomationPlanDraft(
                studentId = "20230002",
                roomName = "综合阅览室",
                seatNumber = "021",
                mode = AutomationTaskMode.CONTINUOUS,
            ),
        )
        assertThat(repository.savedDrafts[1]).isEqualTo(
            AutomationPlanDraft(
                studentId = "20230001",
                roomName = "二楼自习室",
                seatNumber = "166",
                mode = AutomationTaskMode.CONTINUOUS,
            ),
        )
        assertThat(viewModel.uiState.createFromBookingsDialog.visible).isFalse()
        assertThat(viewModel.uiState.statusMessage).contains("创建自动任务完成：成功 2 个")
    }

    @Test
    fun `openCreateFromBookingsDialog reports missing booking executor`() = runTest {
        val viewModel = buildViewModel(accountSeatActionExecutor = null)

        viewModel.openCreateFromBookingsDialog()
        advanceUntilIdle()

        assertThat(viewModel.uiState.createFromBookingsDialog.visible).isFalse()
        assertThat(viewModel.uiState.statusMessage).isEqualTo("当前版本未配置预约读取入口，无法根据当前预约创建自动任务")
    }

    @Test
    fun `closeCreateFromBookingsDialog keeps stale booking load result from reopening sheet`() = runTest {
        val allowLoad = CompletableDeferred<Unit>()
        val viewModel =
            buildViewModel(
                accountSeatActionExecutor =
                    FakeAccountSeatActionExecutor(
                        activeBookings =
                            mapOf(
                                "20230001" to
                                    listOf(
                                        SeatBookingSnapshotView(
                                            roomName = "二楼自习室",
                                            seatNumber = "166",
                                        ),
                                    ),
                            ),
                        beforeLoadActiveBookings = { allowLoad.await() },
                    ),
            )
        advanceUntilIdle()

        viewModel.openCreateFromBookingsDialog()
        viewModel.closeCreateFromBookingsDialog()
        allowLoad.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.uiState.createFromBookingsDialog.visible).isFalse()
        assertThat(viewModel.uiState.createFromBookingsDialog.rows).isEmpty()
    }

    @Test
    fun `savePlan persists selected dialog values and closes dialog`() = runTest {
        val repository = FakeAutomationPlanRepository()
        val viewModel = buildViewModel(automationPlanRepository = repository)

        viewModel.openCreateDialog()
        viewModel.updateDialogRoomName("综合阅览室")
        viewModel.updateDialogSeatNumber("021")
        viewModel.savePlan()
        advanceUntilIdle()

        assertThat(repository.lastSavedDraft).isEqualTo(
            AutomationPlanDraft(
                studentId = "20230001",
                roomName = "综合阅览室",
                seatNumber = "021",
                mode = AutomationTaskMode.CONTINUOUS,
                singleDate = null,
                singleStartTime = null,
                singleEndTime = null,
            ),
        )
        assertThat(viewModel.uiState.dialog.visible).isFalse()
        assertThat(viewModel.uiState.statusMessage).isEqualTo("自动任务已保存")
    }

    @Test
    fun `deletePlan removes saved plan and updates status message`() = runTest {
        val repository =
            FakeAutomationPlanRepository(
                plans =
                    listOf(
                        AutomationPlanRecord(
                            planId = "plan-1",
                            studentId = "20230001",
                            roomName = "自习室圆形二楼",
                            seatNumber = "166",
                            mode = AutomationTaskMode.CONTINUOUS,
                            enabled = true,
                            previewText = "2026-04-11 9:00-22:00；2026-04-12 8:00-22:00；2026-04-13 8:00-22:00",
                            lastResultMessage = "",
                        ),
                    ),
            )
        val viewModel = buildViewModel(automationPlanRepository = repository)
        advanceUntilIdle()

        viewModel.deletePlan("plan-1")
        advanceUntilIdle()

        assertThat(viewModel.uiState.plans).isEmpty()
        assertThat(viewModel.uiState.statusMessage).isEqualTo("自动任务已删除")
    }

    @Test
    fun `refreshSeatOptions keeps selected room and resets seat to that room suggestion`() = runTest {
        val seatLookupRepository =
            FakeSeatLookupRepository(
                result =
                    SeatLookupLoadResult.Success(
                        SeatLookupData(
                            beginTimeEpochSeconds = 1_712_800_000,
                            durationHours = 4,
                            peopleCount = 1,
                            rooms =
                                listOf(
                                    SeatRoomSnapshot(
                                        roomId = "room-1",
                                        roomName = "综合阅览室",
                                        storey = "1F",
                                        availableCount = 2,
                                        seatNumbers = listOf("021", "020"),
                                        recommendedSeatNumber = "021",
                                    ),
                                    SeatRoomSnapshot(
                                        roomId = "room-2",
                                        roomName = "自习室圆形二楼",
                                        storey = "2F",
                                        availableCount = 2,
                                        seatNumbers = listOf("166", "168"),
                                        recommendedSeatNumber = "166",
                                    ),
                                ),
                        ),
                    ),
            )
        val viewModel = buildViewModel(seatLookupRepository = seatLookupRepository)

        viewModel.openCreateDialog()
        viewModel.updateDialogRoomName("综合阅览室")
        viewModel.refreshSeatOptions()
        advanceUntilIdle()

        assertThat(viewModel.uiState.dialog.roomName).isEqualTo("综合阅览室")
        assertThat(viewModel.uiState.dialog.seatNumber).isEqualTo("021")
    }

    private fun buildViewModel(
        automationPlanRepository: FakeAutomationPlanRepository = FakeAutomationPlanRepository(),
        seatLookupRepository: FakeSeatLookupRepository = FakeSeatLookupRepository(),
        initialStudentFilter: String = "",
        clock: Clock = Clock.fixed(Instant.parse("2026-04-11T01:30:00Z"), ZoneId.of("Asia/Shanghai")),
        accountSeatActionExecutor: AccountSeatActionExecutor? = null,
    ): AutomationTaskViewModel =
        AutomationTaskViewModel(
            accountRepository =
                FakeSavedAccountRepository(
                    entries =
                        listOf(
                            SavedAccountEntry(
                                studentId = "20230001",
                                password = "alpha",
                                preferredRoomName = "自习室圆形二楼",
                                preferredSeatNumber = "166",
                                isAuthenticated = true,
                                isActive = true,
                            ),
                            SavedAccountEntry(
                                studentId = "20230002",
                                password = "beta",
                                preferredRoomName = "综合阅览室",
                                preferredSeatNumber = "021",
                            ),
                        ),
                ),
            automationPlanRepository = automationPlanRepository,
            seatLookupRepository = seatLookupRepository,
            sessionRepository = FakeSessionRepository(),
            buildContinuousReservationWindowsUseCase = BuildContinuousReservationWindowsUseCase(),
            clock = clock,
            initialStudentFilter = initialStudentFilter,
            historyReader = FakeAccountReservationHistoryReader(),
            diagnosticsLogRepository = buildDiagnosticsLogRepository(),
            accountSeatActionExecutor = accountSeatActionExecutor,
        )

    private fun buildDiagnosticsLogRepository(): DiagnosticsLogRepository =
        DiagnosticsLogRepository(
            executionLogRepository = ExecutionLogRepository(NoOpExecutionLogDao()),
            loginAuditRepository = LoginAuditRepository(NoOpSharedPreferences()),
            seatStatusAuditRepository = SeatStatusAuditRepository(NoOpSharedPreferences()),
            seatLookupAuditRepository = SeatLookupAuditRepository(NoOpSharedPreferences()),
            seatActionAuditRepository = SeatActionAuditRepository(NoOpSharedPreferences()),
        )

    private class FakeAccountReservationHistoryReader(
        private val hits: List<ReservationHistoryHit> = emptyList(),
    ) : AccountReservationHistoryReader {
        override suspend fun loadHistory(studentId: String): List<ReservationHistoryHit> = hits
    }

    private class NoOpExecutionLogDao : ExecutionLogDao {
        override suspend fun insert(log: ExecutionLogEntity) = Unit

        override suspend fun listAllNewestFirst(): List<ExecutionLogEntity> = emptyList()

        override suspend fun clearAll() = Unit
    }

    private class NoOpSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key != null) {
                        values[key] = value
                    }
                    return this
                }

                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key != null) {
                        values.remove(key)
                    }
                    return this
                }

                override fun clear(): SharedPreferences.Editor {
                    values.clear()
                    return this
                }

                override fun commit(): Boolean = true

                override fun apply() = Unit

                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

                override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

                override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                    this.also {
                        if (key != null) {
                            values[key] = value
                        }
                    }

                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeSavedAccountRepository(
        private val entries: List<SavedAccountEntry>,
    ) : SavedAccountRepository {
        override fun readAll(): List<SavedAccountEntry> = entries

        override fun remove(studentId: String) = Unit
    }

    private class FakeAutomationPlanRepository(
        plans: List<AutomationPlanRecord> = emptyList(),
    ) : AutomationPlanRepository {
        private val state = MutableStateFlow(plans)
        var lastSavedDraft: AutomationPlanDraft? = null
        val savedDrafts = mutableListOf<AutomationPlanDraft>()

        override fun observePlans(): Flow<List<AutomationPlanRecord>> = state

        override suspend fun savePlan(draft: AutomationPlanDraft): AutomationPlanRecord {
            lastSavedDraft = draft
            savedDrafts += draft
            val saved =
                AutomationPlanRecord(
                    planId = draft.planId.ifBlank { "plan-new-${draft.studentId}" },
                    studentId = draft.studentId,
                    roomName = draft.roomName,
                    seatNumber = draft.seatNumber,
                    mode = draft.mode,
                    singleDate = draft.singleDate,
                    singleStartTime = draft.singleStartTime,
                    singleEndTime = draft.singleEndTime,
                    enabled = true,
                    previewText = "2026-04-11 9:00-22:00；2026-04-12 8:00-22:00",
                    lastResultMessage = "",
                )
            state.value = listOf(saved) + state.value
            return saved
        }

        override suspend fun deletePlan(planId: String) {
            state.value = state.value.filterNot { it.planId == planId }
        }
    }

    private class FakeSeatLookupRepository(
        private val result: SeatLookupLoadResult = SeatLookupLoadResult.Failure("未使用"),
    ) : SeatLookupRepository {
        override suspend fun loadDefaultSeats(): SeatLookupLoadResult = result

        override suspend fun loadDefaultSeats(studentId: String): SeatLookupLoadResult = result
    }

    private class FakeAccountSeatActionExecutor(
        private val activeBookings: Map<String, List<SeatBookingSnapshotView>> = emptyMap(),
        private val beforeLoadActiveBookings: suspend () -> Unit = {},
    ) : AccountSeatActionExecutor {
        override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView =
            activeBookings[studentId]?.firstOrNull() ?: SeatBookingSnapshotView()

        override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> {
            beforeLoadActiveBookings()
            return activeBookings[studentId].orEmpty()
        }

        override suspend fun performAction(
            studentId: String,
            action: AccountSeatAction,
            bookingId: String?,
        ): AccountSeatActionExecutionResult =
            AccountSeatActionExecutionResult(
                message = "未使用",
                updatedSnapshot = loadSnapshot(studentId),
            )
    }

    private class FakeSessionRepository : SessionRepository {
        private val state = MutableStateFlow(fakeSession("20230001"))
        private val sessions = linkedMapOf("20230001" to fakeSession("20230001"))

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? = sessions[studentId]

        override fun activeStudentId(): String? = "20230001"

        override fun activate(studentId: String): Boolean = sessions.containsKey(studentId)

        override fun save(session: AuthenticatedSession) = Unit

        override fun save(
            studentId: String,
            session: AuthenticatedSession,
            activate: Boolean,
        ) = Unit

        override fun remove(studentId: String) = Unit

        override fun clear() = Unit
    }

    private companion object {
        fun fakeSession(studentId: String): AuthenticatedSession =
            AuthenticatedSession(
                session = SessionBundle(cookieHeader = "auth=token-$studentId", userId = studentId),
                cookies = emptyList(),
                currentUserJson = """{"id":"$studentId"}""",
                origin = "https://example.com",
                installationId = "install-$studentId",
            )
    }
}
