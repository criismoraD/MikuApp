package com.example.mikuapp.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Motor de acciones ARTEMIS.
 *
 * Estrategia "Dynamic-First, Coordinate-Fallback":
 * 1. Intenta la acción semántica sobre el nodo indexado.
 * 2. Si el nodo no responde, usa sus coordenadas.
 * 3. Si el modelo envió coordenadas explícitas, hace un tap directo.
 */
class ActionExecutor(private val service: MikuAccessibilityService) {

    suspend fun click(index: Int?, x: Int?, y: Int?): ActionResult = withContext(Dispatchers.Main) {
        val node = index?.let { service.nodeAt(it) }
        var failureDetail = ""

        if (node != null) {
            runCatching { node.refresh() }
            if (clickNodeOrAncestor(node)) {
                return@withContext ActionResult(
                    true,
                    "Clic accesible en el índice [$index] (${shortId(node)})",
                )
            }

            val center = nodeCenter(node)
            if (center != null) {
                delay(ACTION_SETTLE_MS)
                if (tap(center.first, center.second)) {
                    return@withContext ActionResult(
                        true,
                        "Clic por coordenadas del nodo [$index] (${center.first}, ${center.second})",
                    )
                }
            }
            failureDetail = "El nodo [$index] no aceptó clic y no pude usar sus coordenadas."
        }

        if (x != null && y != null) {
            delay(ACTION_SETTLE_MS)
            if (tap(x, y)) {
                return@withContext ActionResult(true, "Tap por coordenadas ($x, $y)")
            }
        }

        ActionResult(
            false,
            failureDetail.ifBlank {
                "No hay un índice válido ni coordenadas para tocar. Observa la pantalla y vuelve a intentarlo."
            },
        )
    }

