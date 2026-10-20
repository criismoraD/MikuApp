package com.example.mikuapp.agent

import org.json.JSONObject

/**
 * Llamada a herramienta emitida por el modelo que piensa el bucle ARTEMIS.
 *
 * Es común a los dos cerebros posibles:
 * - [GeminiTextAgentClient]: Gemini 3.5 Flash Lite vía generateContent (rápido).
 * - [GeminiLiveAgentClient]: Gemini 3.8 Live vía WebSocket (legado/fallback).
 */
data class AgentToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
)

/**
 * Decisión del modelo en un paso del bucle: o pide herramientas, o responde
 * con texto (lo que el bucle interpreta como posible final).
 */
data class AgentDecision(
    val text: String?,
    val calls: List<AgentToolCall>,
)

/**
 * Contrato del "cerebro" que decide el siguiente paso ARTEMIS.
 *
 * El bucle [ArtemisAgentLoop] solo conoce esta interfaz, de modo que se puede
 * cambiar entre Live y texto sin tocar la lógica de observación/acción.
 */
interface AgentBrain {

    /** Prepara la conexión. Debe lanzar IOException si no se puede usar. */
    suspend fun awaitConnect(timeoutMs: Long = 25_000)

    /** Espera la siguiente decisión (toolCall o texto). */
    suspend fun awaitDecision(timeoutMs: Long = 90_000): AgentDecision

    /** Envía la observación actual de la pantalla al modelo. */
    fun sendObservation(text: String, screenshotBase64: String?)

    /** Comunica el resultado de las herramientas ejecutadas. */
    fun sendToolResponses(results: List<Pair<AgentToolCall, ActionResult>>)

    /** Libera recursos de red. */
    fun close()
}
