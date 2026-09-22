// DASMO CYBER CAPTURE // Windows Desktop Station Controller
let activeDevice = null;
let isVideoPaused = false;
let isMicMuted = false;
let isSpeakerOn = true;
let isDriverRunning = false;
let isAudioRelayRunning = false;
let statusPollInterval = null;
let streamViewer = null;

let currentCamBgMode = 'raw';
let studioSelectedBgColor = '#ffffff';
let isVirtualCamFrozen = false;
let studioTransparentFg = null;

function hexToRgb(hex) {
    const clean = hex.replace('#', '');
    const num = parseInt(clean, 16);
    return {
        r: (num >> 16) & 255,
        g: (num >> 8) & 255,
        b: num & 255
    };
}

function rgbToHex(r, g, b) {
    return '#' + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1).toUpperCase();
}

function setTargetBgColor(hex, label) {
    studioSelectedBgColor = hex;

    const badge = document.getElementById('badgeWebcamBg');
    if (badge) {
        badge.innerText = `TARGET: ${label || hex.toUpperCase()}`;
        badge.style.color = '#00e5ff';
    }

    // Update active button state
    document.querySelectorAll('.portal-bg-toolbar-actions .bg-mode-btn').forEach(b => {
        if (!b.classList.contains('btn-capture-studio')) {
            b.classList.remove('active');
        }
    });

    const cleanHex = hex.toLowerCase();
    if (cleanHex === '#ffffff') {
        document.getElementById('btnBgWhite')?.classList.add('active');
    } else if (cleanHex === '#1e88e5' || cleanHex === '#2196f3') {
        document.getElementById('btnBgBlue')?.classList.add('active');
    } else if (cleanHex === '#e53935') {
        document.getElementById('btnBgRed')?.classList.add('active');
    }

    // Also sync color pills in studio modal if user opens modal
    document.querySelectorAll('.studio-color-pill').forEach(pill => {
        if ((pill.getAttribute('data-color') || '').toLowerCase() === cleanHex) {
            pill.classList.add('active');
        } else {
            pill.classList.remove('active');
        }
    });

    // If modal already has a captured studio photo, re-render & re-freeze virtual camera
    if (studioTransparentFg) {
        renderStudioCanvas();
        syncVirtualCamFreeze();
    }
}

function initWebcamBgControls() {
    document.getElementById('btnBgWhite')?.addEventListener('click', () => {
        setTargetBgColor('#ffffff', 'PURE WHITE (GOVT)');
    });

    document.getElementById('btnBgBlue')?.addEventListener('click', () => {
        setTargetBgColor('#1e88e5', 'PASSPORT BLUE');
    });

    document.getElementById('btnBgRed')?.addEventListener('click', () => {
        setTargetBgColor('#e53935', 'VISA RED');
    });

    const picker = document.getElementById('inputCustomBgColor');
    const hexLbl = document.getElementById('lblCustomColorHex');
    picker?.addEventListener('input', (e) => {
        if (hexLbl) hexLbl.innerText = e.target.value.toUpperCase();
    });

    document.getElementById('btnApplyCustomBg')?.addEventListener('click', () => {
        const hex = picker ? picker.value : '#00E5FF';
        setTargetBgColor(hex, `CUSTOM (${hex.toUpperCase()})`);
    });
}

// ==============================================================================
// STUDIO AI PHOTO CAPTURE & MATTING CONTROLLER (v1.6.0)
// High-Resolution Snapshot -> Deep Neural Model -> Studio-Clean Cutout
// DirectShow Virtual Camera Freeze: Broadcasts Studio Photo to Portals!
// ==============================================================================

function renderStudioCanvas() {
    const resultCanvas = document.getElementById('studioResultCanvas');
    if (!studioTransparentFg || !resultCanvas) return;
    const ctx = resultCanvas.getContext('2d');
    if (!ctx) return;

    const w = studioTransparentFg.naturalWidth || studioTransparentFg.width;
    const h = studioTransparentFg.naturalHeight || studioTransparentFg.height;
    resultCanvas.width = w;
    resultCanvas.height = h;

    ctx.clearRect(0, 0, w, h);

    if (studioSelectedBgColor !== 'transparent') {
        ctx.fillStyle = studioSelectedBgColor;
        ctx.fillRect(0, 0, w, h);
    }

    ctx.drawImage(studioTransparentFg, 0, 0);
}

function syncVirtualCamFreeze() {
    const resultCanvas = document.getElementById('studioResultCanvas');
    if (!resultCanvas || !studioTransparentFg) return;
    try {
        let exportCanvas = resultCanvas;
        if (studioSelectedBgColor === 'transparent') {
            exportCanvas = document.createElement('canvas');
            exportCanvas.width = resultCanvas.width;
            exportCanvas.height = resultCanvas.height;
            const eCtx = exportCanvas.getContext('2d');
            eCtx.fillStyle = '#ffffff';
            eCtx.fillRect(0, 0, exportCanvas.width, exportCanvas.height);
            eCtx.drawImage(resultCanvas, 0, 0);
        }
        const dataUrl = exportCanvas.toDataURL('image/jpeg', 0.95);
        window.dasmoAPI?.freezeVirtualCamera(dataUrl);
        isVirtualCamFrozen = true;

        const banner = document.getElementById('studioVirtualCamBanner');
        const resumeBtn = document.getElementById('btnResumeLiveCam');
        if (banner) {
            banner.style.display = 'flex';
            const label = banner.querySelector('span');
            if (label) label.innerText = '📡 DASMO CAMERA: STREAMING THIS PHOTO';
        }
        if (resumeBtn) {
            resumeBtn.innerText = '▶ Unfreeze';
            resumeBtn.style.borderColor = 'var(--cyan)';
            resumeBtn.style.color = 'var(--cyan)';
        }
    } catch (e) {
        console.warn('[Studio] Virtual camera freeze error:', e);
    }
}

function unfreezeVirtualCam() {
    window.dasmoAPI?.unfreezeVirtualCamera();
    isVirtualCamFrozen = false;
    const banner = document.getElementById('studioVirtualCamBanner');
    const resumeBtn = document.getElementById('btnResumeLiveCam');
    if (banner) {
        const label = banner.querySelector('span');
        if (label) label.innerText = '📷 DASMO CAMERA: LIVE FEED RESUMED';
    }
    if (resumeBtn) {
        resumeBtn.innerText = '⏸ Freeze';
        resumeBtn.style.borderColor = 'var(--green)';
        resumeBtn.style.color = 'var(--green)';
    }
}

