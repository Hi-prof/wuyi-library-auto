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
class CredentialStoreTest {

    private lateinit var context: Context
    private lateinit var store: CredentialStore
    private val preferencesName = "credential-store-test"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        store = CredentialStore(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `save then read returns stored credentials`() {
        store.save(studentId = "2024001", password = "secret")

        assertThat(store.read())
            .isEqualTo(CredentialStore.Credentials(studentId = "2024001", password = "secret"))
    }

    @Test
    fun `clear removes stored credentials`() {
        store.save(studentId = "2024001", password = "secret")

        store.clear()

        assertThat(store.read()).isNull()
    }

    @Test
    fun `read returns null when encrypted preferences read fails`() {
        val crashingPreferences =
            object : SharedPreferences by context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE) {
                override fun getString(
                    key: String?,
                    defValue: String?,
                ): String? = throw IllegalStateException("boom")
            }

        val crashingStore = CredentialStore(crashingPreferences)

        assertThat(crashingStore.read()).isNull()
    }

    @Test
    fun `save rejects blank studentId`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                store.save(studentId = "   ", password = "secret")
            }

        assertThat(exception).hasMessageThat().contains("studentId must not be blank")
    }

    @Test
    fun `save rejects blank password`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                store.save(studentId = "2024001", password = " ")
            }

        assertThat(exception).hasMessageThat().contains("password must not be blank")
    }
}
