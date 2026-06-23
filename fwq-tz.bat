@echo off
setlocal

set "PORT=5000"

set "FOUND=0"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$pids = Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; $pids | ForEach-Object { $_ }"`) do (
    taskkill /PID %%P /F >nul 2>&1
    set "FOUND=1"
)

if "%FOUND%"=="0" (
    echo 端口 %PORT% 上没有运行中的服务。
    exit /b 0
)

powershell -NoProfile -Command ^
  "$deadline = (Get-Date).AddSeconds(5);" ^
  "while ((Get-Date) -lt $deadline) {" ^
  "  $busy = Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue;" ^
  "  if (-not $busy) { exit 0 }" ^
  "  Start-Sleep -Milliseconds 300" ^
  "}" ^
  "exit 1"
if errorlevel 1 (
    echo 服务停止失败：端口 %PORT% 仍被占用。
    exit /b 1
)

echo 服务已停止（端口 %PORT% 已释放）。
exit /b 0
