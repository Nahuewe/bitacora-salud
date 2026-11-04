package ar.nahuel.bitacora.data

import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.samsung.SamsungActivity
import ar.nahuel.bitacora.samsung.SamsungParts
import ar.nahuel.bitacora.samsung.SamsungStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMergeTest {
    private val zone = ZoneId.of("America/Argentina/Catamarca")
    private val day = LocalDate.of(2026, 10, 20)

    private fun session(id: String, minute: Long) = ExerciseSession(
        id, day.atTime(18, 0).atZone(zone).toInstant().plusSeconds(minute * 60), day.atTime(19, 0).atZone(zone).toInstant(),
        79, ExerciseKind.WALKING, null, null, null, null, null, "x",
    )

    private val hc = HealthSnapshot(
        zone,
        listOf(DayActivity(day, 6_019, 4_000.0, null, activeMinutes = 31, sources = mapOf("phone" to 6_019L))),
        listOf(session("hc-1", 0)),
        emptyList(),
        emptyList(),
    )

    private fun samsung(failed: List<String> = emptyList()) = SamsungActivity(
        listOf(DayActivity(day, 11_231, 5_000.0, null, activeMinutes = 115, sources = mapOf("x.sdk" to 11_231L))),
        listOf(session("sh:1:0", 1)),
        emptyList(),
        failed,
    )

    @Test
    fun samsungStepsAndActiveTimeWin() {
        val merged = SourceMerge.combine(hc, SamsungStatus.Ready, samsung()).snapshot.days.single()
        assertEquals(11_231L, merged.steps)
        assertEquals(115L, merged.activeMinutes)
        assertEquals(6_019L, merged.healthConnectSteps)
    }

    @Test
    fun failedStepsKeepHealthConnectSteps() {
        val combined = SourceMerge.combine(hc, SamsungStatus.Ready, samsung(listOf(SamsungParts.STEPS)))
        val merged = combined.snapshot.days.single()
        assertEquals(6_019L, merged.steps)
        assertEquals(115L, merged.activeMinutes)
        assertFalse(combined.samsungComplete)
        assertNotNull(combined.note)
    }

    @Test
    fun failedWalksKeepHealthConnectSessions() {
        val merged = SourceMerge.combine(hc, SamsungStatus.Ready, samsung(listOf(SamsungParts.WALKS))).snapshot
        assertEquals(listOf("hc-1"), merged.sessions.map { it.id })
    }

    @Test
    fun samsungIgnoredWhenNotReady() {
        val combined = SourceMerge.combine(hc, SamsungStatus.NeedsPermission, samsung())
        assertEquals(6_019L, combined.snapshot.days.single().steps)
        assertNull(combined.samsung)
        assertNull(combined.note)
    }

    @Test
    fun totalFailureFallsBackEntirely() {
        val combined = SourceMerge.combine(hc, SamsungStatus.Ready, SamsungActivity.failure("sin respuesta"))
        assertEquals(hc, combined.snapshot)
        assertTrue(combined.note.orEmpty().contains("sin respuesta"))
    }

    @Test
    fun matchingSessionsKeepHealthConnectId() {
        val merged = SourceMerge.mergeSessions(listOf(session("sh:1:0", 1)), listOf(session("hc-1", 0)))
        assertEquals(listOf("hc-1"), merged.map { it.id })
    }

    @Test
    fun repeatedSamsungSessionsCollapseToOneRow() {
        val merged = SourceMerge.mergeSessions(listOf(session("sh:1:0", 1), session("sh:1:0", 1)), emptyList())
        assertEquals(listOf("sh:1:0"), merged.map { it.id })
    }
}
