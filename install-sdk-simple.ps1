# Android SDK 简化安装脚本
# 使用方法：在 PowerShell 中执行此脚本

$ErrorActionPreference = "Stop"

Write-Host "=== Android SDK Installation ===" -ForegroundColor Cyan

$ANDROID_HOME = "C:\Android"
$CMDLINE_TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"

# Step 1: Create directories
Write-Host "Step 1: Creating directories..." -ForegroundColor Yellow
if (!(Test-Path $ANDROID_HOME)) {
    New-Item -ItemType Directory -Path $ANDROID_HOME | Out-Null
}
if (!(Test-Path "$ANDROID_HOME\cmdline-tools")) {
    New-Item -ItemType Directory -Path "$ANDROID_HOME\cmdline-tools" | Out-Null
}
Write-Host "Directories created" -ForegroundColor Green

# Step 2: Download
Write-Host "Step 2: Downloading Command Line Tools..." -ForegroundColor Yellow
$zipFile = "$ANDROID_HOME\cmdline-tools.zip"

if (Test-Path $zipFile) {
    Write-Host "Download already exists, skipping..." -ForegroundColor Green
} else {
    Write-Host "Attempting download from Google..." -ForegroundColor Gray
    try {
        [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $CMDLINE_TOOLS_URL -OutFile $zipFile -UseBasicParsing
        Write-Host "Download completed" -ForegroundColor Green
    } catch {
        Write-Host "Download failed!" -ForegroundColor Red
        Write-Host "Please manually download from:" -ForegroundColor Yellow
        Write-Host "https://developer.android.com/studio#command-line-tools-only" -ForegroundColor Cyan
        Write-Host "Save it as: $zipFile" -ForegroundColor Cyan
        Write-Host "Then run this script again" -ForegroundColor Yellow
        exit 1
    }
}

# Step 3: Extract
Write-Host "Step 3: Extracting..." -ForegroundColor Yellow
if (Test-Path "$ANDROID_HOME\cmdline-tools\latest") {
    Write-Host "Already extracted, skipping..." -ForegroundColor Green
} else {
    Expand-Archive -Path $zipFile -DestinationPath "$ANDROID_HOME\cmdline-tools" -Force
    if (Test-Path "$ANDROID_HOME\cmdline-tools\cmdline-tools") {
        Move-Item "$ANDROID_HOME\cmdline-tools\cmdline-tools" "$ANDROID_HOME\cmdline-tools\latest"
    }
    Write-Host "Extraction completed" -ForegroundColor Green
}

# Step 4: Set environment variables
Write-Host "Step 4: Setting environment variables..." -ForegroundColor Yellow
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $ANDROID_HOME, [System.EnvironmentVariableTarget]::User)

$currentPath = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::User)
$pathsToAdd = @(
    "$ANDROID_HOME\cmdline-tools\latest\bin",
    "$ANDROID_HOME\platform-tools"
)
foreach ($path in $pathsToAdd) {
    if ($currentPath -notlike "*$path*") {
        $currentPath = "$path;$currentPath"
    }
}
[System.Environment]::SetEnvironmentVariable("Path", $currentPath, [System.EnvironmentVariableTarget]::User)
Write-Host "Environment variables set" -ForegroundColor Green

# Refresh current session
$env:ANDROID_HOME = $ANDROID_HOME
$env:Path = "$ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"

# Step 5: Install SDK components
Write-Host "Step 5: Installing SDK components..." -ForegroundColor Yellow
Write-Host "This may take a few minutes..." -ForegroundColor Gray

$sdkmanager = "$ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat"

# Accept licenses
Write-Host "Accepting licenses..." -ForegroundColor Gray
$licenses = "y`ny`ny`ny`ny`ny`ny`ny`ny`n"
$licenses | & $sdkmanager --licenses 2>&1 | Out-Null

# Install components
$components = @(
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0"
)

foreach ($component in $components) {
    Write-Host "Installing $component..." -ForegroundColor Gray
    & $sdkmanager $component
}

Write-Host "SDK components installed" -ForegroundColor Green

# Step 6: Create local.properties
Write-Host "Step 6: Creating local.properties..." -ForegroundColor Yellow
$projectRoot = Split-Path -Parent $PSScriptRoot
$localPropsPath = Join-Path $PSScriptRoot "library-android\local.properties"
$androidHomePath = $ANDROID_HOME -replace '\\', '\\'
Set-Content -Path $localPropsPath -Value "sdk.dir=$androidHomePath"
Write-Host "Created: $localPropsPath" -ForegroundColor Green

Write-Host ""
Write-Host "=== Installation Complete ===" -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "1. Restart your terminal"
Write-Host "2. Verify: adb --version"
Write-Host "3. Build: cd library-android; .\gradlew.bat assembleDebug"
