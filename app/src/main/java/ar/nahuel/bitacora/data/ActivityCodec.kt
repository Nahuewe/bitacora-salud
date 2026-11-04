package ar.nahuel.bitacora.data

import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.HeartPoint
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RoutePoint
import ar.nahuel.bitacora.domain.RouteSource
import ar.nahuel.bitacora.domain.SleepSession
import ar.nahuel.bitacora.domain.WeightEntry
import ar.nahuel.bitacora.samsung.SamsungActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DayDto(
    val date: String,
    val steps: Long,
    val distance: Double? = null,
    val kcal: Double? = null,
    val activeMin: Long = 0,
    val sources: Map<String, Long> = emptyMap(),
    val hcSteps: Long? = null,
)

@Serializable
internal data class SessionDto(
    val id: String,
    val start: Long,
    val end: Long,
    val type: Int,
    val kind: String,
    val title: String? = null,
    val distance: Double? = null,
    val avgHr: Long? = null,
    val maxHr: Long? = null,
    val kcal: Double? = null,
    val sourceApp: String,
)

@Serializable
internal data class SleepDto(
    val id: String,
    val start: Long,
    val end: Long,
    val deep: Long,
    val rem: Long,
    val light: Long,
    val awake: Long,
    val avgHr: Long? = null,
)

@Serializable
internal data class WeightDto(val id: String, val time: Long, val kg: Double)

@Serializable
internal data class RouteDto(
    val id: String,
    val start: Long,
    val end: Long,
    val source: String,
    val time: List<Long>,
    val lat: List<Double>,
    val lon: List<Double>,
    val heartTime: List<Long> = emptyList(),
    val heartBpm: List<Long> = emptyList(),
)

@Serializable
internal data class SamsungPayloadDto(
    val version: Int,
    val days: List<DayDto>,
    val sessions: List<SessionDto>,
    val routes: List<RouteDto>,
    val failed: List<String>,
    val fault: String? = null,
)

@Serializable
internal data class CachedActivityDto(
    val version: Int,
    val zone: String,
    val from: String,
    val to: String,
    val savedAt: Long,
    val fullReadAt: Long,
    val days: List<DayDto>,
    val sessions: List<SessionDto>,
    val sleeps: List<SleepDto>,
    val weights: List<WeightDto>,
    val samsungRoutes: List<RouteDto>,
    val samsungComplete: Boolean,
)

data class CachedActivity(
    val snapshot: HealthSnapshot,
    val from: LocalDate,
    val to: LocalDate,
    val savedAt: Instant,
    val fullReadAt: Instant,
    val samsungRoutes: List<Route>,
    val samsungComplete: Boolean,
)

object ActivityCodec {

    const val PAYLOAD_VERSION = 1
    const val CACHE_VERSION = 1
    private const val MAX_ROUTE_POINTS = 20_000

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeSamsung(activity: SamsungActivity): String = json.encodeToString(
        SamsungPayloadDto.serializer(),
        SamsungPayloadDto(
            version = PAYLOAD_VERSION,
            days = activity.days.map(::day),
            sessions = activity.sessions.map(::session),
            routes = activity.routes.map(::route),
            failed = activity.failed,
            fault = activity.fault,
        ),
    )

    fun decodeSamsung(text: String): SamsungActivity? = decode {
        val dto = json.decodeFromString(SamsungPayloadDto.serializer(), text)
        require(dto.version == PAYLOAD_VERSION)
        SamsungActivity(
            days = dto.days.map(::day),
            sessions = dto.sessions.map(::session),
            routes = dto.routes.mapNotNull(::route),
            failed = dto.failed,
            fault = dto.fault?.take(300),
        )
    }

    fun encodeCache(cache: CachedActivity): String = json.encodeToString(
        CachedActivityDto.serializer(),
        CachedActivityDto(
            version = CACHE_VERSION,
            zone = cache.snapshot.zone.id,
            from = cache.from.toString(),
            to = cache.to.toString(),
            savedAt = cache.savedAt.toEpochMilli(),
            fullReadAt = cache.fullReadAt.toEpochMilli(),
            days = cache.snapshot.days.map(::day),
            sessions = cache.snapshot.sessions.map(::session),
            sleeps = cache.snapshot.sleeps.map(::sleep),
            weights = cache.snapshot.weights.map(::weight),
            samsungRoutes = cache.samsungRoutes.map(::route),
            samsungComplete = cache.samsungComplete,
        ),
    )

