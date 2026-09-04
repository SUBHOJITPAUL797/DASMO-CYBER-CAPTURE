package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
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
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfigIn,
                audioFormat,
                bufferSize
            )

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

    // --- Speaker Output Engine (PC System Audio -> Phone Speaker) ---
    fun initSpeakerPlayback(routing: AudioRouting = AudioRouting.SPEAKERPHONE, volume: Float = 0.9f) {
        try {
            if (audioTrack != null) {
                setSpeakerVolume(volume)
                setAudioRouting(routing)
                return
            }

            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
            val bufferSize = (minBufSize * 2).coerceAtLeast(8192)

            applyAudioRouting(routing)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfigOut)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.setVolume(volume.coerceIn(0f, 1f))
            audioTrack?.play()
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
        applyAudioRouting(routing)
    }

    private fun applyAudioRouting(routing: AudioRouting) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val targetType = if (routing == AudioRouting.SPEAKERPHONE) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val targetDevice = devices.firstOrNull { it.type == targetType }
                if (targetDevice != null) {
                    audioManager.setCommunicationDevice(targetDevice)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                if (routing == AudioRouting.SPEAKERPHONE) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                } else {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
            }
        } catch (e: Exception) {
            Log.w("CyberAudioEngine", "Failed to apply audio routing", e)
        }
    }

    fun playSpeakerPcmChunk(data: ByteArray, offset: Int, length: Int) {
        try {
            audioTrack?.write(data, offset, length)
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
        stopMicCapture()
        stopSpeakerPlayback()
        audioStreams.clear()
    }
}
