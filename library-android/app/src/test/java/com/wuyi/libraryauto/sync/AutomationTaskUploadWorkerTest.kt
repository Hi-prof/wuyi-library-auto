package com.wuyi.libraryauto.sync

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.db.ActiveAccountDao
import com.wuyi.libraryauto.core.storage.db.AppDatabase
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadDao
import com.wuyi.libraryauto.core.storage.db.PendingTaskUploadEntity
import com.wuyi.libraryauto.core.storage.db.TaskUploadConflictDao
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 任务 12.3 单元测试：AutomationTaskUploadWorker。
 *
 * 用 Robolectric + Room in-memory + MockWebServer 覆盖每条 status 路径：
 * - 200 → success：删除队列条目，与服务端响应一致；
 * - 409 revision_conflict → 写入 task_upload_conflicts、清队列、整体 success；
 * - 404 account_not_found → 触发 refreshActiveList、保留队列条目、整体 failure；
 * - 422 validation_error → 丢弃队列条目、整体 failure；
 * - 401 unauthorized → 丢弃队列条目、整体 failure（避免拿无效 token 重试）；
 * - 429 / 5xx / IO → 保留队列条目、retryCount 自增、整体 retry；
 * - blacklist 事件路径：200 删除、404 触发刷新；
 * - buildOneTimeRequest：网络约束 + 线性退避；
 * - AutomationTaskUploader.enqueueUpsert/Delete/Blacklist：写队列时带正确字段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class AutomationTaskUploadWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var database: AppDatabase
    private lateinit var pendingDao: PendingTaskUploadDao
    private lateinit var conflictDao: TaskUploadConflictDao
    private lateinit var activeAccountDao: ActiveAccountDao
    private lateinit var repository: AccountPoolSyncRepository
    private lateinit var uploadEnabledSyncConfig: ServerSyncConfig

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        pendingDao = database.pendingTaskUploadDao()
        conflictDao = database.taskUploadConflictDao()
        activeAccountDao = database.activeAccountDao()
        // 任务 12.10：双开关守卫已落到 [AutomationTaskUploader]，测试默认注入「已配置 + 上行开关开启」
        // 的 ServerSyncConfig；Local_Only_Mode 路径在 enqueueUpsert/Delete skips local-only 用例里单测。
        val prefs: SharedPreferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_automation_task_upload_worker", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        uploadEnabledSyncConfig = ServerSyncConfig(prefs).apply {
            saveAll(
                baseUrl = "https://example.com",
                bearerToken = "tok",
                verifyTls = true,
                uploadEnabled = true,
            )
        }
        repository =
            AccountPoolSyncRepository(
                api =
                    AccountPoolApiFactory.create(
                        baseUrl = server.url("/").toString(),
                        tokenProvider = { "tok" },
                    ),
                activeAccountDao = activeAccountDao,
                nowEpochSeconds = { 1_700_000_000L },
            )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
        AutomationTaskUploadWorker.Provider.reset()
    }

    private fun dependencies(now: Long = 1_700_000_000L): AutomationTaskUploadWorker.Dependencies =
        AutomationTaskUploadWorker.Dependencies(
            api =
                AccountPoolApiFactory.create(
                    baseUrl = server.url("/").toString(),
                    tokenProvider = { "tok" },
                ),
            pendingDao = pendingDao,
            conflictDao = conflictDao,
            activePoolRepository = repository,
            nowEpochSeconds = { now },
        )

    private fun upsertRequest(revision: Long = 3): AutomationTaskUpsertRequest =
        AutomationTaskUpsertRequest(
            roomName = "三楼自习室",
            seatNumber = "A-007",
            mode = AutomationTaskMode.PREFERRED,
            customWindows = emptyList(),
            enabled = true,
            revision = revision,
        )

    private fun newUploader(
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
        serverSyncConfig: ServerSyncConfig = uploadEnabledSyncConfig,
    ): AutomationTaskUploader =
        AutomationTaskUploader(
            context = ApplicationProvider.getApplicationContext(),
            pendingDao = pendingDao,
            serverSyncConfig = serverSyncConfig,
            nowEpochSeconds = nowEpochSeconds,
        )

    @Test
    fun `runOnce uploads upsert and removes entity on 200`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"server_time":"2026-04-26T08:30:00Z","revision":4}
                    """.trimIndent(),
                ),
        )
        val uploader = newUploader(
            nowEpochSeconds = { 1_700_000_000L },
        )
        uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(revision = 3),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.success())
        assertThat(pendingDao.findAll()).isEmpty()
        val recorded: RecordedRequest = server.takeRequest()
        assertThat(recorded.method).isEqualTo("PUT")
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts/17/automation-tasks/901")
        assertThat(recorded.body.readUtf8()).contains("\"revision\":3")
    }

    @Test
    fun `runOnce uploads delete and removes entity on 200`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"server_time":"2026-04-26T08:30:00Z","revision":5}"""),
        )
        val uploader = newUploader()
        uploader.enqueueDelete(accountId = 17L, taskId = 901L, revision = 4L, enqueueWorker = false)

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.success())
        assertThat(pendingDao.findAll()).isEmpty()
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("DELETE")
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts/17/automation-tasks/901?revision=4")
    }

    @Test
    fun `runOnce on 409 stores conflict and clears queue but returns success`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "reason":"revision_conflict",
                      "server_revision":7,
                      "server_payload":{
                        "task_id":901,
                        "room_name":"三楼自习室",
                        "seat_number":"A-008",
                        "mode":"preferred",
                        "custom_windows":[],
                        "enabled":true,
                        "revision":7,
                        "updated_at":"2026-04-26T08:25:00Z"
                      }
                    }
                    """.trimIndent(),
                ),
        )
        val uploader = newUploader()
        uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(revision = 3),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies(now = 1_700_001_111L))

        assertThat(result).isEqualTo(Result.success())
        // 队列被清空（避免拿旧 revision 反复打）
        assertThat(pendingDao.findAll()).isEmpty()
        // 冲突被记录，等待 UI 解决
        val conflicts = conflictDao.findAll()
        assertThat(conflicts).hasSize(1)
        val conflict = conflicts.single()
        assertThat(conflict.accountId).isEqualTo(17L)
        assertThat(conflict.taskId).isEqualTo(901L)
        assertThat(conflict.kind).isEqualTo(PendingTaskUploadEntity.KIND_UPSERT)
        assertThat(conflict.localRevision).isEqualTo(3L)
        assertThat(conflict.serverRevision).isEqualTo(7L)
        assertThat(conflict.serverPayloadJson).contains("\"revision\":7")
        assertThat(conflict.detectedAtEpochSeconds).isEqualTo(1_700_001_111L)
    }

    @Test
    fun `runOnce on 404 triggers refresh and keeps queue with failure`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"account not found\"}"),
        )
        // refresh 调用：返回空清单（但能成功），证明 worker 触发了 refreshActiveList
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"server_time":"2026-04-26T08:30:00Z","accounts":[]}"""
                ),
        )
        val uploader = newUploader()
        uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.failure())
        // 队列保留（用户 / UI 决定如何处理）
        val pending = pendingDao.findAll()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().retryCount).isEqualTo(1)
        assertThat(pending.single().lastErrorReason).isEqualTo("account_not_found")
        // refreshActiveList 被调用（第 2 个请求路径）
        server.takeRequest() // PUT
        val refresh = server.takeRequest()
        assertThat(refresh.method).isEqualTo("GET")
        assertThat(refresh.path).isEqualTo("/api/v1/active-accounts")
    }

    @Test
    fun `runOnce on 422 discards entity and returns failure`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"reason\":\"validation_error\",\"errors\":[]}"),
        )
        val uploader = newUploader()
        uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.failure())
        assertThat(pendingDao.findAll()).isEmpty()
    }

    @Test
    fun `runOnce on 401 discards entity and returns failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val uploader = newUploader()
        uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.failure())
        assertThat(pendingDao.findAll()).isEmpty()
    }

    @Test
    fun `runOnce on 429 keeps entity and returns retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val uploader = newUploader()
        uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.retry())
        val pending = pendingDao.findAll()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().retryCount).isEqualTo(1)
        assertThat(pending.single().lastErrorReason).isEqualTo("rate_limited")
    }

    @Test
    fun `runOnce on 500 keeps entity and returns retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val uploader = newUploader()
        uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.retry())
        val pending = pendingDao.findAll().single()
        assertThat(pending.retryCount).isEqualTo(1)
        assertThat(pending.lastErrorReason).isEqualTo("server_500")
    }

    @Test
    fun `runOnce returns retry when network IO fails`() = runBlocking {
        server.shutdown() // 触发 IOException
        val uploader = newUploader()
        uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.retry())
        assertThat(pendingDao.findAll().single().lastErrorReason).isEqualTo("io_error")
    }

    @Test
    fun `runOnce processes blacklist event and removes entity on 200`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"server_time":"2026-04-26T08:30:00Z","account_id":17,"pool_status":"suspended"}
                    """.trimIndent(),
                ),
        )
        val uploader = newUploader()
        uploader.enqueueBlacklistEvent(
            accountId = 17L,
            request =
                BlacklistEventRequest(
                    evidence = "人机验证失败 5 次",
                    clientKind = ClientKindLiteral.ANDROID,
                    clientObservedAt = "2026-04-26T08:25:00Z",
                ),
            enqueueWorker = false,
        )

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.success())
        assertThat(pendingDao.findAll()).isEmpty()
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts/17/blacklist-events")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"evidence\":\"人机验证失败 5 次\"")
        assertThat(body).contains("\"client_kind\":\"android\"")
    }

    @Test
    fun `runOnce processes multiple pending entries in FIFO order`() = runBlocking {
        // 两条 PUT，依次 200
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"server_time":"t","revision":4}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"server_time":"t","revision":5}"""),
        )
        val uploader = newUploader(
            nowEpochSeconds = { 1_700_000_000L },
        )
        uploader.enqueueUpsert(17L, 901L, upsertRequest(revision = 3), enqueueWorker = false)
        uploader.enqueueUpsert(17L, 902L, upsertRequest(revision = 1), enqueueWorker = false)

        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.success())
        assertThat(pendingDao.findAll()).isEmpty()
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.path).isEqualTo("/api/v1/active-accounts/17/automation-tasks/901")
        assertThat(second.path).isEqualTo("/api/v1/active-accounts/17/automation-tasks/902")
    }

    @Test
    fun `runOnce returns success when queue is empty`() = runBlocking {
        val result = AutomationTaskUploadWorker.runOnce(dependencies())

        assertThat(result).isEqualTo(Result.success())
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `buildOneTimeRequest enforces network constraint and linear backoff`() {
        val request = AutomationTaskUploadWorker.buildOneTimeRequest()

        assertThat(request.workSpec.constraints.requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
        assertThat(request.workSpec.backoffPolicy).isEqualTo(BackoffPolicy.LINEAR)
    }

    @Test
    fun `Provider install and reset toggle dependency factory`() {
        AutomationTaskUploadWorker.Provider.reset()
        assertThat(AutomationTaskUploadWorker.Provider.factory).isNull()

        AutomationTaskUploadWorker.Provider.install { _ -> dependencies() }
        assertThat(AutomationTaskUploadWorker.Provider.factory).isNotNull()

        AutomationTaskUploadWorker.Provider.reset()
        assertThat(AutomationTaskUploadWorker.Provider.factory).isNull()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 任务 12.10：双开关守卫（Local_Only_Mode 静默跳过 + 双开关全开启时正常入队）
    // ─────────────────────────────────────────────────────────────────────

    private fun localOnlySyncConfig(): ServerSyncConfig {
        val prefs: SharedPreferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_uploader_local_only", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return ServerSyncConfig(prefs)
    }

    @Test
    fun `enqueueUpsert skips DB write and Worker when ServerSyncConfig is not configured`() = runBlocking {
        val uploader = newUploader(serverSyncConfig = localOnlySyncConfig())

        val pendingId = uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(revision = 3),
            enqueueWorker = true,
        )

        assertThat(pendingId).isEqualTo(AutomationTaskUploader.SKIPPED_PENDING_ID)
        assertThat(pendingDao.findAll()).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `enqueueUpsert skips when configured but upload_enabled is false`() = runBlocking {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_uploader_upload_disabled", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val syncConfig = ServerSyncConfig(prefs).apply {
            saveBaseUrl("https://example.com")
            saveBearerToken("tok")
            // upload_enabled 默认 false
        }
        val uploader = newUploader(serverSyncConfig = syncConfig)

        val pendingId = uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(revision = 3),
            enqueueWorker = true,
        )

        assertThat(pendingId).isEqualTo(AutomationTaskUploader.SKIPPED_PENDING_ID)
        assertThat(pendingDao.findAll()).isEmpty()
    }

    @Test
    fun `enqueueDelete skips DB write and Worker in Local_Only_Mode`() = runBlocking {
        val uploader = newUploader(serverSyncConfig = localOnlySyncConfig())

        val pendingId = uploader.enqueueDelete(
            accountId = 17L,
            taskId = 901L,
            revision = 4L,
            enqueueWorker = true,
        )

        assertThat(pendingId).isEqualTo(AutomationTaskUploader.SKIPPED_PENDING_ID)
        assertThat(pendingDao.findAll()).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `enqueueUpsert writes queue when both switches are enabled`() = runBlocking {
        val uploader = newUploader()

        val pendingId = uploader.enqueueUpsert(
            accountId = 17L,
            taskId = 901L,
            request = upsertRequest(revision = 3),
            enqueueWorker = false,
        )

        assertThat(pendingId).isNotEqualTo(AutomationTaskUploader.SKIPPED_PENDING_ID)
        assertThat(pendingId).isGreaterThan(0L)
        val pending = pendingDao.findAll().single()
        assertThat(pending.kind).isEqualTo(PendingTaskUploadEntity.KIND_UPSERT)
        assertThat(pending.accountId).isEqualTo(17L)
        assertThat(pending.taskId).isEqualTo(901L)
    }
}
