package com.wuyi.libraryauto.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.db.ActiveAccountDao
import com.wuyi.libraryauto.core.storage.db.ActiveAccountEntity
import com.wuyi.libraryauto.core.storage.db.AppDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 任务 12.2 单元测试：AccountPoolSyncRepository。
 *
 * 用 Robolectric 跑 Room in-memory + MockWebServer，覆盖以下行为：
 * - `refreshActiveList` 成功路径整批替换缓存，并把 `syncedAtEpochSeconds` 注入到实体。
 * - `refreshActiveList` 失败路径按状态码归类到 [AccountPoolSyncResult.Error] 子类。
 * - `getActiveAccountDetail` 把详情包装成 [ActiveAccountSyncDetail]，且密码不会写入 DAO。
 * - 缓存的查询/观察接口可读到 Worker 写入的数据。
 *
 * 使用 `@Config(application = Application::class)` 让 Robolectric 跑朴素 Application，绕过
 * `@HiltAndroidApp` 默认初始化对生产依赖（CaptivePortalRecoveryProvider 等）的需求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class AccountPoolSyncRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var database: AppDatabase
    private lateinit var dao: ActiveAccountDao

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
        dao = database.activeAccountDao()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private fun repository(now: Long = 1_700_000_000L): AccountPoolSyncRepository =
        AccountPoolSyncRepository(
            api =
                AccountPoolApiFactory.create(
                    baseUrl = server.url("/").toString(),
                    tokenProvider = { "tok" },
                ),
            activeAccountDao = dao,
            nowEpochSeconds = { now },
        )

    @Test
    fun `refreshActiveList replaces local cache with server payload`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "server_time": "2026-04-26T08:30:00Z",
                      "accounts": [
                        {
                          "account_id": 17,
                          "student_id": "20231121130",
                          "display_name": "张三",
                          "pool_status": "active",
                          "updated_at": "2026-04-26T08:25:11Z"
                        },
                        {
                          "account_id": 18,
                          "student_id": "20231121200",
                          "display_name": "李四",
                          "pool_status": "active",
                          "updated_at": "2026-04-26T08:25:12Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        // 预先放一条「过期」缓存，验证 replaceAll 会清除
        dao.upsert(
            ActiveAccountEntity(
                accountId = 999,
                studentId = "stale",
                displayName = "stale",
                poolStatus = "active",
                updatedAt = "old",
                syncedAtEpochSeconds = 1,
            ),
        )

        val result = repository(now = 1_700_000_500L).refreshActiveList()

        assertThat(result).isInstanceOf(AccountPoolSyncResult.Success::class.java)
        result as AccountPoolSyncResult.Success
        assertThat(result.serverTime).isEqualTo("2026-04-26T08:30:00Z")
        assertThat(result.value).hasSize(2)
        val cached = dao.findAll()
        assertThat(cached.map { it.accountId }).containsExactly(17L, 18L).inOrder()
        assertThat(cached.first().syncedAtEpochSeconds).isEqualTo(1_700_000_500L)
        assertThat(cached.first().displayName).isEqualTo("张三")
        // 旧记录应被整批替换清掉
        assertThat(cached.any { it.accountId == 999L }).isFalse()
    }

    @Test
    fun `refreshActiveList classifies 401 as Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val result = repository().refreshActiveList()

        assertThat(result).isInstanceOf(AccountPoolSyncResult.Error.Unauthorized::class.java)
        assertThat(dao.findAll()).isEmpty()
    }

    @Test
    fun `refreshActiveList classifies 429 as RateLimited`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))

        val result = repository().refreshActiveList()

        assertThat(result).isInstanceOf(AccountPoolSyncResult.Error.RateLimited::class.java)
    }

    @Test
    fun `refreshActiveList classifies 500 as Server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        val result = repository().refreshActiveList()

        assertThat(result).isInstanceOf(AccountPoolSyncResult.Error.Server::class.java)
        result as AccountPoolSyncResult.Error.Server
        assertThat(result.statusCode).isEqualTo(500)
    }

    @Test
    fun `refreshActiveList classifies 426 as HttpsRequired`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(426).setBody("{}"))

        val result = repository().refreshActiveList()

        assertThat(result).isInstanceOf(AccountPoolSyncResult.Error.HttpsRequired::class.java)
    }

    @Test
    fun `getActiveAccountDetail wraps response and does not persist password`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "server_time": "2026-04-26T08:30:00Z",
                      "account": {
                        "account_id": 17,
                        "student_id": "20231121130",
                        "display_name": "张三",
                        "password": "Pa55w0rd!",
                        "revision": 42
                      },
                      "automation_tasks": []
                    }
                    """.trimIndent(),
                ),
        )

        val result = repository().getActiveAccountDetail(17L)

        assertThat(result).isInstanceOf(AccountPoolSyncResult.Success::class.java)
        result as AccountPoolSyncResult.Success
        assertThat(result.value.account.password).isEqualTo("Pa55w0rd!")
        // 关键约束：密码不入库
        assertThat(dao.findById(17L)).isNull()
        assertThat(dao.findAll()).isEmpty()
    }

    @Test
    fun `getActiveAccountDetail maps 404 to AccountNotInActivePool`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"account not found\"}"),
        )

        val result = repository().getActiveAccountDetail(999L)

        assertThat(result).isInstanceOf(AccountPoolSyncResult.Error.AccountNotInActivePool::class.java)
    }

    @Test
    fun `loadCachedActiveAccounts returns previously synced rows`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "server_time": "2026-04-26T08:30:00Z",
                      "accounts": [
                        {
                          "account_id": 7,
                          "student_id": "s7",
                          "display_name": "user-7",
                          "pool_status": "active",
                          "updated_at": "2026-04-26T08:25:11Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        val repo = repository()
        repo.refreshActiveList()

        val cached = repo.loadCachedActiveAccounts()

        assertThat(cached).hasSize(1)
        assertThat(cached.single().accountId).isEqualTo(7L)
        assertThat(repo.getCachedActiveAccount(7L)?.studentId).isEqualTo("s7")
        assertThat(repo.getCachedActiveAccount(99L)).isNull()
    }
}
