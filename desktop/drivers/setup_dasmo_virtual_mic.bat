@echo off
title DASMO CYBER CAPTURE // Hardware Branding Engine
:: Check for Administrator elevation
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [*] Requesting Administrator privileges (please click 'Yes' on UAC prompt)...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_dasmo_virtual_mic.ps1" %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [!] Setup exited with code %ERRORLEVEL%.
)
echo.
echo Press any key to close this window...
pause >nul
