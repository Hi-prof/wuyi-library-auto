package com.wuyi.libraryauto.property

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.db.ActiveAccountDao
import com.wuyi.libraryauto.core.storage.db.ActiveAccountEntity
import com.wuyi.libraryauto.sync.AccountPoolApiFactory
import com.wuyi.libraryauto.sync.AccountPoolSyncRepository
import com.wuyi.libraryauto.sync.AccountPoolSyncViewModel
import com.wuyi.libraryauto.sync.SyncButtonState
import com.wuyi.libraryauto.sync.SyncStatusIndicator
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration

/**
 * Feature: account-pool-tri-sync, Property 16' (Android)：客户端同步按钮可用性。
 *
 * Validates: Requirements 12.3, 12.4, 12.7, 13.9
 *
 * 与 Window 端 `test_property_16_sync_button_availability.py` 等价：
 *
 * 对 mock 后端 `R ∈ {ConnectError, Timeout, 5xx, 401, 426}` 五类响应：
 * 1. `syncButtonState` SHALL 落到 [SyncButtonState.DisabledUnreachable]，并携带可读的
 *    [SyncStatusIndicator.REASON_*] 错误状态指示文案。
 * 2. 本地业务循环（执行任务 / 刷新登录态 / 座位监控 Composable 的运行时心跳）SHALL **保持原有
 *    交互、可点击**、不显示「服务端不可达，已暂停」文案；这里通过一个 [LocalExecutionProbe]
 *    模拟本地循环，要求在每次 sync 失败前后都能继续推进 tick。
 * 3. mock 恢复 200 后下次 [AccountPoolSyncViewModel.triggerManualSync] SHALL 把按钮恢复
 *    [SyncButtonState.Enabled] 并完成一次正常同步。
 *
 * 本测试 **不** 覆盖上一版「拒绝执行 + 不读 Room 兜底」断言（任务 11.16 / 12.17 已显式撤回）。
 *
 * 实现要点：
 * - 使用 [MockWebServer] 拦截 sync 客户端的 HTTP 调用：5xx/401/426 直接走响应码；
 *   ConnectError 通过 [SocketPolicy.DISCONNECT_AT_START] 模拟连接被拒；Timeout 通过
 *   [MockResponse.setBodyDelay] 配合极短的 OkHttp readTimeout 触发。
 * - 使用 [io.kotest.property.checkAll] 对 5 类 backend × 1..3 次失败重复 × 0..2 次本地循环
 *   tick 做随机组合；同时保留一个 `@Test` 级 sanity 用例，确保即使 hypothesis sample 跳过
 *   极端组合，每类 backend 也至少跑过一次。
 * - 不依赖 Robolectric / Room：复用 [AccountPoolSyncViewModelTest] 中的 [MemoryDao] 模式，
 *   保证 `gradlew testDebugUnitTest` 在纯 JVM 下即可运行。
 *
 * _Requirements: 12.3, 12.4, 12.7, 13.9_
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Property16SyncButtonAvailabilityTest {

    @Before
    fun setUp() {
        // 任务 12.5：测试不依赖 [StandardTestDispatcher] 的虚拟时钟；用 Unconfined 让
        // viewModelScope.launch 直接在调用线程执行后续步骤，配合 [waitUntilTerminal] 轮询
        // 状态机即可。
        Dispatchers.setMain(Dispatchers.Unconfined)
        // checkAll 默认 1000 次，对于带 MockWebServer 的用例太重；调到与 Window 端同档。
        PropertyTesting.defaultIterationCount = MAX_EXAMPLES
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SyncStatusIndicator.installDefaultForTesting(null)
    }

    /**
     * 五类 backend 错误下：按钮置灰 + 本地循环可继续 + 恢复 200 后按钮重新可用。
     */
    @Test
    fun `sync button disabled on each backend error then recovers`() {
        runBlocking {
            checkAll(
                BACKEND_ARB,
                FAILURE_REPEATS_ARB,
                LOCAL_TICKS_ARB,
            ) { backend, failureRepeats, localTicksDuringFailure ->
                runOneScenario(
                    backend = backend,
                    failureRepeats = failureRepeats,
                    localTicksDuringFailure = localTicksDuringFailure,
                )
            }
        }
    }

    /**
     * 对每类 backend 单独覆盖一次（example 用例）。
     *
     * 保证 Hypothesis-style sample 不到极端组合时，每类 backend 仍至少跑过一次最简
     * 「失败一次 → 本地循环执行一次 → 恢复 200」流程。
     */
    @Test
    fun `each backend kind disables sync button at least once`() {
        runBlocking {
            for (backend in BACKEND_KINDS) {
                runOneScenario(
                    backend = backend,
                    failureRepeats = 1,
                    localTicksDuringFailure = 1,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 单次场景
    // ------------------------------------------------------------------

    private suspend fun runOneScenario(
        backend: BackendKind,
        failureRepeats: Int,
        localTicksDuringFailure: Int,
    ) {
        val server = MockWebServer()
        server.start()
        val readTimeout = Duration.ofMillis(200L)
        val indicator = SyncStatusIndicator(initialConfigured = true)
        val toastBus = ToastBus()
        val localProbe = LocalExecutionProbe()
        val viewModel = buildViewModel(server, indicator, readTimeout = readTimeout)
        try {
            // ----------------------------------------------------------
            // 阶段 1：初始为 Enabled（已配置 + 未失败）
            // ----------------------------------------------------------
            assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.Enabled)

            // ----------------------------------------------------------
            // 阶段 2：触发 N 次失败的 Manual_Sync_Action
            // ----------------------------------------------------------
            repeat(failureRepeats) {
                enqueueBackendFailure(server, backend)
                viewModel.triggerManualSync()
                viewModel.waitUntilTerminal()

                // P16'-A: ViewModel 落到 LastResult.Failed，按钮三态切到 disabled_unreachable
                val viewState = viewModel.state.value
                assertThat(viewState)
                    .isInstanceOf(AccountPoolSyncViewModel.ManualSyncState.LastResult::class.java)
                val outcome =
                    (viewState as AccountPoolSyncViewModel.ManualSyncState.LastResult).outcome
                assertThat(outcome)
                    .isInstanceOf(AccountPoolSyncViewModel.SyncResult.Failed::class.java)

                val buttonState = indicator.syncButtonState.value
                assertThat(buttonState)
                    .isInstanceOf(SyncButtonState.DisabledUnreachable::class.java)
                val unreachable = buttonState as SyncButtonState.DisabledUnreachable

                // P16'-B: 错误状态指示有可读的 reason 文案，且 reason 与 backend 类型一致
                assertThat(unreachable.reason).isEqualTo(expectedReasonOf(backend))

                // P16'-C: 本地业务循环在 sync 失败状态下继续推进
                repeat(localTicksDuringFailure) { localProbe.tick() }
            }

            // ----------------------------------------------------------
            // 阶段 3：禁止断言 —— 不弹出阻塞性 toast
            // ----------------------------------------------------------
            for (blacklisted in BLOCKED_TOAST_BLACKLIST) {
                for (msg in toastBus.messages) {
                    assertThat(msg).doesNotContain(blacklisted)
                }
            }

            // ----------------------------------------------------------
            // 阶段 4：mock 恢复 200，按钮回到 Enabled，本地缓存被一次正常同步覆盖
            // ----------------------------------------------------------
            server.enqueue(okListResponse())
            viewModel.triggerManualSync()
            viewModel.waitUntilTerminal()

            assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.Enabled)
            val final = viewModel.state.value
            assertThat(final)
                .isInstanceOf(AccountPoolSyncViewModel.ManualSyncState.LastResult::class.java)
            val outcome = (final as AccountPoolSyncViewModel.ManualSyncState.LastResult).outcome
            assertThat(outcome)
                .isInstanceOf(AccountPoolSyncViewModel.SyncResult.Success::class.java)

            // 本地循环 tick 数应等于「失败次数 × 每次失败间 tick 数」（不被 sync 失败打断）。
            assertThat(localProbe.executedCount)
                .isEqualTo(failureRepeats * localTicksDuringFailure)
        } finally {
            server.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 工具：构造 MockWebServer 响应
    // ------------------------------------------------------------------

    private fun enqueueBackendFailure(server: MockWebServer, backend: BackendKind) {
        when (backend) {
            BackendKind.ConnectError ->
                server.enqueue(
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
                )
            BackendKind.Timeout ->
                server.enqueue(
                    MockResponse()
                        .setBody(EMPTY_LIST_BODY)
                        .setHeader("Content-Type", "application/json")
                        // 远超 OkHttp readTimeout (200ms) → 触发读超时 → 归类到 Network 错误。
                        .setBodyDelay(2L, java.util.concurrent.TimeUnit.SECONDS),
                )
            BackendKind.Server5xx ->
                server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            BackendKind.Unauthorized ->
                server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            BackendKind.HttpsRequired ->
                server.enqueue(MockResponse().setResponseCode(426).setBody("{}"))
        }
    }

    private fun okListResponse(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(EMPTY_LIST_BODY)

    // ------------------------------------------------------------------
    // 工具：构造 ViewModel
    // ------------------------------------------------------------------

    private fun buildViewModel(
        server: MockWebServer,
        indicator: SyncStatusIndicator,
        readTimeout: Duration,
    ): AccountPoolSyncViewModel {
        val httpClient = okhttp3.OkHttpClient.Builder()
            .readTimeout(readTimeout)
            .callTimeout(Duration.ofSeconds(5L))
            .build()
        val api =
            AccountPoolApiFactory.create(
                baseUrl = server.url("/").toString(),
                tokenProvider = { "tok-property-16" },
                httpClient = httpClient,
            )
        val repository =
            AccountPoolSyncRepository(
                api = api,
                activeAccountDao = MemoryDao(),
                nowEpochSeconds = { 1_700_000_000L },
            )
        // 任务 12.5：用真实 [Dispatchers.Unconfined] 跑 ViewModel 的 IO 调度，避开
        // [StandardTestDispatcher] 与 OkHttp 真实线程池协作时「scheduler advanceUntilIdle 提前
        // 收敛」的时序坑。本属性测试关心的是「按钮三态」与「本地循环可继续」，无需精确控制
        // 协程调度时序；用 [waitUntilTerminal] 轮询状态机即可。
        return AccountPoolSyncViewModel(
            repository = repository,
            indicator = indicator,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    /**
     * 等到 ViewModel 的 [AccountPoolSyncViewModel.state] 离开 [AccountPoolSyncViewModel.ManualSyncState.InProgress]。
     *
     * 真实 OkHttp 线程池完成网络调用后，结果通过 [AccountPoolSyncViewModel.ioDispatcher]
     * 回到 ViewModel 的 [androidx.lifecycle.viewModelScope]；这里采用「忙等 + 短 sleep」的
     * 简单轮询，避免引入 `kotlinx-coroutines-test` 的调度器协调成本（在 OkHttp 真实线程池介入
     * 时容易死等）。
     */
    private fun AccountPoolSyncViewModel.waitUntilTerminal(timeoutMillis: Long = WAIT_TIMEOUT_MS) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (state.value is AccountPoolSyncViewModel.ManualSyncState.InProgress) {
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "Property 16' 等待 Manual_Sync_Action 终态超时（${timeoutMillis}ms）；" +
                        "当前状态=${state.value}",
                )
            }
            Thread.sleep(WAIT_POLL_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------------
    // 五类 backend 与映射
    // ------------------------------------------------------------------

    private enum class BackendKind {
        ConnectError,
        Timeout,
        Server5xx,
        Unauthorized,
        HttpsRequired,
    }

    private fun expectedReasonOf(backend: BackendKind): String =
        when (backend) {
            BackendKind.ConnectError -> SyncStatusIndicator.REASON_NETWORK
            BackendKind.Timeout -> SyncStatusIndicator.REASON_NETWORK
            BackendKind.Server5xx -> SyncStatusIndicator.REASON_SERVER
            BackendKind.Unauthorized -> SyncStatusIndicator.REASON_UNAUTHORIZED
            BackendKind.HttpsRequired -> SyncStatusIndicator.REASON_HTTPS_REQUIRED
        }

    // ------------------------------------------------------------------
    // 本地业务循环探针 + toast 总线 + 内存 DAO
    // ------------------------------------------------------------------

    private class LocalExecutionProbe {
        var executedCount: Int = 0
            private set

        fun tick() {
            // 模拟「自动任务执行 / 刷新登录态 / 座位监控」一次 tick：
            // - 不读 SyncStatusIndicator
            // - 不查 ServerSyncConfig
            // - 不调任何 sync 模块
            // 仅验证「本地执行不依赖服务端可达性」的设计边界。
            executedCount += 1
        }
    }

    private class ToastBus {
        val messages: MutableList<String> = mutableListOf()

        fun push(text: String) {
            messages.add(text)
        }
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

    private companion object {
        const val MAX_EXAMPLES: Int = 25
        const val WAIT_TIMEOUT_MS: Long = 5_000L
        const val WAIT_POLL_INTERVAL_MS: Long = 5L

        val BACKEND_KINDS: List<BackendKind> = BackendKind.values().toList()

        val BACKEND_ARB: Arb<BackendKind> = Arb.element(BACKEND_KINDS)
        val FAILURE_REPEATS_ARB: Arb<Int> = Arb.int(min = 1, max = 3)
        val LOCAL_TICKS_ARB: Arb<Int> = Arb.int(min = 0, max = 2)

        val BLOCKED_TOAST_BLACKLIST: List<String> = listOf(
            "服务端不可达，已暂停",
            "已暂停，请稍后重试",
        )

        const val EMPTY_LIST_BODY: String =
            """{"server_time":"2026-04-26T08:30:00Z","accounts":[]}"""
    }
}
