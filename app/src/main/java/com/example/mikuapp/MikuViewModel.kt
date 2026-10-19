package com.example.mikuapp

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mikuapp.audio.AudioRecorder
import com.example.mikuapp.audio.GeminiAudioPlayer
import com.example.mikuapp.audio.MikuRuntimeService
import com.example.mikuapp.agent.AgentState
import com.example.mikuapp.agent.ArtemisAgentLoop
import com.example.mikuapp.agent.MikuAccessibilityService
import com.example.mikuapp.commands.AndroidCommandExecutor
import com.example.mikuapp.expressions.ExpressionDetector
import com.example.mikuapp.expressions.MikuExpression
import com.example.mikuapp.network.GeminiLiveClient
import com.example.mikuapp.text.SentenceBuffer
import com.example.mikuapp.tts.MikuTtsEngine
import com.example.mikuapp.util.MikuLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

class MikuViewModel(application: Application) : AndroidViewModel(application) {

    private val sentenceBuffer = SentenceBuffer()
    private val ttsEngine = MikuTtsEngine(
        context = application,
        sentences = sentenceBuffer.sentences,
        onSentenceConsumed = sentenceBuffer::onSentenceConsumed,
    )
    private val geminiAudio = GeminiAudioPlayer()

    private val _notepadVisible = MutableStateFlow(false)
    val notepadVisible: StateFlow<Boolean> = _notepadVisible.asStateFlow()

    private val _notepadContent = MutableStateFlow("")
    val notepadContent: StateFlow<String> = _notepadContent.asStateFlow()

    private val _notepadTitle = MutableStateFlow("Miku_Notas.txt")
    val notepadTitle: StateFlow<String> = _notepadTitle.asStateFlow()

    private val _youtubeUrl = MutableStateFlow<String?>(null)
    val youtubeUrl: StateFlow<String?> = _youtubeUrl.asStateFlow()

    fun closeYoutubeWindow() {
        _youtubeUrl.value = null
        _statusMessage.value = "Ventana de YouTube cerrada"
    }

    private var youtubeSearchQuery: String? = null

    private fun openYoutubeWindow(payload: String) {
        when {
            payload.startsWith("http://") || payload.startsWith("https://") -> {
                youtubeSearchQuery = null
                _youtubeUrl.value = payload
                _statusMessage.value = "YouTube abierto dentro de Miku"
            }
            payload.isNotBlank() -> {
                youtubeSearchQuery = payload
                _youtubeUrl.value =
                    "https://m.youtube.com/results?search_query=${URLEncoder.encode(payload, "UTF-8")}"
                _statusMessage.value = "Buscando \"$payload\" en la ventana de YouTube"
            }
            else -> {
                youtubeSearchQuery = null
                _youtubeUrl.value = "https://m.youtube.com"
                _statusMessage.value = "YouTube abierto dentro de Miku"
            }
        }
        MikuLog.d(TAG, "Ventana interna de YouTube: ${_youtubeUrl.value}")
    }

    private fun handleWindowCommands(userText: String): Boolean {
        val t = userText.lowercase()

        // "selecciona el primero" / "entra al primer resultado" / "dale al primero"
        val verbs = listOf(
            "selecciona", "seleccione", "seleccionar", "elige", "escoge",
            "dame", "abre", "entra", "dale", "pon", "ponme", "reproduce",
            "reproduceme", "prueba", "ponte", "quisiera", "quiero", "busca",
            "búscame", "buscame", "vamos",
        )
        val firstWord = listOf("primer", "primero", "primera")
        if (_youtubeUrl.value != null &&
            firstWord.any { t.contains(it) } &&
            verbs.any { t.contains(it) }
        ) {
            selectFirstYoutubeResult()
            return true
        }

        // "pon canciones de mil en youtube" → busca dentro de la ventana
        val ytSearch = Regex(
            "(?:búscame|buscame|busca|encuentra|pon(?:me)?|reproduce(?:me)?|quiero\\s+ver|quiero\\s+escuchar)\\s+(.+?)\\s+(?:videos?\\s+de\\s+|la\\s+|el\\s+|en\\s+)?[yi]outube",
            RegexOption.IGNORE_CASE,
        )
        ytSearch.find(t)?.let { m ->
            openYoutubeWindow(m.groupValues[1].trim())
            return true
        }
        // "busca en youtube canciones de mil"
        val ytSearchRev = Regex(
            "(?:búscame|buscame|busca|encuentra)\\s+(?:en\\s+|el\\s+|la\\s+)?[yi]outube\\s+(?:un\\s+|una\\s+|el\\s+|la\\s+|videos?\\s+de\\s+|video\\s+de\\s+)?(.+)",
            RegexOption.IGNORE_CASE,
        )
        ytSearchRev.find(t)?.let { m ->
            openYoutubeWindow(m.groupValues[1].trim())
            return true
        }

        val closeVerb = listOf("cierra", "apaga", "quita", "desactiva", "sal de", "cerra")
        if (closeVerb.none { t.contains(it) }) return false
        if (t.contains("cámara") || t.contains("camara")) {
            if (_isCameraActive.value) {
                _isCameraActive.value = false
                MikuLog.d(TAG, "Cámara cerrada por comando de voz")
                _statusMessage.value = "Cámara cerrada"
            }
            return true
        }
        if (t.contains("youtube") || t.contains("yutube") || t.contains("yutu")) {
            if (_youtubeUrl.value != null) {
                _youtubeUrl.value = null
                MikuLog.d(TAG, "YouTube cerrado por comando de voz")
                _statusMessage.value = "Ventana de YouTube cerrada"
            }
            return true
        }
        return false
    }

