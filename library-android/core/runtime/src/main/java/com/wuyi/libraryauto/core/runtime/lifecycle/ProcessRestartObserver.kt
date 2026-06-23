package com.wuyi.libraryauto.core.runtime.lifecycle

import android.content.Context
import android.content.SharedPreferences
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource

class ProcessRestartObserver(
    private val preferences: SharedPreferences,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun observe(): TriggerSource? {
        val now = nowMillis()
        val lastKnownSessionMillis = preferences.getLong(KEY_LAST_KNOWN_SESSION_MILLIS, 0L)
        preferences.edit().putLong(KEY_LAST_KNOWN_SESSION_MILLIS, now).apply()
        return if (lastKnownSessionMillis > 0L &&
            now - lastKnownSessionMillis > NEW_SESSION_THRESHOLD_MILLIS
        ) {
            TriggerSource.ProcessRestart
        } else {
            null
        }
    }

    companion object {
        fun create(context: Context): ProcessRestartObserver =
            ProcessRestartObserver(
                preferences =
                    context.applicationContext.getSharedPreferences(
                        PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    ),
            )

        internal const val KEY_LAST_KNOWN_SESSION_MILLIS = "last_known_session_millis"
        private const val PREFERENCES_NAME = "process-restart-observer"
        private const val NEW_SESSION_THRESHOLD_MILLIS = 30_000L
    }
}
