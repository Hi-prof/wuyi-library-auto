package com.wuyi.libraryauto.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.EventSeat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wuyi.libraryauto.ui.screen.account.AccountManagementScreen
import com.wuyi.libraryauto.ui.screen.home.TodayOverviewScreen
import com.wuyi.libraryauto.ui.screen.login.LoginScreen
import com.wuyi.libraryauto.ui.screen.seat.SeatDisplayScreen
import com.wuyi.libraryauto.ui.screen.seat.SeatLookupScreen
import com.wuyi.libraryauto.ui.screen.session.SessionBootstrapHost
import com.wuyi.libraryauto.ui.screen.settings.AutomationGuideScreen
import com.wuyi.libraryauto.ui.screen.settings.BuildInfoScreen
import com.wuyi.libraryauto.ui.screen.settings.DiagnosticsScreen
import com.wuyi.libraryauto.ui.screen.settings.PermissionsScreen
import com.wuyi.libraryauto.ui.screen.settings.RuntimeGuideScreen
import com.wuyi.libraryauto.ui.screen.settings.ServerSyncScreen
import com.wuyi.libraryauto.ui.screen.settings.SettingsAutomationGuideRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsBuildInfoRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsDiagnosticsRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsHomeRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsHomeScreen
import com.wuyi.libraryauto.ui.screen.settings.SettingsPermissionsRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsRuntimeGuideRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsServerSyncRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsSignInMonitoringRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsWatchdogStatusRoute
import com.wuyi.libraryauto.ui.screen.settings.SignInMonitoringScreen
import com.wuyi.libraryauto.ui.screen.settings.WatchdogStatusScreen
import com.wuyi.libraryauto.ui.screen.settings.settingsRoutes
import com.wuyi.libraryauto.ui.screen.task.TaskListScreen

object AppRoutes {
    const val Home = "home"
    const val Accounts = "accounts"
    const val Login = "login"
    const val SeatDisplay = "seatDisplay"
    const val SeatLookup = "seatLookup"
    const val Settings = SettingsHomeRoute
    const val SettingsBuildInfo = SettingsBuildInfoRoute
    const val SettingsPermissions = SettingsPermissionsRoute
    const val SettingsRuntimeGuide = SettingsRuntimeGuideRoute
    const val SettingsAutomationGuide = SettingsAutomationGuideRoute
    const val SettingsSignInMonitoring = SettingsSignInMonitoringRoute
    const val SettingsWatchdogStatus = SettingsWatchdogStatusRoute
    const val SettingsDiagnostics = SettingsDiagnosticsRoute
    const val SettingsServerSync = SettingsServerSyncRoute
    const val Tasks = "tasks"
    const val TasksPattern = "tasks?studentId={studentId}"
    const val StudentIdArg = "studentId"

    fun tasks(studentId: String? = null): String =
        studentId?.takeIf(String::isNotBlank)?.let { "$Tasks?studentId=$it" } ?: Tasks
}

internal data class TopLevelDestination(
    val route: String,
    val title: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val matchRoutes: Set<String> = setOf(route),
)

