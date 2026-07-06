package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.ui.graphics.vector.ImageVector

const val SettingsHomeRoute = "settings/home"
const val SettingsBuildInfoRoute = "settings/build-info"
const val SettingsPermissionsRoute = "settings/permissions"
const val SettingsRuntimeGuideRoute = "settings/runtime-guide"
const val SettingsAutomationGuideRoute = "settings/automation-guide"
const val SettingsSignInMonitoringRoute = "settings/sign-in-monitoring"
const val SettingsWatchdogStatusRoute = "settings/watchdog-status"
const val SettingsDiagnosticsRoute = "settings/diagnostics"
const val SettingsServerSyncRoute = "settings/server-sync"

enum class SettingsGroup(val title: String) {
    Runtime("常用"),
    Network("监控"),
    Sync("同步"),
    Diagnostics("诊断"),
}

data class SettingsDestination(
    val route: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val group: SettingsGroup,
)

val settingsDestinations =
    listOf(
        // 运行 / 任务
        SettingsDestination(
            route = SettingsPermissionsRoute,
            title = "权限",
            summary = "检查权限、通知、无障碍和电池优化状态",
            icon = Icons.Outlined.Lock,
            group = SettingsGroup.Runtime,
        ),
        SettingsDestination(
            route = SettingsRuntimeGuideRoute,
            title = "运行说明",
            summary = "查看后台存活、常驻通知和电池优化说明",
            icon = Icons.Outlined.PowerSettingsNew,
            group = SettingsGroup.Runtime,
        ),
        SettingsDestination(
            route = SettingsAutomationGuideRoute,
            title = "自动预约说明",
            summary = "持续预约、单次模式和目标座位说明",
            icon = Icons.Outlined.AutoAwesome,
            group = SettingsGroup.Runtime,
        ),
        SettingsDestination(
            route = SettingsWatchdogStatusRoute,
            title = "看门狗状态",
            summary = "周期巡检心跳、看门狗失败计数与关键任务排程",
            icon = Icons.Outlined.WatchLater,
            group = SettingsGroup.Runtime,
        ),
        // 网络
        SettingsDestination(
            route = SettingsSignInMonitoringRoute,
            title = "签到监控",
            summary = "最近的签到与蓝牙扫描审计、24 小时失败聚合",
            icon = Icons.Outlined.MonitorHeart,
            group = SettingsGroup.Network,
        ),
        // 服务端同步
        SettingsDestination(
            route = SettingsServerSyncRoute,
            title = "服务端同步",
            summary = "从服务端拉取活跃池快照，按账号勾选覆盖本地受管字段",
            icon = Icons.Outlined.CloudSync,
            group = SettingsGroup.Sync,
        ),
        // 诊断 / 关于
        SettingsDestination(
            route = SettingsDiagnosticsRoute,
            title = "诊断日志",
            summary = "复制、刷新和清空诊断日志",
            icon = Icons.Outlined.BugReport,
            group = SettingsGroup.Diagnostics,
        ),
        SettingsDestination(
            route = SettingsBuildInfoRoute,
            title = "构建信息",
            summary = "查看当前安装包的版本号和构建标记",
            icon = Icons.AutoMirrored.Outlined.Article,
            group = SettingsGroup.Diagnostics,
        ),
    )

val settingsRoutes: Set<String> =
    buildSet {
        add(SettingsHomeRoute)
        addAll(settingsDestinations.map(SettingsDestination::route))
    }

fun findSettingsDestination(route: String?): SettingsDestination? =
    settingsDestinations.firstOrNull { destination -> destination.route == route }
