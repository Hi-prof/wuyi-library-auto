package com.wuyi.libraryauto.core.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BleScanThrottlerTest {
    @Test
    fun `allows five attempts in thirty second window and rejects sixth`() {
        var nowMillis = 1_000L
        val throttler = BleScanThrottler(nowMillis = { nowMillis })

        val results = List(6) { throttler.tryAcquire("booking-1") }

        assertThat(results).containsExactly(true, true, true, true, true, false).inOrder()
    }

    @Test
    fun `booking ids use independent windows`() {
        val throttler = BleScanThrottler(nowMillis = { 1_000L })

        repeat(5) {
            assertThat(throttler.tryAcquire("booking-1")).isTrue()
        }

        assertThat(throttler.tryAcquire("booking-1")).isFalse()
        assertThat(throttler.tryAcquire("booking-2")).isTrue()
    }

    @Test
    fun `reset clears booking window`() {
        val throttler = BleScanThrottler(nowMillis = { 1_000L })
        repeat(5) {
            assertThat(throttler.tryAcquire("booking-1")).isTrue()
        }

        throttler.reset("booking-1")

        assertThat(throttler.tryAcquire("booking-1")).isTrue()
    }

    @Test
    fun `expired attempts leave sliding window`() {
        var nowMillis = 1_000L
        val throttler = BleScanThrottler(nowMillis = { nowMillis })
        repeat(5) {
            assertThat(throttler.tryAcquire("booking-1")).isTrue()
        }
        assertThat(throttler.tryAcquire("booking-1")).isFalse()

        nowMillis += 30_000L

        assertThat(throttler.tryAcquire("booking-1")).isTrue()
    }
}
