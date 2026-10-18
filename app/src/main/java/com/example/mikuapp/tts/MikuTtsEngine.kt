package com.example.mikuapp.tts

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import com.example.mikuapp.util.MikuLog
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

class MikuTtsEngine(
    private val context: Context,
    private val sentences: Channel<String>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var consumerJob: Job? = null
    private val speakMutex = Mutex()

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var framesWrittenSinceFlush = 0L
    private var smoothedAmplitude = 0f

    @Volatile
    private var playbackEpoch = 0

    @Volatile
    var playbackEnabled = true
        private set

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _spokenText = MutableStateFlow("")
    val spokenText: StateFlow<String> = _spokenText.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_isReady.value) return@withContext true
        val startTime = System.currentTimeMillis()
        try {
            MikuLog.d(TAG, "Inicializando motor de voz (assets/$MODEL_ASSET_DIR)")
            val assetChildren = context.assets.list(MODEL_ASSET_DIR)
            if (assetChildren == null || assetChildren.isEmpty()) {
                MikuLog.d(TAG, "ERROR: no hay archivos en assets/$MODEL_ASSET_DIR")
                _error.value = "Faltan los archivos del modelo TTS en assets/$MODEL_ASSET_DIR"
                return@withContext false
            }

            val modelDir = File(context.filesDir, MODEL_ASSET_DIR)
            val modelFile = File(modelDir, MODEL_FILE)
            if (!modelFile.exists()) {
                MikuLog.d(TAG, "Copiando modelo de assets a ${modelDir.absolutePath}")
                val copyStart = System.currentTimeMillis()
                copyAssetDirectory(context.assets, MODEL_ASSET_DIR, modelDir)
                MikuLog.d(TAG, "Copia terminada en ${System.currentTimeMillis() - copyStart} ms")
            } else {
                MikuLog.d(TAG, "Modelo ya presente en ${modelDir.absolutePath}")
            }
            if (!modelFile.exists()) {
                MikuLog.d(TAG, "ERROR: no se encontró $MODEL_FILE tras la copia")
                _error.value = "No se encontró el modelo TTS en assets/$MODEL_ASSET_DIR"
                return@withContext false
            }

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                tokens = File(modelDir, TOKENS_FILE).absolutePath,
                dataDir = File(modelDir, ESPEAK_DATA_DIR).absolutePath,
                lengthScale = 1.0f,
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            MikuLog.d(TAG, "Cargando modelo VITS (${modelFile.length() / 1024 / 1024} MB)")
            tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelConfig))
            _isReady.value = true
            _error.value = null
            val engine = tts
            val sampleRate = runCatching { engine?.sampleRate() }.getOrDefault(0)
            val numSpeakers = runCatching { engine?.numSpeakers() }.getOrDefault(0)
            MikuLog.d(
                TAG,
                "Motor listo en ${System.currentTimeMillis() - startTime} ms " +
                    "(sampleRate=$sampleRate, speakers=$numSpeakers)",
            )
            true
        } catch (t: Throwable) {
            MikuLog.d(TAG, "ERROR inicializando motor: ${t::class.java.simpleName}: ${t.message}")
            _error.value = t.message ?: "Error inicializando el motor de voz"
            false
        }
    }

    fun start() {
        if (consumerJob != null) return
        MikuLog.d(TAG, "Consumidor de frases iniciado")
        consumerJob = scope.launch {
            for (sentence in sentences) {
                try {
                    speak(sentence)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    MikuLog.d(TAG, "Error en consumidor (sigue vivo): ${t::class.java.simpleName}: ${t.message}")
                    _error.value = t.message ?: "Error procesando frase"
                }
            }
            MikuLog.d(TAG, "Consumidor de frases terminado (canal cerrado)")
        }
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        if (playbackEnabled == enabled) return
        playbackEnabled = enabled
        MikuLog.d(TAG, if (enabled) "Reproducción activada" else "Reproducción desactivada (modo solo texto)")
        if (!enabled) {
            stopSpeaking()
        }
    }

    fun testVoice() {
        MikuLog.d(TAG, "Prueba de voz solicitada (ready=${_isReady.value})")
        scope.launch {
            try {
                if (!_isReady.value) {
                    _error.value = "La voz no está lista. Espera a que cargue el modelo."
                    return@launch
                }
                speakInternal(TEST_PHRASE)
            } catch (t: Throwable) {
                MikuLog.d(TAG, "ERROR en prueba de voz: ${t::class.java.simpleName}: ${t.message}")
                _error.value = t.message ?: "Error en la prueba de voz"
            }
        }
    }

    fun stopSpeaking() {
        playbackEpoch++
        while (sentences.tryReceive().isSuccess) {
        }
        audioTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                track.flush()
            }
        }
        framesWrittenSinceFlush = 0
        _isSpeaking.value = false
        _audioAmplitude.value = 0f
        smoothedAmplitude = 0f
    }

    fun release() {
        stopSpeaking()
        consumerJob?.cancel()
        consumerJob = null
        scope.cancel()
        releaseTrack()
        runCatching { tts?.release() }
        tts = null
        _isReady.value = false
        MikuLog.d(TAG, "Motor de voz liberado")
    }

    private suspend fun speak(sentence: String) {
        if (!_isReady.value) {
            MikuLog.d(TAG, "Frase ignorada (motor no listo): \"${sentence.take(60)}\"")
            return
        }
        if (!playbackEnabled) {
            MikuLog.d(TAG, "Frase ignorada (modo solo texto): \"${sentence.take(60)}\"")
            return
        }
        speakInternal(sentence)
    }

    private suspend fun speakInternal(sentence: String) = speakMutex.withLock {
        val engine = tts
        if (engine == null) {
            MikuLog.d(TAG, "speakInternal abortado: motor nulo")
            return@withLock
        }
        val text = sentence.trim()
        if (text.isEmpty()) return@withLock

        val epoch = playbackEpoch
        MikuLog.d(TAG, "Generando voz para: \"${text.take(80)}\"")

        val genStart = System.currentTimeMillis()
        val audio = try {
            engine.generate(text = text, sid = SPEAKER_ID, speed = GENERATION_SPEED)
        } catch (t: Throwable) {
            MikuLog.d(TAG, "ERROR en generate: ${t::class.java.simpleName}: ${t.message}")
            _error.value = t.message ?: "Error generando voz"
            return@withLock
        }
        val genMs = System.currentTimeMillis() - genStart

        val samples = audio.samples
        MikuLog.d(
            TAG,
            "Audio generado en $genMs ms: ${samples.size} muestras, " +
                "sampleRate=${audio.sampleRate}, duración=${samples.size * 1000 / maxOf(1, audio.sampleRate)} ms",
        )
        if (samples.isEmpty()) {
            MikuLog.d(TAG, "ERROR: generate devolvió 0 muestras")
            return@withLock
        }

        val track = obtainTrack(audio.sampleRate) ?: return@withLock
        applyVocaloidEffect(track)

        _spokenText.value = text
        _isSpeaking.value = true

        try {
            var offset = 0
            var chunks = 0
            while (offset < samples.size && epoch == playbackEpoch) {
                val count = minOf(WRITE_CHUNK_FRAMES, samples.size - offset)
                updateAmplitude(samples, offset, count)
                val written = try {
                    track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                } catch (t: Throwable) {
                    MikuLog.d(TAG, "ERROR en track.write: ${t::class.java.simpleName}: ${t.message}")
                    _error.value = t.message ?: "Error escribiendo audio"
                    break
                }
                if (written <= 0) {
                    MikuLog.d(TAG, "track.write devolvió $written, se detiene la escritura")
                    break
                }
                offset += written
                chunks++
                framesWrittenSinceFlush += written
            }
            if (epoch == playbackEpoch && offset >= samples.size) {
                MikuLog.d(TAG, "Enviados ${offset} frames en $chunks bloques, esperando reproducción")
                waitForPlayback(track, epoch)
            } else if (epoch != playbackEpoch) {
                MikuLog.d(TAG, "Escritura interrumpida por stopSpeaking (epoch $epoch != $playbackEpoch)")
            }
        } finally {
            _isSpeaking.value = false
            _audioAmplitude.value = 0f
            smoothedAmplitude = 0f
        }
    }

    private fun obtainTrack(sampleRate: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null && trackSampleRate == sampleRate && existing.state == AudioTrack.STATE_INITIALIZED) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            return existing
        }

        releaseTrack()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSize = if (minBuffer > 0) minBuffer * 2 else sampleRate * 4
        MikuLog.d(TAG, "Creando AudioTrack: sampleRate=$sampleRate, minBuffer=$minBuffer, buffer=$bufferSize")

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            MikuLog.d(TAG, "ERROR creando AudioTrack: ${t::class.java.simpleName}: ${t.message}")
            _error.value = t.message ?: "No se pudo crear el AudioTrack"
            return null
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            MikuLog.d(TAG, "ERROR: AudioTrack no se inicializó (state=${track.state})")
            _error.value = "AudioTrack no se inicializó correctamente"
            return null
        }

        audioTrack = track
        trackSampleRate = sampleRate
        framesWrittenSinceFlush = 0
        runCatching { track.play() }
        MikuLog.d(TAG, "AudioTrack en reproducción")
        return track
    }

    private fun applyVocaloidEffect(track: AudioTrack) {
        val result = runCatching {
            track.playbackParams = PlaybackParams()
                .setPitch(MIKU_PITCH)
                .setSpeed(MIKU_SPEED)
        }
        if (result.isSuccess) {
            val applied = runCatching { track.playbackParams }
            MikuLog.d(
                TAG,
                "Efecto Vocaloid aplicado (pitch=$MIKU_PITCH, speed=$MIKU_SPEED, " +
                    "actual: ${applied.getOrNull()?.pitch}/${applied.getOrNull()?.speed})",
            )
        } else {
            MikuLog.d(TAG, "Aviso: no se pudo aplicar PlaybackParams: ${result.exceptionOrNull()?.message}")
        }
    }

    private suspend fun waitForPlayback(track: AudioTrack, epoch: Int) {
        val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        while (epoch == playbackEpoch && track.playbackHeadPosition.toLong() < framesWrittenSinceFlush) {
            if (System.currentTimeMillis() >= deadline) {
                MikuLog.d(
                    TAG,
                    "Timeout de reproducción: head=${track.playbackHeadPosition}, " +
                        "escrito=$framesWrittenSinceFlush (epoch=$epoch/$playbackEpoch)",
                )
                return
            }
            delay(20)
        }
        MikuLog.d(TAG, "Reproducción completada (head=${track.playbackHeadPosition}, escrito=$framesWrittenSinceFlush)")
    }

    private fun updateAmplitude(samples: FloatArray, offset: Int, count: Int) {
        if (count <= 0) return
        var sum = 0.0
        for (i in offset until offset + count) {
            sum += samples[i] * samples[i]
        }
        val rms = sqrt(sum / count).toFloat()
        val normalized = (rms * AMPLITUDE_GAIN).coerceIn(0f, 1f)
        smoothedAmplitude = smoothedAmplitude * 0.6f + normalized * 0.4f
        _audioAmplitude.value = smoothedAmplitude
    }

    private fun releaseTrack() {
        audioTrack?.let { track ->
            runCatching {
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.pause()
                track.flush()
                track.release()
            }
        }
        audioTrack = null
        trackSampleRate = 0
        framesWrittenSinceFlush = 0
    }

    private fun copyAssetDirectory(assets: AssetManager, assetPath: String, destination: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            destination.mkdirs()
            for (child in children) {
                copyAssetDirectory(assets, "$assetPath/$child", File(destination, child))
            }
        }
    }

    companion object {
        const val MODEL_ASSET_DIR = "vits-piper-es_MX-claude-high"
        const val MIKU_PITCH = 1.25f
        const val MIKU_SPEED = 1.05f
        const val TEST_PHRASE =
            "¡Hola! Soy Hatsune Miku. Si escuchas esta frase, mi voz funciona correctamente. ¡Yupi!"

        private const val TAG = "TTS"
        private const val MODEL_FILE = "es_MX-claude-high.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
        private const val SPEAKER_ID = 0
        private const val GENERATION_SPEED = 1.0f
        private const val WRITE_CHUNK_FRAMES = 4096
        private const val AMPLITUDE_GAIN = 4f
        private const val PLAYBACK_TIMEOUT_MS = 30_000L
    }
}