    suspend fun typeText(index: Int, text: String, clearFirst: Boolean): ActionResult =
        withContext(Dispatchers.Main) {
            val node = service.nodeAt(index)
                ?: return@withContext ActionResult(
                    false,
                    "No existe el índice [$index] en el árbol actual. Vuelve a observar la pantalla.",
                )

            runCatching { node.refresh() }
            if (!node.isEditable) {
                return@withContext ActionResult(
                    false,
                    "El índice [$index] no es un campo editable. Busca un nodo con editable=true.",
                )
            }

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(TEXT_FOCUS_SETTLE_MS)

            val currentText = node.text?.toString().orEmpty()
            val finalText = if (clearFirst || currentText.isEmpty()) text else currentText + text
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    finalText,
                )
            }

            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                return@withContext ActionResult(
                    true,
                    "Texto escrito en el índice [$index]${if (clearFirst) " (campo limpiado)" else " (texto añadido)"}.",
                )
            }

            // Fallback para WebViews o campos que ignoran ACTION_SET_TEXT.
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Miku", finalText))
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return@withContext ActionResult(
                        true,
                        "Texto pegado en el índice [$index] usando el portapapeles.",
                    )
                }
            }

            ActionResult(
                false,
                "El campo [$index] no aceptó el texto. Intenta tocar el campo y luego escribir de nuevo.",
            )
        }

    suspend fun swipe(direction: String): ActionResult = withContext(Dispatchers.Main) {
        val width = service.resources.displayMetrics.widthPixels
        val height = service.resources.displayMetrics.heightPixels
        val coords = swipeCoordinates(direction, width, height)
            ?: return@withContext ActionResult(
                false,
                "Dirección inválida: '$direction'. Usa UP, DOWN, LEFT o RIGHT.",
            )

        val path = Path().apply {
            moveTo(coords.startX, coords.startY)
            lineTo(coords.endX, coords.endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    SWIPE_DURATION_MS,
                ),
            )
            .build()

        if (service.dispatchGesture(gesture, null, null)) {
            ActionResult(
                true,
                "Swipe ${direction.uppercase()} ejecutado " +
                    "(${coords.startX.toInt()}, ${coords.startY.toInt()}) → " +
                    "(${coords.endX.toInt()}, ${coords.endY.toInt()}).",
            )
        } else {
            ActionResult(false, "Android rechazó el gesto de swipe.")
        }
    }

    suspend fun globalAction(button: String): ActionResult = withContext(Dispatchers.Main) {
        val normalized = button.uppercase()
        val action = when (normalized) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return@withContext ActionResult(
                false,
                "Botón inválido: '$button'. Usa BACK, HOME, RECENTS o NOTIFICATIONS.",
            )
        }

        if (normalized == "NOTIFICATIONS" && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return@withContext ActionResult(
                false,
                "Abrir las notificaciones requiere Android 12 o superior.",
            )
        }

        if (service.performGlobalAction(action)) {
            ActionResult(true, "Botón del sistema $normalized presionado.")
        } else {
            ActionResult(false, "No pude ejecutar la acción global $normalized.")
        }
    }

    suspend fun openApp(appName: String): ActionResult = withContext(Dispatchers.Main) {
        if (appName.isBlank()) {
            return@withContext ActionResult(false, "Falta el nombre de la aplicación.")
        }

        val resolved = resolvePackage(appName)
            ?: return@withContext ActionResult(
                false,
                "No encontré una aplicación instalada que coincida con \"$appName\".",
            )

        val intent = service.packageManager.getLaunchIntentForPackage(resolved.first)
            ?: return@withContext ActionResult(
                false,
                "La app ${resolved.second} (${resolved.first}) no tiene una actividad de inicio.",
            )

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )

        try {
            service.startActivity(intent)
            ActionResult(true, "Abriendo ${resolved.second} (${resolved.first}).")
        } catch (t: Throwable) {
            ActionResult(
                false,
                "No pude abrir ${resolved.second}: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (depth < MAX_CLICK_ANCESTOR_DEPTH) {
            val target = current ?: break
            if (target.isClickable &&
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = runCatching { target.parent }.getOrNull()
            depth++
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
    }

    private fun nodeCenter(node: AccessibilityNodeInfo): Pair<Int, Int>? {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return rect.centerX() to rect.centerY()
    }

    private suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    TAP_DURATION_MS,
                ),
            )
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun swipeCoordinates(
        direction: String,
        width: Int,
        height: Int,
    ): SwipeCoords? {
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        return when (direction.uppercase()) {
            "UP" -> SwipeCoords(centerX, height * 0.75f, centerX, height * 0.25f)
            "DOWN" -> SwipeCoords(centerX, height * 0.28f, centerX, height * 0.75f)
            "LEFT" -> SwipeCoords(width * 0.82f, centerY, width * 0.18f, centerY)
            "RIGHT" -> SwipeCoords(width * 0.18f, centerY, width * 0.82f, centerY)
            else -> null
        }
    }

    private fun resolvePackage(query: String): Pair<String, String>? {
        val packageManager = service.packageManager
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return null

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }

        var best: Pair<String, String>? = null
        var bestScore = 0

        for (activity in activities) {
            val packageName = activity.activityInfo.packageName
            val label = runCatching { activity.loadLabel(packageManager).toString() }
                .getOrDefault(packageName)
            val normalizedLabel = normalize(label)
            val normalizedPackage = normalize(packageName)

            val score = when {
                normalizedLabel == normalizedQuery -> 100
                normalizedLabel.contains(normalizedQuery) -> 80
                normalizedQuery.contains(normalizedLabel) && normalizedLabel.length > 2 -> 60
                normalizedPackage.contains(normalizedQuery) && normalizedQuery.length > 2 -> 50
                else -> 0
            }

            if (score > bestScore) {
                bestScore = score
                best = packageName to label
            }
        }

        if (best != null) return best

        val aliasPackage = KNOWN_ALIASES.entries
            .firstOrNull { normalizedQuery.contains(it.key) }
            ?.value
            ?: return null
        if (packageManager.getLaunchIntentForPackage(aliasPackage) == null) return null

        val aliasLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(aliasPackage, 0),
            ).toString()
        }.getOrDefault(aliasPackage)
        return aliasPackage to aliasLabel
    }

    private fun shortId(node: AccessibilityNodeInfo): String {
        val id = node.viewIdResourceName
        if (!id.isNullOrBlank()) return id.substringAfterLast('/')
        return node.className?.toString()?.substringAfterLast('.') ?: "nodo"
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    private data class SwipeCoords(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
    )

    private companion object {
        const val MAX_CLICK_ANCESTOR_DEPTH = 5
        const val TAP_DURATION_MS = 80L
        const val SWIPE_DURATION_MS = 320L
        const val ACTION_SETTLE_MS = 90L
        const val TEXT_FOCUS_SETTLE_MS = 180L

        val KNOWN_ALIASES = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "wasap" to "com.whatsapp",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "navegador" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "mapas" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "correo" to "com.google.android.gm",
            "instagram" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "tiktok" to "com.zhiliaoapp.musically",
            "facebook" to "com.facebook.katana",
            "netflix" to "com.netflix.mediaclient",
            "ajustes" to "com.android.settings",
            "configuracion" to "com.android.settings",
        )
    }
}
