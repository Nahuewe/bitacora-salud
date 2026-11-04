package ar.nahuel.bitacora.sync

import ar.nahuel.bitacora.account.SyncMessages
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    private val zone = ZoneId.of("America/Argentina/Catamarca")
    private val today = LocalDate.of(2026, 11, 4)

    @Test
    fun firstSyncCoversHistory() {
        val window = SyncPlan.window(null, false, fullHistory = true, today, zone)
        assertEquals(today.minusDays(365), window.start.atZone(zone).toLocalDate())
        assertEquals(today.minusDays(365), window.dailyFrom)
        assertEquals(today.plusDays(1).atStartOfDay(zone).toInstant(), window.end)
    }

    @Test
    fun laterSyncsOverlapAndRecomputeTenDays() {
        val last = today.atTime(9, 0).atZone(zone).toInstant()
        val window = SyncPlan.window(last, true, fullHistory = true, today, zone)
        assertEquals(last.minusSeconds(3 * 86_400), window.start)
        assertEquals(today.minusDays(10), window.dailyFrom)
    }

    @Test
    fun staleIdsAreTheRemoteOnesMissingLocally() {
        assertEquals(listOf("b"), SyncPlan.staleIds(listOf("a", "b", "c"), listOf("a", "c", "d")))
        assertEquals(emptyList<String>(), SyncPlan.staleIds(listOf("a"), listOf("a")))
    }

    @Test
    fun suspiciousMassDeletesAreSkipped() {
        assertNull(SyncPlan.staleIds(listOf("a", "b"), emptyList()))
        assertNull(SyncPlan.staleIds((1..10).map { "r$it" }, listOf("r1")))
        assertEquals(3, SyncPlan.staleIds((1..10).map { "r$it" }, (4..10).map { "r$it" })?.size)
    }

    @Test
    fun messagesSeparateSentAndRemoved() {
        val (text, error) = SyncMessages.describe(SyncOutcome.Success(mapOf("daily_activity" to 10, "exercise_sessions" to 2, "removed" to 1)))
        assertFalse(error)
        assertTrue(text, text.contains("12 registros"))
        assertTrue(text, text.contains("1 como borrados"))
        assertTrue(SyncMessages.describe(SyncOutcome.Failed("network", true)).second)
    }
}
