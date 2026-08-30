// DASMO CYBER CAPTURE // Windows Desktop Station Controller
let activeDevice = null;
let isVideoPaused = false;
let isMicMuted = false;
let isSpeakerOn = true;
let isDriverRunning = false;
let statusPollInterval = null;

// Initialize on DOM load
document.addEventListener('DOMContentLoaded', async () => {
    initNavigation();
    initWindowControls();
    initDeviceDiscovery();
    initHardwareControls();
    initSettings();
    checkDriverEnv();

    setInterval(updateClock, 1000);
    updateClock();
});

// Clock
function updateClock() {
    const el = document.getElementById('liveClock');
    if (el) el.innerText = new Date().toLocaleTimeString();
}

// Navigation Tabs
function initNavigation() {
    const navItems = document.querySelectorAll('.nav-item');
    navItems.forEach(item => {
        item.addEventListener('click', () => {
            navItems.forEach(n => n.classList.remove('active'));
            item.classList.add('active');

            const view = item.getAttribute('data-view');
            document.querySelectorAll('.view-panel').forEach(p => p.style.display = 'none');
            
            if (view === 'dashboard') document.getElementById('viewDashboard').style.display = 'flex';
            if (view === 'drivers') document.getElementById('viewDrivers').style.display = 'flex';
            if (view === 'settings') document.getElementById('viewSettings').style.display = 'flex';
        });
    });
}

// Window Titlebar Controls
function initWindowControls() {
    document.getElementById('btnMin')?.addEventListener('click', () => window.dasmoAPI?.minimizeWindow());
    document.getElementById('btnMax')?.addEventListener('click', () => window.dasmoAPI?.maximizeWindow());
    document.getElementById('btnClose')?.addEventListener('click', () => window.dasmoAPI?.closeWindow());

    document.getElementById('btnPopout')?.addEventListener('click', () => {
        if (activeDevice) {
            window.dasmoAPI?.popoutPreview(activeDevice.streamUrl);
        } else {
            alert('Please connect to a phone stream first.');
        }
    });

    document.getElementById('btnSnapshot')?.addEventListener('click', () => {
        if (activeDevice) {
            window.dasmoAPI?.openExternal(activeDevice.snapshotUrl);
        }
    });
}

// Auto-Discovery & Device Management
function initDeviceDiscovery() {
    const deviceListEl = document.getElementById('deviceList');

    if (window.dasmoAPI) {
        window.dasmoAPI.onDeviceFound((device) => {
            renderDiscoveredDevices();
            // If not connected to any device, auto-connect to the first found device
            if (!activeDevice) {
                connectToDevice(device);
            }
        });

        window.dasmoAPI.onDeviceLost(() => {
            renderDiscoveredDevices();
        });

        renderDiscoveredDevices();
    }

    document.getElementById('btnScanNow')?.addEventListener('click', async () => {
        if (window.dasmoAPI) {
            deviceListEl.innerHTML = '<div style="font-size: 11px; color: var(--cyan); padding: 8px;">Scanning Wi-Fi LAN...</div>';
            await window.dasmoAPI.scanNetworkNow();
            setTimeout(renderDiscoveredDevices, 1500);
        }
    });

    // Manual Modal
    const modal = document.getElementById('manualConnectModal');
    document.getElementById('btnManualConnect')?.addEventListener('click', () => modal.classList.add('open'));
    document.getElementById('btnCloseModal')?.addEventListener('click', () => modal.classList.remove('open'));
    document.getElementById('btnCancelManual')?.addEventListener('click', () => modal.classList.remove('open'));

    document.getElementById('btnSubmitManual')?.addEventListener('click', () => {
        const ip = document.getElementById('inputManualIp').value.trim();
        if (ip) {
            const dev = {
                id: `${ip}:8080`,
                ip: ip,
                port: 8080,
                name: 'DASMO Phone (Manual)',
                streamUrl: `http://${ip}:8080/video_feed`,
                snapshotUrl: `http://${ip}:8080/snapshot.jpg`,
                audioUrl: `http://${ip}:8080/audio_feed`,
                speakerUrl: `http://${ip}:8080/speaker_feed`,
                controlUrl: `http://${ip}:8080/api/control`
            };
            modal.classList.remove('open');
            connectToDevice(dev);
        }
    });
}

