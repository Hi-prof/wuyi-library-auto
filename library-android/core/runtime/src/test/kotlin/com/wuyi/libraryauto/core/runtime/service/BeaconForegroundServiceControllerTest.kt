package com.wuyi.libraryauto.core.runtime.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BeaconForegroundServiceControllerTest {

    @Test
    fun `acquire starts once reuses active service and stops when last release runs`() = runTest {
        val observer = BeaconForegroundServiceStartObserver()
        val starter = FakeStarter(observer)
        val controller = newController(starter = starter, observer = observer)

        val first = controller.acquire()
        val second = controller.acquire()
        controller.release()
        controller.release()

        assertThat(first).isEqualTo(BeaconForegroundServiceController.Acquisition.Started)
        assertThat(second).isEqualTo(BeaconForegroundServiceController.Acquisition.Reused)
        assertThat(starter.startCount).isEqualTo(1)
        assertThat(starter.stopCount).isEqualTo(1)
    }

    @Test
    fun `separate controller instances share foreground service state`() = runTest {
        val observer = BeaconForegroundServiceStartObserver()
        val starter = FakeStarter(observer)
        val state = BeaconForegroundServiceState()
        val firstController = newController(starter = starter, observer = observer, state = state)
        val secondController = newController(starter = starter, observer = observer, state = state)

        val first = firstController.acquire()
        val second = secondController.acquire()
        firstController.release()
        secondController.release()

        assertThat(first).isEqualTo(BeaconForegroundServiceController.Acquisition.Started)
        assertThat(second).isEqualTo(BeaconForegroundServiceController.Acquisition.Reused)
        assertThat(starter.startCount).isEqualTo(1)
        assertThat(starter.stopCount).isEqualTo(1)
    }

    @Test
    fun `acquire returns fallback when permission checker reports missing permission`() = runTest {
        val observer = BeaconForegroundServiceStartObserver()
        val starter = FakeStarter(observer)
        val controller =
            newController(
                permissionChecker = ForegroundServicePermissionChecker {
                    Manifest.permission.POST_NOTIFICATIONS
                },
                starter = starter,
                observer = observer,
            )

        val result = controller.acquire()

        assertThat(result)
            .isEqualTo(
                BeaconForegroundServiceController.Acquisition.FailedFallback(
                    "Missing permission: ${Manifest.permission.POST_NOTIFICATIONS}",
                ),
            )
        assertThat(starter.startCount).isEqualTo(0)
        assertThat(starter.stopCount).isEqualTo(0)
    }

    @Test
    @Config(sdk = [34])
    fun `acquire returns fallback when foreground service start is blocked`() = runTest {
        val observer = BeaconForegroundServiceStartObserver()
        val starter =
            FakeStarter(
                observer = observer,
                startFailure = ForegroundServiceStartNotAllowedException("blocked"),
            )
        val controller = newController(starter = starter, observer = observer)

        val result = controller.acquire()

        assertThat(result)
            .isEqualTo(
                BeaconForegroundServiceController.Acquisition.FailedFallback(
                    "ForegroundServiceStartNotAllowedException",
                ),
            )
        assertThat(starter.startCount).isEqualTo(1)
        assertThat(starter.stopCount).isEqualTo(0)
    }

    private fun newController(
        permissionChecker: ForegroundServicePermissionChecker = ForegroundServicePermissionChecker { null },
        starter: BeaconForegroundServiceStarter,
        observer: BeaconForegroundServiceStartObserver,
        state: BeaconForegroundServiceState = BeaconForegroundServiceState(),
    ): BeaconForegroundServiceController =
        BeaconForegroundServiceController(
            context = ApplicationProvider.getApplicationContext(),
            permissionChecker = permissionChecker,
            serviceStarter = starter,
            startObserver = observer,
            state = state,
        )

    private class FakeStarter(
        private val observer: BeaconForegroundServiceStartObserver,
        private val startFailure: Throwable? = null,
    ) : BeaconForegroundServiceStarter {
        var startCount = 0
        var stopCount = 0

        override fun start(context: Context) {
            startCount += 1
            startFailure?.let { throw it }
            observer.notifyStarted()
        }

        override fun stop(context: Context) {
            stopCount += 1
        }
    }
}
