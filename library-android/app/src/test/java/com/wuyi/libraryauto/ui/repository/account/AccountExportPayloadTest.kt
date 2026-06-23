package com.wuyi.libraryauto.ui.repository.account

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

class AccountExportPayloadTest {
    @Test
    fun `build exports whitelist fields only`() {
        val request =
            AccountExportPayload(fixedClock()).build(
                listOf(
                    SavedAccountEntry(
                        studentId = "20230001",
                        password = "secret",
                        preferredRoomName = "自习室圆形二楼",
                        preferredSeatNumber = "166",
                        preferredSeatLabel = "自习室圆形二楼 / 166",
                    ),
                ),
            )

        val keys = Regex("\"([^\"]+)\"\\s*:").findAll(request.jsonText).map { it.groupValues[1] }.toSet()
        assertThat(keys)
            .containsExactly("studentId", "preferredRoomName", "preferredSeatNumber", "preferredSeatLabel")
        assertThat(request.jsonText).doesNotContain("secret")
    }

    @Test
    fun `build creates reproducible shanghai timestamp filename and json mime`() {
        val request =
            AccountExportPayload(fixedClock()).build(
                listOf(SavedAccountEntry(studentId = "20230001", password = "secret")),
            )

        assertThat(request.fileName).isEqualTo("wuyi_accounts_20260516-080102.json")
        assertThat(request.mimeType).isEqualTo("application/json")
    }

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-05-16T00:01:02Z"), ZoneOffset.UTC)
}
