package com.wuyi.libraryauto.ui.repository.account

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import kotlin.system.measureTimeMillis
import org.junit.Test

class AccountFilterTest {
    private val filter = AccountFilter()

    @Test
    fun `blank query returns original accounts`() {
        val accounts =
            listOf(
                SavedAccountEntry(studentId = "A001", password = "pw"),
                SavedAccountEntry(studentId = "B002", password = "pw"),
            )

        assertThat(filter.filter(accounts, "   ")).isSameInstanceAs(accounts)
    }

    @Test
    fun `query matches student id room and seat label ignoring case`() {
        val accounts =
            listOf(
                SavedAccountEntry(studentId = "A001", password = "pw", preferredRoomName = "North Hall"),
                SavedAccountEntry(studentId = "B002", password = "pw", preferredSeatLabel = "二楼 / 166"),
                SavedAccountEntry(studentId = "C003", password = "pw", preferredRoomName = "South"),
            )

        assertThat(filter.filter(accounts, "north").map { it.studentId }).containsExactly("A001")
        assertThat(filter.filter(accounts, "166").map { it.studentId }).containsExactly("B002")
        assertThat(filter.filter(accounts, "c003").map { it.studentId }).containsExactly("C003")
    }

    @Test
    fun `query result is subsequence and fast for two hundred accounts`() {
        val accounts =
            (1..200).map { index ->
                SavedAccountEntry(
                    studentId = "S$index",
                    password = "pw",
                    preferredRoomName = if (index % 2 == 0) "East" else "West",
                )
            }

        lateinit var result: List<SavedAccountEntry>
        val elapsed = measureTimeMillis {
            result = filter.filter(accounts, "east")
        }

        val expectedStudentIds =
            accounts
                .filter { it.preferredRoomName == "East" }
                .map { it.studentId }
        assertThat(result.map { it.studentId }).containsExactlyElementsIn(expectedStudentIds).inOrder()
        assertThat(elapsed).isLessThan(100L)
    }
}
