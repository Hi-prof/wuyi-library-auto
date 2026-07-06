# Wuyi Library Android

Android 原生客户端当前提供 5 个底部 tab：`首页 / 账号 / 预约 / 任务 / 设置`。账号新增时密码可以留空，默认等于学号；手动预约成功和自动任务创建成功后，都会把本次目标座位回写到账号偏好。自动任务现在按账号独立保存，同一账号只维护一条启用计划；账号列表会展示当前预约/签到状态，并可直接执行签到、取消预约、签退。设置页按常用、监控、同步和诊断分组，包含权限、运行说明、自动预约说明、签到监控、看门狗状态、服务端同步、诊断日志和构建信息模块。

## Build Commands

在 `library-android/` 目录执行：

```powershell
python .\scripts\build_android.py debug
python .\scripts\build_android.py release
python .\scripts\build_android.py bundleRelease
```

推荐优先使用 `scripts/build_android.py`。这个脚本会先读取 [version.properties](C:/Users/xuhuangbin/Desktop/wuyi-library-auto/library-android/version.properties)，如果当前版本是两段 `x.y`，下一次打包会变成 `x.(y+1).0`；如果当前版本已经是三段 `x.y.z`，下一次打包会变成 `x.y.(z+1)`。同时它还会把 `versionCode` 加 `1`、刷新 `BUILD_MARKER`，然后再执行对应的 Gradle 打包任务。比如当前是 `3.0`，下一次执行 `python .\scripts\build_android.py debug` 会自动变成 `3.1.0`；如果当前是 `3.1.0`，下一次会自动变成 `3.1.1`。

如果只想直接调用 Gradle，也可以继续使用：

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug
.\gradlew.bat --no-daemon --max-workers=1 :app:assembleRelease
.\gradlew.bat --no-daemon --max-workers=1 :app:bundleRelease
```

所有 Android 打包命令都会先清理 `dist/android` 里的旧产物，再把新的结果同步进去。

`assembleDebug` 完成后会输出：

- `dist/android/wuyi-library-auto-v<versionName>-<BUILD_MARKER>-debug.apk`
- `dist/android/output-metadata.json`

`assembleRelease` 完成后会输出到 `dist/android`：

- `wuyi-library-auto-v<versionName>-<BUILD_MARKER>-release.apk`
- release 对应的 `output-metadata.json`

`bundleRelease` 完成后会输出到 `dist/android`：

- `wuyi-library-auto-v<versionName>-<BUILD_MARKER>-release.aab`

`assembleRelease` 和 `bundleRelease` 只有在 `keystore.properties` 四个字段都已替换为真实值，且 `storeFile` 指向的 keystore 文件实际存在时，才会自动启用 release signing；否则会保留未签名 release 制品，适合本地冒烟检查，不适合正式分发。

## Release Signing

1. 复制 `keystore.properties.example` 为 `keystore.properties`。
2. 按实际 keystore 路径和密码填写以下字段：

```properties
storeFile=keystore/release.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

3. `storeFile` 以 `library-android/` 根目录为基准解析，所以 `storeFile=keystore/release.keystore` 表示 keystore 文件应放在 `library-android/keystore/release.keystore`。
4. 确保 `storeFile` 指向的 keystore 文件在本机存在。

## Device Validation

建议的最小真机验证步骤：

1. 执行 `:app:assembleDebug`，确认 debug APK 可以生成。
2. 用 Android Studio 或 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装到真机。
3. 启动应用，确认首屏出现“首页”和“今日概览”，并能看到底部 5 个 tab：`首页 / 账号 / 预约 / 任务 / 设置`。
4. 切换到“账号”，点击“添加账号”，使用真实学校账号登录；密码留空时默认应等于学号。认证成功后会回到账号列表。
5. 进入“预约”，选择日期、开始时间和时长后查询座位；选中座位后点击“立即预约”，确认成功提示出现。
6. 返回“任务”，点击“添加任务”，确认会弹出新对话框；默认模式为“持续预约”，并优先带入账号里的目标座位，同时保留“刷新/查询座位”按钮。
7. 切换到“账号”，确认账号卡片能看到认证状态、当前账号标记、已保存目标座位，以及“查看自动任务”动作。
8. 切换到“设置”，确认能看到“设置中心”和“常用 / 监控 / 同步 / 诊断”四组入口。
9. 如需验证 instrumentation smoke test，连接真机或已启动模拟器后执行：

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:connectedDebugAndroidTest
```

当前仓库新增的 instrumentation smoke test 位于 `app/src/androidTest/java/com/wuyi/libraryauto/SmokeNavigationTest.kt`，启动 `MainActivity` 后会断言首页能看到新的 5 个底部 tab，并验证设置页里的模块文案。

## Known Environment Constraints

- 当前工作机默认使用 `D:\jdk-24.0.1`，但这不是本项目推荐的 Gradle/AGP 运行时。针对当前仓库使用的 AGP `8.5.2` 与 Gradle `8.7`，应优先使用 JDK 17 或其他明确受支持的运行时来执行 README 里的构建命令。JDK 24 只适合作为当前机器上的临时兼容环境，不应视为受支持基线。
- 这个环境对 Gradle daemon、KAPT 和缓存占用比较敏感。建议始终使用 `--no-daemon --max-workers=1`，并配合较小堆内存，例如：

```powershell
$env:_JAVA_OPTIONS='-Xms64m -Xmx256m -XX:+UseSerialGC'
$env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8'
```

- 如果 instrumentation 验证环境缺少真机或模拟器，`connectedDebugAndroidTest` 无法稳定完成；这类场景先确保 `:app:compileDebugAndroidTestKotlin` 可通过，再在具备设备的环境执行连机验证。
- 如果前一次 Gradle 构建被超时或强制中断，后续命令可能卡在 `C:\Users\xuhuangbin\.gradle\caches\journal-1` 或项目内 `.gradle\8.7\fileHashes` 锁。出现 owner PID 提示时，需要先等待该 Gradle 进程结束，或清理本次残留的 Gradle/Java 进程后再重试。
