package com.wuyi.libraryauto.core.domain.usecase

import com.wuyi.libraryauto.core.domain.model.SignInAttemptResult
import com.wuyi.libraryauto.core.domain.model.SignInError
import com.wuyi.libraryauto.core.domain.port.BeaconScanResult
import com.wuyi.libraryauto.core.domain.port.BeaconScanner
import com.wuyi.libraryauto.core.domain.port.SeatGateway

class SignInWithBeaconUseCase(
    private val scanner: BeaconScanner,
    private val seatGateway: SeatGateway,
) {
    suspend operator fun invoke(
        expectedMinors: Set<Int>,
        timeoutMillis: Long,
        correlationId: String,
    ): SignInAttemptResult {
        val scanResult = scanner.scan(
            expectedMinors = expectedMinors,
            timeoutMillis = timeoutMillis,
            correlationId = correlationId,
        )

        if (scanResult !is BeaconScanResult.Matched) {
            return scanResult.toAttemptResult(signInError = scanResult.toSignInError())
        }

        return SignInAttemptResult(
            correlationId = scanResult.correlationId,
            matchedMinor = scanResult.matchedMinor,
            seenMinors = scanResult.seenMinors,
            signInError = seatGateway.performCheckIn(),
            scanDurationMillis = scanResult.scanDurationMillis,
        )
    }

    private fun BeaconScanResult.toAttemptResult(signInError: SignInError): SignInAttemptResult =
        SignInAttemptResult(
            correlationId = correlationId,
            matchedMinor = null,
            seenMinors = seenMinors,
            signInError = signInError,
            scanDurationMillis = scanDurationMillis,
        )

    private fun BeaconScanResult.toSignInError(): SignInError = when (this) {
        is BeaconScanResult.PermissionDenied -> SignInError.PermissionDenied
        is BeaconScanResult.BluetoothDisabled -> SignInError.BluetoothDisabled
        is BeaconScanResult.Aborted,
        is BeaconScanResult.EmptyExpectedMinors,
        is BeaconScanResult.ForegroundServiceUnavailable,
        is BeaconScanResult.Timeout,
        -> SignInError.BeaconNotMatched
        is BeaconScanResult.Matched -> error("Matched scan results are handled before error mapping.")
    }
}
