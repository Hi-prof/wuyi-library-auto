package com.wuyi.libraryauto.ui.navigation

import com.wuyi.libraryauto.ui.screen.settings.findSettingsDestination

internal data class AppShellPresentation(
    val shellVisible: Boolean,
    val bottomBarVisible: Boolean,
    val topBar: AppShellTopBarPresentation?,
)

internal data class AppShellTopBarPresentation(
    val title: String,
    val backTargetRoute: String? = null,
    val backContentDescription: String? = null,
)

internal fun buildAppShellPresentation(
    currentRoute: String?,
    currentTopLevelDestination: TopLevelDestination?,
): AppShellPresentation {
    val shellVisible = currentTopLevelDestination != null
    if (!shellVisible || currentTopLevelDestination == null) {
        return AppShellPresentation(
            shellVisible = false,
            bottomBarVisible = false,
            topBar = null,
        )
    }

    val topBar =
        when {
            currentRoute == AppRoutes.Home || currentRoute == AppRoutes.Accounts -> null
            currentRoute == AppRoutes.SeatDisplay ->
                AppShellTopBarPresentation(
                    title = "座位状态",
                    backTargetRoute = AppRoutes.Home,
                    backContentDescription = "返回首页",
                )
            currentRoute != AppRoutes.Settings && findSettingsDestination(currentRoute) != null ->
                AppShellTopBarPresentation(
                    title = findSettingsDestination(currentRoute)?.title ?: currentTopLevelDestination.title,
                    backTargetRoute = AppRoutes.Settings,
                    backContentDescription = "返回设置",
                )
            else ->
                AppShellTopBarPresentation(title = currentTopLevelDestination.title)
        }

    return AppShellPresentation(
        shellVisible = true,
        bottomBarVisible = true,
        topBar = topBar,
    )
}
