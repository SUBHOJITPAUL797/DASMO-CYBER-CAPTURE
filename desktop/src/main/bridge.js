const { spawn, exec } = require('child_process');
const path = require('path');
const fs = require('fs');
const { app } = require('electron');
const EventEmitter = require('events');

function getDriverScriptPath(scriptName) {
    if (app && app.isPackaged) {
        const unpackedPath = path.join(process.resourcesPath, 'app.asar.unpacked', 'drivers', scriptName);
        if (fs.existsSync(unpackedPath)) return unpackedPath;
        const resPath = path.join(process.resourcesPath, 'drivers', scriptName);
        if (fs.existsSync(resPath)) return resPath;
    }
    const devPath = path.join(__dirname, '../../drivers', scriptName);
    if (fs.existsSync(devPath)) return devPath;
    return path.join(process.cwd(), 'drivers', scriptName);
}

class HardwareDriverBridge extends EventEmitter {
    constructor() {
        super();
        this.bridgeProcess = null;
        this.audioProcess = null;
        this.isActive = false;
        this.currentDeviceIp = null;
        this.status = 'idle'; // idle | launching | running | error
    }

    checkEnvironment() {
        return new Promise((resolve) => {
            exec('python --version', (err, stdout, stderr) => {
                if (err) {
                    return resolve({
                        hasPython: false,
                        version: null,
                        message: 'Python not detected in PATH'
                    });
                }
                const versionStr = (stdout || stderr || '').trim();
                
                // Check opencv, pyvirtualcam, and sounddevice
                exec('python -c "import cv2, pyvirtualcam, sounddevice; print(\'OK\')"', (pkgErr, pkgStdout) => {
                    const hasPackages = !pkgErr && pkgStdout.includes('OK');
                    resolve({
                        hasPython: true,
                        version: versionStr,
                        hasPackages: hasPackages,
                        message: hasPackages ? '✓ Virtual Camera & Audio Drivers Ready' : 'Dependencies need installation'
                    });
                });
            });
        });
    }

    installDependencies() {
        return new Promise((resolve, reject) => {
            // Use python -m pip to avoid 'pip not found' error
            const child = exec('python -m pip install --quiet opencv-python pyvirtualcam sounddevice numpy', (err, stdout, stderr) => {
                if (err) {
                    return reject(new Error(stderr || err.message));
                }
                resolve(stdout || 'Dependencies installed successfully');
            });
        });
    }

    startVirtualCamera(phoneIp) {
        if (this.isActive && this.bridgeProcess) {
            this.stopVirtualCamera();
        }

        this.currentDeviceIp = phoneIp;
        this.status = 'launching';
        this.emit('status_change', { status: this.status, message: 'Launching DASMO Virtual Camera Bridge...' });

        const scriptPath = getDriverScriptPath('dasmo_virtualcam.py');

        // Check if driver script exists
        if (!fs.existsSync(scriptPath)) {
            this.status = 'error';
            this.emit('status_change', { status: 'error', message: `Driver script not found: ${scriptPath}` });
            return;
        }

        try {
            this.bridgeProcess = spawn('python', [scriptPath, phoneIp], {
                stdio: ['pipe', 'pipe', 'pipe']
            });

            this.isActive = true;
            this.status = 'running';

            this.bridgeProcess.stdout.on('data', (data) => {
                const text = data.toString();
                console.log('[VirtualCam Driver]', text);
                if (text.includes('[SUCCESS]') || text.includes('Virtual Camera active')) {
                    this.emit('status_change', { status: 'running', message: 'DASMO CYBER CAPTURE Virtual Camera active in Windows!' });
                }
            });

            this.bridgeProcess.stderr.on('data', (data) => {
                console.warn('[VirtualCam Driver Warning]', data.toString());
            });

            this.bridgeProcess.on('exit', (code) => {
                this.isActive = false;
                this.bridgeProcess = null;
                this.status = 'idle';
                this.emit('status_change', { status: 'idle', message: `Virtual camera bridge stopped (code ${code})` });
            });

            this.bridgeProcess.on('error', (err) => {
                this.isActive = false;
                this.bridgeProcess = null;
                this.status = 'error';
                this.emit('status_change', { status: 'error', message: `Driver execution error: ${err.message}` });
            });
        } catch (e) {
            this.status = 'error';
            this.emit('status_change', { status: 'error', message: e.message });
        }
    }

    stopVirtualCamera() {
        if (this.bridgeProcess) {
            try {
                this.bridgeProcess.kill('SIGTERM');
            } catch (_) {}
            this.bridgeProcess = null;
        }
        this.isActive = false;
        this.status = 'idle';
        this.emit('status_change', { status: 'idle', message: 'Virtual camera bridge stopped' });
    }

    startAudioBridge(phoneIp) {
        if (this.audioProcess) {
            this.stopAudioBridge();
        }

        const scriptPath = getDriverScriptPath('dasmo_audio_bridge.py');
        if (!fs.existsSync(scriptPath)) return;

        try {
            this.audioProcess = spawn('python', [scriptPath, phoneIp], {
                stdio: ['pipe', 'pipe', 'pipe']
            });

            this.audioProcess.stdout.on('data', (d) => {
                console.log('[Audio Bridge]', d.toString());
            });

            this.audioProcess.on('exit', () => {
                this.audioProcess = null;
            });
        } catch (e) {
            console.warn('[Audio Bridge Error]', e.message);
        }
    }

    stopAudioBridge() {
        if (this.audioProcess) {
            try { this.audioProcess.kill('SIGTERM'); } catch (_) {}
            this.audioProcess = null;
        }
    }

    getStatus() {
        return {
            isActive: this.isActive,
            isAudioActive: !!this.audioProcess,
            status: this.status,
            deviceIp: this.currentDeviceIp
        };
    }
}

module.exports = { HardwareDriverBridge, getDriverScriptPath };
