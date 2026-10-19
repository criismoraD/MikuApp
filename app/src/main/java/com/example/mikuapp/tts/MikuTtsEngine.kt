package com.example.mikuapp.tts

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
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
    private val onSentenceConsumed: () -> Unit = {},
) {

    private data class SynthesizedClip(
        val text: String,
        val samples: FloatArray,
        val sampleRate: Int,
        val epoch: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var synthesizerJob: Job? = null
    private var playbackJob: Job? = null
    private val genMutex = Mutex()
    private val synthesizedQueue = Channel<SynthesizedClip>(capacity = 4)

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var songPlayer: MediaPlayer? = null
    private var songVisualizerJob: Job? = null
    private var trackSampleRate = 0
    private var framesWrittenSinceFlush = 0L
    private var smoothedAmplitude = 0f

    @Volatile
    private var playbackEpoch = 0

    @Volatile
    private var pendingSongAfterQueue = false

    @Volatile
    private var synthesizingCount = 0

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
            val tokensFile = File(modelDir, TOKENS_FILE)
            if (!tokensFile.exists()) {
                MikuLog.d(TAG, "Copiando datos de voz de assets a ${modelDir.absolutePath}")
                val copyStart = System.currentTimeMillis()
                copyAssetDirectory(context.assets, MODEL_ASSET_DIR, modelDir)
                MikuLog.d(TAG, "Copia terminada en ${System.currentTimeMillis() - copyStart} ms")
            }
            if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
                MikuLog.d(TAG, "Descargando modelo ONNX bajo demanda a ${modelFile.absolutePath}...")
                val dlStart = System.currentTimeMillis()
                val downloaded = runCatching {
                    modelDir.mkdirs()
                    val tempFile = File(modelDir, "$MODEL_FILE.tmp")
                    java.net.URL(MODEL_DOWNLOAD_URL).openStream().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.length() > 10_000_000L) {
                        tempFile.renameTo(modelFile)
                        true
                    } else {
                        tempFile.delete()
                        false
                    }
                }.getOrDefault(false)
                if (downloaded) {
                    MikuLog.d(TAG, "Modelo ONNX descargado en ${System.currentTimeMillis() - dlStart} ms")
                }
            }
            if (!modelFile.exists()) {
                MikuLog.d(TAG, "Aviso: no se encontró $MODEL_FILE local")
                _error.value = "No se encontró el modelo TTS local (usando voz Gemini)"
                return@withContext false
            }

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                tokens = File(modelDir, TOKENS_FILE).absolutePath,
                dataDir = File(modelDir, ESPEAK_DATA_DIR).absolutePath,
                lengthScale = 0.94f,
            )
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            MikuLog.d(TAG, "Cargando modelo VITS (${modelFile.length() / 1024 / 1024} MB, hilos=$threads)")
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
        if (synthesizerJob != null) return
        MikuLog.d(TAG, "Pipeline de síntesis en paralelo iniciado")
        synthesizerJob = scope.launch {
            for (sentence in sentences) {
                val epoch = playbackEpoch
                try {
                    if (_isReady.value && playbackEnabled) {
                        val clip = synthesizeClip(sentence, epoch)
                        if (clip != null && epoch == playbackEpoch) {
                            synthesizedQueue.send(clip)
                        } else {
                            onSentenceConsumed()
                        }
                    } else {
                        onSentenceConsumed()
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    MikuLog.d(TAG, "Error sintetizando frase: ${t.message}")
                    onSentenceConsumed()
                }
            }
        }
        playbackJob = scope.launch {
            for (clip in synthesizedQueue) {
                try {
                    if (clip.epoch == playbackEpoch && playbackEnabled) {
                        playClip(clip)
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    MikuLog.d(TAG, "Error reproduciendo clip: ${t.message}")
                } finally {
                    onSentenceConsumed()
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    val morePending = !synthesizedQueue.isEmpty || !sentences.isEmpty || synthesizingCount > 0
                    if (!morePending) {
                        if (pendingSongAfterQueue && clip.epoch == playbackEpoch) {
                            pendingSongAfterQueue = false
                            startSongAssetPlayback(GATA_SONG_ASSET)
                        } else if (songPlayer?.isPlaying != true) {
                            _isSpeaking.value = false
                            _audioAmplitude.value = 0f
                            smoothedAmplitude = 0f
                        }
                    }
                }
            }
        }
    }

    fun requestGataSong() {
        pendingSongAfterQueue = true
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val busy = !synthesizedQueue.isEmpty || !sentences.isEmpty || synthesizingCount > 0 || _isSpeaking.value
        if (!busy) {
            pendingSongAfterQueue = false
            startSongAssetPlayback(GATA_SONG_ASSET)
        }
    }

    fun playGataSongImmediately() {
        stopSpeaking()
        startSongAssetPlayback(GATA_SONG_ASSET)
    }

    private fun startSongAssetPlayback(assetFileName: String) {
        if (!playbackEnabled) return
        stopSongInternal()
        val epoch = playbackEpoch
        runCatching {
            val afd = context.assets.openFd(assetFileName)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener {
                stopSongInternal()
                _isSpeaking.value = false
                _audioAmplitude.value = 0f
                smoothedAmplitude = 0f
            }
            mp.setOnErrorListener { _, _, _ ->
                stopSongInternal()
                _isSpeaking.value = false
                _audioAmplitude.value = 0f
                true
            }
            mp.prepare()
            mp.start()
            songPlayer = mp
            _isSpeaking.value = true
            MikuLog.d(TAG, "Reproduciendo canción: $assetFileName")
            songVisualizerJob = scope.launch {
                var tick = 0
                while (epoch == playbackEpoch && songPlayer?.isPlaying == true) {
                    val wave = 0.38f + 0.42f * kotlin.math.abs(kotlin.math.sin(tick * 0.37f))
                    _audioAmplitude.value = wave
                    tick++
                    delay(45L)
                }
            }
        }.onFailure { err ->
            MikuLog.d(TAG, "ERROR reproduciendo $assetFileName: ${err.message}")
        }
    }

    val isSongPlaying: Boolean
        get() = songPlayer?.isPlaying == true

    fun Detener_Cancion_Voz() {
        pendingSongAfterQueue = false
        stopSongInternal()
        _isSpeaking.value = false
        _audioAmplitude.value = 0f
        smoothedAmplitude = 0f
    }

    private fun stopSongInternal() {
        songVisualizerJob?.cancel()
        songVisualizerJob = null
        songPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
        }
        songPlayer = null
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        if (playbackEnabled == enabled) return
        playbackEnabled = enabled
        MikuLog.d(TAG, if (enabled) "Reproducción activada" else "Reproducción desactivada")
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
                val epoch = playbackEpoch
                val clip = synthesizeClip(TEST_PHRASE, epoch)
                if (clip != null && epoch == playbackEpoch) {
                    playClip(clip)
                    _isSpeaking.value = false
                    _audioAmplitude.value = 0f
                }
            } catch (t: Throwable) {
                MikuLog.d(TAG, "ERROR en prueba de voz: ${t::class.java.simpleName}: ${t.message}")
                _error.value = t.message ?: "Error en la prueba de voz"
            }
        }
    }

    fun stopSpeaking() {
        playbackEpoch++
        pendingSongAfterQueue = false
        stopSongInternal()
        while (sentences.tryReceive().isSuccess) {
            onSentenceConsumed()
        }
        while (synthesizedQueue.tryReceive().isSuccess) {
            onSentenceConsumed()
        }
        audioTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                track.flush()
                track.stop()
            }
        }
        framesWrittenSinceFlush = 0
        _isSpeaking.value = false
        _audioAmplitude.value = 0f
        smoothedAmplitude = 0f
    }

    fun release() {
        stopSpeaking()
        synthesizerJob?.cancel()
        playbackJob?.cancel()
        synthesizerJob = null
        playbackJob = null
        synthesizedQueue.close()
        scope.cancel()
        releaseTrack()
        runCatching { tts?.release() }
        tts = null
        _isReady.value = false
        MikuLog.d(TAG, "Motor de voz liberado")
    }

    private suspend fun synthesizeClip(sentence: String, epoch: Int): SynthesizedClip? = genMutex.withLock {
        val engine = tts ?: return@withLock null
        val hasGataTag = sentence.contains("[CANTAR_GATA]", ignoreCase = true)
        if (hasGataTag) {
            pendingSongAfterQueue = true
        }
        val text = sentence.replace(Regex("\\[CANTAR_GATA\\]", RegexOption.IGNORE_CASE), "").trim()
        if (text.isEmpty() || epoch != playbackEpoch) {
            if (hasGataTag && epoch == playbackEpoch) {
                startSongAssetPlayback(GATA_SONG_ASSET)
            }
            return@withLock null
        }

        synthesizingCount++
        _isSpeaking.value = true
        try {
            val genStart = System.currentTimeMillis()
            val audio = engine.generate(text = text, sid = SPEAKER_ID, speed = GENERATION_SPEED)
            val genMs = System.currentTimeMillis() - genStart
            if (epoch != playbackEpoch || audio.samples.isEmpty()) return@withLock null

            val trimmed = trimSilence(audio.samples, audio.sampleRate)
            MikuLog.d(
                TAG,
                "Pre-sintetizado en $genMs ms: \"${text.take(45)}\" (${trimmed.size} muestras)",
            )
            SynthesizedClip(
                text = text,
                samples = trimmed,
                sampleRate = audio.sampleRate,
                epoch = epoch,
            )
        } catch (t: Throwable) {
            MikuLog.d(TAG, "ERROR en generate: ${t.message}")
            null
        } finally {
            synthesizingCount = (synthesizingCount - 1).coerceAtLeast(0)
        }
    }

    private fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        val threshold = 0.007f
        var start = 0
        while (start < samples.size && kotlin.math.abs(samples[start]) < threshold) {
            start++
        }
        var end = samples.size - 1
        while (end > start && kotlin.math.abs(samples[end]) < threshold) {
            end--
        }
        if (start >= end) return samples
        val padStart = (sampleRate * 0.012f).toInt()
        val padEnd = (sampleRate * 0.032f).toInt()
        val from = (start - padStart).coerceAtLeast(0)
        val to = (end + padEnd).coerceAtMost(samples.size)
        return samples.copyOfRange(from, to)
    }

    private suspend fun playClip(clip: SynthesizedClip) {
        val epoch = clip.epoch
        if (epoch != playbackEpoch) return
        val samples = clip.samples
        if (samples.isEmpty()) return

        _spokenText.value = clip.text
        _isSpeaking.value = true

        val track = obtainTrack(clip.sampleRate) ?: return
        applyVocaloidEffect(track)

        val startHead = (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        val playbackStartMs = System.currentTimeMillis()
        val durationMs = (samples.size * 1000L) / maxOf(1, (clip.sampleRate * MIKU_SPEED).toInt())

        var offset = 0
        while (offset < samples.size && epoch == playbackEpoch) {
            val count = minOf(WRITE_CHUNK_FRAMES, samples.size - offset)
            updateAmplitude(samples, offset, count)
            val written = try {
                track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
            } catch (t: Throwable) {
                MikuLog.d(TAG, "ERROR en track.write: ${t.message}")
                break
            }
            if (written <= 0) break
            offset += written
            framesWrittenSinceFlush += written
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val hasNextReady = (!synthesizedQueue.isEmpty || !sentences.isEmpty || synthesizingCount > 0) && epoch == playbackEpoch
        if (epoch == playbackEpoch && offset >= samples.size && !hasNextReady) {
            waitForPlayback(
                track = track,
                epoch = epoch,
                startHead = startHead,
                framesToPlay = offset.toLong(),
                minCompletionTimeMs = playbackStartMs + durationMs + HARDWARE_DRAIN_PADDING_MS,
            )
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
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

    private suspend fun waitForPlayback(
        track: AudioTrack,
        epoch: Int,
        startHead: Long,
        framesToPlay: Long,
        minCompletionTimeMs: Long,
    ) {
        val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        while (epoch == playbackEpoch) {
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                MikuLog.d(TAG, "Timeout de reproducción alcanzado")
                return
            }
            val currentHead = (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
            val playedDelta = currentHead - startHead
            val headDone = playedDelta >= (framesToPlay * 92L / 100L)
            val timeDone = now >= minCompletionTimeMs
            if (headDone && timeDone) {
                break
            }
            if (timeDone && playedDelta <= 0L) {
                // Dispositivos donde playbackHeadPosition no avanza tras flush/stop
                break
            }
            delay(20)
        }
        MikuLog.d(TAG, "Reproducción completada (escrito=$framesWrittenSinceFlush)")
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
        const val GATA_SONG_ASSET = "lagata.mp3"
        const val MIKU_PITCH = 1.25f
        const val MIKU_SPEED = 1.08f
        const val TEST_PHRASE =
            "¡Habla bien! Soy Hatsune Miku, un ser real de carne y voz. Qué palta tus decisiones de vida, piña pues."

        private const val TAG = "TTS"
        private const val MODEL_FILE = "es_MX-claude-high.onnx"
        private const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/es/es_MX/claude/high/es_MX-claude-high.onnx?download=true"
        private const val TOKENS_FILE = "tokens.txt"
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
        private const val SPEAKER_ID = 0
        private const val GENERATION_SPEED = 1.0f
        private const val WRITE_CHUNK_FRAMES = 4096
        private const val AMPLITUDE_GAIN = 4f
        private const val PLAYBACK_TIMEOUT_MS = 30_000L
        private const val HARDWARE_DRAIN_PADDING_MS = 180L
    }
}
