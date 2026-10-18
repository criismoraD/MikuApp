package com.example.mikuapp.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MikuLog {

    data class Entry(
        val time: String,
        val tag: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 1500

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val buffer = ArrayDeque<Entry>()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) {
        val updated: List<Entry>
        synchronized(lock) {
            buffer.addLast(Entry(timeFormat.format(Date()), tag, message))
            while (buffer.size > MAX_ENTRIES) {
                buffer.removeFirst()
            }
            updated = buffer.toList()
        }
        _entries.value = updated
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
        _entries.value = emptyList()
        d("Log", "Log limpiado")
    }

    fun snapshotText(): String {
        val copy = synchronized(lock) { buffer.toList() }
        return copy.joinToString(separator = "\n") { entry ->
            "${entry.time}  ${entry.tag}  ${entry.message}"
        }
    }

    fun saveToCache(context: Context): File {
        val dir = File(context.cacheDir, "debug").apply { mkdirs() }
        val file = File(dir, "miku_log_${fileFormat.format(Date())}.txt")
        file.writeText(
            buildString {
                appendLine("MikuApp - Log de depuracion")
                appendLine("Fecha: ${Date()}")
                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})")
                appendLine("----------------------------------------")
                append(snapshotText())
                appendLine()
            },
        )
        return file
    }
}
