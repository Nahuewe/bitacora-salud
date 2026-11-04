package ar.nahuel.bitacora.sync

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SyncWindow(val start: Instant, val end: Instant, val dailyFrom: LocalDate)

object SyncPlan {

    const val INITIAL_HISTORY_DAYS = 365L
    const val RECENT_DAYS = 29L
    const val OVERLAP_DAYS = 3L
    const val DAILY_RECOMPUTE_DAYS = 10L
    private const val MIN_GUARDED_DELETES = 3

    fun window(lastSuccess: Instant?, initialSyncDone: Boolean, fullHistory: Boolean, today: LocalDate, zone: ZoneId): SyncWindow {
        val historyDays = if (fullHistory) INITIAL_HISTORY_DAYS else RECENT_DAYS
        val start = if (initialSyncDone && lastSuccess != null) {
            lastSuccess.minus(Duration.ofDays(OVERLAP_DAYS))
        } else {
            today.minusDays(historyDays).atStartOfDay(zone).toInstant()
        }
        val startDate = start.atZone(zone).toLocalDate()
        val recomputeFrom = today.minusDays(DAILY_RECOMPUTE_DAYS)
        val dailyFrom = if (startDate.isBefore(recomputeFrom)) startDate else recomputeFrom
        return SyncWindow(start, today.plusDays(1).atStartOfDay(zone).toInstant(), dailyFrom)
    }

    fun staleIds(remote: Collection<String>, local: Collection<String>): List<String>? {
        val localSet = local.toSet()
        val stale = remote.filter { it !in localSet }.distinct()
        if (stale.isEmpty()) return emptyList()
        if (localSet.isEmpty()) return null
        if (stale.size > MIN_GUARDED_DELETES && stale.size * 2 > remote.size) return null
        return stale
    }
}
