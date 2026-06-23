package com.wuyi.libraryauto.core.runtime.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalDiagnosticLogRepositoryTest {
    private lateinit var repository: LocalDiagnosticLogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = LocalDiagnosticLogRepository(context)
        repository.clear()
    }

    @After
    fun tearDown() {
        repository.clear()
    }

    @Test
    fun `append stores redacted entries newest first`() {
        repository.append(
            level = "INFO",
            source = "Process",
            title = "进程启动",
            detailLines = listOf("pid=1"),
            recordedAtEpochMillis = 1_776_100_000_000L,
        )
        repository.append(
            level = "ERROR",
            source = "Crash",
            title = "未捕获异常",
            detailLines =
                listOf(
                    "Authorization: Bearer secret-token",
                    "password=secret-password",
                    "message=boom",
                ),
            recordedAtEpochMillis = 1_776_100_010_000L,
        )

        val entries = repository.loadEntries()

        assertThat(entries).hasSize(2)
        assertThat(entries.first().source).isEqualTo("Crash")
        assertThat(entries.first().detailLines.joinToString("\n")).contains("Authorization=[REDACTED]")
        assertThat(entries.first().detailLines.joinToString("\n")).contains("password=[REDACTED]")
        assertThat(entries.first().detailLines.joinToString("\n")).contains("message=boom")
        assertThat(entries.first().detailLines.joinToString("\n")).doesNotContain("secret-token")
        assertThat(entries.first().detailLines.joinToString("\n")).doesNotContain("secret-password")
    }

    @Test
    fun `clear removes stored entries`() {
        repository.append(
            level = "WARN",
            source = "GuardSchedulerService",
            title = "前台服务超时",
            recordedAtEpochMillis = 1_776_100_000_000L,
        )

        repository.clear()

        assertThat(repository.loadEntries()).isEmpty()
    }
}
