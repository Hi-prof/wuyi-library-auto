package com.wuyi.libraryauto.ui.permission

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionGuideTest {

    @Test
    fun `android 13 exposes runtime permission labels in request order`() {
        assertThat(runtimePermissionLabelsForSdk(33)).containsExactly(
            "通知",
            "蓝牙扫描",
            "蓝牙连接",
        ).inOrder()
    }

    @Test
    fun `android 13 requests notifications and bluetooth runtime permissions`() {
        assertThat(runtimePermissionsForSdk(33)).containsExactly(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).inOrder()
    }

    @Test
    fun `android 12 requests bluetooth runtime permissions without notifications`() {
        assertThat(runtimePermissionsForSdk(31)).containsExactly(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).inOrder()
    }

    @Test
    fun `android 11 falls back to location permission for bluetooth scanning`() {
        assertThat(runtimePermissionsForSdk(30)).containsExactly(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    @Test
    fun `stable runtime capabilities keep app specific status order`() {
        assertThat(
            buildCapabilityStatuses(
                missingRuntimePermissions = listOf(Manifest.permission.BLUETOOTH_SCAN),
                accessibilityEnabled = false,
                notificationsEnabled = true,
                batteryOptimizationIgnored = false,
                exactAlarmAllowed = false,
                sdkInt = 33,
                manufacturer = "Xiaomi",
            ),
        ).containsExactly(
            CapabilityStatus(
                title = "运行时权限",
                detail = "还缺少 蓝牙扫描",
                ready = false,
            ),
            CapabilityStatus(
                title = "无障碍服务",
                detail = "未开启",
                ready = false,
            ),
            CapabilityStatus(
                title = "通知开关",
                detail = "已开启",
                ready = true,
            ),
            CapabilityStatus(
                title = "电池优化豁免",
                detail = "未豁免可能导致后台签到失败，建议前往系统设置允许本应用忽略电池优化",
                ready = false,
            ),
            CapabilityStatus(
                title = "精确闹钟",
                detail = "未授予精确闹钟权限，息屏过久可能导致签到延迟，请在「闹钟与提醒」中允许本应用",
                ready = false,
            ),
            CapabilityStatus(
                title = "后台运行 / 自启动",
                detail =
                    "建议授权：小米「设置 → 应用设置 → 授权管理 → 自启动管理」中开启自启动并允许后台弹窗",
                ready = true,
            ),
        ).inOrder()
    }

    @Test
    fun `exact alarm capability flips to positive copy when granted`() {
        val statuses =
            buildCapabilityStatuses(
                missingRuntimePermissions = emptyList(),
                accessibilityEnabled = true,
                notificationsEnabled = true,
                batteryOptimizationIgnored = true,
                exactAlarmAllowed = true,
                sdkInt = 33,
                manufacturer = "Generic",
            )

        val exactAlarm = statuses.first { it.title == "精确闹钟" }
        assertThat(exactAlarm.ready).isTrue()
        assertThat(exactAlarm.detail).contains("已允许精确闹钟")
    }

    @Test
    fun `exact alarm capability is auto granted on Android 11`() {
        val statuses =
            buildCapabilityStatuses(
                missingRuntimePermissions = emptyList(),
                accessibilityEnabled = true,
                notificationsEnabled = true,
                batteryOptimizationIgnored = true,
                exactAlarmAllowed = true,
                sdkInt = 30,
                manufacturer = "Generic",
            )

        val exactAlarm = statuses.first { it.title == "精确闹钟" }
        assertThat(exactAlarm.ready).isTrue()
        assertThat(exactAlarm.detail).contains("无需授权")
    }

    @Test
    fun `battery optimization detail flips to positive copy after granted`() {
        val statuses =
            buildCapabilityStatuses(
                missingRuntimePermissions = emptyList(),
                accessibilityEnabled = true,
                notificationsEnabled = true,
                batteryOptimizationIgnored = true,
                sdkInt = 33,
                manufacturer = "Generic",
            )

        val battery = statuses.first { it.title == "电池优化豁免" }
        assertThat(battery.ready).isTrue()
        assertThat(battery.detail).isEqualTo(
            "已豁免电池优化，后台签到守护可在锁屏与息屏期间持续运行",
        )
    }

    @Test
    fun `battery optimization detail mentions risk hint when not ignored`() {
        val statuses =
            buildCapabilityStatuses(
                missingRuntimePermissions = emptyList(),
                accessibilityEnabled = true,
                notificationsEnabled = true,
                batteryOptimizationIgnored = false,
                sdkInt = 33,
            )

        val battery = statuses.first { it.title == "电池优化豁免" }
        assertThat(battery.detail).contains("未豁免可能导致后台签到失败")
        // R11.1：未豁免说明文案至少 16 个汉字。
        val chineseChars = battery.detail.count { ch -> ch in '\u4e00'..'\u9fff' }
        assertThat(chineseChars).isAtLeast(16)
    }

    @Test
    fun `auto start hint surfaces manufacturer specific entry for Huawei`() {
        val statuses =
            buildCapabilityStatuses(
                missingRuntimePermissions = emptyList(),
                accessibilityEnabled = true,
                notificationsEnabled = true,
                batteryOptimizationIgnored = true,
                sdkInt = 33,
                manufacturer = "HUAWEI",
            )

        val autoStart = statuses.first { it.title == "后台运行 / 自启动" }
        assertThat(autoStart.ready).isTrue()
        assertThat(autoStart.detail).startsWith("建议授权：")
        assertThat(autoStart.detail).contains("华为")
        assertThat(autoStart.detail).contains("应用启动管理")
    }

    @Test
    fun `auto start hint covers OPPO vivo Xiaomi Honor and Meizu manufacturers`() {
        listOf(
            "OPPO" to "OPPO",
            "vivo" to "vivo",
            "Redmi" to "小米",
            "HONOR" to "荣耀",
            "Meizu" to "魅族",
        ).forEach { (manufacturer, expectedKeyword) ->
            val hint = manufacturerAutoStartHint(manufacturer)
            assertThat(hint).contains(expectedKeyword)
        }
    }

    @Test
    fun `auto start hint falls back to generic guidance for unknown brands`() {
        val hint = manufacturerAutoStartHint("SomeUnknownBrand")
        assertThat(hint).contains("应用管理")
        assertThat(hint).contains("自启动")
    }

    @Test
    fun `auto start hint reflects user confirmed status when toggled on`() {
        val statuses =
            buildCapabilityStatuses(
                missingRuntimePermissions = emptyList(),
                accessibilityEnabled = true,
                notificationsEnabled = true,
                batteryOptimizationIgnored = true,
                autoStartEnabledHint = true,
                sdkInt = 33,
                manufacturer = "Xiaomi",
            )

        val autoStart = statuses.first { it.title == "后台运行 / 自启动" }
        assertThat(autoStart.detail).startsWith("已确认：")
        assertThat(autoStart.ready).isTrue()
    }
}
