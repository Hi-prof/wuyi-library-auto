package com.wuyi.libraryauto.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SchoolAuthRepository
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AccountManagementViewModelBatchSignInTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `startBatchCheckIn dispatches without blocking main thread`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = FakeBatchCheckInRunner(runGate = gate)
        val viewModel = buildViewModel(runner = runner)

        val elapsedMillis = measureTimeMillis {
            viewModel.startBatchCheckIn()
        }

        assertThat(elapsedMillis).isLessThan(100)
        assertThat(viewModel.uiState.batchCheckInState).isInstanceOf(BatchCheckInState.Running::class.java)
        assertThat(runner.runCalls).isEqualTo(1)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `startBatchCheckIn while running returns cooldown without duplicate run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = FakeBatchCheckInRunner(runGate = gate)
        val viewModel = buildViewModel(runner = runner)

        viewModel.startBatchCheckIn()
        viewModel.startBatchCheckIn()

        val state = viewModel.uiState.batchCheckInState
        assertThat(state).isInstanceOf(BatchCheckInState.Cooldown::class.java)
        assertThat((state as BatchCheckInState.Cooldown).message).contains("正在执行")
        assertThat(runner.runCalls).isEqualTo(1)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `retryBatchCheckIn only refreshes failed row`() = runTest {
        val runner =
            FakeBatchCheckInRunner(
                batchReport =
                    BatchCheckInReport(
                        rows =
                            listOf(
                                BatchCheckInRowState(
                                    studentId = "20230001",
                                    status = BatchCheckInRowStatus.Success,
                                    message = "已签到",
                                    canRetry = false,
                                ),
                                BatchCheckInRowState(
                                    studentId = "20230002",
                                    status = BatchCheckInRowStatus.Failed,
                                    message = "网络异常",
                                    canRetry = true,
                                ),
                            ),
                        summaryMessage = "批量签到进度：成功 1，失败 1，跳过 0。",
                    ),
                retryResult =
                    BatchCheckInRowState(
                        studentId = "20230002",
                        status = BatchCheckInRowStatus.Success,
                        message = "重试成功",
                        canRetry = false,
                    ),
            )
        val viewModel = buildViewModel(runner = runner)

        viewModel.startBatchCheckIn()
        advanceUntilIdle()
        viewModel.retryBatchCheckIn("20230002")
        advanceUntilIdle()

        val rows = viewModel.uiState.batchCheckInState.rows
        assertThat(rows.map { row -> row.studentId to row.message })
            .containsExactly("20230001" to "已签到", "20230002" to "重试成功")
            .inOrder()
        assertThat(runner.retryStudentIds).containsExactly("20230002")
    }

    @Test
    fun `batch summary with prefix is written to execution log`() = runTest {
        val writer = RecordingBatchProgressWriter()
        val viewModel = buildViewModel(
            runner =
                FakeBatchCheckInRunner(
                    batchReport =
                        BatchCheckInReport(
                            rows =
                                listOf(
                                    BatchCheckInRowState(
                                        studentId = "20230001",
                                        status = BatchCheckInRowStatus.Success,
                                        message = "已签到",
                                        canRetry = false,
                                    ),
                                ),
                            summaryMessage = "完成：成功 1，失败 0，跳过 0。",
                        ),
                ),
            writer = writer,
        )

        viewModel.startBatchCheckIn()
        advanceUntilIdle()

        assertThat(writer.messages).isNotEmpty()
        assertThat(writer.messages.all { message -> message.startsWith("批量签到") }).isTrue()
    }

    private fun buildViewModel(
        runner: BatchCheckInRunner,
        writer: BatchCheckInProgressWriter = BatchCheckInProgressWriter.Noop,
    ): AccountManagementViewModel {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(studentId = "20230001", session = fakeSession("20230001"))
                save(studentId = "20230002", session = fakeSession("20230002"))
            }
        return AccountManagementViewModel(
            accountRepository =
                FakeSavedAccountRepository(
                    listOf(
                        SavedAccountEntry(studentId = "20230001", password = "pw1"),
                        SavedAccountEntry(studentId = "20230002", password = "pw2"),
                    ),
                ),
            loginGateway = ImmediateLoginGateway,
            sessionRepository = sessionRepository,
            batchCheckInRunner = runner,
            batchProgressWriter = writer,
        )
    }

    private class FakeBatchCheckInRunner(
        private val batchReport: BatchCheckInReport =
            BatchCheckInReport(
                rows =
                    listOf(
                        BatchCheckInRowState(
                            studentId = "20230001",
                            status = BatchCheckInRowStatus.Success,
                            message = "已签到",
                            canRetry = false,
                        ),
                        BatchCheckInRowState(
                            studentId = "20230002",
                            status = BatchCheckInRowStatus.Skipped,
                            message = "没有处于签到窗口的预约",
                            canRetry = false,
                        ),
                    ),
                summaryMessage = "批量签到进度：成功 1，失败 0，跳过 1。",
            ),
        private val retryResult: BatchCheckInRowState =
            BatchCheckInRowState(
                studentId = "20230002",
                status = BatchCheckInRowStatus.Success,
                message = "重试成功",
                canRetry = false,
            ),
        private val runGate: CompletableDeferred<Unit>? = null,
    ) : BatchCheckInRunner {
        var runCalls = 0
            private set
        val retryStudentIds = mutableListOf<String>()

        override suspend fun runBatch(onProgress: suspend (BatchCheckInReport) -> Unit): BatchCheckInReport {
            runCalls += 1
            runGate?.await()
            onProgress(batchReport)
            return batchReport
        }

        override suspend fun retry(studentId: String): BatchCheckInRowState {
            retryStudentIds += studentId
            return retryResult.copy(studentId = studentId)
        }
    }

    private class RecordingBatchProgressWriter : BatchCheckInProgressWriter {
        val messages = mutableListOf<String>()

        override suspend fun record(message: String) {
            messages += message
        }
    }

    private class FakeSavedAccountRepository(
        private val entries: List<SavedAccountEntry>,
    ) : SavedAccountRepository {
        override fun readAll(): List<SavedAccountEntry> = entries

        override fun remove(studentId: String) = Unit
    }

    private object ImmediateLoginGateway : LoginGateway {
        override suspend fun login(
            studentId: String,
            password: String,
        ): LoginResult = LoginResult.Success
    }

    private class FakeSessionRepository : SessionRepository {
        private val state = MutableStateFlow<AuthenticatedSession?>(null)
        private val sessions = linkedMapOf<String, AuthenticatedSession>()

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? = sessions[studentId]

        override fun activeStudentId(): String? = sessions.keys.firstOrNull()

        override fun activate(studentId: String): Boolean {
            state.value = sessions[studentId] ?: return false
            return true
        }

        override fun save(session: AuthenticatedSession) {
            save(session.session.userId, session)
        }

        override fun save(
            studentId: String,
            session: AuthenticatedSession,
            activate: Boolean,
        ) {
            sessions[studentId] = session
            if (activate) {
                state.value = session
            }
        }

        override fun remove(studentId: String) {
            sessions.remove(studentId)
        }

        override fun clear() {
            sessions.clear()
            state.value = null
        }
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
