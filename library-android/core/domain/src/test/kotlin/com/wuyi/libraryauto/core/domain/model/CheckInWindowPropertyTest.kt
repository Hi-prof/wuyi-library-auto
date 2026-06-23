package com.wuyi.libraryauto.core.domain.model

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class CheckInWindowPropertyTest {
    @Test
    fun `remote values are always sanitized into supported range`() {
        val random = Random(42)
        val candidates = buildList<Any?> {
            add(null)
            add("")
            add("not-a-number")
            add("-1")
            add("0")
            add("1800")
            add("86400")
            add("86401")
            add(-1L)
            add(0L)
            add(1L)
            add(86_400L)
            add(86_401L)
            repeat(200) {
                val value = random.nextLong(-100_000L, 100_000L)
                add(value)
                add(value.toString())
            }
        }

        for (ago in candidates) {
            for (back in candidates) {
                val window = CheckInWindow.fromRemote(
                    startEpochSeconds = 1_710_000_000L,
                    limitSignAgoSeconds = ago,
                    limitSignBackSeconds = back,
                )

                assertThat(window.limitSignAgoSeconds).isAtLeast(0L)
                assertThat(window.limitSignAgoSeconds).isAtMost(CheckInWindow.MAX_WINDOW_SECONDS)
                assertThat(window.limitSignBackSeconds).isAtLeast(0L)
                assertThat(window.limitSignBackSeconds).isAtMost(CheckInWindow.MAX_WINDOW_SECONDS)
            }
        }
    }

    @Test
    fun `missing and invalid values fall back to 1800 seconds`() {
        val invalidValues = listOf<Any?>(
            null,
            "",
            "abc",
            "10.5",
            -1,
            86_401,
            1.5,
            true,
        )

        for (value in invalidValues) {
            val window = CheckInWindow.fromRemote(
                startEpochSeconds = 1_710_000_000L,
                limitSignAgoSeconds = value,
                limitSignBackSeconds = value,
            )

            assertThat(window.limitSignAgoSeconds).isEqualTo(CheckInWindow.FALLBACK_SECONDS)
            assertThat(window.limitSignBackSeconds).isEqualTo(CheckInWindow.FALLBACK_SECONDS)
        }
    }

    @Test
    fun `numeric strings and boundary values are accepted`() {
        val window = CheckInWindow.fromRemote(
            startEpochSeconds = 1_710_000_000L,
            limitSignAgoSeconds = "0",
            limitSignBackSeconds = "86400",
        )

        assertThat(window.limitSignAgoSeconds).isEqualTo(0L)
        assertThat(window.limitSignBackSeconds).isEqualTo(86_400L)
        assertThat(window.openFromEpochSeconds()).isEqualTo(1_710_000_000L)
        assertThat(window.closeAtEpochSeconds()).isEqualTo(1_710_086_400L)
    }

    @Test
    fun `open interval includes start and excludes close`() {
        val window = CheckInWindow(
            startEpochSeconds = 1_000L,
            limitSignAgoSeconds = 100L,
            limitSignBackSeconds = 200L,
        )

        assertThat(window.isOpen(899L)).isFalse()
        assertThat(window.isOpen(900L)).isTrue()
        assertThat(window.isOpen(1_199L)).isTrue()
        assertThat(window.isOpen(1_200L)).isFalse()
    }
}
