package com.wuyi.libraryauto.core.domain.usecase

private const val GUARD_ADVANCE_SECONDS = 600L

class ComputeGuardWindowUseCase {
    operator fun invoke(
        startTimeEpochSeconds: Long,
        limitSignAgoSeconds: Long,
    ): Long = startTimeEpochSeconds - limitSignAgoSeconds - GUARD_ADVANCE_SECONDS
}
