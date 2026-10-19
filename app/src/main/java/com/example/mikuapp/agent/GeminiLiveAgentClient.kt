package com.example.mikuapp.agent

import com.example.mikuapp.util.MikuLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sesión Live dedicada al agente ARTEMIS.
 *
 * Usa exclusivamente Gemini 3.8 Live (`bidiGenerateContent`). Este modelo es de
 * audio nativo y rechaza `responseModalities=TEXT`, así que la sesión pide
 * AUDIO y el cliente descarta los fragmentos de audio: solo consume toolCall,
 * texto/transcripciones y el evento de fin de turno.
 *
 * La sesión de voz de Miku (GeminiLiveClient) es independiente y sigue usando
 * el mismo modelo Live con AUDIO.
 */
class GeminiLiveAgentClient(private val apiKey: String) : AgentBrain {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var closed = false

    private val connected = CompletableDeferred<Unit>()
    private val decisions = Channel<AgentDecision>(Channel.UNLIMITED)

    private val turnLock = Any()
    private val textBuffer = StringBuilder()
    private var toolCallInTurn = false

    override suspend fun awaitConnect(timeoutMs: Long) {
        if (apiKey.isBlank() || apiKey == "your_api_key_here") {
            throw IOException("GEMINI_API_KEY no configurada")
        }
        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .build()
        webSocket = http.newWebSocket(request, socketListener)
        try {
            withTimeout(timeoutMs) { connected.await() }
        } catch (timeout: TimeoutCancellationException) {
            close()
            throw IOException("Tiempo de espera agotado conectando el agente Live.", timeout)
        } catch (t: Throwable) {
            close()
            throw t
        }
        MikuLog.d(TAG, "Agente Live conectado ($MODEL)")
    }

    override suspend fun awaitDecision(timeoutMs: Long): AgentDecision {
        return try {
            withTimeout(timeoutMs) { decisions.receive() }
        } catch (timeout: TimeoutCancellationException) {
            throw IOException("El agente Live no respondió a tiempo.", timeout)
        }
    }

    override fun sendObservation(text: String, screenshotBase64: String?) {
        val socket = webSocket ?: throw IOException("La sesión del agente Live no está conectada.")
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (!screenshotBase64.isNullOrBlank()) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", screenshotBase64),
                ),
            )
        }

        synchronized(turnLock) {
            toolCallInTurn = false
            textBuffer.setLength(0)
        }

        val message = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", parts),
                    ),
                )
                .put("turnComplete", true),
        )

        if (!socket.send(message.toString())) {
            throw IOException("No pude enviar la observación al agente Live.")
        }
    }

    override fun sendToolResponses(results: List<Pair<AgentToolCall, ActionResult>>) {
        if (results.isEmpty()) return
        val socket = webSocket ?: return

        val responses = JSONArray()
        results.forEach { (call, result) ->
            val response = JSONObject()
                .put("name", call.name)
                .put("response", result.toResponse())
            if (call.id.isNotBlank()) response.put("id", call.id)
            responses.put(response)
        }

        val message = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", responses),
        )
        socket.send(message.toString())
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { webSocket?.close(NORMAL_CLOSURE_CODE, "agent finished") }
        webSocket = null
        if (!connected.isCompleted) {
            connected.completeExceptionally(IOException("Agente Live cerrado"))
        }
        decisions.close()
    }

    private fun fail(error: Throwable) {
        MikuLog.d(TAG, "Error del agente Live: ${error.message}")
        if (closed) return
        closed = true
        if (!connected.isCompleted) connected.completeExceptionally(error)
        decisions.close(error)
        runCatching { webSocket?.cancel() }
        webSocket = null
    }

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            MikuLog.d(TAG, "WebSocket del agente abierto (HTTP ${response.code})")
            val setup = try {
                buildSetupMessage().toString()
            } catch (t: Throwable) {
                fail(t)
                return
            }
            if (!webSocket.send(setup)) {
                fail(IOException("No pude enviar el setup del agente Live."))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleMessage(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE_CODE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) {
                fail(IOException("Agente Live cerrado por el servidor: $code ${reason.orEmpty()}"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(t)
        }
    }

    private fun buildSetupMessage(): JSONObject = JSONObject().put(
        "setup",
        JSONObject()
            .put("model", MODEL)
            .put(
                "generationConfig",
                // Gemini 3.8 Live es audio nativo: TEXT no está soportado.
                // Pedimos AUDIO y descartamos los fragmentos de audio.
                JSONObject().put("responseModalities", JSONArray().put("AUDIO")),
            )
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", AgentToolSchema.SYSTEM_PROMPT)),
                ),
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject().put(
                        "functionDeclarations",
                        AgentToolSchema.declarations(),
                    ),
                ),
            )
            .put("outputAudioTranscription", JSONObject()),
    )

    private fun handleMessage(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return

        json.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { error.toString() }
            fail(IOException("Gemini Live: $message"))
            return
        }

        if (json.has("setupComplete")) {
            MikuLog.d(TAG, "Setup del agente Live completado")
            if (!connected.isCompleted) connected.complete(Unit)
            return
        }

        json.optJSONObject("toolCall")
            ?.optJSONArray("functionCalls")
            ?.let { callsJson ->
                val calls = mutableListOf<AgentToolCall>()
                for (index in 0 until callsJson.length()) {
                    val call = callsJson.optJSONObject(index) ?: continue
                    val name = call.optString("name")
                    if (name.isBlank()) continue
                    calls += AgentToolCall(
                        id = call.optString("id"),
                        name = name,
                        args = call.optJSONObject("args") ?: JSONObject(),
                    )
                }
                if (calls.isNotEmpty()) {
                    synchronized(turnLock) { toolCallInTurn = true }
                    MikuLog.d(TAG, "toolCall del agente: ${calls.map { it.name }}")
                    decisions.trySend(AgentDecision(text = null, calls = calls))
                }
                return
            }

        json.optJSONObject("serverContent")?.let { serverContent ->
            serverContent.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { chunk ->
                    synchronized(turnLock) { textBuffer.append(chunk) }
                }

            serverContent.optJSONObject("modelTurn")
                ?.optJSONArray("parts")
                ?.let { parts ->
                    for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        val chunk = part.optString("text")
                        if (chunk.isNotBlank()) {
                            synchronized(turnLock) { textBuffer.append(chunk) }
                        }
                        // inlineData de audio: se ignora deliberadamente.
                    }
                }

            if (serverContent.optBoolean("turnComplete", false)) {
                val text = synchronized(turnLock) {
                    val value = if (!toolCallInTurn) textBuffer.toString().trim() else ""
                    textBuffer.setLength(0)
                    toolCallInTurn = false
                    value
                }
                if (text.isNotBlank()) {
                    MikuLog.d(TAG, "Turno de texto del agente: ${text.take(160)}")
                    decisions.trySend(AgentDecision(text = text, calls = emptyList()))
                }
            }
        }
    }

    companion object {
        const val MODEL = "models/gemini-3.8-live"

        private const val TAG = "ArtemisLive"
        private const val NORMAL_CLOSURE_CODE = 1000
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
