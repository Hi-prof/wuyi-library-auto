# 图书馆自动预约应用修复报告

## 问题诊断总结

根据手机日志 `手机日志.txt` 分析，应用存在以下主要问题：

### 1. WorkManager 初始化失败（严重）
**错误信息**：
```
WorkManager is not initialized properly. You have explicitly disabled WorkManagerInitializer 
in your manifest, have not manually called WorkManager#initialize at this point, and your 
Application does not implement Configuration.Provider.
```

**影响**：多个预约任务的守护 Worker 无法入队，导致自动签到功能失效。

**发生时间**：2026-06-23 17:11:00 - 17:11:15（多次）

---

### 2. 进程异常终止（严重）
**警告信息**：
```
上次进程没有正常收尾记录
previousStart=2026-05-26 22:07:31
lastHeartbeat=2026-05-26 22:07:31
note=可能是系统回收、用户划掉后台、厂商管家清理或进程崩溃
```

**影响**：应用长时间未运行（近1个月），所有自动功能失效。

---

### 3. 签到超时失败（高频）
**错误信息**：`FAILED_MANUAL_ACTION - 已超过自动签到时间窗，请手动处理`

**影响**：大量预约因应用未在签到时间窗口内运行而需要手动处理。

---

### 4. 蓝牙扫描失败（频繁）
**错误信息**：`蓝牙扫描未命中目标信标，请稍后再试`

**影响**：基于蓝牙的自动签到功能无法正常工作。

---

### 5. 校园网认证问题（中等）
**错误信息**：
- `校园网认证冷却中，请稍后再试`
- `后台 Wi-Fi 重连未开启`
- `CLEARTEXT communication to 10.10.244.11 not permitted by network security policy`

**影响**：无法自动完成校园网认证，可能影响网络连接。

---

## 修复措施

### ✅ 修复 1：手动初始化 WorkManager

**文件**：`app/src/main/java/com/wuyi/libraryauto/WuyiLibraryApp.kt`

**修改内容**：
1. 让 `WuyiLibraryApp` 实现 `Configuration.Provider` 接口
2. 添加 `workManagerConfiguration` 属性提供 WorkManager 配置
3. 在 `onCreate()` 中手动调用 `WorkManager.initialize()`

```kotlin
class WuyiLibraryApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        WorkManager.initialize(this, workManagerConfiguration)
        // ...
    }
}
```

**文件**：`app/src/main/AndroidManifest.xml`

**修改内容**：
禁用 WorkManager 的默认自动初始化

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

**预期效果**：解决 "WorkManager is not initialized properly" 错误，GuardWorker 可以正常入队。

---

### ✅ 修复 2：添加网络安全配置

**新文件**：`app/src/main/res/xml/network_security_config.xml`

**内容**：
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- 默认配置：要求 HTTPS -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>

    <!-- 允许校园网认证服务器使用明文 HTTP 通信 -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.10.244.11</domain>
    </domain-config>

    <!-- 允许本地环回地址使用明文 HTTP（便于本机调试） -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

**文件**：`app/src/main/AndroidManifest.xml`

**修改内容**：
在 `<application>` 标签中引用网络安全配置

```xml
<application
    ...
    android:networkSecurityConfig="@xml/network_security_config">
```

**预期效果**：解决 "CLEARTEXT communication not permitted" 错误，允许与校园网认证服务器的明文通信。

---

### ✅ 修复 3：优化后台保活机制

**文件**：`app/src/main/java/com/wuyi/libraryauto/WuyiLibraryApp.kt`

**修改内容**：
添加 WatchdogWorker 的调度，每 6 小时检查一次调度健康状态

```kotlin
override fun onCreate() {
    // ...
    PeriodicCheckInWorker.ensureScheduled(this)
    WatchdogWorker.ensureScheduled(this)  // 新增
    GuardSchedulerService.start(this)
    // ...
}
```

**新文件**：`BACKGROUND_KEEP_ALIVE_GUIDE.md`

创建了详细的用户指南，说明如何在不同品牌手机上配置后台保活设置。

**预期效果**：
1. WatchdogWorker 会定期检查并恢复失效的调度服务
2. 用户按照指南配置后，应用不易被系统杀后台

---

## 代码变更总结

### 修改的文件：
1. `app/src/main/java/com/wuyi/libraryauto/WuyiLibraryApp.kt`
   - 实现 `Configuration.Provider` 接口
   - 手动初始化 WorkManager
   - 添加 WatchdogWorker 调度

2. `app/src/main/AndroidManifest.xml`
   - 添加 tools 命名空间
   - 禁用 WorkManager 默认初始化
   - 配置网络安全策略

### 新增的文件：
1. `app/src/main/res/xml/network_security_config.xml`
   - 网络安全配置文件

2. `BACKGROUND_KEEP_ALIVE_GUIDE.md`
   - 用户后台保活配置指南

---

## 验证步骤

### 1. 编译验证
```bash
cd library-android
./gradlew assembleDebug
```

### 2. 安装测试
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 功能验证
- [ ] 启动应用，检查是否有崩溃
- [ ] 查看通知栏，确认 "守护调度服务" 正常运行
- [ ] 添加一个测试预约，观察是否能正常调度
- [ ] 锁屏 15 分钟后检查应用是否仍在后台运行
- [ ] 在校园网环境下测试自动认证功能

### 4. 日志验证
在应用内查看运行日志，确认：
- [ ] 没有 "WorkManager is not initialized properly" 错误
- [ ] 没有 "CLEARTEXT communication not permitted" 错误
- [ ] GuardSchedulerService 正常同步守护任务
- [ ] WatchdogWorker 定期巡检正常

---

## 用户操作建议

### 立即操作：
1. **更新应用**：安装修复后的新版本 APK
2. **配置保活**：参照 `BACKGROUND_KEEP_ALIVE_GUIDE.md` 配置手机后台保活设置
3. **重新登录**：确保账号信息和预约配置正确

### 日常使用：
1. **保持联网**：签到时间段确保手机连接校园网
2. **检查通知**：每天查看守护服务通知是否正常显示
3. **定期巡检**：建议每周打开应用一次，查看运行日志

### 遇到问题时：
1. **查看日志**：在应用内导出运行日志
2. **重启应用**：完全退出后重新打开
3. **重启手机**：清除系统缓存
4. **联系开发者**：提供详细的日志文件

---

## 已知限制

1. **蓝牙签到**：需要用户在图书馆范围内，且蓝牙信标正常工作
2. **网络依赖**：必须连接到校园网才能完成签到
3. **系统限制**：部分极端省电模式下仍可能被杀后台
4. **厂商限制**：某些手机厂商的系统优化可能影响后台运行

---

## 技术债务和改进建议

### 短期改进：
1. 添加应用内的保活配置引导页面
2. 增加更详细的错误提示和恢复建议
3. 优化日志记录，便于问题排查

### 长期优化：
1. 考虑使用云端推送服务作为签到触发的补充方案
2. 实现更智能的网络状态检测和恢复机制
3. 添加签到失败的多种降级策略

---

**修复完成时间**：2026-06-23
**修复版本**：建议更新 versionCode 为 227，versionName 为 3.1.5
