package com.example.mikuapp.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Nodo interactivo del árbol de accesibilidad, ya indexado para el modelo.
 *
 * El índice es la referencia estable que viaja a Gemini. Las coordenadas se
 * usan únicamente como fallback cuando el nodo no acepta una acción directa.
 */
data class AgentNode(
    val index: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val centerX: Int,
    val centerY: Int,
    val bounds: String,
    val packageName: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("className", className.substringAfterLast('.'))
        .put("text", text)
        .put("contentDescription", contentDescription)
        .put("viewId", viewId)
        .put("clickable", clickable)
        .put("editable", editable)
        .put("scrollable", scrollable)
        .put("centerX", centerX)
        .put("centerY", centerY)
        .put("bounds", bounds)
}

/**
 * Fotografía del estado de la pantalla en un instante del bucle.
 */
data class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val focusedEditable: Boolean,
    val nodes: List<AgentNode>,
) {
    fun nodesJson(): String {
        val array = JSONArray()
        nodes.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    /**
     * Huella barata para detectar si la pantalla cambió entre dos pasos.
     */
    fun fingerprint(): Int {
        val seed = buildString {
            append(packageName)
            append('|')
            append(focusedEditable)
            nodes.forEach { node ->
                append('|')
                append(node.viewId)
                append(':')
                append(node.text.take(24))
                append(':')
                append(node.contentDescription.take(24))
                append(':')
                append(node.centerX)
                append(',')
                append(node.centerY)
            }
        }
        return seed.hashCode()
    }
}

data class ActionResult(
    val ok: Boolean,
    val detail: String,
) {
    fun toResponse(): JSONObject = JSONObject()
        .put("result", if (ok) "ok" else "error")
        .put("detail", detail)
}

/**
 * Estado observable del agente para la UI y el puente Web.
 */
data class AgentState(
    val running: Boolean = false,
    val goal: String = "",
    val step: Int = 0,
    val maxSteps: Int = 15,
    val lastAction: String = "",
    val lastResult: String = "",
    val finalMessage: String? = null,
    val success: Boolean? = null,
)
