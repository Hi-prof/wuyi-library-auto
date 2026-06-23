package com.wuyi.libraryauto.core.runtime.worker

import java.io.IOException
import java.net.SocketTimeoutException

internal object AutomationPlanReservationRetryPolicy {
    private val rateLimitKeywords = listOf("请求太频繁", "稍后再试")

    fun isRecoverableFailure(error: Throwable): Boolean {
        if (error is IOException || error is SocketTimeoutException) {
            return true
        }
        if (isRateLimitFailure(error)) {
            return true
        }
        val cause = error.cause ?: return false
        return isRecoverableFailure(cause)
    }

    fun isRateLimitFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        if (rateLimitKeywords.any(message::contains)) {
            return true
        }
        val cause = error.cause ?: return false
        return isRateLimitFailure(cause)
    }
}
