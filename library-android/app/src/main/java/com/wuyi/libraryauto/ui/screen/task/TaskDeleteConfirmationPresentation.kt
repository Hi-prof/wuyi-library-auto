package com.wuyi.libraryauto.ui.screen.task

import com.wuyi.libraryauto.ui.components.StatusTone

internal data class TaskDeleteConfirmationPresentation(
    val title: String,
    val message: String,
    val badgeLabel: String,
    val badgeTone: StatusTone,
    val confirmAction: TaskDeleteConfirmationActionPresentation,
    val dismissAction: TaskDeleteConfirmationActionPresentation,
)

internal data class TaskDeleteConfirmationActionPresentation(
    val label: String,
)

internal fun buildTaskDeleteConfirmationPresentation(): TaskDeleteConfirmationPresentation =
    TaskDeleteConfirmationPresentation(
        title = "删除自动任务",
        message = "删除后该自动任务不再执行，可在添加任务里重新创建。",
        badgeLabel = "不可撤销",
        badgeTone = StatusTone.Negative,
        confirmAction = TaskDeleteConfirmationActionPresentation(label = "确认删除"),
        dismissAction = TaskDeleteConfirmationActionPresentation(label = "取消"),
    )
