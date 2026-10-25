const dgram = require('dgram');
const http = require('http');
const EventEmitter = require('events');

const DEFAULT_WAKE_PORT = 8089;

/**
 * WakeServer listens for wake signals from the DASMO CYBER CAPTURE Android mobile app.
 * Supports:
 *  1. UDP Broadcast packets on port 8089 (with reuseAddr: true)
 *  2. HTTP GET /wake requests on port 8089
 */
class WakeServer extends EventEmitter {
    constructor(port = DEFAULT_WAKE_PORT) {
        super();
        this.port = port;
        this.udpSocket = null;
        this.httpServer = null;
        this.lastWakeTime = 0;
    }

    start() {
        this._startUdpServer();
        this._startHttpServer();
    }

    _startUdpServer() {
        try {
            this.udpSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

            this.udpSocket.on('message', (msg, rinfo) => {
                try {
                    const text = msg.toString('utf-8').trim();
                    let data = {};
                    if (text.startsWith('{')) {
                        data = JSON.parse(text);
                    }

                    const isWakeCmd = data.cmd === 'WAKE_DESKTOP' ||
                                      data.action === 'wake' ||
                                      data.app === 'DASMO CYBER CAPTURE' ||
                                      text.includes('WAKE_DESKTOP');

                    if (isWakeCmd) {
                        const phoneIp = data.phone_ip || rinfo.address;
                        const port = data.port || 8080;
                        const phoneName = data.phone_name || data.device_name || `DASMO Phone (${phoneIp})`;
                        this._handleWake(phoneIp, port, phoneName, 'UDP');
                    }
                } catch (e) {
                    console.warn('[WakeServer UDP] Error parsing message:', e.message);
                }
            });

            this.udpSocket.on('error', (err) => {
                console.warn('[WakeServer UDP Error]', err.message);
                try { this.udpSocket.close(); } catch (_) {}
            });

            this.udpSocket.bind(this.port, '0.0.0.0', () => {
                console.log(`[+] WakeServer UDP broadcast listener active on 0.0.0.0:${this.port}`);
            });
        } catch (e) {
            console.warn('[WakeServer] Failed to initialize UDP socket:', e.message);
        }
    }

    _startHttpServer() {
        try {
            this.httpServer = http.createServer((req, res) => {
                // Enable CORS
                res.setHeader('Access-Control-Allow-Origin', '*');
                res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
                res.setHeader('Access-Control-Allow-Headers', '*');

                if (req.method === 'OPTIONS') {
                    res.writeHead(204);
                    res.end();
                    return;
                }

                const urlObj = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
                if (urlObj.pathname === '/wake' || urlObj.pathname === '/open' || urlObj.pathname === '/ping') {
                    const clientIp = (req.socket.remoteAddress || '').replace(/^.*:/, '');
                    const phoneIp = urlObj.searchParams.get('phone_ip') || clientIp;
                    const port = parseInt(urlObj.searchParams.get('port') || '8080', 10);
                    const phoneName = urlObj.searchParams.get('phone_name') || `DASMO Phone (${phoneIp})`;

                    this._handleWake(phoneIp, port, phoneName, 'HTTP');

                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({
                        status: 'ok',
                        app: 'DASMO CYBER CAPTURE',
                        message: 'Desktop client awakened successfully'
                    }));
                } else {
                    res.writeHead(404, { 'Content-Type': 'text/plain' });
                    res.end('Not Found');
                }
            });

            this.httpServer.on('error', (err) => {
                console.warn('[WakeServer HTTP Notice]', err.message);
            });

            this.httpServer.listen(this.port, '0.0.0.0', () => {
                console.log(`[+] WakeServer HTTP listener active on http://0.0.0.0:${this.port}/wake`);
            });
        } catch (e) {
            console.warn('[WakeServer] Failed to initialize HTTP server:', e.message);
        }
    }

    _handleWake(phoneIp, port, phoneName, protocol) {
        const now = Date.now();
        // Debounce wake triggers within 1.5 seconds to prevent multi-burst spam
        if (now - this.lastWakeTime < 1500) {
            return;
        }
        this.lastWakeTime = now;

        console.log(`[WakeServer] Received WAKE signal from ${phoneName} (${phoneIp}:${port}) via ${protocol}`);
        this.emit('wake', { phoneIp, port, phoneName, protocol });
    }

    stop() {
        if (this.udpSocket) {
            try { this.udpSocket.close(); } catch (_) {}
            this.udpSocket = null;
        }
        if (this.httpServer) {
            try { this.httpServer.close(); } catch (_) {}
            this.httpServer = null;
        }
    }
}

module.exports = { WakeServer, DEFAULT_WAKE_PORT };