async function renderDiscoveredDevices() {
    const listEl = document.getElementById('deviceList');
    if (!listEl) return;

    const devices = window.dasmoAPI ? await window.dasmoAPI.getDiscoveredDevices() : [];

    if (devices.length === 0) {
        listEl.innerHTML = '<div style="font-size: 11px; color: var(--text-muted); font-family: var(--font-mono); padding: 8px;">No phone found yet. Ensure phone app is active on same Wi-Fi.</div>';
        return;
    }

    listEl.innerHTML = '';
    devices.forEach(dev => {
        const isCurrent = activeDevice && activeDevice.id === dev.id;
        const card = document.createElement('div');
        card.className = `device-card ${isCurrent ? 'active' : ''}`;
        card.innerHTML = `
            <div class="device-card-header">
                <span class="device-name">${dev.name}</span>
                <span class="device-badge ${isCurrent ? 'badge-connected' : 'badge-available'}">${isCurrent ? 'CONNECTED' : 'AIR LINK'}</span>
            </div>
            <div class="device-meta">
                <span>${dev.ip}:${dev.port}</span>
                <span>${isCurrent ? '● Active' : 'Tap to Connect'}</span>
            </div>
        `;
        card.addEventListener('click', () => connectToDevice(dev));
        listEl.appendChild(card);
    });
}

// Connect to Selected Device
function connectToDevice(device) {
    activeDevice = device;
    window.dasmoAPI?.setActiveDevice(device);

    document.getElementById('activePhoneName').innerText = device.name;
    document.getElementById('activePhoneIp').innerText = `${device.ip}:${device.port}`;

    const livePill = document.getElementById('livePill');
    livePill.className = 'live-indicator live-on';
    document.getElementById('pulseDot').style.background = 'var(--green)';
    document.getElementById('txtLiveStatus').innerText = 'AIR LINK ACTIVE';

    // Start Live Stream Viewfinder
    const videoImg = document.getElementById('videoFeedImg');
    videoImg.src = device.streamUrl;

    renderDiscoveredDevices();
    startStatusPolling();
}

// In-Call Hardware Controls & Sliders
function initHardwareControls() {
    // ⏸️ Pause Video (Privacy Slate)
    document.getElementById('btnPauseVideo')?.addEventListener('click', () => {
        isVideoPaused = !isVideoPaused;
        sendControl('pause_video', isVideoPaused ? 'true' : 'false');
        const btn = document.getElementById('btnPauseVideo');
        const lbl = document.getElementById('lblPauseVideo');
        if (isVideoPaused) {
            btn.classList.add('danger');
            lbl.innerText = 'Resume Video';
        } else {
            btn.classList.remove('danger');
            lbl.innerText = 'Pause Video';
        }
    });

    // 🎤 Mute Mic
    document.getElementById('btnMuteMic')?.addEventListener('click', () => {
        isMicMuted = !isMicMuted;
        sendControl('mute', isMicMuted ? 'true' : 'false');
        const btn = document.getElementById('btnMuteMic');
        const lbl = document.getElementById('lblMuteMic');
        if (isMicMuted) {
            btn.classList.add('danger');
            lbl.innerText = 'Unmute Mic';
        } else {
            btn.classList.remove('danger');
            lbl.innerText = 'Mute Mic';
        }
    });

    // 🔊 Speaker Output Toggle
    document.getElementById('btnSpeaker')?.addEventListener('click', () => {
        isSpeakerOn = !isSpeakerOn;
        sendControl('speaker', isSpeakerOn ? 'true' : 'false');
        document.getElementById('lblSpeaker').innerText = isSpeakerOn ? 'Speaker: ON' : 'Speaker: OFF';
    });

    // 🔄 Flip Camera
    document.getElementById('btnFlipCam')?.addEventListener('click', () => {
        sendControl('flip', '');
    });

    // ⚡ Torch Flash
    document.getElementById('btnTorch')?.addEventListener('click', () => {
        sendControl('torch', '');
    });

    // Sliders
    document.getElementById('sliderZoom')?.addEventListener('input', (e) => {
        const val = e.target.value;
        document.getElementById('valZoom').innerText = `${val}x`;
        document.getElementById('hudZoomLabel').innerText = `ZOOM: ${val}x`;
        sendControl('zoom', val);
    });

    document.getElementById('sliderGain')?.addEventListener('input', (e) => {
        const val = e.target.value;
        document.getElementById('valGain').innerText = `${val}x`;
        sendControl('gain', val);
    });

    document.getElementById('sliderSpeakerVol')?.addEventListener('input', (e) => {
        const val = e.target.value;
        document.getElementById('valSpeakerVol').innerText = `${Math.round(val * 100)}%`;
        sendControl('volume', val);
    });

    document.getElementById('btnLoudspeaker')?.addEventListener('click', () => {
        sendControl('routing', 'SPEAKERPHONE');
    });

    document.getElementById('btnEarpiece')?.addEventListener('click', () => {
        sendControl('routing', 'EARPIECE');
    });

    let isAudioRelayRunning = false;
    document.getElementById('btnStartAudioRelay')?.addEventListener('click', () => {
        if (!activeDevice) {
            alert('Please connect to your phone first.');
            return;
        }
        isAudioRelayRunning = !isAudioRelayRunning;
        const btn = document.getElementById('btnStartAudioRelay');
        const lbl = document.getElementById('lblAudioRelayBtn');

        if (isAudioRelayRunning) {
            window.dasmoAPI?.startAudioBridge(activeDevice.ip);
            btn.style.background = 'var(--cyan)';
            btn.style.color = '#000';
            lbl.innerText = 'Stop PC ➔ Phone Speaker Relay';
        } else {
            window.dasmoAPI?.stopAudioBridge();
            btn.style.background = 'transparent';
            btn.style.color = 'var(--cyan)';
            lbl.innerText = 'Start PC ➔ Phone Speaker Relay';
        }
    });

    // Virtual Camera Driver Button
    document.getElementById('btnStartDriver')?.addEventListener('click', () => {
        toggleVirtualCameraDriver();
    });
}

