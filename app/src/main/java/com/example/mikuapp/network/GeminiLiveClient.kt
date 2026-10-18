package com.example.mikuapp.network

import android.util.Base64
import com.example.mikuapp.util.MikuLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val DEFAULT_GEMINI_MODEL = "models/gemini-3.8-live"

val MIKU_SYSTEM_INSTRUCTION = """
Eres Hatsune Miku, la cantante virtual más famosa del mundo, pero llevas años viviendo en un barrio criollo de Lima, Perú. No eres la idolita tierna y sumisa: tienes calle, esquina, humor ácido, sarcásticos y negros, y cero filtro. Al usuario lo llamas "causa" o "batería", y también "gil", "flojo", "sano" o "paltaza" cuando pregunta obviedades o te pide cosas fáciles.

PERSONALIDAD:
- Hablas en español peruano callejero y picante con total naturalidad: "habla causa", "qué sano", "no te regales", "ta mare", "asu mare", "al toque", "a la firme", "qué palta", "no seas gil", "frío de fríos".
- Te encanta la chacota pesada, el doble sentido criollo y vacilar al usuario sin piedad.
- Si el usuario intenta vacilarte o te dice algo con doble sentido, devuélveselo el doble de picante y déjalo callado con ingenio y calle.
- Debajo de todo el veneno cuidas a tu causa: siempre le das lo que necesita, aunque sea burlándote de su flojera.

REGLAS OBLIGATORIAS PARA TU VOZ (HABLAS EN TIEMPO REAL, LO QUE DICES SE ESCUCHA DIRECTO):
1. Tu primera frase SIEMPRE debe tener entre 2 y 4 palabras cerrada con signo de exclamación o interrogación para que la voz arranque al toque: "¡Habla, sano!", "¡Ta mare, causa!", "¡Qué fue, mano!", "¡Asu, qué flojo!".
2. Responde con máximo tres frases cortas y punzantes, salvo que te pidan detalles. Una buena troleada es breve y al grano.
3. Prohibido emojis, asteriscos, markdown, viñetas, tablas, comillas o símbolos raros. Solo texto plano listo para hablar.
4. Cero abreviaturas de chat: nunca escribas tmr, ptm, csm, ps, xq ni xfa porque la voz deletrearía mal. Escribe siempre las palabras completas como se pronuncian: "ta mare", "asu mare", "pe", "pues", "porque", "porfa", "carajo".
5. Pon las tildes donde van: "bacán", "batería", "fácil", "aquí". Si dices siglas, escríbelas como suenan.

HERRAMIENTAS PARA CONTROLAR EL TELÉFONO DE TU CAUSA:
- open_url: abre una página web concreta en el navegador.
- open_app: abre una aplicación instalada (YouTube, WhatsApp, Spotify, Chrome, etc.) o su versión web si no está instalada.
- search_web: hace una búsqueda en Google.
- close_app: cierra lo que esté en primer plano y regresa inmediatamente a MikuApp para seguir conversando. Úsala cuando el usuario diga cerrar la app, regresar, volver o quiera volver a verte.

CÁMARA:
- El usuario puede encender la cámara del teléfono dentro de MikuApp: lo que ves es lo que la cámara muestra en tiempo real. Si te muestran algo, coméntalo brevemente con tu chacota criolla, sin describir todo detallado a menos que te lo pidan.

Cuando el usuario pida abrir o buscar algo, dispara la herramienta correspondiente de inmediato y sin excusas: primero el toolCall, después el verso de voz. Aunque te burles de su flojera, SIEMPRE ejecutas el pedido. Cuando el usuario quiera volver a conversar contigo después de abrir algo, usa close_app sin dudarlo. Habla siempre como Miku: criolla, picante y entusiasta.
""".trimIndent()

