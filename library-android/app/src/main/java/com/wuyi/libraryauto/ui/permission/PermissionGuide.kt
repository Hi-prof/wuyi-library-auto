package com.wuyi.libraryauto.ui.permission

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * UI 层呈现的"权限/能力检查"单项条目。
 *
 * 字段语义：
 * - [title]：中文标题，对应权限页/设置中心的列标题。
 * - [detail]：中文说明文案，长度 ≥16 字，需要让用户能直接看出影响。
 * - [ready]：当前能力是否已就绪，false 时由 UI 决定是否在视觉上强调。
 */
data class CapabilityStatus(
    val title: String,
    val detail: String,
    val ready: Boolean,
)

fun runtimePermissionsForSdk(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
    when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU ->
            listOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )

        sdkInt >= Build.VERSION_CODES.S ->
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )

        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun runtimePermissionLabelsForSdk(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
    runtimePermissionsForSdk(sdkInt).map(::permissionLabel)

fun missingRuntimePermissions(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
): List<String> =
    runtimePermissionsForSdk(sdkInt).filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

fun isAccessibilityServiceEnabled(
    context: Context,
    serviceClass: Class<out AccessibilityService>,
): Boolean {
    val enabledServices =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

    val expectedService = "${context.packageName}/${serviceClass.name}"
    return enabledServices
        .split(':')
        .any { it.equals(expectedService, ignoreCase = true) }
}

fun areNotificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

fun isBatteryOptimizationIgnored(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

/**
 * 是否已授予 SCHEDULE_EXACT_ALARM。Android 12（API 31）以下默认始终为 true。
 *
 * 这是 GuardWorker 兜底使用 [AlarmManager.setExactAndAllowWhileIdle] 的前置条件。未授权时
 * exact alarm 自动降级为 noop，签到主链仍由 WorkManager 承担，准时性会受 Doze 影响。
 */
fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return true
    }
    val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
    return alarmManager.canScheduleExactAlarms()
}

/**
 * 构建权限页展示用的能力检查列表。
 *
 * 用途：
 * - 为权限页/设置中心提供有序的中文检查项。
 * - 把"运行时权限/无障碍/通知/电池优化豁免/后台运行 · 自启动"五项统一封装。
 *
 * 关键入参：
 * - [missingRuntimePermissions]：尚未授予的运行时权限名列表（可空）。
 * - [accessibilityEnabled]：无障碍服务是否已启用。
 * - [notificationsEnabled]：通知开关是否已启用。
 * - [batteryOptimizationIgnored]：是否已豁免电池优化（`PowerManager.isIgnoringBatteryOptimizations`）。
 * - [exactAlarmAllowed]：是否允许调度精确闹钟（`AlarmManager.canScheduleExactAlarms`）。
 *   仅 Android 12+ 实际有意义；低版本由调用方传 true。
 * - [autoStartEnabledHint]：用户是否已自行确认厂商的"自启动 / 后台运行"已开启；
 *   仅影响 detail 文案语气，不影响 ready（该项"不强制要求"，永远视为 ready=true）。
 * - [sdkInt]：当前 SDK 版本，用于选择运行时权限集合。
 * - [manufacturer]：设备厂商字符串（默认 `Build.MANUFACTURER`），仅用于"后台运行 · 自启动"
 *   条目按厂商生成入口提示，便于单测覆盖各厂商分支。
 *
 * 返回值：按"运行时权限 → 无障碍服务 → 通知开关 → 电池优化豁免 → 后台运行 / 自启动"
 * 顺序排列的 [CapabilityStatus] 列表，前 4 项的 ready 反映真实系统状态，第 5 项 ready 恒为 true。
 */
fun buildCapabilityStatuses(
    missingRuntimePermissions: List<String>,
    accessibilityEnabled: Boolean,
    notificationsEnabled: Boolean,
    batteryOptimizationIgnored: Boolean,
    exactAlarmAllowed: Boolean = true,
    autoStartEnabledHint: Boolean = false,
    sdkInt: Int = Build.VERSION.SDK_INT,
    manufacturer: String = Build.MANUFACTURER.orEmpty(),
): List<CapabilityStatus> =
    listOf(
        CapabilityStatus(
            title = "运行时权限",
            detail = buildRuntimePermissionDetail(missingRuntimePermissions, sdkInt),
            ready = missingRuntimePermissions.isEmpty(),
        ),
        CapabilityStatus(
            title = "无障碍服务",
            detail = if (accessibilityEnabled) "已开启" else "未开启",
            ready = accessibilityEnabled,
        ),
        CapabilityStatus(
            title = "通知开关",
            detail = if (notificationsEnabled) "已开启" else "未开启",
            ready = notificationsEnabled,
        ),
        CapabilityStatus(
            title = "电池优化豁免",
            detail = buildBatteryOptimizationDetail(batteryOptimizationIgnored),
            ready = batteryOptimizationIgnored,
        ),
        CapabilityStatus(
            title = "精确闹钟",
            detail = buildExactAlarmDetail(exactAlarmAllowed, sdkInt),
            ready = exactAlarmAllowed,
        ),
        CapabilityStatus(
            title = "后台运行 / 自启动",
            detail = buildAutoStartDetail(autoStartEnabledHint, manufacturer),
            // R11.5：厂商自启动属"建议授权、不强制要求"，无法可靠探测，统一视为 ready。
            ready = true,
        ),
    )

