const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const { NetworkDiscoveryEngine } = require('./discovery');
const { HardwareDriverBridge, getDriverScriptPath } = require('./bridge');
const { checkForDesktopUpdates } = require('./updater');

const gotTheLock = app.requestSingleInstanceLock();
if (!gotTheLock) {
    app.quit();
}

let mainWindow = null;
let popoutWindow = null;
let tray = null;
let discoveryEngine = null;
let driverBridge = null;
let activeConnectedDevice = null;

function createMainWindow() {
    mainWindow = new BrowserWindow({
        width: 1040,
        height: 700,
        minWidth: 840,
        minHeight: 580,
        backgroundColor: '#080c14',
        title: 'DASMO CYBER CAPTURE',
        icon: path.join(__dirname, '../renderer/assets/icon.png'),
        frame: false,
        titleBarStyle: 'hidden',
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            nodeIntegration: false,
            contextIsolation: true
        }
    });

    mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'));

    mainWindow.on('close', (event) => {
        if (!app.isQuitting) {
            event.preventDefault();
            mainWindow.hide();
        }
        return false;
    });
}

function createPopoutWindow(streamUrl) {
    if (popoutWindow) {
        popoutWindow.focus();
        return;
    }

    popoutWindow = new BrowserWindow({
        width: 480,
        height: 300,
        minWidth: 320,
        minHeight: 200,
        alwaysOnTop: true,
        backgroundColor: '#000000',
        title: 'DASMO Live Camera Viewfinder',
        frame: true,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true
        }
    });

    popoutWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(`
        <!DOCTYPE html>
        <html>
        <head>
            <title>DASMO CYBER CAPTURE - Pop-Out Viewfinder</title>
            <style>
                body { margin: 0; background: #000; overflow: hidden; display: flex; align-items: center; justify-content: center; height: 100vh; font-family: monospace; }
                canvas, img { width: 100%; height: 100%; object-fit: contain; }
                .hud { position: absolute; top: 10px; left: 10px; color: #00e5ff; font-size: 11px; background: rgba(8,12,20,0.8); padding: 4px 8px; border-radius: 4px; border: 1px solid #00e5ff; pointer-events: none; z-index: 10; }
            </style>
        </head>
        <body>
            <div class="hud">DASMO CYBER CAPTURE // REAL-TIME PREVIEW</div>
            <canvas id="cv" style="display:none;"></canvas>
            <img id="im" src="${streamUrl}" alt="Live Camera Stream" />
            <script>
                const cv = document.getElementById('cv');
                const im = document.getElementById('im');
                const ctx = cv.getContext('2d', { alpha: false, desynchronized: true });
                const wsUrl = '${streamUrl}'.replace(/^http:/, 'ws:').replace(/\\/video_feed.*$/, '/ws/video');
                let pending = null;
                let rendering = false;
                try {
                    const ws = new WebSocket(wsUrl);
                    ws.binaryType = 'arraybuffer';
                    ws.onopen = () => { cv.style.display = 'block'; im.style.display = 'none'; };
                    ws.onmessage = (e) => {
                        if (e.data instanceof ArrayBuffer) {
                            pending = e.data;
                            if (!rendering) render();
                        }
                    };
                    function render() {
                        if (!pending || rendering) return;
                        rendering = true;
                        const buf = pending;
                        pending = null;
                        createImageBitmap(new Blob([buf], { type: 'image/jpeg' })).then(bm => {
                            if (cv.width !== bm.width || cv.height !== bm.height) { cv.width = bm.width; cv.height = bm.height; }
                            ctx.drawImage(bm, 0, 0);
                            bm.close();
                            rendering = false;
                            if (pending) render();
                        }).catch(() => { rendering = false; });
                    }
                } catch(_) {}
            </script>
        </body>
        </html>
    `)}`);

    popoutWindow.on('closed', () => {
        popoutWindow = null;
    });
}

function createTray() {
    const iconPath = path.join(__dirname, '../renderer/assets/icon.png');
    let trayIcon = nativeImage.createFromPath(iconPath);
    if (trayIcon.isEmpty()) {
        // Fallback transparent dot icon
        trayIcon = nativeImage.createEmpty();
    } else {
        trayIcon = trayIcon.resize({ width: 16, height: 16 });
    }

    tray = new Tray(trayIcon);
    tray.setToolTip('DASMO CYBER CAPTURE // Wireless Call Station');

    updateTrayMenu();

    tray.on('double-click', () => {
        if (mainWindow) {
            mainWindow.show();
            mainWindow.focus();
        }
    });
}

function updateTrayMenu() {
    const isConnected = !!activeConnectedDevice;
    const deviceName = isConnected ? activeConnectedDevice.name : 'No Phone Connected';

    const contextMenu = Menu.buildFromTemplate([
        { label: `● ${deviceName} (${isConnected ? 'Connected' : 'Standby'})`, enabled: false },
        { type: 'separator' },
        {
            label: 'Open DASMO Cyber Capture',
            click: () => {
                if (mainWindow) {
                    mainWindow.show();
                    mainWindow.focus();
                }
            }
        },
        {
            label: 'Quick Controls',
            submenu: [
                {
                    label: 'Pause Camera (Privacy Slate)',
                    type: 'checkbox',
                    click: () => {
                        sendPhoneControl('pause_video', 'true');
                    }
                },
                {
                    label: 'Mute Microphone',
                    type: 'checkbox',
                    click: () => {
                        sendPhoneControl('mute', 'true');
                    }
                },
                {
                    label: 'Mute Phone Speaker',
                    type: 'checkbox',
                    click: () => {
                        sendPhoneControl('speaker', 'false');
                    }
                },
                {
                    label: 'Flip Camera (Front/Rear)',
                    click: () => {
                        sendPhoneControl('flip', '');
                    }
                }
            ]
        },
        {
            label: 'Virtual Camera Driver',
            submenu: [
                {
                    label: 'Start "DASMO CYBER CAPTURE" Device',
                    click: () => {
                        if (activeConnectedDevice) {
                            driverBridge.startVirtualCamera(activeConnectedDevice.ip);
                        }
                    }
                },
                {
                    label: 'Stop Virtual Camera Device',
                    click: () => {
                        driverBridge.stopVirtualCamera();
                    }
                }
            ]
        },
        { type: 'separator' },
        {
            label: isConnected ? 'Disconnect Phone' : 'Scan for Phones',
            click: () => {
                if (isConnected) {
                    activeConnectedDevice = null;
                    driverBridge.stopVirtualCamera();
                    updateTrayMenu();
                    mainWindow?.webContents.send('connection:changed', null);
                } else {
                    discoveryEngine?.scanLocalSubnet();
                }
            }
        },
        {
            label: 'Quit DASMO Cyber Capture',
            click: () => {
                app.isQuitting = true;
                driverBridge?.stopVirtualCamera();
                discoveryEngine?.stopDiscovery();
                app.quit();
            }
        }
    ]);

    tray?.setContextMenu(contextMenu);
}

function sendPhoneControl(action, value = '') {
    if (!activeConnectedDevice) return;
    const ip = activeConnectedDevice.ip;
    const port = activeConnectedDevice.port || 8080;
    const url = `http://${ip}:${port}/api/control?action=${encodeURIComponent(action)}&value=${encodeURIComponent(value)}`;
    
    http.get(url, (res) => res.resume()).on('error', (err) => {
        console.warn('[Phone Control Error]', err.message);
    });
}