internal val topLevelDestinations =
    listOf(
        TopLevelDestination(
            route = AppRoutes.Home,
            title = "首页",
            label = "首页",
            icon = Icons.Outlined.Home,
            selectedIcon = Icons.Filled.Home,
            matchRoutes = setOf(AppRoutes.Home, AppRoutes.SeatDisplay),
        ),
        TopLevelDestination(
            route = AppRoutes.Accounts,
            title = "账号列表",
            label = "账号",
            icon = Icons.Outlined.ManageAccounts,
            selectedIcon = Icons.Filled.ManageAccounts,
        ),
        TopLevelDestination(
            route = AppRoutes.SeatLookup,
            title = "手动预约",
            label = "预约",
            icon = Icons.Outlined.EventSeat,
            selectedIcon = Icons.Filled.EventSeat,
        ),
        TopLevelDestination(
            route = AppRoutes.Tasks,
            title = "自动任务",
            label = "任务",
            icon = Icons.Outlined.TaskAlt,
            selectedIcon = Icons.Filled.TaskAlt,
            matchRoutes = setOf(AppRoutes.Tasks, AppRoutes.TasksPattern),
        ),
        TopLevelDestination(
            route = AppRoutes.Settings,
            title = "设置",
            label = "设置",
            icon = Icons.Outlined.Settings,
            selectedIcon = Icons.Filled.Settings,
            matchRoutes = settingsRoutes,
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavGraph(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val appDependencies = remember(appContext) { AppDependencies(appContext) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val currentTopLevelDestination = resolveTopLevelDestination(currentDestination)
    val shellPresentation =
        buildAppShellPresentation(
            currentRoute = currentRoute,
            currentTopLevelDestination = currentTopLevelDestination,
        )

    fun navigateToTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(AppRoutes.Home) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun navigateBackToAccountsFromLogin() {
        navController.navigate(AppRoutes.Accounts) {
            popUpTo(AppRoutes.Login) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun navigateToSettingsHome() {
        navController.navigate(AppRoutes.Settings) {
            popUpTo(AppRoutes.Settings) { inclusive = false }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            shellPresentation.topBar?.let { topBar ->
                TopAppBar(
                    title = {
                        Text(
                            text = topBar.title,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        when (topBar.backTargetRoute) {
                            AppRoutes.Home ->
                                IconButton(onClick = { navigateToTopLevel(AppRoutes.Home) }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = topBar.backContentDescription,
                                    )
                                }
                            AppRoutes.Settings ->
                                IconButton(onClick = ::navigateToSettingsHome) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = topBar.backContentDescription,
                                    )
                                }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
        },
        bottomBar = {
            if (shellPresentation.bottomBarVisible) {
                AppBottomBar(
                    currentDestination = currentDestination,
                    onNavigate = ::navigateToTopLevel,
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = AppRoutes.Home,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(AppRoutes.Home) {
                    TodayOverviewScreen(
                        repository = appDependencies.todayOverviewRepository,
                        seatDisplayRepository = appDependencies.seatDisplayRepository,
                        onOpenAccountManager = { navigateToTopLevel(AppRoutes.Accounts) },
                        onOpenAddAccount = { navController.navigate(AppRoutes.Login) },
                        onOpenManualReservation = { navigateToTopLevel(AppRoutes.SeatLookup) },
                        onOpenSeatDisplay = { navController.navigate(AppRoutes.SeatDisplay) },
                        onOpenTasks = { navigateToTopLevel(AppRoutes.Tasks) },
                        onOpenSettings = { navigateToTopLevel(AppRoutes.Settings) },
                    )
                }
                composable(AppRoutes.Accounts) {
                    AccountManagementScreen(
                        accountRepository = appDependencies.savedAccountRepository,
                        loginGateway = appDependencies.loginGateway,
                        sessionRepository = appDependencies.sessionRepository,
                        accountStatusRepository = appDependencies.accountStatusRepository,
                        accountSeatActionExecutor = appDependencies.accountSeatActionRepository,
                        batchCheckInRunner = appDependencies.batchCheckInRunner,
                        batchProgressWriter = appDependencies.batchProgressWriter,
                        onOpenAddAccount = { navController.navigate(AppRoutes.Login) },
                        onOpenTasksForAccount = { studentId ->
                            navigateToTopLevel(AppRoutes.tasks(studentId))
                        },
                    )
                }
                composable(AppRoutes.Login) {
                    LoginScreen(
                        onLoginSuccess = ::navigateBackToAccountsFromLogin,
                        onOpenPermissionHelp = { navigateToTopLevel(AppRoutes.SettingsPermissions) },
                        loginGateway = appDependencies.loginGateway,
                        title = "添加账号",
                        description = "添加账号后回到账号列表，方便继续查看状态、设置当前账号或打开自动任务。",
                        hint = "密码可以留空，默认会使用学号。认证成功后会保存凭据和会话。",
                        primaryButtonLabel = "保存账号并返回账号列表",
                        secondaryButtonLabel = "查看权限设置",
                    )
                }
                composable(AppRoutes.SeatLookup) {
                    SeatLookupScreen(
                        accountRepository = appDependencies.savedAccountRepository,
                        seatLookupRepository = appDependencies.seatLookupRepository,
                        manualReservationRepository = appDependencies.manualReservationRepository,
                        sessionRepository = appDependencies.sessionRepository,
                        onOpenAccounts = { navigateToTopLevel(AppRoutes.Accounts) },
                    )
                }
                composable(AppRoutes.SeatDisplay) {
                    SeatDisplayScreen(
                        repository = appDependencies.seatDisplayRepository,
                        onOpenAccounts = { navigateToTopLevel(AppRoutes.Accounts) },
                    )
                }
                composable(
                    route = AppRoutes.TasksPattern,
                    arguments =
                        listOf(
                            navArgument(AppRoutes.StudentIdArg) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = ""
                            },
                        ),
                ) { entry ->
                    TaskListScreen(
                        accountRepository = appDependencies.savedAccountRepository,
                        automationPlanRepository = appDependencies.automationPlanRepository,
                        seatLookupRepository = appDependencies.seatLookupRepository,
                        sessionRepository = appDependencies.sessionRepository,
                        historyReader = appDependencies.accountReservationHistoryReader,
                        diagnosticsLogRepository = appDependencies.diagnosticsLogRepository,
                        accountSeatActionExecutor = appDependencies.accountSeatActionRepository,
                        studentIdFilter = entry.arguments?.getString(AppRoutes.StudentIdArg).orEmpty(),
                        onOpenAccounts = { navigateToTopLevel(AppRoutes.Accounts) },
                        onClearStudentFilter = { navigateToTopLevel(AppRoutes.Tasks) },
                    )
                }
                composable(AppRoutes.Settings) {
                    SettingsHomeScreen(
                        onOpenDestination = { route -> navController.navigate(route) },
                    )
                }
                composable(AppRoutes.SettingsBuildInfo) {
                    BuildInfoScreen()
                }
                composable(AppRoutes.SettingsPermissions) {
                    PermissionsScreen()
                }
                composable(AppRoutes.SettingsRuntimeGuide) {
                    RuntimeGuideScreen()
                }
                composable(AppRoutes.SettingsAutomationGuide) {
                    AutomationGuideScreen()
                }
                composable(AppRoutes.SettingsSignInMonitoring) {
                    SignInMonitoringScreen(
                        source = appDependencies.signInMonitoringDataSource,
                    )
                }
                composable(AppRoutes.SettingsWatchdogStatus) {
                    WatchdogStatusScreen(
                        heartbeatStore = appDependencies.watchdogHeartbeatStore,
                        stateStore = appDependencies.watchdogStateStore,
                    )
                }
                composable(AppRoutes.SettingsDiagnostics) {
                    DiagnosticsScreen(
                        diagnosticsLogRepository = appDependencies.diagnosticsLogRepository,
                    )
                }
                composable(AppRoutes.SettingsServerSync) {
                    ServerSyncScreen(dependencies = appDependencies)
                }
            }

            SessionBootstrapHost(sessionRepository = appDependencies.sessionRepository)
        }
    }
}

@Composable
private fun AppBottomBar(
    currentDestination: NavDestination?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        topLevelDestinations.forEach { destination ->
            val selected =
                currentDestination
                    ?.hierarchy
                    ?.any { navDestination ->
                        destination.matchRoutes.contains(navDestination.route)
                    } == true

            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = destination.title,
                    )
                },
                label = { Text(destination.label) },
                alwaysShowLabel = true,
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
    }
}

private fun resolveTopLevelDestination(currentDestination: NavDestination?): TopLevelDestination? =
    topLevelDestinations.firstOrNull { destination ->
        currentDestination
            ?.hierarchy
            ?.any { navDestination -> destination.matchRoutes.contains(navDestination.route) } == true
    }
