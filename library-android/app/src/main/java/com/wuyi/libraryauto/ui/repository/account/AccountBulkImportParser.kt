package com.wuyi.libraryauto.ui.repository.account

import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import java.nio.charset.StandardCharsets

class AccountBulkImportParser {
    fun parse(
        rawText: String,
        existingStudentIds: Set<String>,
    ): BulkImportResult {
        if (rawText.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            return BulkImportResult(rejectedByCap = BulkImportCapFailure())
        }

        val accepted = mutableListOf<SavedAccountEntry>()
        val invalid = mutableListOf<BulkImportFailure>()
        val duplicates = mutableListOf<BulkImportFailure>()
        val existing = existingStudentIds.map(String::trim).toSet()
        val currentBatch = linkedSetOf<String>()

        rawText.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                return@forEachIndexed
            }

            val parsed = parseLine(trimmed)
            val studentId = parsed.studentId.trim()
            val reason = validateStudentId(studentId)
            if (reason != null) {
                invalid += failure(lineNumber, studentId, reason)
                return@forEachIndexed
            }
            if (studentId in existing) {
                duplicates += failure(lineNumber, studentId, BulkImportFailureReason.DuplicateInExisting)
                return@forEachIndexed
            }
            if (studentId in currentBatch) {
                duplicates += failure(lineNumber, studentId, BulkImportFailureReason.DuplicateInCurrentBatch)
                return@forEachIndexed
            }

            currentBatch += studentId
            accepted +=
                SavedAccountEntry(
                    studentId = studentId,
                    password = parsed.password.trim().ifBlank { studentId },
                )
            if (accepted.size > MAX_ACCEPTED) {
                return BulkImportResult(rejectedByCap = BulkImportCapFailure())
            }
        }

        return BulkImportResult(
            accepted = accepted,
            invalid = invalid,
            duplicates = duplicates,
        )
    }

    private fun parseLine(line: String): ParsedAccountLine {
        for (regex in FORMAT_REGEXES) {
            val match = regex.matchEntire(line) ?: continue
            return ParsedAccountLine(
                studentId = match.groupValues.getOrElse(1) { "" },
                password = match.groupValues.getOrElse(2) { "" },
            )
        }
        return ParsedAccountLine(studentId = "", password = "")
    }

    private fun validateStudentId(studentId: String): BulkImportFailureReason? =
        when {
            studentId.isBlank() -> BulkImportFailureReason.EmptyStudentId
            studentId.length > MAX_STUDENT_ID_LENGTH -> BulkImportFailureReason.StudentIdTooLong
            !STUDENT_ID_REGEX.matches(studentId) -> BulkImportFailureReason.InvalidStudentIdCharacter
            else -> null
        }

    private fun failure(
        lineNumber: Int,
        studentId: String,
        reason: BulkImportFailureReason,
    ): BulkImportFailure =
        BulkImportFailure(
            lineNumber = lineNumber,
            studentId = studentId,
            reason = reason,
            rawLineSummary = "学号 ${studentId.ifBlank { "<空>" }}",
        )

    private data class ParsedAccountLine(
        val studentId: String,
        val password: String,
    )

    private companion object {
        private const val MAX_BYTES = 200 * 1024
        private const val MAX_ACCEPTED = 200
        private const val MAX_STUDENT_ID_LENGTH = 32
        private val STUDENT_ID_REGEX = Regex("^[A-Za-z0-9_]+$")
        private val FORMAT_REGEXES =
            listOf(
                Regex("^([^:]+):(.*)$"),
                Regex("^([^,]+),(.*)$"),
                Regex("^(\\S+)\\s+(.+)$"),
                Regex("^(\\S+)$"),
            )
    }
}
