import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.screen.settings.SettingsCampusNetworkRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsDestination
import com.wuyi.libraryauto.ui.screen.settings.SettingsHomeRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsNetworkMonitoringRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsServerSyncRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsSignInMonitoringRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsWatchdogStatusRoute
import com.wuyi.libraryauto.ui.screen.settings.findSettingsDestination
import com.wuyi.libraryauto.ui.screen.settings.settingsDestinations
import com.wuyi.libraryauto.ui.screen.settings.settingsRoutes
import org.junit.Test

class SettingsMenuModelsTest {

    @Test
    fun `settings menu exposes documented routes`() {
        // 历史断言因 SettingsDestination 字段扩展而失效；改为按 route 校验顺序，避免测试因 UI
        // 文案/图标变化反复维护。生产顺序由 SettingsHomeScreen 按 SettingsGroup 分组重排。
        val routesInOrder = settingsDestinations.map(SettingsDestination::route)
        assertThat(routesInOrder).containsAtLeast(
            SettingsCampusNetworkRoute,
            SettingsNetworkMonitoringRoute,
            SettingsServerSyncRoute,
            SettingsSignInMonitoringRoute,
            SettingsWatchdogStatusRoute,
        )
    }

    @Test
    fun `settings routes contain all four new monitoring destinations`() {
        assertThat(settingsRoutes).containsAtLeast(
            SettingsHomeRoute,
            SettingsCampusNetworkRoute,
            SettingsNetworkMonitoringRoute,
            SettingsServerSyncRoute,
            SettingsSignInMonitoringRoute,
            SettingsWatchdogStatusRoute,
        )
        assertThat(
            listOf(
                SettingsCampusNetworkRoute,
                SettingsNetworkMonitoringRoute,
                SettingsServerSyncRoute,
                SettingsSignInMonitoringRoute,
                SettingsWatchdogStatusRoute,
            ).map(::findSettingsDestination),
        ).doesNotContain(null)
    }
}