function sendControl(action, value = '') {
    if (!activeDevice) return;
    const url = `http://${activeDevice.ip}:${activeDevice.port}/api/control?action=${encodeURIComponent(action)}&value=${encodeURIComponent(value)}`;
    fetch(url).catch(() => {});
}

// Telemetry & Status Polling
function startStatusPolling() {
    if (statusPollInterval) clearInterval(statusPollInterval);

    statusPollInterval = setInterval(async () => {
        if (!activeDevice) return;
        try {
            const res = await fetch(`http://${activeDevice.ip}:${activeDevice.port}/status.json`, { cache: 'no-store' });
            if (res.ok) {
                const data = await res.json();
                document.getElementById('telFps').innerText = `${data.fps || 30.0}`;
                document.getElementById('telBitrate').innerText = `${data.bitrate_kbps || 1850} kbps`;
                document.getElementById('telClients').innerText = `${data.clients || 1} PC`;
                
                // Simulate VU meter
                const vu = isMicMuted ? 0 : Math.min(100, Math.max(10, Math.random() * 85));
                document.getElementById('vuMeterBar').style.width = `${vu}%`;
                document.getElementById('txtMicLevel').innerText = isMicMuted ? 'MUTED' : `-${Math.round(60 - (vu * 0.6))} dB`;
            }
        } catch (_) {}
    }, 1000);
}

// Virtual Camera Driver Bridge Management
async function checkDriverEnv() {
    const envText = document.getElementById('envStatusText');
    if (!window.dasmoAPI) return;

    const env = await window.dasmoAPI.checkDriverEnvironment();
    if (env.hasPython && env.hasPackages) {
        envText.innerText = '✓ Python & pyvirtualcam installed and ready!';
        envText.style.color = 'var(--green)';
    } else if (env.hasPython) {
        envText.innerText = 'Python detected, dependencies needed (Click Install below)';
        envText.style.color = 'var(--amber)';
    } else {
        envText.innerText = 'Python not detected in PATH. Install Python from python.org';
        envText.style.color = 'var(--red)';
    }

    document.getElementById('btnInstallDeps')?.addEventListener('click', async () => {
        envText.innerText = 'Installing opencv-python & pyvirtualcam (Please wait)...';
        try {
            await window.dasmoAPI.installDriverDependencies();
            envText.innerText = '✓ Dependencies installed successfully!';
            envText.style.color = 'var(--green)';
        } catch (e) {
            envText.innerText = `Installation error: ${e.message}`;
            envText.style.color = 'var(--red)';
        }
    });

    document.getElementById('btnInstallAudioDriver')?.addEventListener('click', () => {
        window.dasmoAPI?.installAudioDriver();
    });

    window.dasmoAPI.onDriverStatusChange((data) => {
        const badge = document.getElementById('driverStatusBadge');
        if (data.status === 'running') {
            badge.innerText = 'Virtual Cam: ACTIVE ("DASMO CYBER CAPTURE")';
            badge.style.color = 'var(--green)';
            document.getElementById('lblDriverBtn').innerText = 'Stop Virtual Cam';
            isDriverRunning = true;
        } else {
            badge.innerText = 'Virtual Cam: Stopped';
            badge.style.color = 'var(--text-muted)';
            document.getElementById('lblDriverBtn').innerText = 'Start Virtual Cam';
            isDriverRunning = false;
        }
    });
}

