package com.example.mikuapp.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mikuapp.BuildConfig
import com.example.mikuapp.MainActivity
import com.example.mikuapp.R
import com.example.mikuapp.util.MikuLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Bucle reactivo Observe → Think → Act de MikuApp.
 *
 * Vive fuera de la Activity para seguir funcionando mientras el agente abre
 * otras aplicaciones. El servicio de accesibilidad es el único ejecutor.
 */
object ArtemisAgentLoop {

    const val MAX_STEPS = 15

    private const val STABILIZE_MS = 1_500L
    private const val MAX_CALLS_PER_TURN = 4
    private const val MAX_HISTORY = 8
    private const val NOTIFICATION_ID = 4546
    private const val CHANNEL_ID = "miku_artemis"
    private const val API_KEY_PLACEHOLDER = "your_api_key_here"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AgentState(maxSteps = MAX_STEPS))
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var job: Job? = null

    @Synchronized
    fun start(
        goal: String,
        apiKey: String = BuildConfig.GEMINI_API_KEY,
        onFinished: ((Boolean, String) -> Unit)? = null,
    ): Boolean {
        if (job?.isActive == true) return false

        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank()) return false

        val service = MikuAccessibilityService.instance
        if (service == null) {
            val message =
                "Activa el Servicio de Accesibilidad de MikuApp en Ajustes para que pueda controlar el teléfono."
            _state.value = AgentState(
                maxSteps = MAX_STEPS,
                finalMessage = message,
                success = false,
            )
            runCatching { onFinished?.invoke(false, message) }
            return false
        }

        val effectiveKey = apiKey.ifBlank { BuildConfig.GEMINI_API_KEY }
        if (effectiveKey.isBlank() || effectiveKey == API_KEY_PLACEHOLDER) {
            val message = "Configura GEMINI_API_KEY en el archivo .env para usar el agente."
            _state.value = AgentState(
                maxSteps = MAX_STEPS,
                finalMessage = message,
                success = false,
            )
            runCatching { onFinished?.invoke(false, message) }
            return false
        }

        MikuLog.d(TAG, "Iniciando tarea ARTEMIS: $cleanGoal")
        _state.value = AgentState(
            running = true,
            goal = cleanGoal,
            maxSteps = MAX_STEPS,
            lastAction = "Observando la pantalla",
        )
        postNotification(service, cleanGoal)
        job = scope.launch {
            runLoop(service, cleanGoal, effectiveKey, onFinished)
        }
        return true
    }

    @Synchronized
    fun stop(reason: String = "Control detenido por el usuario") {
        val current = _state.value
        job?.cancel()
        job = null
        if (current.running) {
            MikuLog.d(TAG, "Tarea detenida: $reason")
            _state.value = current.copy(
                running = false,
                finalMessage = current.finalMessage ?: reason,
                success = current.success ?: false,
            )
        }
        cancelNotification()
    }

    fun clearResult() {
        if (_state.value.running) return
        _state.value = AgentState(maxSteps = MAX_STEPS)
    }

    private suspend fun runLoop(
        service: MikuAccessibilityService,
        goal: String,
        apiKey: String,
        onFinished: ((Boolean, String) -> Unit)?,
    ) {
        val client = GeminiAgentClient(apiKey)
        val executor = service.actionExecutor
        val history = ArrayDeque<String>()

        try {
            val initialSnapshot = service.snapshot()
            if (initialSnapshot == null) {
                finish(
                    success = false,
                    message = "No pude leer la pantalla. Verifica que el servicio de accesibilidad siga activo.",
                    onFinished = onFinished,
                )
                return
            }
            var snapshot: ScreenSnapshot = initialSnapshot

            var previousFingerprint: Int? = null
            var textOnlyTurns = 0

            for (step in 1..MAX_STEPS) {
                currentCoroutineContext().ensureActive()

                val screenshot = service.captureScreenshotBase64()
                val observation = buildObservation(
                    goal = goal,
                    step = step,
                    snapshot = snapshot,
                    history = history,
                    screenUnchanged = previousFingerprint == snapshot.fingerprint(),
                    hasScreenshot = screenshot != null,
                )
                client.addUserObservation(observation, screenshot)
                client.pruneScreenshots(keepLast = 3)

                val turn = client.generate()

                if (turn.calls.isEmpty()) {
                    textOnlyTurns++
                    val text = turn.text.orEmpty()
                    if (text.isNotBlank() && textOnlyTurns >= 2) {
                        finish(
                            success = true,
                            message = text,
                            onFinished = onFinished,
                        )
                        return
                    }
                    client.addUserText(
                        "No has llamado a ninguna herramienta. Usa una herramienta para avanzar " +
                            "o llama a complete_task cuando termines.",
                    )
                    continue
                }

                textOnlyTurns = 0

                val completion = turn.calls.firstOrNull { it.name == "complete_task" }
                if (completion != null) {
                    val success = completion.args.optBoolean("success", true)
                    val message = completion.args.optString("final_response_to_user")
                        .ifBlank {
                            if (success) "Tarea completada." else "No pude completar la tarea."
                        }
                    finish(success, message, onFinished)
                    return
                }

                previousFingerprint = snapshot.fingerprint()
                val results = mutableListOf<Pair<String, JSONObject>>()

                for (call in turn.calls.take(MAX_CALLS_PER_TURN)) {
                    currentCoroutineContext().ensureActive()

                    val reason = call.args.optString("reason").trim()
                    _state.update {
                        it.copy(
                            step = step,
                            lastAction = buildString {
                                append(call.name)
                                if (reason.isNotBlank()) append(" · ").append(reason.take(120))
                            },
                        )
                    }

                    val result = executeTool(executor, call)
                    results += call.name to result.toResponse()

                    history.addLast(
                        "$step. ${call.name}(${call.args}) → " +
                            "${if (result.ok) "ok" else "error"}: ${result.detail.take(160)}",
                    )
                    while (history.size > MAX_HISTORY) history.removeFirst()

                    _state.update { it.copy(lastResult = result.detail.take(200)) }
                    delay(STABILIZE_MS)
                }

                client.addToolResults(results)
                snapshot = service.snapshot() ?: snapshot
            }

            finish(
                success = false,
                message = "Me quedé sin pasos (límite de $MAX_STEPS). No pude completar la tarea.",
                onFinished = onFinished,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            MikuLog.d(TAG, "Error en el bucle: ${t.message}")
            finish(
                success = false,
                message = "No pude completar la tarea: ${t.message ?: t.javaClass.simpleName}",
                onFinished = onFinished,
            )
        } finally {
            cancelNotification()
        }
    }

    private suspend fun executeTool(
        executor: ActionExecutor,
        call: GeminiAgentClient.ToolCall,
    ): ActionResult {
        val args = call.args
        return when (call.name) {
            "tap_element" -> executor.click(
                index = args.intOrNull("index")?.takeIf { it > 0 },
                x = args.intOrNull("x")?.takeIf { it >= 0 },
                y = args.intOrNull("y")?.takeIf { it >= 0 },
            )

            "input_text" -> {
                val index = args.intOrNull("index")
                if (index == null || index <= 0) {
                    ActionResult(false, "input_text requiere un index válido del árbol UI.")
                } else {
                    executor.typeText(
                        index = index,
                        text = args.optString("text"),
                        clearFirst = args.optBoolean("clear_first", true),
                    )
                }
            }

            "swipe_screen" -> executor.swipe(args.optString("direction"))

            "press_system_button" -> executor.globalAction(args.optString("button"))

            "launch_app" -> executor.openApp(args.optString("app_name"))

            "wait_seconds" -> {
                val seconds = args.optInt("seconds", 2).coerceIn(1, 15)
                delay(seconds * 1_000L)
                ActionResult(true, "Esperé $seconds segundos.")
            }

            "complete_task" -> ActionResult(
                true,
                args.optString("final_response_to_user", "Tarea finalizada."),
            )

            else -> ActionResult(false, "Herramienta desconocida: ${call.name}")
        }
    }

    private fun buildObservation(
        goal: String,
        step: Int,
        snapshot: ScreenSnapshot,
        history: ArrayDeque<String>,
        screenUnchanged: Boolean,
        hasScreenshot: Boolean,
    ): String = buildString {
        appendLine("TAREA DEL USUARIO: $goal")
        appendLine("PASO: $step/$MAX_STEPS")
        appendLine("APP_EN_PRIMER_PLANO: ${snapshot.appLabel} (${snapshot.packageName})")
        appendLine(
            "PANTALLA: ${snapshot.screenWidth}x${snapshot.screenHeight} · " +
                "campo_editable_enfocado=${snapshot.focusedEditable}",
        )
        if (screenUnchanged) {
            appendLine(
                "ADVERTENCIA: la pantalla no cambió desde la última acción. " +
                    "No repitas exactamente el mismo clic: prueba coordenadas, scroll o atrás.",
            )
        }
        appendLine("HISTORIAL_RECIENTE:")
        if (history.isEmpty()) {
            appendLine("- (sin acciones todavía)")
        } else {
            history.forEach { appendLine("- $it") }
        }
        appendLine("ARBOL_UI_JSON:")
        appendLine(snapshot.nodesJson())
        appendLine(
            if (hasScreenshot) {
                "CAPTURA: adjunta como image/jpeg."
            } else {
                "CAPTURA: no disponible en este dispositivo/paso."
            },
        )
        appendLine(
            "Decide la siguiente herramienta. Usa índices del árbol; " +
                "si el elemento no está indexado, usa coordenadas de la captura.",
        )
    }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun finish(
        success: Boolean,
        message: String,
        onFinished: ((Boolean, String) -> Unit)?,
    ) {
        MikuLog.d(TAG, "Tarea finalizada (success=$success): $message")
        _state.update {
            it.copy(
                running = false,
                finalMessage = message,
                success = success,
                lastResult = message.take(200),
            )
        }
        job = null
        cancelNotification()
        runCatching { onFinished?.invoke(success, message) }
    }

    private fun postNotification(context: Context, goal: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Control ARTEMIS",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Miku controlando el teléfono de forma autónoma"
                },
            )
        }

        val openIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, AgentStopReceiver::class.java)
                .setAction(AgentStopReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Miku está controlando el teléfono")
            .setContentText(goal.take(90))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "DETENER", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() {
        val context = MikuAccessibilityService.instance ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private const val TAG = "Artemis"
}
