package com.wuyi.libraryauto.core.runtime.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPlanReservationRetryPolicyTest {

    @Test
    fun `retry policy treats rate limit message as recoverable`() {
        val error = IllegalArgumentException("请求太频繁了，请稍后再试")

        assertTrue(AutomationPlanReservationRetryPolicy.isRateLimitFailure(error))
        assertTrue(AutomationPlanReservationRetryPolicy.isRecoverableFailure(error))
    }

    @Test
    fun `retry policy keeps seat conflict non recoverable`() {
        val error = IllegalArgumentException("座位不可预约")

        assertFalse(AutomationPlanReservationRetryPolicy.isRateLimitFailure(error))
        assertFalse(AutomationPlanReservationRetryPolicy.isRecoverableFailure(error))
    }
}