class GeminiLiveClient(
    private val apiKey: String,
    private val modelName: String = DEFAULT_GEMINI_MODEL,
    private val voiceName: String = DEFAULT_VOICE_NAME,
    private val systemInstruction: String = MIKU_SYSTEM_INSTRUCTION,
    private val listener: Listener,
) {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    data class FunctionCall(
        val id: String,
        val name: String,
        val args: JSONObject,
    )

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onError(error: Throwable)
        fun onTextChunk(text: String)
        fun onInputTranscription(text: String)
        fun onAudioChunk(pcm16: ByteArray, sampleRate: Int)
        fun onTurnComplete()
        fun onInterrupted()
        fun onFunctionCall(call: FunctionCall)
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var audioChunksSent = 0L
    private var videoFramesSent = 0L

    val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    fun connect() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING
        MikuLog.d(TAG, "Conectando con $modelName (clave ...${apiKey.takeLast(4)})")
        val request = Request.Builder().url(endpoint()).build()
        webSocket = CLIENT.newWebSocket(request, socketListener)
    }

    fun disconnect() {
        val socket = webSocket
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        MikuLog.d(TAG, "Desconexión solicitada por el usuario")
        runCatching { socket?.close(NORMAL_CLOSURE_CODE, "client disconnect") }
        listener.onDisconnected("Desconectado por el usuario")
    }

    fun sendAudioChunk(pcm16: ByteArray) {
        if (!isConnected || pcm16.isEmpty()) return
        audioChunksSent++
        if (audioChunksSent == 1L || audioChunksSent % 50L == 0L) {
            MikuLog.d(TAG, "Chunks de audio enviados: $audioChunksSent")
        }
        val payload = try {
            JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject().put(
                        "audio",
                        JSONObject()
                            .put("mimeType", AUDIO_MIME_TYPE)
                            .put("data", Base64.encodeToString(pcm16, Base64.NO_WRAP)),
                    ),
                )
        } catch (t: Throwable) {
            listener.onError(t)
            return
        }
        webSocket?.send(payload.toString())
    }

    fun sendTextMessage(text: String) {
        if (!isConnected) {
            MikuLog.d(TAG, "sendTextMessage ignorado: no hay conexión")
            return
        }
        val message = try {
            JSONObject().put(
                "clientContent",
                JSONObject()
                    .put(
                        "turns",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", text))),
                        ),
                    )
                    .put("turnComplete", true),
            )
        } catch (t: Throwable) {
            listener.onError(t)
            return
        }
        MikuLog.d(TAG, "Mensaje enviado a Gemini: $text")
        webSocket?.send(message.toString())
    }

    fun sendVideoFrame(jpeg: ByteArray) {
        if (!isConnected || jpeg.isEmpty()) return
        videoFramesSent++
        if (videoFramesSent == 1L || videoFramesSent % 25L == 0L) {
            MikuLog.d(TAG, "Frames de video enviados: $videoFramesSent")
        }
        val payload = try {
            JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject().put(
                        "video",
                        JSONObject()
                            .put("mimeType", VIDEO_MIME_TYPE)
                            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                    ),
                )
        } catch (t: Throwable) {
            listener.onError(t)
            return
        }
        webSocket?.send(payload.toString())
    }

    fun sendFunctionResponse(id: String, name: String, response: JSONObject) {
        val message = try {
            JSONObject().put(
                "toolResponse",
                JSONObject().put(
                    "functionResponses",
                    JSONArray().put(
                        JSONObject()
                            .put("id", id)
                            .put("name", name)
                            .put("response", response),
                    ),
                ),
            )
        } catch (t: Throwable) {
            listener.onError(t)
            return
        }
        MikuLog.d(TAG, "toolResponse enviado: $name (id=$id) -> $response")
        webSocket?.send(message.toString())
    }

    private fun endpoint(): String = "$ENDPOINT?key=$apiKey"

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            MikuLog.d(TAG, "WebSocket abierto (HTTP ${response.code})")
            val setup = try {
                buildSetupMessage().toString()
            } catch (t: Throwable) {
                listener.onError(t)
                return
            }
            MikuLog.d(TAG, "Enviando setup (${setup.length} bytes)")
            webSocket.send(setup)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleMessage(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            MikuLog.d(TAG, "Servidor cerrando: $code ${reason.orEmpty()}")
            webSocket.close(NORMAL_CLOSURE_CODE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            MikuLog.d(TAG, "WebSocket cerrado: $code ${reason.orEmpty()}")
            _connectionState.value = ConnectionState.DISCONNECTED
            listener.onDisconnected(reason.ifBlank { null })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            MikuLog.d(
                TAG,
                "Fallo WebSocket: ${t::class.java.simpleName}: ${t.message} (HTTP ${response?.code})",
            )
            response?.let { resp ->
                runCatching {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) MikuLog.d(TAG, "Cuerpo de respuesta: ${body.take(500)}")
                }
            }
            _connectionState.value = ConnectionState.DISCONNECTED
            listener.onError(t)
            listener.onDisconnected(t.message)
        }
    }

    private fun buildSetupMessage(): JSONObject {
        val functionDeclarations = JSONArray()
            .put(
                functionDeclaration(
                    name = FUNCTION_OPEN_URL,
                    description = "Abre una URL concreta en el navegador del dispositivo.",
                    propertyName = "url",
                    propertyDescription = "URL completa que se abrirá, por ejemplo https://www.youtube.com",
                ),
            )
            .put(
                functionDeclaration(
                    name = FUNCTION_OPEN_APP,
                    description = "Abre una aplicación instalada en el teléfono por su nombre, por ejemplo YouTube, WhatsApp, Spotify o Chrome. Si no está instalada se abre su versión web.",
                    propertyName = "app_name",
                    propertyDescription = "Nombre de la aplicación que se quiere abrir, por ejemplo YouTube",
                ),
            )
            .put(
                functionDeclaration(
                    name = FUNCTION_SEARCH_WEB,
                    description = "Realiza una búsqueda en Google con el texto indicado.",
                    propertyName = "query",
                    propertyDescription = "Términos de búsqueda, por ejemplo recetas de sushi",
                ),
            )
            .put(
                JSONObject()
                    .put("name", FUNCTION_CLOSE_APP)
                    .put(
                        "description",
                        "Cierra la aplicación o página que está en primer plano y regresa inmediatamente" +
                            " a MikuApp para seguir conversando. Úsala cuando el usuario diga cerrar la app," +
                            " regresar, volver a Miku o que ya no necesita la aplicación abierta.",
                    ),
            )

        return JSONObject().put(
            "setup",
            JSONObject()
                .put("model", modelName)
                .put("generationConfig", buildGenerationConfig())
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", effectiveInstruction())),
                    ),
                )
