const { spawn, exec } = require('child_process');
const path = require('path');
const fs = require('fs');
const EventEmitter = require('events');

class HardwareDriverBridge extends EventEmitter {
    constructor() {
        super();
        this.bridgeProcess = null;
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
                
                // Check opencv and pyvirtualcam
                exec('python -c "import cv2, pyvirtualcam; print(\'OK\')"', (pkgErr, pkgStdout) => {
                    const hasPackages = !pkgErr && pkgStdout.includes('OK');
                    resolve({
                        hasPython: true,
                        version: versionStr,
                        hasPackages: hasPackages,
                        message: hasPackages ? 'Virtual Camera Driver Ready' : 'Dependencies need installation (opencv-python, pyvirtualcam)'
                    });
                });
            });
        });
    }

    installDependencies() {
        return new Promise((resolve, reject) => {
            const child = exec('pip install opencv-python pyvirtualcam', (err, stdout, stderr) => {
                if (err) {
                    return reject(new Error(stderr || err.message));
                }
                resolve(stdout);
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

        const scriptPath = path.join(__dirname, '../../drivers/dasmo_virtualcam.py');

        // Check if driver script exists
        if (!fs.existsSync(scriptPath)) {
            this.status = 'error';
            this.emit('status_change', { status: 'error', message: 'Driver script dasmo_virtualcam.py not found.' });
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

    getStatus() {
        return {
            isActive: this.isActive,
            status: this.status,
            deviceIp: this.currentDeviceIp
        };
    }
}

module.exports = { HardwareDriverBridge };
