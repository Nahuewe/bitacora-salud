package ar.nahuel.bitacora.data

import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.Route
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

object ActivityCache {

    const val OVERLAP_DAYS = 3L
    val FULL_READ_EVERY: Duration = Duration.ofDays(7)
    private const val MAX_GAP_DAYS = 30L

    fun incrementalFrom(cache: CachedActivity?, today: LocalDate, now: Instant, zoneId: String): LocalDate? {
        cache ?: return null
        if (cache.snapshot.zone.id != zoneId) return null
        if (Duration.between(cache.fullReadAt, now) > FULL_READ_EVERY || cache.fullReadAt.isAfter(now)) return null
        if (cache.to.isBefore(today.minusDays(MAX_GAP_DAYS)) || cache.to.isAfter(today)) return null
        return cache.to.minusDays(OVERLAP_DAYS)
    }

    fun splice(old: HealthSnapshot, fresh: HealthSnapshot, freshFrom: LocalDate, keepFrom: LocalDate): HealthSnapshot {
        val zone = fresh.zone
        val cut = freshFrom.atStartOfDay(zone).toInstant()
        val keep = keepFrom.atStartOfDay(zone).toInstant()
        return HealthSnapshot(
            zone = zone,
            days = (old.days.filter { it.date < freshFrom } + fresh.days)
                .filter { it.date >= keepFrom }
                .distinctBy { it.date }
                .sortedBy { it.date.toEpochDay() },
            sessions = (fresh.sessions + old.sessions.filter { it.start < cut })
                .filter { it.start >= keep }
                .distinctBy { it.id }
                .sortedBy { it.start },
            sleeps = (fresh.sleeps + old.sleeps.filter { it.end < cut })
                .filter { it.end >= keep }
                .distinctBy { it.id }
                .sortedBy { it.start },
            weights = fresh.weights,
        )
    }

    fun spliceRoutes(old: List<Route>, fresh: List<Route>, freshFrom: Instant, keepFrom: Instant): List<Route> =
        (fresh + old.filter { it.start < freshFrom })
            .filter { it.start >= keepFrom }
            .distinctBy { it.id }
            .sortedBy { it.start }

    fun trim(snapshot: HealthSnapshot, keepFrom: LocalDate): HealthSnapshot {
        val keep = keepFrom.atStartOfDay(snapshot.zone).toInstant()
        return snapshot.copy(
            days = snapshot.days.filter { it.date >= keepFrom },
            sessions = snapshot.sessions.filter { it.start >= keep },
            sleeps = snapshot.sleeps.filter { it.end >= keep },
        )
    }
}
