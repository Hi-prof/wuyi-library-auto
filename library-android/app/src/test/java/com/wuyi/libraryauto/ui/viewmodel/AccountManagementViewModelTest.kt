package com.wuyi.libraryauto.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SchoolAuthRepository
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.ui.repository.account.toSummaryText
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountSeatAction
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutionResult
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.AccountStatusRepository
import com.wuyi.libraryauto.ui.repository.task.SeatBookingSnapshotView
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSnapshot
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSource
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
class AccountManagementViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `init loads saved accounts with auth state and status summary`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(studentId = "20230002", session = fakeSession("20230002"))
            }
        val repository =
            FakeSavedAccountRepository(
                entries =
                    mutableListOf(
                        SavedAccountEntry(
                            studentId = "20230002",
                            password = "beta",
                            preferredRoomName = "自习室圆形二楼",
                            preferredSeatNumber = "166",
                        ),
                        SavedAccountEntry(studentId = "20230001", password = "alpha"),
                    ),
            )
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = sessionRepository,
                accountStatusRepository = buildStatusRepository(repository, sessionRepository),
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        val currentAccount = viewModel.uiState.accounts.first { it.studentId == "20230002" }
        val idleAccount = viewModel.uiState.accounts.first { it.studentId == "20230001" }

        assertThat(currentAccount.isAuthenticated).isTrue()
        assertThat(currentAccount.isActive).isTrue()
        assertThat(currentAccount.preferredSeatLabel).isEqualTo("自习室圆形二楼 / 166")
        assertThat(currentAccount.statusSummary).isEqualTo("当前账号已认证，可直接用于手动预约和自动任务。")
        assertThat(idleAccount.isAuthenticated).isFalse()
        assertThat(idleAccount.statusSummary).isEqualTo("当前未认证，请先刷新认证。")
    }

    @Test
    fun `setCurrentAccount reuses cached session without logging in`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(studentId = "20230001", session = fakeSession("20230001"), activate = false)
            }
        val gateway = DeferredLoginGateway()
        val viewModel =
            AccountManagementViewModel(
                accountRepository =
                    FakeSavedAccountRepository(
                        entries = mutableListOf(SavedAccountEntry(studentId = "20230001", password = "alpha")),
                    ),
                loginGateway = gateway,
                sessionRepository = sessionRepository,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.setCurrentAccount("20230001")
        advanceUntilIdle()

        assertThat(gateway.lastStudentId).isNull()
        assertThat(sessionRepository.activeStudentId()).isEqualTo("20230001")
        assertThat(viewModel.uiState.accounts.single().isActive).isTrue()
    }

    @Test
    fun `setCurrentAccount logs in when session is missing`() = runTest {
        val gateway = DeferredLoginGateway()
        val sessionRepository = FakeSessionRepository()
        val viewModel =
            AccountManagementViewModel(
                accountRepository =
                    FakeSavedAccountRepository(
                        entries = mutableListOf(SavedAccountEntry(studentId = "20230001", password = "alpha")),
                    ),
                loginGateway = gateway,
                sessionRepository = sessionRepository,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.setCurrentAccount("20230001")

        assertThat(viewModel.uiState.pendingStudentId).isEqualTo("20230001")
        assertThat(viewModel.uiState.pendingAction).isEqualTo(AccountPendingAction.SetCurrent)
        assertThat(gateway.lastStudentId).isEqualTo("20230001")
        assertThat(gateway.lastPassword).isEqualTo("alpha")

        sessionRepository.save(studentId = "20230001", session = fakeSession("20230001"))
        gateway.complete(LoginResult.Success)
        advanceUntilIdle()

        assertThat(viewModel.uiState.pendingStudentId).isNull()
        assertThat(viewModel.uiState.pendingAction).isNull()
        assertThat(viewModel.uiState.accounts.single().isAuthenticated).isTrue()
        assertThat(viewModel.uiState.accounts.single().isActive).isTrue()
    }

    @Test
    fun `refreshAuthForAccount updates auth status without changing current account`() = runTest {
        val gateway = DeferredLoginGateway()
        val sessionRepository = FakeSessionRepository()
        val viewModel =
            AccountManagementViewModel(
                accountRepository =
                    FakeSavedAccountRepository(
                        entries = mutableListOf(SavedAccountEntry(studentId = "20230001", password = "alpha")),
                    ),
                loginGateway = gateway,
                sessionRepository = sessionRepository,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.refreshAuthForAccount("20230001")

        assertThat(viewModel.uiState.pendingAction).isEqualTo(AccountPendingAction.RefreshAuth)
        assertThat(gateway.lastStudentId).isEqualTo("20230001")

        sessionRepository.save(studentId = "20230001", session = fakeSession("20230001"), activate = false)
        gateway.complete(LoginResult.Success)
        advanceUntilIdle()

        assertThat(viewModel.uiState.pendingStudentId).isNull()
        assertThat(viewModel.uiState.pendingAction).isNull()
        assertThat(viewModel.uiState.accounts.single().isAuthenticated).isTrue()
        assertThat(viewModel.uiState.accounts.single().isActive).isFalse()
    }

    @Test
    fun `setCurrentAccount keeps user on account list when gateway fails`() = runTest {
        val sessionRepository = FakeSessionRepository()
        val viewModel =
            AccountManagementViewModel(
                accountRepository =
                    FakeSavedAccountRepository(
                        entries = mutableListOf(SavedAccountEntry(studentId = "20230001", password = "alpha")),
                    ),
                loginGateway = ImmediateLoginGateway.failure("网络异常，请稍后重试"),
                sessionRepository = sessionRepository,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.setCurrentAccount("20230001")
        advanceUntilIdle()

        assertThat(viewModel.uiState.pendingStudentId).isNull()
        assertThat(viewModel.uiState.pendingAction).isNull()
        assertThat(viewModel.uiState.errorMessage).isEqualTo("网络异常，请稍后重试")
        assertThat(sessionRepository.currentSession("20230001")).isNull()
    }

    @Test
    fun `removeAccount updates account list and clears session`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(studentId = "20230002", session = fakeSession("20230002"))
                save(studentId = "20230001", session = fakeSession("20230001"), activate = false)
            }
        val repository =
            FakeSavedAccountRepository(
                entries =
                    mutableListOf(
                        SavedAccountEntry(studentId = "20230002", password = "beta"),
                        SavedAccountEntry(studentId = "20230001", password = "alpha"),
                    ),
            )
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = sessionRepository,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.removeAccount("20230002")
        advanceUntilIdle()

        assertThat(viewModel.uiState.accounts.map { it.studentId }).containsExactly("20230001")
        assertThat(sessionRepository.currentSession("20230002")).isNull()
    }

    @Test
    fun `toggleAccountExpanded keeps only one expanded account at a time`() = runTest {
        val viewModel =
            AccountManagementViewModel(
                accountRepository =
                    FakeSavedAccountRepository(
                        entries =
                            mutableListOf(
                                SavedAccountEntry(studentId = "20230002", password = "beta"),
                                SavedAccountEntry(studentId = "20230001", password = "alpha"),
                            ),
                    ),
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = FakeSessionRepository(),
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.toggleAccountExpanded("20230002")
        assertThat(viewModel.uiState.expandedStudentId).isEqualTo("20230002")

        viewModel.toggleAccountExpanded("20230001")
        assertThat(viewModel.uiState.expandedStudentId).isEqualTo("20230001")

        viewModel.toggleAccountExpanded("20230001")
        assertThat(viewModel.uiState.expandedStudentId).isNull()
    }

    @Test
    fun `requestRemoveAccount keeps account until user confirms`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(studentId = "20230002", session = fakeSession("20230002"))
            }
        val repository =
            FakeSavedAccountRepository(
                entries =
                    mutableListOf(
                        SavedAccountEntry(studentId = "20230002", password = "beta"),
                        SavedAccountEntry(studentId = "20230001", password = "alpha"),
                    ),
            )
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = sessionRepository,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.requestRemoveAccount("20230002")

        assertThat(viewModel.uiState.pendingDeleteStudentId).isEqualTo("20230002")
        assertThat(viewModel.uiState.accounts.map { it.studentId }).containsExactly("20230002", "20230001")
        assertThat(sessionRepository.currentSession("20230002")).isNotNull()
    }

    @Test
    fun `confirmRemoveAccount deletes pending account and clears dialog state`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(studentId = "20230002", session = fakeSession("20230002"))
            }
        val repository =
            FakeSavedAccountRepository(
                entries =
                    mutableListOf(
                        SavedAccountEntry(studentId = "20230002", password = "beta"),
                        SavedAccountEntry(studentId = "20230001", password = "alpha"),
                    ),
            )
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = sessionRepository,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.requestRemoveAccount("20230002")
        viewModel.confirmRemoveAccount()
        advanceUntilIdle()

        assertThat(viewModel.uiState.pendingDeleteStudentId).isNull()
        assertThat(viewModel.uiState.accounts.map { it.studentId }).containsExactly("20230001")
        assertThat(sessionRepository.currentSession("20230002")).isNull()
    }

    @Test
    fun `cancelRemoveAccount keeps account list unchanged`() = runTest {
        val repository =
            FakeSavedAccountRepository(
                entries =
                    mutableListOf(
                        SavedAccountEntry(studentId = "20230002", password = "beta"),
                        SavedAccountEntry(studentId = "20230001", password = "alpha"),
                    ),
            )
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = FakeSessionRepository(),
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.requestRemoveAccount("20230002")
        viewModel.cancelRemoveAccount()

        assertThat(viewModel.uiState.pendingDeleteStudentId).isNull()
        assertThat(viewModel.uiState.accounts.map { it.studentId }).containsExactly("20230002", "20230001")
    }

    @Test
    fun `manual refresh exposes dynamic action labels from account status`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(studentId = "20230001", session = fakeSession("20230001"))
            }
        val repository =
            FakeSavedAccountRepository(
                entries = mutableListOf(SavedAccountEntry(studentId = "20230001", password = "alpha")),
            )
        val actionExecutor = FakeAccountSeatActionExecutor()
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = sessionRepository,
                accountStatusRepository =
                    buildStatusRepository(
                        repository = repository,
                        sessionRepository = sessionRepository,
                        accountSeatActionExecutor = actionExecutor,
                    ),
                accountSeatActionExecutor = actionExecutor,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()
        viewModel.refreshAllAccountsManually()
        advanceUntilIdle()

        val account = viewModel.uiState.accounts.single()
        assertThat(account.primaryActionLabel).isEqualTo("立即签到")
        assertThat(account.primaryActionEnabled).isFalse()
        assertThat(account.secondaryActionLabel).isEqualTo("取消预约")
        assertThat(account.actionHint).isEqualTo("未到签到时间")
    }

    @Test
    fun `bulk import saves parsed accounts and clears raw dialog text`() = runTest {
        val repository =
            FakeSavedAccountRepository(
                entries = mutableListOf(SavedAccountEntry(studentId = "EXISTING", password = "alpha")),
            )
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = FakeSessionRepository(),
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.openBulkImportDialog()
        viewModel.updateBulkImportRawText("20230001:secret\nEXISTING:secret2")
        viewModel.submitBulkImport()
        advanceUntilIdle()

        assertThat(repository.entries.map { it.studentId }).containsAtLeast("EXISTING", "20230001")
        assertThat(viewModel.uiState.bulkImportDialog.rawText).isEmpty()
        assertThat(viewModel.uiState.bulkImportDialog.result?.acceptedCount).isEqualTo(1)
        assertThat(viewModel.uiState.bulkImportDialog.result?.toSummaryText()).doesNotContain("secret")
    }

    @Test
    fun `select all visible uses filtered accounts for export`() = runTest {
        val repository =
            FakeSavedAccountRepository(
                entries =
                    mutableListOf(
                        SavedAccountEntry(
                            studentId = "A001",
                            password = "alpha",
                            preferredRoomName = "North",
                            preferredSeatNumber = "166",
                        ),
                        SavedAccountEntry(
                            studentId = "B002",
                            password = "beta",
                            preferredRoomName = "South",
                            preferredSeatNumber = "188",
                        ),
                    ),
            )
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = FakeSessionRepository(),
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.updateSearchQuery("north")
        viewModel.enterMultiSelectMode()
        viewModel.selectAllVisible()
        val export = viewModel.buildExportIntentPayload()

        assertThat(export?.jsonText).contains("A001")
        assertThat(export?.jsonText).doesNotContain("B002")
        assertThat(export?.jsonText).doesNotContain("alpha")
    }

    @Test
    fun `performAccountAction updates action message`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(studentId = "20230001", session = fakeSession("20230001"))
            }
        val repository =
            FakeSavedAccountRepository(
                entries = mutableListOf(SavedAccountEntry(studentId = "20230001", password = "alpha")),
            )
        val actionExecutor = FakeAccountSeatActionExecutor()
        val viewModel =
            AccountManagementViewModel(
                accountRepository = repository,
                loginGateway = ImmediateLoginGateway.success(),
                sessionRepository = sessionRepository,
                accountStatusRepository =
                    buildStatusRepository(
                        repository = repository,
                        sessionRepository = sessionRepository,
                        accountSeatActionExecutor = actionExecutor,
                    ),
                accountSeatActionExecutor = actionExecutor,
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
        advanceUntilIdle()

        viewModel.performAccountAction("20230001", AccountSeatAction.CheckIn)
        advanceUntilIdle()

        assertThat(viewModel.uiState.actionMessage).isEqualTo("已签到")
    }

    private fun buildStatusRepository(
        repository: FakeSavedAccountRepository,
        sessionRepository: FakeSessionRepository,
        accountSeatActionExecutor: AccountSeatActionExecutor? = null,
    ): AccountStatusRepository =
        AccountStatusRepository(
            accountSource = repository,
            sessionRepository = sessionRepository,
            automationPlanDao =
                FakeAutomationPlanDao(
                    listOf(
                        AutomationPlanEntity(
                            planId = "plan-1",
                            studentId = "20230002",
                            roomName = "自习室圆形二楼",
                            seatNumber = "166",
                            mode = "CONTINUOUS",
                            singleDate = null,
                            singleStartTime = null,
                            singleEndTime = null,
                            enabled = true,
                            createdAtEpochSeconds = 100L,
                            updatedAtEpochSeconds = 200L,
                            nextRunAtEpochSeconds = 300L,
                            lastRunAtEpochSeconds = 150L,
                            lastResultMessage = "待执行",
                        ),
                    ),
                ),
            reservationTaskDao =
                FakeReservationTaskDao(
                    mapOf(
                        "20230002" to
                            ReservationTaskEntity(
                                id = "task-1",
                                studentId = "20230002",
                                roomName = "自习室圆形二楼",
                                seatNumber = "166",
                                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                                bookingId = "booking-1",
                                startTimeEpochSeconds = 1_712_800_000L,
                                limitSignAgoSeconds = 900L,
                                expectedMinorsCsv = "",
                                lastError = null,
                            ),
                    ),
                ),
            accountSeatActionExecutor = accountSeatActionExecutor,
        )

    private class FakeSavedAccountRepository(
        val entries: MutableList<SavedAccountEntry>,
    ) : SavedAccountRepository, StoredAccountSource {
        override fun readAll(): List<SavedAccountEntry> = entries.toList()

        override fun remove(studentId: String) {
            entries.removeAll { it.studentId == studentId }
        }

        override fun saveImported(account: SavedAccountEntry) {
            entries.add(account)
        }

        override fun readStoredAccounts(): List<StoredAccountSnapshot> =
            entries.map { account ->
                StoredAccountSnapshot(
                    studentId = account.studentId,
                    password = account.password,
                    preferredRoomName = account.preferredRoomName,
                    preferredSeatNumber = account.preferredSeatNumber,
                )
            }
    }

    private class FakeAutomationPlanDao(
        plans: List<AutomationPlanEntity>,
    ) : AutomationPlanDao {
        private val state = MutableStateFlow(plans)

        override suspend fun upsert(plan: AutomationPlanEntity) {
            state.value = listOf(plan) + state.value.filterNot { it.planId == plan.planId }
        }

        override fun observeAll(): Flow<List<AutomationPlanEntity>> = state

        override fun observeEnabledPlans(): Flow<List<AutomationPlanEntity>> =
            MutableStateFlow(state.value.filter { it.enabled })

        override suspend fun findById(planId: String): AutomationPlanEntity? =
            state.value.firstOrNull { it.planId == planId }

        override suspend fun findLatestEnabledByStudentId(studentId: String): AutomationPlanEntity? =
            state.value
                .filter { it.studentId == studentId && it.enabled }
                .sortedWith(compareByDescending<AutomationPlanEntity> { it.updatedAtEpochSeconds }.thenBy { it.planId })
                .firstOrNull()

        override suspend fun deleteById(planId: String) {
            state.value = state.value.filterNot { it.planId == planId }
        }

        override suspend fun findAll(): List<AutomationPlanEntity> = state.value

        override suspend fun deleteByPlanIdPrefix(planIdPrefix: String) {
            state.value = state.value.filterNot { it.planId.startsWith(planIdPrefix) }
        }
    }

    private class FakeReservationTaskDao(
        private val latestTasks: Map<String, ReservationTaskEntity>,
    ) : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit

        override suspend fun findById(id: String): ReservationTaskEntity? =
            latestTasks.values.firstOrNull { it.id == id }

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? =
            latestTasks[studentId]

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> =
            latestTasks[studentId]?.let { listOf(it) } ?: emptyList()

        override fun observeAll(): Flow<List<ReservationTaskEntity>> =
            MutableStateFlow(latestTasks.values.toList())

        override suspend fun listAll(): List<ReservationTaskEntity> = latestTasks.values.toList()
    }

    private class FakeSessionRepository : SessionRepository {
        private val state = MutableStateFlow<AuthenticatedSession?>(null)
        private val sessions = linkedMapOf<String, AuthenticatedSession>()
        private var activeStudentId: String? = null

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? = sessions[studentId]

        override fun activeStudentId(): String? = activeStudentId

        override fun activate(studentId: String): Boolean {
            val savedSession = sessions[studentId] ?: return false
            activeStudentId = studentId
            state.value = savedSession
            return true
        }

        override fun save(session: AuthenticatedSession) {
            val studentId = activeStudentId ?: return
            save(studentId = studentId, session = session)
        }

        override fun save(
            studentId: String,
            session: AuthenticatedSession,
            activate: Boolean,
        ) {
            sessions[studentId] = session
            if (activate) {
                activeStudentId = studentId
                state.value = session
            }
        }

        override fun remove(studentId: String) {
            sessions.remove(studentId)
            if (activeStudentId == studentId) {
                activeStudentId = null
                state.value = null
            }
        }

        override fun clear() {
            sessions.clear()
            activeStudentId = null
            state.value = null
        }
    }

    private class ImmediateLoginGateway(
        private val result: LoginResult,
    ) : LoginGateway {
        override suspend fun login(studentId: String, password: String): LoginResult = result

        companion object {
            fun success(): ImmediateLoginGateway = ImmediateLoginGateway(LoginResult.Success)

            fun failure(message: String): ImmediateLoginGateway =
                ImmediateLoginGateway(LoginResult.Failure(message))
        }
    }

    private class DeferredLoginGateway : LoginGateway {
        private val deferred = CompletableDeferred<LoginResult>()

        var lastStudentId: String? = null
            private set
        var lastPassword: String? = null
            private set

        override suspend fun login(studentId: String, password: String): LoginResult {
            lastStudentId = studentId
            lastPassword = password
            return deferred.await()
        }

        fun complete(result: LoginResult) {
            deferred.complete(result)
        }
    }

    private class FakeAccountSeatActionExecutor : AccountSeatActionExecutor {
        override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView =
            SeatBookingSnapshotView(
                liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                bookingId = "booking-1",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                beginLabel = "2026-04-11 09:00",
                statusLabel = "待签到",
                checkinWindowOpen = false,
            )

        override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> =
            listOf(loadSnapshot(studentId))

        override suspend fun performAction(
            studentId: String,
            action: AccountSeatAction,
            bookingId: String?,
        ): AccountSeatActionExecutionResult =
            AccountSeatActionExecutionResult(
                message = "已签到",
                updatedSnapshot =
                    SeatBookingSnapshotView(
                        liveState = SeatBookingLiveState.ACTIVE_SIGNED_IN,
                        bookingId = "booking-1",
                        roomName = "自习室圆形二楼",
                        seatNumber = "166",
                        beginLabel = "2026-04-11 09:00",
                        statusLabel = "已签到",
                        checkinWindowOpen = false,
                    ),
            )
    }

    private fun fakeSession(userId: String): AuthenticatedSession =
        AuthenticatedSession(
            session = SessionBundle(cookieHeader = "auth=token-$userId", userId = userId),
            cookies =
                listOf(
                    SchoolAuthRepository.CookieRecord(
                        name = "auth",
                        value = "token-$userId",
                        path = "/",
                    ),
                ),
            currentUserJson = """{"id":"$userId"}""",
            origin = "https://example.com",
            installationId = "install-$userId",
        )
}
