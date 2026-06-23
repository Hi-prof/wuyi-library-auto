package com.wuyi.libraryauto.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComputeGuardWindowUseCaseTest {
    @Test
    fun `returns guard window start 10 minutes before limit sign time`() {
        val useCase = ComputeGuardWindowUseCase()

        val result = useCase(
            startTimeEpochSeconds = 1_710_000_000L,
            limitSignAgoSeconds = 1_800L,
        )

        assertThat(result).isEqualTo(1_709_997_600L)
    }
}
