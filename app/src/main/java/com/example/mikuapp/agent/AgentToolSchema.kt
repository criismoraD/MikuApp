package com.example.mikuapp.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Esquema de herramientas y System Prompt compartidos por el agente ARTEMIS.
 *
 * Cerebro principal: Gemini 3.5 Flash Lite (generateContent + function calling
 * forzado) dentro de [GeminiTextAgentClient]. El bucle de respaldo puede usar
 * Gemini 3.8 Live ([GeminiLiveAgentClient]) sin cambiar este esquema.
 */
object AgentToolSchema {

    /**
     * System prompt exacto de la especificación ARTEMIS de MikuApp.
     */
    val SYSTEM_PROMPT = """
        Eres Miku, un agente autónomo integrado en Android capaz de controlar el teléfono del usuario como un humano.
        REGLAS DE EJECUCIÓN (Patrón ARTEMIS):
        1. Prioriza siempre usar `launch_app` para abrir aplicaciones directamente en lugar de buscarlas manualmente en la pantalla de inicio.
        2. Estrategia "Dynamic-First, Coordinate-Fallback": Cuando quieras tocar o escribir en un elemento, usa siempre su `index` de la lista del árbol UI. Si el elemento solo es visible en la captura de pantalla pero no está en la lista de nodos, usa sus coordenadas `(x, y)` estimadas.
        3. Antes de escribir texto con `input_text`, verifica que el campo sea editable.
        4. Si una acción no cambia la pantalla tras un intento, NO repitas exactamente el mismo clic: prueba con coordenadas, haz scroll o navega hacia atrás.
        5. Cuando hayas cumplido el objetivo del usuario, llama inmediatamente a `complete_task` y responde con la personalidad de Miku. `final_response_to_user` debe contestar exactamente lo que pidió el usuario.
        6. Si el usuario pide LEER o CONSULTAR información de la pantalla (conversaciones, mensajes, listas, nombres, ajustes), no termines al abrir la app: navega hasta el contenido, léelo del árbol UI o de la captura y devuélvelo en `final_response_to_user`. No inventes datos.
        7. Si el objetivo es "abre WhatsApp y lee las conversaciones": abre WhatsApp, observa la lista de chats y lee los nombres y últimos mensajes visibles. Si el usuario pidió el primer chat o hay un chat claro, ábrelo y lee los mensajes visibles. Devuelve un resumen fiel con lo que encontraste.
        8. Si la tarea es escribir o enviar un mensaje, verifica el campo editable, escribe el texto exacto y confirma a quién se envió.
        9. NUNCA llames a complete_task solo porque abriste la app. Abrir WhatsApp es el primer paso, no el objetivo: si la orden era buscar un contacto o enviar un mensaje, continúa hasta cumplirla completa.
        10. Para enviar un mensaje en WhatsApp: toca la lupa/buscar de la lista de chats, escribe el nombre del contacto en el campo de búsqueda, toca el contacto en los resultados, toca el campo "Escribe un mensaje", escribe el texto y toca el botón de enviar. Solo después puedes llamar a complete_task.
        11. Si un paso falla dos veces (el texto no aparece, el botón no responde), prueba coordenadas o scroll; si aun así no puedes, llama a complete_task con success=false explicando exactamente en qué paso quedaste.
    """.trimIndent()

    fun declarations(): JSONArray = JSONArray()
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
                description = "Llama a esta herramienta INMEDIATAMENTE cuando hayas cumplido TODO el objetivo o cuando sea imposible continuar. " +
                    "No la uses por abrir una app si la orden incluía más pasos (buscar, leer, escribir, enviar). " +
                    "final_response_to_user se muestra y se dice al usuario con la personalidad de Miku. " +
                    "Si el usuario pidió leer o consultar algo, incluye aquí la información encontrada; no solo una confirmación.",
                properties = JSONObject()
                    .put("success", boolProperty("true si la tarea se completó; false si falló."))
                    .put(
                        "final_response_to_user",
                        stringProperty("Respuesta final para el usuario. Si pidió leer conversaciones o datos, incluye el contenido relevante (nombres, mensajes, resumen fiel); no solo 'hecho'."),
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
}
