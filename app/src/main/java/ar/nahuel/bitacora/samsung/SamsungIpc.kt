package ar.nahuel.bitacora.samsung

import android.content.Context
import android.os.Looper
import android.os.Process
import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.HeartPoint
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RoutePoint
import ar.nahuel.bitacora.domain.RouteSource
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class DayDto(
    val date: String,
    val steps: Long,
    val distance: Double?,
    val kcal: Double?,
    val activeMin: Long,
    val sources: Map<String, Long>,
)

@Serializable
private data class SessionDto(
    val id: String,
    val start: Long,
    val end: Long,
    val type: Int,
    val kind: String,
    val title: String?,
    val distance: Double?,
    val avgHr: Long?,
    val maxHr: Long?,
    val kcal: Double?,
    val sourceApp: String,
)

@Serializable
private data class RouteDto(
    val id: String,
    val start: Long,
    val end: Long,
    val time: List<Long>,
    val lat: List<Double>,
    val lon: List<Double>,
    val heartTime: List<Long>,
    val heartBpm: List<Long>,
)

@Serializable
private data class PayloadDto(
    val days: List<DayDto>,
    val sessions: List<SessionDto>,
    val routes: List<RouteDto>,
    val failed: List<String>,
    val fault: String?,
)

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

    private const val FAULT_FILE = "samsung_fault.txt"
    private const val PAYLOAD_PREFIX = "samsung_payload_"
    private const val SEPARATOR = "|"
    private const val MAX_FAULT_CHARS = 600

    private val json = Json { ignoreUnknownKeys = true }

    fun faultFile(context: Context) = File(context.filesDir, FAULT_FILE)

    fun installProcessGuard(context: Context) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val offMain = thread !== Looper.getMainLooper().thread
            if (offMain && SamsungFaults.isSamsung(error)) {
                SamsungFaults.report(error)
                return@setDefaultUncaughtExceptionHandler
            }
            runCatching { writeFault(context, thread, error) }
            Process.killProcess(Process.myPid())
            exitProcess(EXIT_FAULT)
        }
    }

    private const val EXIT_FAULT = 10

    private fun writeFault(context: Context, thread: Thread, error: Throwable) {
        val frames = generateSequence(error) { it.cause }.last().stackTrace.take(4)
            .joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}" }
        val text = (summary(error) + "\n" + frames + "\nhilo: " + thread.name.take(40)).take(MAX_FAULT_CHARS)
        atomicWrite(faultFile(context), text)
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
        val message = raw.substringAfter(SEPARATOR, "")
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
        val dto = PayloadDto(
            days = activity.days.map {
                DayDto(it.date.toString(), it.steps, it.distanceMeters, it.activeKilocalories, it.activeMinutes, it.sources)
            },
            sessions = activity.sessions.map {
                SessionDto(
                    it.id, it.start.toEpochMilli(), it.end.toEpochMilli(), it.type, it.kind.name, it.title,
                    it.distanceMeters, it.avgHeartRate, it.maxHeartRate, it.kilocalories, it.sourceApp,
                )
            },
            routes = activity.routes.map { route ->
                RouteDto(
                    id = route.id,
                    start = route.start.toEpochMilli(),
                    end = route.end.toEpochMilli(),
                    time = route.points.map { it.timeMs },
                    lat = route.points.map { it.lat },
                    lon = route.points.map { it.lon },
                    heartTime = route.heartLog.map { it.timeMs },
                    heartBpm = route.heartLog.map { it.bpm },
                )
            },
            failed = activity.failed,
            fault = activity.fault,
        )
        val name = PAYLOAD_PREFIX + System.nanoTime() + ".json"
        atomicWrite(File(context.filesDir, name), json.encodeToString(PayloadDto.serializer(), dto))
        return name
    }

    fun readPayload(context: Context, name: String): SamsungActivity? {
        if (!name.startsWith(PAYLOAD_PREFIX) || name.contains('/') || name.contains("..")) return null
        val file = File(context.filesDir, name)
        return try {
            val dto = json.decodeFromString(PayloadDto.serializer(), file.readText())
            SamsungActivity(
                days = dto.days.map {
                    DayActivity(LocalDate.parse(it.date), it.steps, it.distance, it.kcal, it.activeMin, it.sources)
                },
                sessions = dto.sessions.map {
                    ExerciseSession(
                        id = it.id,
                        start = Instant.ofEpochMilli(it.start),
                        end = Instant.ofEpochMilli(it.end),
                        type = it.type,
                        kind = ExerciseKind.entries.firstOrNull { kind -> kind.name == it.kind } ?: ExerciseKind.OTHER,
                        title = it.title,
                        distanceMeters = it.distance,
                        avgHeartRate = it.avgHr,
                        maxHeartRate = it.maxHr,
                        kilocalories = it.kcal,
                        sourceApp = it.sourceApp,
                    )
                },
                routes = dto.routes.filter { it.lat.size == it.time.size && it.lon.size == it.time.size }.map { r ->
                    Route(
                        id = r.id,
                        start = Instant.ofEpochMilli(r.start),
                        end = Instant.ofEpochMilli(r.end),
                        points = r.time.indices.map { RoutePoint(r.time[it], r.lat[it], r.lon[it]) },
                        source = RouteSource.SAMSUNG_SDK,
                        heartLog = r.heartTime.indices.filter { it < r.heartBpm.size }.map { HeartPoint(r.heartTime[it], r.heartBpm[it]) },
                    )
                },
                failed = dto.failed,
                fault = dto.fault,
            )
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    fun clearStalePayloads(context: Context) {
        context.filesDir.listFiles { file -> file.name.startsWith(PAYLOAD_PREFIX) }?.forEach { it.delete() }
    }

    private fun atomicWrite(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }
}
