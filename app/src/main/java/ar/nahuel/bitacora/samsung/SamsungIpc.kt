package ar.nahuel.bitacora.samsung

import android.content.Context
import android.os.Looper
import android.os.Process
import ar.nahuel.bitacora.data.ActivityCodec
import ar.nahuel.bitacora.data.AtomicFile
import java.io.File
import kotlin.system.exitProcess

object SamsungIpc {
    const val PROCESS_SUFFIX = ":samsung"
    const val STATUS = 1
    const val LOAD = 2
    const val KEY_STATUS = "status"
    const val KEY_FILE = "file"
    const val KEY_ERROR = "error"
    const val KEY_FROM = "from"
    const val KEY_TO = "to"
    const val KEY_ZONE = "zone"

    private const val PAYLOAD_PREFIX = "samsung_payload_"
    private const val PAYLOAD_SUFFIX = ".json"
    private const val SEPARATOR = "|"
    private const val EXIT_FAULT = 10
    private const val MAX_PAYLOAD_BYTES = 24L * 1024 * 1024
    private val PAYLOAD_NAME = Regex("${PAYLOAD_PREFIX}[0-9]{1,20}\\$PAYLOAD_SUFFIX")

    fun installProcessGuard(context: Context) {
        val crashes = SamsungCrashReport(context.filesDir)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val offMain = thread !== Looper.getMainLooper().thread
            if (offMain && SamsungFaults.isSamsung(error)) {
                SamsungFaults.report(error)
                return@setDefaultUncaughtExceptionHandler
            }
            runCatching { crashes.write(thread, error) }
            Process.killProcess(Process.myPid())
            exitProcess(EXIT_FAULT)
        }
    }

    fun encode(status: SamsungStatus): String = when (status) {
        SamsungStatus.Ready -> "READY"
        SamsungStatus.NeedsPermission -> "PERMISSION"
        is SamsungStatus.NeedsResolution -> "RESOLUTION$SEPARATOR${status.message}"
        is SamsungStatus.Unavailable -> "UNAVAILABLE$SEPARATOR${status.message}"
        is SamsungStatus.Paused -> "PAUSED$SEPARATOR${status.message}"
    }

    fun decode(raw: String?): SamsungStatus? {
        raw ?: return null
        val kind = raw.substringBefore(SEPARATOR)
        val message = sanitize(raw.substringAfter(SEPARATOR, ""), 300)
        return when (kind) {
            "READY" -> SamsungStatus.Ready
            "PERMISSION" -> SamsungStatus.NeedsPermission
            "RESOLUTION" -> SamsungStatus.NeedsResolution(message)
            "UNAVAILABLE" -> SamsungStatus.Unavailable(message)
            "PAUSED" -> SamsungStatus.Paused(message)
            else -> null
        }
    }

    fun writePayload(context: Context, activity: SamsungActivity): String {
        val name = PAYLOAD_PREFIX + System.nanoTime().coerceAtLeast(0) + PAYLOAD_SUFFIX
        AtomicFile.write(File(context.filesDir, name), ActivityCodec.encodeSamsung(activity))
        return name
    }

    fun readPayload(context: Context, name: String): SamsungActivity? {
        if (!PAYLOAD_NAME.matches(name)) return null
        val file = File(context.filesDir, name)
        return try {
            if (file.length() > MAX_PAYLOAD_BYTES) null else ActivityCodec.decodeSamsung(file.readText())
        } catch (e: java.io.IOException) {
            null
        } finally {
            file.delete()
        }
    }

    fun clearStalePayloads(context: Context) {
        context.filesDir.listFiles { file -> PAYLOAD_NAME.matches(file.name) }?.forEach { it.delete() }
    }
}