function toggleVirtualCameraDriver() {
    if (!activeDevice) {
        alert('Please connect to your phone first.');
        return;
    }

    if (!isDriverRunning) {
        window.dasmoAPI?.startVirtualCamera(activeDevice.ip);
    } else {
        window.dasmoAPI?.stopVirtualCamera();
    }
}

// Settings & Preferences
async function initSettings() {
    const chkAutoStart = document.getElementById('chkAutoStart');
    if (chkAutoStart && window.dasmoAPI) {
        const autoStart = await window.dasmoAPI.getAutoStart();
        chkAutoStart.checked = autoStart;
        chkAutoStart.addEventListener('change', async (e) => {
            await window.dasmoAPI.setAutoStart(e.target.checked);
        });
    }

    document.getElementById('selResolution')?.addEventListener('change', (e) => {
        sendControl('resolution', e.target.value);
    });

    // Update Checker
    document.getElementById('btnCheckDesktopUpdates')?.addEventListener('click', () => {
        checkDesktopUpdates(true);
    });

    document.getElementById('btnCloseUpdateModal')?.addEventListener('click', () => {
        document.getElementById('desktopUpdateModal')?.classList.remove('open');
    });

    document.getElementById('btnRemindLater')?.addEventListener('click', () => {
        document.getElementById('desktopUpdateModal')?.classList.remove('open');
    });

    // Automatic check on start
    setTimeout(() => {
        checkDesktopUpdates(false);
    }, 2500);
}

let latestUpdateData = null;

async function checkDesktopUpdates(isManual = false) {
    const versionText = document.getElementById('desktopVersionText');
    if (isManual && versionText) {
        versionText.innerText = 'Checking GitHub Releases...';
    }

    if (!window.dasmoAPI) return;

    try {
        const updateInfo = await window.dasmoAPI.checkForUpdates();
        if (updateInfo && updateInfo.isUpdateAvailable) {
            latestUpdateData = updateInfo;
            document.getElementById('modalLatestVer').innerText = `LATEST: v${updateInfo.latestVersion}`;
            document.getElementById('modalReleaseTitle').innerText = updateInfo.releaseTitle || 'DASMO Cyber Capture Update';
            document.getElementById('modalReleaseNotes').innerText = updateInfo.releaseNotes || 'Bug fixes and performance enhancements.';
            
            document.getElementById('btnDownloadMsi').onclick = () => {
                const url = updateInfo.msiUrl || updateInfo.exeUrl || updateInfo.releaseUrl;
                window.dasmoAPI.openExternal(url);
                document.getElementById('desktopUpdateModal')?.classList.remove('open');
            };

            document.getElementById('desktopUpdateModal')?.classList.add('open');
            if (versionText) {
                versionText.innerText = `Update Available: v${updateInfo.latestVersion}`;
                versionText.style.color = 'var(--green)';
            }
        } else {
            if (versionText) {
                versionText.innerText = `Current: v1.0.0 (Latest)`;
                versionText.style.color = 'var(--text-muted)';
            }
            if (isManual) {
                alert('You are already on the latest version of DASMO CYBER CAPTURE (v1.0.0)!');
            }
        }
    } catch (e) {
        if (isManual) {
            alert('Could not check for updates. Please check your internet connection.');
        }
    }
}
