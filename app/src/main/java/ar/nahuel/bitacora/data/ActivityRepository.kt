package ar.nahuel.bitacora.data

import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.health.HealthConnectSource
import ar.nahuel.bitacora.samsung.SamsungClient
import ar.nahuel.bitacora.samsung.SamsungStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ActivityData(
    val snapshot: HealthSnapshot,
    val samsungRoutes: List<Route>,
    val samsungStatus: SamsungStatus?,
    val note: String?,
    val savedAt: Instant,
    val fromCache: Boolean,
)

class ActivityRepository(
    private val health: HealthConnectSource,
    private val samsung: SamsungClient,
    private val store: ActivityStore,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Mutex()

    suspend fun cached(zone: ZoneId): ActivityData? {
        val cache = store.read()?.takeIf { it.snapshot.zone == zone } ?: return null
        return ActivityData(cache.snapshot, cache.samsungRoutes, null, null, cache.savedAt, fromCache = true)
    }

    suspend fun refresh(today: LocalDate, zone: ZoneId, full: Boolean): ActivityData = lock.withLock {
        val now = clock()
        val keepFrom = today.minusDays(DISPLAY_DAYS)
        val cache = if (full) null else store.read()
        val incrementalFrom = ActivityCache.incrementalFrom(cache, today, now, zone.id)
        val from = incrementalFrom ?: keepFrom
        val combined = read(from, today, zone)
        val freshRoutes = combined.samsung?.routes.orEmpty()

        val snapshot: HealthSnapshot
        val routes: List<Route>
        val fullReadAt: Instant
        if (cache != null && incrementalFrom != null) {
            snapshot = ActivityCache.splice(cache.snapshot, combined.snapshot, incrementalFrom, keepFrom)
            routes = ActivityCache.spliceRoutes(
                cache.samsungRoutes,
                freshRoutes,
                incrementalFrom.atStartOfDay(zone).toInstant(),
                keepFrom.atStartOfDay(zone).toInstant(),
            )
            fullReadAt = cache.fullReadAt
        } else {
            snapshot = ActivityCache.trim(combined.snapshot, keepFrom)
            routes = freshRoutes
            fullReadAt = now
        }
        store.write(CachedActivity(snapshot, keepFrom, today, now, fullReadAt, routes, combined.samsungComplete))
        ActivityData(snapshot, routes, combined.status, combined.note, now, fromCache = false)
    }

    suspend fun read(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): Combined {
        val hc = health.snapshot(from, toInclusive, zone)
        val status = samsung.status()
        val loaded = if (status == SamsungStatus.Ready) samsung.load(from, toInclusive, zone) else null
        return SourceMerge.combine(hc, status, loaded)
    }

    suspend fun invalidate() = store.clear()

    companion object {
        const val DISPLAY_DAYS = 182L
    }
}
