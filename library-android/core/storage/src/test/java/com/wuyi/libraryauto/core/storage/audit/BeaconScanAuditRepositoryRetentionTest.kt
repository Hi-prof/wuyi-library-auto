package com.wuyi.libraryauto.core.storage.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BeaconScanAuditRepositoryRetentionTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: BeaconScanAuditRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        repository =
            BeaconScanAuditRepository(
                dao = database.beaconScanAuditDao(),
                nowEpochSeconds = { NOW_EPOCH_SECONDS },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `write purges rows older than thirty days and keeps at most five hundred rows`() =
        runTest {
            repository.write(record(correlationId = "stale", createdAtEpochSeconds = THIRTY_ONE_DAYS_AGO))
            repeat(600) { index ->
                repository.write(
                    record(
                        correlationId = "current-$index",
                        createdAtEpochSeconds = NOW_EPOCH_SECONDS - 1_000L + index,
                    ),
                )
            }

            val records = repository.recent(limit = 1_000)

            assertThat(records).hasSize(500)
            assertThat(records.minOf { it.createdAtEpochSeconds }).isAtLeast(THIRTY_DAYS_AGO)
            assertThat(records.map { it.correlationId }).doesNotContain("stale")
            assertThat(records.first().correlationId).isEqualTo("current-599")
        }

    @Test
    fun `write redacts sensitive text before persisting`() = runTest {
        repository.write(
            record(
                correlationId = "corr-1",
                terminationReason = "Timeout cookie=session123 token=secret456",
                createdAtEpochSeconds = NOW_EPOCH_SECONDS,
            ),
        )

        val persisted = repository.recent(limit = 1).single()

        assertThat(persisted.terminationReason).contains("cookie=[REDACTED]")
        assertThat(persisted.terminationReason).contains("token=[REDACTED]")
        assertThat(persisted.terminationReason).doesNotContain("session123")
        assertThat(persisted.terminationReason).doesNotContain("secret456")
    }

    @Test
    fun `write bounds minors and scan duration before persisting`() = runTest {
        repository.write(
            record(
                correlationId = "corr-1",
                expectedMinors = (-1..40).toList() + listOf(65_536),
                seenMinors = (0..70).toList() + listOf(70),
                matchedMinor = 65_536,
                scanDurationMillis = 700_000L,
                createdAtEpochSeconds = NOW_EPOCH_SECONDS,
            ),
        )

        val persisted = repository.recent(limit = 1).single()

        assertThat(persisted.expectedMinorsCsv.split(",")).hasSize(32)
        assertThat(persisted.expectedMinorsCsv).startsWith("0,1,2")
        assertThat(persisted.seenMinorsCsv.split(",")).hasSize(64)
        assertThat(persisted.matchedMinor).isNull()
        assertThat(persisted.scanDurationMillis).isEqualTo(600_000L)
    }

    private fun record(
        correlationId: String,
        createdAtEpochSeconds: Long,
        expectedMinors: List<Int> = listOf(12, 16),
        seenMinors: List<Int> = listOf(12, 20),
        matchedMinor: Int? = 12,
        scanDurationMillis: Long = 1_250L,
        terminationReason: String = "Matched",
    ): BeaconScanAuditWrite =
        BeaconScanAuditWrite(
            correlationId = correlationId,
            bookingId = "booking-1",
            expectedMinors = expectedMinors,
            seenMinors = seenMinors,
            matchedMinor = matchedMinor,
            scanDurationMillis = scanDurationMillis,
            terminationReason = terminationReason,
            createdAtEpochSeconds = createdAtEpochSeconds,
        )

    private companion object {
        private const val NOW_EPOCH_SECONDS = 2_000_000L
        private const val THIRTY_DAYS_AGO = NOW_EPOCH_SECONDS - 30L * 86_400L
        private const val THIRTY_ONE_DAYS_AGO = NOW_EPOCH_SECONDS - 31L * 86_400L
    }
}
