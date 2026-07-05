# Android SDK 非交互式安装脚本
# 前提：已下载 commandlinetools-win-*.zip 到 C:\Android\cmdline-tools.zip

Write-Host "=== Android SDK Auto Installer ===" -ForegroundColor Cyan
Write-Host ""

# 检查下载文件
if (!(Test-Path "C:\Android\cmdline-tools.zip")) {
    Write-Host "错误: 未找到下载文件" -ForegroundColor Red
    Write-Host ""
    Write-Host "请按以下步骤操作：" -ForegroundColor Yellow
    Write-Host "1. 访问 https://developer.android.com/studio#command-line-tools-only"
    Write-Host "2. 下载 'Command line tools only' Windows 版本"
    Write-Host "3. 将下载的 ZIP 文件重命名为 cmdline-tools.zip"
    Write-Host "4. 移动到 C:\Android\ 目录（如果目录不存在请创建）"
    Write-Host "5. 再次运行此脚本"
    Write-Host ""
    Write-Host "或者，直接在浏览器中复制此链接下载：" -ForegroundColor Cyan
    Write-Host "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    exit 1
}

Write-Host "检测到下载文件，开始安装..." -ForegroundColor Green
Write-Host ""

# 创建目录
Write-Host "[1/5] 准备目录..." -ForegroundColor Yellow
if (!(Test-Path "C:\Android\cmdline-tools")) {
    New-Item -ItemType Directory -Path "C:\Android\cmdline-tools" -Force | Out-Null
}
Write-Host "完成" -ForegroundColor Green

# 解压
Write-Host "[2/5] 解压文件..." -ForegroundColor Yellow
if (Test-Path "C:\Android\cmdline-tools\latest") {
    Write-Host "已存在，清理旧版本..." -ForegroundColor Gray
    Remove-Item "C:\Android\cmdline-tools\latest" -Recurse -Force
}
Expand-Archive -Path "C:\Android\cmdline-tools.zip" -DestinationPath "C:\Android\cmdline-tools" -Force
if (Test-Path "C:\Android\cmdline-tools\cmdline-tools") {
    Move-Item "C:\Android\cmdline-tools\cmdline-tools" "C:\Android\cmdline-tools\latest"
}
Write-Host "完成" -ForegroundColor Green

# 设置环境变量
Write-Host "[3/5] 配置环境变量..." -ForegroundColor Yellow
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Android", [System.EnvironmentVariableTarget]::User)

$currentPath = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::User)
$pathsToAdd = @(
    "C:\Android\cmdline-tools\latest\bin",
    "C:\Android\platform-tools"
)
foreach ($path in $pathsToAdd) {
    if ($currentPath -notlike "*$path*") {
        $currentPath = "$path;$currentPath"
    }
}
[System.Environment]::SetEnvironmentVariable("Path", $currentPath, [System.EnvironmentVariableTarget]::User)

$env:ANDROID_HOME = "C:\Android"
$env:Path = "C:\Android\cmdline-tools\latest\bin;$env:Path"
Write-Host "ANDROID_HOME = C:\Android" -ForegroundColor Green

# 接受许可证
Write-Host "[4/5] 接受 SDK 许可证..." -ForegroundColor Yellow
$sdkmanager = "C:\Android\cmdline-tools\latest\bin\sdkmanager.bat"
"y`ny`ny`ny`ny`ny`ny`ny`n" | & $sdkmanager --licenses 2>&1 | Out-Null
Write-Host "完成" -ForegroundColor Green

# 安装 SDK 组件
Write-Host "[5/5] 安装 SDK 组件（需要几分钟）..." -ForegroundColor Yellow
$components = @(
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0"
)

foreach ($component in $components) {
    Write-Host "  安装 $component..." -ForegroundColor Gray
    & $sdkmanager $component 2>&1 | Out-Null
}
Write-Host "完成" -ForegroundColor Green

# 创建 local.properties
Write-Host ""
Write-Host "创建 local.properties..." -ForegroundColor Yellow
$localPropsPath = "$PSScriptRoot\library-android\local.properties"
Set-Content -Path $localPropsPath -Value "sdk.dir=C:\\Android"
Write-Host "已创建: $localPropsPath" -ForegroundColor Green

# 完成
Write-Host ""
Write-Host "=== 安装成功! ===" -ForegroundColor Green
Write-Host ""
Write-Host "环境变量已配置：" -ForegroundColor Cyan
Write-Host "  JAVA_HOME: C:\Program Files\Java\jdk-17"
Write-Host "  ANDROID_HOME: C:\Android"
Write-Host ""
Write-Host "下一步：" -ForegroundColor Cyan
Write-Host "1. 重启终端"
Write-Host "2. 验证: java -version"
Write-Host "3. 验证: adb --version"
Write-Host "4. 构建: cd library-android; .\gradlew.bat assembleDebug"
