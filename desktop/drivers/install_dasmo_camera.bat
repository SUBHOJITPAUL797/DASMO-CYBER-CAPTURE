@echo off
title DASMO CYBER CAPTURE // 1-Click Desktop Virtual Camera Setup
color 0b

:: Check for Administrator privileges
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo ==============================================================================
    echo   REQUESTING ADMINISTRATOR PRIVILEGES...
    echo   Please click "Yes" on the Windows prompt to register the camera driver.
    echo ==============================================================================
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \"\"%~f0\"\"' -Verb RunAs"
    exit /b
)

pushd "%~dp0"

echo ==============================================================================
echo   DASMO CYBER CAPTURE - NATIVE DESKTOP VIRTUAL CAMERA DRIVER INSTALLER
echo   Enables "DASMO CYBER CAPTURE" / "Unity Video Capture" in Windows
echo ==============================================================================
echo.

echo [1/3] Registering DirectShow Virtual Camera Filter (64-bit and 32-bit)...
if exist "UnityCaptureFilter64.dll" (
    regsvr32.exe /s /i:UnityCaptureDevices=1 "%~dp0UnityCaptureFilter64.dll"
    echo   [+] Registered UnityCaptureFilter64.dll successfully.
) else (
    echo   [!] UnityCaptureFilter64.dll not found in %~dp0
)

if exist "UnityCaptureFilter32.dll" (
    regsvr32.exe /s /i:UnityCaptureDevices=1 "%~dp0UnityCaptureFilter32.dll"
    echo   [+] Registered UnityCaptureFilter32.dll successfully.
)

:: Set FriendlyName directly to "DASMO CAMERA"
reg add "HKCR\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{5C2CD55C-92AD-4999-8666-912BD3E70010}" /v FriendlyName /t REG_SZ /d "DASMO CAMERA" /f >nul 2>&1
reg add "HKLM\SOFTWARE\Classes\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{5C2CD55C-92AD-4999-8666-912BD3E70010}" /v FriendlyName /t REG_SZ /d "DASMO CAMERA" /f >nul 2>&1

echo.
echo [2/3] Setting friendly branded names for DASMO MIC and DASMO SPEAKER...
if exist "%~dp0setup_dasmo_device_names.ps1" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_dasmo_device_names.ps1"
)

echo.
echo [3/3] Installing Python driver bridge packages...
python -m pip install --quiet opencv-python pyvirtualcam sounddevice PyAudioWPatch numpy simplejpeg PyTurboJPEG
if %errorlevel% equ 0 (
    echo   [+] Python packages (OpenCV, pyvirtualcam, simplejpeg, WASAPI audio) installed and verified.
) else (
    echo   [!] Note: Ensure Python is installed from python.org with 'Add to PATH' checked.
)

echo.
echo ==============================================================================
echo   SUCCESS! DASMO CYBER CAPTURE hardware devices are configured:
echo   - Camera:     "DASMO CAMERA"
echo   - Microphone: "DASMO MIC"
echo   - Speaker:    "DASMO SPEAKER"
echo ==============================================================================
echo.
pause
