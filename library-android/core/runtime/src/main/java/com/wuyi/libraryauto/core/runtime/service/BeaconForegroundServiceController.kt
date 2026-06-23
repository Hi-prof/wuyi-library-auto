package com.wuyi.libraryauto.core.runtime.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class BeaconForegroundServiceController(
    context: Context,
    private val permissionChecker: ForegroundServicePermissionChecker =
        AndroidForegroundServicePermissionChecker(context.applicationContext),
    private val serviceStarter: BeaconForegroundServiceStarter = AndroidBeaconForegroundServiceStarter,
    private val startObserver: BeaconForegroundServiceStartObserver = defaultStartObserver,
    private val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
    private val state: BeaconForegroundServiceState = defaultState,
) {
    private val appContext = context.applicationContext

    suspend fun acquire(): Acquisition =
        state.mutex.withLock {
            require(startTimeoutMillis > 0L) { "startTimeoutMillis must be positive" }
            if (state.referenceCount > 0) {
                state.referenceCount += 1
                return@withLock Acquisition.Reused
            }

            permissionChecker.firstMissingPermission()?.let { missingPermission ->
                return@withLock Acquisition.FailedFallback("Missing permission: $missingPermission")
            }

            val foregroundStarted = startObserver.register()
            val startFailure = runCatching {
                serviceStarter.start(appContext)
            }.exceptionOrNull()
            if (startFailure != null) {
                startObserver.unregister(foregroundStarted)
                return@withLock Acquisition.FailedFallback(startFailure.toFallbackReason())
            }

            val started =
                withTimeoutOrNull(startTimeoutMillis) {
                    foregroundStarted.await()
                    true
                } == true

            if (!started) {
                startObserver.unregister(foregroundStarted)
                serviceStarter.stop(appContext)
                return@withLock Acquisition.FailedFallback("startForeground timeout")
            }

            state.referenceCount = 1
            Acquisition.Started
        }

    suspend fun release() {
        state.mutex.withLock {
            if (state.referenceCount == 0) {
                return@withLock
            }

            state.referenceCount -= 1
            if (state.referenceCount == 0) {
                serviceStarter.stop(appContext)
            }
        }
    }

    sealed class Acquisition {
        data object Started : Acquisition()
        data object Reused : Acquisition()
        data class FailedFallback(val reason: String) : Acquisition()
    }

    companion object {
        const val DEFAULT_START_TIMEOUT_MILLIS = 2_000L

        private val defaultStartObserver = BeaconForegroundServiceStartObserver()
        private val defaultState = BeaconForegroundServiceState()

        internal fun notifyForegroundStarted() {
            defaultStartObserver.notifyStarted()
        }
    }
}

class BeaconForegroundServiceState {
    internal val mutex = Mutex()
    internal var referenceCount = 0
}

fun interface ForegroundServicePermissionChecker {
    fun firstMissingPermission(): String?
}

interface BeaconForegroundServiceStarter {
    fun start(context: Context)
    fun stop(context: Context)
}

class BeaconForegroundServiceStartObserver {
    private val lock = Any()
    private val waiters = mutableSetOf<CompletableDeferred<Unit>>()

    fun register(): CompletableDeferred<Unit> {
        val waiter = CompletableDeferred<Unit>()
        synchronized(lock) {
            waiters += waiter
        }
        waiter.invokeOnCompletion {
            unregister(waiter)
        }
        return waiter
    }

    fun notifyStarted() {
        val pending =
            synchronized(lock) {
                waiters.toList().also { waiters.clear() }
            }
        pending.forEach { waiter ->
            waiter.complete(Unit)
        }
    }

    fun unregister(waiter: CompletableDeferred<Unit>) {
        synchronized(lock) {
            waiters -= waiter
        }
    }
}

private class AndroidForegroundServicePermissionChecker(
    private val context: Context,
) : ForegroundServicePermissionChecker {
    override fun firstMissingPermission(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
        }

        return null
    }
}

private object AndroidBeaconForegroundServiceStarter : BeaconForegroundServiceStarter {
    override fun start(context: Context) {
        ContextCompat.startForegroundService(context, BeaconForegroundService.createIntent(context))
    }

    override fun stop(context: Context) {
        context.stopService(BeaconForegroundService.createIntent(context))
    }
}

private fun Throwable.toFallbackReason(): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        this is ForegroundServiceStartNotAllowedException
    ) {
        return "ForegroundServiceStartNotAllowedException"
    }
    return this::class.java.simpleName.ifBlank { "ForegroundServiceStartFailed" }
}
