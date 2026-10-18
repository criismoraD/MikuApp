package com.example.mikuapp.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.mikuapp.util.MikuLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class GeminiAudioPlayer {

    private var track: AudioTrack? = null
    private var trackSampleRate = 0
    private var smoothedAmplitude = 0f
    private var receivedChunks = 0L
    private var receivedBytes = 0L

    @Volatile
    var playbackEnabled = true
        private set

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Synchronized
    fun play(pcm16: ByteArray, sampleRate: Int) {
        if (pcm16.isEmpty() || !playbackEnabled) return
        val target = obtainTrack(sampleRate) ?: return

        val written = try {
            target.write(pcm16, 0, pcm16.size, AudioTrack.WRITE_BLOCKING)
        } catch (t: Throwable) {
            MikuLog.d(TAG, "ERROR escribiendo audio de Gemini: ${t.message}")
            return
        }
        if (written <= 0) {
            MikuLog.d(TAG, "Audio de Gemini: write devolvió $written")
            return
        }

        receivedChunks++
        receivedBytes += written
        if (receivedChunks == 1L || receivedChunks % 25L == 0L) {
            MikuLog.d(TAG, "Audio de Gemini: $receivedChunks chunks, ${receivedBytes / 1024} KB recibidos")
        }

        updateAmplitude(pcm16, written)
        _isPlaying.value = true
    }

    @Synchronized
    fun turnComplete() {
        if (receivedChunks > 0) {
            MikuLog.d(TAG, "Turno de audio de Gemini terminado (${receivedBytes / 1024} KB en total)")
        }
        receivedChunks = 0
        receivedBytes = 0
        _isPlaying.value = false
        _amplitude.value = 0f
        smoothedAmplitude = 0f
    }

    @Synchronized
    fun stop() {
        track?.let { current ->
            runCatching {
                if (current.playState == AudioTrack.PLAYSTATE_PLAYING) current.pause()
                current.flush()
            }
        }
        receivedChunks = 0
        receivedBytes = 0
        _isPlaying.value = false
        _amplitude.value = 0f
        smoothedAmplitude = 0f
    }

    @Synchronized
    fun setPlaybackEnabled(value: Boolean) {
        if (playbackEnabled == value) return
        playbackEnabled = value
        MikuLog.d(TAG, if (value) "Audio de Gemini habilitado" else "Audio de Gemini silenciado")
        if (!value) stop()
    }

    @Synchronized
    fun release() {
        stop()
        track?.let { current ->
            runCatching {
                if (current.playState != AudioTrack.PLAYSTATE_STOPPED) current.pause()
                current.flush()
                current.release()
            }
        }
        track = null
        trackSampleRate = 0
    }

    private fun obtainTrack(sampleRate: Int): AudioTrack? {
        val existing = track
        if (existing != null && trackSampleRate == sampleRate && existing.state == AudioTrack.STATE_INITIALIZED) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            return existing
        }

        release()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBuffer > 0) minBuffer * 4 else sampleRate * 2 * 2
        MikuLog.d(TAG, "Creando AudioTrack para Gemini: sampleRate=$sampleRate, buffer=$bufferSize")

        val created = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            MikuLog.d(TAG, "ERROR creando AudioTrack de Gemini: ${t.message}")
            return null
        }

        if (created.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { created.release() }
            MikuLog.d(TAG, "ERROR: AudioTrack de Gemini no inicializado")
            return null
        }

        track = created
        trackSampleRate = sampleRate
        runCatching { created.play() }
        return created
    }

    private fun updateAmplitude(pcm16: ByteArray, byteCount: Int) {
        if (byteCount < 2) return
        var sum = 0.0
        var index = 0
        while (index + 1 < byteCount) {
            val sample = ((pcm16[index + 1].toInt() shl 8) or (pcm16[index].toInt() and 0xFF))
                .toShort()
                .toFloat() / Short.MAX_VALUE
            sum += sample * sample
            index += 2
        }
        val count = byteCount / 2
        val rms = sqrt(sum / count).toFloat()
        val normalized = (rms * AMPLITUDE_GAIN).coerceIn(0f, 1f)
        smoothedAmplitude = smoothedAmplitude * 0.6f + normalized * 0.4f
        _amplitude.value = smoothedAmplitude
    }

    companion object {
        private const val TAG = "GeminiAudio"
        private const val AMPLITUDE_GAIN = 4f
    }
}