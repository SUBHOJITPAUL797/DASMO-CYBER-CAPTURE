package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.model.AudioRouting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var recordJob: Job? = null
    private val audioStreams = CopyOnWriteArrayList<OutputStream>()

    private val _micDbLevel = MutableStateFlow(-60f)
    val micDbLevel: StateFlow<Float> = _micDbLevel

    private var isMuted = false
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

                        // Broadcast to any attached HTTP audio streams
                        val iterator = audioStreams.iterator()
                        while (iterator.hasNext()) {
                            val stream = iterator.next()
                            try {
                                stream.write(byteBuffer, 0, bytesToWrite)
                                stream.flush()
                            } catch (_: Exception) {
                                audioStreams.remove(stream)
                            }
                        }
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
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
            val bufferSize = (minBufSize * 2).coerceAtLeast(8192)

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (routing == AudioRouting.SPEAKERPHONE) {
                audioManager?.isSpeakerphoneOn = true
            }

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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

    fun playSpeakerPcmChunk(data: ByteArray, offset: Int, length: Int) {
        try {
            audioTrack?.write(data, offset, length)
        } catch (e: Exception) {
            Log.w("CyberAudioEngine", "Error writing to AudioTrack", e)
        }
    }

    fun stopSpeakerPlayback() {
        try {
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