    fun decodeCache(text: String): CachedActivity? = decode {
        val dto = json.decodeFromString(CachedActivityDto.serializer(), text)
        require(dto.version == CACHE_VERSION)
        val zone = ZoneId.of(dto.zone)
        CachedActivity(
            snapshot = HealthSnapshot(
                zone = zone,
                days = dto.days.map(::day),
                sessions = dto.sessions.map(::session),
                sleeps = dto.sleeps.map(::sleep),
                weights = dto.weights.map(::weight),
            ),
            from = LocalDate.parse(dto.from),
            to = LocalDate.parse(dto.to),
            savedAt = Instant.ofEpochMilli(dto.savedAt),
            fullReadAt = Instant.ofEpochMilli(dto.fullReadAt),
            samsungRoutes = dto.samsungRoutes.mapNotNull(::route),
            samsungComplete = dto.samsungComplete,
        )
    }

    private inline fun <T> decode(block: () -> T): T? = try {
        block()
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: java.time.DateTimeException) {
        null
    }

    private fun day(d: DayActivity) = DayDto(
        d.date.toString(), d.steps, d.distanceMeters, d.activeKilocalories, d.activeMinutes, d.sources, d.healthConnectSteps,
    )

    private fun day(d: DayDto) = DayActivity(
        date = LocalDate.parse(d.date),
        steps = d.steps.coerceAtLeast(0),
        distanceMeters = d.distance,
        activeKilocalories = d.kcal,
        activeMinutes = d.activeMin.coerceIn(0, 1440),
        sources = d.sources,
        healthConnectSteps = d.hcSteps,
    )

    private fun session(s: ExerciseSession) = SessionDto(
        s.id, s.start.toEpochMilli(), s.end.toEpochMilli(), s.type, s.kind.name, s.title,
        s.distanceMeters, s.avgHeartRate, s.maxHeartRate, s.kilocalories, s.sourceApp,
    )

    private fun session(s: SessionDto): ExerciseSession {
        require(s.end >= s.start)
        return ExerciseSession(
            id = s.id,
            start = Instant.ofEpochMilli(s.start),
            end = Instant.ofEpochMilli(s.end),
            type = s.type,
            kind = ExerciseKind.entries.firstOrNull { it.name == s.kind } ?: ExerciseKind.OTHER,
            title = s.title,
            distanceMeters = s.distance,
            avgHeartRate = s.avgHr,
            maxHeartRate = s.maxHr,
            kilocalories = s.kcal,
            sourceApp = s.sourceApp,
        )
    }

    private fun sleep(s: SleepSession) = SleepDto(
        s.id, s.start.toEpochMilli(), s.end.toEpochMilli(), s.deepMinutes, s.remMinutes, s.lightMinutes, s.awakeMinutes, s.avgHeartRate,
    )

    private fun sleep(s: SleepDto) = SleepSession(
        s.id, Instant.ofEpochMilli(s.start), Instant.ofEpochMilli(s.end), s.deep, s.rem, s.light, s.awake, s.avgHr,
    )

    private fun weight(w: WeightEntry) = WeightDto(w.id, w.time.toEpochMilli(), w.kilograms)

    private fun weight(w: WeightDto) = WeightEntry(w.id, Instant.ofEpochMilli(w.time), w.kg)

    private fun route(r: Route) = RouteDto(
        id = r.id,
        start = r.start.toEpochMilli(),
        end = r.end.toEpochMilli(),
        source = r.source.name,
        time = r.points.map { it.timeMs },
        lat = r.points.map { it.lat },
        lon = r.points.map { it.lon },
        heartTime = r.heartLog.map { it.timeMs },
        heartBpm = r.heartLog.map { it.bpm },
    )

    private fun route(r: RouteDto): Route? {
        val size = r.time.size
        if (size > MAX_ROUTE_POINTS || r.lat.size != size || r.lon.size != size || r.heartTime.size != r.heartBpm.size) return null
        return Route(
            id = r.id,
            start = Instant.ofEpochMilli(r.start),
            end = Instant.ofEpochMilli(r.end),
            points = r.time.indices.map { RoutePoint(r.time[it], r.lat[it], r.lon[it]) },
            source = RouteSource.entries.firstOrNull { it.name == r.source } ?: RouteSource.SAMSUNG_SDK,
            heartLog = r.heartTime.indices.map { HeartPoint(r.heartTime[it], r.heartBpm[it]) },
        )
    }
}
