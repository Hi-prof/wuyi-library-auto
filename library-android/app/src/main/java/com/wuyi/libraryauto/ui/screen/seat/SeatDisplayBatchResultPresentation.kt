package com.wuyi.libraryauto.ui.screen.seat

import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.seat.BatchCheckInResult
import com.wuyi.libraryauto.ui.repository.seat.BatchReservationResult

internal data class SeatDisplayBatchResultPresentation(
    val title: String,
    val summary: String,
    val statusBadgeLabel: String,
    val statusBadgeTone: StatusTone,
    val detailTitle: String?,
    val detailLines: List<String>,
    val confirmAction: SeatDisplayBatchResultActionPresentation,
)

internal data class SeatDisplayBatchResultActionPresentation(
    val label: String,
)

internal fun buildBatchCheckInResultPresentation(
    result: BatchCheckInResult,
): SeatDisplayBatchResultPresentation =
    buildSeatDisplayBatchResultPresentation(
        title = "签到完成",
        success = result.success,
        failed = result.failed,
        failureLines =
            result.details
                .asSequence()
                .filter { detail -> !detail.success }
                .take(MaxFailureDetailLines)
                .map { detail -> "${detail.studentId}：${detail.error ?: detail.message}" }
                .toList(),
    )

internal fun buildBatchReservationResultPresentation(
    result: BatchReservationResult,
): SeatDisplayBatchResultPresentation =
    buildSeatDisplayBatchResultPresentation(
        title = "补约完成",
        success = result.success,
        failed = result.failed,
        failureLines =
            result.details
                .asSequence()
                .filter { detail -> !detail.success }
                .take(MaxFailureDetailLines)
                .map { detail -> "${detail.studentId} ${detail.targetDate}：${detail.error ?: detail.message}" }
                .toList(),
    )

private fun buildSeatDisplayBatchResultPresentation(
    title: String,
    success: Int,
    failed: Int,
    failureLines: List<String>,
): SeatDisplayBatchResultPresentation =
    SeatDisplayBatchResultPresentation(
        title = title,
        summary = "成功 $success 个，失败 $failed 个",
        statusBadgeLabel =
            when {
                success == 0 && failed == 0 -> "无可处理"
                failed == 0 -> "全部完成"
                success == 0 -> "全部失败"
                else -> "部分失败"
            },
        statusBadgeTone =
            when {
                success == 0 && failed == 0 -> StatusTone.Neutral
                failed == 0 -> StatusTone.Positive
                success == 0 -> StatusTone.Negative
                else -> StatusTone.Warning
            },
        detailTitle = if (failed > 0) "失败原因（前3条）" else null,
        detailLines = failureLines,
        confirmAction = SeatDisplayBatchResultActionPresentation(label = "确定"),
    )

private const val MaxFailureDetailLines = 3
