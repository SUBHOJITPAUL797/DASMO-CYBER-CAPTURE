package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.example.model.AudioRouting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.log10
import kotlin.math.sqrt

class CyberAudioEngine(private val context: Context) {

    private val sampleRate = 48000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_STEREO // High-Res 48kHz Stereo Output (Left + Right)
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var recordJob: Job? = null
    private var broadcastJob: Job? = null
    private val audioBroadcastChannel = Channel<ByteArray>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val audioStreams = CopyOnWriteArrayList<OutputStream>()

    private val _micDbLevel = MutableStateFlow(-60f)
    val micDbLevel: StateFlow<Float> = _micDbLevel

    private val _connectedAudioDeviceName = MutableStateFlow("Phone Speaker")
    val connectedAudioDeviceName: StateFlow<String> = _connectedAudioDeviceName

    private val _isHeadphoneConnected = MutableStateFlow(false)
    val isHeadphoneConnected: StateFlow<Boolean> = _isHeadphoneConnected

    @Volatile
    private var currentRouting: AudioRouting = AudioRouting.AUTO

    // Decoupled Low-Latency Adaptive Jitter Buffer (30 chunks = 600ms capacity ceiling)
    private val speakerQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(30)
    @Volatile private var isSpeakerWorkerRunning = false
    private var speakerWorkerThread: Thread? = null

