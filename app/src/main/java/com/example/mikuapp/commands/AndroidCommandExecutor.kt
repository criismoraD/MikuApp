package com.example.mikuapp.commands

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.URLEncoder

class AndroidCommandExecutor(private val context: Context) {

    private data class KnownApp(
        val packageName: String,
        val webUrl: String,
        val keywords: List<String>,
    )

    fun execute(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            FUNCTION_OPEN_URL -> openUrl(args.optString("url"))
            FUNCTION_OPEN_APP -> openApp(args.optString("app_name"))
            FUNCTION_SEARCH_WEB -> searchWeb(args.optString("query"))
            FUNCTION_CLOSE_APP -> goHome()
            else -> errorResult("Función no soportada: $name")
        }
    } catch (t: Throwable) {
        errorResult(t.message ?: t.javaClass.simpleName)
    }

    private fun goHome(): JSONObject {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (safeStart(intent)) {
            okResult("app" to "cierre", "modo" to "regreso a la app de Miku")
        } else {
            errorResult("No pude regresar a la app de Miku")
        }
    }

    private fun openUrl(url: String): JSONObject {
        if (url.isBlank()) return errorResult("Falta la URL")
        val normalized = normalizeUrl(url)
        return if (launchUrl(normalized)) {
            okResult("url" to normalized, "modo" to "navegador")
        } else {
            errorResult("No hay ninguna aplicación capaz de abrir $normalized")
        }
    }

    private fun searchWeb(query: String): JSONObject {
        if (query.isBlank()) return errorResult("Falta el término de búsqueda")
        val url = "https://www.google.com/search?q=${urlEncode(query)}"
        return if (launchUrl(url)) {
            okResult("query" to query, "modo" to "búsqueda web")
        } else {
            errorResult("No se pudo abrir el navegador para buscar")
        }
    }

    private fun openApp(appName: String): JSONObject {
        if (appName.isBlank()) return errorResult("Falta el nombre de la aplicación")
        val query = appName.lowercase().trim()

        val known = KNOWN_APPS.firstOrNull { app ->
            app.keywords.any { keyword -> query.contains(keyword) }
        }
        if (known != null) {
            if (isInstalled(known.packageName) && launchPackage(known.packageName)) {
                return okResult("app" to appName, "modo" to "aplicación instalada")
            }
            if (launchUrl(known.webUrl)) {
                return okResult("app" to appName, "modo" to "versión web de ${known.packageName}")
            }
        }

        val installedPackage = findInstalledAppPackage(query)
        if (installedPackage != null && launchPackage(installedPackage)) {
            return okResult("app" to appName, "paquete" to installedPackage, "modo" to "aplicación instalada")
        }

        return searchWeb(appName)
    }

    private fun isInstalled(packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    private fun launchPackage(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return safeStart(intent)
    }

    private fun launchUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return safeStart(intent)
    }

    private fun safeStart(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        false
    }

    private fun findInstalledAppPackage(query: String): String? {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }
        return activities.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            label == query || label.contains(query) || query.contains(label)
        }?.activityInfo?.packageName
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(' ') -> "https://www.google.com/search?q=${urlEncode(trimmed)}"
            else -> "https://$trimmed"
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun okResult(vararg details: Pair<String, String>): JSONObject {
        val json = JSONObject().put("result", "ok")
        for ((key, value) in details) {
            json.put(key, value)
        }
        return json
    }

    private fun errorResult(message: String): JSONObject = JSONObject()
        .put("result", "error")
        .put("message", message)

    companion object {
        const val FUNCTION_OPEN_URL = "open_url"
        const val FUNCTION_OPEN_APP = "open_app"
        const val FUNCTION_SEARCH_WEB = "search_web"
        const val FUNCTION_CLOSE_APP = "close_app"

        private val KNOWN_APPS = listOf(
            KnownApp("com.google.android.youtube", "https://m.youtube.com", listOf("youtube", "you tube")),
            KnownApp("com.whatsapp", "https://web.whatsapp.com", listOf("whatsapp", "wasap", "guasap")),
            KnownApp("com.spotify.music", "https://open.spotify.com", listOf("spotify")),
            KnownApp("com.android.chrome", "https://www.google.com", listOf("chrome", "navegador")),
            KnownApp("com.google.android.apps.maps", "https://maps.google.com", listOf("maps", "mapas")),
            KnownApp("com.google.android.gm", "https://mail.google.com", listOf("gmail", "correo")),
            KnownApp("com.instagram.android", "https://www.instagram.com", listOf("instagram")),
            KnownApp("org.telegram.messenger", "https://web.telegram.org", listOf("telegram")),
            KnownApp("com.zhiliaoapp.musically", "https://www.tiktok.com", listOf("tiktok", "tik tok")),
            KnownApp("com.facebook.katana", "https://m.facebook.com", listOf("facebook")),
            KnownApp("com.netflix.mediaclient", "https://www.netflix.com", listOf("netflix")),
            KnownApp("com.google.android.apps.photos", "https://photos.google.com", listOf("google fotos", "fotos")),
            KnownApp("com.android.vending", "https://play.google.com", listOf("play store", "tienda")),
        )
    }
}
