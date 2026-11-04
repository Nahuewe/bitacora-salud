package ar.nahuel.bitacora.samsung

import ar.nahuel.bitacora.data.AtomicFile
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SamsungCrash(val at: Instant, val summary: String, val thread: String)

data class SamsungPause(val since: Instant, val reason: String)

@Serializable
private data class CrashDto(val v: Int, val at: Long, val summary: String, val thread: String)

@Serializable
private data class PauseDto(val v: Int, val paused: Boolean, val reason: String, val since: Long, val ackCrashAt: Long)

private val faultJson = Json { ignoreUnknownKeys = false }
private const val FORMAT_VERSION = 1
private const val MAX_SUMMARY = 300
private const val MAX_THREAD = 40
private val CLOCK_SKEW: Duration = Duration.ofDays(1)

internal fun sanitize(text: String, max: Int): String =
    text.filterNot { it.isISOControl() }.trim().take(max)

private fun <T> readJson(file: File, block: (String) -> T): T? {
    if (!file.exists() || file.length() > 16 * 1024) return null
    return try {
        block(file.readText())
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

class SamsungCrashReport(dir: File) {

    private val file = File(dir, FILE_NAME)

    fun write(thread: Thread, error: Throwable, now: Instant = Instant.now()) {
        val dto = CrashDto(FORMAT_VERSION, now.toEpochMilli(), sanitize(summary(error), MAX_SUMMARY), sanitize(thread.name, MAX_THREAD))
        AtomicFile.write(file, faultJson.encodeToString(CrashDto.serializer(), dto))
    }

    fun read(now: Instant = Instant.now()): SamsungCrash? = readJson(file) { text ->
        val dto = faultJson.decodeFromString(CrashDto.serializer(), text)
        require(dto.v == FORMAT_VERSION && dto.at > 0)
        val at = Instant.ofEpochMilli(dto.at)
        require(!at.isAfter(now.plus(CLOCK_SKEW)))
        SamsungCrash(at, sanitize(dto.summary, MAX_SUMMARY), sanitize(dto.thread, MAX_THREAD))
    }

    private companion object {
        const val FILE_NAME = "samsung_crash.json"
    }
}

class SamsungPauseStore(dir: File, private val crashes: SamsungCrashReport, private val clock: () -> Instant = Instant::now) {

    private val file = File(dir, FILE_NAME)

    private data class State(val paused: Boolean, val reason: String, val since: Instant, val ackCrashAt: Instant)

    private fun state(): State? = readJson(file) { text ->
        val dto = faultJson.decodeFromString(PauseDto.serializer(), text)
        require(dto.v == FORMAT_VERSION)
        State(dto.paused, sanitize(dto.reason, MAX_SUMMARY), Instant.ofEpochMilli(dto.since), Instant.ofEpochMilli(dto.ackCrashAt))
    }

    private fun save(state: State) {
        val dto = PauseDto(FORMAT_VERSION, state.paused, sanitize(state.reason, MAX_SUMMARY), state.since.toEpochMilli(), state.ackCrashAt.toEpochMilli())
        AtomicFile.write(file, faultJson.encodeToString(PauseDto.serializer(), dto))
    }

    @Synchronized
    fun current(): SamsungPause? {
        val now = clock()
        val state = state()
        val ack = state?.ackCrashAt ?: Instant.EPOCH
        val crash = crashes.read(now)
        if (crash != null && crash.at.isAfter(ack)) {
            val reason = crash.summary.ifBlank { "falla sin detalle" } + " · hilo ${crash.thread}"
            save(State(true, reason, crash.at, crash.at))
            return SamsungPause(crash.at, reason)
        }
        return state?.takeIf { it.paused }?.let { SamsungPause(it.since, it.reason) }
    }

    @Synchronized
    fun pause(reason: String) {
        if (current() != null) return
        val ack = state()?.ackCrashAt ?: Instant.EPOCH
        save(State(true, reason, clock(), ack))
    }

    @Synchronized
    fun resume() {
        val now = clock()
        val latestCrash = crashes.read(now)?.at ?: Instant.EPOCH
        val ack = maxOf(state()?.ackCrashAt ?: Instant.EPOCH, latestCrash)
        save(State(false, "", now, ack))
    }

    private companion object {
        const val FILE_NAME = "samsung_pause.json"
    }
}
