package com.example.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda en un archivo el último cierre inesperado para poder verlo (y copiarlo) desde
 * Más → Acerca de, sin necesidad de Android Studio ni Logcat.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 7000

    private const val EXIT_FILE_NAME = "last_exit.txt"
    private const val EXIT_PREFS = "crash_reporter"
    private const val EXIT_KEY_LAST_TS = "last_exit_ts"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun exitFile(context: Context) = File(context.filesDir, EXIT_FILE_NAME)

    /** Se llama una vez al arrancar el proceso. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appContext, "Cierre inesperado (hilo ${thread.name})", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun record(context: Context, title: String, throwable: Throwable?) {
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val body = buildString {
                appendLine("$stamp — $title")
                if (throwable != null) appendLine(throwable.stackTraceToString())
            }
            file(context).writeText(body.take(MAX_CHARS))
        }
    }

    /** Para cierres del código nativo: no hay excepción, solo sabemos qué se estaba haciendo. */
    fun recordNative(context: Context, whatWasHappening: String) {
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            file(context).writeText(
                "$stamp — La app se cerró dentro del motor de voz nativo.\nEstaba haciendo: $whatWasHappening\n" +
                    "Modelo del móvil: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                    "Android ${android.os.Build.VERSION.RELEASE}, ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
            )
        }
    }

    /**
     * Android guarda por qué murió cada proceso de la app (Android 11+). Esto incluye los cierres
     * del código nativo y los cierres por falta de memoria, que NO dejan ninguna excepción.
     * Se llama una vez al arrancar, fuera del hilo principal.
     */
    fun recordPreviousProcessExit(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = context.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(EXIT_KEY_LAST_TS, 0L)
            val interesting = setOf(
                android.app.ApplicationExitInfo.REASON_CRASH,
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                android.app.ApplicationExitInfo.REASON_SIGNALED,
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY,
                android.app.ApplicationExitInfo.REASON_ANR,
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                android.app.ApplicationExitInfo.REASON_OTHER
            )
            val latest = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .filter { it.reason in interesting && it.timestamp > lastSeen }
                .maxByOrNull { it.timestamp } ?: return
            prefs.edit().putLong(EXIT_KEY_LAST_TS, latest.timestamp).apply()

            val reasonName = when (latest.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH (excepción Java)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (fallo en código nativo)"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (el sistema mató el proceso)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (sin memoria)"
                android.app.ApplicationExitInfo.REASON_ANR -> "ANR (app bloqueada)"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                else -> "OTHER"
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(latest.timestamp))
            val body = buildString {
                appendLine("Motivo según Android ($stamp): $reasonName")
                appendLine("Proceso: ${latest.processName}, estado=${latest.status}, importancia=${latest.importance}")
                appendLine("RAM en uso (PSS): ${latest.pss / 1024} MB, RSS: ${latest.rss / 1024} MB")
                if (!latest.description.isNullOrBlank()) appendLine("Detalle: ${latest.description}")
                appendLine(
                    "Móvil: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                        "Android ${android.os.Build.VERSION.RELEASE}, ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
                )
                if (latest.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    val lines = runCatching { nativeTraceHighlights(latest) }.getOrNull().orEmpty()
                    if (lines.isNotEmpty()) {
                        appendLine("Pila nativa (resumen):")
                        lines.forEach { appendLine("  $it") }
                    }
                }
            }
            exitFile(context).writeText(body.take(MAX_CHARS))
        }
    }

    /** El informe nativo (tombstone) es binario; se sacan solo las cadenas legibles que parecen pila. */
    private fun nativeTraceHighlights(info: android.app.ApplicationExitInfo): List<String> {
        val bytes = info.traceInputStream?.use { it.readBytes() } ?: return emptyList()
        val runs = mutableListOf<String>()
        val cur = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) cur.append(c.toChar()) else {
                if (cur.length >= 6) runs += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.length >= 6) runs += cur.toString()
        val interestingRuns = runs.filter {
            it.contains(".so") || it.contains("SIG") || it.contains("abort", ignoreCase = true) ||
                it.contains("sherpa", ignoreCase = true) || it.contains("onnx", ignoreCase = true) ||
                it.contains("Abort message", ignoreCase = true) || it.contains("terminate", ignoreCase = true) ||
                it.contains("what()", ignoreCase = true)
        }
        return interestingRuns.distinct().take(25).map { it.take(160) }
    }

    fun readLast(context: Context): String? {
        val crash = runCatching { file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()
        val exit = runCatching { exitFile(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()
        return when {
            crash != null && exit != null -> crash + "\n\n" + exit
            else -> crash ?: exit
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { exitFile(context).delete() }
    }
}
