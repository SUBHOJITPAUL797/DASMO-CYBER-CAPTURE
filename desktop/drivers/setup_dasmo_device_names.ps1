# ==============================================================================
# DASMO CYBER CAPTURE // 1-CLICK WINDOWS HARDWARE BRANDING ENGINE
# Sets friendly device names in Windows:
#   1. Video Capture -> "DASMO CAMERA"
#   2. Virtual Mic   -> "DASMO MIC"
#   3. Phone Speaker -> "DASMO SPEAKER"
# ==============================================================================

# Ensure Running as Administrator
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host '[*] Requesting Administrator elevation to rename Windows devices...' -ForegroundColor Yellow
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File "$PSCommandPath"" -Verb RunAs
    exit
}

Write-Host '==========================================================' -ForegroundColor Cyan
Write-Host '  DASMO CYBER CAPTURE // WINDOWS DEVICE BRANDING ENGINE    ' -ForegroundColor Cyan
Write-Host '==========================================================' -ForegroundColor Cyan
Write-Host ''

# 1. RENAME DIRECTSHOW VIRTUAL CAMERA -> "DASMO CAMERA"
$camPaths = @(
    'HKLM:\SOFTWARE\Classes\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{5C2CD55C-92AD-4999-8666-912BD3E70010}',
    'HKLM:\SOFTWARE\WOW6432Node\Classes\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{5C2CD55C-92AD-4999-8666-912BD3E70010}'
)

$camSuccess = $false
foreach ($cp in $camPaths) {
    if (Test-Path $cp) {
        try {
            Set-ItemProperty -Path $cp -Name 'FriendlyName' -Value 'DASMO CAMERA' -Force
            $camSuccess = $true
        } catch {
            Write-Warning "Could not update $($cp): $($_)"
        }
    }
}

if ($camSuccess) {
    Write-Host '[+] Camera Device Name: "DASMO CAMERA"' -ForegroundColor Green
} else {
    Write-Host '[*] Note: Register virtual camera first using install_dasmo_camera.bat' -ForegroundColor Yellow
}

# 2. RENAME VIRTUAL MIC (CAPTURE) -> "DASMO MIC"
$micCount = 0
Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture' -ErrorAction SilentlyContinue | ForEach-Object {
    $propsPath = Join-Path $_.PSPath 'Properties'
    if (Test-Path $propsPath) {
        $props = Get-ItemProperty $propsPath
        $desc = [string]$props.'{a45c254e-df1c-4efd-8020-67d146a850e0},2'
        $friendly = [string]$props.'{b3f8fa53-0004-438e-9002-2d4f3136ac5e},2'
        
        if ($desc -like '*CABLE Output*' -or $friendly -like '*CABLE Output*' -or $desc -like '*DASMO MIC*' -or $friendly -like '*DASMO MIC*') {
            try {
                Set-ItemProperty -Path $propsPath -Name '{b3f8fa53-0004-438e-9002-2d4f3136ac5e},2' -Value 'DASMO MIC' -Force
                $micCount++
            } catch {
                Write-Warning "Failed setting mic name: $($_)"
            }
        }
    }
}

if ($micCount -gt 0) {
    Write-Host '[+] Microphone Device Name: "DASMO MIC"' -ForegroundColor Green
} else {
    Write-Host '[*] CABLE Output not detected. Please ensure VB-Cable is installed.' -ForegroundColor Yellow
}

# 3. RENAME PHONE SPEAKER (RENDER) -> "DASMO SPEAKER"
$spkCount = 0
Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render' -ErrorAction SilentlyContinue | ForEach-Object {
    $propsPath = Join-Path $_.PSPath 'Properties'
    if (Test-Path $propsPath) {
        $props = Get-ItemProperty $propsPath
        $desc = [string]$props.'{a45c254e-df1c-4efd-8020-67d146a850e0},2'
        $friendly = [string]$props.'{b3f8fa53-0004-438e-9002-2d4f3136ac5e},2'
        
        if ($desc -like '*CABLE Input*' -or $friendly -like '*CABLE Input*' -or $desc -like '*DASMO SPEAKER*' -or $friendly -like '*DASMO SPEAKER*') {
            try {
                Set-ItemProperty -Path $propsPath -Name '{b3f8fa53-0004-438e-9002-2d4f3136ac5e},2' -Value 'DASMO SPEAKER' -Force
                $spkCount++
            } catch {
                Write-Warning "Failed setting speaker name: $($_)"
            }
        }
    }
}

if ($spkCount -gt 0) {
    Write-Host '[+] Speaker Device Name: "DASMO SPEAKER"' -ForegroundColor Green
} else {
    Write-Host '[*] CABLE Input not detected. Please ensure VB-Cable is installed.' -ForegroundColor Yellow
}

# 4. RESTART WINDOWS AUDIO SERVICE
Write-Host "
[*] Refreshing Windows Audio Service..." -ForegroundColor Gray
try {
    Restart-Service AudioSrv -Force -ErrorAction SilentlyContinue
    Write-Host '[+] Windows Audio Service refreshed!' -ForegroundColor Green
} catch {
    Write-Warning 'Could not restart AudioSrv automatically. Devices will refresh on next app launch.'
}

Write-Host "
==========================================================" -ForegroundColor Cyan
Write-Host '  SUCCESS! Your hardware devices are now named:           ' -ForegroundColor Cyan
Write-Host '  - Camera:     "DASMO CAMERA"                            ' -ForegroundColor White
Write-Host '  - Microphone: "DASMO MIC"                               ' -ForegroundColor White
Write-Host '  - Speaker:    "DASMO SPEAKER"                           ' -ForegroundColor White
Write-Host '==========================================================' -ForegroundColor Cyan
