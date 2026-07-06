package com.wuyi.libraryauto.core.domain.model

data class CheckInWindow(
    val startEpochSeconds: Long,
    val limitSignAgoSeconds: Long,
    val limitSignBackSeconds: Long,
) {
    fun openFromEpochSeconds(): Long = startEpochSeconds - limitSignAgoSeconds

    fun closeAtEpochSeconds(): Long = startEpochSeconds + limitSignBackSeconds

    fun isOpen(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds >= openFromEpochSeconds() && nowEpochSeconds < closeAtEpochSeconds()

    companion object {
        const val FALLBACK_SECONDS = 1_800L
        const val MAX_WINDOW_SECONDS = 86_400L

        /**
         * 自动签到统一的"开始时间 +30 分钟"上限。
         *
         * 周期签到、GuardWorker 都使用此值给 `limitSignBackSeconds` 截断，
         * 避免学校返回的过长签到窗口让自动签到一直空打学校接口。
         */
        const val AUTO_SIGN_BACK_CAP_SECONDS: Long = 1_800L

        /** 用 [AUTO_SIGN_BACK_CAP_SECONDS] 截断学校给的 `limitSignBackSeconds`。 */
        fun capSignBackSeconds(limitSignBackSeconds: Long): Long =
            limitSignBackSeconds.coerceAtMost(AUTO_SIGN_BACK_CAP_SECONDS).coerceAtLeast(0L)

        fun fromRemote(
            startEpochSeconds: Long,
            limitSignAgoSeconds: Any?,
            limitSignBackSeconds: Any?,
        ): CheckInWindow = CheckInWindow(
            startEpochSeconds = startEpochSeconds,
            limitSignAgoSeconds = sanitize(limitSignAgoSeconds) ?: FALLBACK_SECONDS,
            limitSignBackSeconds = sanitize(limitSignBackSeconds) ?: FALLBACK_SECONDS,
        )

        private fun sanitize(value: Any?): Long? =
            parseRemoteSeconds(value)?.takeIf { it in 0L..MAX_WINDOW_SECONDS }

        private fun parseRemoteSeconds(value: Any?): Long? = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }
}
