import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.screen.settings.SettingsAutomationGuideRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsBuildInfoRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsDestination
import com.wuyi.libraryauto.ui.screen.settings.SettingsDiagnosticsRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsHomeRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsPermissionsRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsRuntimeGuideRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsServerSyncRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsSignInMonitoringRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsWatchdogStatusRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsGroup
import com.wuyi.libraryauto.ui.screen.settings.findSettingsDestination
import com.wuyi.libraryauto.ui.screen.settings.settingsDestinations
import com.wuyi.libraryauto.ui.screen.settings.settingsRoutes
import org.junit.Test

class SettingsMenuModelsTest {
    private val removedNetworkRoutes =
        listOf(
            "settings/wifi-reconnect",
            "settings/campus-network",
            "settings/network-monitoring",
        )

    private val retainedRoutes =
        listOf(
            SettingsPermissionsRoute,
            SettingsRuntimeGuideRoute,
            SettingsAutomationGuideRoute,
            SettingsWatchdogStatusRoute,
            SettingsServerSyncRoute,
            SettingsSignInMonitoringRoute,
            SettingsDiagnosticsRoute,
            SettingsBuildInfoRoute,
        )

    @Test
    fun `settings menu exposes retained routes and removes campus recovery routes`() {
        val routesInOrder = settingsDestinations.map(SettingsDestination::route)

        assertThat(routesInOrder).containsAtLeastElementsIn(retainedRoutes)
        assertThat(routesInOrder).containsNoneIn(removedNetworkRoutes)
    }

    @Test
    fun `settings routes contain retained destinations only`() {
        assertThat(settingsRoutes).contains(SettingsHomeRoute)
        assertThat(settingsRoutes).containsAtLeastElementsIn(retainedRoutes)
        assertThat(settingsRoutes).containsNoneIn(removedNetworkRoutes)
        assertThat(retainedRoutes.map(::findSettingsDestination)).doesNotContain(null)
    }

    @Test
    fun `settings groups use post removal labels`() {
        assertThat(SettingsGroup.values().map { it.title })
            .containsExactly("常用", "监控", "同步", "诊断")
            .inOrder()
    }
}
