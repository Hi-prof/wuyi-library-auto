package com.wuyi.libraryauto.core.runtime.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProcessRestartObserverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
    }

    @Test
    fun `observe records first session without triggering recovery`() {
        val observer = ProcessRestartObserver(preferences(), nowMillis = { 1_000L })

        assertThat(observer.observe()).isNull()
        assertThat(
            preferences().getLong(ProcessRestartObserver.KEY_LAST_KNOWN_SESSION_MILLIS, 0L),
        ).isEqualTo(1_000L)
    }

    @Test
    fun `observe returns process restart when last session is older than thirty seconds`() {
        preferences()
            .edit()
            .putLong(ProcessRestartObserver.KEY_LAST_KNOWN_SESSION_MILLIS, 1_000L)
            .commit()
        val observer = ProcessRestartObserver(preferences(), nowMillis = { 32_001L })

        assertThat(observer.observe()).isEqualTo(TriggerSource.ProcessRestart)
    }

    @Test
    fun `observe does not trigger when last session is within thirty seconds`() {
        preferences()
            .edit()
            .putLong(ProcessRestartObserver.KEY_LAST_KNOWN_SESSION_MILLIS, 1_000L)
            .commit()
        val observer = ProcessRestartObserver(preferences(), nowMillis = { 31_000L })

        assertThat(observer.observe()).isNull()
    }

    private fun preferences() =
        context.getSharedPreferences("process-restart-observer-test", Context.MODE_PRIVATE)
}
