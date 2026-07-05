# Android SDK 自动安装脚本
# 使用方法：在 PowerShell 中执行 .\install-android-sdk.ps1

$ErrorActionPreference = "Stop"

Write-Host "=== Android SDK 自动安装脚本 ===" -ForegroundColor Cyan
Write-Host ""

# 配置
$ANDROID_HOME = "C:\Android"
$CMDLINE_TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$CMDLINE_TOOLS_MIRROR = "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-11076708_latest.zip"

# 创建目录
Write-Host "1. 创建 Android SDK 目录..." -ForegroundColor Yellow
if (!(Test-Path $ANDROID_HOME)) {
    New-Item -ItemType Directory -Path $ANDROID_HOME | Out-Null
}
if (!(Test-Path "$ANDROID_HOME\cmdline-tools")) {
    New-Item -ItemType Directory -Path "$ANDROID_HOME\cmdline-tools" | Out-Null
}

# 下载 Command Line Tools
Write-Host "2. 下载 Android Command Line Tools..." -ForegroundColor Yellow
$zipFile = "$ANDROID_HOME\cmdline-tools.zip"

if (Test-Path $zipFile) {
    Write-Host "   已存在下载文件，跳过下载" -ForegroundColor Green
} else {
    Write-Host "   尝试从 Google 官方源下载..."
    try {
        Invoke-WebRequest -Uri $CMDLINE_TOOLS_URL -OutFile $zipFile -UseBasicParsing
        Write-Host "   下载完成" -ForegroundColor Green
    } catch {
        Write-Host "   官方源失败，尝试腾讯镜像..." -ForegroundColor Yellow
        try {
            Invoke-WebRequest -Uri $CMDLINE_TOOLS_MIRROR -OutFile $zipFile -UseBasicParsing
            Write-Host "   下载完成" -ForegroundColor Green
        } catch {
            Write-Host "   自动下载失败。请手动下载：" -ForegroundColor Red
            Write-Host "   1. 访问 https://developer.android.com/studio#command-line-tools-only"
            Write-Host "   2. 下载 Windows 版本"
            Write-Host "   3. 保存到 $zipFile"
            Write-Host "   4. 重新运行此脚本"
            exit 1
        }
    }
}

# 解压
Write-Host "3. 解压 Command Line Tools..." -ForegroundColor Yellow
if (Test-Path "$ANDROID_HOME\cmdline-tools\cmdline-tools") {
    Write-Host "   已解压，跳过" -ForegroundColor Green
} else {
    Expand-Archive -Path $zipFile -DestinationPath "$ANDROID_HOME\cmdline-tools" -Force
    Write-Host "   解压完成" -ForegroundColor Green
}

# 重命名为 latest（sdkmanager 要求的目录结构）
if (!(Test-Path "$ANDROID_HOME\cmdline-tools\latest")) {
    Move-Item "$ANDROID_HOME\cmdline-tools\cmdline-tools" "$ANDROID_HOME\cmdline-tools\latest"
    Write-Host "   目录结构调整完成" -ForegroundColor Green
}

# 配置环境变量
Write-Host "4. 配置环境变量..." -ForegroundColor Yellow
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $ANDROID_HOME, [System.EnvironmentVariableTarget]::User)
$currentPath = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::User)
$pathsToAdd = @(
    "$ANDROID_HOME\cmdline-tools\latest\bin",
    "$ANDROID_HOME\platform-tools",
    "$ANDROID_HOME\emulator"
)
foreach ($path in $pathsToAdd) {
    if ($currentPath -notlike "*$path*") {
        $currentPath = "$path;$currentPath"
    }
}
[System.Environment]::SetEnvironmentVariable("Path", $currentPath, [System.EnvironmentVariableTarget]::User)
Write-Host "   ANDROID_HOME = $ANDROID_HOME" -ForegroundColor Green

# 刷新当前会话的环境变量
$env:ANDROID_HOME = $ANDROID_HOME
$env:Path = "$ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"

# 安装必需的 SDK 组件
Write-Host "5. 安装 Android SDK 组件..." -ForegroundColor Yellow
Write-Host "   这可能需要几分钟，请耐心等待..." -ForegroundColor Gray

$sdkmanager = "$ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat"

# 接受许可证
Write-Host "   接受 SDK 许可证..." -ForegroundColor Gray
& $sdkmanager --licenses 2>&1 | ForEach-Object {
    if ($_ -match "Accept") {
        "y"
    }
} | & $sdkmanager --licenses

# 安装组件
$components = @(
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0",
    "cmdline-tools;latest"
)

foreach ($component in $components) {
    Write-Host "   安装 $component..." -ForegroundColor Gray
    & $sdkmanager $component
}

Write-Host "   SDK 组件安装完成" -ForegroundColor Green

# 创建 local.properties
Write-Host "6. 创建 local.properties..." -ForegroundColor Yellow
$localPropsPath = "$PSScriptRoot\library-android\local.properties"
$androidHomePath = $ANDROID_HOME -replace '\\', '\\'
Set-Content -Path $localPropsPath -Value "sdk.dir=$androidHomePath"
Write-Host "   已创建: $localPropsPath" -ForegroundColor Green

# 完成
Write-Host ""
Write-Host "=== 安装完成 ===" -ForegroundColor Green
Write-Host ""
Write-Host "下一步操作：" -ForegroundColor Cyan
Write-Host "1. 重启终端（让环境变量生效）"
Write-Host "2. 验证安装: adb --version"
Write-Host "3. 构建项目: cd library-android && .\gradlew.bat assembleDebug"
Write-Host ""
Write-Host "提示: 如果命令找不到，请重启 PowerShell 或者注销重新登录系统" -ForegroundColor Yellow
