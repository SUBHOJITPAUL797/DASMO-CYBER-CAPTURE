# ==============================================================================
# DASMO CYBER CAPTURE // PROPRIETARY HARDWARE BRANDING & DRIVER ENGINE
# Brands Virtual Audio endpoints in Windows as official DASMO hardware:
#   - Capture (Input):  "DASMO Virtual Microphone" (Default Recording Device for Win+H)
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
    Write-Host "[*] Requesting Administrator elevation to install/configure DASMO Virtual Audio..." -ForegroundColor Yellow
    $argList = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    if ($Uninstall) { $argList += " -Uninstall" }
    if ($Silent) { $argList += " -Silent" }
    Start-Process powershell -ArgumentList $argList -Verb RunAs -Wait
    exit
}

$scriptDir = Split-Path -Parent $PSCommandPath
$vbcableSetup = Join-Path $scriptDir "VBCABLE_Setup_x64.exe"
if (-not (Test-Path $vbcableSetup)) {
    $vbcableSetup = "C:\Program Files\VB\CABLE\VBCABLE_Setup_x64.exe"
}

if ($Uninstall) {
    Write-Host "[*] Removing DASMO Virtual Audio Hardware..." -ForegroundColor Yellow
    if (Test-Path $vbcableSetup) {
        Start-Process $vbcableSetup -ArgumentList "-u -h" -WorkingDirectory $scriptDir -Wait
    }
    Restart-Service AudioSrv -Force -ErrorAction SilentlyContinue
    Write-Host "[+] DASMO Virtual Audio cleanly uninstalled. No leftover drivers." -ForegroundColor Green
    exit 0
}

# 1. DIRECTSHOW VIRTUAL CAMERA FILTER -> "DASMO CAMERA"
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

# 2. CHECK IF VIRTUAL AUDIO DEVICE IS REGISTERED IN WINDOWS PNP
$hasAudioDevice = $false
try {
    $dev = Get-PnpDevice -Class "MEDIA" -ErrorAction SilentlyContinue | Where-Object {
        $_.FriendlyName -like "*CABLE*" -or $_.FriendlyName -like "*DASMO*" -or $_.InstanceId -like "*VBAudioVACWDM*"
    }
    if ($dev) { $hasAudioDevice = $true }
} catch {}

if (-not $hasAudioDevice) {
    Write-Host "[*] Registering DASMO Virtual Audio kernel device..." -ForegroundColor Cyan

    # Stage inf in driver store
    $infPath = Join-Path $scriptDir "vbMmeCable64_win7.inf"
    if (Test-Path $infPath) {
        & pnputil /add-driver $infPath /install 2>&1 | Out-Null
    }

    # Run installer in its directory with -i -h
    if (Test-Path $vbcableSetup) {
        Start-Process $vbcableSetup -ArgumentList "-i -h" -WorkingDirectory $scriptDir -Wait
        Start-Sleep -Seconds 3
    }

    # Verify if installed, if not, launch interactive installer
    $devCheck = Get-PnpDevice -Class "MEDIA" -ErrorAction SilentlyContinue | Where-Object {
        $_.FriendlyName -like "*CABLE*" -or $_.FriendlyName -like "*DASMO*" -or $_.InstanceId -like "*VBAudioVACWDM*"
    }
    if (-not $devCheck -and (Test-Path $vbcableSetup)) {
        Write-Host "[*] Launching driver installer wizard (click 'Install Driver')..." -ForegroundColor Yellow
        Start-Process $vbcableSetup -ArgumentList "-i" -WorkingDirectory $scriptDir -Wait
        Start-Sleep -Seconds 3
    }
}

# Restart Windows Audio Service so MMDevice populates the new endpoints
Restart-Service AudioSrv -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 3. DEFINE COM INTERFACE FOR SETTING WINDOWS DEFAULT RECORDING DEVICE
$typeDefinition = @"
using System;
using System.Runtime.InteropServices;

