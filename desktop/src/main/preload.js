const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('dasmoAPI', {
    // Auto-Discovery API
    getDiscoveredDevices: () => ipcRenderer.invoke('discovery:get-devices'),
    scanNetworkNow: () => ipcRenderer.invoke('discovery:scan-now'),
    onDeviceFound: (callback) => {
        const handler = (event, device) => callback(device);
        ipcRenderer.on('discovery:device_found', handler);
        return () => ipcRenderer.removeListener('discovery:device_found', handler);
    },
    onDeviceLost: (callback) => {
        const handler = (event, deviceId) => callback(deviceId);
        ipcRenderer.on('discovery:device_lost', handler);
        return () => ipcRenderer.removeListener('discovery:device_lost', handler);
    },

    // Active Connection
    setActiveDevice: (device) => ipcRenderer.invoke('connection:set-active', device),
    getActiveDevice: () => ipcRenderer.invoke('connection:get-active'),
    onConnectionChanged: (callback) => {
        const handler = (event, device) => callback(device);
        ipcRenderer.on('connection:changed', handler);
        return () => ipcRenderer.removeListener('connection:changed', handler);
    },

    // Virtual Camera Driver Bridge
    checkDriverEnvironment: () => ipcRenderer.invoke('driver:check-env'),
    installDriverDependencies: () => ipcRenderer.invoke('driver:install-deps'),
    installAudioDriver: () => ipcRenderer.invoke('driver:install-audio'),
    startVirtualCamera: (phoneIp) => ipcRenderer.invoke('driver:start-cam', phoneIp),
    stopVirtualCamera: () => ipcRenderer.invoke('driver:stop-cam'),
    getDriverStatus: () => ipcRenderer.invoke('driver:get-status'),
    onDriverStatusChange: (callback) => {
        const handler = (event, data) => callback(data);
        ipcRenderer.on('driver:status_change', handler);
        return () => ipcRenderer.removeListener('driver:status_change', handler);
    },

    // Window Controls
    minimizeWindow: () => ipcRenderer.invoke('window:minimize'),
    maximizeWindow: () => ipcRenderer.invoke('window:maximize'),
    closeWindow: () => ipcRenderer.invoke('window:close'),
    popoutPreview: (streamUrl) => ipcRenderer.invoke('window:popout', streamUrl),

    // System Autostart
    getAutoStart: () => ipcRenderer.invoke('autostart:get'),
    setAutoStart: (enable) => ipcRenderer.invoke('autostart:set', enable),

    // Phone Remote Control
    sendPhoneControl: (action, value) => ipcRenderer.invoke('phone:control', { action, value }),

    // OTA Updates
    checkForUpdates: () => ipcRenderer.invoke('updater:check'),

    // External URLs
    openExternal: (url) => ipcRenderer.invoke('shell:open-external', url)
});