function initStudioCapture() {
    const btnStudioCapture = document.getElementById('btnStudioCapture');
    const btnSnapshot = document.getElementById('btnSnapshot');
    const modal = document.getElementById('studioCaptureModal');
    const btnClose = document.getElementById('btnCloseStudioModal');
    const loadingOverlay = document.getElementById('studioLoadingOverlay');
    const resultCanvas = document.getElementById('studioResultCanvas');
    const modelSelect = document.getElementById('selectStudioModel');
    const statusEl = document.getElementById('studioModalStatus');

    const colorPills = document.querySelectorAll('.studio-color-pill');
    const customColorInput = document.getElementById('studioCustomColorInput');
    const customHexLabel = document.getElementById('studioCustomHex');
    const btnApplyCustomColor = document.getElementById('btnApplyStudioCustomColor');
    const btnResumeLiveCam = document.getElementById('btnResumeLiveCam');

    const btnCopy = document.getElementById('btnCopyStudioImage');
    const btnSaveJpg = document.getElementById('btnSaveStudioJpg');
    const btnSavePng = document.getElementById('btnSaveStudioPng');
    const btnRetake = document.getElementById('btnRetakeStudioPhoto');

    async function triggerCapture() {
        const frameDataUrl = streamViewer ? streamViewer.getLatestFrameDataUrl() : null;
        if (!frameDataUrl) {
            alert('No camera feed active yet. Please connect your phone via Air Link first.');
            return;
        }

        if (modal) modal.style.display = 'flex';
        if (loadingOverlay) loadingOverlay.style.display = 'flex';
        if (statusEl) {
            statusEl.innerText = '🎨 DEEP AI PORTRAIT MATTING IN PROGRESS... (~2-3 sec)';
            statusEl.style.color = '#00e5ff';
        }

        const model = modelSelect ? modelSelect.value : 'isnet-general-use';

        try {
            const res = await window.dasmoAPI.removeBackground({
                image: frameDataUrl,
                model: model
            });

            if (res && res.success && res.image) {
                const img = new Image();
                img.onload = () => {
                    studioTransparentFg = img;
                    if (loadingOverlay) loadingOverlay.style.display = 'none';
                    if (statusEl) {
                        statusEl.innerText = `✓ STUDIO MATTING COMPLETE (${res.time_sec}s · ${res.width}×${res.height})`;
                        statusEl.style.color = '#00e676';
                    }
                    renderStudioCanvas();
                    syncVirtualCamFreeze();
                };
                img.src = res.image;
            } else {
                if (loadingOverlay) loadingOverlay.style.display = 'none';
                if (statusEl) {
                    statusEl.innerText = 'PROCESSING ERROR';
                    statusEl.style.color = 'var(--red)';
                }
                alert('Background removal failed: ' + (res?.error || 'Unknown error'));
            }
        } catch (err) {
            if (loadingOverlay) loadingOverlay.style.display = 'none';
            if (statusEl) {
                statusEl.innerText = 'ERROR';
                statusEl.style.color = 'var(--red)';
            }
            alert('Error running AI background removal: ' + err.message);
        }
    }

    btnStudioCapture?.addEventListener('click', triggerCapture);
    btnSnapshot?.addEventListener('click', triggerCapture);

    btnResumeLiveCam?.addEventListener('click', () => {
        if (isVirtualCamFrozen) {
            unfreezeVirtualCam();
        } else {
            syncVirtualCamFreeze();
        }
    });

    btnClose?.addEventListener('click', () => {
        unfreezeVirtualCam();
        if (modal) modal.style.display = 'none';
    });

    btnRetake?.addEventListener('click', () => {
        triggerCapture();
    });

    // ESC key closes modal
    window.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && modal && modal.style.display !== 'none') {
            unfreezeVirtualCam();
            modal.style.display = 'none';
        }
    });

    // Background color pills
    colorPills.forEach(pill => {
        pill.addEventListener('click', () => {
            colorPills.forEach(p => p.classList.remove('active'));
            pill.classList.add('active');
            studioSelectedBgColor = pill.getAttribute('data-color') || '#ffffff';
            renderStudioCanvas();
            syncVirtualCamFreeze();
        });
    });

    // Custom color input
    if (customColorInput) {
        customColorInput.addEventListener('input', (e) => {
            if (customHexLabel) customHexLabel.innerText = e.target.value.toUpperCase();
        });
    }

    btnApplyCustomColor?.addEventListener('click', () => {
        colorPills.forEach(p => p.classList.remove('active'));
        studioSelectedBgColor = customColorInput ? customColorInput.value : '#00e5ff';
        renderStudioCanvas();
        syncVirtualCamFreeze();
    });

    // Model selection change
    modelSelect?.addEventListener('change', () => {
        if (modal && modal.style.display !== 'none' && studioTransparentFg) {
            triggerCapture();
        }
    });

    // Copy to clipboard
    btnCopy?.addEventListener('click', async () => {
        if (!resultCanvas) return;
        const dataUrl = resultCanvas.toDataURL('image/png');
        try {
            const res = await window.dasmoAPI.copyImageToClipboard(dataUrl);
            if (res && res.success) {
                const origHtml = btnCopy.innerHTML;
                btnCopy.innerHTML = '<span>✓ Copied! Ready to Paste (Ctrl+V)</span>';
                btnCopy.style.background = '#00e676';
                btnCopy.style.color = '#000';
                setTimeout(() => {
                    btnCopy.innerHTML = origHtml;
                    btnCopy.style.background = 'linear-gradient(135deg, #00e5ff 0%, #00b0ff 100%)';
                    btnCopy.style.color = '#000';
                }, 2500);
            } else {
                alert('Could not copy image: ' + (res?.error || 'Unknown'));
            }
        } catch (e) {
            alert('Copy error: ' + e.message);
        }
    });

    // Save JPG
    btnSaveJpg?.addEventListener('click', async () => {
        if (!resultCanvas) return;
        let dataUrl;
        if (studioSelectedBgColor === 'transparent') {
            const off = document.createElement('canvas');
            off.width = resultCanvas.width;
            off.height = resultCanvas.height;
            const offCtx = off.getContext('2d');
            offCtx.fillStyle = '#ffffff';
            offCtx.fillRect(0, 0, off.width, off.height);
            offCtx.drawImage(resultCanvas, 0, 0);
            dataUrl = off.toDataURL('image/jpeg', 0.95);
        } else {
            dataUrl = resultCanvas.toDataURL('image/jpeg', 0.95);
        }

        const res = await window.dasmoAPI.saveImageToFile({
            dataUrl,
            defaultName: `DASMO_STUDIO_PHOTO_${Date.now()}.jpg`
        });
        if (res && res.success) {
            const orig = btnSaveJpg.innerText;
            btnSaveJpg.innerText = '✓ Saved JPG!';
            setTimeout(() => { btnSaveJpg.innerText = orig; }, 2000);
        }
    });

    // Save PNG
    btnSavePng?.addEventListener('click', async () => {
        if (!resultCanvas) return;
        const dataUrl = resultCanvas.toDataURL('image/png');
        const res = await window.dasmoAPI.saveImageToFile({
            dataUrl,
            defaultName: `DASMO_STUDIO_PHOTO_${Date.now()}.png`
        });
        if (res && res.success) {
            const orig = btnSavePng.innerText;
            btnSavePng.innerText = '✓ Saved PNG!';
            setTimeout(() => { btnSavePng.innerText = orig; }, 2000);
        }
    });
}

