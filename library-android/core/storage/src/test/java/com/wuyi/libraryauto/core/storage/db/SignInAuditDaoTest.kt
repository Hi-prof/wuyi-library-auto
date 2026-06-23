package com.wuyi.libraryauto.core.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignInAuditDaoTest {

    private lateinit var database: SignInAuditTestDatabase
    private lateinit var signInAuditDao: SignInAuditDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                SignInAuditTestDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        signInAuditDao = database.signInAuditDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recent returns newest audits first and applies limit`() = runTest {
        signInAuditDao.insert(audit(correlationId = "corr-old", createdAtEpochSeconds = 100L))
        signInAuditDao.insert(audit(correlationId = "corr-new", createdAtEpochSeconds = 200L))

        assertThat(signInAuditDao.recent(limit = 1).map { it.correlationId })
            .containsExactly("corr-new")
    }

    @Test
    fun `recent uses id as descending tie breaker`() = runTest {
        signInAuditDao.insert(audit(correlationId = "corr-first", createdAtEpochSeconds = 100L))
        signInAuditDao.insert(audit(correlationId = "corr-second", createdAtEpochSeconds = 100L))

        assertThat(signInAuditDao.recent(limit = 50).map { it.correlationId })
            .containsExactly("corr-second", "corr-first")
            .inOrder()
    }

    @Test
    fun `countByErrorWithin groups audits inside inclusive time range`() = runTest {
        signInAuditDao.insert(
            audit(
                correlationId = "corr-before",
                signInError = "NetworkError",
                createdAtEpochSeconds = 99L,
            ),
        )
        signInAuditDao.insert(
            audit(
                correlationId = "corr-first",
                signInError = "BeaconNotMatched",
                createdAtEpochSeconds = 100L,
            ),
        )
        signInAuditDao.insert(
            audit(
                correlationId = "corr-second",
                signInError = "BeaconNotMatched",
                createdAtEpochSeconds = 150L,
            ),
        )
        signInAuditDao.insert(
            audit(
                correlationId = "corr-success",
                signInError = null,
                createdAtEpochSeconds = 200L,
            ),
        )

        val counts =
            signInAuditDao
                .countByErrorWithin(rangeStart = 100L, rangeEnd = 200L)
                .associate { it.signInError to it.count }

        assertThat(counts).containsEntry("BeaconNotMatched", 2L)
        assertThat(counts).containsEntry(null, 1L)
        assertThat(counts).doesNotContainKey("NetworkError")
    }

    @Test
    fun `deleteOlderThan removes audits before cutoff`() = runTest {
        signInAuditDao.insert(audit(correlationId = "corr-stale", createdAtEpochSeconds = 99L))
        signInAuditDao.insert(audit(correlationId = "corr-current", createdAtEpochSeconds = 100L))

        assertThat(signInAuditDao.deleteOlderThan(cutoffEpochSeconds = 100L)).isEqualTo(1)

        assertThat(signInAuditDao.recent(limit = 50).map { it.correlationId })
            .containsExactly("corr-current")
    }

    @Test
    fun `deleteRowsOutsideRecentLimit keeps newest rows`() = runTest {
        signInAuditDao.insert(audit(correlationId = "corr-old", createdAtEpochSeconds = 100L))
        signInAuditDao.insert(audit(correlationId = "corr-mid", createdAtEpochSeconds = 200L))
        signInAuditDao.insert(audit(correlationId = "corr-new", createdAtEpochSeconds = 300L))

        assertThat(signInAuditDao.deleteRowsOutsideRecentLimit(maxRows = 2)).isEqualTo(1)

        assertThat(signInAuditDao.recent(limit = 50).map { it.correlationId })
            .containsExactly("corr-new", "corr-mid")
            .inOrder()
    }

    @Test
    fun `clearAll removes all audits`() = runTest {
        signInAuditDao.insert(audit(correlationId = "corr-1", createdAtEpochSeconds = 100L))

        assertThat(signInAuditDao.clearAll()).isEqualTo(1)

        assertThat(signInAuditDao.recent(limit = 50)).isEmpty()
    }

    private fun audit(
        correlationId: String,
        createdAtEpochSeconds: Long,
        signInError: String? = "BeaconNotMatched",
    ): SignInAuditEntity =
        SignInAuditEntity(
            correlationId = correlationId,
            bookingId = "booking-1",
            studentId = "20230001",
            matchedMinor = 12,
            httpStatusCode = 200,
            rawMessage = "raw message",
            signInError = signInError,
            triggerSource = "GuardWorker",
            createdAtEpochSeconds = createdAtEpochSeconds,
        )

    @Database(
        entities = [SignInAuditEntity::class],
        version = 1,
        exportSchema = false,
    )
    abstract class SignInAuditTestDatabase : RoomDatabase() {
        abstract fun signInAuditDao(): SignInAuditDao
    }
}
