@echo off
title QuestBeam Host Launcher
echo =======================================================
echo          QuestBeam Meta Quest 3 Cast Host
echo =======================================================
echo.

cd /d "%~dp0\signaling-server"
echo [1/3] Starting QuestBeam Signaling Server on port 8080...
start "QuestBeam Server" cmd /k "node server.js"

timeout /t 2 /nobreak >nul

echo [2/3] Checking ADB Reverse Tunnel over USB-C...
set "ADB_PATH=C:\Users\Howard Wilyman\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if exist "%ADB_PATH%" (
    "%ADB_PATH%" reverse tcp:8080 tcp:8080 2>nul
    if %errorlevel% equ 0 (
        echo [OK] USB Cable Reverse Tunnel is active!
    ) else (
        echo [NOTE] If using USB-C cable, put on your Quest 3 and tap "Allow USB debugging".
    )
)

echo [3/3] Opening Web Receiver...
start http://localhost:3000

echo.
echo =======================================================
echo  QuestBeam is running!
echo  - Local Web Receiver: http://localhost:3000
echo  - Global Web Receiver: https://questbeam.web.app
echo  - Now put on your Quest 3 and tap "Start Casting"!
echo =======================================================
echo.
pause
