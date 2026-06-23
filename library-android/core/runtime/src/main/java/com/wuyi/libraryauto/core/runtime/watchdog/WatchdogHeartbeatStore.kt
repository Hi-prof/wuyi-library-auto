package com.wuyi.libraryauto.core.runtime.watchdog

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInHeartbeatWriter

interface WatchdogHeartbeatWriter {
    fun markWatchdogHeartbeat(epochSeconds: Long)
}

class WatchdogHeartbeatStore(
    private val preferences: Lazy<SharedPreferences>,
) : PeriodicCheckInHeartbeatWriter,
    WatchdogHeartbeatWriter {

    constructor(context: Context) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            createPreferences(context.applicationContext)
        },
    )

    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    override fun markPeriodicCheckInHeartbeat(epochSeconds: Long) {
        preferences.value.edit()
            .putLong(KEY_PERIODIC_CHECK_IN_HEARTBEAT, epochSeconds)
            .apply()
    }

    fun readPeriodicCheckInHeartbeat(): Long =
        preferences.value.getLong(KEY_PERIODIC_CHECK_IN_HEARTBEAT, 0L)

    override fun markWatchdogHeartbeat(epochSeconds: Long) {
        preferences.value.edit()
            .putLong(KEY_WATCHDOG_HEARTBEAT, epochSeconds)
            .apply()
    }

    fun readWatchdogHeartbeat(): Long =
        preferences.value.getLong(KEY_WATCHDOG_HEARTBEAT, 0L)

    companion object {
        private const val PREFERENCES_NAME = "watchdog_heartbeat"
        private const val KEY_PERIODIC_CHECK_IN_HEARTBEAT = "periodic_check_in_heartbeat_epoch_seconds"
        private const val KEY_WATCHDOG_HEARTBEAT = "watchdog_heartbeat_epoch_seconds"

        private fun createPreferences(context: Context): SharedPreferences {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFERENCES_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
