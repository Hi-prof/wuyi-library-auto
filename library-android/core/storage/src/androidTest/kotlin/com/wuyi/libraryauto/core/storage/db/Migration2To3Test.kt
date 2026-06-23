package com.wuyi.libraryauto.core.storage.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun migratesReservationTasksAndCreatesAuditTables() {
        helper.createDatabase(TEST_DATABASE, 2).apply {
            createVersionTwoSchema()
            execSQL(
                """
                INSERT INTO reservation_tasks (
                    id,
                    studentId,
                    roomName,
                    seatNumber,
                    state,
                    bookingId,
                    startTimeEpochSeconds,
                    limitSignAgoSeconds,
                    expectedMinorsCsv,
                    lastError
                ) VALUES (
                    'task-1',
                    '20230001',
                    '自习室圆形二楼',
                    '166',
                    'RESERVED_WAITING_SIGNIN',
                    'booking-1',
                    1712800000,
                    900,
                    '',
                    NULL
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                3,
                true,
                MIGRATION_2_3,
            )

        assertThat(
            migrated.queryLong(
                "SELECT limitSignBackSeconds FROM reservation_tasks WHERE id = 'task-1'",
            ),
        ).isEqualTo(1_800L)
        assertThat(migrated.queryLong("SELECT COUNT(*) FROM beacon_scan_audit")).isEqualTo(0L)
        assertThat(migrated.queryLong("SELECT COUNT(*) FROM signin_audit")).isEqualTo(0L)
        migrated.close()
    }

    private fun SupportSQLiteDatabase.createVersionTwoSchema() {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS reservation_tasks (
                id TEXT NOT NULL PRIMARY KEY,
                studentId TEXT NOT NULL DEFAULT '',
                roomName TEXT NOT NULL DEFAULT '',
                seatNumber TEXT NOT NULL,
                state TEXT NOT NULL,
                bookingId TEXT,
                startTimeEpochSeconds INTEGER NOT NULL,
                limitSignAgoSeconds INTEGER NOT NULL,
                expectedMinorsCsv TEXT NOT NULL,
                lastError TEXT
            )
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS execution_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                taskId TEXT NOT NULL,
                state TEXT NOT NULL,
                recordedAtEpochSeconds INTEGER NOT NULL,
                message TEXT,
                FOREIGN KEY(taskId) REFERENCES reservation_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS index_execution_logs_taskId ON execution_logs(taskId)")
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS automation_plans (
                planId TEXT NOT NULL PRIMARY KEY,
                studentId TEXT NOT NULL,
                roomName TEXT NOT NULL,
                seatNumber TEXT NOT NULL,
                mode TEXT NOT NULL,
                singleDate TEXT,
                singleStartTime TEXT,
                singleEndTime TEXT,
                enabled INTEGER NOT NULL,
                createdAtEpochSeconds INTEGER NOT NULL,
                updatedAtEpochSeconds INTEGER NOT NULL,
                nextRunAtEpochSeconds INTEGER,
                lastRunAtEpochSeconds INTEGER,
                lastResultMessage TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.queryLong(sql: String): Long =
        query(sql).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getLong(0)
        }

    private companion object {
        private const val TEST_DATABASE = "migration-2-3-test"
    }
}
