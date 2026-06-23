package com.wuyi.libraryauto.core.storage.db

import android.content.Context
import androidx.room.Room

object StorageDatabaseProvider {
    private const val DATABASE_NAME = "library-auto.db"

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        try {
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        } catch (error: AppDatabaseOpenException) {
            throw error
        } catch (error: Exception) {
            throw AppDatabaseErrorReporter.openFailure(error)
        }

    private fun buildDatabase(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            )
            // GuardSchedulerService 跑在 :guard 独立进程，UI 进程对 reservation_tasks 的写入需要
            // 立刻通知 :guard 进程的 observeAll Flow 重新取值，否则要等下一次冷启动才能感知。
            .enableMultiInstanceInvalidation()
            .build()
}
