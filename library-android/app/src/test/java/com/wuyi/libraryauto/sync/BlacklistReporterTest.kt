package com.wuyi.libraryauto.sync

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.db.AppDatabase
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadDao
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 任务 12.9 单元测试：BlacklistReporter。
 *
 * 覆盖要点（与 Requirement 15.1 / 15.2 / 15.3 一致）：
 * - 双开关任一关闭时不入队、不调用 enqueue、不触达 PendingTaskUpload 表，仅返回 SkippedLocalOnly；
 * - 双开关全开启时把 BlacklistEventRequest 转交 enqueue 函数，字段映射正确；
 * - 入队过程抛异常时返回 EnqueueFailed 且不抛出给调用方，模拟「调用方继续走本地流程」语义；
 * - 与真实 [AutomationTaskUploader] 配合（fromUploader 工厂）时确实写入 PendingTaskUpload 队列。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class BlacklistReporterTest {
    private lateinit var database: AppDatabase
    private lateinit var pendingDao: PendingTaskUploadDao
    private lateinit var serverSyncConfig: ServerSyncConfig
    private lateinit var uploader: AutomationTaskUploader

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        pendingDao = database.pendingTaskUploadDao()

        val prefs: SharedPreferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_blacklist_reporter", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        serverSyncConfig = ServerSyncConfig(prefs)

        uploader = AutomationTaskUploader(
            context = context,
            pendingDao = pendingDao,
            nowEpochSeconds = { 1_700_000_000L },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun configureUploadEnabled() {
        serverSyncConfig.saveAll(
            baseUrl = "https://example.com",
            bearerToken = "tok-abc",
            verifyTls = true,
            uploadEnabled = true,
        )
    }

    /** 记录入队调用次数与最近一次参数的可观察 fake。 */
    private class RecordingEnqueuer(
        private val nextId: Long = 42L,
        private val throwable: Throwable? = null,
    ) : BlacklistReporter.BlacklistEventEnqueuer {
        var invocationCount: Int = 0
            private set
        var lastAccountId: Long? = null
            private set
        var lastRequest: BlacklistEventRequest? = null
            private set

        override suspend fun enqueueBlacklistEvent(
            accountId: Long,
            request: BlacklistEventRequest,
        ): Long {
            invocationCount += 1
            lastAccountId = accountId
            lastRequest = request
            throwable?.let { throw it }
            return nextId
        }
    }

    private fun reporter(
        config: ServerSyncConfig = serverSyncConfig,
        enqueuer: BlacklistReporter.BlacklistEventEnqueuer = RecordingEnqueuer(),
        nowIsoUtc: () -> String = { "2026-04-26T08:25:00Z" },
        logger: BlacklistReporter.Logger = BlacklistReporter.Logger { _, _, _, _ -> },
    ): BlacklistReporter = BlacklistReporter(
        serverSyncConfig = config,
        enqueue = enqueuer,
        nowIsoUtc = nowIsoUtc,
        logger = logger,
    )

    @Test
    fun `report skips when ServerSyncConfig is not configured`() = runBlocking {
        // serverSyncConfig 默认未配置 base_url / bearer_token
        val enqueuer = RecordingEnqueuer()

        val outcome = reporter(enqueuer = enqueuer).report(
            accountId = 17L,
            evidence = "人机验证失败 5 次",
        )

        assertThat(outcome).isEqualTo(BlacklistReporter.Outcome.SkippedLocalOnly)
        assertThat(enqueuer.invocationCount).isEqualTo(0)
        assertThat(pendingDao.findAll()).isEmpty()
    }

    @Test
    fun `report skips when configured but upload_enabled is false`() = runBlocking {
        serverSyncConfig.saveBaseUrl("https://example.com")
        serverSyncConfig.saveBearerToken("tok-abc")
        // upload_enabled defaults to false
        val enqueuer = RecordingEnqueuer()

        val outcome = reporter(enqueuer = enqueuer).report(
            accountId = 17L,
            evidence = "拉黑证据",
        )

        assertThat(outcome).isEqualTo(BlacklistReporter.Outcome.SkippedLocalOnly)
        assertThat(enqueuer.invocationCount).isEqualTo(0)
    }

    @Test
    fun `report skips when upload_enabled is true but Server_Sync_Config is missing`() = runBlocking {
        // 只开启 upload_enabled，未填 base_url / bearer_token
        serverSyncConfig.saveUploadEnabled(true)
        val enqueuer = RecordingEnqueuer()

        val outcome = reporter(enqueuer = enqueuer).report(
            accountId = 17L,
            evidence = "拉黑证据",
        )

        assertThat(outcome).isEqualTo(BlacklistReporter.Outcome.SkippedLocalOnly)
        assertThat(enqueuer.invocationCount).isEqualTo(0)
    }

    @Test
    fun `report invokes enqueue with correct fields when both switches enabled`() = runBlocking {
        configureUploadEnabled()
        val enqueuer = RecordingEnqueuer(nextId = 123L)

        val outcome = reporter(
            enqueuer = enqueuer,
            nowIsoUtc = { "2026-04-26T08:25:00Z" },
        ).report(
            accountId = 17L,
            evidence = "人机验证失败 5 次",
        )

        assertThat(outcome).isEqualTo(BlacklistReporter.Outcome.Enqueued(pendingId = 123L))
        assertThat(enqueuer.invocationCount).isEqualTo(1)
        assertThat(enqueuer.lastAccountId).isEqualTo(17L)
        val request = enqueuer.lastRequest
        assertThat(request).isNotNull()
        assertThat(request!!.evidence).isEqualTo("人机验证失败 5 次")
        assertThat(request.clientKind).isEqualTo(ClientKindLiteral.ANDROID)
        assertThat(request.clientObservedAt).isEqualTo("2026-04-26T08:25:00Z")
    }

    @Test
    fun `report uses provided clientObservedAt when supplied`() = runBlocking {
        configureUploadEnabled()
        val enqueuer = RecordingEnqueuer()

        reporter(enqueuer = enqueuer).report(
            accountId = 42L,
            evidence = "evidence",
            clientObservedAt = "2026-05-01T00:00:00Z",
        )

        assertThat(enqueuer.lastRequest?.clientObservedAt).isEqualTo("2026-05-01T00:00:00Z")
    }

    @Test
    fun `report returns EnqueueFailed when enqueue throws and does not propagate`() = runBlocking {
        configureUploadEnabled()
        val enqueuer = RecordingEnqueuer(
            throwable = IllegalStateException("simulated db failure"),
        )

        val outcome = reporter(enqueuer = enqueuer).report(
            accountId = 17L,
            evidence = "evidence",
        )

        assertThat(outcome).isInstanceOf(BlacklistReporter.Outcome.EnqueueFailed::class.java)
        assertThat((outcome as BlacklistReporter.Outcome.EnqueueFailed).reason)
            .contains("simulated db failure")
    }

    @Test
    fun `fromUploader factory writes to PendingTaskUpload queue end to end`() = runBlocking {
        configureUploadEnabled()
        val reporter = BlacklistReporter.fromUploader(
            serverSyncConfig = serverSyncConfig,
            uploader = uploader,
            nowIsoUtc = { "2026-04-26T08:25:00Z" },
            logger = BlacklistReporter.Logger { _, _, _, _ -> },
        )

        val outcome = reporter.report(
            accountId = 17L,
            evidence = "人机验证失败 5 次",
        )

        assertThat(outcome).isInstanceOf(BlacklistReporter.Outcome.Enqueued::class.java)
        val pending = pendingDao.findAll().single()
        assertThat(pending.kind).isEqualTo(PendingTaskUploadEntity.KIND_BLACKLIST)
        assertThat(pending.accountId).isEqualTo(17L)
        assertThat(pending.taskId).isNull()
        assertThat(pending.revision).isNull()
        val payload = pending.payloadJson.orEmpty()
        assertThat(payload).contains("\"evidence\":\"人机验证失败 5 次\"")
        assertThat(payload).contains("\"client_kind\":\"android\"")
        assertThat(payload).contains("\"client_observed_at\":\"2026-04-26T08:25:00Z\"")
    }

    @Test
    fun `fromUploader factory does not write to queue when local-only`() = runBlocking {
        // 不调用 configureUploadEnabled() —— 配置缺失
        val reporter = BlacklistReporter.fromUploader(
            serverSyncConfig = serverSyncConfig,
            uploader = uploader,
            logger = BlacklistReporter.Logger { _, _, _, _ -> },
        )

        val outcome = reporter.report(
            accountId = 17L,
            evidence = "evidence",
        )

        assertThat(outcome).isEqualTo(BlacklistReporter.Outcome.SkippedLocalOnly)
        assertThat(pendingDao.findAll()).isEmpty()
    }
}
