package com.example.mikuapp

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mikuapp.audio.AudioRecorder
import com.example.mikuapp.audio.GeminiAudioPlayer
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
import kotlin.random.Random

class MikuViewModel(application: Application) : AndroidViewModel(application) {

    private val sentenceBuffer = SentenceBuffer()
    private val ttsEngine = MikuTtsEngine(application, sentenceBuffer.sentences)
    private val geminiAudio = GeminiAudioPlayer()
    private val commandExecutor = AndroidCommandExecutor(application)

    private var geminiClient: GeminiLiveClient? = null
    private var audioRecorder: AudioRecorder? = null
    private var stateCollectionJob: Job? = null
    private var initializationStarted = false
    private var consecutiveBargeInChunks = 0
    private var lastApiKey = ""
    private var pendingPrompt: String? = null
    private var userTurnActive = false

    private val _connectionState = MutableStateFlow(GeminiLiveClient.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GeminiLiveClient.ConnectionState> = _connectionState.asStateFlow()

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

    private val _currentExpression = MutableStateFlow<MikuExpression?>(null)
    val currentExpression: StateFlow<MikuExpression?> = _currentExpression.asStateFlow()

    private var expressionTimerJob: Job? = null

    private var lastCameraFrameAt = 0L

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
    ) { local, remote -> local || remote }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
        updateVoiceRouting()
        sentenceBuffer.onSentence = { sentence -> handleMikuSentence(sentence) }
    }

    private fun handleMikuSentence(sentence: String) {
        ExpressionDetector.detect(sentence)?.let { showExpression(it, "frase") }
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
                MikuLog.d(TAG, "Modelo de voz NO disponible: ${ttsEngine.error.value}")
                _statusMessage.value = ttsEngine.error.value
                    ?: "No se pudo cargar el modelo de voz local"
            }
            sentenceBuffer.reset()
            ttsEngine.start()
            _isInitializing.value = false
        }
    }

    fun connect(apiKey: String) {
        if (geminiClient != null) return
        if (apiKey.isBlank()) {
            MikuLog.d(TAG, "ERROR: GEMINI_API_KEY vacía")
            _statusMessage.value = "Falta la GEMINI_API_KEY en local.properties"
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
        sentenceBuffer.reset()
        stateCollectionJob?.cancel()
        stateCollectionJob = viewModelScope.launch {
            client.connectionState.collect { state ->
                _connectionState.value = state
            }
        }
        client.connect()
    }

    fun disconnect() {
        MikuLog.d(TAG, "Desconectando")
        pendingPrompt = null
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        clearExpression("sesion")
        stopRecorder()
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

    fun toggleCamera() {
        val newValue = !_isCameraActive.value
        _isCameraActive.value = newValue
        lastCameraFrameAt = 0L
        MikuLog.d(TAG, "Cámara ${if (newValue) "ACTIVADA ( ahora te miro por sus ojos )" else "desactivada"}")
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

        if (ttsEngine.isSpeaking.value) {
            if (rms >= BARGE_IN_RMS_THRESHOLD) {
                consecutiveBargeInChunks++
                if (consecutiveBargeInChunks >= BARGE_IN_CHUNKS_REQUIRED) {
                    consecutiveBargeInChunks = 0
                    MikuLog.d(TAG, "Barge-in detectado (RMS=%.3f): cortando voz".format(rms))
                    ttsEngine.stopSpeaking()
                    sentenceBuffer.reset()
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
            stopRecorder()
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
            userTurnActive = false
            sentenceBuffer.append(text)
            responseBuilder.append(text)
            _responseText.value = responseBuilder.toString()
        }

        override fun onInputTranscription(text: String) {
            if (!userTurnActive) {
                userSpeechBuilder.setLength(0)
                userTurnActive = true
            }
            userSpeechBuilder.append(text)
            val userText = userSpeechBuilder.toString().trim()
            _userSpeech.value = userText
            ExpressionDetector.detect(userText)?.let { showExpression(it, "usuario") }
        }

        override fun onAudioChunk(pcm16: ByteArray, sampleRate: Int) {
            geminiAudio.play(pcm16, sampleRate)
        }

        override fun onTurnComplete() {
            sentenceBuffer.flush()
            geminiAudio.turnComplete()
        }

        override fun onInterrupted() {
            MikuLog.d(TAG, "Interrupción: descartando frases pendientes")
            clearExpression("interrumpida")
            sentenceBuffer.reset()
            ttsEngine.stopSpeaking()
            geminiAudio.stop()
        }

        override fun onFunctionCall(call: GeminiLiveClient.FunctionCall) {
            val client = geminiClient ?: return
            MikuLog.d(TAG, "Ejecutando ${call.name}...")
            viewModelScope.launch(Dispatchers.IO) {
                val result = commandExecutor.execute(call.name, call.args)
                MikuLog.d(TAG, "Resultado de ${call.name}: $result")
                client.sendFunctionResponse(call.id, call.name, result)
            }
        }
    }

    override fun onCleared() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        stopRecorder()
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
        private const val BARGE_IN_RMS_THRESHOLD = 0.09f
        private const val BARGE_IN_CHUNKS_REQUIRED = 3
        private const val EXPRESSION_MIN_MS = 5000L
        private const val EXPRESSION_MAX_MS = 8000L
    }
}
