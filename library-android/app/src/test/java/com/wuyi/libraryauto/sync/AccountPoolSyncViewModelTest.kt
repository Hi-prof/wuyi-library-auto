package com.wuyi.libraryauto.sync

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.db.ActiveAccountDao
import com.wuyi.libraryauto.core.storage.db.ActiveAccountEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 任务 12.8：AccountPoolSyncViewModel 单元测试。
 *
 * 覆盖：
 * - `triggerManualSync` 成功路径：进入 InProgress、最终落在 LastResult.Success，并向
 *   [SyncStatusIndicator] 上报 Enabled。
 * - 失败路径（401）：state 落在 LastResult.Failed，indicator 切到 disabled_unreachable。
 * - 并发触发：重入调用不会发起额外网络请求。
 *
 * 实现说明：
 * - 这里直接实现 [AccountPoolApi] 的进程内 fake，避免引入真实 OkHttp / MockWebServer。
 *   原因：ViewModel 通过 `viewModelScope.launch { withContext(ioDispatcher) { ... } }`
 *   触发同步，单测希望靠 `advanceUntilIdle()` 推进 [TestDispatcher]；而真实 OkHttp 走自己
 *   的线程池跑 IO，对 [TestDispatcher] 不可见，`advanceUntilIdle()` 无法等待真实网络回包，
 *   会观察到状态停留在 InProgress。改用 fake API 后整个协程链路都在测试调度器上完成。
 * - 仓库的错误归类逻辑（[AccountPoolSyncResult.Error.fromThrowable]）在
 *   [AccountPoolSyncRepositoryTest] 中通过 MockWebServer 已有覆盖；这里只需要让 fake API
 *   抛出 [HttpException] 401 即可触发 ViewModel 的失败分支。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountPoolSyncViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SyncStatusIndicator.installDefaultForTesting(null)
    }

    @Test
    fun `triggerManualSync transitions Idle to InProgress to Success`() = runTest(dispatcher) {
        val api =
            FakeAccountPoolApi(
                listResponse = {
                    ActiveAccountListResponse(
                        serverTime = "2026-04-26T08:30:00Z",
                        accounts =
                            listOf(
                                ActiveAccountListItem(
                                    accountId = 17,
                                    studentId = "20231121130",
                                    displayName = "张三",
                                    poolStatus = "active",
                                    updatedAt = "2026-04-26T08:25:11Z",
                                ),
                            ),
                    )
                },
            )
        val indicator = SyncStatusIndicator(initialConfigured = true)
        val viewModel = buildViewModel(api = api, indicator = indicator)

        viewModel.triggerManualSync()
        // launch 任务被 enqueue 但还未执行：状态应已切到 InProgress。
        assertThat(viewModel.state.value).isEqualTo(AccountPoolSyncViewModel.ManualSyncState.InProgress)

        advanceUntilIdle()

        val finalState = viewModel.state.value as AccountPoolSyncViewModel.ManualSyncState.LastResult
        val outcome = finalState.outcome as AccountPoolSyncViewModel.SyncResult.Success
        assertThat(outcome.count).isEqualTo(1)
        assertThat(outcome.serverTime).isEqualTo("2026-04-26T08:30:00Z")
        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.Enabled)
    }

    @Test
    fun `triggerManualSync on unauthorized reports failure and disables sync button`() =
        runTest(dispatcher) {
            val api = FakeAccountPoolApi(listResponseError = { unauthorizedException() })
            val indicator = SyncStatusIndicator(initialConfigured = true)
            val viewModel = buildViewModel(api = api, indicator = indicator)

            viewModel.triggerManualSync()
            advanceUntilIdle()

            val finalState =
                viewModel.state.value as AccountPoolSyncViewModel.ManualSyncState.LastResult
            val outcome = finalState.outcome as AccountPoolSyncViewModel.SyncResult.Failed
            assertThat(outcome.reason).isEqualTo(SyncStatusIndicator.REASON_UNAUTHORIZED)
            assertThat(outcome.statusCode).isEqualTo(401)
            val buttonState = indicator.syncButtonState.value as SyncButtonState.DisabledUnreachable
            assertThat(buttonState.reason).isEqualTo(SyncStatusIndicator.REASON_UNAUTHORIZED)
        }

    @Test
    fun `concurrent triggerManualSync collapses to single network request`() =
        runTest(dispatcher) {
            val api =
                FakeAccountPoolApi(
                    listResponse = {
                        ActiveAccountListResponse(
                            serverTime = "2026-04-26T08:30:00Z",
                            accounts = emptyList(),
                        )
                    },
                )
            val viewModel = buildViewModel(api = api, indicator = SyncStatusIndicator(initialConfigured = true))

            viewModel.triggerManualSync()
            // 第二次点击在同步进行中：应当被合并，不发起额外网络请求。
            viewModel.triggerManualSync()
            advanceUntilIdle()

            assertThat(api.listCalls.get()).isEqualTo(1)
            assertThat(viewModel.state.value)
                .isInstanceOf(AccountPoolSyncViewModel.ManualSyncState.LastResult::class.java)
        }

    @Test
    fun `acknowledgeLastResult resets state to Idle`() = runTest(dispatcher) {
        val api =
            FakeAccountPoolApi(
                listResponse = {
                    ActiveAccountListResponse(
                        serverTime = "2026-04-26T08:30:00Z",
                        accounts = emptyList(),
                    )
                },
            )
        val viewModel = buildViewModel(api = api, indicator = SyncStatusIndicator(initialConfigured = true))

        viewModel.triggerManualSync()
        advanceUntilIdle()
        assertThat(viewModel.state.value)
            .isInstanceOf(AccountPoolSyncViewModel.ManualSyncState.LastResult::class.java)

        viewModel.acknowledgeLastResult()
        assertThat(viewModel.state.value).isEqualTo(AccountPoolSyncViewModel.ManualSyncState.Idle)
    }

    private fun buildViewModel(
        api: AccountPoolApi,
        indicator: SyncStatusIndicator,
    ): AccountPoolSyncViewModel {
        val repository =
            AccountPoolSyncRepository(
                api = api,
                activeAccountDao = MemoryDao(),
                nowEpochSeconds = { 1_700_000_000L },
            )
        return AccountPoolSyncViewModel(
            repository = repository,
            indicator = indicator,
            ioDispatcher = dispatcher,
        )
    }

    private fun unauthorizedException(): HttpException {
        // 复用 Retrofit 的 HttpException 触发仓库内部的状态码归类（401 → Unauthorized）。
        val errorBody = "{}".toResponseBody("application/json".toMediaType())
        val errorResponse = Response.error<ActiveAccountListResponse>(401, errorBody)
        return HttpException(errorResponse)
    }

    /**
     * 进程内 fake：仅实现 ViewModel 触达的 `listActiveAccounts`，其余方法在被误用时立刻
     * 报错，避免悄悄返回空响应掩盖测试覆盖盲区。
     */
    private class FakeAccountPoolApi(
        private val listResponse: () -> ActiveAccountListResponse = {
            ActiveAccountListResponse(serverTime = "1970-01-01T00:00:00Z", accounts = emptyList())
        },
        private val listResponseError: (() -> Throwable)? = null,
    ) : AccountPoolApi {
        val listCalls: AtomicInteger = AtomicInteger(0)

        override suspend fun listActiveAccounts(): ActiveAccountListResponse {
            listCalls.incrementAndGet()
            listResponseError?.let { throw it() }
            return listResponse()
        }

        override suspend fun getActiveAccountDetail(accountId: Long): ActiveAccountDetailResponse =
            error("FakeAccountPoolApi.getActiveAccountDetail 未被该测试覆盖")

        override suspend fun reportBlacklistEvent(
            accountId: Long,
            request: BlacklistEventRequest,
        ): BlacklistEventResponse =
            error("FakeAccountPoolApi.reportBlacklistEvent 未被该测试覆盖")

        override suspend fun listAutomationTasks(accountId: Long): AutomationTasksResponse =
            error("FakeAccountPoolApi.listAutomationTasks 未被该测试覆盖")

        override suspend fun upsertAutomationTask(
            accountId: Long,
            taskId: Long,
            request: AutomationTaskUpsertRequest,
        ): AutomationTaskUpsertResponse =
            error("FakeAccountPoolApi.upsertAutomationTask 未被该测试覆盖")

        override suspend fun deleteAutomationTask(
            accountId: Long,
            taskId: Long,
            revision: Long,
        ): AutomationTaskDeleteResponse =
            error("FakeAccountPoolApi.deleteAutomationTask 未被该测试覆盖")
    }

    /** 进程内伪 DAO：避免在纯 JVM 单元测试里启动 Room 数据库。 */
    private class MemoryDao : ActiveAccountDao {
        private val rows: MutableMap<Long, ActiveAccountEntity> = linkedMapOf()

        override suspend fun upsert(account: ActiveAccountEntity) {
            rows[account.accountId] = account
        }

        override suspend fun upsertAll(accounts: List<ActiveAccountEntity>) {
            for (account in accounts) {
                rows[account.accountId] = account
            }
        }

        override suspend fun findAll(): List<ActiveAccountEntity> = rows.values.toList()

        override fun observeAll(): Flow<List<ActiveAccountEntity>> = flowOf(rows.values.toList())

        override suspend fun findById(accountId: Long): ActiveAccountEntity? = rows[accountId]

        override suspend fun findByStudentId(studentId: String): ActiveAccountEntity? =
            rows.values.firstOrNull { it.studentId == studentId }

        override suspend fun deleteByStudentId(studentId: String) {
            rows.values
                .filter { it.studentId == studentId }
                .map { it.accountId }
                .forEach { rows.remove(it) }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
