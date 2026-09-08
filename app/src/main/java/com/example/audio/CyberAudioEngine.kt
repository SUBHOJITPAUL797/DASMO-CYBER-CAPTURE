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
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var recordJob: Job? = null
    private var broadcastJob: Job? = null
    private val audioBroadcastChannel = Channel<ByteArray>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val audioStreams = CopyOnWriteArrayList<OutputStream>()

    private val _micDbLevel = MutableStateFlow(-60f)
    val micDbLevel: StateFlow<Float> = _micDbLevel

    private val _connectedAudioDeviceName = MutableStateFlow("Phone Speaker")
    val connectedAudioDeviceName: StateFlow<String> = _connectedAudioDeviceName

    private val _isHeadphoneConnected = MutableStateFlow(false)
    val isHeadphoneConnected: StateFlow<Boolean> = _isHeadphoneConnected

    @Volatile
    private var currentRouting: AudioRouting = AudioRouting.AUTO

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
            val sources = intArrayOf(
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION
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
                val pcmBuffer = ShortArray(bufferSize / 2)
                val byteBuffer = ByteArray(bufferSize)

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

            // Minimal buffer size for lowest latency (2048 bytes = ~21.3ms at 48kHz 16-bit mono)
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
            val bufferSize = minBufSize.coerceAtLeast(2048)

            applyAudioRouting(routing)

            // Ensure Android volume is audible across both STREAM_MUSIC and STREAM_VOICE_CALL
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                try {
                    val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val curMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (curMusic < maxMusic / 2) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusic * 0.9f).toInt().coerceAtLeast(1), 0)
                    }
                } catch (_: Exception) {}
                try {
                    val maxCall = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    val curCall = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    if (curCall < maxCall / 2) {
                        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxCall * 0.9f).toInt().coerceAtLeast(1), 0)
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
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    attrBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                }

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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && track.state == AudioTrack.STATE_INITIALIZED) {
                    try {
                        val minFrames = minBufSize / 2
                        track.setBufferSizeInFrames(minFrames)
                    } catch (_: Exception) {}
                }
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
                Log.i("CyberAudioEngine", "AudioTrack initialized (Low-Latency mode) & PLAYING")
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val commDevices = audioManager.availableCommunicationDevices
                        val targetHeadphone = commDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        }
                        if (targetHeadphone != null) {
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            audioManager.setCommunicationDevice(targetHeadphone)
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = false
                            val devName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) targetHeadphone.productName else "Headphone"
                            Log.i("CyberAudioEngine", "[AUTO] Routed to connected headphone: $devName")
                        } else {
                            val speaker = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            if (speaker != null) {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                                audioManager.setCommunicationDevice(speaker)
                            } else {
                                audioManager.clearCommunicationDevice()
                            }
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = true
                            Log.i("CyberAudioEngine", "[AUTO] No headphones found, routed to TYPE_BUILTIN_SPEAKER")
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        val hasHeadphone = outputs.any {
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        }
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = !hasHeadphone
                    } else {
                        @Suppress("DEPRECATION")
                        val hasHeadphone = audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = !hasHeadphone
                    }
                }

                AudioRouting.SPEAKERPHONE -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val commDevices = audioManager.availableCommunicationDevices
                        val speaker = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null) {
                            audioManager.setCommunicationDevice(speaker)
                        } else {
                            audioManager.clearCommunicationDevice()
                        }
                    }
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                    Log.i("CyberAudioEngine", "Force routed to SPEAKERPHONE")
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
                    Log.i("CyberAudioEngine", "Force routed to EARPIECE")
                }
            }
        } catch (e: Exception) {
            Log.w("CyberAudioEngine", "Failed to apply audio routing", e)
        }
    }

    fun playSpeakerPcmChunk(data: ByteArray, offset: Int, length: Int) {
        try {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initSpeakerPlayback(currentRouting, 1.0f)
            }
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack?.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING) ?: 0
            } else {
                audioTrack?.write(data, offset, length) ?: 0
            }
            if (written < 0) {
                Log.w("CyberAudioEngine", "AudioTrack.write error code: $written")
            }
        } catch (e: Exception) {
            Log.w("CyberAudioEngine", "Error writing to AudioTrack", e)
        }
    }

    fun stopSpeakerPlayback() {
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
