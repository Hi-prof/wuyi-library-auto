package com.wuyi.libraryauto.core.storage.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE reservation_tasks ADD COLUMN studentId TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE reservation_tasks ADD COLUMN roomName TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
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
    }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (!db.hasColumn(tableName = "reservation_tasks", columnName = "limitSignBackSeconds")) {
                db.execSQL(
                    "ALTER TABLE reservation_tasks ADD COLUMN limitSignBackSeconds INTEGER NOT NULL DEFAULT 1800",
                )
            }
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS beacon_scan_audit (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    correlationId TEXT NOT NULL,
                    bookingId TEXT,
                    expectedMinorsCsv TEXT NOT NULL,
                    seenMinorsCsv TEXT NOT NULL,
                    matchedMinor INTEGER,
                    scanDurationMillis INTEGER NOT NULL,
                    terminationReason TEXT NOT NULL,
                    createdAtEpochSeconds INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_beacon_scan_audit_created_at ON beacon_scan_audit(createdAtEpochSeconds)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS signin_audit (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    correlationId TEXT NOT NULL,
                    bookingId TEXT NOT NULL,
                    studentId TEXT NOT NULL,
                    matchedMinor INTEGER,
                    httpStatusCode INTEGER,
                    rawMessage TEXT NOT NULL,
                    signInError TEXT,
                    triggerSource TEXT NOT NULL,
                    createdAtEpochSeconds INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_signin_audit_created_at ON signin_audit(createdAtEpochSeconds)",
            )
        }
    }

val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS seat_display_snapshots (
                    studentId TEXT NOT NULL PRIMARY KEY,
                    roomName TEXT NOT NULL,
                    seatNumber TEXT NOT NULL,
                    beginLabel TEXT NOT NULL,
                    liveState TEXT NOT NULL,
                    statusLabel TEXT NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_seat_display_snapshots_room_seat " +
                    "ON seat_display_snapshots(roomName, seatNumber)",
            )
        }
    }

val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // BUG 2 修复：新增 lastGuardAttemptEpochSeconds 列，用于 60s 重复签到防抖。
            if (!db.hasColumn(tableName = "reservation_tasks", columnName = "lastGuardAttemptEpochSeconds")) {
                db.execSQL(
                    "ALTER TABLE reservation_tasks ADD COLUMN lastGuardAttemptEpochSeconds INTEGER DEFAULT NULL",
                )
            }
        }
    }

val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // BUG-RETRY 修复：新增 consecutiveRetryCount 列，用于 GuardWorker 指数退避。
            if (!db.hasColumn(tableName = "reservation_tasks", columnName = "consecutiveRetryCount")) {
                db.execSQL(
                    "ALTER TABLE reservation_tasks ADD COLUMN consecutiveRetryCount INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }

val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // account-pool-tri-sync 任务 12.2：新增 active_accounts 表，作为 Active_Account_List_API
            // 接口 A 返回的本地缓存。仅持久化非敏感字段（accountId / studentId / displayName /
            // poolStatus / updatedAt / syncedAtEpochSeconds），密码与自动任务详情不入库。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS active_accounts (
                    accountId INTEGER NOT NULL PRIMARY KEY,
                    studentId TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    poolStatus TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    syncedAtEpochSeconds INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // account-pool-tri-sync 任务 12.3：新增 pending_task_uploads 与 task_upload_conflicts。
            // 前者承载用户编辑自动任务（PUT/DELETE）与拉黑事件上报（POST blacklist）的本地待发送队列；
            // 后者承载 409 revision_conflict 的「待人工解决」记录，由后续任务的冲突解决 UI 消费。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_task_uploads (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    accountId INTEGER NOT NULL,
                    taskId INTEGER,
                    payloadJson TEXT,
                    revision INTEGER,
                    createdAtEpochSeconds INTEGER NOT NULL,
                    retryCount INTEGER NOT NULL DEFAULT 0,
                    lastErrorReason TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_pending_task_uploads_created_at " +
                    "ON pending_task_uploads(createdAtEpochSeconds)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_upload_conflicts (
                    conflictHash TEXT NOT NULL PRIMARY KEY,
                    accountId INTEGER NOT NULL,
                    taskId INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    localPayloadJson TEXT,
                    localRevision INTEGER NOT NULL,
                    serverPayloadJson TEXT,
                    serverRevision INTEGER NOT NULL,
                    detectedAtEpochSeconds INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

private fun SupportSQLiteDatabase.hasColumn(
    tableName: String,
    columnName: String,
): Boolean =
    query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) {
                return true
            }
        }
        false
    }
