package com.wuyi.libraryauto.core.storage.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedAccountStoreTest {

    private lateinit var context: Context
    private lateinit var store: SavedAccountStore
    private val preferencesName = "saved-account-store-test"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        store = SavedAccountStore(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `save then readAll returns saved accounts with latest first`() {
        store.save(studentId = "20230001", password = "alpha")
        store.save(studentId = "20230002", password = "beta")

        assertThat(store.readAll()).containsExactly(
            SavedAccountStore.SavedAccount(studentId = "20230002", password = "beta"),
            SavedAccountStore.SavedAccount(studentId = "20230001", password = "alpha"),
        ).inOrder()
    }

    @Test
    fun `save replaces duplicate studentId instead of duplicating it`() {
        store.save(studentId = "20230001", password = "alpha")

        store.save(studentId = "20230001", password = "updated")

        assertThat(store.readAll()).containsExactly(
            SavedAccountStore.SavedAccount(studentId = "20230001", password = "updated"),
        )
    }

    @Test
    fun `readAll keeps legacy accounts and defaults preferred seat fields to blank`() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(
                "saved_accounts",
                """[{"student_id":"20230001","password":"alpha"}]""",
            )
            .commit()

        assertThat(store.readAll()).containsExactly(
            SavedAccountStore.SavedAccount(
                studentId = "20230001",
                password = "alpha",
                preferredRoomName = "",
                preferredSeatNumber = "",
            ),
        )
    }

    @Test
    fun `readAll returns empty list when encrypted preferences read fails`() {
        val crashingPreferences =
            object : SharedPreferences by context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE) {
                override fun getString(
                    key: String?,
                    defValue: String?,
                ): String? = throw IllegalStateException("boom")
            }

        val crashingStore = SavedAccountStore(crashingPreferences)

        assertThat(crashingStore.readAll()).isEmpty()
    }

    @Test
    fun `updatePreferredSeat writes preferred room and seat`() {
        store.save(studentId = "20230001", password = "alpha")

        store.updatePreferredSeat(
            studentId = "20230001",
            preferredRoomName = "自习室圆形二楼",
            preferredSeatNumber = "166",
        )

        assertThat(store.readAll()).containsExactly(
            SavedAccountStore.SavedAccount(
                studentId = "20230001",
                password = "alpha",
                preferredRoomName = "自习室圆形二楼",
                preferredSeatNumber = "166",
            ),
        )
    }

    @Test
    fun `remove deletes matching saved account`() {
        store.save(studentId = "20230001", password = "alpha")
        store.save(studentId = "20230002", password = "beta")

        store.remove(studentId = "20230001")

        assertThat(store.readAll()).containsExactly(
            SavedAccountStore.SavedAccount(studentId = "20230002", password = "beta"),
        )
    }

    @Test
    fun `save rejects blank studentId`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            store.save(studentId = " ", password = "secret")
        }

        assertThat(exception).hasMessageThat().contains("studentId must not be blank")
    }

    @Test
    fun `save rejects blank password`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            store.save(studentId = "20230001", password = " ")
        }

        assertThat(exception).hasMessageThat().contains("password must not be blank")
    }
}
