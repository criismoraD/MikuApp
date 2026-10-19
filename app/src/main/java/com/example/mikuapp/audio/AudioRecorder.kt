package com.example.mikuapp.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.example.mikuapp.util.MikuLog
import kotlin.math.sqrt

class AudioRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
    private val chunkMillis: Int = CHUNK_MILLIS,
    private val onChunk: (pcm16: ByteArray, rms: Float) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean
        get() = running

    fun start() {
        if (running) return

        val chunkSizeBytes = sampleRate / 1000 * chunkMillis * BYTES_PER_SAMPLE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBuffer > 0) maxOf(minBuffer, chunkSizeBytes * 4) else chunkSizeBytes * 4

        val record = createRecord(bufferSize)
        if (record == null) {
            MikuLog.d(TAG, "ERROR: no se pudo inicializar AudioRecord (minBuffer=$minBuffer)")
            onError(IllegalStateException("No se pudo inicializar AudioRecord"))
            return
        }

        audioRecord = record
        enableEchoCancellation(record.audioSessionId)

        try {
            record.startRecording()
        } catch (t: Throwable) {
            releaseRecord()
            onError(t)
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseRecord()
            onError(IllegalStateException("AudioRecord no entró en estado de grabación"))
            return
        }

        running = true
        MikuLog.d(TAG, "Grabación iniciada (${sampleRate} Hz, chunk=${chunkSizeBytes} bytes, sesión=${record.audioSessionId})")
        worker = Thread({
            val buffer = ByteArray(chunkSizeBytes)
            var chunkCount = 0L
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                        val rms = computeRms(chunk)
                        chunkCount++
                        if (chunkCount == 1L || chunkCount % 25L == 0L) {
                            MikuLog.d(TAG, "Chunks capturados: $chunkCount (RMS=%.3f)".format(rms))
                        }
                        onChunk(chunk, rms)
                    }

                    read < 0 -> {
                        MikuLog.d(TAG, "ERROR de lectura en AudioRecord: $read")
                        onError(IllegalStateException("Error de lectura en AudioRecord: $read"))
                        break
                    }
                }
            }
        }, "MikuAudioRecorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        MikuLog.d(TAG, "Deteniendo grabación")
        runCatching { audioRecord?.stop() }
        worker?.let { thread ->
            runCatching { thread.join(500) }
        }
        worker = null
        releaseRecord()
    }

    private fun createRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val record = try {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            } catch (t: Throwable) {
                null
            }
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                return record
            }
            runCatching { record?.release() }
        }
        return null
    }

    private fun enableEchoCancellation(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { setEnabled(true) }
            }.getOrNull()
            MikuLog.d(TAG, "Cancelador de eco ${if (echoCanceler != null) "activo" else "no creado"}")
        } else {
            MikuLog.d(TAG, "Cancelador de eco no disponible en este dispositivo")
        }
        runCatching {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor.create(sessionId)?.setEnabled(true)
            }
        }
    }

    private fun releaseRecord() {
        runCatching { echoCanceler?.setEnabled(false) }
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private fun computeRms(pcm16: ByteArray): Float {
        if (pcm16.size < BYTES_PER_SAMPLE) return 0f
        var sum = 0.0
        var index = 0
        while (index + 1 < pcm16.size) {
            val sample = ((pcm16[index + 1].toInt() shl 8) or (pcm16[index].toInt() and 0xFF))
                .toShort()
                .toFloat() / Short.MAX_VALUE
            sum += sample * sample
            index += BYTES_PER_SAMPLE
        }
        val count = pcm16.size / BYTES_PER_SAMPLE
        return sqrt(sum / count).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_MILLIS = 100
        private const val TAG = "Mic"
        private const val BYTES_PER_SAMPLE = 2
    }
}