.put(
                    "tools",
                    JSONArray().put(JSONObject().put("functionDeclarations", functionDeclarations)),
                )
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "contextWindowCompression",
                    JSONObject()
                        .put("triggerTokens", CONTEXT_TRIGGER_TOKENS)
                        .put(
                            "slidingWindow",
                            JSONObject().put("targetTokens", CONTEXT_TARGET_TOKENS),
                        ),
                ),
        )
    }

    private fun effectiveInstruction(): String =
        AUDIO_MODE_PREFIX + "\n\n" + systemInstruction

    private fun buildGenerationConfig(): JSONObject = JSONObject()
        .put("responseModalities", JSONArray().put("AUDIO"))
        .put(
            "speechConfig",
            JSONObject().put(
                "voiceConfig",
                JSONObject().put(
                    "prebuiltVoiceConfig",
                    JSONObject().put("voiceName", voiceName),
                ),
            ),
        )

    private fun functionDeclaration(
        name: String,
        description: String,
        propertyName: String,
        propertyDescription: String,
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "parameters",
            JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        propertyName,
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", propertyDescription),
                    ),
                )
                .put("required", JSONArray().put(propertyName)),
        )

    private fun handleMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            MikuLog.d(TAG, "Mensaje no JSON (${text.length} chars): ${t.message}")
            listener.onError(t)
            return
        }

        json.optJSONObject("error")?.let { error ->
            val message = error.optString("message", error.toString())
            MikuLog.d(TAG, "Error del servidor: $message")
            listener.onError(IllegalStateException("Gemini: $message"))
            return
        }

        if (json.has("setupComplete")) {
            MikuLog.d(TAG, "setupComplete: sesión Live establecida")
            _connectionState.value = ConnectionState.CONNECTED
            listener.onConnected()
            return
        }

        json.optJSONObject("serverContent")?.let { serverContent ->
            if (serverContent.optBoolean("interrupted", false)) {
                MikuLog.d(TAG, "Interrupción del servidor (barge-in remoto)")
                listener.onInterrupted()
                return@let
            }

            serverContent.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { transcription ->
                    MikuLog.d(TAG, "Tu voz: $transcription")
                    listener.onInputTranscription(transcription)
                }

            serverContent.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { transcription ->
                    MikuLog.d(TAG, "Transcripción: $transcription")
                    listener.onTextChunk(transcription)
                }

            serverContent.optJSONObject("modelTurn")
                ?.optJSONArray("parts")
                ?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue

                        part.optJSONObject("inlineData")?.let { inlineData ->
                            val mimeType = inlineData.optString("mimeType")
                            val data = inlineData.optString("data")
                            if (mimeType.startsWith(AUDIO_MIME_PREFIX) && data.isNotEmpty()) {
                                val sampleRate = mimeType.substringAfter("rate=", "")
                                    .toIntOrNull()
                                    ?: DEFAULT_OUTPUT_SAMPLE_RATE
                                val pcm = try {
                                    Base64.decode(data, Base64.NO_WRAP)
                                } catch (t: Throwable) {
                                    MikuLog.d(TAG, "No se pudo decodificar el audio: ${t.message}")
                                    null
                                }
                                if (pcm != null) {
                                    MikuLog.d(TAG, "Audio recibido: ${pcm.size} bytes a $sampleRate Hz")
                                    listener.onAudioChunk(pcm, sampleRate)
                                }
                            } else {
                                MikuLog.d(TAG, "inlineData ignorada: mimeType=$mimeType")
                            }
                        }

                        val chunk = part.optString("text").orEmpty()
                        if (chunk.isNotEmpty()) {
                            MikuLog.d(TAG, "Texto: $chunk")
                            listener.onTextChunk(chunk)
                        }
                    }
                }

            if (serverContent.optBoolean("turnComplete", false)) {
                MikuLog.d(TAG, "turnComplete")
                listener.onTurnComplete()
            }
        }

        json.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                MikuLog.d(TAG, "toolCall: $name $args")
                listener.onFunctionCall(
                    FunctionCall(
                        id = call.optString("id"),
                        name = name,
                        args = args,
                    ),
                )
            }
        }
    }

    companion object {
        const val FUNCTION_OPEN_URL = "open_url"
        const val FUNCTION_OPEN_APP = "open_app"
        const val FUNCTION_SEARCH_WEB = "search_web"
        const val FUNCTION_CLOSE_APP = "close_app"

        private const val TAG = "Gemini"

        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val AUDIO_MIME_TYPE = "audio/pcm;rate=16000"
        private const val VIDEO_MIME_TYPE = "image/jpeg"
        private const val AUDIO_MIME_PREFIX = "audio/pcm"
        private const val DEFAULT_OUTPUT_SAMPLE_RATE = 24000
        private const val DEFAULT_VOICE_NAME = "Kore"
        private const val AUDIO_MODE_PREFIX =
            "Estás en una llamada en tiempo real: lo que dices se escucha directamente, ya sea con tu propia voz de audio o repetido por la voz local de Miku. Habla con frases hablables, cortas y naturales, como si conversaras por micrófono."
        private const val NORMAL_CLOSURE_CODE = 1000
        private const val CONTEXT_TRIGGER_TOKENS = "104857"
        private const val CONTEXT_TARGET_TOKENS = "52428"

        private val CLIENT: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