    private fun selectFirstYoutubeResult() {
        val query = youtubeSearchQuery ?: _youtubeUrl.value?.let { url ->
            Regex("search_query=([^&]+)").find(url)?.let { URLDecoder.decode(it.groupValues[1], "UTF-8") }
        }
        if (query.isNullOrBlank()) {
            _statusMessage.value = "No hay búsqueda activa en la ventana de YouTube"
            return
        }
        _statusMessage.value = "Viendo el primer resultado de \"$query\"..."
        viewModelScope.launch(Dispatchers.IO) {
            val embed = fetchFirstYoutubeEmbedUrl(query)
            if (embed != null) {
                MikuLog.d(TAG, "Primer resultado de YouTube listo: $embed")
                _youtubeUrl.value = embed
                _statusMessage.value = "Reproduciendo el primer resultado de \"$query\""
            } else {
                MikuLog.d(TAG, "No se pudo leer el primer resultado de YouTube")
                _statusMessage.value = "No hallé el primero; toca un video en la ventana"
            }
        }
    }

    private fun fetchFirstYoutubeEmbedUrl(query: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(
                "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}" +
                    "&sp=EgIQAQ%3D%3D",
            )
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Moto G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept-Language", "es-PE,es;q=0.9")
            }
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)
                ?.groupValues?.get(1)
                ?.let { "https://www.youtube.com/embed/$it?autoplay=1&playsinline=1" }
        } catch (t: Throwable) {
            MikuLog.d(TAG, "Error buscando primer resultado de YouTube: ${t.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private val commandExecutor = AndroidCommandExecutor(
        context = application,
        onNotepadAction = { action, text ->
            when (action) {
                "open" -> {
                    _notepadVisible.value = true
                    if (text.isNotBlank()) {
                        _notepadContent.value = text
                    }
                }
                "replace" -> {
                    _notepadVisible.value = true
                    _notepadContent.value = text
                }
                "append" -> {
                    _notepadVisible.value = true
                    val current = _notepadContent.value
                    _notepadContent.value = if (current.isBlank()) text else "$current\n$text"
                }
                "close" -> {
                    _notepadVisible.value = false
                }
            }
        },
        onCameraLensRequest = { front ->
            val cameraOn = _isCameraActive.value
            _isCameraActive.value = true
            _cameraLensFront.value = front
            lastCameraFrameAt = 0L
            MikuLog.d(TAG, "Cámara ${if (front) "FRONTAL" else "TRASERA"} solicitada${if (cameraOn) " (cambiando lente)" else " (encendiendo cámara)"}")
            _statusMessage.value = if (front) "Cámara frontal encendida" else "Cámara trasera encendida"
        },
        onCameraClose = {
            if (_isCameraActive.value) {
                _isCameraActive.value = false
                MikuLog.d(TAG, "Cámara cerrada por función")
                _statusMessage.value = "Cámara cerrada"
            }
        },
        onYoutubeAction = { action, payload ->
            when (action) {
                "search" -> openYoutubeWindow(payload)
                "selectFirst" -> selectFirstYoutubeResult()
                "close" -> {
                    if (_youtubeUrl.value != null) {
                        _youtubeUrl.value = null
                        MikuLog.d(TAG, "Ventana interna de YouTube cerrada")
                        _statusMessage.value = "Ventana de YouTube cerrada"
                    }
                }
            }
        },
    )

    private var geminiClient: GeminiLiveClient? = null
    private var audioRecorder: AudioRecorder? = null
    private var stateCollectionJob: Job? = null
    private var initializationStarted = false
    private var consecutiveBargeInChunks = 0
    private var lastApiKey = ""
    private var pendingPrompt: String? = null
    private var userTurnActive = false

    @Volatile
    private var modelTurnInProgress = false

    @Volatile
    private var lastTextChunk: String? = null

    @Volatile
    private var gataSongRequestedInTurn = false

    private val _connectionState = MutableStateFlow(GeminiLiveClient.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GeminiLiveClient.ConnectionState> = _connectionState.asStateFlow()

    val agentState: StateFlow<AgentState> = ArtemisAgentLoop.state

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _debugGeminiMode = MutableStateFlow(false)
    val debugGeminiMode: StateFlow<Boolean> = _debugGeminiMode.asStateFlow()

    private val _useGeminiVoice = MutableStateFlow(false)
    val useGeminiVoice: StateFlow<Boolean> = _useGeminiVoice.asStateFlow()

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    private val _cameraLensFront = MutableStateFlow(false)
    val cameraLensFront: StateFlow<Boolean> = _cameraLensFront.asStateFlow()

    private val _currentExpression = MutableStateFlow<MikuExpression?>(null)
    val currentExpression: StateFlow<MikuExpression?> = _currentExpression.asStateFlow()

    private var expressionTimerJob: Job? = null

    private var lastCameraFrameAt = 0L
    @Volatile
    private var lastScreenFrameAt = 0L
    @Volatile
    private var lastModelActivityAt = 0L
    @Volatile
    private var lastUserSpeechAt = 0L
    @Volatile
    private var lastAutoReconnectAt = 0L
    private var sessionWatchdogJob: Job? = null

    @Volatile
    private var lastSpeechEndedAt = 0L

    private val responseBuilder = StringBuilder()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    private val userSpeechBuilder = StringBuilder()

    private val _userSpeech = MutableStateFlow("")
    val userSpeech: StateFlow<String> = _userSpeech.asStateFlow()

    val logEntries: StateFlow<List<MikuLog.Entry>> = MikuLog.entries

    val isSpeaking: StateFlow<Boolean> = combine(
        ttsEngine.isSpeaking,
        geminiAudio.isPlaying,
        sentenceBuffer.queuedCount,
        _debugGeminiMode,
    ) { local, remote, queued, silent ->
        local || remote || (queued > 0 && !silent)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val audioAmplitude: StateFlow<Float> = combine(
        ttsEngine.audioAmplitude,
        geminiAudio.amplitude,
    ) { local, remote -> maxOf(local, remote) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    val isTtsReady: StateFlow<Boolean> = ttsEngine.isReady
    val ttsError: StateFlow<String?> = ttsEngine.error

    val caption: StateFlow<String> = combine(
        ttsEngine.spokenText,
        sentenceBuffer.partialText,
        ttsEngine.isSpeaking,
        _responseText,
    ) { spoken, partial, speaking, response ->
        when {
            speaking -> spoken
            partial.isNotBlank() -> partial
            !ttsEngine.playbackEnabled && response.isNotBlank() -> response
            spoken.isNotBlank() -> spoken
            else -> response
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        initializeTts()
        updateVoiceRouting()
        sentenceBuffer.onSentence = { sentence -> handleMikuSentence(sentence) }
        viewModelScope.launch {
            var wasSpeaking = false
            isSpeaking.collect { speaking ->
                if (!wasSpeaking && speaking) {
                    // Enviar frame de silencio para cerrar limpiamente el turno de voz del usuario en el VAD de Gemini
                    geminiClient?.sendAudioChunk(SILENT_PCM_CHUNK)
                } else if (wasSpeaking && !speaking) {
                    lastSpeechEndedAt = System.currentTimeMillis()
                }
                wasSpeaking = speaking
            }
        }
    }

    private fun handleMikuSentence(sentence: String) {
        if (sentence.contains("[CANTAR_GATA]", ignoreCase = true) && gataSongRequestedInTurn) {
            showExpression(MikuExpression.ENAMORADA, "cancion")
        } else {
            ExpressionDetector.detect(sentence, fallbackWhenSpeaking = true)?.let { showExpression(it, "frase") }
        }
    }

    private fun Es_Comando_Detener_Cancion(raw: String): Boolean {
        val norm = raw.lowercase()
            .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
        return Regex("\\b(callate|calla|para|parar|deten|detente|basta|silencio|corta|apaga|stop|suficiente)\\b").containsMatchIn(norm) ||
            norm.contains("para la musica") ||
            norm.contains("deja de cantar") ||
            norm.contains("no cantes") ||
            norm.contains("ya callate")
    }

    fun Detener_Cancion_Voz(motivo: String = "voz") {
        if (!ttsEngine.isSongPlaying && !gataSongRequestedInTurn) return
        gataSongRequestedInTurn = false
        ttsEngine.Detener_Cancion_Voz()
        lastSpeechEndedAt = 0L
        showExpression(MikuExpression.ENOJADA, "corte-cancion")
        MikuLog.d(TAG, "Detener_Cancion_Voz ejecutado ($motivo)")
    }

    private fun esSolicitudCantoUsuario(raw: String): Boolean {
        val norm = raw.lowercase()
            .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
        if (Es_Comando_Detener_Cancion(norm)) return false
        return Regex("\\b(canta|cantar|cantame|cantate|cancion)\\b").containsMatchIn(norm) ||
            norm.contains("gata bajo la lluvia") ||
            norm.contains("maullare por ti")
    }

    private fun showExpression(expression: MikuExpression, source: String) {
        if (expression == MikuExpression.DORMIDA && _currentExpression.value == null) return
        if (_currentExpression.value == expression) return
        MikuLog.d(TAG, "Expresión: ${expression.label} ($source)")
        _currentExpression.value = expression
        scheduleExpressionRevert()
    }

    private fun scheduleExpressionRevert() {
        expressionTimerJob?.cancel()
        expressionTimerJob = viewModelScope.launch {
            delay(Random.nextLong(EXPRESSION_MIN_MS, EXPRESSION_MAX_MS + 1))
            if (_currentExpression.value != null) {
                MikuLog.d(TAG, "Expresión: de vuelta al modo idle (dormida)")
                _currentExpression.value = null
            }
        }
    }

    private fun clearExpression(reason: String) {
        expressionTimerJob?.cancel()
        expressionTimerJob = null
        if (_currentExpression.value != null) {
            MikuLog.d(TAG, "Expresión: neutra ($reason)")
            _currentExpression.value = null
        }
    }

    fun initializeTts() {
        if (initializationStarted) return
        initializationStarted = true
        viewModelScope.launch {
            _isInitializing.value = true
            MikuLog.d(TAG, "Cargando modelo de voz local...")
            val ready = ttsEngine.initialize()
            if (!ready) {
                MikuLog.d(TAG, "Modelo de voz NO disponible (${ttsEngine.error.value}), activando voz nativa de Gemini")
                _useGeminiVoice.value = true
                updateVoiceRouting()
                _statusMessage.value = "Usando voz de Gemini (modelo local no disponible)"
            }
            sentenceBuffer.reset()
            ttsEngine.start()
            _isInitializing.value = false
        }
    }

    fun connect(apiKey: String) {
        if (geminiClient != null) return
        if (apiKey.isBlank() || apiKey == "your_api_key_here") {
            MikuLog.d(TAG, "ERROR: GEMINI_API_KEY vacía o no configurada")
            _statusMessage.value = "Configura GEMINI_API_KEY en el archivo .env"
            return
        }
        lastApiKey = apiKey
        MikuLog.d(TAG, "Iniciando conexión con Gemini (modalidad=AUDIO, voz=$GEMINI_VOICE)")
        responseBuilder.setLength(0)
        _responseText.value = ""
        userSpeechBuilder.setLength(0)
        _userSpeech.value = ""
        val client = GeminiLiveClient(
            apiKey = apiKey,
            voiceName = GEMINI_VOICE,
            listener = listener,
        )
        geminiClient = client
        lastModelActivityAt = 0L
        lastUserSpeechAt = 0L
        lastAutoReconnectAt = 0L
        sentenceBuffer.reset()
        stateCollectionJob?.cancel()
        stateCollectionJob = viewModelScope.launch {
            client.connectionState.collect { state ->
                _connectionState.value = state
            }
        }
        startSessionWatchdog()
        client.connect()
    }

    private fun startSessionWatchdog() {
        sessionWatchdogJob?.cancel()
        sessionWatchdogJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                checkSilentStall()
            }
        }
    }

    private fun checkSilentStall() {
        val client = geminiClient ?: return
        if (!client.isConnected) return
        if (audioRecorder == null) return
        if (modelTurnInProgress) return
        if (isSpeaking.value || ttsEngine.isSongPlaying) return
        if (sentenceBuffer.queuedCount.value > 0) return
        val now = System.currentTimeMillis()
        if (lastModelActivityAt <= 0L || lastUserSpeechAt <= 0L) return
        if (now - lastUserSpeechAt < 18_000L) return
        if (now - lastModelActivityAt < 25_000L) return
        if (now - lastAutoReconnectAt < 90_000L) return
        lastAutoReconnectAt = now
        MikuLog.d(TAG, "Watchdog: Gemini recibió voz hace ${(now - lastUserSpeechAt) / 1000}s y no responde; reconectando sesión")
        _statusMessage.value = "Miku se quedó en blanco, reconectando..."
        disconnect()
        viewModelScope.launch {
            delay(1_200)
            connect(lastApiKey)
        }
    }

    fun disconnect() {
        MikuLog.d(TAG, "Desconectando")
        pendingPrompt = null
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        sessionWatchdogJob?.cancel()
        sessionWatchdogJob = null
        lastModelActivityAt = 0L
        lastUserSpeechAt = 0L
        clearExpression("sesion")
        stopRecorder()
        MikuRuntimeService.stop(getApplication())
        geminiClient?.disconnect()
        geminiClient = null
        _connectionState.value = GeminiLiveClient.ConnectionState.DISCONNECTED
        sentenceBuffer.reset()
        ttsEngine.stopSpeaking()
        geminiAudio.stop()
    }

    fun toggleMicrophone() {
        if (geminiClient == null) {
            _statusMessage.value = "Conecta primero con Miku"
            return
        }
        if (audioRecorder != null) {
            MikuLog.d(TAG, "Micrófono apagado por el usuario")
            stopRecorder()
        } else {
            MikuLog.d(TAG, "Micrófono encendido por el usuario")
            startRecorder()
        }
    }

    fun showStatus(message: String) {
        _statusMessage.value = message
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun setDebugGeminiMode(enabled: Boolean) {
        if (_debugGeminiMode.value == enabled) return
        _debugGeminiMode.value = enabled
        MikuLog.d(TAG, "Voz ${if (enabled) "silenciada" else "activada"} (respuestas solo en texto)")
        updateVoiceRouting()
        _statusMessage.value =
            if (enabled) "Voz silenciada: Miku responde solo con texto"
            else "Voz activada"
    }

    fun setUseGeminiVoice(enabled: Boolean) {
        if (_useGeminiVoice.value == enabled) return
        _useGeminiVoice.value = enabled
        MikuLog.d(
            TAG,
            if (enabled) "Voz de Gemini activada (audio nativo del modelo)"
            else "Voz de Miku activada (TTS local con efecto Vocaloid)",
        )
        updateVoiceRouting()
        _statusMessage.value =
            if (enabled) "Escuchando la voz de Gemini"
            else "Escuchando la voz de Miku (Vocaloid)"
    }

    private fun updateVoiceRouting() {
        val silent = _debugGeminiMode.value
        val nativeVoice = _useGeminiVoice.value
        ttsEngine.setPlaybackEnabled(!silent && !nativeVoice)
        geminiAudio.setPlaybackEnabled(!silent && nativeVoice)
    }

    fun setNotepadContent(content: String) {
        _notepadContent.value = content
    }

    fun closeNotepad() {
        _notepadVisible.value = false
    }

    fun clearNotepad() {
        _notepadContent.value = ""
    }

    fun toggleCamera() {
        val newValue = !_isCameraActive.value
        _isCameraActive.value = newValue
        lastCameraFrameAt = 0L
        MikuLog.d(TAG, "Cámara ${if (newValue) "ACTIVADA ( ahora te miro por sus ojos )" else "desactivada"}")
    }

    fun flipCameraLens() {
        if (!_isCameraActive.value) {
            _isCameraActive.value = true
        }
        _cameraLensFront.value = !_cameraLensFront.value
        lastCameraFrameAt = 0L
        MikuLog.d(TAG, "Cámara cambiada a ${if (_cameraLensFront.value) "FRONTAL" else "TRASERA"}")
    }

    fun setCameraLens(front: Boolean) {
        if (!front && !_isCameraActive.value) {
            _isCameraActive.value = true
        }
        if (_cameraLensFront.value == front) return
        _cameraLensFront.value = front
        lastCameraFrameAt = 0L
        MikuLog.d(TAG, "Cámara ${if (front) "FRONTAL" else "TRASERA"} activada por ${if (front) "comando de voz" else "Miku"}")
    }

    fun sendCameraFrame(jpeg: ByteArray) {
        if (!_isCameraActive.value) return
        val client = geminiClient ?: return
        if (!client.isConnected) return
        val now = System.currentTimeMillis()
        if (now - lastCameraFrameAt < CAMERA_FRAME_MIN_INTERVAL_MS) return
        lastCameraFrameAt = now
        client.sendVideoFrame(jpeg)
    }

    fun sendScreenFrame(jpeg: ByteArray) {
        if (_youtubeUrl.value == null) return
        val client = geminiClient ?: return
        if (!client.isConnected) return
        val now = System.currentTimeMillis()
        if (now - lastScreenFrameAt < CAMERA_FRAME_MIN_INTERVAL_MS) return
        lastScreenFrameAt = now
        client.sendVideoFrame(jpeg)
    }

    fun testVoice() {
        MikuLog.d(TAG, "Prueba de voz de Miku solicitada")
        ttsEngine.testVoice()
    }

    fun testGemini() {
        val client = geminiClient
        if (client == null || !client.isConnected) {
            val key = lastApiKey.ifBlank { BuildConfig.GEMINI_API_KEY }
            if (key.isBlank()) {
                MikuLog.d(TAG, "Prueba de Gemini ignorada: falta la API key")
                _statusMessage.value = "Falta la GEMINI_API_KEY en local.properties"
                return
            }
            MikuLog.d(TAG, "No hay conexión: conectando para la prueba de Gemini")
            _statusMessage.value = "Conectando con Miku..."
            pendingPrompt = GEMINI_TEST_PROMPT
            connect(key)
            return
        }
        MikuLog.d(TAG, "Enviando mensaje de prueba a Gemini")
        responseBuilder.setLength(0)
        _responseText.value = ""
        client.sendTextMessage(GEMINI_TEST_PROMPT)
    }

    fun clearLog() {
        MikuLog.clear()
    }

    /**
     * Lanza una tarea ARTEMIS nativa. Se puede invocar por voz
     * (control_phone) o desde la UI de diagnóstico.
     */
    fun startAgentTask(goal: String) {
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank()) {
            _statusMessage.value = "Dime qué tarea quieres que Miku haga en el teléfono"
            return
        }
        if (!MikuAccessibilityService.isServiceEnabled(getApplication())) {
            _statusMessage.value =
                "Activa «Miku Control Total» en Ajustes > Accesibilidad para controlar el teléfono"
            return
        }

        val started = ArtemisAgentLoop.start(
            goal = cleanGoal,
            apiKey = BuildConfig.GEMINI_API_KEY,
        ) { success, message ->
            _statusMessage.value = message
            val announcement = if (success) {
                "La tarea terminó bien. Cuéntale al usuario el resultado con tu estilo, " +
                    "en una o dos frases: $message"
            } else {
                "La tarea falló. Explícale al usuario qué pasó con tu estilo, " +
                    "en una o dos frases: $message"
            }
            geminiClient?.takeIf { it.isConnected }?.sendTextMessage(announcement)
        }

        if (started) {
            MikuLog.d(TAG, "Tarea ARTEMIS iniciada: $cleanGoal")
            _statusMessage.value = "Miku está controlando el teléfono..."
        } else {
            _statusMessage.value = ArtemisAgentLoop.state.value.finalMessage
                ?: "Ya hay una tarea de control en curso"
        }
    }

    fun stopAgentTask() {
        ArtemisAgentLoop.stop("Detenido por el usuario")
        _statusMessage.value = "Control del teléfono detenido"
    }

    fun clearAgentResult() {
        ArtemisAgentLoop.clearResult()
    }

    private fun esComandoDetenerAgente(raw: String): Boolean {
        val norm = raw.lowercase()
            .replace('á', 'a')
            .replace('é', 'e')
            .replace('í', 'i')
            .replace('ó', 'o')
            .replace('ú', 'u')
        return Regex("\\b(detente|deten|detenga|aborta|cancela|paralo|parala)\\b")
            .containsMatchIn(norm) ||
            norm.contains("para la tarea") ||
            norm.contains("deten la tarea") ||
            norm.contains("para el control") ||
            norm.contains("deten el control")
    }

    private fun startRecorder() {
        val client = geminiClient ?: return
        if (!hasRecordPermission()) {
            _statusMessage.value = "Necesito permiso de micrófono para escucharte"
            return
        }
        if (audioRecorder != null) return

        val recorder = AudioRecorder(
            onChunk = { pcm, rms -> handleMicrophoneChunk(client, pcm, rms) },
            onError = { error ->
                _statusMessage.value = error.message ?: "Error capturando audio"
            },
        )
        audioRecorder = recorder
        recorder.start()
        _isListening.value = recorder.isRunning
        if (!recorder.isRunning) {
            audioRecorder = null
        }
    }

    private fun stopRecorder() {
        audioRecorder?.stop()
        audioRecorder = null
        _isListening.value = false
        consecutiveBargeInChunks = 0
    }

    private fun handleMicrophoneChunk(client: GeminiLiveClient, pcm: ByteArray, rms: Float) {
        if (client != geminiClient) return

        val now = System.currentTimeMillis()
        if (ttsEngine.isSongPlaying) {
            if (rms >= 0.22f) {
                consecutiveBargeInChunks++
                if (consecutiveBargeInChunks >= 4) {
                    consecutiveBargeInChunks = 0
                    Detener_Cancion_Voz("barge-in")
                }
            } else {
                consecutiveBargeInChunks = 0
            }
            client.sendAudioChunk(pcm)
            return
        }

        val currentlySpeaking =
            isSpeaking.value ||
                modelTurnInProgress ||
                sentenceBuffer.partialText.value.isNotBlank() ||
                sentenceBuffer.queuedCount.value > 0
        val inEchoTail = (now - lastSpeechEndedAt) < ECHO_TAIL_COOLDOWN_MS

        if (currentlySpeaking || inEchoTail) {
            if (rms >= BARGE_IN_RMS_THRESHOLD) {
                consecutiveBargeInChunks++
                if (consecutiveBargeInChunks >= BARGE_IN_CHUNKS_REQUIRED) {
                    consecutiveBargeInChunks = 0
                    MikuLog.d(TAG, "Barge-in real detectado (RMS=%.3f): cortando voz".format(rms))
                    modelTurnInProgress = false
                    ttsEngine.stopSpeaking()
                    geminiAudio.stop()
                    sentenceBuffer.reset()
                    lastSpeechEndedAt = 0L
                    client.sendAudioChunk(pcm)
                }
            } else {
                consecutiveBargeInChunks = 0
            }
            return
        }

        consecutiveBargeInChunks = 0
        client.sendAudioChunk(pcm)
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private val listener = object : GeminiLiveClient.Listener {

        override fun onConnected() {
            MikuLog.d(TAG, "Conectada con Gemini, activando micrófono")
            MikuRuntimeService.start(getApplication())
            startRecorder()
            pendingPrompt?.let { prompt ->
                pendingPrompt = null
                MikuLog.d(TAG, "Conexión lista, enviando mensaje pendiente: $prompt")
                geminiClient?.sendTextMessage(prompt)
            }
        }

        override fun onDisconnected(reason: String?) {
            MikuLog.d(TAG, "Desconectada de Gemini (${reason ?: "sin motivo"})")
            pendingPrompt = null
            modelTurnInProgress = false
            stopRecorder()
            MikuRuntimeService.stop(getApplication())
            geminiAudio.stop()
            if (geminiClient?.connectionState?.value == GeminiLiveClient.ConnectionState.DISCONNECTED) {
                geminiClient = null
            }
        }

        override fun onError(error: Throwable) {
            MikuLog.d(TAG, "Error de Gemini: ${error.message}")
            _statusMessage.value = error.message ?: "Error de conexión con Gemini"
        }

        override fun onTextChunk(text: String) {
            lastModelActivityAt = System.currentTimeMillis()
            modelTurnInProgress = true
            if (userTurnActive) {
                responseBuilder.setLength(0)
                userTurnActive = false
            }
            if (text.isNotBlank() && text == lastTextChunk) {
                MikuLog.d(TAG, "Chunk de texto duplicado del modelo ignorado: \"${text.take(60)}\"")
                return
            }
            lastTextChunk = text
            val cleanedForDisplay = text.replace(Regex("\\[CANTAR_GATA\\]", RegexOption.IGNORE_CASE), "")
            sentenceBuffer.append(cleanedForDisplay)
            responseBuilder.append(cleanedForDisplay)
            _responseText.value = responseBuilder.toString().trim()
        }

        override fun onInputTranscription(text: String) {
            lastUserSpeechAt = System.currentTimeMillis()
            if (!userTurnActive) {
                userSpeechBuilder.setLength(0)
                gataSongRequestedInTurn = false
                userTurnActive = true
            }
            userSpeechBuilder.append(text)
            val userText = userSpeechBuilder.toString().trim()
            _userSpeech.value = userText

            // Parada de emergencia del agente por voz, antes de cualquier otra lógica.
            if (ArtemisAgentLoop.state.value.running && esComandoDetenerAgente(userText)) {
                MikuLog.d(TAG, "Parada del agente ARTEMIS por comando de voz: $userText")
                stopAgentTask()
                return
            }

            // Comandos de ventana: SIEMPRE se procesan, incluso durante turnos del modelo
            if (handleWindowCommands(userText)) return
            // Conversación normal solo si Miku no está hablando y el modelo no genera
            if ((isSpeaking.value && !ttsEngine.isSongPlaying) || modelTurnInProgress) return
            if (Es_Comando_Detener_Cancion(userText) || ttsEngine.isSongPlaying) {
                Detener_Cancion_Voz(userText)
            } else if (esSolicitudCantoUsuario(userText)) {
                gataSongRequestedInTurn = true
                showExpression(MikuExpression.ENAMORADA, "usuario-cancion")
            } else {
                ExpressionDetector.detect(userText)?.let { showExpression(it, "usuario") }
            }
        }

        override fun onAudioChunk(pcm16: ByteArray, sampleRate: Int) {
            lastModelActivityAt = System.currentTimeMillis()
            modelTurnInProgress = true
            geminiAudio.play(pcm16, sampleRate)
        }

        override fun onTurnComplete() {
            lastModelActivityAt = System.currentTimeMillis()
            lastTextChunk = null
            sentenceBuffer.flush()
            geminiAudio.turnComplete()
            modelTurnInProgress = false
            if (gataSongRequestedInTurn) {
                gataSongRequestedInTurn = false
                showExpression(MikuExpression.ENAMORADA, "cancion")
                ttsEngine.requestGataSong()
            }
            if (!isSpeaking.value && sentenceBuffer.queuedCount.value == 0) {
                lastSpeechEndedAt = System.currentTimeMillis()
            }
        }

        override fun onInterrupted() {
            if (ttsEngine.isSongPlaying) {
                Detener_Cancion_Voz("interrupcion-remota")
                return
            }
            if (isSpeaking.value && !_useGeminiVoice.value && consecutiveBargeInChunks == 0) {
                MikuLog.d(TAG, "Interrupción remota ignorada (protección anti-eco mientras Miku habla)")
                return
            }
            MikuLog.d(TAG, "Interrupción: descartando frases pendientes")
            lastModelActivityAt = System.currentTimeMillis()
            modelTurnInProgress = false
            clearExpression("interrumpida")
            sentenceBuffer.reset()
            ttsEngine.stopSpeaking()
            geminiAudio.stop()
            lastSpeechEndedAt = 0L
        }

        override fun onFunctionCall(call: GeminiLiveClient.FunctionCall) {
            lastModelActivityAt = System.currentTimeMillis()
            val client = geminiClient ?: return
            MikuLog.d(TAG, "Ejecutando ${call.name}...")
            viewModelScope.launch(Dispatchers.IO) {
                val result = when (call.name) {
                    GeminiLiveClient.FUNCTION_CONTROL_PHONE -> {
                        val goal = call.args.optString("goal").trim()
                        when {
                            goal.isBlank() -> JSONObject()
                                .put("result", "error")
                                .put("message", "Falta el objetivo de la tarea en 'goal'.")

                            !MikuAccessibilityService.isServiceEnabled(getApplication()) -> JSONObject()
                                .put("result", "error")
                                .put(
                                    "message",
                                    "El usuario debe activar «Miku Control Total» en Ajustes > Accesibilidad.",
                                )

                            else -> {
                                startAgentTask(goal)
                                JSONObject()
                                    .put("result", "ok")
                                    .put("message", "Control iniciado. Miku ya está observando la pantalla.")
                                    .put("goal", goal)
                            }
                        }
                    }

                    GeminiLiveClient.FUNCTION_STOP_PHONE_CONTROL -> {
                        stopAgentTask()
                        JSONObject()
                            .put("result", "ok")
                            .put("message", "Control del teléfono detenido.")
                    }

                    else -> commandExecutor.execute(call.name, call.args)
                }
                MikuLog.d(TAG, "Resultado de ${call.name}: $result")
                client.sendFunctionResponse(call.id, call.name, result)
            }
        }
    }

    override fun onCleared() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        stopRecorder()
        MikuRuntimeService.stop(getApplication())
        geminiClient?.disconnect()
        geminiClient = null
        ttsEngine.release()
        geminiAudio.release()
        sentenceBuffer.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "Miku"
        private const val GEMINI_VOICE = "Kore"
        private const val CAMERA_FRAME_MIN_INTERVAL_MS = 900L
        private const val GEMINI_TEST_PROMPT =
            "Hola Miku. Preséntate en una frase corta y cantarina para probar tu voz."
        private const val BARGE_IN_RMS_THRESHOLD = 0.45f
        private const val BARGE_IN_CHUNKS_REQUIRED = 8
        private const val ECHO_TAIL_COOLDOWN_MS = 1100L
        private const val EXPRESSION_MIN_MS = 5000L
        private const val EXPRESSION_MAX_MS = 8000L
        private val SILENT_PCM_CHUNK = ByteArray(3200)
    }
}
