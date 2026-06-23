package com.wuyi.libraryauto.ui.repository.account

import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry

data class BulkImportResult(
    val accepted: List<SavedAccountEntry> = emptyList(),
    val invalid: List<BulkImportFailure> = emptyList(),
    val duplicates: List<BulkImportFailure> = emptyList(),
    val rejectedByCap: BulkImportCapFailure? = null,
) {
    val acceptedCount: Int = accepted.size
    val skippedCount: Int = duplicates.size
    val failedCount: Int = invalid.size + duplicates.size + if (rejectedByCap == null) 0 else 1

    override fun toString(): String =
        "BulkImportResult(acceptedCount=$acceptedCount, skippedCount=$skippedCount, failedCount=$failedCount)"
}

data class BulkImportFailure(
    val lineNumber: Int,
    val studentId: String,
    val reason: BulkImportFailureReason,
    val rawLineSummary: String = "学号 $studentId",
)

enum class BulkImportFailureReason {
    EmptyStudentId,
    StudentIdTooLong,
    InvalidStudentIdCharacter,
    DuplicateInExisting,
    DuplicateInCurrentBatch,
}

data class BulkImportCapFailure(
    val reasonText: String = BULK_IMPORT_CAP_EXCEEDED_MESSAGE,
)

data class BulkImportDialogState(
    val isVisible: Boolean = false,
    val rawText: String = "",
    val isSubmitting: Boolean = false,
    val result: BulkImportResult? = null,
)

fun BulkImportFailureReason.toChineseLabel(): String =
    when (this) {
        BulkImportFailureReason.EmptyStudentId -> "学号为空"
        BulkImportFailureReason.StudentIdTooLong -> "学号长度超过 32 个字符"
        BulkImportFailureReason.InvalidStudentIdCharacter -> "学号包含非法字符（仅允许字母数字下划线）"
        BulkImportFailureReason.DuplicateInExisting -> "学号已存在"
        BulkImportFailureReason.DuplicateInCurrentBatch -> "本批次内重复"
    }

fun BulkImportResult.toSummaryText(): String =
    buildString {
        appendLine("成功 $acceptedCount 条")
        appendLine("跳过 $skippedCount 条（重复）")
        appendLine("失败 $failedCount 条")
        rejectedByCap?.let { appendLine(it.reasonText) }
        val failures = invalid + duplicates
        if (failures.isNotEmpty()) {
            appendLine("失败明细：")
            failures.forEach { failure ->
                appendLine("第 ${failure.lineNumber} 行：${failure.reason.toChineseLabel()}（${failure.rawLineSummary}）")
            }
        }
    }.trimEnd()

const val BULK_IMPORT_CAP_EXCEEDED_MESSAGE = "单次批量导入超过上限，请分批导入"
