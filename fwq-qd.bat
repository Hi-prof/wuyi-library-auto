@echo off
setlocal

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "HOST=127.0.0.1"
set "PORT=5000"
set "PAGE_URL=http://%HOST%:%PORT%/"
set "PYTHONPATH_VALUE=%ROOT%\library-window\src;%ROOT%\library-fwq\src"

where uv >nul 2>&1
if errorlevel 1 (
    set "ERROR_MESSAGE=uv was not found in PATH."
    goto fail
)

for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$pids = Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; $pids | ForEach-Object { $_ }"`) do (
    taskkill /PID %%P /F >nul 2>&1
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
    set "ERROR_MESSAGE=Port %PORT% is still occupied."
    goto fail
)

powershell -NoProfile -Command ^
  "$env:PYTHONPATH = '%PYTHONPATH_VALUE%';" ^
  "$env:PREVENT_AUTO_HOST = '%HOST%';" ^
  "$env:PREVENT_AUTO_PORT = '%PORT%';" ^
  "Start-Process -FilePath 'uv' -ArgumentList 'run','--project','%ROOT%\library-fwq','python','-m','prevent_auto.main' -WorkingDirectory '%ROOT%' -WindowStyle Hidden"
if errorlevel 1 (
    set "ERROR_MESSAGE=Failed to start prevent_auto.main."
    goto fail
)

powershell -NoProfile -Command ^
  "$deadline = (Get-Date).AddSeconds(10);" ^
  "while ((Get-Date) -lt $deadline) {" ^
  "  $busy = Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue;" ^
  "  if ($busy) { exit 0 }" ^
  "  Start-Sleep -Milliseconds 500" ^
  "}" ^
  "exit 1"
if errorlevel 1 (
    set "ERROR_MESSAGE=Server did not start on port %PORT% within the timeout."
    goto fail
)

powershell -NoProfile -Command "Start-Process '%PAGE_URL%'"
if errorlevel 1 (
    set "ERROR_MESSAGE=Failed to open %PAGE_URL%."
    goto fail
)

exit /b 0

:fail
echo %ERROR_MESSAGE%
pause
exit /b 1
