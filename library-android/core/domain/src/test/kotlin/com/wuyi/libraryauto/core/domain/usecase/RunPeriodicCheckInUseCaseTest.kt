package com.wuyi.libraryauto.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.model.SignInAttemptResult
import com.wuyi.libraryauto.core.domain.model.SignInError
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RunPeriodicCheckInUseCaseTest {

    @Test
    fun `processes accounts concurrently`() = runTest {
        val calls = mutableListOf<String>()
        val useCase =
            useCase(
                accounts = listOf(account("20230001"), account("20230002")),
                reservations = mapOf(
                    "20230001" to listOf(reservation(bookingId = "booking-1")),
                    "20230002" to listOf(reservation(bookingId = "booking-2")),
                ),
                executor = { attempt ->
                    calls += "${attempt.account.studentId}:${attempt.reservation.bookingId}"
                    success(attempt)
                },
            )

        val summary = useCase()

        // BUG-B 修复：账号间并发后顺序不再保证；只断言两个账号都被处理过即可。
        assertThat(calls).containsExactly("20230001:booking-1", "20230002:booking-2")
        assertThat(summary.attemptedReservations).isEqualTo(2)
    }

    @Test
    fun `counts failed reservation attempts`() = runTest {
        val useCase =
            useCase(
                accounts = listOf(account("20230001")),
                reservations = mapOf("20230001" to listOf(reservation(bookingId = "booking-1"))),
                executor = { attempt -> success(attempt).copy(signInError = SignInError.NetworkError) },
            )

        val summary = useCase()

        assertThat(summary.attemptedReservations).isEqualTo(1)
        assertThat(summary.failedReservations).isEqualTo(1)
    }

    @Test
    fun `skips expired sessions and logs Chinese message`() = runTest {
        val logs = mutableListOf<String>()
        val useCase =
            useCase(
                accounts = listOf(account("20230001", isSessionValid = false)),
                reservations = emptyMap(),
                logger = { studentId, message -> logs += "$studentId:$message" },
            )

        val summary = useCase()

        assertThat(summary.accountResults.single().status)
            .isEqualTo(PeriodicCheckInAccountStatus.SkippedExpiredSession)
        assertThat(logs).containsExactly("20230001:账号登录态过期，已跳过")
    }

    @Test
    fun `skips signed ended outside window and recent guard attempts`() = runTest {
        val attempted = mutableListOf<String>()
        val useCase =
            useCase(
                accounts = listOf(account("20230001")),
                reservations = mapOf(
                    "20230001" to listOf(
                        reservation(bookingId = "signed", isAlreadySignedIn = true),
                        reservation(bookingId = "ended", isEnded = true),
                        reservation(bookingId = "outside", startTimeEpochSeconds = 4_000L),
                        reservation(bookingId = "recent", lastGuardAttemptEpochSeconds = 990L),
                        reservation(bookingId = "valid"),
                    ),
                ),
                executor = { attempt ->
                    attempted += attempt.reservation.bookingId
                    success(attempt)
                },
            )

        val summary = useCase()

        assertThat(attempted).containsExactly("valid")
        assertThat(summary.attemptedReservations).isEqualTo(1)
        assertThat(summary.skippedReservations).isEqualTo(4)
    }

    @Test
    fun `times out a slow account`() = runTest {
        val logs = mutableListOf<String>()
        val useCase =
            useCase(
                accounts = listOf(account("20230001")),
                reservations = emptyMap(),
                reservationDelayMillis = 1_000L,
                singleAccountTimeoutMillis = 100L,
                logger = { studentId, message -> logs += "$studentId:$message" },
            )

        val summary = useCase()

        assertThat(summary.accountResults.single().status).isEqualTo(PeriodicCheckInAccountStatus.TimedOut)
        assertThat(summary.timedOutAccounts).isEqualTo(1)
        assertThat(logs).containsExactly("20230001:周期签到超时，已跳过")
    }

    @Test
    fun `skips reservation when last guard attempt is within 60 seconds`() = runTest {
        // BUG 2 回归测试：lastGuardAttemptEpochSeconds 在 60 秒内必须把 reservation 标记为 skipped，
        // 防止周期签到 + GuardWorker 60s 重试在同一 booking 上重复发起签到。
        val attempted = mutableListOf<String>()
        val useCase =
            useCase(
                accounts = listOf(account("20230001")),
                reservations =
                    mapOf(
                        "20230001" to
                            listOf(
                                reservation(bookingId = "fresh", lastGuardAttemptEpochSeconds = 950L),
                                reservation(bookingId = "stale", lastGuardAttemptEpochSeconds = 900L),
                            ),
                    ),
                executor = { attempt ->
                    attempted += attempt.reservation.bookingId
                    success(attempt)
                },
            )

        val summary = useCase()

        assertThat(attempted).containsExactly("stale")
        assertThat(summary.attemptedReservations).isEqualTo(1)
        assertThat(summary.skippedReservations).isEqualTo(1)
    }

    private fun useCase(
        accounts: List<PeriodicCheckInAccount>,
        reservations: Map<String, List<PeriodicCheckInReservation>>,
        reservationDelayMillis: Long = 0L,
        singleAccountTimeoutMillis: Long = 60_000L,
        logger: suspend (String, String) -> Unit = { _, _ -> },
        executor: suspend (PeriodicCheckInAttempt) -> SignInAttemptResult = { attempt -> success(attempt) },
    ): RunPeriodicCheckInUseCase =
        RunPeriodicCheckInUseCase(
            accountSource = object : PeriodicCheckInAccountSource {
                override suspend fun listAccounts(): List<PeriodicCheckInAccount> = accounts
            },
            reservationSource = object : PeriodicCheckInReservationSource {
                override suspend fun listReservations(
                    account: PeriodicCheckInAccount,
                    nowEpochSeconds: Long,
                ): List<PeriodicCheckInReservation> {
                    if (reservationDelayMillis > 0L) {
                        delay(reservationDelayMillis)
                    }
                    return reservations[account.studentId].orEmpty()
                }
            },
            signInExecutor = object : PeriodicCheckInSignInExecutor {
                override suspend fun attempt(attempt: PeriodicCheckInAttempt): SignInAttemptResult =
                    executor(attempt)
            },
            eventLogger = object : PeriodicCheckInEventLogger {
                override suspend fun log(
                    studentId: String,
                    message: String,
                ) = logger(studentId, message)
            },
            mutexRegistry = BookingCheckInMutexRegistry(),
            nowEpochSeconds = { 1_000L },
            singleAccountTimeoutMillis = singleAccountTimeoutMillis,
        )

    private fun account(
        studentId: String,
        isSessionValid: Boolean = true,
    ): PeriodicCheckInAccount =
        PeriodicCheckInAccount(
            studentId = studentId,
            isSessionValid = isSessionValid,
        )

    private fun reservation(
        bookingId: String = "booking-1",
        state: ReservationTaskState = ReservationTaskState.RESERVED_WAITING_SIGNIN,
        startTimeEpochSeconds: Long = 1_000L,
        limitSignAgoSeconds: Long = 600L,
        limitSignBackSeconds: Long = 1_800L,
        lastGuardAttemptEpochSeconds: Long? = null,
        isAlreadySignedIn: Boolean = false,
        isEnded: Boolean = false,
    ): PeriodicCheckInReservation =
        PeriodicCheckInReservation(
            taskId = "task-$bookingId",
            bookingId = bookingId,
            state = state,
            startTimeEpochSeconds = startTimeEpochSeconds,
            limitSignAgoSeconds = limitSignAgoSeconds,
            limitSignBackSeconds = limitSignBackSeconds,
            expectedMinors = setOf(12),
            lastGuardAttemptEpochSeconds = lastGuardAttemptEpochSeconds,
            isAlreadySignedIn = isAlreadySignedIn,
            isEnded = isEnded,
        )
}

private fun success(attempt: PeriodicCheckInAttempt): SignInAttemptResult =
    SignInAttemptResult(
        correlationId = "${attempt.reservation.bookingId}:corr",
        matchedMinor = 12,
        seenMinors = listOf(12),
        signInError = null,
        scanDurationMillis = 1_000L,
    )
