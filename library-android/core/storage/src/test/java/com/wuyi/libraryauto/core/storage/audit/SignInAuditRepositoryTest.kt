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
class SignInAuditRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: SignInAuditRepository

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
            SignInAuditRepository(
                dao = database.signInAuditDao(),
                nowEpochSeconds = { NOW_EPOCH_SECONDS },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `write truncates raw message to one thousand twenty four chars after redaction`() =
        runTest {
            repository.write(
                record(
                    correlationId = "corr-1",
                    rawMessage = "failed token=secret456 " + "x".repeat(1_200),
                    createdAtEpochSeconds = NOW_EPOCH_SECONDS,
                ),
            )

            val persisted = repository.recent(limit = 1).single()

            assertThat(persisted.rawMessage).hasLength(1_024)
            assertThat(persisted.rawMessage).contains("token=[REDACTED]")
            assertThat(persisted.rawMessage).doesNotContain("secret456")
        }

    @Test
    fun `countByErrorWithin returns twenty four hour grouped counts`() = runTest {
        repository.write(
            record(
                correlationId = "corr-before",
                signInError = "NetworkError",
                createdAtEpochSeconds = NOW_EPOCH_SECONDS - 86_401L,
            ),
        )
        repository.write(
            record(
                correlationId = "corr-first",
                signInError = "BeaconNotMatched",
                createdAtEpochSeconds = NOW_EPOCH_SECONDS - 86_400L,
            ),
        )
        repository.write(
            record(
                correlationId = "corr-second",
                signInError = "BeaconNotMatched",
                createdAtEpochSeconds = NOW_EPOCH_SECONDS - 10L,
            ),
        )
        repository.write(
            record(
                correlationId = "corr-success",
                signInError = null,
                createdAtEpochSeconds = NOW_EPOCH_SECONDS,
            ),
        )

        val counts =
            repository
                .countByErrorWithin(
                    rangeStart = NOW_EPOCH_SECONDS - 86_400L,
                    rangeEnd = NOW_EPOCH_SECONDS,
                )
                .associate { it.signInError to it.count }

        assertThat(counts).containsEntry("BeaconNotMatched", 2L)
        assertThat(counts).containsEntry(null, 1L)
        assertThat(counts).doesNotContainKey("NetworkError")
    }

    @Test
    fun `write preserves trigger source`() = runTest {
        repository.write(
            record(
                correlationId = "corr-1",
                triggerSource = "ManualBatch",
                createdAtEpochSeconds = NOW_EPOCH_SECONDS,
            ),
        )

        assertThat(repository.recent(limit = 1).single().triggerSource).isEqualTo("ManualBatch")
    }

    private fun record(
        correlationId: String,
        createdAtEpochSeconds: Long,
        rawMessage: String = "raw message",
        signInError: String? = "BeaconNotMatched",
        triggerSource: String = "GuardWorker",
    ): SignInAuditWrite =
        SignInAuditWrite(
            correlationId = correlationId,
            bookingId = "booking-1",
            studentId = "20230001",
            matchedMinor = 12,
            httpStatusCode = 200,
            rawMessage = rawMessage,
            signInError = signInError,
            triggerSource = triggerSource,
            createdAtEpochSeconds = createdAtEpochSeconds,
        )

    private companion object {
        private const val NOW_EPOCH_SECONDS = 2_000_000L
    }
}
