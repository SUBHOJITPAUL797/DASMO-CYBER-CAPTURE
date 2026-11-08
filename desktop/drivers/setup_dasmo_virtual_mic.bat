@echo off
title DASMO CYBER CAPTURE // Hardware Branding Engine
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_dasmo_virtual_mic.ps1" %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [!] Setup exited with code %ERRORLEVEL%.
)
echo.
echo Press any key to close this window...
pause >nul
