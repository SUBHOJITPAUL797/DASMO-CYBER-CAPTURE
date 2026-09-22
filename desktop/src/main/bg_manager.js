const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { resolvePythonExecutable, getDriverScriptPath } = require('./bridge');

class BackgroundRemovalManager {
    constructor() {
        this.process = null;
        this.requestId = 0;
        this.pendingRequests = new Map();
        this.buffer = '';
    }

    startWorker() {
        if (this.process) return;
        const pythonExe = resolvePythonExecutable();
        const scriptPath = getDriverScriptPath('bg_remove.py');

        console.log(`[BgRemovalManager] Starting worker: ${pythonExe} "${scriptPath}" --server`);

        try {
            this.process = spawn(pythonExe, [scriptPath, '--server'], {
                stdio: ['pipe', 'pipe', 'pipe']
            });

            this.process.stdout.on('data', (chunk) => {
                this.buffer += chunk.toString();
                let newlineIdx;
                while ((newlineIdx = this.buffer.indexOf('\n')) !== -1) {
                    const line = this.buffer.slice(0, newlineIdx).trim();
                    this.buffer = this.buffer.slice(newlineIdx + 1);
                    if (line) {
                        try {
                            const res = JSON.parse(line);
                            if (res.id && this.pendingRequests.has(res.id)) {
                                const { resolve } = this.pendingRequests.get(res.id);
                                this.pendingRequests.delete(res.id);
                                resolve(res);
                            }
                        } catch (e) {
                            console.error('[BgRemovalManager] Parse JSON error:', e);
                        }
                    }
                }
            });

            this.process.stderr.on('data', (errChunk) => {
                console.log('[BgRemovalWorker]', errChunk.toString().trim());
            });

            this.process.on('close', (code) => {
                console.log(`[BgRemovalManager] Worker exited with code ${code}`);
                this.process = null;
                for (const [id, { reject }] of this.pendingRequests.entries()) {
                    reject(new Error(`Worker process exited before response (code ${code})`));
                }
                this.pendingRequests.clear();
            });

            this.process.on('error', (err) => {
                console.error('[BgRemovalManager] Worker spawn error:', err);
                this.process = null;
            });

        } catch (err) {
            console.error('[BgRemovalManager] Failed to start worker:', err);
            this.process = null;
        }
    }

    async removeBackground(imageBase64, model = 'u2net_human_seg') {
        if (!this.process) {
            this.startWorker();
        }

        if (!this.process || !this.process.stdin) {
            throw new Error('Failed to initialize Python background removal worker.');
        }

        const id = ++this.requestId;
        const payload = JSON.stringify({
            action: 'remove_bg',
            id,
            image: imageBase64,
            model
        }) + '\n';

        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                if (this.pendingRequests.has(id)) {
                    this.pendingRequests.delete(id);
                    reject(new Error('Background removal timed out after 35 seconds'));
                }
            }, 35000);

            this.pendingRequests.set(id, {
                resolve: (data) => {
                    clearTimeout(timeout);
                    resolve(data);
                },
                reject: (err) => {
                    clearTimeout(timeout);
                    reject(err);
                }
            });

            try {
                this.process.stdin.write(payload);
            } catch (writeErr) {
                clearTimeout(timeout);
                this.pendingRequests.delete(id);
                reject(writeErr);
            }
        });
    }

    stopWorker() {
        if (this.process) {
            try {
                this.process.stdin.write(JSON.stringify({ action: 'exit' }) + '\n');
            } catch (_) {}
            setTimeout(() => {
                if (this.process) {
                    try { this.process.kill(); } catch (_) {}
                    this.process = null;
                }
            }, 1000);
        }
    }
}

module.exports = { BackgroundRemovalManager };
