package com.wuyi.libraryauto.core.storage.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CampusNetworkCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var store: CampusNetworkCredentialStore
    private val preferencesName = "campus-network-credential-store-test"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        store = CampusNetworkCredentialStore(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
        )
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `save then read returns trimmed username and original password`() {
        store.save(username = "  20240001  ", password = " pass word ")

        assertThat(store.read())
            .isEqualTo(CampusCredential(username = "20240001", password = " pass word "))
    }

    @Test
    fun `clear removes saved campus credential`() {
        store.save(username = "20240001", password = "secret")

        store.clear()

        assertThat(store.read()).isNull()
    }

    @Test
    fun `read returns null when either field is missing or blank`() {
        assertThat(store.read()).isNull()

        assertThrows(IllegalArgumentException::class.java) {
            store.save(username = "", password = "secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(username = "20240001", password = " ")
        }
    }

    @Test
    fun `campus credential keys do not overwrite saved account keys`() {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val savedAccountStore = SavedAccountStore(preferences)
        val campusStore = CampusNetworkCredentialStore(preferences)

        savedAccountStore.save(studentId = "20230001", password = "saved-secret")
        campusStore.save(username = "campus-user", password = "campus-secret")

        assertThat(savedAccountStore.readAll()).containsExactly(
            SavedAccountStore.SavedAccount(studentId = "20230001", password = "saved-secret"),
        )
        assertThat(campusStore.read())
            .isEqualTo(CampusCredential(username = "campus-user", password = "campus-secret"))
        assertThat(preferences.all.keys).containsAtLeast(
            "saved_accounts",
            "campus_username",
            "campus_password",
        )
    }
}
