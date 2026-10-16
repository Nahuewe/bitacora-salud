package ar.nahuel.bitacora.domain

import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class RoutePoint(val timeMs: Long, val lat: Double, val lon: Double) {
    val position: LatLon get() = LatLon(lat, lon)
}

enum class RouteSource { HEALTH_CONNECT, SAMSUNG_EXPORT }

data class Route(
    val id: String,
    val start: Instant,
    val end: Instant,
    val points: List<RoutePoint>,
    val source: RouteSource,
) {
    val distanceMeters: Double by lazy { points.zipWithNext { a, b -> Geo.distance(a.position, b.position) }.sum() }
}

data class Segment(
    val from: LatLon,
    val to: LatLon,
    val midTimeMs: Long,
    val speedKmh: Double,
    val heartRate: Long?,
)

data class RouteTrack(val route: Route, val segments: List<Segment>, val session: ExerciseSession?)

data class Zone(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val speedKmh: Double,
    val heartRate: Double?,
    val samples: Int,
    val passes: Int,
)

enum class MapMetric { SPEED, HEART_RATE }

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val MIN_SEGMENT_METERS = 25.0
    private const val MIN_SEGMENT_MS = 10_000L
    private const val MAX_SEGMENT_MS = 120_000L
    private const val MIN_SPEED = 1.0
    private const val MAX_SPEED = 14.0
    private const val SLACK_SECONDS = 180L

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun isValid(lat: Double, lon: Double): Boolean =
        lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    fun thin(points: List<RoutePoint>, maxPoints: Int = 2_000): List<RoutePoint> {
        if (points.size <= maxPoints) return points
        val step = points.size.toDouble() / maxPoints
        return (0 until maxPoints).map { points[(it * step).toInt()] } + points.last()
    }

    fun segments(route: Route, heartRateAt: (Long) -> Long?): List<Segment> {
        val out = mutableListOf<Segment>()
        val points = route.points
        if (points.size < 2) return out
        var anchor = points.first()
        var meters = 0.0
        for (i in 1 until points.size) {
            val current = points[i]
            meters += distance(points[i - 1].position, current.position)
            val elapsed = current.timeMs - anchor.timeMs
            if (elapsed > MAX_SEGMENT_MS) {
                anchor = current
                meters = 0.0
                continue
            }
            if (meters >= MIN_SEGMENT_METERS && elapsed >= MIN_SEGMENT_MS) {
                val speed = meters / 1000.0 / (elapsed / 3_600_000.0)
                if (speed in MIN_SPEED..MAX_SPEED) {
                    val mid = (anchor.timeMs + current.timeMs) / 2
                    out += Segment(anchor.position, current.position, mid, speed, heartRateAt(mid))
                }
                anchor = current
                meters = 0.0
            }
        }
        return out
    }

    fun zones(tracks: List<RouteTrack>, cellMeters: Double = 120.0): List<Zone> {
        val all = tracks.flatMap { track -> track.segments.map { track.route.id to it } }
        if (all.isEmpty()) return emptyList()
        val refLat = all.map { it.second.from.lat }.average()
        val latStep = cellMeters / 111_320.0
        val lonStep = cellMeters / (111_320.0 * cos(Math.toRadians(refLat)))
        return all
            .groupBy { (_, s) ->
                val lat = (s.from.lat + s.to.lat) / 2
                val lon = (s.from.lon + s.to.lon) / 2
                floor(lat / latStep).toLong() to floor(lon / lonStep).toLong()
            }
            .mapNotNull { (cell, items) ->
                val passes = items.map { it.first }.distinct().size
                if (items.size < 2) return@mapNotNull null
                val hrs = items.mapNotNull { it.second.heartRate?.toDouble() }
                Zone(
                    south = cell.first * latStep,
                    west = cell.second * lonStep,
                    north = (cell.first + 1) * latStep,
                    east = (cell.second + 1) * lonStep,
                    speedKmh = items.map { it.second.speedKmh }.average(),
                    heartRate = if (hrs.isEmpty()) null else hrs.average(),
                    samples = items.size,
                    passes = passes,
                )
            }
    }

    fun breaks(values: List<Double>, buckets: Int = 5): List<Double> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        return (1 until buckets).map { i -> sorted[((sorted.size - 1) * i.toDouble() / buckets).toInt()] }
    }

    fun bucket(value: Double, breaks: List<Double>): Int = breaks.count { value > it }

    fun matchSession(route: Route, sessions: List<ExerciseSession>): ExerciseSession? =
        sessions.firstOrNull { session ->
            !route.start.isBefore(session.start.minusSeconds(SLACK_SECONDS)) && !route.start.isAfter(session.end)
        }
}
