package com.wuyi.libraryauto.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.SignInAttemptResult
import com.wuyi.libraryauto.core.domain.model.SignInError
import com.wuyi.libraryauto.core.domain.port.BeaconScanResult
import com.wuyi.libraryauto.core.domain.port.BeaconScanner
import com.wuyi.libraryauto.core.domain.port.SeatGateway
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SignInWithBeaconUseCaseTest {
    @Test
    fun `returns success attempt when scan matches and check in succeeds`() = runTest {
        val scanner = FakeBeaconScanner(
            result = BeaconScanResult.Matched(
                matchedMinor = 58,
                seenMinors = listOf(12, 58),
                scanDurationMillis = 240L,
                correlationId = "corr-1",
            ),
        )
        val seatGateway = RecordingSeatGateway(signInError = null)

        val result = SignInWithBeaconUseCase(scanner, seatGateway).invoke(
            expectedMinors = setOf(58, 77),
            timeoutMillis = 8_000L,
            correlationId = "corr-1",
        )

        assertThat(result).isEqualTo(
            SignInAttemptResult(
                correlationId = "corr-1",
                matchedMinor = 58,
                seenMinors = listOf(12, 58),
                signInError = null,
                scanDurationMillis = 240L,
            ),
        )
        assertThat(seatGateway.checkInCount).isEqualTo(1)
        assertThat(scanner.expectedMinors).isEqualTo(setOf(58, 77))
        assertThat(scanner.timeoutMillis).isEqualTo(8_000L)
        assertThat(scanner.correlationId).isEqualTo("corr-1")
    }

    @Test
    fun `maps timeout to beacon not matched and skips check in`() = runTest {
        val scanner = FakeBeaconScanner(
            result = BeaconScanResult.Timeout(
                seenMinors = listOf(12, 34),
                scanDurationMillis = 8_000L,
                correlationId = "corr-2",
            ),
        )
        val seatGateway = RecordingSeatGateway(signInError = null)

        val result = SignInWithBeaconUseCase(scanner, seatGateway).invoke(
            expectedMinors = setOf(58, 77),
            timeoutMillis = 8_000L,
            correlationId = "corr-2",
        )

        assertThat(result).isEqualTo(
            SignInAttemptResult(
                correlationId = "corr-2",
                matchedMinor = null,
                seenMinors = listOf(12, 34),
                signInError = SignInError.BeaconNotMatched,
                scanDurationMillis = 8_000L,
            ),
        )
        assertThat(seatGateway.checkInCount).isEqualTo(0)
    }

    @Test
    fun `maps permission denied and skips check in`() = runTest {
        val scanner = FakeBeaconScanner(
            result = BeaconScanResult.PermissionDenied(correlationId = "corr-3"),
        )
        val seatGateway = RecordingSeatGateway(signInError = null)

        val result = SignInWithBeaconUseCase(scanner, seatGateway).invoke(
            expectedMinors = setOf(58, 77),
            timeoutMillis = 8_000L,
            correlationId = "corr-3",
        )

        assertThat(result).isEqualTo(
            SignInAttemptResult(
                correlationId = "corr-3",
                matchedMinor = null,
                seenMinors = emptyList(),
                signInError = SignInError.PermissionDenied,
                scanDurationMillis = 0L,
            ),
        )
        assertThat(seatGateway.checkInCount).isEqualTo(0)
    }

    @Test
    fun `maps bluetooth disabled and skips check in`() = runTest {
        val scanner = FakeBeaconScanner(
            result = BeaconScanResult.BluetoothDisabled(correlationId = "corr-4"),
        )
        val seatGateway = RecordingSeatGateway(signInError = null)

        val result = SignInWithBeaconUseCase(scanner, seatGateway).invoke(
            expectedMinors = setOf(58, 77),
            timeoutMillis = 8_000L,
            correlationId = "corr-4",
        )

        assertThat(result.signInError).isEqualTo(SignInError.BluetoothDisabled)
        assertThat(result.correlationId).isEqualTo("corr-4")
        assertThat(seatGateway.checkInCount).isEqualTo(0)
    }

    @Test
    fun `returns check in error when matched scan is rejected by gateway`() = runTest {
        val scanner = FakeBeaconScanner(
            result = BeaconScanResult.Matched(
                matchedMinor = 58,
                seenMinors = listOf(58, 77),
                scanDurationMillis = 120L,
                correlationId = "corr-5",
            ),
        )
        val seatGateway = RecordingSeatGateway(signInError = SignInError.ServerRejected)

        val result = SignInWithBeaconUseCase(scanner, seatGateway).invoke(
            expectedMinors = setOf(58, 77),
            timeoutMillis = 8_000L,
            correlationId = "corr-5",
        )

        assertThat(result).isEqualTo(
            SignInAttemptResult(
                correlationId = "corr-5",
                matchedMinor = 58,
                seenMinors = listOf(58, 77),
                signInError = SignInError.ServerRejected,
                scanDurationMillis = 120L,
            ),
        )
        assertThat(seatGateway.checkInCount).isEqualTo(1)
    }

    private class FakeBeaconScanner(
        private val result: BeaconScanResult,
    ) : BeaconScanner {
        var expectedMinors: Set<Int>? = null
            private set
        var timeoutMillis: Long? = null
            private set
        var correlationId: String? = null
            private set

        override suspend fun scan(
            expectedMinors: Set<Int>,
            timeoutMillis: Long,
            correlationId: String,
        ): BeaconScanResult {
            this.expectedMinors = expectedMinors
            this.timeoutMillis = timeoutMillis
            this.correlationId = correlationId
            return result
        }
    }

    private class RecordingSeatGateway(
        private val signInError: SignInError?,
    ) : SeatGateway {
        var checkInCount: Int = 0
            private set

        override suspend fun performCheckIn(): SignInError? {
            checkInCount += 1
            return signInError
        }
    }
}
