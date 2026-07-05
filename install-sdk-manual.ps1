# Android SDK 手动安装助手
# 请按照以下步骤操作

Write-Host "=== Android SDK 手动安装助手 ===" -ForegroundColor Cyan
Write-Host ""

# 步骤 1
Write-Host "步骤 1: 下载 Android Command Line Tools" -ForegroundColor Yellow
Write-Host "--------------------------------------" -ForegroundColor Gray
Write-Host "请在浏览器中打开以下链接："
Write-Host "https://developer.android.com/studio#command-line-tools-only" -ForegroundColor Cyan
Write-Host ""
Write-Host "下载 'Command line tools only' - Windows 版本"
Write-Host "文件名应该类似: commandlinetools-win-XXXXXXXX_latest.zip"
Write-Host ""
Write-Host "下载完成后，按任意键继续..." -ForegroundColor Yellow
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

# 步骤 2
Write-Host ""
Write-Host "步骤 2: 移动下载的文件" -ForegroundColor Yellow
Write-Host "--------------------------------------" -ForegroundColor Gray
Write-Host "请将下载的 ZIP 文件移动到以下位置："
Write-Host "C:\Android\cmdline-tools.zip" -ForegroundColor Cyan
Write-Host ""
Write-Host "如果提示创建文件夹，请选择创建"
Write-Host ""
Write-Host "移动完成后，按任意键继续..." -ForegroundColor Yellow
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

# 验证文件
Write-Host ""
Write-Host "验证文件..." -ForegroundColor Gray
if (!(Test-Path "C:\Android\cmdline-tools.zip")) {
    Write-Host "错误: 未找到文件 C:\Android\cmdline-tools.zip" -ForegroundColor Red
    Write-Host "请确保文件已正确移动到该位置" -ForegroundColor Red
    exit 1
}
Write-Host "文件验证成功!" -ForegroundColor Green

# 步骤 3: 解压
Write-Host ""
Write-Host "步骤 3: 解压文件..." -ForegroundColor Yellow
Write-Host "--------------------------------------" -ForegroundColor Gray
if (!(Test-Path "C:\Android\cmdline-tools")) {
    New-Item -ItemType Directory -Path "C:\Android\cmdline-tools" | Out-Null
}
Expand-Archive -Path "C:\Android\cmdline-tools.zip" -DestinationPath "C:\Android\cmdline-tools" -Force
if (Test-Path "C:\Android\cmdline-tools\cmdline-tools") {
    if (Test-Path "C:\Android\cmdline-tools\latest") {
        Remove-Item "C:\Android\cmdline-tools\latest" -Recurse -Force
    }
    Move-Item "C:\Android\cmdline-tools\cmdline-tools" "C:\Android\cmdline-tools\latest"
}
Write-Host "解压完成" -ForegroundColor Green

# 步骤 4: 设置环境变量
Write-Host ""
Write-Host "步骤 4: 设置环境变量..." -ForegroundColor Yellow
Write-Host "--------------------------------------" -ForegroundColor Gray
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
Write-Host "ANDROID_HOME = C:\Android" -ForegroundColor Green
Write-Host "PATH updated" -ForegroundColor Green

# 刷新当前会话
$env:ANDROID_HOME = "C:\Android"
$env:Path = "C:\Android\cmdline-tools\latest\bin;$env:Path"

# 步骤 5: 安装 SDK 组件
Write-Host ""
Write-Host "步骤 5: 安装 SDK 组件..." -ForegroundColor Yellow
Write-Host "--------------------------------------" -ForegroundColor Gray
Write-Host "这可能需要几分钟时间，请耐心等待..." -ForegroundColor Gray
Write-Host ""

$sdkmanager = "C:\Android\cmdline-tools\latest\bin\sdkmanager.bat"

# 接受许可
Write-Host "接受 SDK 许可证..." -ForegroundColor Gray
$licenses = "y`ny`ny`ny`ny`ny`ny`ny`n"
$licenses | & $sdkmanager --licenses 2>&1 | Out-Null

# 安装组件
$components = @(
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0"
)

foreach ($component in $components) {
    Write-Host "正在安装 $component..." -ForegroundColor Gray
    & $sdkmanager $component 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  $component 安装成功" -ForegroundColor Green
    }
}

# 步骤 6: 创建 local.properties
Write-Host ""
Write-Host "步骤 6: 创建 local.properties..." -ForegroundColor Yellow
Write-Host "--------------------------------------" -ForegroundColor Gray
$localPropsPath = "$PSScriptRoot\library-android\local.properties"
Set-Content -Path $localPropsPath -Value "sdk.dir=C:\\Android"
Write-Host "已创建: $localPropsPath" -ForegroundColor Green

# 完成
Write-Host ""
Write-Host "=== 安装完成! ===" -ForegroundColor Green
Write-Host ""
Write-Host "环境配置摘要:" -ForegroundColor Cyan
Write-Host "  JAVA_HOME: C:\Program Files\Java\jdk-17"
Write-Host "  ANDROID_HOME: C:\Android"
Write-Host ""
Write-Host "下一步操作:" -ForegroundColor Cyan
Write-Host "1. 重启终端（PowerShell 或命令提示符）"
Write-Host "2. 验证 Java: java -version"
Write-Host "3. 验证 Android SDK: adb --version"
Write-Host "4. 构建项目: cd library-android; .\gradlew.bat assembleDebug"
Write-Host ""
Write-Host "按任意键退出..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