function updateAudioRelayUI(running) {
    isAudioRelayRunning = running;
    const btn = document.getElementById('btnStartAudioRelay');
    const lbl = document.getElementById('lblAudioRelayBtn');
    if (!btn || !lbl) return;
    if (running) {
        btn.style.background = 'var(--cyan)';
        btn.style.color = '#000';
        lbl.innerText = 'Stop PC ➔ Phone Speaker Relay';
    } else {
        btn.style.background = 'transparent';
        btn.style.color = 'var(--cyan)';
        lbl.innerText = 'Start PC ➔ Phone Speaker Relay';
    }
}

// Initialize on DOM load
document.addEventListener('DOMContentLoaded', async () => {
    const canvas = document.getElementById('videoCanvas');
    const img = document.getElementById('videoFeedImg');
    if (canvas && img) {
        streamViewer = new UltraLowLatencyStreamViewer(canvas, img);
    }

    initNavigation();
    initWindowControls();
    initDeviceDiscovery();
    initHardwareControls();
    initWebcamBgControls();
    initStudioCapture();
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

    document.getElementById('btnSnapshot')?.addEventListener('click', async () => {
        if (!activeDevice) {
            alert('Please connect to a phone first.');
            return;
        }
        const btn = document.getElementById('btnSnapshot');
        const origText = btn ? btn.innerText : '📸 Snapshot';
        if (btn) btn.innerText = '📸 Saving...';
        try {
            if (currentCamBgMode === 'color' && streamViewer?.canvas) {
                // Export directly from rendered canvas with white/custom background
                const dataUrl = streamViewer.canvas.toDataURL('image/jpeg', 0.95);
                const a = document.createElement('a');
                a.href = dataUrl;
                a.download = `DASMO_PORTAL_PHOTO_${new Date().toISOString().replace(/[:.]/g, '-')}.jpg`;
                document.body.appendChild(a);
                a.click();
                a.remove();
                if (btn) btn.innerText = '✓ Portal Photo Saved!';
            } else {
                const snapUrl = `${activeDevice.snapshotUrl}?_t=${Date.now()}`;
                const res = await fetch(snapUrl);
                if (!res.ok) throw new Error('Fetch failed');
                const blob = await res.blob();
                const blobUrl = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = blobUrl;
                a.download = `DASMO_SNAPSHOT_${new Date().toISOString().replace(/[:.]/g, '-')}.jpg`;
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(() => URL.revokeObjectURL(blobUrl), 10000);
                if (btn) btn.innerText = '✓ Saved!';
            }
        } catch (_) {
            window.dasmoAPI?.openExternal(activeDevice.snapshotUrl);
            if (btn) btn.innerText = '✓ Opened!';
        }
        setTimeout(() => { if (btn) btn.innerText = origText; }, 2000);
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
    const statusEl = document.getElementById('manualStatusText');

    document.getElementById('btnManualConnect')?.addEventListener('click', () => {
        if (statusEl) statusEl.innerText = '';
        modal.classList.add('open');
    });
    document.getElementById('btnCloseModal')?.addEventListener('click', () => modal.classList.remove('open'));
    document.getElementById('btnCancelManual')?.addEventListener('click', () => modal.classList.remove('open'));

    document.getElementById('btnSubmitManual')?.addEventListener('click', async () => {
        const rawIp = document.getElementById('inputManualIp').value.trim();
        const rawPort = document.getElementById('inputManualPort')?.value.trim() || '8080';
        const submitBtn = document.getElementById('btnSubmitManual');

        if (!rawIp) {
            if (statusEl) statusEl.innerHTML = '<span style="color: var(--red);">Please enter an IP address.</span>';
            return;
        }

        const { ip, port } = parseManualInput(rawIp, rawPort);
        if (!ip) {
            if (statusEl) statusEl.innerHTML = '<span style="color: var(--red);">Invalid IP address format.</span>';
            return;
        }

        if (statusEl) statusEl.innerHTML = `<span style="color: var(--cyan);">Probing http://${ip}:${port}...</span>`;
        if (submitBtn) submitBtn.disabled = true;

        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 3000);

            const res = await fetch(`http://${ip}:${port}/status.json?_t=${Date.now()}`, {
                signal: controller.signal
            });
            clearTimeout(timeoutId);

            if (res.ok) {
                const data = await res.json();
                const dev = {
                    id: `${ip}:${port}`,
                    ip: ip,
                    port: port,
                    name: data.app || `DASMO Phone (${ip})`,
                    streamUrl: `http://${ip}:${port}/video_feed`,
                    snapshotUrl: `http://${ip}:${port}/snapshot.jpg`,
                    audioUrl: `http://${ip}:${port}/audio_feed`,
                    speakerUrl: `http://${ip}:${port}/speaker_feed`,
                    controlUrl: `http://${ip}:${port}/api/control`
                };

                saveManualDevice(dev);
                modal.classList.remove('open');
                if (statusEl) statusEl.innerText = '';
                connectToDevice(dev);
            } else {
                throw new Error(`Server returned HTTP ${res.status}`);
            }
        } catch (err) {
            // Fallback: connect anyway in case status.json is blocked but video_feed works
            const dev = {
                id: `${ip}:${port}`,
                ip: ip,
                port: port,
                name: `DASMO Phone (${ip})`,
                streamUrl: `http://${ip}:${port}/video_feed`,
                snapshotUrl: `http://${ip}:${port}/snapshot.jpg`,
                audioUrl: `http://${ip}:${port}/audio_feed`,
                speakerUrl: `http://${ip}:${port}/speaker_feed`,
                controlUrl: `http://${ip}:${port}/api/control`
            };
            saveManualDevice(dev);
            modal.classList.remove('open');
            if (statusEl) statusEl.innerText = '';
            connectToDevice(dev);
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    });
}

function parseManualInput(rawIp, rawPort) {
    let clean = (rawIp || '').trim();
    clean = clean.replace(/^https?:\/\//i, '');
    clean = clean.replace(/\/.*$/, ''); // strip any trailing path

    let port = parseInt(rawPort, 10) || 8080;

    if (clean.includes(':')) {
        const parts = clean.split(':');
        clean = parts[0].trim();
        const p = parseInt(parts[1], 10);
        if (p > 0 && p <= 65535) port = p;
    }

    return { ip: clean, port: port };
}

function getSavedManualDevices() {
    try {
        const raw = localStorage.getItem('dasmo_manual_devices');
        return raw ? JSON.parse(raw) : [];
    } catch (_) {
        return [];
    }
}

function saveManualDevice(dev) {
    try {
        const list = getSavedManualDevices().filter(d => d.id !== dev.id);
        list.unshift(dev);
        localStorage.setItem('dasmo_manual_devices', JSON.stringify(list.slice(0, 10)));
    } catch (_) {}
}

async function renderDiscoveredDevices() {
    const listEl = document.getElementById('deviceList');
    if (!listEl) return;

    const mdnsDevices = window.dasmoAPI ? await window.dasmoAPI.getDiscoveredDevices() : [];
    const manualDevices = getSavedManualDevices();

    // Merge without duplicates
    const allDevices = [...mdnsDevices];
    manualDevices.forEach(m => {
        if (!allDevices.some(d => d.ip === m.ip && d.port === m.port)) {
            allDevices.push(m);
        }
    });

    if (allDevices.length === 0) {
        listEl.innerHTML = '<div style="font-size: 11px; color: var(--text-muted); font-family: var(--font-mono); padding: 8px;">No phone found yet. Click ➕ Manual IP Pair or ensure phone app is open.</div>';
        return;
    }

    listEl.innerHTML = '';
    allDevices.forEach(dev => {
        const isCurrent = activeDevice && activeDevice.id === dev.id;
        const isManual = !mdnsDevices.some(d => d.id === dev.id);
        const card = document.createElement('div');
        card.className = `device-card ${isCurrent ? 'active' : ''}`;
        card.innerHTML = `
            <div class="device-card-header">
                <span class="device-name">${dev.name}</span>
                <span class="device-badge ${isCurrent ? 'badge-connected' : 'badge-available'}">${isCurrent ? 'CONNECTED' : (isManual ? 'MANUAL IP' : 'AIR LINK')}</span>
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
    if (livePill) livePill.className = 'live-indicator live-on';
    document.getElementById('pulseDot').style.background = 'var(--green)';
    document.getElementById('txtLiveStatus').innerText = 'AIR LINK ACTIVE';

    // Start Ultra-Low Latency Live Stream Viewfinder (Zero-Buffer Canvas with WebSocket)
    if (streamViewer) {
        streamViewer.start(device);
    } else {
        const videoImg = document.getElementById('videoFeedImg');
        if (videoImg) {
            videoImg.src = '';
            setTimeout(() => {
                videoImg.src = `${device.streamUrl}?_t=${Date.now()}`;
            }, 50);
        }
    }

    // Auto-start Audio Bridge: Phone Mic → PC (DASMO MIC) + PC Audio → Phone Speaker (DASMO SPEAKER)
    if (!isAudioRelayRunning) {
        window.dasmoAPI?.startAudioBridge(`${device.ip}:${device.port}`);
        updateAudioRelayUI(true);
    }

    // Auto-start Virtual Camera: Phone Camera → PC (DASMO CAMERA) for WhatsApp/Zoom/Teams
    if (!isDriverRunning && window.dasmoAPI) {
        window.dasmoAPI.startVirtualCamera(`${device.ip}:${device.port}`);
        isDriverRunning = true;
        const btn = document.getElementById('btnStartDriver');
        const lbl = document.getElementById('lblStartDriver');
        if (btn) btn.style.background = 'var(--cyan)';
        if (btn) btn.style.color = '#000';
        if (lbl) lbl.innerText = 'Stop DASMO CAMERA Driver';
    }

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

    // Throttled Hardware Sliders (Zero Network Saturation)
    document.getElementById('sliderZoom')?.addEventListener('input', (e) => {
        const val = e.target.value;
        document.getElementById('valZoom').innerText = `${val}x`;
        document.getElementById('hudZoomLabel').innerText = `ZOOM: ${val}x`;
        sendControlThrottled('zoom', val);
    });

    document.getElementById('sliderGain')?.addEventListener('input', (e) => {
        const val = e.target.value;
        document.getElementById('valGain').innerText = `${val}x`;
        sendControlThrottled('gain', val);
    });

    document.getElementById('sliderSpeakerVol')?.addEventListener('input', (e) => {
        const val = e.target.value;
        document.getElementById('valSpeakerVol').innerText = `${Math.round(val * 100)}%`;
        sendControlThrottled('volume', val);
    });

    document.getElementById('btnAutoAudio')?.addEventListener('click', () => {
        sendControl('routing', 'AUTO');
    });

    document.getElementById('btnLoudspeaker')?.addEventListener('click', () => {
        sendControl('routing', 'SPEAKERPHONE');
    });

    document.getElementById('btnEarpiece')?.addEventListener('click', () => {
        sendControl('routing', 'EARPIECE');
    });

    document.getElementById('btnStartAudioRelay')?.addEventListener('click', () => {
        if (!activeDevice) {
            alert('Please connect to your phone first.');
            return;
        }
        if (!isAudioRelayRunning) {
            window.dasmoAPI?.startAudioBridge(activeDevice.ip);
            updateAudioRelayUI(true);
        } else {
            window.dasmoAPI?.stopAudioBridge();
            updateAudioRelayUI(false);
        }
    });

    // Virtual Camera Driver Button
    document.getElementById('btnStartDriver')?.addEventListener('click', () => {
        toggleVirtualCameraDriver();
    });

    // 🎥 1-Click Register Camera
    document.getElementById('btnInstallCamDriver')?.addEventListener('click', async () => {
        const btn = document.getElementById('btnInstallCamDriver');
        btn.innerText = 'Registering...';
        try {
            await window.dasmoAPI?.installCameraDriver();
            btn.innerText = '✓ Camera Registered';
        } catch (e) {
            btn.innerText = 'Error Registering';
        }
        setTimeout(() => { btn.innerText = '🎥 1-Click Register Camera'; }, 3000);
    });

    // ⚡ 1-Click Name Devices (DASMO CAMERA / MIC / SPEAKER)
    document.getElementById('btnConfigureDeviceNames')?.addEventListener('click', async () => {
        const btn = document.getElementById('btnConfigureDeviceNames');
        btn.innerText = 'Naming Devices...';
        try {
            await window.dasmoAPI?.configureDeviceNames();
            btn.innerText = '✓ Devices Named';
        } catch (e) {
            btn.innerText = 'Error Naming';
        }
        setTimeout(() => { btn.innerText = '⚡ 1-Click Name Devices (DASMO MIC / SPEAKER / CAM)'; }, 3000);
    });

    // 🎧 1-Click Install Audio Driver
    document.getElementById('btnInstallAudioDriver')?.addEventListener('click', async () => {
        const btn = document.getElementById('btnInstallAudioDriver');
        btn.innerText = 'Installing Audio...';
        try {
            await window.dasmoAPI?.installAudioDriver();
            btn.innerText = '✓ Driver Setup Launched';
        } catch (e) {
            btn.innerText = 'Error Installing';
        }
        setTimeout(() => { btn.innerText = '🎧 1-Click Install Audio Driver'; }, 3000);
    });

    // 📦 Install Dependencies
    document.getElementById('btnInstallDeps')?.addEventListener('click', async () => {
        const btn = document.getElementById('btnInstallDeps');
        btn.innerText = 'Installing Packages...';
        try {
            await window.dasmoAPI?.installDriverDependencies();
            btn.innerText = '✓ Packages Installed';
        } catch (e) {
            btn.innerText = 'Error Installing';
        }
        setTimeout(() => { btn.innerText = '📦 Install Dependencies'; }, 3000);
    });
}

const throttledControls = new Map();
function sendControlThrottled(action, value = '', delayMs = 50) {
    if (!activeDevice) return;
    if (throttledControls.has(action)) {
        clearTimeout(throttledControls.get(action));
    }
    const timer = setTimeout(() => {
        sendControl(action, value);
        throttledControls.delete(action);
    }, delayMs);
    throttledControls.set(action, timer);
}

function sendControl(action, value = '') {
    if (!activeDevice) return;
    const url = `http://${activeDevice.ip}:${activeDevice.port}/api/control?action=${encodeURIComponent(action)}&value=${encodeURIComponent(value)}`;
    fetch(url).catch(() => {});
}

// Telemetry, Status Polling & Resilient Auto-Reconnection
let consecutiveFailures = 0;
let isReconnecting = false;

function startStatusPolling() {
    if (statusPollInterval) clearInterval(statusPollInterval);
    consecutiveFailures = 0;
    isReconnecting = false;

    statusPollInterval = setInterval(async () => {
        if (!activeDevice) return;
        const pingStart = performance.now();
        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 1800);
            const res = await fetch(`http://${activeDevice.ip}:${activeDevice.port}/status.json?_t=${Date.now()}`, {
                cache: 'no-store',
                signal: controller.signal
            });
            clearTimeout(timeoutId);

            if (res.ok) {
                const rtt = Math.round(performance.now() - pingStart);
                const data = await res.json();
                consecutiveFailures = 0;

                if (isReconnecting) {
                    // Connection recovered!
                    isReconnecting = false;
                    const livePill = document.getElementById('livePill');
                    if (livePill) livePill.className = 'live-indicator live-on';
                    const pulseDot = document.getElementById('pulseDot');
                    if (pulseDot) pulseDot.style.background = 'var(--green)';
                    const statusText = document.getElementById('txtLiveStatus');
                    if (statusText) statusText.innerText = 'AIR LINK ACTIVE';

                    // Remove reconnecting overlay
                    const reconnectOverlay = document.getElementById('reconnectOverlay');
                    if (reconnectOverlay) reconnectOverlay.remove();

                    // Refresh video feed with fresh timestamp
                    if (streamViewer) {
                        streamViewer.start(activeDevice);
                    } else {
                        const videoImg = document.getElementById('videoFeedImg');
                        if (videoImg) videoImg.src = `${activeDevice.streamUrl}?_t=${Date.now()}`;
                    }
                }

                // Update Latency (RTT)
                const telLatency = document.getElementById('telLatency');
                if (telLatency) telLatency.innerText = `${rtt}ms`;
                const hudLatency = document.getElementById('hudLatency');
                if (hudLatency) {
                    hudLatency.innerText = `LATENCY: <${Math.max(12, rtt)}ms (Real-Time)`;
                    hudLatency.style.color = rtt < 45 ? 'var(--green)' : (rtt < 100 ? 'var(--amber)' : 'var(--red)');
                }

                // Update FPS & Bitrate from real live telemetry
                const fpsVal = typeof data.fps === 'number' ? data.fps.toFixed(1) : (data.fps || '30.0');
                const telFps = document.getElementById('telFps');
                if (telFps) telFps.innerText = `${fpsVal}`;
                const hudFps = document.getElementById('hudFpsLabel');
                if (hudFps) hudFps.innerText = `FPS: ${fpsVal}`;
                
                const telBitrate = document.getElementById('telBitrate');
                if (telBitrate) telBitrate.innerText = `${data.bitrate_kbps || 1850} kbps`;
                
                const telClients = document.getElementById('telClients');
                if (telClients) telClients.innerText = `${data.clients || 1} PC`;

                // Update Device Name / Model
                if (data.device_name) {
                    const devNameEl = document.getElementById('activePhoneName');
                    if (devNameEl && devNameEl.innerText !== data.device_name) {
                        devNameEl.innerText = data.device_name;
                    }
                }

                // Battery Telemetry & Temperature
                const telBattery = document.getElementById('telBattery');
                if (telBattery && data.battery_pct !== undefined) {
                    const temp = (data.battery_temp && data.battery_temp > 0) ? ` (${Math.round(data.battery_temp)}°C)` : '';
                    telBattery.innerText = `${data.battery_pct}%${temp}`;
                }

                // Resolution
                if (data.resolution) {
                    const hudRes = document.getElementById('hudResolution');
                    if (hudRes) hudRes.innerText = `RES: ${data.resolution} · ${fpsVal} FPS`;
                }

                // Zoom Bidirectional Sync
                const sliderZoom = document.getElementById('sliderZoom');
                if (data.zoom_ratio !== undefined && document.activeElement !== sliderZoom) {
                    const zoomVal = Number(data.zoom_ratio).toFixed(1);
                    if (sliderZoom) sliderZoom.value = zoomVal;
                    const valZoom = document.getElementById('valZoom');
                    if (valZoom) valZoom.innerText = `${zoomVal}x`;
                    const hudZoom = document.getElementById('hudZoomLabel');
                    if (hudZoom) hudZoom.innerText = `ZOOM: ${zoomVal}x`;
                }

                // Torch State Bidirectional Sync
                if (data.is_torch_on !== undefined) {
                    const btnTorch = document.getElementById('btnTorch');
                    if (btnTorch) {
                        if (data.is_torch_on) {
                            btnTorch.classList.add('active');
                            btnTorch.style.borderColor = 'var(--cyan)';
                            btnTorch.style.color = 'var(--cyan)';
                        } else {
                            btnTorch.classList.remove('active');
                            btnTorch.style.borderColor = '';
                            btnTorch.style.color = '';
                        }
                    }
                }

                // Hardware Microphone RMS Decibel Meter
                const micMuted = data.is_mic_muted !== undefined ? data.is_mic_muted : isMicMuted;
                const vuBar = document.getElementById('vuMeterBar');
                const micTxt = document.getElementById('txtMicLevel');
                if (micMuted) {
                    if (vuBar) vuBar.style.width = '0%';
                    if (micTxt) micTxt.innerText = 'MUTED';
                } else {
                    const db = typeof data.mic_db === 'number' ? data.mic_db : -60.0;
                    // Scale -60 dB .. 0 dB to 0% .. 100%
                    const vuPct = Math.max(0, Math.min(100, Math.round(((db + 60) / 60) * 100)));
                    if (vuBar) vuBar.style.width = `${vuPct}%`;
                    if (micTxt) micTxt.innerText = `${db.toFixed(1)} dB`;
                }

                // Privacy Video Pause Sync
                if (data.is_video_paused !== undefined && data.is_video_paused !== isVideoPaused) {
                    isVideoPaused = data.is_video_paused;
                    const btn = document.getElementById('btnPauseVideo');
                    const lbl = document.getElementById('lblPauseVideo');
                    if (isVideoPaused) {
                        btn?.classList.add('danger');
                        if (lbl) lbl.innerText = 'Resume Video';
                    } else {
                        btn?.classList.remove('danger');
                        if (lbl) lbl.innerText = 'Pause Video';
                    }
                }

                // Mic Mute State Sync
                if (data.is_mic_muted !== undefined && data.is_mic_muted !== isMicMuted) {
                    isMicMuted = data.is_mic_muted;
                    const btn = document.getElementById('btnMuteMic');
                    const lbl = document.getElementById('lblMuteMic');
                    if (isMicMuted) {
                        btn?.classList.add('danger');
                        if (lbl) lbl.innerText = 'Unmute Mic';
                    } else {
                        btn?.classList.remove('danger');
                        if (lbl) lbl.innerText = 'Mute Mic';
                    }
                }

                // Speaker State Sync
                if (data.is_speaker_on !== undefined && data.is_speaker_on !== isSpeakerOn) {
                    isSpeakerOn = data.is_speaker_on;
                    const lblSpeaker = document.getElementById('lblSpeaker');
                    if (lblSpeaker) lblSpeaker.innerText = isSpeakerOn ? 'Speaker: ON' : 'Speaker: OFF';
                }

                // Mic Gain & Speaker Volume Sync (when user not dragging slider)
                const sliderGain = document.getElementById('sliderGain');
                if (data.mic_gain !== undefined && document.activeElement !== sliderGain) {
                    const gainVal = Number(data.mic_gain).toFixed(1);
                    if (sliderGain) sliderGain.value = gainVal;
                    const valGain = document.getElementById('valGain');
                    if (valGain) valGain.innerText = `${gainVal}x`;
                }
                const sliderSpeakerVol = document.getElementById('sliderSpeakerVol');
                if (data.speaker_volume !== undefined && document.activeElement !== sliderSpeakerVol) {
                    const volVal = Number(data.speaker_volume);
                    if (sliderSpeakerVol) sliderSpeakerVol.value = volVal;
                    const valSpeakerVol = document.getElementById('valSpeakerVol');
                    if (valSpeakerVol) valSpeakerVol.innerText = `${Math.round(volVal * 100)}%`;
                }

                // Connected Audio Output Device & Routing Telemetry Sync
                const audioDevName = data.audio_output_name || data.audio_output_device || 'Phone Speaker';
                const hasHp = data.has_headphones === true;
                const activeAudioDeviceEl = document.getElementById('activeAudioDevice');
                if (activeAudioDeviceEl) {
                    activeAudioDeviceEl.innerText = audioDevName;
                    activeAudioDeviceEl.style.color = hasHp ? 'var(--green)' : 'var(--cyan)';
                }
                const txtAudioOutputDeviceEl = document.getElementById('txtAudioOutputDevice');
                if (txtAudioOutputDeviceEl) {
                    txtAudioOutputDeviceEl.innerText = audioDevName;
                    txtAudioOutputDeviceEl.style.color = hasHp ? 'var(--green)' : 'var(--cyan)';
                }

                // Highlight active routing button
                const curRouting = (data.audio_routing || 'AUTO').toUpperCase();
                const btnAuto = document.getElementById('btnAutoAudio');
                const btnSpk = document.getElementById('btnLoudspeaker');
                const btnEar = document.getElementById('btnEarpiece');
                if (btnAuto && btnSpk && btnEar) {
                    btnAuto.style.borderColor = curRouting === 'AUTO' ? 'var(--green)' : '';
                    btnAuto.style.color = curRouting === 'AUTO' ? 'var(--green)' : '';
                    btnSpk.style.borderColor = curRouting === 'SPEAKERPHONE' ? 'var(--green)' : '';
                    btnSpk.style.color = curRouting === 'SPEAKERPHONE' ? 'var(--green)' : '';
                    btnEar.style.borderColor = curRouting === 'EARPIECE' ? 'var(--green)' : '';
                    btnEar.style.color = curRouting === 'EARPIECE' ? 'var(--green)' : '';
                }
            } else {
                throw new Error('HTTP ' + res.status);
            }
        } catch (err) {
            consecutiveFailures++;
            if (consecutiveFailures >= 3 && !isReconnecting) {
                isReconnecting = true;
                const livePill = document.getElementById('livePill');
                if (livePill) livePill.className = 'live-indicator live-standby';
                const pulseDot = document.getElementById('pulseDot');
                if (pulseDot) pulseDot.style.background = 'var(--amber)';
                const statusText = document.getElementById('txtLiveStatus');
                if (statusText) statusText.innerText = 'RECONNECTING...';

                // Display stylish reconnection HUD overlay on viewfinder
                const viewfinder = document.querySelector('.viewfinder-frame');
                if (viewfinder && !document.getElementById('reconnectOverlay')) {
                    const overlay = document.createElement('div');
                    overlay.id = 'reconnectOverlay';
                    overlay.style.cssText = 'position:absolute;inset:0;background:rgba(8,12,20,0.85);display:flex;flex-direction:column;align-items:center;justify-content:center;color:var(--amber);font-family:var(--font-mono);z-index:20;';
                    overlay.innerHTML = `
                        <div style="font-size:18px;font-weight:bold;margin-bottom:8px;animation:pulse 1.5s infinite;">⚠ SIGNAL INTERRUPTED</div>
                        <div style="font-size:12px;color:var(--text-muted);">Reconnecting to ${activeDevice.name} (${activeDevice.ip})...</div>
                    `;
                    viewfinder.appendChild(overlay);
                }
            }
        }
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

    document.getElementById('btnInstallCamDriver')?.addEventListener('click', async () => {
        envText.innerText = 'Launching Camera Driver registration (UAC prompt)...';
        try {
            await window.dasmoAPI.installCameraDriver();
            envText.innerText = '✓ DirectShow Virtual Camera registration launched! Check Windows prompt.';
            envText.style.color = 'var(--green)';
        } catch (e) {
            envText.innerText = `Driver registration error: ${e.message}`;
            envText.style.color = 'var(--red)';
        }
    });

    document.getElementById('btnConfigureDeviceNames')?.addEventListener('click', async () => {
        const audioStatus = document.getElementById('audioDriverStatusText');
        if (audioStatus) audioStatus.innerText = 'Configuring DASMO Hardware (Check Windows prompt)...';
        try {
            await window.dasmoAPI.configureDeviceNames();
            if (audioStatus) audioStatus.innerText = '✓ DASMO Hardware configuration launched!';
        } catch (e) {
            if (audioStatus) audioStatus.innerText = `Error: ${e.message}`;
        }
    });

    document.getElementById('btnInstallAudioDriver')?.addEventListener('click', async () => {
        const audioStatus = document.getElementById('audioDriverStatusText');
        if (audioStatus) audioStatus.innerText = 'Setting up DASMO Virtual Mic (Check Windows prompt)...';
        try {
            await window.dasmoAPI.installAudioDriver();
            if (audioStatus) audioStatus.innerText = '✓ DASMO Virtual Mic setup launched!';
        } catch (e) {
            if (audioStatus) audioStatus.innerText = `Error: ${e.message}`;
        }
    });

    document.getElementById('btnUninstallAudioDriver')?.addEventListener('click', async () => {
        if (!confirm('Completely uninstall DASMO Virtual Audio device from Windows?')) return;
        const audioStatus = document.getElementById('audioDriverStatusText');
        if (audioStatus) audioStatus.innerText = 'Uninstalling DASMO Virtual Audio (Check Windows prompt)...';
        try {
            await window.dasmoAPI.uninstallAudioDriver();
            if (audioStatus) audioStatus.innerText = '✓ DASMO Virtual Audio uninstalled cleanly.';
        } catch (e) {
            if (audioStatus) audioStatus.innerText = `Error: ${e.message}`;
        }
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

    const deviceAddress = `${activeDevice.ip}:${activeDevice.port || 8080}`;
    if (!isDriverRunning) {
        window.dasmoAPI?.startVirtualCamera(deviceAddress);
        window.dasmoAPI?.startAudioBridge(deviceAddress);
        updateAudioRelayUI(true);
    } else {
        window.dasmoAPI?.stopVirtualCamera();
        window.dasmoAPI?.stopAudioBridge();
        updateAudioRelayUI(false);
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
    if (window.dasmoAPI?.getAppVersion) {
        window.dasmoAPI.getAppVersion().then(v => {
            const el = document.getElementById('desktopVersionText');
            if (el) el.innerText = `Current: v${v} (Latest)`;
            const modalCur = document.getElementById('modalCurrentVer');
            if (modalCur) modalCur.innerText = `CURRENT: v${v}`;
        }).catch(() => {});
    }

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
        const currentVer = (updateInfo && updateInfo.currentVersion) || (await window.dasmoAPI.getAppVersion?.()) || '1.4.9';
        const modalCur = document.getElementById('modalCurrentVer');
        if (modalCur) modalCur.innerText = `CURRENT: v${currentVer}`;

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
                versionText.innerText = `Current: v${currentVer} (Latest)`;
                versionText.style.color = 'var(--text-muted)';
            }
            if (isManual) {
                alert(`You are already on the latest version of DASMO CYBER CAPTURE (v${currentVer})!`);
            }
        }
    } catch (e) {
        if (isManual) {
            alert('Could not check for updates. Please check your internet connection.');
        }
    }
}

// ==============================================================================
// DASMO CYBER CAPTURE // ULTRA-LOW LATENCY REAL-TIME STREAM VIEWER
// Architecture: WebSocket Binary -> createImageBitmap -> HTML5 Canvas
// Latency: Sub-30ms glass-to-glass, zero queue accumulation, automatic buffer drop
// ==============================================================================
class UltraLowLatencyStreamViewer {
    constructor(canvasEl, fallbackImgEl) {
        this.canvas = canvasEl;
        this.ctx = canvasEl.getContext('2d', { alpha: false, desynchronized: true });
        this.img = fallbackImgEl;
        this.ws = null;
        this.fetchAbort = null;
        this.isRendering = false;
        this.pendingBuffer = null;
        this.connectedDevice = null;
        this.mode = 'idle'; // 'ws' | 'fetch' | 'img' | 'idle'
        this.renderCount = 0;
        this.lastFpsMeasure = Date.now();
        this.measuredFps = 0;

        // Video Stream Display Mode: Always Pure Raw 60 FPS Camera Feed
        this.bgMode = 'raw';
    }

    setBg(mode, color) {
        this.bgMode = 'raw';
    }

    start(device) {
        this.stop();
        this.connectedDevice = device;

        const wsUrl = `ws://${device.ip}:${device.port}/ws/video`;
        this._tryConnectWebSocket(wsUrl, device);
    }

    _tryConnectWebSocket(wsUrl, device) {
        try {
            console.log('[StreamViewer] Connecting low-latency WebSocket:', wsUrl);
            this.ws = new WebSocket(wsUrl);
            this.ws.binaryType = 'arraybuffer';

            let wsOpened = false;
            const timeout = setTimeout(() => {
                if (!wsOpened) {
                    console.warn('[StreamViewer] WebSocket connect timeout, falling back to chunked fetch');
                    if (this.ws) {
                        try { this.ws.close(); } catch(_) {}
                        this.ws = null;
                    }
                    this._startFetchStream(device);
                }
            }, 2500);

            this.ws.onopen = () => {
                wsOpened = true;
                clearTimeout(timeout);
                this.mode = 'ws';
                console.log('[StreamViewer] WebSocket connected! Zero-queue real-time video active.');
            };

            this.ws.onmessage = (event) => {
                if (event.data instanceof ArrayBuffer) {
                    // Always store the newest frame buffer, dropping any unrendered previous frame
                    this.pendingBuffer = event.data;
                    if (!this.isRendering) {
                        this._scheduleRender();
                    }
                }
            };

            this.ws.onerror = (e) => {
                console.warn('[StreamViewer] WebSocket error, fallback to fetch reader', e);
                clearTimeout(timeout);
                if (!wsOpened) {
                    this._startFetchStream(device);
                }
            };

            this.ws.onclose = () => {
                console.log('[StreamViewer] WebSocket closed');
                if (this.mode === 'ws') {
                    if (activeDevice && activeDevice.id === device.id) {
                        setTimeout(() => {
                            if (activeDevice && activeDevice.id === device.id && this.mode !== 'idle') {
                                this._tryConnectWebSocket(wsUrl, device);
                            }
                        }, 1200);
                    }
                }
            };
        } catch (err) {
            console.warn('[StreamViewer] WS init failed, falling back', err);
            this._startFetchStream(device);
        }
    }

    async _startFetchStream(device) {
        this.mode = 'fetch';
        console.log('[StreamViewer] Starting zero-queue Fetch ReadableStream reader...');

        this.fetchAbort = new AbortController();
        const url = `${device.streamUrl}?_t=${Date.now()}`;

        try {
            const response = await fetch(url, {
                signal: this.fetchAbort.signal,
                cache: 'no-store'
            });

            if (!response.body) {
                throw new Error('ReadableStream not supported on response body');
            }

            const reader = response.body.getReader();
            let accumulatedChunks = [];
            let totalLength = 0;

            const readLoop = async () => {
                while (this.mode === 'fetch') {
                    const { done, value } = await reader.read();
                    if (done) break;

                    accumulatedChunks.push(value);
                    totalLength += value.length;

                    // Combine and search for JPEG boundaries (0xFFD8 to 0xFFD9)
                    if (totalLength > 1000) {
                        const merged = new Uint8Array(totalLength);
                        let offset = 0;
                        for (const chunk of accumulatedChunks) {
                            merged.set(chunk, offset);
                            offset += chunk.length;
                        }

                        // Find JPEG Start of Image (0xFF, 0xD8) and End of Image (0xFF, 0xD9)
                        let soi = -1;
                        let eoi = -1;

                        for (let i = 0; i < merged.length - 1; i++) {
                            if (merged[i] === 0xFF && merged[i + 1] === 0xD8) {
                                soi = i;
                            } else if (merged[i] === 0xFF && merged[i + 1] === 0xD9 && soi !== -1) {
                                eoi = i + 2;
                            }
                        }

                        if (soi !== -1 && eoi !== -1 && eoi > soi) {
                            const jpegFrame = merged.slice(soi, eoi);
                            this.pendingBuffer = jpegFrame.buffer;
                            if (!this.isRendering) {
                                this._scheduleRender();
                            }

                            // Keep remainder for next frame
                            const remainder = merged.slice(eoi);
                            accumulatedChunks = [remainder];
                            totalLength = remainder.length;
                        } else if (totalLength > 500000) {
                            // Reset buffer if sync boundary lost
                            accumulatedChunks = [];
                            totalLength = 0;
                        }
                    }
                }
            };

            readLoop().catch((e) => {
                if (e.name !== 'AbortError') {
                    console.warn('[StreamViewer] Fetch stream loop ended, fallback to img tag', e);
                    this._fallbackToImg(device);
                }
            });
        } catch (e) {
            if (e.name !== 'AbortError') {
                console.warn('[StreamViewer] Fetch stream failed, falling back to img tag', e);
                this._fallbackToImg(device);
            }
        }
    }

    _fallbackToImg(device) {
        this.mode = 'img';
        this._showImg();
        if (this.img && device) {
            this.img.src = `${device.streamUrl}?_t=${Date.now()}`;
        }
    }

    _scheduleRender() {
        if (this.isRendering || !this.pendingBuffer) return;
        this.isRendering = true;

        requestAnimationFrame(async () => {
            const buf = this.pendingBuffer;
            this.pendingBuffer = null; // Cleared so incoming packets never queue

            if (buf) {
                try {
                    const blob = new Blob([buf], { type: 'image/jpeg' });
                    const bitmap = await createImageBitmap(blob);

                    if (this.canvas.width !== bitmap.width || this.canvas.height !== bitmap.height) {
                        this.canvas.width = bitmap.width;
                        this.canvas.height = bitmap.height;
                    }

                    // Always save pristine raw camera frame for studio photo capture
                    if (!this.latestRawCanvas) {
                        this.latestRawCanvas = document.createElement('canvas');
                    }
                    if (this.latestRawCanvas.width !== bitmap.width || this.latestRawCanvas.height !== bitmap.height) {
                        this.latestRawCanvas.width = bitmap.width;
                        this.latestRawCanvas.height = bitmap.height;
                    }
                    const rawCtx = this.latestRawCanvas.getContext('2d');
                    rawCtx.drawImage(bitmap, 0, 0);

                    // Live Viewfinder: Pure 60 FPS Raw Camera Feed
                    this.ctx.drawImage(bitmap, 0, 0);
                    if (this.renderCount === 0) {
                        this._showCanvas();
                    }
                    bitmap.close(); // Immediate GPU texture & memory release

                    this.renderCount++;
                    const now = Date.now();
                    if (now - this.lastFpsMeasure >= 1000) {
                        this.measuredFps = Math.round((this.renderCount * 1000) / (now - this.lastFpsMeasure));
                        this.renderCount = 0;
                        this.lastFpsMeasure = now;
                    }
                } catch (e) {
                    console.warn('[StreamViewer] Render error:', e);
                    if (this.renderCount === 0 && this.connectedDevice) {
                        this._fallbackToImg(this.connectedDevice);
                    }
                }
            }

            this.isRendering = false;
            // If a newer frame arrived while decoding, draw it immediately
            if (this.pendingBuffer) {
                this._scheduleRender();
            }
        });
    }

    _showCanvas() {
        if (this.canvas) this.canvas.style.display = 'block';
        if (this.img) this.img.style.display = 'none';
    }

    _showImg() {
        if (this.canvas) this.canvas.style.display = 'none';
        if (this.img) this.img.style.display = 'block';
    }

    stop() {
        this.mode = 'idle';
        if (this.ws) {
            try { this.ws.close(); } catch(_) {}
            this.ws = null;
        }
        if (this.fetchAbort) {
            try { this.fetchAbort.abort(); } catch(_) {}
            this.fetchAbort = null;
        }
        this.pendingBuffer = null;
        this.isRendering = false;
        this._showImg();
        if (this.img) {
            this.img.src = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='640' height='360' viewBox='0 0 640 360'><rect width='640' height='360' fill='%23080c14'/><text x='50%25' y='50%25' font-family='monospace' font-size='14' fill='%2300e5ff' text-anchor='middle' dominant-baseline='middle'>[ DASMO CYBER CAPTURE // WAITING FOR AIR LINK FEED ]</text></svg>";
        }
    }

    getLatestFrameDataUrl() {
        if (this.latestRawCanvas && this.latestRawCanvas.width > 0 && this.latestRawCanvas.height > 0) {
            return this.latestRawCanvas.toDataURL('image/jpeg', 0.95);
        }
        if (this.canvas && this.canvas.width > 0 && this.canvas.height > 0) {
            return this.canvas.toDataURL('image/jpeg', 0.95);
        }
        if (this.img && this.img.complete && this.img.naturalWidth > 0) {
            const off = document.createElement('canvas');
            off.width = this.img.naturalWidth;
            off.height = this.img.naturalHeight;
            const ctx = off.getContext('2d');
            ctx.drawImage(this.img, 0, 0);
            return off.toDataURL('image/jpeg', 0.95);
        }
        return null;
    }
}


