@echo off
title DASMO CYBER CAPTURE // Windows Device Name Setup
color 0b

:: Self-elevate to Administrator
net session >nul 2>&1
if %errorLevel% neq 0 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \"\"%~f0\"\"' -Verb RunAs"
    exit /b
)

pushd "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_dasmo_device_names.ps1"
popd