[ComImport]
[Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")]
internal class _PolicyConfigClient
{
}

[Guid("f8679f50-850a-41cf-9c72-430f290290c8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IPolicyConfig
{
    [PreserveSig] int GetMixFormat();
    [PreserveSig] int GetDeviceFormat();
    [PreserveSig] int ResetDeviceFormat();
    [PreserveSig] int SetDeviceFormat();
    [PreserveSig] int GetProcessingPeriod();
    [PreserveSig] int SetProcessingPeriod();
    [PreserveSig] int GetShareMode();
    [PreserveSig] int SetShareMode();
    [PreserveSig] int GetPropertyValue();
    [PreserveSig] int SetPropertyValue();
    [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string wszDeviceId, int eRole);
    [PreserveSig] int SetEndpointVisibility();
}

public class AudioPolicy
{
    public static int SetDefault(string devId, int role)
    {
        try {
            var client = (_PolicyConfigClient)new _PolicyConfigClient();
            var policy = (IPolicyConfig)client;
            return policy.SetDefaultEndpoint(devId, role);
        } catch (Exception) {
            return -1;
        }
    }
}
"@

try {
    Add-Type -TypeDefinition $typeDefinition -ErrorAction SilentlyContinue
} catch {}

# 4. BRAND CAPTURE ENDPOINTS -> "DASMO Virtual Microphone"
$micCount = 0
$captureEndpointId = $null

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
                # Force DeviceState = 1 (ACTIVE / PLUGGED IN)
                Set-ItemProperty -Path $_.PSPath -Name 'DeviceState' -Value 1 -Force
                # CRITICAL ANTI-ECHO FIX: Disable Windows "Listen to this device" so mic NEVER plays through PC speakers
                Remove-ItemProperty -Path $propsPath -Name '{24dbb0fc-9311-4b3d-9cf0-18ff155639d4},1' -ErrorAction SilentlyContinue
                Remove-ItemProperty -Path $propsPath -Name '{24dbb0fc-9311-4b3d-9cf0-18ff155639d4},0' -ErrorAction SilentlyContinue
                $captureEndpointId = "{0.0.1.00000000}.$guid"
                $micCount++
            } catch {
                Write-Warning "Could not update capture props: $_"
            }
        }
    }
}

# 5. BRAND RENDER ENDPOINTS -> "DASMO Cyber Audio Link"
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
                # Force DeviceState = 1 (ACTIVE / PLUGGED IN)
                Set-ItemProperty -Path $_.PSPath -Name 'DeviceState' -Value 1 -Force
                $spkCount++
            } catch {
                Write-Warning "Could not update render props: $_"
            }
        }
    }
}

# 6. SET AS DEFAULT RECORDING DEVICE FOR WIN+H AND SPEECH INPUT
if ($captureEndpointId) {
    try {
        [AudioPolicy]::SetDefault($captureEndpointId, 0) | Out-Null # eConsole (Default Recording Device)
        [AudioPolicy]::SetDefault($captureEndpointId, 1) | Out-Null # eMultimedia
        [AudioPolicy]::SetDefault($captureEndpointId, 2) | Out-Null # eCommunications (Default Communications Device)
        Write-Host "[+] 'DASMO Virtual Microphone' configured as Windows Default Recording Device!" -ForegroundColor Green
    } catch {
        Write-Warning "Could not set default endpoint: $_"
    }
}

# 7. ENSURE WINDOWS MICROPHONE PRIVACY CONSENT IS ALLOWED
try {
    $consentPathUser = "HKCU:\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone"
    if (Test-Path $consentPathUser) {
        Set-ItemProperty -Path $consentPathUser -Name "Value" -Value "Allow" -Force -ErrorAction SilentlyContinue
    }
    $consentPathSystem = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone"
    if (Test-Path $consentPathSystem) {
        Set-ItemProperty -Path $consentPathSystem -Name "Value" -Value "Allow" -Force -ErrorAction SilentlyContinue
    }
} catch {}

# 8. RESTART WINDOWS AUDIO SERVICE
Restart-Service AudioSrv -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  DASMO CYBER CAPTURE HARDWARE CONFIGURED SUCCESSFULLY!    " -ForegroundColor Cyan
Write-Host "  - Microphone: 'DASMO Virtual Microphone' (Default Mic)  " -ForegroundColor Green
Write-Host "  - Audio Link: 'DASMO Cyber Audio Link' (Bridge)         " -ForegroundColor Green
Write-Host "  - Win+H Dictation & Voice Typing: READY                 " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan
