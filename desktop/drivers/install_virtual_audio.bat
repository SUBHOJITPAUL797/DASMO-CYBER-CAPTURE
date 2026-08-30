@echo off
title DASMO CYBER CAPTURE // 1-Click Virtual Audio Device Setup
color 0b
echo ==============================================================================
echo   DASMO CYBER CAPTURE - VIRTUAL AUDIO DRIVER INSTALLER
echo   Enables "DASMO Virtual Microphone" and "DASMO Virtual Speaker" in Windows
echo ==============================================================================
echo.
echo [1/2] Downloading Official VB-Audio Virtual Cable driver...
powershell -Command "Invoke-WebRequest -Uri 'https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack43.zip' -OutFile '%TEMP%\VBCABLE_Driver_Pack43.zip'"

echo.
echo [2/2] Extracting and running installer...
powershell -Command "Expand-Archive -Path '%TEMP%\VBCABLE_Driver_Pack43.zip' -DestinationPath '%TEMP%\VBCABLE_Driver' -Force"

echo.
echo [*] Launching Driver Setup (Please click 'Install Driver' when prompted)...
start "" "%TEMP%\VBCABLE_Driver\VBCABLE_Setup_x64.exe"

echo.
echo ==============================================================================
echo   AFTER INSTALLATION COMPLETE:
echo   1. Windows Settings > System > Sound:
echo      - Output Device: Select "CABLE Input (VB-Audio Virtual Cable)"
echo      - Input Device:  Select "CABLE Output (VB-Audio Virtual Cable)"
echo   2. In WhatsApp Desktop:
echo      - Microphone:    Select "CABLE Output (VB-Audio Virtual Cable)"
echo ==============================================================================
pause
