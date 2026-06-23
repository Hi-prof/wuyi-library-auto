package com.wuyi.libraryauto.core.storage.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 任务 12.2：ActiveAccountDao 行为测试。
 *
 * 覆盖：upsert / findAll / findById / deleteAll / replaceAll 事务语义。
 */
@RunWith(RobolectricTestRunner::class)
class ActiveAccountDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ActiveAccountDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        dao = database.activeAccountDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert inserts new row and updates existing one`() = runTest {
        dao.upsert(entity(accountId = 1, displayName = "old"))
        dao.upsert(entity(accountId = 1, displayName = "new"))
        dao.upsert(entity(accountId = 2, displayName = "two"))

        val all = dao.findAll()
        assertThat(all).hasSize(2)
        val first = dao.findById(1)
        assertThat(first?.displayName).isEqualTo("new")
        val second = dao.findById(2)
        assertThat(second?.displayName).isEqualTo("two")
    }

    @Test
    fun `replaceAll wipes existing cache and writes new list atomically`() = runTest {
        dao.upsert(entity(accountId = 99, displayName = "stale"))

        dao.replaceAll(
            listOf(
                entity(accountId = 10, displayName = "a"),
                entity(accountId = 11, displayName = "b"),
            ),
        )

        val cached = dao.findAll()
        assertThat(cached.map { it.accountId }).containsExactly(10L, 11L)
        assertThat(cached.none { it.accountId == 99L }).isTrue()
    }

    @Test
    fun `replaceAll with empty list clears cache`() = runTest {
        dao.upsert(entity(accountId = 1))
        dao.upsert(entity(accountId = 2))

        dao.replaceAll(emptyList())

        assertThat(dao.findAll()).isEmpty()
    }

    @Test
    fun `deleteAll clears entire cache`() = runTest {
        dao.upsert(entity(accountId = 1))
        dao.upsert(entity(accountId = 2))

        dao.deleteAll()

        assertThat(dao.findAll()).isEmpty()
    }

    @Test
    fun `observeAll emits latest snapshot`() = runTest {
        dao.upsert(entity(accountId = 5, studentId = "s5"))

        val emitted = dao.observeAll().first()

        assertThat(emitted.map { it.studentId }).containsExactly("s5")
    }

    private fun entity(
        accountId: Long,
        studentId: String = "s$accountId",
        displayName: String = "user-$accountId",
    ): ActiveAccountEntity =
        ActiveAccountEntity(
            accountId = accountId,
            studentId = studentId,
            displayName = displayName,
            poolStatus = "active",
            updatedAt = "2026-04-26T08:25:11Z",
            syncedAtEpochSeconds = 1_700_000_000L,
        )
}
