@echo off
setlocal

set "ROOT_DIR=%~dp0"
set "VERSION_FILE=%ROOT_DIR%version.properties"
set "BACKUP_FILE=%ROOT_DIR%version.properties.bak"
set "BUILD_TARGET=%~1"
if "%BUILD_TARGET%"=="" set "BUILD_TARGET=debug"

if /I "%BUILD_TARGET%"=="debug" (
    set "GRADLE_TASK=:app:assembleDebug"
) else if /I "%BUILD_TARGET%"=="release" (
    set "GRADLE_TASK=:app:assembleRelease"
) else if /I "%BUILD_TARGET%"=="bundleRelease" (
    set "GRADLE_TASK=:app:bundleRelease"
) else (
    echo 用法：build-android.bat [debug^|release^|bundleRelease]
    exit /b 1
)

copy /Y "%VERSION_FILE%" "%BACKUP_FILE%" >nul
if errorlevel 1 (
    echo 备份版本文件失败：%VERSION_FILE%
    exit /b 1
)

python "%ROOT_DIR%scripts\prepare_android_version.py" --version-file "%VERSION_FILE%"
if errorlevel 1 goto restore_version

call "%ROOT_DIR%gradlew.bat" --no-daemon --max-workers=1 %GRADLE_TASK%
if errorlevel 1 goto restore_version

del /Q "%BACKUP_FILE%" >nul 2>nul
echo 打包完成：%GRADLE_TASK%
exit /b 0

:restore_version
move /Y "%BACKUP_FILE%" "%VERSION_FILE%" >nul
echo 打包失败，已恢复版本文件。
exit /b 1
