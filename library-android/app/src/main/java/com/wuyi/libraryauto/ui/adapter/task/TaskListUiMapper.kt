package com.wuyi.libraryauto.ui.adapter.task

import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TaskListItemUiModel(
    val id: String,
    val title: String,
    val timeText: String,
    val seatText: String,
    val status: TaskStatusUiModel,
    val description: String,
)

data class TaskStatusUiModel(
    val text: String,
    val tone: TaskStatusTone,
)

enum class TaskStatusTone {
    POSITIVE,
    WARNING,
    NEGATIVE,
    NEUTRAL,
}

private data class TaskStatusMeta(
    val text: String,
    val tone: TaskStatusTone,
    val defaultDescription: String,
)

private val timeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

fun ReservationTaskEntity.toTaskListItemUiModel(
    zoneId: ZoneId = ZoneId.systemDefault(),
): TaskListItemUiModel {
    val statusMeta = state.toStatusMeta()
    val startTime = Instant.ofEpochSecond(startTimeEpochSeconds).atZone(zoneId)
    val signDeadline = Instant.ofEpochSecond(startTimeEpochSeconds - limitSignAgoSeconds).atZone(zoneId)
    val safeSeatText = seatNumber.trim().ifEmpty { "暂未分配座位" }
    val safeError = lastError?.trim().orEmpty()
    val description =
        if (safeError.isEmpty()) {
            statusMeta.defaultDescription
        } else {
            "${statusMeta.defaultDescription} 最近错误：$safeError"
        }

    return TaskListItemUiModel(
        id = id,
        title = "预约任务 · ${id.takeLast(6)}",
        timeText = "开始 ${timeFormatter.format(startTime)} · 截止 ${timeFormatter.format(signDeadline)}",
        seatText = safeSeatText,
        status = TaskStatusUiModel(text = statusMeta.text, tone = statusMeta.tone),
        description = description,
    )
}

private fun ReservationTaskState.toStatusMeta(): TaskStatusMeta =
    when (this) {
        ReservationTaskState.PENDING_RESERVATION ->
            TaskStatusMeta(
                text = "待预约",
                tone = TaskStatusTone.WARNING,
                defaultDescription = "等待预约窗口，窗口开启后会自动尝试抢座。",
            )
        ReservationTaskState.RESERVING ->
            TaskStatusMeta(
                text = "预约中",
                tone = TaskStatusTone.WARNING,
                defaultDescription = "任务正在提交预约请求。",
            )
        ReservationTaskState.RESERVED_WAITING_SIGNIN ->
            TaskStatusMeta(
                text = "待签到",
                tone = TaskStatusTone.POSITIVE,
                defaultDescription = "预约成功，等待签到窗口并执行签到。",
            )
        ReservationTaskState.GUARD_SCHEDULED ->
            TaskStatusMeta(
                text = "已排守护",
                tone = TaskStatusTone.POSITIVE,
                defaultDescription = "守护任务已安排，临近签到窗口会自动启动。",
            )
        ReservationTaskState.SCANNING ->
            TaskStatusMeta(
                text = "签到中",
                tone = TaskStatusTone.POSITIVE,
                defaultDescription = "正在扫描签到条件。",
            )
        ReservationTaskState.SIGNIN_SUCCESS ->
            TaskStatusMeta(
                text = "已完成",
                tone = TaskStatusTone.POSITIVE,
                defaultDescription = "任务已完成签到。",
            )
        ReservationTaskState.FAILED_RETRYABLE ->
            TaskStatusMeta(
                text = "可重试",
                tone = TaskStatusTone.WARNING,
                defaultDescription = "任务执行失败，可稍后重试。",
            )
        ReservationTaskState.FAILED_MANUAL_ACTION ->
            TaskStatusMeta(
                text = "需处理",
                tone = TaskStatusTone.NEGATIVE,
                defaultDescription = "任务执行失败，需要手动处理。",
            )
        ReservationTaskState.CANCELLED ->
            TaskStatusMeta(
                text = "已取消",
                tone = TaskStatusTone.NEUTRAL,
                defaultDescription = "任务已取消，不再继续执行。",
            )
    }