    // Adaptive buffering state
    @Volatile private var isBuffering = true
    private val prebufferChunks = 3   // 3 chunks @ 20ms = 60ms initial cushion for silky smooth jitter resilience
    private val maxLagChunks = 12     // 12 chunks @ 20ms = 240ms max drift ceiling before smooth trim

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                updateConnectedDevices()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                updateConnectedDevices()
            }
        }
    } else null

    init {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null && audioManager != null) {
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
            }
        } catch (_: Exception) {}
        updateConnectedDevices()
    }

    fun updateConnectedDevices() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                var hasHeadphone = false
                var label = "Phone Speaker"

                val wired = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                }
                val usb = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                }
                val bt = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                }

                if (wired != null) {
                    hasHeadphone = true
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) wired.productName?.toString() else null
                    label = if (!name.isNullOrBlank()) "🎧 $name" else "🎧 Wired Headphones"
                } else if (usb != null) {
                    hasHeadphone = true
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) usb.productName?.toString() else null
                    label = if (!name.isNullOrBlank()) "🎧 $name" else "🎧 USB-C Headset"
                } else if (bt != null) {
                    hasHeadphone = true
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) bt.productName?.toString() else null
                    label = if (!name.isNullOrBlank()) "🎧 $name" else "🎧 Bluetooth Headset"
                } else {
                    label = "🔊 Phone Speaker"
                }

                _isHeadphoneConnected.value = hasHeadphone
                _connectedAudioDeviceName.value = label
            } else {
                @Suppress("DEPRECATION")
                val isWiredOn = audioManager.isWiredHeadsetOn
                @Suppress("DEPRECATION")
                val isBtOn = audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
                _isHeadphoneConnected.value = isWiredOn || isBtOn
                _connectedAudioDeviceName.value = if (isWiredOn) "🎧 Wired Headphones" else if (isBtOn) "🎧 Bluetooth Headset" else "🔊 Phone Speaker"
            }

            if (currentRouting == AudioRouting.AUTO) {
                applyAudioRouting(AudioRouting.AUTO)
            }
        } catch (e: Exception) {
            Log.w("CyberAudioEngine", "Failed to update connected devices", e)
        }
    }

    @Volatile
    private var isMuted = false
    @Volatile
    private var micGain = 1.0f

    @SuppressLint("MissingPermission")
    fun startMicCapture(scope: CoroutineScope, onPcmChunk: ((ByteArray, Int) -> Unit)? = null) {
        if (recordJob?.isActive == true) return

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

        try {
            var record: AudioRecord? = null
            // Prioritize VOICE_RECOGNITION for crystal-clear speech typing (Win+H) and active mic input
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.CAMCORDER
            )
            for (src in sources) {
                try {
                    val candidate = AudioRecord(
                        src,
                        sampleRate,
                        channelConfigIn,
                        audioFormat,
                        bufferSize
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        break
                    } else {
                        candidate.release()
                    }
                } catch (_: Exception) {}
            }

            audioRecord = record

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("CyberAudioEngine", "AudioRecord initialization failed (hardware in use or missing permission)")
                stopMicCapture()
                return
            }

            val audioSessionId = audioRecord?.audioSessionId ?: 0
            if (audioSessionId != 0) {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId).apply {
                        enabled = true
                    }
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(audioSessionId).apply {
                        enabled = true
                    }
                }
            }

            audioRecord?.startRecording()

            // Asynchronous decoupled network broadcast worker (prevents network jitter from blocking AudioRecord loop)
            broadcastJob = scope.launch(Dispatchers.IO) {
                for (chunk in audioBroadcastChannel) {
                    if (!isActive) break
                    val iterator = audioStreams.iterator()
                    while (iterator.hasNext()) {
                        val stream = iterator.next()
                        try {
                            stream.write(chunk)
                            stream.flush()
                        } catch (_: Exception) {
                            audioStreams.remove(stream)
                        }
                    }
                }
            }

            recordJob = scope.launch(Dispatchers.IO) {
                // 20ms chunk size (960 samples at 48kHz) for ultra-low latency speech delivery
                val chunkSamples = 960
                val pcmBuffer = ShortArray(chunkSamples)
                val byteBuffer = ByteArray(chunkSamples * 2)

                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readShorts = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                    if (readShorts > 0) {
                        var sumSquares = 0.0
                        for (i in 0 until readShorts) {
                            var sample = (pcmBuffer[i] * (if (isMuted) 0f else micGain)).toInt()
                            sample = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            val sampleShort = sample.toShort()
                            byteBuffer[i * 2] = (sampleShort.toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((sampleShort.toInt() shr 8) and 0xFF).toByte()

                            sumSquares += (sampleShort * sampleShort).toDouble()
                        }

                        val rms = sqrt(sumSquares / readShorts)
                        val db = if (rms > 1.0) (20 * log10(rms / 32767.0)).toFloat().coerceIn(-60f, 0f) else -60f
                        _micDbLevel.value = db

                        val bytesToWrite = readShorts * 2

                        // Subtle digital floor squelch:
                        // Only zero out true absolute digital silence (RMS < 8.0 ~= -72dBFS)
                        // All whispers, spoken words, and speech recognition inputs pass through untouched!
                        if (!isMuted && rms < 8.0) {
                            byteBuffer.fill(0, 0, bytesToWrite)
                        }

                        onPcmChunk?.invoke(byteBuffer, bytesToWrite)

                        // Forward to decoupled broadcast channel
                        audioBroadcastChannel.trySend(byteBuffer.copyOf(bytesToWrite))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CyberAudioEngine", "Failed to start AudioRecord", e)
        }
    }

    fun stopMicCapture() {
        recordJob?.cancel()
        recordJob = null
        broadcastJob?.cancel()
        broadcastJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        noiseSuppressor?.release()
        noiseSuppressor = null
        echoCanceler?.release()
        echoCanceler = null
        _micDbLevel.value = -60f
    }

    fun addAudioStream(stream: OutputStream) {
        audioStreams.add(stream)
    }

    fun removeAudioStream(stream: OutputStream) {
        audioStreams.remove(stream)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun setGain(gain: Float) {
        micGain = gain.coerceIn(0.1f, 3.0f)
    }

    // --- Speaker Output Engine (PC System Audio -> Phone Speaker / Headphone) ---
    fun initSpeakerPlayback(routing: AudioRouting = AudioRouting.AUTO, volume: Float = 1.0f) {
        try {
            currentRouting = routing
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                setSpeakerVolume(volume)
                setAudioRouting(routing)
                return
            }

            audioTrack?.release()
            audioTrack = null

            // High-resolution low-latency stereo hardware buffer size (~40-60ms headroom)
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
            val bufferSize = (minBufSize * 3).coerceAtLeast(8192)

            applyAudioRouting(routing)

            // Ensure Android volume is audible across STREAM_MUSIC
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                try {
                    val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val curMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (curMusic < maxMusic / 2) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusic * 0.9f).toInt().coerceAtLeast(1), 0)
                    }
                } catch (_: Exception) {}
            }

            val usage = if (routing == AudioRouting.EARPIECE) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }
            val contentType = if (routing == AudioRouting.EARPIECE) {
                AudioAttributes.CONTENT_TYPE_SPEECH
            } else {
                AudioAttributes.CONTENT_TYPE_MUSIC
            }

            var track: AudioTrack? = null
            try {
                val attrBuilder = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)

                val trackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(attrBuilder.build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfigOut)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }

                track = trackBuilder.build()
            } catch (e: Exception) {
                Log.w("CyberAudioEngine", "AudioTrack.Builder failed, using legacy constructor", e)
            }

            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                track?.release()
                Log.i("CyberAudioEngine", "Using robust legacy AudioTrack fallback for STREAM_MUSIC")
                @Suppress("DEPRECATION")
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfigOut,
                    audioFormat,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            audioTrack = track

            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.setVolume(volume.coerceIn(0f, 1f))
                audioTrack?.play()
                Log.i("CyberAudioEngine", "AudioTrack initialized & PLAYING")
            } else {
                Log.e("CyberAudioEngine", "AudioTrack failed to initialize in both builder and legacy fallback")
            }
        } catch (e: Exception) {
            Log.e("CyberAudioEngine", "Failed to init AudioTrack", e)
        }
    }

    fun setSpeakerVolume(volume: Float) {
        try {
            audioTrack?.setVolume(volume.coerceIn(0f, 1f))
        } catch (e: Exception) {
            Log.w("CyberAudioEngine", "Failed to set speaker volume", e)
        }
    }

    fun setAudioRouting(routing: AudioRouting) {
        currentRouting = routing
        applyAudioRouting(routing)
    }

    private fun applyAudioRouting(routing: AudioRouting) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            when (routing) {
                AudioRouting.AUTO -> {
                    // MODE_NORMAL guarantees pristine Hi-Fi audio through Bluetooth headphones or phone speaker
                    audioManager.mode = AudioManager.MODE_NORMAL
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice()
                    }
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    Log.i("CyberAudioEngine", "[AUTO] Routed to MODE_NORMAL (Hi-Fi media playback)")
                }

                AudioRouting.SPEAKERPHONE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val commDevices = audioManager.availableCommunicationDevices
                        val speaker = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null && _isHeadphoneConnected.value) {
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            audioManager.setCommunicationDevice(speaker)
                            Log.i("CyberAudioEngine", "Force routed to BUILTIN_SPEAKER over headphones")
                        } else {
                            audioManager.mode = AudioManager.MODE_NORMAL
                            audioManager.clearCommunicationDevice()
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = true
                            Log.i("CyberAudioEngine", "Routed to SPEAKERPHONE (MODE_NORMAL)")
                        }
                    } else {
                        audioManager.mode = AudioManager.MODE_NORMAL
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        Log.i("CyberAudioEngine", "Routed to SPEAKERPHONE (MODE_NORMAL)")
                    }
                }

                AudioRouting.EARPIECE -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val commDevices = audioManager.availableCommunicationDevices
                        val earpiece = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        if (earpiece != null) {
                            audioManager.setCommunicationDevice(earpiece)
                        } else {
                            audioManager.clearCommunicationDevice()
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                    }
                    Log.i("CyberAudioEngine", "Force routed to EARPIECE (MODE_IN_COMMUNICATION)")
                }
            }
        } catch (e: Exception) {
            Log.w("CyberAudioEngine", "Failed to apply audio routing", e)
        }
    }

    private fun applySoftFadeIn(pcmStereo: ByteArray) {
        val numFrames = kotlin.math.min(32, pcmStereo.size / 4)
        for (i in 0 until numFrames) {
            val factor = (i + 1).toFloat() / numFrames.toFloat()
            val byteIdx = i * 4

            // Left channel 16-bit LE
            var left = (pcmStereo[byteIdx].toInt() and 0xFF) or (pcmStereo[byteIdx + 1].toInt() shl 8)
            if (left >= 32768) left -= 65536
            val rampedLeft = (left * factor).toInt().coerceIn(-32768, 32767)
            pcmStereo[byteIdx] = (rampedLeft and 0xFF).toByte()
            pcmStereo[byteIdx + 1] = ((rampedLeft shr 8) and 0xFF).toByte()

            // Right channel 16-bit LE
            var right = (pcmStereo[byteIdx + 2].toInt() and 0xFF) or (pcmStereo[byteIdx + 3].toInt() shl 8)
            if (right >= 32768) right -= 65536
            val rampedRight = (right * factor).toInt().coerceIn(-32768, 32767)
            pcmStereo[byteIdx + 2] = (rampedRight and 0xFF).toByte()
            pcmStereo[byteIdx + 3] = ((rampedRight shr 8) and 0xFF).toByte()
        }
    }

    private fun convertMonoToStereo(monoPcm: ByteArray, offset: Int, length: Int): ByteArray {
        val samples = length / 2
        val stereo = ByteArray(samples * 4)
        for (i in 0 until samples) {
            val b0 = monoPcm[offset + i * 2]
            val b1 = monoPcm[offset + i * 2 + 1]
            val outIdx = i * 4
            // Left
            stereo[outIdx] = b0
            stereo[outIdx + 1] = b1
            // Right
            stereo[outIdx + 2] = b0
            stereo[outIdx + 3] = b1
        }
        return stereo
    }

    private fun ensureSpeakerWorkerStarted() {
        if (isSpeakerWorkerRunning && speakerWorkerThread?.isAlive == true) return
        isSpeakerWorkerRunning = true

        speakerWorkerThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initSpeakerPlayback(currentRouting, 1.0f)
            }

            var needsFadeIn = true

            while (isSpeakerWorkerRunning) {
                try {
                    val track = audioTrack
                    if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                        Thread.sleep(15)
                        continue
                    }

                    // Adaptive pre-buffering gate: accumulate prebufferChunks (60ms) before starting/resuming
                    if (isBuffering) {
                        if (speakerQueue.size < prebufferChunks) {
                            Thread.sleep(5)
                            continue
                        } else {
                            isBuffering = false
                            needsFadeIn = true
                        }
                    }

                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        try {
                            track.play()
                        } catch (_: Exception) {}
                    }

                    // Blocking poll for next PCM chunk from network (poll up to 35ms)
                    val chunk = speakerQueue.poll(35, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        // Network starved / packet gap detected: re-enter buffering state to avoid rapid pop/click
                        isBuffering = true
                        needsFadeIn = true
                        continue
                    }

                    // Apply anti-click micro-ramp on start or recovery
                    if (needsFadeIn) {
                        applySoftFadeIn(chunk)
                        needsFadeIn = false
                    }

                    var totalWritten = 0
                    while (totalWritten < chunk.size && isSpeakerWorkerRunning) {
                        val toWrite = chunk.size - totalWritten
                        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            track.write(chunk, totalWritten, toWrite, AudioTrack.WRITE_BLOCKING)
                        } else {
                            track.write(chunk, totalWritten, toWrite)
                        }
                        if (written <= 0) {
                            break
                        }
                        totalWritten += written
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w("CyberAudioEngine", "Speaker playback worker exception", e)
                }
            }
        }, "CyberAudioPlaybackThread").apply {
            isDaemon = true
            start()
        }
    }

    fun playSpeakerPcmChunk(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        try {
            ensureSpeakerWorkerStarted()

            val chunk = if (length == 1920 || length % 4 != 0) {
                convertMonoToStereo(data, offset, length)
            } else {
                data.copyOfRange(offset, offset + length)
            }

            // Real-time jitter drift control:
            // Trim only if backlog exceeds maxLagChunks (12 chunks = ~240ms ceiling)
            while (speakerQueue.size > maxLagChunks) {
                speakerQueue.poll()
            }

            speakerQueue.offer(chunk)

            if (isBuffering && speakerQueue.size >= prebufferChunks) {
                isBuffering = false
            }
        } catch (e: Exception) {
            Log.w("CyberAudioEngine", "Error enqueuing speaker PCM chunk", e)
        }
    }

    fun stopSpeakerPlayback() {
        isSpeakerWorkerRunning = false
        isBuffering = true
        speakerWorkerThread?.interrupt()
        speakerWorkerThread = null
        speakerQueue.clear()

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager?.clearCommunicationDevice()
            }
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun release() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null && audioManager != null) {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        } catch (_: Exception) {}
        stopMicCapture()
        stopSpeakerPlayback()
        audioStreams.clear()
    }
}
