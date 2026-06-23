package com.wuyi.libraryauto.core.storage.db

object AppDatabaseErrorReporter {
    fun openFailure(cause: Exception): AppDatabaseOpenException =
        AppDatabaseOpenException(
            message = "本地数据库升级失败，已保留原有数据，不会自动清空或重建用户表。请重启应用；若仍失败，请导出日志后联系维护者处理。",
            cause = cause,
        )
}

class AppDatabaseOpenException(
    message: String,
    cause: Exception,
) : IllegalStateException(message, cause)
