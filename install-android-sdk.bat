@echo off
REM Android SDK 自动安装脚本 - 批处理版本
REM 使用方法：双击运行或在命令提示符中执行 install-android-sdk.bat

echo === Android SDK 自动安装脚本 ===
echo.

set ANDROID_HOME=C:\Android
set CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip

REM 创建目录
echo 1. 创建 Android SDK 目录...
if not exist "%ANDROID_HOME%" mkdir "%ANDROID_HOME%"
if not exist "%ANDROID_HOME%\cmdline-tools" mkdir "%ANDROID_HOME%\cmdline-tools"

REM 下载提示
echo 2. 下载 Android Command Line Tools...
echo.
echo 请手动完成以下步骤：
echo 1. 访问 https://developer.android.com/studio#command-line-tools-only
echo 2. 下载 "Command line tools only" - Windows 版本
echo 3. 将下载的 ZIP 文件重命名为 cmdline-tools.zip
echo 4. 移动到 %ANDROID_HOME% 目录
echo 5. 按任意键继续...
echo.
pause

REM 检查文件是否存在
if not exist "%ANDROID_HOME%\cmdline-tools.zip" (
    echo 错误: 未找到 %ANDROID_HOME%\cmdline-tools.zip
    echo 请按照上述步骤下载并放置文件
    pause
    exit /b 1
)

REM 解压（需要 PowerShell）
echo 3. 解压 Command Line Tools...
powershell -Command "Expand-Archive -Path '%ANDROID_HOME%\cmdline-tools.zip' -DestinationPath '%ANDROID_HOME%\cmdline-tools' -Force"

REM 重命名目录
if not exist "%ANDROID_HOME%\cmdline-tools\latest" (
    move "%ANDROID_HOME%\cmdline-tools\cmdline-tools" "%ANDROID_HOME%\cmdline-tools\latest"
)
echo 解压完成

REM 配置环境变量
echo 4. 配置环境变量...
setx ANDROID_HOME "%ANDROID_HOME%"
setx Path "%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools;%Path%"
echo 环境变量配置完成

REM 设置当前会话的环境变量
set PATH=%ANDROID_HOME%\cmdline-tools\latest\bin;%PATH%

REM 安装 SDK 组件
echo 5. 安装 Android SDK 组件...
echo 这可能需要几分钟，请耐心等待...
echo.

REM 接受许可证
echo y | "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" --licenses

REM 安装必需组件
echo 安装 platform-tools...
call "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools"

echo 安装 Android 34 平台...
call "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34"

echo 安装 Build Tools...
call "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" "build-tools;34.0.0"

echo SDK 组件安装完成

REM 创建 local.properties
echo 6. 创建 local.properties...
echo sdk.dir=C:\\Android > "%~dp0library-android\local.properties"
echo 已创建 local.properties

echo.
echo === 安装完成 ===
echo.
echo 下一步操作：
echo 1. 重启命令提示符（让环境变量生效）
echo 2. 验证安装: adb --version
echo 3. 构建项目: cd library-android ^&^& gradlew.bat assembleDebug
echo.
echo 提示: 如果命令找不到，请重启电脑让环境变量生效
pause
