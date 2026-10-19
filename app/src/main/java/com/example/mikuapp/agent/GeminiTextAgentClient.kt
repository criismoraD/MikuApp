package com.example.mikuapp.agent

import com.example.mikuapp.util.MikuLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cerebro ARTEMIS basado en Gemini 3.5 Flash Lite (generateContent).
 *
 * Por qué texto y no Live: el modelo Live genera audio aunque no lo usemos, y
 * cada decisión tardaba decenas de segundos. Flash Lite con function calling
 * forzado (`toolConfig.mode=ANY`) responde una sola acción por paso en pocos
 * segundos y sin audio.
 *
 * Es un cliente sin estado: cada paso se envía como una conversación nueva que
 * incluye la observación completa (pantalla + historial reciente). Así el
 * contexto no crece, no hay que emparejar functionCall/functionResponse y cada
 * petición es rápida.
 *
 * La voz de Miku sigue viviendo en su propia sesión Live independiente.
 */
class GeminiTextAgentClient(
    private val apiKey: String,
    private val model: String = MODEL,
) : AgentBrain {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var closed = false

    @Volatile
    private var pendingObservation: String? = null

    @Volatile
    private var pendingScreenshot: String? = null

    override suspend fun awaitConnect(timeoutMs: Long) {
        if (apiKey.isBlank() || apiKey == "your_api_key_here") {
            throw IOException("GEMINI_API_KEY no configurada")
        }
        MikuLog.d(TAG, "Agente de texto listo ($model)")
    }

    override suspend fun awaitDecision(timeoutMs: Long): AgentDecision = withContext(Dispatchers.IO) {
        if (closed) throw IOException("El agente de texto está cerrado.")

        val observation = pendingObservation
            ?: throw IOException("No hay observación pendiente para el agente de texto.")
        pendingObservation = null
        val screenshot = pendingScreenshot
        pendingScreenshot = null

        val startedAt = System.currentTimeMillis()
        val call = http.newCall(buildRequest(observation, screenshot))
        val response = try {
            call.execute()
        } catch (t: Throwable) {
            throw IOException("No pude consultar a Gemini 3.5 Flash Lite: ${t.message}", t)
        }

        response.use { res ->
            val body = res.body?.string().orEmpty()
            val elapsed = System.currentTimeMillis() - startedAt
            if (!res.isSuccessful) {
                throw IOException("Gemini 3.5 Flash Lite respondió HTTP ${res.code}: ${body.take(220)}")
            }
            val decision = parseDecision(body)
            val summary = decision.calls.joinToString(",") { it.name }.ifBlank { "texto" }
            MikuLog.d(TAG, "Decisión en ${elapsed} ms → $summary")
            return@withContext decision
        }
    }

    override fun sendObservation(text: String, screenshotBase64: String?) {
        pendingObservation = text
        pendingScreenshot = screenshotBase64
    }

    override fun sendToolResponses(results: List<Pair<AgentToolCall, ActionResult>>) {
        // Sin estado: el bucle ya resume el resultado en el historial de la
        // siguiente observación, así que aquí no hay nada que enviar.
    }

    override fun close() {
        closed = true
        pendingObservation = null
        pendingScreenshot = null
    }

    private fun buildRequest(observation: String, screenshotBase64: String?): Request {
        val parts = JSONArray().put(JSONObject().put("text", observation))
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

        val payload = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", AgentToolSchema.SYSTEM_PROMPT)),
                ),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts),
                ),
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject().put("functionDeclarations", AgentToolSchema.declarations()),
                ),
            )
            // Estructurado: obligamos a que cada paso termine en una tool call.
            .put(
                "toolConfig",
                JSONObject().put(
                    "functionCallingConfig",
                    JSONObject().put("mode", "ANY"),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.15)
                    .put("maxOutputTokens", 1_024)
                    .put("candidateCount", 1),
            )

        return Request.Builder()
            .url("$ENDPOINT_BASE/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun parseDecision(body: String): AgentDecision {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            throw IOException("Respuesta ilegible de Gemini 3.5 Flash Lite: ${body.take(180)}", t)
        }

        json.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { error.toString() }
            throw IOException("Gemini 3.5 Flash Lite: $message")
        }

        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return AgentDecision(text = null, calls = emptyList())

        val calls = mutableListOf<AgentToolCall>()
        val textBuffer = StringBuilder()

        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue

            part.optJSONObject("functionCall")?.let { functionCall ->
                val name = functionCall.optString("name")
                if (name.isNotBlank()) {
                    calls += AgentToolCall(
                        id = functionCall.optString("id"),
                        name = name,
                        args = functionCall.optJSONObject("args") ?: JSONObject(),
                    )
                }
            }

            val text = part.optString("text")
            if (text.isNotBlank()) textBuffer.append(text)
        }

        return AgentDecision(
            text = textBuffer.toString().trim().ifBlank { null },
            calls = calls,
        )
    }

    companion object {
        /**
         * Modelo de texto del agente. Flash Lite prioriza latencia: la decisión
         * de UI es simple y el árbol ya viene indexado.
         */
        const val MODEL = "models/gemini-3.5-flash-lite"

        private const val TAG = "ArtemisText"
        private const val ENDPOINT_BASE =
            "https://generativelanguage.googleapis.com/v1beta"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
