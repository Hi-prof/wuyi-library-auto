# Android SDK 快速安装指南

由于网络限制，无法自动下载 Android SDK。请按以下步骤手动安装：

## 方案一：使用 Android Studio（最简单，推荐）

1. **下载 Android Studio**
   - 访问：https://developer.android.com/studio
   - 或国内镜像：https://developer.android.google.cn/studio

2. **安装并配置**
   - 运行安装程序
   - 首次启动时会自动下载 SDK
   - SDK 默认位置：`C:\Users\Admin\AppData\Local\Android\Sdk`

3. **配置环境变量**（已自动化）
   运行以下 PowerShell 命令：
   ```powershell
   [System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\Admin\AppData\Local\Android\Sdk", [System.EnvironmentVariableTarget]::User)
   $path = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::User)
   [System.Environment]::SetEnvironmentVariable("Path", "C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools;$path", [System.EnvironmentVariableTarget]::User)
   ```

4. **创建 local.properties**
   在项目 `library-android` 目录创建文件：
   ```properties
   sdk.dir=C\:\\Users\\Admin\\AppData\\Local\\Android\\Sdk
   ```

## 方案二：纯命令行工具

1. **下载 Command Line Tools**
   - 官方链接：https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
   - 如果无法访问，使用浏览器的下载工具或迅雷等下载器
   - 或者从这里下载：https://developer.android.com/studio#command-line-tools-only

2. **手动配置**
   下载后，将文件保存为 `C:\Android\cmdline-tools.zip`，然后运行：
   ```powershell
   .\install-sdk.ps1
   ```

## 方案三：使用现有的 Android SDK（如果已安装）

如果你之前安装过 Android Studio 或 SDK，查找以下位置：
- `C:\Users\Admin\AppData\Local\Android\Sdk`
- `C:\Android`
- `D:\Android`

找到后，运行：
```powershell
# 替换为你的实际路径
$sdkPath = "你的SDK路径"
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkPath, [System.EnvironmentVariableTarget]::User)
$path = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::User)
[System.Environment]::SetEnvironmentVariable("Path", "$sdkPath\platform-tools;$path", [System.EnvironmentVariableTarget]::User)

# 创建 local.properties
$sdkPathEscaped = $sdkPath -replace '\\', '\\'
Set-Content -Path "library-android\local.properties" -Value "sdk.dir=$sdkPathEscaped"
```

## 当前已完成

✅ Java 17 已安装并配置
   - JAVA_HOME: C:\Program Files\Java\jdk-17
   - 验证：`java -version` 显示 17.0.12

## 验证安装

完成 Android SDK 安装后：
```bash
# 重启终端，然后运行
adb --version        # 应显示 Android Debug Bridge version
java -version        # 应显示 java version "17.0.12"

# 构建项目
cd library-android
.\gradlew.bat assembleDebug
```

## 推荐方案

**建议使用方案一（Android Studio）**，因为：
- 自动处理所有下载和配置
- 提供图形界面管理 SDK
- 包含模拟器和调试工具
- 国内可访问 developer.android.google.cn

完成后告诉我，我会帮你验证环境并构建项目。
