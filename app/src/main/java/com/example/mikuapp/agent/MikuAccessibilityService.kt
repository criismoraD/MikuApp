package com.example.mikuapp.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.mikuapp.util.MikuLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Servicio de accesibilidad de MikuApp.
 *
 * Responsabilidades:
 * - Extraer e indexar el árbol UI activo ([snapshot]).
 * - Mantener la referencia índice → nodo para [ActionExecutor].
 * - Capturar la pantalla como JPEG/Base64 (API 30+) como contexto visual.
 *
 * No ejecuta el bucle del agente: eso vive en [ArtemisAgentLoop] para que el
 * servicio siga siendo una capa delgada y reutilizable.
 */
class MikuAccessibilityService : AccessibilityService() {

    @Volatile
    private var nodesByIndex: Map<Int, AccessibilityNodeInfo> = emptyMap()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var agentOverlayView: TextView? = null

    val actionExecutor: ActionExecutor by lazy { ActionExecutor(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MikuLog.d(TAG, "Servicio de accesibilidad conectado")
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // El agente consulta la pantalla activamente en cada paso. No necesitamos
        // reaccionar a cada evento, solo mantener el servicio vivo.
    }

    override fun onInterrupt() {
        // Sin operaciones pendientes que interrumpir.
    }

    override fun onDestroy() {
        MikuLog.d(TAG, "Servicio de accesibilidad desconectado")
        removeAgentOverlayNow()
        if (instance === this) instance = null
        nodesByIndex = emptyMap()
        super.onDestroy()
    }

    /**
     * Muestra o actualiza el aviso flotante del agente ARTEMIS encima de
     * cualquier app (WhatsApp, Ajustes...). Usa TYPE_ACCESSIBILITY_OVERLAY,
     * por lo que no necesita el permiso de "dibujar sobre otras apps".
     */
    fun updateAgentOverlay(text: String) {
        mainHandler.post {
            if (instance !== this) return@post
            val view = agentOverlayView ?: createAgentOverlayView().also { created ->
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 150
                }
                runCatching {
                    (getSystemService(WINDOW_SERVICE) as? WindowManager)
                        ?.addView(created, params)
                }
                agentOverlayView = created
            }
            view.text = text
        }
    }

    /** Oculta el aviso flotante del agente. */
    fun hideAgentOverlay() {
        mainHandler.post { removeAgentOverlayNow() }
    }

    private fun removeAgentOverlayNow() {
        val view = agentOverlayView ?: return
        agentOverlayView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        }
    }

    private fun createAgentOverlayView(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 12.5f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setPadding(42, 26, 42, 26)
        background = GradientDrawable().apply {
            cornerRadius = 46f
            setColor(Color.argb(235, 10, 30, 34))
            setStroke(2, Color.argb(190, 82, 226, 212))
        }
        setShadowLayer(4f, 0f, 2f, Color.argb(210, 0, 0, 0))
    }

    /**
     * Recorre [rootInActiveWindow] y devuelve una lista estable de nodos
     * visibles e interactivos (o con texto relevante) indexados desde 1.
     */
    suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext null
        val activePackage = root.packageName?.toString().orEmpty()
        val packageManager = packageManager
        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(activePackage, 0),
            ).toString()
        }.getOrDefault(activePackage.ifBlank { "desconocida" })

        val metrics = resources.displayMetrics
        val collected = mutableListOf<AgentNode>()
        val newIndex = HashMap<Int, AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var visited = 0
        var index = 1
        var focusedEditable = false

        while (queue.isNotEmpty() && collected.size < MAX_NODES && visited < MAX_VISITED) {
            val node = queue.removeFirst()
            visited++

            if (!node.isVisibleToUser) continue

            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }
            if (rect.width() <= 0 || rect.height() <= 0) continue

            val text = node.text?.toString()?.trim()?.take(MAX_TEXT_LENGTH).orEmpty()
            val description = node.contentDescription
                ?.toString()
                ?.trim()
                ?.take(MAX_TEXT_LENGTH)
                .orEmpty()
            val interactive = node.isClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.isCheckable ||
                node.isLongClickable

            if (node.isEditable && node.isFocused) focusedEditable = true

            if (interactive || text.isNotBlank() || description.isNotBlank()) {
                collected += AgentNode(
                    index = index,
                    className = node.className?.toString().orEmpty(),
                    text = text,
                    contentDescription = description,
                    viewId = node.viewIdResourceName.orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    centerX = rect.centerX(),
                    centerY = rect.centerY(),
                    bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
                    packageName = node.packageName?.toString().orEmpty(),
                )
                newIndex[index] = node
                index++
            }

            for (childIndex in 0 until node.childCount) {
                runCatching { node.getChild(childIndex) }
                    .getOrNull()
                    ?.let { queue.add(it) }
            }
        }

        nodesByIndex = newIndex
        ScreenSnapshot(
            packageName = activePackage,
            appLabel = appLabel,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            focusedEditable = focusedEditable,
            nodes = collected,
        )
    }

    fun nodeAt(index: Int): AccessibilityNodeInfo? = nodesByIndex[index]

    /**
     * Captura la pantalla actual y la devuelve como JPEG en Base64.
     * Devuelve null en Android < 11 o si la captura falla / tarda demasiado.
     */
    suspend fun captureScreenshotBase64(
        maxWidth: Int = 720,
        quality: Int = 60,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val bitmap = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            takeScreenshotBitmap()
        } ?: return null

        return withContext(Dispatchers.Default) {
            encodeJpegBase64(bitmap, maxWidth, quality)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotBitmap(): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    var bitmap: Bitmap? = null
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            buffer,
                            screenshot.colorSpace,
                        )
                        bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Throwable) {
                        bitmap = null
                    } finally {
                        runCatching { buffer.close() }
                    }
                    if (continuation.isActive) continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }

            val requested = runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ContextCompat.getMainExecutor(this@MikuAccessibilityService),
                    callback,
                )
            }.isSuccess

            if (!requested && continuation.isActive) {
                continuation.resume(null)
            }
        }

    private fun encodeJpegBase64(
        source: Bitmap,
        maxWidth: Int,
        quality: Int,
    ): String? {
        var bitmap = source
        return try {
            if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    maxWidth,
                    max(1, (bitmap.height * ratio).toInt()),
                    true,
                )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        @Volatile
        var instance: MikuAccessibilityService? = null
            private set

        private const val TAG = "Accessibility"
        private const val MAX_NODES = 140
        private const val MAX_VISITED = 5_000
        private const val MAX_TEXT_LENGTH = 100
        private const val SCREENSHOT_TIMEOUT_MS = 3_500L

        /**
         * Comprueba el ajuste del sistema. [instance] puede ser null justo
         * después de volver de Ajustes; el ajuste es la fuente de verdad.
         */
        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true

            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            if (!manager.isEnabled) return false

            val expected = ComponentName(context, MikuAccessibilityService::class.java)
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    ComponentName.unflattenFromString(info.id) == expected
                }
        }

        fun openAccessibilitySettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
