@echo off
title DASMO CYBER CAPTURE // 1-Click Desktop Virtual Camera Setup
color 0b
echo ==============================================================================
echo   DASMO CYBER CAPTURE - NATIVE DESKTOP VIRTUAL CAMERA DRIVER INSTALLER
echo   Device Name in WhatsApp Desktop: "DASMO CYBER CAPTURE"
echo ==============================================================================
echo.
echo [1/3] Checking Python environment...
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] Python is not found in PATH. Please install Python 3.9+ from https://www.python.org/downloads/
    echo [!] Make sure to check "Add Python to PATH" during installation.
    pause
    exit /b
)

echo [2/3] Installing virtual camera dependencies (opencv-python, pyvirtualcam)...
pip install --quiet opencv-python pyvirtualcam

echo.
echo [3/3] Ready to launch virtual camera!
set /p PHONE_IP="Enter Phone Wi-Fi IP address (default: 192.168.1.50): "
if "%PHONE_IP%"=="" set PHONE_IP=192.168.1.50

echo.
echo ==============================================================================
echo   LAUNCHING DASMO CYBER CAPTURE VIRTUAL CAMERA DRIVER...
echo ==============================================================================
python dasmo_virtualcam.py %PHONE_IP%
pause
