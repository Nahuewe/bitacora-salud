package ar.nahuel.bitacora

import android.content.Context
import java.time.Instant

data class CrashReport(val at: Instant, val summary: String)

class CrashLog(context: Context) {

    private val prefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)

    fun install(isolate: (Thread, Throwable) -> Boolean = { _, _ -> false }) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (runCatching { isolate(thread, error) }.getOrDefault(false)) return@setDefaultUncaughtExceptionHandler
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(): CrashReport? {
        val at = prefs.getLong(KEY_AT, 0L).takeIf { it > 0 } ?: return null
        return CrashReport(Instant.ofEpochMilli(at), prefs.getString(KEY_SUMMARY, null).orEmpty())
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun record(thread: Thread, error: Throwable) {
        val root = generateSequence(error) { it.cause }.last()
        val frames = (error.stackTrace.take(4) + root.stackTrace.filter { it.className.startsWith(PACKAGE) }.take(6))
            .distinct()
            .joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        val summary = buildString {
            append(error.javaClass.simpleName).append(": ").append(error.message.orEmpty().take(MAX_MESSAGE))
            if (root !== error) append("\ncausa: ").append(root.javaClass.simpleName).append(": ").append(root.message.orEmpty().take(MAX_MESSAGE))
            append("\n").append(frames)
            append("\nhilo: ").append(thread.name.take(40))
            append("\nv").append(BuildConfig.VERSION_NAME)
        }
        prefs.edit()
            .putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_SUMMARY, summary)
            .commit()
    }

    private companion object {
        const val KEY_AT = "at"
        const val KEY_SUMMARY = "summary"
        const val PACKAGE = "ar.nahuel.bitacora"
        const val MAX_MESSAGE = 240
    }
}
