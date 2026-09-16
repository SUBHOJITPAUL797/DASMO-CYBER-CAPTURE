# ==============================================================================
# DASMO CYBER CAPTURE // PROPRIETARY HARDWARE BRANDING & DRIVER ENGINE
# Brands Virtual Audio endpoints in Windows as official DASMO hardware:
#   - Capture (Input):  "DASMO Virtual Microphone"
#   - Render (Bridge):  "DASMO Cyber Audio Link"
#   - Provider:         "DASMO CYBER CAPTURE"
# ==============================================================================

param (
    [switch]$Uninstall,
    [switch]$Silent
)

# Ensure running as Administrator
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    $argList = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    if ($Uninstall) { $argList += " -Uninstall" }
    if ($Silent) { $argList += " -Silent" }
    Start-Process powershell -ArgumentList $argList -Verb RunAs -Wait
    exit
}

$scriptDir = Split-Path -Parent $PSCommandPath
$vbcableSetup = "C:\Program Files\VB\CABLE\VBCABLE_Setup_x64.exe"
if (-not (Test-Path $vbcableSetup)) {
    $vbcableSetup = Join-Path $scriptDir "VBCABLE_Setup_x64.exe"
}

if ($Uninstall) {
    Write-Host "[*] Removing DASMO Virtual Audio Hardware..." -ForegroundColor Yellow
    if (Test-Path $vbcableSetup) {
        Start-Process $vbcableSetup -ArgumentList "-u -h" -Wait
    }
    Restart-Service AudioSrv -Force -ErrorAction SilentlyContinue
    Write-Host "[+] DASMO Virtual Audio cleanly uninstalled. No leftover drivers." -ForegroundColor Green
    exit 0
}

# 1. DirectShow Virtual Camera Filter -> "DASMO CAMERA"
$camPaths = @(
    'HKLM:\SOFTWARE\Classes\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{5C2CD55C-92AD-4999-8666-912BD3E70010}',
    'HKLM:\SOFTWARE\WOW6432Node\Classes\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{5C2CD55C-92AD-4999-8666-912BD3E70010}'
)
foreach ($cp in $camPaths) {
    if (Test-Path $cp) {
        try {
            Set-ItemProperty -Path $cp -Name 'FriendlyName' -Value 'DASMO CAMERA' -Force
        } catch {}
    }
}

# 2. If driver is not registered, install silently
$hasAudioDriver = Test-Path "C:\Windows\System32\drivers\vbaudio_cable64_win7.sys"
if (-not $hasAudioDriver -and (Test-Path $vbcableSetup)) {
    Write-Host "[*] Registering DASMO Virtual Audio kernel device..." -ForegroundColor Cyan
    Start-Process $vbcableSetup -ArgumentList "-i -h" -Wait
    Start-Sleep -Seconds 2
}

# 2. BRAND CAPTURE ENDPOINTS -> "DASMO Virtual Microphone"
$micCount = 0
Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture' -ErrorAction SilentlyContinue | ForEach-Object {
    $propsPath = Join-Path $_.PSPath 'Properties'
    if (Test-Path $propsPath) {
        $props = Get-ItemProperty $propsPath
        $desc = [string]$props.'{a45c254e-df1c-4efd-8020-67d146a850e0},2'
        $prov = [string]$props.'{b3f8fa53-0004-438e-9003-51a46e139bfc},6'
        $drv = [string]$props.'{a8b865dd-2e3d-4094-ad97-e593a70c75d6},8'
        $guid = $_.PSChildName

        if ($desc -like '*CABLE*' -or $prov -like '*VB-Audio*' -or $drv -like '*VBAudio*' -or $desc -like '*DASMO*' -or $guid -eq '{b1d118cd-be00-4bb9-bd5f-8f36a9fec1e7}') {
            try {
                Set-ItemProperty -Path $propsPath -Name '{a45c254e-df1c-4efd-8020-67d146a850e0},2' -Value 'DASMO Virtual Microphone' -Force
                Set-ItemProperty -Path $propsPath -Name '{b3f8fa53-0004-438e-9002-2d4f3136ac5e},2' -Value 'DASMO Virtual Microphone' -Force
                Set-ItemProperty -Path $propsPath -Name '{b3f8fa53-0004-438e-9003-51a46e139bfc},6' -Value 'DASMO CYBER CAPTURE' -Force
                # CRITICAL ANTI-ECHO FIX: Disable Windows "Listen to this device" so mic NEVER plays through PC speakers
                Remove-ItemProperty -Path $propsPath -Name '{24dbb0fc-9311-4b3d-9cf0-18ff155639d4},1' -ErrorAction SilentlyContinue
                Remove-ItemProperty -Path $propsPath -Name '{24dbb0fc-9311-4b3d-9cf0-18ff155639d4},0' -ErrorAction SilentlyContinue
                $micCount++
            } catch {
                Write-Warning "Could not update capture props: $_"
            }
        }
    }
}

# 3. BRAND RENDER ENDPOINTS -> "DASMO Cyber Audio Link"
$spkCount = 0
Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render' -ErrorAction SilentlyContinue | ForEach-Object {
    $propsPath = Join-Path $_.PSPath 'Properties'
    if (Test-Path $propsPath) {
        $props = Get-ItemProperty $propsPath
        $desc = [string]$props.'{a45c254e-df1c-4efd-8020-67d146a850e0},2'
        $prov = [string]$props.'{b3f8fa53-0004-438e-9003-51a46e139bfc},6'
        $drv = [string]$props.'{a8b865dd-2e3d-4094-ad97-e593a70c75d6},8'
        $guid = $_.PSChildName

        if ($desc -like '*CABLE*' -or $prov -like '*VB-Audio*' -or $drv -like '*VBAudio*' -or $desc -like '*DASMO*' -or $guid -eq '{769552b1-cc30-4a27-9d31-892afb20ac9e}') {
            try {
                Set-ItemProperty -Path $propsPath -Name '{a45c254e-df1c-4efd-8020-67d146a850e0},2' -Value 'DASMO Cyber Audio Link' -Force
                Set-ItemProperty -Path $propsPath -Name '{b3f8fa53-0004-438e-9002-2d4f3136ac5e},2' -Value 'DASMO Cyber Audio Link' -Force
                Set-ItemProperty -Path $propsPath -Name '{b3f8fa53-0004-438e-9003-51a46e139bfc},6' -Value 'DASMO CYBER CAPTURE' -Force
                $spkCount++
            } catch {
                Write-Warning "Could not update render props: $_"
            }
        }
    }
}

# 4. RESTART WINDOWS AUDIO SERVICE
Restart-Service AudioSrv -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  DASMO CYBER CAPTURE HARDWARE CONFIGURED SUCCESSFULLY!    " -ForegroundColor Cyan
Write-Host "  - Microphone: 'DASMO Virtual Microphone'                " -ForegroundColor Green
Write-Host "  - Audio Link: 'DASMO Cyber Audio Link'                  " -ForegroundColor Green
Write-Host "  - Provider:   'DASMO CYBER CAPTURE'                     " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan
