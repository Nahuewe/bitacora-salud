package ar.nahuel.bitacora.data

import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.HealthSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityCacheTest {
    private val zone = ZoneId.of("America/Argentina/Catamarca")
    private val today = LocalDate.of(2026, 11, 4)

    private fun day(d: LocalDate, steps: Long) = DayActivity(d, steps, null, null)
    private fun session(id: String, d: LocalDate) = ExerciseSession(
        id, d.atTime(18, 0).atZone(zone).toInstant(), d.atTime(19, 0).atZone(zone).toInstant(),
        79, ExerciseKind.WALKING, null, null, null, null, null, "x",
    )
    private fun snapshot(days: List<DayActivity>, sessions: List<ExerciseSession>) = HealthSnapshot(zone, days, sessions, emptyList(), emptyList())
    private fun cache(fullReadAt: Instant, to: LocalDate = today.minusDays(1)) =
        CachedActivity(snapshot(emptyList(), emptyList()), today.minusDays(182), to, fullReadAt, fullReadAt, emptyList(), true)

    @Test
    fun freshDataReplacesTheOverlap() {
        val old = snapshot(
            listOf(day(today.minusDays(5), 100), day(today.minusDays(2), 200)),
            listOf(session("old", today.minusDays(5)), session("deleted", today.minusDays(2))),
        )
        val fresh = snapshot(listOf(day(today.minusDays(2), 999), day(today, 50)), listOf(session("new", today)))
        val merged = ActivityCache.splice(old, fresh, today.minusDays(3), today.minusDays(182))
        assertEquals(listOf(100L, 999L, 50L), merged.days.map { it.steps })
        assertEquals(listOf("old", "new"), merged.sessions.map { it.id })
    }

    @Test
    fun oldDataIsTrimmed() {
        val old = snapshot(listOf(day(today.minusDays(400), 1)), listOf(session("ancient", today.minusDays(400))))
        val merged = ActivityCache.splice(old, snapshot(emptyList(), emptyList()), today.minusDays(3), today.minusDays(182))
        assertEquals(0, merged.days.size)
        assertEquals(0, merged.sessions.size)
    }

    @Test
    fun incrementalOnlyWhenCacheIsRecentAndSameZone() {
        val now = today.atTime(12, 0).atZone(zone).toInstant()
        assertEquals(today.minusDays(4), ActivityCache.incrementalFrom(cache(now.minusSeconds(3600)), today, now, zone.id))
        assertNull(ActivityCache.incrementalFrom(cache(now.minusSeconds(8 * 86_400)), today, now, zone.id))
        assertNull(ActivityCache.incrementalFrom(cache(now.minusSeconds(3600)), today, now, "Europe/Madrid"))
        assertNull(ActivityCache.incrementalFrom(cache(now.minusSeconds(3600), today.minusDays(40)), today, now, zone.id))
        assertNull(ActivityCache.incrementalFrom(null, today, now, zone.id))
    }
}
