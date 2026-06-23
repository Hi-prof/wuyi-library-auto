package com.wuyi.libraryauto.ui.repository.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountBulkImportParserTest {
    private val parser = AccountBulkImportParser()

    @Test
    fun `parse skips blank and comment lines`() {
        val result =
            parser.parse(
                rawText =
                    """

                    # comment
                    20230001:alpha
                       # another comment
                    20230002,beta
                    """.trimIndent(),
                existingStudentIds = emptySet(),
            )

        assertThat(result.accepted.map { it.studentId }).containsExactly("20230001", "20230002").inOrder()
        assertThat(result.invalid).isEmpty()
        assertThat(result.duplicates).isEmpty()
    }

    @Test
    fun `parse applies format priority and defaults password to student id`() {
        val result =
            parser.parse(
                rawText =
                    """
                    20230001:alpha:tail
                    20230002,beta,tail
                    20230003 gamma tail
                    20230004
                    """.trimIndent(),
                existingStudentIds = emptySet(),
            )

        assertThat(result.accepted.map { it.studentId })
            .containsExactly("20230001", "20230002", "20230003", "20230004")
            .inOrder()
        assertThat(result.accepted.map { it.password })
            .containsExactly("alpha:tail", "beta,tail", "gamma tail", "20230004")
            .inOrder()
    }

    @Test
    fun `parse validates student id and reports duplicates without password`() {
        val result =
            parser.parse(
                rawText =
                    """
                    20230001:secret
                    bad-id:secret2
                    ${"A".repeat(33)}:secret3
                    20230001:secret4
                    EXISTING:secret5
                    """.trimIndent(),
                existingStudentIds = setOf("EXISTING"),
            )

        assertThat(result.accepted.map { it.studentId }).containsExactly("20230001")
        assertThat(result.invalid.map { it.reason })
            .containsExactly(
                BulkImportFailureReason.InvalidStudentIdCharacter,
                BulkImportFailureReason.StudentIdTooLong,
            )
            .inOrder()
        assertThat(result.duplicates.map { it.reason })
            .containsExactly(
                BulkImportFailureReason.DuplicateInCurrentBatch,
                BulkImportFailureReason.DuplicateInExisting,
            )
            .inOrder()
        assertThat(result.toSummaryText()).doesNotContain("secret")
        assertThat(result.toString()).doesNotContain("secret")
    }

    @Test
    fun `parse rejects cap overflow`() {
        val overLimit =
            (1..201).joinToString("\n") { index ->
                "S$index"
            }

        val result = parser.parse(overLimit, existingStudentIds = emptySet())

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejectedByCap?.reasonText).isEqualTo(BULK_IMPORT_CAP_EXCEEDED_MESSAGE)
    }
}
