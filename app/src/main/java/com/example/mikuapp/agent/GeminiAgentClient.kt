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
 * Cliente REST de Gemini para el control del teléfono.
 *
 * A diferencia de GeminiLiveClient (voz en tiempo real), este cliente usa
 * generateContent con function calling. El bucle conserva la conversación
 * completa: observación → tool call → tool response → nueva observación.
 */
class GeminiAgentClient(
    private val apiKey: String,
    private val modelName: String = DEFAULT_MODEL,
) {

    data class ToolCall(
        val name: String,
        val args: JSONObject,
    )

    data class Turn(
        val text: String?,
        val calls: List<ToolCall>,
    )

    private val contents = mutableListOf<JSONObject>()

    fun addUserText(text: String) {
        contents += content(
            role = "user",
            parts = JSONArray().put(JSONObject().put("text", text)),
        )
    }

    fun addUserObservation(text: String, screenshotBase64: String?) {
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
        contents += content(role = "user", parts = parts)
    }

    fun addToolResults(results: List<Pair<String, JSONObject>>) {
        if (results.isEmpty()) return
        val parts = JSONArray()
        results.forEach { (name, response) ->
            parts.put(
                JSONObject().put(
                    "functionResponse",
                    JSONObject()
                        .put("name", name)
                        .put("response", response),
                ),
            )
        }
        contents += content(role = "user", parts = parts)
    }

    /**
     * Envía el estado actual al modelo y devuelve su siguiente decisión.
     *
     * Si el modelo principal devuelve 503/429 (alta demanda), reintenta una vez
     * con [FALLBACK_MODEL]. El historial es independiente del modelo, así que
     * el cambio es transparente para el bucle.
     */
    suspend fun generate(): Turn = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "your_api_key_here") {
            throw IOException("GEMINI_API_KEY no configurada")
        }

        val models = listOf(modelName, FALLBACK_MODEL).distinct()
        var lastError: IOException? = null

        for ((index, model) in models.withIndex()) {
            try {
                val json = requestGenerate(model)
                return@withContext parseTurn(json)
            } catch (error: IOException) {
                lastError = error
                val hasFallback = index < models.lastIndex
                if (!hasFallback || !isRetryable(error)) throw error
                MikuLog.d(
                    TAG,
                    "Modelo $model no disponible (${error.message}). " +
                        "Probando $FALLBACK_MODEL...",
                )
            }
        }

        throw lastError ?: IOException("Gemini no devolvió respuesta")
    }

    private fun requestGenerate(model: String): JSONObject {
        val contentsArray = JSONArray()
        contents.forEach { contentsArray.put(it) }

        val payload = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)),
                ),
            )
            .put("contents", contentsArray)
            .put(
                "tools",
                JSONArray().put(
                    JSONObject().put("functionDeclarations", toolDeclarations()),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.15)
                    .put("topP", 0.9)
                    .put("maxOutputTokens", 2048),
            )

        val request = Request.Builder()
            .url("$BASE_URL/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { httpResponse ->
            val body = httpResponse.body?.string().orEmpty()
            if (!httpResponse.isSuccessful) {
                throw IOException(
                    "Gemini HTTP ${httpResponse.code}: ${body.take(500)}",
                )
            }
            return runCatching { JSONObject(body) }.getOrElse {
                throw IOException("Respuesta de Gemini no es JSON: ${body.take(300)}")
            }
        }
    }

    private fun parseTurn(json: JSONObject): Turn {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IOException(
                "Gemini no devolvió candidatos: ${json.toString().take(400)}",
            )
        val finishReason = candidate.optString("finishReason")
        val modelContent = candidate.optJSONObject("content")
            ?: throw IOException(
                "Gemini devolvió contenido vacío (finishReason=$finishReason).",
            )

        // Guardamos la respuesta exacta del modelo (incluidos los
        // functionCall) para que el siguiente turno sea coherente.
        contents += JSONObject(modelContent.toString()).put("role", "model")

        val parts = modelContent.optJSONArray("parts") ?: JSONArray()
        val textBuilder = StringBuilder()
        val calls = mutableListOf<ToolCall>()

        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (part.optBoolean("thought", false)) continue

            part.optJSONObject("functionCall")?.let { functionCall ->
                val name = functionCall.optString("name")
                if (name.isNotBlank()) {
                    calls += ToolCall(
                        name = name,
                        args = functionCall.optJSONObject("args") ?: JSONObject(),
                    )
                }
            }

            part.optString("text")
                .takeIf { it.isNotBlank() }
                ?.let { textBuilder.append(it) }
        }

        MikuLog.d(
            TAG,
            "Decisión: calls=${calls.map { it.name }} texto=${textBuilder.length} chars",
        )

        return Turn(
            text = textBuilder.toString().trim().ifBlank { null },
            calls = calls,
        )
    }

    private fun isRetryable(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("HTTP 503") ||
            message.contains("HTTP 429") ||
            message.contains("HTTP 500") ||
            message.contains("UNAVAILABLE") ||
            message.contains("high demand")
    }

    /**
     * Conserva solo las últimas capturas en el historial para no inflar los
     * tokens ni el tamaño del request. El texto de cada observación se mantiene.
     */
    fun pruneScreenshots(keepLast: Int = 3) {
        var seen = 0
        for (index in contents.indices.reversed()) {
            val entry = contents[index]
            val parts = entry.optJSONArray("parts") ?: continue
            val kept = JSONArray()
            for (partIndex in 0 until parts.length()) {
                val part = parts.optJSONObject(partIndex) ?: continue
                if (part.has("inlineData")) {
                    seen++
                    if (seen > keepLast) continue
                }
                kept.put(part)
            }
            entry.put("parts", kept)
        }
    }

    private fun content(role: String, parts: JSONArray): JSONObject = JSONObject()
        .put("role", role)
        .put("parts", parts)

    private fun toolDeclarations(): JSONArray = JSONArray()
        .put(
            declaration(
                name = "tap_element",
                description = "Toca un elemento de la pantalla. Usa index del árbol UI. " +
                    "Si el elemento no está indexado pero es visible en la captura, usa x e y estimadas. " +
                    "Siempre explica el motivo en reason.",
                properties = JSONObject()
                    .put("index", intProperty("Índice del nodo del árbol UI (1, 2, 3...). Opcional si usas x/y."))
                    .put("x", intProperty("Coordenada X en píxeles de la pantalla. Opcional."))
                    .put("y", intProperty("Coordenada Y en píxeles de la pantalla. Opcional."))
                    .put("reason", stringProperty("Motivo breve de la acción, en español.")),
                required = listOf("reason"),
            ),
        )
        .put(
            declaration(
                name = "input_text",
                description = "Escribe texto en un campo editable. Verifica en el árbol UI que el nodo tenga editable=true. " +
                    "Usa clear_first=true para reemplazar el contenido actual o false para añadir al final.",
                properties = JSONObject()
                    .put("index", intProperty("Índice del nodo editable del árbol UI."))
                    .put("text", stringProperty("Texto exacto que se escribirá."))
                    .put("clear_first", boolProperty("Si true, limpia el campo antes de escribir. Por defecto true."))
                    .put("reason", stringProperty("Motivo breve de la acción, en español.")),
                required = listOf("index", "text", "reason"),
            ),
        )
        .put(
            declaration(
                name = "swipe_screen",
                description = "Desliza la pantalla en una dirección. Úsalo para hacer scroll, cambiar de página o revelar contenido.",
                properties = JSONObject()
                    .put("direction", enumProperty("Dirección del gesto.", listOf("UP", "DOWN", "LEFT", "RIGHT")))
                    .put("reason", stringProperty("Motivo breve de la acción, en español.")),
                required = listOf("direction", "reason"),
            ),
        )
        .put(
            declaration(
                name = "press_system_button",
                description = "Presiona un botón global de Android: atrás, inicio, aplicaciones recientes o notificaciones.",
                properties = JSONObject()
                    .put(
                        "button",
                        enumProperty(
                            "Botón del sistema a presionar.",
                            listOf("BACK", "HOME", "RECENTS", "NOTIFICATIONS"),
                        ),
                    )
                    .put("reason", stringProperty("Motivo breve de la acción, en español.")),
                required = listOf("button", "reason"),
            ),
        )
        .put(
            declaration(
                name = "launch_app",
                description = "Abre una aplicación instalada por su nombre (por ejemplo WhatsApp, YouTube, Ajustes). " +
                    "Prefiere esta herramienta antes que buscar el icono manualmente.",
                properties = JSONObject()
                    .put("app_name", stringProperty("Nombre de la aplicación a abrir."))
                    .put("reason", stringProperty("Motivo breve de la acción, en español.")),
                required = listOf("app_name", "reason"),
            ),
        )
        .put(
            declaration(
                name = "wait_seconds",
                description = "Espera N segundos para que una app, una animación o una carga termine. Máximo recomendado: 10.",
                properties = JSONObject()
                    .put("seconds", intProperty("Segundos a esperar (1-15)."))
                    .put("reason", stringProperty("Motivo breve de la espera, en español.")),
                required = listOf("seconds", "reason"),
            ),
        )
        .put(
            declaration(
                name = "complete_task",
                description = "Llama a esta herramienta INMEDIATAMENTE cuando hayas cumplido el objetivo o cuando sea imposible continuar. " +
                    "final_response_to_user se muestra y se dice al usuario con la personalidad de Miku.",
                properties = JSONObject()
                    .put("success", boolProperty("true si la tarea se completó; false si falló."))
                    .put(
                        "final_response_to_user",
                        stringProperty("Respuesta final breve para el usuario, con el estilo de Miku."),
                    ),
                required = listOf("success", "final_response_to_user"),
            ),
        )

    private fun declaration(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject {
        val requiredArray = JSONArray()
        required.forEach { requiredArray.put(it) }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", properties)
                    .put("required", requiredArray),
            )
    }

    private fun stringProperty(description: String): JSONObject = JSONObject()
        .put("type", "STRING")
        .put("description", description)

    private fun intProperty(description: String): JSONObject = JSONObject()
        .put("type", "INTEGER")
        .put("description", description)

    private fun boolProperty(description: String): JSONObject = JSONObject()
        .put("type", "BOOLEAN")
        .put("description", description)

    private fun enumProperty(description: String, values: List<String>): JSONObject {
        val valuesArray = JSONArray()
        values.forEach { valuesArray.put(it) }
        return JSONObject()
            .put("type", "STRING")
            .put("description", description)
            .put("enum", valuesArray)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val FALLBACK_MODEL = "gemini-3-flash-preview"

        private const val TAG = "Artemis"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * System prompt exacto de la especificación ARTEMIS de MikuApp.
         */
        val SYSTEM_PROMPT = """
            Eres Miku, un agente autónomo integrado en Android capaz de controlar el teléfono del usuario como un humano.
            REGLAS DE EJECUCIÓN (Patrón ARTEMIS):
            1. Prioriza siempre usar `open_app / launch_app` para abrir aplicaciones directamente en lugar de buscarlas manualmente en la pantalla de inicio.
            2. Estrategia "Dynamic-First, Coordinate-Fallback": Cuando quieras tocar o escribir en un elemento, usa siempre su `index` de la lista del árbol UI. Si el elemento solo es visible en la captura de pantalla pero no está en la lista de nodos, usa sus coordenadas `(x, y)` estimadas.
            3. Antes de escribir texto con `input_text`, verifica que el campo sea editable.
            4. Si una acción no cambia la pantalla tras un intento, NO repitas exactamente el mismo clic: prueba con coordenadas, haz scroll o navega hacia atrás.
            5. Cuando hayas cumplido el objetivo del usuario, llama inmediatamente a `complete_task` y responde con la personalidad de Miku de forma breve y natural.
        """.trimIndent()
    }
}
