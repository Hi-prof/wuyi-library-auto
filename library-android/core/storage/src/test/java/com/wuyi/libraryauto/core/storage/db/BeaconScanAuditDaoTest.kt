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
class BeaconScanAuditDaoTest {

    private lateinit var database: BeaconScanAuditTestDatabase
    private lateinit var beaconScanAuditDao: BeaconScanAuditDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                BeaconScanAuditTestDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        beaconScanAuditDao = database.beaconScanAuditDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recent returns newest audits first`() = runTest {
        beaconScanAuditDao.insert(audit(correlationId = "corr-old", createdAtEpochSeconds = 100L))
        beaconScanAuditDao.insert(audit(correlationId = "corr-new", createdAtEpochSeconds = 200L))

        assertThat(beaconScanAuditDao.recent(limit = 50).map { it.correlationId })
            .containsExactly("corr-new", "corr-old")
            .inOrder()
    }

    @Test
    fun `recent uses id as descending tie breaker`() = runTest {
        beaconScanAuditDao.insert(audit(correlationId = "corr-first", createdAtEpochSeconds = 100L))
        beaconScanAuditDao.insert(audit(correlationId = "corr-second", createdAtEpochSeconds = 100L))

        assertThat(beaconScanAuditDao.recent(limit = 50).map { it.correlationId })
            .containsExactly("corr-second", "corr-first")
            .inOrder()
    }

    @Test
    fun `deleteOlderThan removes audits before cutoff`() = runTest {
        beaconScanAuditDao.insert(audit(correlationId = "corr-stale", createdAtEpochSeconds = 99L))
        beaconScanAuditDao.insert(audit(correlationId = "corr-current", createdAtEpochSeconds = 100L))

        assertThat(beaconScanAuditDao.deleteOlderThan(cutoffEpochSeconds = 100L)).isEqualTo(1)

        assertThat(beaconScanAuditDao.recent(limit = 50).map { it.correlationId })
            .containsExactly("corr-current")
    }

    @Test
    fun `deleteRowsOutsideRecentLimit keeps newest rows`() = runTest {
        beaconScanAuditDao.insert(audit(correlationId = "corr-old", createdAtEpochSeconds = 100L))
        beaconScanAuditDao.insert(audit(correlationId = "corr-mid", createdAtEpochSeconds = 200L))
        beaconScanAuditDao.insert(audit(correlationId = "corr-new", createdAtEpochSeconds = 300L))

        assertThat(beaconScanAuditDao.deleteRowsOutsideRecentLimit(maxRows = 2)).isEqualTo(1)

        assertThat(beaconScanAuditDao.recent(limit = 50).map { it.correlationId })
            .containsExactly("corr-new", "corr-mid")
            .inOrder()
    }

    @Test
    fun `clearAll removes all audits`() = runTest {
        beaconScanAuditDao.insert(audit(correlationId = "corr-1", createdAtEpochSeconds = 100L))

        assertThat(beaconScanAuditDao.clearAll()).isEqualTo(1)

        assertThat(beaconScanAuditDao.recent(limit = 50)).isEmpty()
    }

    private fun audit(
        correlationId: String,
        createdAtEpochSeconds: Long,
    ): BeaconScanAuditEntity =
        BeaconScanAuditEntity(
            correlationId = correlationId,
            bookingId = "booking-1",
            expectedMinorsCsv = "12,16",
            seenMinorsCsv = "12,20",
            matchedMinor = 12,
            scanDurationMillis = 1_250L,
            terminationReason = "Matched",
            createdAtEpochSeconds = createdAtEpochSeconds,
        )

    @Database(
        entities = [BeaconScanAuditEntity::class],
        version = 1,
        exportSchema = false,
    )
    abstract class BeaconScanAuditTestDatabase : RoomDatabase() {
        abstract fun beaconScanAuditDao(): BeaconScanAuditDao
    }
}
