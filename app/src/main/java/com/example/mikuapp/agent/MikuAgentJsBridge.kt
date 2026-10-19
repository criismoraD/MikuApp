package com.example.mikuapp.agent

import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.mikuapp.BuildConfig
import org.json.JSONObject

/**
 * Puente opcional para una UI Web/React/Capacitor embebida en un WebView.
 *
 * Contrato JS:
 *   window.MikuAgent.startTask("abre WhatsApp y escribe a mamá")
 *   window.MikuAgent.stopTask()
 *   window.MikuAgent.getStatus()
 *   window.MikuAgent.isAccessibilityEnabled()
 *   window.MikuAgent.openAccessibilitySettings()
 *
 * SEGURIDAD: nunca adjuntar este puente a contenido remoto no confiable
 * (por ejemplo, la ventana de YouTube). Solo debe usarse con orígenes propios
 * mediante [MikuAgentBridge.attachIfTrusted].
 */
class MikuAgentJsBridge(private val context: Context) {

    @JavascriptInterface
    fun startTask(goal: String): String {
        val started = ArtemisAgentLoop.start(
            goal = goal,
            apiKey = BuildConfig.GEMINI_API_KEY,
        )
        return JSONObject()
            .put("started", started)
            .put("status", statusJson())
            .toString()
    }

    @JavascriptInterface
    fun stopTask(): String {
        ArtemisAgentLoop.stop("Detenido desde la interfaz Web")
        return statusJson()
    }

    @JavascriptInterface
    fun clearResult(): String {
        ArtemisAgentLoop.clearResult()
        return statusJson()
    }

    @JavascriptInterface
    fun getStatus(): String = statusJson()

    @JavascriptInterface
    fun isAccessibilityEnabled(): Boolean =
        MikuAccessibilityService.isServiceEnabled(context)

    @JavascriptInterface
    fun openAccessibilitySettings() {
        MikuAccessibilityService.openAccessibilitySettings(context)
    }

    private fun statusJson(): String {
        val state = ArtemisAgentLoop.state.value
        return JSONObject()
            .put("running", state.running)
            .put("goal", state.goal)
            .put("step", state.step)
            .put("maxSteps", state.maxSteps)
            .put("lastAction", state.lastAction)
            .put("lastResult", state.lastResult)
            .put("finalMessage", state.finalMessage ?: JSONObject.NULL)
            .put("success", state.success ?: JSONObject.NULL)
            .toString()
    }
}

object MikuAgentBridge {

    const val INTERFACE_NAME = "MikuAgent"

    /**
     * Adjunta el puente solo si la página es de confianza. Debe llamarse antes
     * de cargar la URL en el WebView.
     */
    fun attachIfTrusted(webView: WebView, pageUrl: String, context: Context): Boolean {
        if (!isTrustedOrigin(pageUrl)) return false
        webView.addJavascriptInterface(
            MikuAgentJsBridge(context.applicationContext),
            INTERFACE_NAME,
        )
        return true
    }

    /**
     * Orígenes aceptados: file://, localhost/127.0.0.1 y los esquemas locales
     * que usan Capacitor/Ionic (capacitor://localhost, ionic://localhost).
     */
    fun isTrustedOrigin(url: String): Boolean {
        if (url.startsWith("file://")) return true
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase().orEmpty()
        val host = parsed.host?.lowercase().orEmpty()
        if (scheme == "file") return true
        val localScheme = scheme == "http" || scheme == "https" ||
            scheme == "capacitor" || scheme == "ionic"
        return localScheme && (host == "localhost" || host == "127.0.0.1")
    }
}