app.whenReady().then(() => {
    discoveryEngine = new NetworkDiscoveryEngine();
    driverBridge = new HardwareDriverBridge();

    createMainWindow();
    createTray();

    discoveryEngine.on('device_discovered', (device) => {
        mainWindow?.webContents.send('discovery:device_found', device);
    });

    discoveryEngine.on('device_lost', (deviceId) => {
        mainWindow?.webContents.send('discovery:device_lost', deviceId);
    });

    driverBridge.on('status_change', (data) => {
        mainWindow?.webContents.send('driver:status_change', data);
    });

    discoveryEngine.startDiscovery();

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
    });

    app.on('second-instance', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
    });
});

app.on('before-quit', () => {
    app.isQuitting = true;
    try {
        driverBridge?.stopVirtualCamera();
        driverBridge?.stopAudioBridge();
        discoveryEngine?.stopDiscovery();
    } catch (_) {}
});

app.on('window-all-closed', () => {
    // Keep app running in system tray on Windows
});

// --- IPC Handlers ---
ipcMain.handle('discovery:get-devices', () => {
    return discoveryEngine ? discoveryEngine.getDevices() : [];
});

ipcMain.handle('discovery:scan-now', async () => {
    if (discoveryEngine) {
        await discoveryEngine.scanLocalSubnet();
    }
    return true;
});

