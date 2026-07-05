package com.wuyi.libraryauto.ui.repository.seat

import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutionResult
import java.time.LocalDate

data class CheckInResult(
    val studentId: String,
    val success: Boolean,
    val message: String,
    val error: String? = null,
)

data class BatchCheckInResult(
    val total: Int,
    val success: Int,
    val failed: Int,
    val details: List<CheckInResult>,
) {
    val successRate: Double
        get() = if (total > 0) success.toDouble() / total else 0.0
}

data class ReservationResult(
    val studentId: String,
    val targetDate: LocalDate,
    val success: Boolean,
    val message: String,
    val bookingId: String? = null,
    val error: String? = null,
    val skipped: Boolean = false,
)

data class BatchReservationResult(
    val total: Int,
    val success: Int,
    val failed: Int,
    val details: List<ReservationResult>,
) {
    val successRate: Double
        get() = if (total > 0) success.toDouble() / total else 0.0

    companion object {
        fun fromResults(results: List<ReservationResult>): BatchReservationResult {
            val actionable = results.filterNot { it.skipped }
            return BatchReservationResult(
                total = results.size,
                success = actionable.count { it.success },
                failed = actionable.count { !it.success },
                details = results,
            )
        }
    }
}
