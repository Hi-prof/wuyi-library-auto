package com.wuyi.libraryauto.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AccountPoolApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(token: () -> String? = { "tok" }): AccountPoolApi =
        AccountPoolApiFactory.create(
            baseUrl = server.url("/").toString(),
            tokenProvider = token,
        )

    @Test
    fun `listActiveAccounts decodes server response and sends Bearer token`() = runBlocking {
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
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val response = api().listActiveAccounts()

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("GET")
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer tok")
        assertThat(response.serverTime).isEqualTo("2026-04-26T08:30:00Z")
        assertThat(response.accounts).hasSize(1)
        assertThat(response.accounts.first().accountId).isEqualTo(17L)
        assertThat(response.accounts.first().studentId).isEqualTo("20231121130")
        assertThat(response.accounts.first().displayName).isEqualTo("张三")
        assertThat(response.accounts.first().poolStatus).isEqualTo("active")
    }

    @Test
    fun `getActiveAccountDetail decodes nested account and automation_tasks`() = runBlocking {
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
                      "automation_tasks": [
                        {
                          "task_id": 901,
                          "room_name": "三层东区",
                          "seat_number": "A12",
                          "mode": "preferred",
                          "custom_windows": [
                            {"date": "2026-04-27", "start_hour": 8, "end_hour": 12}
                          ],
                          "enabled": true,
                          "revision": 7,
                          "updated_at": "2026-04-26T07:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val response = api().getActiveAccountDetail(17L)

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts/17/detail")
        assertThat(response.account.accountId).isEqualTo(17L)
        assertThat(response.account.password).isEqualTo("Pa55w0rd!")
        assertThat(response.account.revision).isEqualTo(42L)
        assertThat(response.automationTasks).hasSize(1)
        val task = response.automationTasks.first()
        assertThat(task.taskId).isEqualTo(901L)
        assertThat(task.roomName).isEqualTo("三层东区")
        assertThat(task.seatNumber).isEqualTo("A12")
        assertThat(task.mode).isEqualTo(AutomationTaskMode.PREFERRED)
        assertThat(task.customWindows).hasSize(1)
        assertThat(task.customWindows.first().date).isEqualTo("2026-04-27")
        assertThat(task.customWindows.first().startHour).isEqualTo(8)
        assertThat(task.customWindows.first().endHour).isEqualTo(12)
        assertThat(task.enabled).isTrue()
    }

    @Test
    fun `upsertAutomationTask sends PUT with snake_case body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"server_time\":\"2026-04-26T08:30:00Z\",\"revision\":8}"),
        )
        val request =
            AutomationTaskUpsertRequest(
                roomName = "三层东区",
                seatNumber = "A12",
                mode = AutomationTaskMode.PREFERRED,
                customWindows =
                    listOf(
                        AutomationCustomWindowDto(
                            date = "2026-04-28",
                            startHour = 8,
                            endHour = 12,
                        ),
                    ),
                enabled = true,
                revision = 7,
            )

        val response = api().upsertAutomationTask(17L, 901L, request)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("PUT")
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts/17/automation-tasks/901")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer tok")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"room_name\":\"三层东区\"")
        assertThat(body).contains("\"seat_number\":\"A12\"")
        assertThat(body).contains("\"mode\":\"preferred\"")
        assertThat(body).contains("\"start_hour\":8")
        assertThat(body).contains("\"end_hour\":12")
        assertThat(body).contains("\"enabled\":true")
        assertThat(body).contains("\"revision\":7")
        assertThat(response.revision).isEqualTo(8L)
    }

    @Test
    fun `deleteAutomationTask appends revision query param`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"server_time\":\"2026-04-26T08:30:00Z\",\"revision\":9}"),
        )

        val response = api().deleteAutomationTask(17L, 901L, revision = 8L)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("DELETE")
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts/17/automation-tasks/901?revision=8")
        assertThat(response.revision).isEqualTo(9L)
    }

    @Test
    fun `reportBlacklistEvent serializes evidence and client_kind`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "server_time": "2026-04-26T08:30:00Z",
                      "account_id": 17,
                      "pool_status": "suspended"
                    }
                    """.trimIndent(),
                ),
        )

        val response =
            api().reportBlacklistEvent(
                accountId = 17L,
                request =
                    BlacklistEventRequest(
                        evidence = "人机验证失败 5 次",
                        clientKind = ClientKindLiteral.ANDROID,
                        clientObservedAt = "2026-04-26T08:29:55Z",
                    ),
            )

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts/17/blacklist-events")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"evidence\":\"人机验证失败 5 次\"")
        assertThat(body).contains("\"client_kind\":\"android\"")
        assertThat(body).contains("\"client_observed_at\":\"2026-04-26T08:29:55Z\"")
        assertThat(response.poolStatus).isEqualTo("suspended")
    }

    @Test
    fun `listAutomationTasks returns expected mapping`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "server_time": "2026-04-26T08:30:00Z",
                      "automation_tasks": []
                    }
                    """.trimIndent(),
                ),
        )

        val response = api().listAutomationTasks(17L)

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/active-accounts/17/automation-tasks")
        assertThat(response.automationTasks).isEmpty()
    }
}
