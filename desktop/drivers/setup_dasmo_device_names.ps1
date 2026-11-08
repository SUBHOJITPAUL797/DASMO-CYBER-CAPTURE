# ==============================================================================
# DASMO CYBER CAPTURE // 1-CLICK WINDOWS HARDWARE BRANDING ENGINE
# Sets friendly device names in Windows:
#   1. Video Capture -> "DASMO CAMERA"
#   2. Virtual Mic   -> "DASMO Virtual Microphone" (Default Mic)
#   3. Phone Speaker -> "DASMO Cyber Audio Link"
# ==============================================================================

# Ensure Running as Administrator
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host '[*] Requesting Administrator elevation to rename Windows devices...' -ForegroundColor Yellow
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Verb RunAs
    exit
}

$scriptDir = Split-Path -Parent $PSCommandPath
& (Join-Path $scriptDir "setup_dasmo_virtual_mic.ps1")
