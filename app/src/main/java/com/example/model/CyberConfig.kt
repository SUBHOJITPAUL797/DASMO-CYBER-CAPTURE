package com.example.model

enum class CyberFilter(val displayName: String, val description: String) {
    NONE("Raw Feed", "Pure uncompressed sensor feed"),
    CYBER_HUD("Cyber HUD", "Sci-fi tactical targeting crosshair & telemetry overlay"),
    MATRIX_RAIN("Matrix Stream", "Cascading cyber digital rain data stream"),
    CHROMA_GREEN("Chroma Green", "Virtual green-screen backdrop for OBS compositing"),
    NIGHT_VISION("Night Thermal", "High-contrast phosphor green cyber night vision"),
    MONOKAI_CYBER("Cyber Neon", "Electric cyan & neon magenta high-pass grading"),
    STUDIO_WARM("Studio Glow", "Soft studio key-light beauty warmth"),
    BLUR_SIM("Privacy Blur", "Gaussian privacy blur background simulation"),
    GLITCH_EFFECT("Cyber Glitch", "Vaporwave digital sync scanline distortion")
}

enum class CameraFacing {
    BACK,
    FRONT
}

enum class StreamResolution(val label: String, val width: Int, val height: Int, val desc: String) {
    SD_480P("480p SD", 640, 480, "Low bandwidth / Power saver"),
    HD_720P("720p HD", 1280, 720, "Optimal 30fps balanced (Recommended)"),
    FHD_1080P("1080p FHD", 1920, 1080, "Crisp high-definition pro stream"),
    UHD_4K("4K UHD", 3840, 2160, "Maximum optical sensor detail")
}

enum class AudioRouting {
    SPEAKERPHONE,
    EARPIECE
}

data class CyberStreamStats(
    val fps: Float = 0f,
    val bitrateKbps: Int = 0,
    val connectedClients: Int = 0,
    val bytesSent: Long = 0,
    val latencyMs: Int = 18,
    val resolutionLabel: String = "720p HD",
    val uptimeSeconds: Long = 0,
    val batteryPercent: Int = 100,
    val batteryTemp: Float = 32.0f,
    val micLevelDb: Float = -60f,
    val isStreaming: Boolean = false,
    val wifiSsid: String = "Wi-Fi LAN",
    val serverIp: String = "127.0.0.1",
    val serverPort: Int = 8080
)

data class CyberConfig(
    val resolution: StreamResolution = StreamResolution.HD_720P,
    val targetFps: Int = 30,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val isTorchOn: Boolean = false,
    val zoomFactor: Float = 1.0f,
    val isMirrored: Boolean = false,
    val isVideoPaused: Boolean = false,
    val activeFilter: CyberFilter = CyberFilter.NONE,
    val showGrid: Boolean = true,
    val showTargetReticle: Boolean = true,
    
    // Audio capture settings (Phone Mic -> PC Input)
    val isMicMuted: Boolean = false,
    val micGain: Float = 1.0f,
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    
    // Audio receiver (PC Call Audio -> Phone Speaker Output)
    val isSpeakerEnabled: Boolean = true,
    val speakerVolume: Float = 0.85f,
    val audioRouting: AudioRouting = AudioRouting.SPEAKERPHONE,
    
    // Network & Server
    val serverPort: Int = 8080,
    val isKeepScreenOn: Boolean = true,
    val isWakeLockEnabled: Boolean = true,
    val autoStartCapture: Boolean = true,
    val mDnsDiscoveryEnabled: Boolean = true,
    val jpegQuality: Int = 70
)
