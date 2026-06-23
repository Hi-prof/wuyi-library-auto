package com.wuyi.libraryauto.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.EventSeat
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.wuyi.libraryauto.ui.screen.login.LoginScreen
import com.wuyi.libraryauto.ui.screen.seat.SeatDisplayScreen
import com.wuyi.libraryauto.ui.screen.seat.SeatLookupScreen
import com.wuyi.libraryauto.ui.screen.session.SessionBootstrapHost
import com.wuyi.libraryauto.ui.screen.settings.AutomationGuideScreen
import com.wuyi.libraryauto.ui.screen.settings.BuildInfoScreen
import com.wuyi.libraryauto.ui.screen.settings.CampusNetworkScreen
import com.wuyi.libraryauto.ui.screen.settings.DiagnosticsScreen
import com.wuyi.libraryauto.ui.screen.settings.NetworkMonitoringScreen
import com.wuyi.libraryauto.ui.screen.settings.PermissionsScreen
import com.wuyi.libraryauto.ui.screen.settings.RuntimeGuideScreen
import com.wuyi.libraryauto.ui.screen.settings.ServerSyncScreen
import com.wuyi.libraryauto.ui.screen.settings.SettingsAutomationGuideRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsBuildInfoRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsCampusNetworkRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsDiagnosticsRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsHomeRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsHomeScreen
import com.wuyi.libraryauto.ui.screen.settings.SettingsNetworkMonitoringRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsPermissionsRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsRuntimeGuideRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsServerSyncRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsSignInMonitoringRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsWatchdogStatusRoute
import com.wuyi.libraryauto.ui.screen.settings.SettingsWifiReconnectRoute
import com.wuyi.libraryauto.ui.screen.settings.SignInMonitoringScreen
import com.wuyi.libraryauto.ui.screen.settings.WatchdogStatusScreen
import com.wuyi.libraryauto.ui.screen.settings.WifiReconnectSettingsScreen
import com.wuyi.libraryauto.ui.screen.settings.findSettingsDestination
import com.wuyi.libraryauto.ui.screen.settings.settingsRoutes
import com.wuyi.libraryauto.ui.screen.task.TaskListScreen

object AppRoutes {
    const val Accounts = "accounts"
    const val Login = "login"
    const val SeatDisplay = "seatDisplay"
    const val SeatLookup = "seatLookup"
    const val Settings = SettingsHomeRoute
    const val SettingsBuildInfo = SettingsBuildInfoRoute
    const val SettingsWifiReconnect = SettingsWifiReconnectRoute
    const val SettingsPermissions = SettingsPermissionsRoute
    const val SettingsRuntimeGuide = SettingsRuntimeGuideRoute
    const val SettingsAutomationGuide = SettingsAutomationGuideRoute
    const val SettingsCampusNetwork = SettingsCampusNetworkRoute
    const val SettingsNetworkMonitoring = SettingsNetworkMonitoringRoute
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

private data class TopLevelDestination(
    val route: String,
    val title: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val matchRoutes: Set<String> = setOf(route),
)

private val topLevelDestinations =
    listOf(
        TopLevelDestination(
            route = AppRoutes.Accounts,
            title = "账号",
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
            route = AppRoutes.SeatDisplay,
            title = "座位",
            label = "座位",
            icon = Icons.Outlined.Chair,
            selectedIcon = Icons.Filled.Chair,
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
    val currentSettingsDestination = findSettingsDestination(currentRoute)
    val shellVisible = currentTopLevelDestination != null
    // 账号列表的顶栏由屏幕内部承接（包含搜索 / 操作菜单 / 多选 ContextualBar），
    // 这里 shell 不再渲染默认 TopAppBar，避免重复。
    val showShellTopBar = shellVisible && currentRoute != AppRoutes.Accounts

    fun navigateToTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(AppRoutes.Accounts) { saveState = true }
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
            if (showShellTopBar) {
                currentTopLevelDestination?.let { destination ->
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = currentSettingsDestination?.title ?: destination.title,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        navigationIcon = {
                            if (currentSettingsDestination != null) {
                                IconButton(onClick = ::navigateToSettingsHome) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回设置",
                                    )
                                }
                            }
                        },
                        colors =
                            TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                }
            }
        },
        bottomBar = {
            if (shellVisible) {
                AppBottomBar(
                    currentDestination = currentDestination,
                    onNavigate = ::navigateToTopLevel,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = AppRoutes.Accounts,
                modifier = Modifier.fillMaxSize(),
            ) {
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
                        description = "添加账号后会回到账号列表，方便继续查看状态、设置当前账号或打开自动任务。",
                        hint = "密码可以留空，默认会使用学号。认证成功后会同时保存加密凭据和该账号的会话。",
                        primaryButtonLabel = "保存账号并返回账号列表",
                        secondaryButtonLabel = "查看设置里的权限模块",
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
                composable(AppRoutes.SettingsWifiReconnect) {
                    WifiReconnectSettingsScreen()
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
                composable(AppRoutes.SettingsCampusNetwork) {
                    CampusNetworkScreen(
                        credentialStore = appDependencies.campusNetworkCredentialStore,
                        metricsRepository = appDependencies.networkMonitorMetricsRepository,
                        executionLogRepository = appDependencies.executionLogRepository,
                        authenticator = appDependencies.campusPortalAuthenticator,
                        portalLoginPageUrlProvider = appDependencies.campusPortalLoginPageUrlProvider,
                    )
                }
                composable(AppRoutes.SettingsNetworkMonitoring) {
                    NetworkMonitoringScreen(
                        metricsRepository = appDependencies.networkMonitorMetricsRepository,
                        wifiReconnectStore = appDependencies.wifiReconnectStore,
                    )
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
            // 登录接口有时先返回只够 API 复用的 Cookie，这里统一补齐网页侧会话，避免刚登录的账号马上又被页面判成未登录。
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
