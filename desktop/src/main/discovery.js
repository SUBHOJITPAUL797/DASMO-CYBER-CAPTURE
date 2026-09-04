const http = require('http');
const os = require('os');
const EventEmitter = require('events');

class NetworkDiscoveryEngine extends EventEmitter {
    constructor() {
        super();
        this.discoveredDevices = new Map();
        this.isScanning = false;
        this.scanInterval = null;
        this.bonjour = null;
        this.isConnected = false;
    }

    setIsConnected(connected) {
        this.isConnected = !!connected;
    }

    startDiscovery() {
        // 1. Try mDNS discovery via bonjour-service
        try {
            const { Bonjour } = require('bonjour-service');
            this.bonjour = new Bonjour();

            this.bonjour.find({ type: 'dasmo-cyber-capture' }, (service) => {
                const ip = service.addresses && service.addresses.find(a => a.includes('.') && !a.startsWith('127.')) || service.host;
                const port = service.port || 8080;
                this.probeAndRegisterDevice(ip, port, service.name || 'DASMO-PHONE');
            });
        } catch (e) {
            console.log('[Discovery] mDNS module initialization notice:', e.message);
        }

        // 2. Initial Fast Subnet Scan
        this.scanLocalSubnet();

        // 3. Periodic background scan (every 15 seconds when idle, skipped when connected)
        if (!this.scanInterval) {
            this.scanInterval = setInterval(() => {
                if (!this.isConnected) {
                    this.scanLocalSubnet();
                }
            }, 15000);
        }
    }

    stopDiscovery() {
        if (this.scanInterval) {
            clearInterval(this.scanInterval);
            this.scanInterval = null;
        }
        if (this.bonjour) {
            try { this.bonjour.destroy(); } catch (_) {}
            this.bonjour = null;
        }
    }

    getLocalSubnets() {
        const subnets = [];
        const interfaces = os.networkInterfaces();
        for (const name of Object.keys(interfaces)) {
            for (const iface of interfaces[name]) {
                if (iface.family === 'IPv4' && !iface.internal) {
                    const parts = iface.address.split('.');
                    if (parts.length === 4) {
                        subnets.push({
                            prefix: `${parts[0]}.${parts[1]}.${parts[2]}`,
                            myIp: iface.address
                        });
                    }
                }
            }
        }
        return subnets;
    }

    async scanLocalSubnet() {
        if (this.isScanning) return;
        this.isScanning = true;

        try {
            const subnets = this.getLocalSubnets();
            const allTargets = [];

            for (const sub of subnets) {
                for (let i = 1; i <= 254; i++) {
                    const targetIp = `${sub.prefix}.${i}`;
                    if (targetIp !== sub.myIp) {
                        allTargets.push(targetIp);
                    }
                }
            }

            // Industrial Batching: Probe in chunks of 20 concurrent sockets to prevent Wi-Fi jitter
            const batchSize = 20;
            for (let i = 0; i < allTargets.length; i += batchSize) {
                if (this.isConnected) break; // Abort scan immediately if device connected mid-scan
                const batch = allTargets.slice(i, i + batchSize);
                await Promise.allSettled(batch.map(ip => this.probeIp(ip, 8080)));
            }
        } finally {
            this.isScanning = false;
        }
    }

    probeIp(ip, port = 8080) {
        return new Promise((resolve) => {
            const req = http.get({
                host: ip,
                port: port,
                path: '/status.json',
                timeout: 1200
            }, (res) => {
                if (res.statusCode === 200) {
                    let rawData = '';
                    res.on('data', chunk => rawData += chunk);
                    res.on('end', () => {
                        try {
                            const json = JSON.parse(rawData);
                            if (json.app && (json.app.includes('DASMO') || json.app.includes('AirLink') || json.app.includes('CYBER'))) {
                                this.probeAndRegisterDevice(ip, port, json.app, json);
                            }
                        } catch (_) {}
                        resolve();
                    });
                } else {
                    res.resume();
                    resolve();
                }
            });

            req.on('error', () => resolve());
            req.on('timeout', () => {
                req.destroy();
                resolve();
            });
        });
    }

    probeAndRegisterDevice(ip, port, deviceName, statusData = null) {
        const id = `${ip}:${port}`;
        const existing = this.discoveredDevices.get(id);
        const now = Date.now();

        const device = {
            id: id,
            ip: ip,
            port: port,
            name: deviceName || (existing ? existing.name : 'DASMO Phone'),
            lastSeen: now,
            status: statusData || (existing ? existing.status : {}),
            streamUrl: `http://${ip}:${port}/video_feed`,
            snapshotUrl: `http://${ip}:${port}/snapshot.jpg`,
            audioUrl: `http://${ip}:${port}/audio_feed`,
            speakerUrl: `http://${ip}:${port}/speaker_feed`,
            controlUrl: `http://${ip}:${port}/api/control`
        };

        this.discoveredDevices.set(id, device);
        this.emit('device_discovered', device);
    }

    getDevices() {
        const now = Date.now();
        const active = [];
        for (const [id, dev] of this.discoveredDevices.entries()) {
            if (now - dev.lastSeen < 20000) { // Considered active if seen in last 20s
                active.push(dev);
            } else {
                this.discoveredDevices.delete(id);
                this.emit('device_lost', id);
            }
        }
        return active;
    }
}

module.exports = { NetworkDiscoveryEngine };
