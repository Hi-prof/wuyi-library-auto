package com.wuyi.libraryauto.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.screen.settings.SettingsAutomationGuideRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsBuildInfoRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsDiagnosticsRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsHomeRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsPermissionsRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsRuntimeGuideRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsServerSyncRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsSignInMonitoringRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsWatchdogStatusRoute
import org.junit.Test

class AppNavGraphModelTest {
    @Test
    fun `top level destinations expose mainstream bottom navigation tabs`() {
        assertThat(topLevelDestinations.map { it.route })
            .containsExactly(
                AppRoutes.Home,
                AppRoutes.Accounts,
                AppRoutes.SeatLookup,
                AppRoutes.Tasks,
                AppRoutes.Settings,
            )
            .inOrder()

        assertThat(topLevelDestinations.map { it.label })
            .containsExactly("首页", "账号", "预约", "任务", "设置")
            .inOrder()
    }

    @Test
    fun `settings tab owns all settings detail routes`() {
        val settingsDestination = topLevelDestinations.single { it.route == AppRoutes.Settings }

        assertThat(settingsDestination.matchRoutes)
            .containsAtLeast(
                SettingsHomeRoute,
                SettingsPermissionsRoute,
                SettingsRuntimeGuideRoute,
                SettingsAutomationGuideRoute,
                SettingsSignInMonitoringRoute,
                SettingsWatchdogStatusRoute,
                SettingsServerSyncRoute,
                SettingsDiagnosticsRoute,
                SettingsBuildInfoRoute,
            )
    }

    @Test
    fun `home tab owns the seat status secondary route`() {
        val homeDestination = topLevelDestinations.single { it.route == AppRoutes.Home }

        assertThat(homeDestination.matchRoutes).contains(AppRoutes.SeatDisplay)
    }

    @Test
    fun `shell presentation handles owned top bars and secondary back targets`() {
        val home =
            buildAppShellPresentation(
                currentRoute = AppRoutes.Home,
                currentTopLevelDestination = topLevelDestinations.single { it.route == AppRoutes.Home },
            )
        val settingsDetail =
            buildAppShellPresentation(
                currentRoute = AppRoutes.SettingsPermissions,
                currentTopLevelDestination = topLevelDestinations.single { it.route == AppRoutes.Settings },
            )
        val seatStatus =
            buildAppShellPresentation(
                currentRoute = AppRoutes.SeatDisplay,
                currentTopLevelDestination = topLevelDestinations.single { it.route == AppRoutes.Home },
            )
        val login =
            buildAppShellPresentation(
                currentRoute = AppRoutes.Login,
                currentTopLevelDestination = null,
            )

        assertThat(home.shellVisible).isTrue()
        assertThat(home.bottomBarVisible).isTrue()
        assertThat(home.topBar).isNull()
        assertThat(settingsDetail.topBar?.title).isEqualTo("权限")
        assertThat(settingsDetail.topBar?.backTargetRoute).isEqualTo(AppRoutes.Settings)
        assertThat(settingsDetail.topBar?.backContentDescription).isEqualTo("返回设置")
        assertThat(seatStatus.topBar?.title).isEqualTo("座位状态")
        assertThat(seatStatus.topBar?.backTargetRoute).isEqualTo(AppRoutes.Home)
        assertThat(seatStatus.topBar?.backContentDescription).isEqualTo("返回首页")
        assertThat(login.shellVisible).isFalse()
        assertThat(login.bottomBarVisible).isFalse()
    }
}
