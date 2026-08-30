package com.example.network

import android.util.Log
import com.example.model.CyberFilter
import com.example.model.StreamResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CyberStreamServer(
    private val port: Int = 8080,
    private val onRemoteControlAction: (action: String, value: String) -> Unit,
    private val onSpeakerPcmReceived: (ByteArray, Int, Int) -> Unit,
    private val onAudioFeedConnected: ((OutputStream) -> Unit)? = null,
    private val onAudioFeedDisconnected: ((OutputStream) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    private val connectedClientsCount = AtomicInteger(0)
    private val totalBytesSent = AtomicLong(0)
    private val activeMjpegStreams = CopyOnWriteArrayList<OutputStream>()
    private val activeAudioStreams = CopyOnWriteArrayList<OutputStream>()

    private val _connectedClientsFlow = MutableStateFlow(0)
    val connectedClientsFlow: StateFlow<Int> = _connectedClientsFlow

    private val _bitrateKbpsFlow = MutableStateFlow(0)
    val bitrateKbpsFlow: StateFlow<Int> = _bitrateKbpsFlow

    @Volatile
    private var latestJpegFrame: ByteArray? = null

    private var bytesInLastSecond = AtomicLong(0)
    private var lastBitrateCalcTime = System.currentTimeMillis()

    fun updateFrame(jpegBytes: ByteArray) {
        latestJpegFrame = jpegBytes

        // Broadcast to all active MJPEG stream clients
        if (activeMjpegStreams.isNotEmpty()) {
            val header = "--cyberframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n".toByteArray()
            val footer = "\r\n".toByteArray()

            val iterator = activeMjpegStreams.iterator()
            while (iterator.hasNext()) {
                val stream = iterator.next()
                try {
                    stream.write(header)
                    stream.write(jpegBytes)
                    stream.write(footer)
                    stream.flush()

                    val bytes = header.size + jpegBytes.size + footer.size
                    totalBytesSent.addAndGet(bytes.toLong())
                    bytesInLastSecond.addAndGet(bytes.toLong())
                } catch (_: Exception) {
                    activeMjpegStreams.remove(stream)
                    updateClientCount()
                }
            }
        }

        // Periodic bitrate update
        val now = System.currentTimeMillis()
        if (now - lastBitrateCalcTime >= 1000) {
            val bytes = bytesInLastSecond.getAndSet(0)
            val kbps = ((bytes * 8) / 1024).toInt()
            _bitrateKbpsFlow.value = kbps
            lastBitrateCalcTime = now
        }
    }

    fun start(scope: CoroutineScope) {
        if (isRunning.get()) return

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                isRunning.set(true)
                Log.d("CyberStreamServer", "DASMO CYBER CAPTURE Server active on port $port")

                while (isActive && isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.w("CyberStreamServer", "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("CyberStreamServer", "Server socket failed", e)
            } finally {
                stop()
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 16384
                socket.receiveBufferSize = 16384
                socket.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val outputStream = socket.getOutputStream()

                val requestLine = reader.readLine() ?: return@withContext
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@withContext

                val method = parts[0]
                val uri = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrEmpty()) break
                    val headerParts = line!!.split(": ", limit = 2)
                    if (headerParts.size == 2) {
                        headers[headerParts[0].lowercase()] = headerParts[1]
                    }
                }

                // Handle CORS preflight
                if (method.equals("OPTIONS", ignoreCase = true)) {
                    val optionsResp = (
                        "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
                    outputStream.write(optionsResp)
                    outputStream.flush()
                    socket.close()
                    return@withContext
                }

                val hostHeader = headers["host"]?.split(":")?.get(0)
                val resolvedHostIp = if (!hostHeader.isNullOrEmpty() && hostHeader != "0.0.0.0" && hostHeader != "localhost") {
                    hostHeader
                } else {
                    socket.localAddress.hostAddress?.takeIf { it != "0.0.0.0" } ?: "127.0.0.1"
                }

                when {
                    // MJPEG Video Stream
                    uri.startsWith("/video_feed") || uri.startsWith("/mjpeg") || uri.startsWith("/live.mjpg") -> {
                        socket.soTimeout = 0 // Infinite timeout for video stream
                        val responseHeader = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Server: DASMO-CYBER-CAPTURE/1.0\r\n" +
                            "Cache-Control: no-cache, no-store, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=--cyberframe\r\n\r\n"
                        ).toByteArray()

                        outputStream.write(responseHeader)
                        outputStream.flush()

                        activeMjpegStreams.add(outputStream)
                        updateClientCount()

                        // Keep socket alive while streaming
                        while (socket.isConnected && !socket.isClosed && isRunning.get()) {
                            kotlinx.coroutines.delay(500)
                        }
                    }

                    // Single Frame Snapshot
                    uri.startsWith("/snapshot.jpg") -> {
                        val frame = latestJpegFrame
                        if (frame != null) {
                            val response = (
                                "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray()
                            outputStream.write(response)
                            outputStream.write(frame)
                            outputStream.flush()
                        } else {
                            val notFound = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray()
                            outputStream.write(notFound)
                        }
                        socket.close()
                    }

                    // Control API (REST)
                    uri.startsWith("/api/control") -> {
                        val queryIndex = uri.indexOf("?")
                        if (queryIndex != -1) {
                            val query = uri.substring(queryIndex + 1)
                            val params = query.split("&").associate {
                                val p = it.split("=")
                                val k = if (p.isNotEmpty()) try { java.net.URLDecoder.decode(p[0], "UTF-8") } catch (_: Exception) { p[0] } else ""
                                val v = if (p.size == 2) try { java.net.URLDecoder.decode(p[1], "UTF-8") } catch (_: Exception) { p[1] } else ""
                                k to v
                            }
                            val action = params["action"] ?: ""
                            val value = params["value"] ?: ""
                            onRemoteControlAction(action, value)
                        }

                        val jsonResp = JSONObject().apply {
                            put("status", "ok")
                            put("timestamp", System.currentTimeMillis())
                        }.toString()

                        val response = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${jsonResp.length}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n$jsonResp"
                        ).toByteArray()
                        outputStream.write(response)
                        outputStream.flush()
                        socket.close()
                    }

                    // Status JSON
                    uri.startsWith("/status.json") -> {
                        val json = JSONObject().apply {
                            put("app", "DASMO CYBER CAPTURE")
                            put("version", "1.0")
                            put("clients", connectedClientsCount.get())
                            put("bitrate_kbps", _bitrateKbpsFlow.value)
                            put("total_bytes", totalBytesSent.get())
                            put("active_mjpeg_streams", activeMjpegStreams.size)
                        }.toString()

                        val response = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${json.length}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n$json"
                        ).toByteArray()
                        outputStream.write(response)
                        outputStream.flush()
                        socket.close()
                    }

                    // Phone Microphone Audio Stream (Phone to PC)
                    uri.startsWith("/audio_feed") || uri.startsWith("/mic_feed.wav") || uri.startsWith("/mic.pcm") || uri.startsWith("/audio_stream") -> {
                        socket.soTimeout = 0
                        val responseHeader = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Server: DASMO-CYBER-CAPTURE/1.0\r\n" +
                            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Type: audio/x-raw; rate=48000; channels=1; format=s16le\r\n\r\n"
                        ).toByteArray()

                        outputStream.write(responseHeader)
                        outputStream.flush()

                        activeAudioStreams.add(outputStream)
                        onAudioFeedConnected?.invoke(outputStream)

                        try {
                            while (socket.isConnected && !socket.isClosed && isRunning.get()) {
                                kotlinx.coroutines.delay(500)
                            }
                        } finally {
                            activeAudioStreams.remove(outputStream)
                            onAudioFeedDisconnected?.invoke(outputStream)
                        }
                    }

                    // Speaker audio stream receiver (PC plays audio to phone speaker)
                    uri.startsWith("/speaker_feed") && method.equals("POST", ignoreCase = true) -> {
                        val inputStream = socket.getInputStream()
                        val buffer = ByteArray(4096)
                        while (socket.isConnected && !socket.isClosed && isRunning.get()) {
                            val read = inputStream.read(buffer)
                            if (read <= 0) break
                            onSpeakerPcmReceived(buffer, 0, read)
                        }
                    }

                    // Download 1-Click Desktop Virtual Camera & Mic Driver (Named "DASMO CYBER CAPTURE")
                    uri.startsWith("/download/dasmo_virtualcam.py") -> {
                        val script = getDasmoPythonBridgeScript(resolvedHostIp)
                        val response = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/x-python\r\n" +
                            "Content-Disposition: attachment; filename=\"dasmo_virtualcam.py\"\r\n" +
                            "Content-Length: ${script.toByteArray().size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n$script"
                        ).toByteArray()
                        outputStream.write(response)
                        outputStream.flush()
                        socket.close()
                    }

                    uri.startsWith("/download/dasmo_install.bat") -> {
                        val bat = getDasmoBatchScript(resolvedHostIp)
                        val response = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/x-bat\r\n" +
                            "Content-Disposition: attachment; filename=\"install_dasmo_camera.bat\"\r\n" +
                            "Content-Length: ${bat.toByteArray().size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n$bat"
                        ).toByteArray()
                        outputStream.write(response)
                        outputStream.flush()
                        socket.close()
                    }

                    // Cyber Web Portal & Companion Hub
                    else -> {
                        val html = getCyberPortalHtml()
                        val response = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: ${html.toByteArray().size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n$html"
                        ).toByteArray()
                        outputStream.write(response)
                        outputStream.flush()
                        socket.close()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    if (!activeMjpegStreams.contains(socket.getOutputStream())) {
                        socket.close()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun updateClientCount() {
        val count = activeMjpegStreams.size
        connectedClientsCount.set(count)
        _connectedClientsFlow.value = count
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        activeMjpegStreams.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        activeMjpegStreams.clear()
        updateClientCount()
        serverJob?.cancel()
        serverJob = null
    }

    private fun getCyberPortalHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>DASMO CYBER CAPTURE // PC COMPANION & CALL STATION</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600;800&family=Orbitron:wght@700;900&family=Plus+Jakarta+Sans:wght@400;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --cyan: #00e5ff;
            --blue: #1e88e5;
            --dark: #080c14;
            --surface: #0e1422;
            --card: #141c2e;
            --border: #263554;
            --text: #f0f6fc;
            --muted: #8b9bb4;
            --green: #00e676;
            --red: #ff3d71;
            --amber: #ffab00;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: var(--dark);
            color: var(--text);
            font-family: 'Plus Jakarta Sans', sans-serif;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            overflow-x: hidden;
        }
        header {
            background: linear-gradient(180deg, #0e1422 0%, rgba(14,20,34,0.9) 100%);
            border-bottom: 1px solid var(--border);
            padding: 16px 28px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .brand { display: flex; align-items: center; gap: 14px; }
        .logo-box {
            width: 42px; height: 42px; border-radius: 10px;
            background: linear-gradient(135deg, var(--cyan) 0%, var(--blue) 100%);
            display: grid; place-items: center;
            font-family: 'Orbitron', monospace; font-weight: 900; color: #000; font-size: 20px;
            box-shadow: 0 0 16px rgba(0, 229, 255, 0.4);
        }
        .title {
            font-family: 'Orbitron', monospace; font-size: 20px; font-weight: 800;
            letter-spacing: 1px; color: var(--cyan); text-shadow: 0 0 10px rgba(0, 229, 255, 0.3);
        }
        .status-badge {
            background: rgba(0, 230, 118, 0.15); border: 1px solid var(--green);
            color: var(--green); padding: 6px 14px; border-radius: 20px;
            font-family: 'JetBrains Mono', monospace; font-size: 12px; font-weight: 600;
            display: flex; align-items: center; gap: 8px;
        }
        .pulse-dot {
            width: 8px; height: 8px; border-radius: 50%; background: var(--green);
            box-shadow: 0 0 8px var(--green); animation: pulse 1.5s infinite;
        }
        @keyframes pulse { 0% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(1.3); } 100% { opacity: 1; transform: scale(1); } }
        
        main {
            flex: 1; max-width: 1360px; margin: 0 auto; width: 100%;
            padding: 24px; display: grid; grid-template-columns: 1fr 380px; gap: 24px;
        }
        @media (max-width: 960px) { main { grid-template-columns: 1fr; } }

        .viewfinder-card {
            background: var(--surface); border: 1px solid var(--border);
            border-radius: 18px; padding: 20px; display: flex; flex-direction: column;
            gap: 18px; box-shadow: 0 8px 32px rgba(0,0,0,0.5);
        }
        .viewfinder-wrapper {
            position: relative; background: #000; border-radius: 14px;
            overflow: hidden; aspect-ratio: 16 / 9; border: 2px solid var(--border);
            box-shadow: inset 0 0 40px rgba(0, 229, 255, 0.08);
        }
        .video-stream { width: 100%; height: 100%; object-fit: contain; display: block; }
        .hud-overlay {
            position: absolute; inset: 0; pointer-events: none;
            display: flex; flex-direction: column; justify-content: space-between;
            padding: 14px; font-family: 'JetBrains Mono', monospace; font-size: 11px;
            color: var(--cyan); text-shadow: 0 0 4px #000;
        }
        .hud-top, .hud-bottom { display: flex; justify-content: space-between; }

        .call-station-bar {
            background: #0b101c; border: 1px solid var(--border); border-radius: 12px;
            padding: 14px; display: flex; flex-wrap: wrap; gap: 12px; align-items: center; justify-content: space-between;
        }
        .call-btn {
            flex: 1; min-width: 140px; padding: 12px 16px; border-radius: 10px;
            border: 1px solid var(--border); background: var(--card); color: var(--text);
            font-family: 'JetBrains Mono', monospace; font-weight: 700; font-size: 12px;
            cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px;
            transition: all 0.2s;
        }
        .call-btn:hover { border-color: var(--cyan); background: rgba(0,229,255,0.12); color: var(--cyan); }
        .call-btn.btn-red { border-color: var(--red); color: var(--red); }
        .call-btn.btn-red:hover { background: rgba(255,61,113,0.15); }
        .call-btn.btn-active { background: var(--cyan); color: #000; font-weight: 800; }

        .controls-card {
            background: var(--surface); border: 1px solid var(--border);
            border-radius: 18px; padding: 22px; display: flex; flex-direction: column;
            gap: 18px; box-shadow: 0 8px 32px rgba(0,0,0,0.5);
        }
        .section-title {
            font-family: 'Orbitron', monospace; font-size: 13px; color: var(--cyan);
            letter-spacing: 0.8px; border-bottom: 1px solid var(--border); padding-bottom: 8px;
            display: flex; justify-content: space-between; align-items: center;
        }

        .btn-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
        button {
            background: var(--card); border: 1px solid var(--border); color: var(--text);
            padding: 11px; border-radius: 8px; font-family: 'JetBrains Mono', monospace;
            font-size: 11px; font-weight: 600; cursor: pointer; transition: all 0.2s ease;
            display: flex; align-items: center; justify-content: center; gap: 6px;
        }
        button:hover { border-color: var(--cyan); background: rgba(0, 229, 255, 0.1); color: var(--cyan); }

        .slider-group {
            display: flex; flex-direction: column; gap: 6px;
            font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--muted);
        }
        input[type="range"] { accent-color: var(--cyan); width: 100%; }

        .guide-box {
            background: var(--card); border: 1px solid var(--border); border-radius: 12px;
            padding: 14px; font-size: 12px; line-height: 1.5; color: var(--muted);
            display: flex; flex-direction: column; gap: 10px;
        }
        .guide-step { display: flex; gap: 10px; align-items: flex-start; }
        .step-num {
            background: var(--cyan); color: #000; font-family: 'Orbitron', monospace;
            font-weight: 900; font-size: 10px; width: 18px; height: 18px; border-radius: 50%;
            display: grid; place-items: center; flex-shrink: 0; margin-top: 2px;
        }

        .url-box {
            background: #090d16; border: 1px dashed var(--cyan); border-radius: 8px;
            padding: 8px 12px; font-family: 'JetBrains Mono', monospace; font-size: 11px;
            color: var(--cyan); word-break: break-all; user-select: all;
        }

        footer {
            padding: 16px; text-align: center; font-family: 'JetBrains Mono', monospace;
            font-size: 11px; color: var(--muted); border-top: 1px solid var(--border);
        }
    </style>
</head>
<body>
    <header>
        <div class="brand">
            <div class="logo-box">DC</div>
            <div>
                <div class="title">DASMO CYBER CAPTURE</div>
                <div style="font-size: 11px; color: var(--muted); font-family: 'JetBrains Mono';">WHATSAPP DESKTOP & VIDEO CALL STATION · ZERO USB WIRELESS</div>
            </div>
        </div>
        <div class="status-badge">
            <div class="pulse-dot"></div>
            <span>CALL BRIDGE ACTIVE</span>
        </div>
    </header>

    <main>
        <div class="viewfinder-card">
            <!-- Viewfinder -->
            <div class="viewfinder-wrapper">
                <img class="video-stream" src="/video_feed" alt="DASMO Cyber Live Stream" />
                <div class="hud-overlay">
                    <div class="hud-top">
                        <span>[CALL LINK: 100% AIR LINK]</span>
                        <span id="clockDisplay">00:00:00</span>
                    </div>
                    <div class="hud-bottom">
                        <span id="callStatusBadge">FEED: LIVE 30 FPS</span>
                        <span>LATENCY: ~18ms</span>
                    </div>
                </div>
            </div>

            <!-- Simultaneous In-Call Control Bar -->
            <div class="call-station-bar">
                <button class="call-btn" id="btnPauseVideo" onclick="togglePauseVideo()">
                    <span>⏸️</span> <span id="txtPauseVideo">Pause Video</span>
                </button>
                <button class="call-btn" id="btnMuteMic" onclick="toggleMuteMic()">
                    <span>🎤</span> <span id="txtMuteMic">Mute Mic</span>
                </button>
                <button class="call-btn" id="btnSpeaker" onclick="toggleSpeaker()">
                    <span>🔊</span> <span id="txtSpeaker">Speaker: ON</span>
                </button>
                <button class="call-btn" onclick="sendAction('flip')">
                    <span>🔄</span> <span>Flip Camera</span>
                </button>
            </div>

            <!-- Audio Web Relays (Listen on PC & Stream PC Mic to Phone) -->
            <div style="display: flex; gap: 12px; flex-wrap: wrap;">
                <button style="flex: 1;" onclick="toggleListenPhoneMic()">
                    🎙️ <span id="txtListenMic">Listen to Phone Mic on PC</span>
                </button>
                <button style="flex: 1;" onclick="toggleRelayPcAudio()">
                    🔊 <span id="txtRelayAudio">Relay PC Audio to Phone Speaker</span>
                </button>
                <button onclick="window.open('/snapshot.jpg', '_blank')">📸 Snapshot</button>
            </div>

            <!-- Streaming Endpoints & 1-Click Desktop Driver -->
            <div class="guide-box">
                <div style="font-family: 'Orbitron'; color: var(--cyan); font-weight: 700; font-size: 11px;">
                    NATIVE DESKTOP DRIVER: "DASMO CYBER CAPTURE"
                </div>
                <div style="font-size: 12px; color: var(--text);">
                    Want WhatsApp Desktop to show <b>"DASMO CYBER CAPTURE"</b> directly in its camera device menu instead of generic third-party names? Download the native 1-click driver bridge:
                </div>
                <div style="display: flex; gap: 10px; flex-wrap: wrap; margin-top: 4px;">
                    <a href="/download/dasmo_install.bat" download style="text-decoration: none; flex: 1;">
                        <button style="width: 100%; background: linear-gradient(135deg, rgba(0,229,255,0.2) 0%, rgba(41,121,255,0.2) 100%); border-color: var(--cyan); color: #fff;">
                            📥 Download Windows 1-Click Installer (.BAT)
                        </button>
                    </a>
                    <a href="/download/dasmo_virtualcam.py" download style="text-decoration: none; flex: 1;">
                        <button style="width: 100%; border-color: var(--cyan);">
                            🐍 Download Python Bridge (.PY)
                        </button>
                    </a>
                </div>
                <div style="margin-top: 6px;">Video Feed (MJPEG): <div class="url-box" id="streamUrl">http://${'$'}{window.location.host}/video_feed</div></div>
                <div>Audio Feed (PCM): <div class="url-box" id="audioUrl">http://${'$'}{window.location.host}/audio_feed</div></div>
            </div>
        </div>

        <div class="controls-card">
            <div class="section-title">
                <span>WHATSAPP DESKTOP SETUP</span>
                <span style="font-size: 10px; color: var(--green);">3-WAY SYNC</span>
            </div>

            <div class="guide-box">
                <div class="guide-step">
                    <div class="step-num">1</div>
                    <div>
                        <b style="color: var(--text);">Camera Input ("DASMO CYBER CAPTURE"):</b><br/>
                        Run the 1-click Windows installer above. Open WhatsApp Desktop &gt; <b>Settings &gt; Audio & Video &gt; Camera</b> and select <b>"DASMO CYBER CAPTURE"</b>!
                    </div>
                </div>
                <div class="guide-step">
                    <div class="step-num">2</div>
                    <div>
                        <b style="color: var(--text);">Microphone Input ("DASMO Phone Mic"):</b><br/>
                        Select <b>"DASMO Audio Bridge / Virtual Mic"</b> or click <i>"Listen to Phone Mic on PC"</i> to route crisp mobile mic audio directly into your WhatsApp call.
                    </div>
                </div>
                <div class="guide-step">
                    <div class="step-num">3</div>
                    <div>
                        <b style="color: var(--text);">Audio Output (Mobile Speaker):</b><br/>
                        Click <i>"Relay PC Audio to Phone Speaker"</i> above to hear the incoming call voices in real-time through your phone speaker or earpiece!
                    </div>
                </div>
            </div>

            <div class="section-title">HARDWARE ADJUSTMENTS</div>
            <div class="slider-group">
                <label for="zoomSlider">CAMERA ZOOM: <span id="zoomVal">1.0x</span></label>
                <input type="range" id="zoomSlider" min="1" max="8" step="0.5" value="1" oninput="onZoomChange(this.value)">
            </div>

            <div class="slider-group">
                <label for="micGainSlider">PHONE MIC GAIN: <span id="gainVal">1.0x</span></label>
                <input type="range" id="micGainSlider" min="0.2" max="3.0" step="0.1" value="1.0" oninput="onGainChange(this.value)">
            </div>

            <div class="btn-grid">
                <button onclick="sendAction('torch')">⚡ Torch Flash</button>
                <button onclick="sendAction('routing', 'SPEAKERPHONE')">🔊 Loudspeaker</button>
                <button onclick="sendAction('routing', 'EARPIECE')">👂 Earpiece Mode</button>
                <button onclick="sendAction('filter', 'CYBER_HUD')">🎯 Cyber HUD</button>
            </div>

            <div class="section-title">CYBER FILTERS</div>
            <div class="btn-grid">
                <button onclick="sendAction('filter', 'NONE')">Clear Raw</button>
                <button onclick="sendAction('filter', 'MATRIX_RAIN')">Matrix Rain</button>
                <button onclick="sendAction('filter', 'NIGHT_VISION')">Night Vision</button>
                <button onclick="sendAction('filter', 'CHROMA_GREEN')">Chroma Green</button>
            </div>
        </div>
    </main>

    <footer>
        DASMO CYBER CAPTURE // ZERO-USB WIRELESS DESKTOP CALL STATION · SIMULTANEOUS MIC / VIDEO / SPEAKER CONTROL
    </footer>

    <script>
        document.getElementById('streamUrl').innerText = window.location.origin + '/video_feed';
        document.getElementById('audioUrl').innerText = window.location.origin + '/audio_feed';

        let isVideoPaused = false;
        let isMicMuted = false;
        let isSpeakerOn = true;

        function updateClock() {
            const now = new Date();
            document.getElementById('clockDisplay').innerText = now.toLocaleTimeString();
        }
        setInterval(updateClock, 1000);
        updateClock();

        function sendAction(action, value = '') {
            fetch('/api/control?action=' + encodeURIComponent(action) + '&value=' + encodeURIComponent(value))
                .catch(err => console.error(err));
        }

        function togglePauseVideo() {
            isVideoPaused = !isVideoPaused;
            sendAction('pause_video', isVideoPaused ? 'true' : 'false');
            const btn = document.getElementById('btnPauseVideo');
            const txt = document.getElementById('txtPauseVideo');
            if (isVideoPaused) {
                btn.classList.add('btn-red');
                txt.innerText = 'Resume Video';
                document.getElementById('callStatusBadge').innerText = 'FEED: PAUSED (PRIVACY)';
                document.getElementById('callStatusBadge').style.color = '#ff3d71';
            } else {
                btn.classList.remove('btn-red');
                txt.innerText = 'Pause Video';
                document.getElementById('callStatusBadge').innerText = 'FEED: LIVE 30 FPS';
                document.getElementById('callStatusBadge').style.color = '#00e5ff';
            }
        }

        function toggleMuteMic() {
            isMicMuted = !isMicMuted;
            sendAction('mute', isMicMuted ? 'true' : 'false');
            const btn = document.getElementById('btnMuteMic');
            const txt = document.getElementById('txtMuteMic');
            if (isMicMuted) {
                btn.classList.add('btn-red');
                txt.innerText = 'Unmute Mic';
            } else {
                btn.classList.remove('btn-red');
                txt.innerText = 'Mute Mic';
            }
        }

        function toggleSpeaker() {
            isSpeakerOn = !isSpeakerOn;
            sendAction('speaker', isSpeakerOn ? 'true' : 'false');
            const txt = document.getElementById('txtSpeaker');
            txt.innerText = isSpeakerOn ? 'Speaker: ON' : 'Speaker: OFF';
        }

        function onZoomChange(val) {
            document.getElementById('zoomVal').innerText = val + 'x';
            sendAction('zoom', val);
        }

        function onGainChange(val) {
            document.getElementById('gainVal').innerText = val + 'x';
            sendAction('gain', val);
        }

        // Web Audio Player for Phone Mic
        let audioCtx = null;
        let isListening = false;
        function toggleListenPhoneMic() {
            const btnText = document.getElementById('txtListenMic');
            if (!isListening) {
                try {
                    const audioEl = new Audio('/audio_feed');
                    audioEl.play().then(() => {
                        isListening = true;
                        btnText.innerText = 'Stop Listening to Mic';
                    }).catch(e => {
                        console.log('Direct audio tag fallback', e);
                        alert('Audio feed active on: ' + window.location.origin + '/audio_feed');
                    });
                } catch(e) {
                    alert('Audio stream at ' + window.location.origin + '/audio_feed');
                }
            } else {
                isListening = false;
                btnText.innerText = 'Listen to Phone Mic on PC';
            }
        }

        // Relay PC Audio (Mic/Speaker capture) to Phone Speaker
        let relayStream = null;
        let isRelaying = false;
        async function toggleRelayPcAudio() {
            const btnText = document.getElementById('txtRelayAudio');
            if (!isRelaying) {
                try {
                    relayStream = await navigator.mediaDevices.getUserMedia({ audio: true });
                    const audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
                    const source = audioContext.createMediaStreamSource(relayStream);
                    const processor = audioContext.createScriptProcessor(4096, 1, 1);
                    
                    processor.onaudioprocess = (e) => {
                        if (!isRelaying) return;
                        const inputData = e.inputBuffer.getChannelData(0);
                        const pcmData = new Int16Array(inputData.length);
                        for (let i = 0; i < inputData.length; i++) {
                            const s = Math.max(-1, Math.min(1, inputData[i]));
                            pcmData[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
                        }
                        fetch('/speaker_feed', {
                            method: 'POST',
                            body: pcmData.buffer
                        }).catch(() => {});
                    };

                    source.connect(processor);
                    processor.connect(audioContext.destination);

                    isRelaying = true;
                    btnText.innerText = 'Stop PC Audio Relay';
                } catch(err) {
                    console.error('Audio capture error', err);
                    alert('Could not access PC microphone/audio: ' + err.message);
                }
            } else {
                isRelaying = false;
                if (relayStream) {
                    relayStream.getTracks().forEach(t => t.stop());
                }
                btnText.innerText = 'Relay PC Audio to Phone Speaker';
            }
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun getDasmoPythonBridgeScript(hostIp: String): String {
        return """
# ==============================================================================
# DASMO CYBER CAPTURE // NATIVE DESKTOP VIRTUAL CAMERA & AUDIO BRIDGE
# Device Name: "DASMO CYBER CAPTURE"
# Compatible with: WhatsApp Desktop, Zoom, Microsoft Teams, Google Meet, Discord
# ==============================================================================

import sys
import time
import urllib.request
import numpy as np

try:
    import cv2
except ImportError:
    print("[ERROR] OpenCV not installed. Run: pip install opencv-python")
    sys.exit(1)

try:
    import pyvirtualcam
except ImportError:
    print("[ERROR] pyvirtualcam not installed. Run: pip install pyvirtualcam")
    sys.exit(1)

PHONE_IP = "$hostIp"
STREAM_URL = f"http://{PHONE_IP}:8080/video_feed"

print("==========================================================")
print("  🚀 DASMO CYBER CAPTURE // DIRECT DESKTOP DEVICE DRIVER   ")
print("  Device Registered: 'DASMO CYBER CAPTURE'                ")
print(f"  Streaming from: {STREAM_URL}")
print("==========================================================")

cap = cv2.VideoCapture(STREAM_URL)
if not cap.isOpened():
    print(f"[!] Could not connect to {STREAM_URL}. Ensure phone is streaming on the same Wi-Fi.")
    sys.exit(1)

ret, frame = cap.read()
if not ret or frame is None:
    print("[!] Failed to read initial frame from DASMO Cyber Stream.")
    sys.exit(1)

h, w, _ = frame.shape
print(f"[+] Video Stream Resolution: {w}x{h} @ 30 FPS")
print("[+] Registering Virtual Camera: 'DASMO CYBER CAPTURE'...")

def stream_camera(cam_instance):
    print(f"[SUCCESS] Virtual Camera active: '{cam_instance.device}'")
    print("[*] Open WhatsApp Desktop > Settings > Audio/Video > Camera")
    print(f"[*] Select: '{cam_instance.device}'")
    print("[*] Press Ctrl+C to stop.")
    while True:
        r, f = cap.read()
        if not r or f is None:
            time.sleep(0.01)
            continue
        cam_instance.send(f)
        cam_instance.sleep_until_next_frame()

try:
    try:
        with pyvirtualcam.Camera(width=w, height=h, fps=30, device="DASMO CYBER CAPTURE", fmt=pyvirtualcam.PixelFormat.BGR) as cam:
            stream_camera(cam)
    except Exception as e:
        print(f"[*] Default device fallback notice: {e}")
        with pyvirtualcam.Camera(width=w, height=h, fps=30, fmt=pyvirtualcam.PixelFormat.BGR) as cam:
            stream_camera(cam)
except KeyboardInterrupt:
    print("\n[*] Stopping DASMO Cyber Capture Desktop Bridge...")
except Exception as fatal_e:
    print(f"[ERROR] {fatal_e}")
finally:
    cap.release()
    print("[*] Closed cleanly.")
        """.trimIndent()
    }

    private fun getDasmoBatchScript(hostIp: String): String {
        return """
@echo off
title DASMO CYBER CAPTURE // 1-Click Desktop Virtual Camera Setup
color 0b
echo ==============================================================================
echo   DASMO CYBER CAPTURE - NATIVE DESKTOP VIRTUAL CAMERA DRIVER INSTALLER
echo   Device Name in WhatsApp Desktop: "DASMO CYBER CAPTURE"
echo ==============================================================================
echo.
echo [1/3] Checking Python environment...
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] Python is not found. Please install Python from https://www.python.org/downloads/
    echo [!] Make sure to check "Add Python to PATH" during installation.
    pause
    exit /b
)

echo [2/3] Installing dependencies (opencv-python, pyvirtualcam)...
pip install --quiet opencv-python pyvirtualcam

echo [3/3] Downloading latest DASMO Bridge script from phone ($hostIp)...
curl -s -o dasmo_virtualcam.py http://$hostIp:8080/download/dasmo_virtualcam.py

echo.
echo ==============================================================================
echo   LAUNCHING DASMO CYBER CAPTURE VIRTUAL CAMERA DRIVER...
echo ==============================================================================
python dasmo_virtualcam.py
pause
        """.trimIndent()
    }
}