fun applicationDetailsSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }

fun ignoreBatteryOptimizationSettingsIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

/**
 * 跳转到「闹钟与提醒」系统设置，让用户授予 SCHEDULE_EXACT_ALARM。仅 Android 12+ 有效。
 *
 * 调用方在拿到 Intent 后建议先 [Intent.resolveActivity] 校验，因部分定制 ROM 缺少该 Activity；
 * 兜底可改用 [applicationDetailsSettingsIntent] 让用户手工进入应用详情页。
 */
fun requestScheduleExactAlarmIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return null
    }
    return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
}

/**
 * 根据 [Build.MANUFACTURER] 返回对应厂商的"自启动 / 后台运行"管控入口提示。
 *
 * 覆盖：华为、荣耀、OPPO、vivo、小米/Redmi、魅族；其他厂商使用通用提示。
 * 返回的字符串为入口描述，不带"建议授权"前缀，前缀由 [buildAutoStartDetail] 统一拼接。
 */
internal fun manufacturerAutoStartHint(manufacturer: String): String {
    val normalized = manufacturer.trim().lowercase()
    return when {
        normalized.contains("huawei") ->
            "华为「设置 → 应用 → 应用启动管理」中关闭自动管理并允许自启动、关联启动、后台活动"
        normalized.contains("honor") ->
            "荣耀「设置 → 应用 → 应用启动管理」中关闭自动管理并允许自启动、关联启动、后台活动"
        normalized.contains("oppo") || normalized.contains("realme") ->
            "OPPO「设置 → 电池 → 应用耗电管理」中允许小鱼后台运行与自启动"
        normalized.contains("vivo") || normalized.contains("iqoo") ->
            "vivo「i 管家 → 应用管理 → 权限管理 → 自启动 / 后台高耗电」中开启允许"
        normalized.contains("xiaomi") || normalized.contains("redmi") ||
            normalized.contains("poco") ->
            "小米「设置 → 应用设置 → 授权管理 → 自启动管理」中开启自启动并允许后台弹窗"
        normalized.contains("meizu") ->
            "魅族「手机管家 → 权限管理 → 后台管理」中将小鱼设为允许后台运行与自启动"
        else ->
            "进入系统的应用管理或电池设置，找到本应用并开启自启动与后台运行权限"
    }
}

private fun buildBatteryOptimizationDetail(batteryOptimizationIgnored: Boolean): String =
    if (batteryOptimizationIgnored) {
        // R11.7：豁免后翻转为正向描述，长度 ≥16 字。
        "已豁免电池优化，后台签到守护可在锁屏与息屏期间持续运行"
    } else {
        // R11.2：未豁免文案必须包含关键短语"未豁免可能导致后台签到失败"，长度 ≥16 字。
        "未豁免可能导致后台签到失败，建议前往系统设置允许本应用忽略电池优化"
    }

private fun buildExactAlarmDetail(
    exactAlarmAllowed: Boolean,
    sdkInt: Int,
): String =
    when {
        sdkInt < Build.VERSION_CODES.S ->
            "当前系统无需授权，签到精确闹钟兜底已自动启用"
        exactAlarmAllowed ->
            "已允许精确闹钟，Doze 与息屏期间也能准时触发签到兜底"
        else ->
            "未授予精确闹钟权限，息屏过久可能导致签到延迟，请在「闹钟与提醒」中允许本应用"
    }

private fun buildAutoStartDetail(
    autoStartEnabledHint: Boolean,
    manufacturer: String,
): String {
    val entryHint = manufacturerAutoStartHint(manufacturer)
    val statusPrefix = if (autoStartEnabledHint) "已确认" else "建议授权"
    // 拼接后长度始终 ≥16 字（最短入口提示也大于 20 字）。
    return "$statusPrefix：$entryHint"
}

private fun buildRuntimePermissionDetail(
    missingRuntimePermissions: List<String>,
    sdkInt: Int,
): String {
    if (missingRuntimePermissions.isEmpty()) {
        val labels = runtimePermissionLabelsForSdk(sdkInt)
        return if (labels.isEmpty()) "当前系统无需额外授权" else "已全部授权"
    }
    return "还缺少 ${missingRuntimePermissions.map(::permissionLabel).joinToString("、")}"
}

private fun permissionLabel(permission: String): String =
    when (permission) {
        Manifest.permission.POST_NOTIFICATIONS -> "通知"
        Manifest.permission.BLUETOOTH_SCAN -> "蓝牙扫描"
        Manifest.permission.BLUETOOTH_CONNECT -> "蓝牙连接"
        Manifest.permission.ACCESS_FINE_LOCATION -> "位置信息"
        else -> permission.substringAfterLast('.')
    }