ipcMain.handle('connection:set-active', (event, device) => {
    activeConnectedDevice = device;
    discoveryEngine?.setIsConnected(!!device);
    updateTrayMenu();
    return true;
});

ipcMain.handle('connection:get-active', () => {
    return activeConnectedDevice;
});

ipcMain.handle('driver:check-env', async () => {
    return await driverBridge.checkEnvironment();
});

ipcMain.handle('driver:install-deps', async () => {
    return await driverBridge.installDependencies();
});

ipcMain.handle('driver:install-audio', () => {
    const { exec } = require('child_process');
    const batPath = getDriverScriptPath('setup_dasmo_virtual_mic.bat');
    if (fs.existsSync(batPath)) {
        exec(`powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \\"\\"${batPath}\\"\\"' -Verb RunAs"`, (err) => {
            if (err) console.warn('[Setup DASMO Virtual Audio]', err);
        });
        return { success: true, message: 'DASMO Virtual Microphone setup launched with Administrator privileges' };
    }
    return { success: false, message: 'Installer script not found' };
});

ipcMain.handle('driver:uninstall-audio', () => {
    const { exec } = require('child_process');
    const batPath = getDriverScriptPath('setup_dasmo_virtual_mic.bat');
    if (fs.existsSync(batPath)) {
        exec(`powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \\"\\"${batPath}\\" -Uninstall\\"' -Verb RunAs"`, (err) => {
            if (err) console.warn('[Uninstall DASMO Virtual Audio]', err);
        });
        return { success: true, message: 'DASMO Virtual Hardware uninstaller launched' };
    }
    return { success: false, message: 'Installer script not found' };
});

ipcMain.handle('driver:install-cam-driver', () => {
    const { exec } = require('child_process');
    const batPath = getDriverScriptPath('install_dasmo_camera.bat');
    if (fs.existsSync(batPath)) {
        exec(`powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \\"\\"${batPath}\\"\\"' -Verb RunAs"`, (err) => {
            if (err) console.warn('[Install Cam Driver]', err);
        });
        return { success: true, message: 'Camera installer launched with Administrator privileges' };
    }
    return { success: false, message: 'Installer script not found' };
});

ipcMain.handle('driver:configure-device-names', () => {
    const { exec } = require('child_process');
    const batPath = getDriverScriptPath('setup_dasmo_virtual_mic.bat');
    if (fs.existsSync(batPath)) {
        exec(`powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \\"\\"${batPath}\\"\\"' -Verb RunAs"`, (err) => {
            if (err) console.warn('[Configure Device Names]', err);
        });
        return { success: true, message: 'Device branding setup launched with Administrator privileges' };
    }
    return { success: false, message: 'Setup script not found' };
});

ipcMain.handle('driver:start-cam', (event, phoneIp) => {
    driverBridge.startVirtualCamera(phoneIp);
    return true;
});

ipcMain.handle('driver:stop-cam', () => {
    driverBridge.stopVirtualCamera();
    return true;
});

ipcMain.handle('driver:start-audio-bridge', (event, phoneIp) => {
    driverBridge.startAudioBridge(phoneIp);
    return true;
});

ipcMain.handle('driver:stop-audio-bridge', () => {
    driverBridge.stopAudioBridge();
    return true;
});

ipcMain.handle('driver:get-status', () => {
    return driverBridge.getStatus();
});

ipcMain.handle('window:minimize', () => {
    mainWindow?.minimize();
});

ipcMain.handle('window:maximize', () => {
    if (mainWindow?.isMaximized()) {
        mainWindow.unmaximize();
    } else {
        mainWindow?.maximize();
    }
});

ipcMain.handle('window:close', () => {
    mainWindow?.hide();
});

ipcMain.handle('window:popout', (event, streamUrl) => {
    createPopoutWindow(streamUrl);
});

ipcMain.handle('autostart:get', () => {
    const settings = app.getLoginItemSettings();
    return settings.openAtLogin;
});

ipcMain.handle('autostart:set', (event, enable) => {
    app.setLoginItemSettings({
        openAtLogin: enable,
        path: process.execPath
    });
    return true;
});

ipcMain.handle('phone:control', (event, { action, value }) => {
    sendPhoneControl(action, value || '');
    return true;
});

ipcMain.handle('updater:check', async () => {
    return await checkForDesktopUpdates(app.getVersion());
});

ipcMain.handle('app:get-version', () => {
    return app.getVersion();
});

ipcMain.handle('shell:open-external', (event, url) => {
    shell.openExternal(url);
});
