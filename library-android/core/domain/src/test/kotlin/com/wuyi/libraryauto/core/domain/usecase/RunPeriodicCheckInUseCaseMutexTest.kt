package com.wuyi.libraryauto.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.model.SignInAttemptResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunPeriodicCheckInUseCaseMutexTest {

    @Test
    fun `periodic check in waits while guard worker holds same booking mutex`() = runTest {
        val mutexRegistry = BookingCheckInMutexRegistry()
        val guardEntered = CompletableDeferred<Unit>()
        val releaseGuard = CompletableDeferred<Unit>()
        val periodicAttemptStarted = CompletableDeferred<Unit>()
        val guardJob =
            launch {
                mutexRegistry.withLock("booking-1") {
                    guardEntered.complete(Unit)
                    releaseGuard.await()
                }
            }
        guardEntered.await()

        val useCase =
            RunPeriodicCheckInUseCase(
                accountSource = object : PeriodicCheckInAccountSource {
                    override suspend fun listAccounts(): List<PeriodicCheckInAccount> =
                        listOf(PeriodicCheckInAccount(studentId = "20230001", isSessionValid = true))
                },
                reservationSource = object : PeriodicCheckInReservationSource {
                    override suspend fun listReservations(
                        account: PeriodicCheckInAccount,
                        nowEpochSeconds: Long,
                    ): List<PeriodicCheckInReservation> =
                        listOf(
                            PeriodicCheckInReservation(
                                taskId = "task-1",
                                bookingId = "booking-1",
                                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                                startTimeEpochSeconds = 1_000L,
                                limitSignAgoSeconds = 600L,
                                limitSignBackSeconds = 1_800L,
                                expectedMinors = setOf(12),
                            ),
                        )
                },
                signInExecutor = object : PeriodicCheckInSignInExecutor {
                    override suspend fun attempt(attempt: PeriodicCheckInAttempt): SignInAttemptResult {
                        periodicAttemptStarted.complete(Unit)
                        return SignInAttemptResult(
                            correlationId = "corr-1",
                            matchedMinor = 12,
                            seenMinors = listOf(12),
                            signInError = null,
                            scanDurationMillis = 1_000L,
                        )
                    }
                },
                mutexRegistry = mutexRegistry,
                nowEpochSeconds = { 1_000L },
            )

        val periodicJob = async { useCase() }
        runCurrent()

        assertThat(periodicAttemptStarted.isCompleted).isFalse()

        releaseGuard.complete(Unit)
        periodicJob.await()
        guardJob.join()

        assertThat(periodicAttemptStarted.isCompleted).isTrue()
    }
}
